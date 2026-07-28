from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Iterable, Literal, Sequence

from .computation.broker import ToolBroker
from .computation.contracts import validate_experiment_contract
from .computation.policy import ComputationContext
from .config import SystemConfig
from .schemas import (
    CalculationGateRecord,
    CalculationGateVerdict,
    ComputationDecisionStatus,
    ComputationMethod,
    ComputationPurpose,
    EvidenceRef,
    EvidenceStrength,
    ExperimentOutcome,
    ExperimentSpec,
    ProofStep,
    StrategyCard,
    ToolRequest,
    stable_hash,
)
from .store import ArtifactStore


_ASSERTION_METHODS = {
    ComputationMethod.SYMPY_EQUIVALENT,
    ComputationMethod.MODULAR_EXHAUSTIVE,
    ComputationMethod.BOUNDED_INTEGER_SEARCH,
    ComputationMethod.GRAPH_CERTIFICATE,
    ComputationMethod.RECURRENCE_CHECK,
    ComputationMethod.BOUNDED_GREEDY_SEQUENCE,
    ComputationMethod.CANDIDATE_PERIOD_CHECK,
    ComputationMethod.EXACT_GEOMETRY,
    ComputationMethod.LEAN_CHECK,
}
_NUMBER_ATOM = r"[+-]?\d+(?:\.\d+)?(?:/\d+)?"
_NUMBER_LIST_RE = re.compile(
    rf"(?<![\w.]){_NUMBER_ATOM}(?:\s*[,，、;；]\s*{_NUMBER_ATOM}){{2,}}"
)
_NUMBER_RE = re.compile(_NUMBER_ATOM)
_RELATION_RE = re.compile(r"[=<>]|\u2264|\u2265|\u2260|\u2261")
_RESULT_MARKERS = (
    "\u8ba1\u7b97\u5f97",
    "\u8ba1\u7b97\u5f97\u5230",
    "\u679a\u4e3e\u5f97",
    "\u679a\u4e3e\u5f97\u5230",
    "\u6700\u5c0f\u503c\u4e3a",
    "\u6700\u5c0f\u6b63\u6574\u6570\u4e3a",
    "\u5468\u671f\u4e3a",
    "\u524d\u51e0\u9879\u4e3a",
    "\u524d\u9879\u4e3a",
    "calculation gives",
    "computed as",
    "computes to",
    "direct computation",
    "enumeration gives",
    "minimum is",
    "least value is",
    "has period",
    "period is",
    "first terms are",
    "first values are",
)
_COMPUTATION_PROVENANCE_MARKERS = (
    "\u8ba1\u7b97\u5f97",
    "\u8ba1\u7b97\u5f97\u5230",
    "\u679a\u4e3e\u5f97",
    "\u679a\u4e3e\u5f97\u5230",
    "\u7a0b\u5e8f\u8ba1\u7b97",
    "\u76f4\u63a5\u8ba1\u7b97",
    "\u6709\u9650\u679a\u4e3e",
    "calculation gives",
    "computed as",
    "computes to",
    "direct computation",
    "enumeration gives",
    "computed by",
    "finite enumeration",
)
_SEQUENCE_LIST_MARKERS = (
    "\u524d\u51e0\u9879",
    "\u524d\u9879",
    "\u6570\u5217\u524d",
    "\u6709\u9650\u524d\u7f00",
    "first terms are",
    "first values are",
    "sequence prefix",
    "finite prefix",
)


@dataclass(slots=True)
class CalculationGateBatch:
    records: list[CalculationGateRecord] = field(default_factory=list)

    @property
    def passed(self) -> bool:
        return all(
            record.verdict == CalculationGateVerdict.PASSED for record in self.records
        )

    @property
    def failures(self) -> list[CalculationGateRecord]:
        return [
            record
            for record in self.records
            if record.verdict != CalculationGateVerdict.PASSED
        ]

    def concise_failure(self) -> str:
        if not self.failures:
            return ""
        first = self.failures[0]
        suffix = (
            f" ({len(self.failures)} calculation gate failures)"
            if len(self.failures) > 1
            else ""
        )
        return f"{first.scope_id}: {first.reason}{suffix}"


def calculation_trigger(texts: Iterable[str]) -> str | None:
    """Detect only high-precision signs that a concrete computed value is load-bearing."""

    for raw_text in texts:
        text = (raw_text or "").strip()
        if not text:
            continue
        folded = text.casefold()
        has_computation_provenance = any(
            marker in folded for marker in _COMPUTATION_PROVENANCE_MARKERS
        )
        if _NUMBER_LIST_RE.search(text) and (
            has_computation_provenance
            or any(marker in folded for marker in _SEQUENCE_LIST_MARKERS)
        ):
            return "an explicit computed sequence or finite-prefix value list"
        if has_computation_provenance and _NUMBER_RE.search(text):
            return "an explicit numeric result attributed to finite computation"
    return None


class CriticalCalculationGate:
    """Execute declared finite checks before they can become route premises."""

    def __init__(
        self,
        config: SystemConfig,
        tools: ToolBroker,
        store: ArtifactStore,
    ) -> None:
        self.config = config
        self.tools = tools
        self.store = store

    @property
    def enabled(self) -> bool:
        cfg = self.config.computation
        return bool(
            cfg.enabled
            and cfg.typed_tools_enabled
            and cfg.critical_calculation_gate_enabled
        )

    def evaluate_strategy(
        self,
        strategy: StrategyCard,
        *,
        path_id: str,
        requested_by: str,
    ) -> CalculationGateBatch:
        strategy.calculation_evidence_refs = []
        if not self.enabled:
            return CalculationGateBatch()
        trigger = calculation_trigger(
            [
                strategy.core_idea,
                strategy.key_original_step or "",
                *strategy.expected_lemmas,
                *strategy.prerequisites,
                *(claim.statement for claim in strategy.critical_claims),
            ]
        )
        batch = self._evaluate_scope(
            scope_type="strategy",
            scope_id=strategy.strategy_id,
            path_id=path_id,
            parent_checkpoint_id=None,
            requested_by=requested_by,
            checks=strategy.calculation_checks,
            trigger=trigger,
        )
        strategy.calculation_evidence_refs = self._passed_evidence(batch.records)
        return batch

    def evaluate_steps(
        self,
        steps: Sequence[ProofStep],
        *,
        scope_type: Literal["proof_step", "final_step"],
        path_id: str,
        parent_checkpoint_id: str | None,
        requested_by: str,
    ) -> CalculationGateBatch:
        records: list[CalculationGateRecord] = []
        seen: dict[tuple[str, str], list[CalculationGateRecord]] = {}
        for step in steps:
            step.calculation_evidence_refs = []
            trigger = calculation_trigger(
                [step.statement, step.justification, *step.calculations]
            )
            signature = stable_hash(
                {
                    "step_id": step.step_id,
                    "checks": [
                        check.model_dump(mode="json", exclude={"request_id"})
                        for check in step.calculation_checks
                    ],
                    "trigger": trigger,
                }
            )
            key = (step.step_id, signature)
            if key in seen:
                step.calculation_evidence_refs = self._passed_evidence(seen[key])
                continue
            batch = self._evaluate_scope(
                scope_type=scope_type,
                scope_id=step.step_id,
                path_id=path_id,
                parent_checkpoint_id=parent_checkpoint_id,
                requested_by=requested_by,
                checks=step.calculation_checks,
                trigger=trigger,
            )
            seen[key] = batch.records
            records.extend(batch.records)
            step.calculation_evidence_refs = self._passed_evidence(batch.records)
        return CalculationGateBatch(records=records)

    def _evaluate_scope(
        self,
        *,
        scope_type: Literal["strategy", "proof_step", "final_step"],
        scope_id: str,
        path_id: str,
        parent_checkpoint_id: str | None,
        requested_by: str,
        checks: Sequence[ToolRequest],
        trigger: str | None,
    ) -> CalculationGateBatch:
        cfg = self.config.computation
        if trigger and not checks and cfg.critical_calculation_require_declarations:
            record = CalculationGateRecord(
                scope_type=scope_type,
                scope_id=scope_id,
                path_id=path_id,
                trigger=trigger,
                verdict=CalculationGateVerdict.MISSING_DECLARATION,
                reason=(
                    "A route-critical explicit calculation was detected, but no "
                    "typed calculation_checks request was declared."
                ),
            )
            self._persist(record)
            return CalculationGateBatch(records=[record])
        if len(checks) > cfg.critical_calculation_max_checks_per_artifact:
            record = CalculationGateRecord(
                scope_type=scope_type,
                scope_id=scope_id,
                path_id=path_id,
                trigger=trigger or "declared typed checks",
                verdict=CalculationGateVerdict.INVALID_CONTRACT,
                reason=(
                    f"{len(checks)} checks exceed the per-artifact limit "
                    f"{cfg.critical_calculation_max_checks_per_artifact}."
                ),
            )
            self._persist(record)
            return CalculationGateBatch(records=[record])

        records = [
            self._evaluate_request(
                scope_type=scope_type,
                scope_id=scope_id,
                path_id=path_id,
                parent_checkpoint_id=parent_checkpoint_id,
                requested_by=requested_by,
                request=request,
                trigger=trigger or "a typed calculation check was explicitly declared",
            )
            for request in checks
        ]
        return CalculationGateBatch(records=records)

    def _evaluate_request(
        self,
        *,
        scope_type: Literal["strategy", "proof_step", "final_step"],
        scope_id: str,
        path_id: str,
        parent_checkpoint_id: str | None,
        requested_by: str,
        request: ToolRequest,
        trigger: str,
    ) -> CalculationGateRecord:
        try:
            spec = self._compile_spec(
                scope_type=scope_type,
                scope_id=scope_id,
                path_id=path_id,
                parent_checkpoint_id=parent_checkpoint_id,
                requested_by=requested_by,
                request=request,
            )
        except (TypeError, ValueError) as exc:
            record = CalculationGateRecord(
                scope_type=scope_type,
                scope_id=scope_id,
                path_id=path_id,
                trigger=trigger,
                request=request,
                verdict=CalculationGateVerdict.INVALID_CONTRACT,
                reason=str(exc),
            )
            self._persist(record)
            return record

        issues = validate_experiment_contract(spec)
        issues.extend(self._gate_contract_issues(spec))
        if issues:
            record = CalculationGateRecord(
                scope_type=scope_type,
                scope_id=scope_id,
                path_id=path_id,
                trigger=trigger,
                request=request,
                experiment_id=spec.experiment_id,
                request_hash=spec.request_hash,
                verdict=CalculationGateVerdict.INVALID_CONTRACT,
                reason="Invalid critical calculation contract: " + "; ".join(issues),
            )
            self._persist(record)
            return record

        decision = self.tools.decide(
            spec,
            ComputationContext(
                path_id=path_id,
                stalled_rounds=0,
                meta_review_approved=True,
                remaining_llm_calls=0,
                mandatory_calculation_gate=True,
            ),
        )
        self.tools.cache.register_aliases(
            spec.request_hash,
            request.request_id,
            spec.experiment_id,
        )
        if decision.decision != ComputationDecisionStatus.ALLOW:
            record = CalculationGateRecord(
                scope_type=scope_type,
                scope_id=scope_id,
                path_id=path_id,
                trigger=trigger,
                request=request,
                experiment_id=spec.experiment_id,
                request_hash=spec.request_hash,
                verdict=CalculationGateVerdict.INCONCLUSIVE,
                reason=(
                    "The mandatory check was not admitted: "
                    f"{decision.rule_id}: {decision.reason}"
                ),
            )
            self._persist(record)
            return record

        result = self.tools.run_experiment(spec, decision)
        if result.outcome == ExperimentOutcome.COUNTEREXAMPLE_FOUND:
            verdict = CalculationGateVerdict.REFUTED
            reason = (
                "The deterministic checker refuted the declared finite calculation: "
                f"{result.counterexample}"
            )
        elif result.error or result.outcome in {
            ExperimentOutcome.ERROR,
            ExperimentOutcome.INCONCLUSIVE,
        }:
            verdict = CalculationGateVerdict.INCONCLUSIVE
            reason = result.error or "The deterministic check was inconclusive."
        elif (
            result.exact_arithmetic
            and result.evidence_strength != EvidenceStrength.HEURISTIC
            and result.outcome
            in {ExperimentOutcome.CERTIFIED, ExperimentOutcome.NOT_REFUTED}
        ):
            verdict = CalculationGateVerdict.PASSED
            reason = (
                "The declared finite calculation received exact typed evidence. "
                "Bounded evidence certifies only its stated finite scope."
            )
        else:
            verdict = CalculationGateVerdict.INCONCLUSIVE
            reason = (
                "The result lacks exact non-heuristic evidence and cannot support "
                "a route premise."
            )
        record = CalculationGateRecord(
            scope_type=scope_type,
            scope_id=scope_id,
            path_id=path_id,
            trigger=trigger,
            request=request,
            experiment_id=result.experiment_id,
            request_hash=result.request_hash,
            result_hash=result.result_hash,
            verdict=verdict,
            reason=reason,
            evidence_refs=list(result.artifact_refs),
        )
        self._persist(record)
        return record

    def _compile_spec(
        self,
        *,
        scope_type: str,
        scope_id: str,
        path_id: str,
        parent_checkpoint_id: str | None,
        requested_by: str,
        request: ToolRequest,
    ) -> ExperimentSpec:
        method = ComputationMethod(request.kind)
        if method not in _ASSERTION_METHODS:
            raise ValueError(
                f"{method.value} is not an assertion-checking typed method accepted "
                "by the critical calculation gate"
            )
        arguments = dict(request.arguments)
        legacy_domains = arguments.pop("domains", {})
        raw_domains = request.domains or legacy_domains
        if not isinstance(raw_domains, dict):
            raise ValueError("calculation check domains must be a JSON object")
        raw_max_cases = arguments.pop("max_cases", request.max_cases)
        if isinstance(raw_max_cases, bool):
            raise ValueError("calculation check max_cases must be an integer")
        max_cases = min(
            int(raw_max_cases),
            self.config.computation.max_cases_per_experiment,
        )
        identity = stable_hash(
            {
                "scope_type": scope_type,
                "scope_id": scope_id,
                "request": request.model_dump(mode="json", exclude={"request_id"}),
            }
        )[:20]
        # ToolRequest.purpose is free text; the documented discovery marker is
        # the literal enum value. Ignoring it misjudged exploratory requests
        # as assertion-mode and demanded claimed_values they cannot have.
        purpose = (
            ComputationPurpose.DISCOVER_PATTERN
            if request.purpose.strip().casefold()
            == ComputationPurpose.DISCOVER_PATTERN.value
            else ComputationPurpose.CHECK_DERIVED_IDENTITY
        )
        return ExperimentSpec(
            experiment_id=f"critical_calc_{identity}",
            purpose=purpose,
            target_claim=f"Finite calculation assertion: {request.purpose}",
            assumptions=[],
            reasoning_basis=(
                "This explicit finite calculation is load-bearing for route admission."
            ),
            why_computation_is_needed=(
                "A deterministic typed checker can prevent a hand-calculation error."
            ),
            decision_if_confirmed=(
                "Allow only the finite assertion to remain available for review."
            ),
            decision_if_refuted=(
                "Block the route premise and return the affected step for correction."
            ),
            noncomputational_alternative=(
                "Replace the computed premise with a complete symbolic derivation."
            ),
            method=method,
            domains=raw_domains,
            arguments=arguments,
            exact_arithmetic=True,
            broad_search=purpose == ComputationPurpose.DISCOVER_PATTERN,
            max_cases=max_cases,
            seed=self.config.runtime.random_seed,
            requested_by=requested_by,
            path_id=path_id,
            parent_checkpoint_id=parent_checkpoint_id,
        )

    @staticmethod
    def _gate_contract_issues(spec: ExperimentSpec) -> list[str]:
        if (
            spec.method == ComputationMethod.RECURRENCE_CHECK
            and "claimed_expression" not in spec.arguments
        ):
            return ["recurrence_check requires claimed_expression at the critical gate"]
        return []

    @staticmethod
    def _passed_evidence(
        records: Sequence[CalculationGateRecord],
    ) -> list[EvidenceRef]:
        refs: list[EvidenceRef] = []
        seen: set[tuple[str, str | None]] = set()
        for record in records:
            if record.verdict != CalculationGateVerdict.PASSED:
                continue
            for ref in record.evidence_refs:
                key = (ref.artifact_ref, ref.content_hash)
                if key in seen:
                    continue
                seen.add(key)
                refs.append(ref)
        return refs

    def _persist(self, record: CalculationGateRecord) -> None:
        name = (
            "critical_calculation_gate_"
            + stable_hash(
                {
                    "scope_type": record.scope_type,
                    "scope_id": record.scope_id,
                    "path_id": record.path_id,
                    "request": (
                        record.request.model_dump(mode="json", exclude={"request_id"})
                        if record.request is not None
                        else None
                    ),
                }
            )[:24]
        )
        self.store.write_json("structured", name, record)
        self.store.append_event("critical_calculation_gate_completed", record)
