from __future__ import annotations

import re
from collections.abc import Collection
from dataclasses import dataclass
from typing import Protocol

from ..config import SystemConfig
from ..schemas import (
    ComputationMethod,
    ComputationPlan,
    ComputationPurpose,
    EvidenceType,
    ExperimentOutcome,
    ExperimentResult,
    ExperimentSpec,
    MemoryTier,
    MessageType,
    StrategyCard,
    stable_hash,
)
from .models import (
    ClaimGoalLink,
    FalsificationCompilationStatus,
    FalsificationTaskRecord,
    InferenceRiskRecord,
    TypedFalsificationContract,
)


class FastLaneContext(Protocol):
    proof_control_fast_lane: bool
    target_obligation_id: str | None
    target_claim_id: str | None
    fast_lane_tasks_this_round: int
    requested_runtime_seconds: float | None
    requested_memory_mb: int | None


@dataclass(frozen=True, slots=True)
class FastLaneEligibility:
    eligible: bool
    reason: str


@dataclass(frozen=True, slots=True)
class FalsificationDisposition:
    event_type: str
    memory_tier: MemoryTier | None
    message_type: MessageType | None
    evidence_type: EvidenceType | None
    conclusive_refutation: bool
    claim_status_changed: bool
    reason: str


class FalsificationContractCompiler:
    """Compile a narrow declarative relation without executing free-form text."""

    _NEGATED_RELATIONS = {
        "eq": "ne",
        "ne": "eq",
        "lt": "ge",
        "le": "gt",
        "gt": "le",
        "ge": "lt",
    }

    def compile(
        self,
        request_text: str,
        *,
        target_subject_id: str,
        max_cases: int = 4096,
    ) -> TypedFalsificationContract:
        normalized = " ".join(request_text.split())
        identity = {
            "target_subject_id": target_subject_id,
            "request_text": normalized,
            "max_cases": max_cases,
        }
        contract_id = f"falsification_contract_{stable_hash(identity)[:20]}"
        match = FalsificationTaskMaterializer._FINITE_RELATION.fullmatch(normalized)
        if match is None:
            looks_structured = normalized.casefold().startswith(("check ", "test "))
            return TypedFalsificationContract(
                contract_id=contract_id,
                target_subject_id=target_subject_id,
                max_cases=max_cases,
                expected_if_found="Record an exact counterexample and invalidate only its target.",
                expected_if_not_found=(
                    "Retain an unverified or bounded Insight; do not verify the Claim."
                ),
                status=(
                    FalsificationCompilationStatus.NEEDS_REWRITE
                    if looks_structured
                    else FalsificationCompilationStatus.NON_AUTOMATABLE
                ),
                compile_reason=(
                    "The request resembles a finite test but lacks a complete typed "
                    "interval or relation."
                    if looks_structured
                    else "No registered deterministic finite relation was identified."
                ),
            )
        lower = int(match.group("lower"))
        upper = int(match.group("upper"))
        cases = max(0, upper - lower + 1)
        relation = FalsificationTaskMaterializer._RELATIONS[match.group("relation")]
        if upper < lower or cases > max_cases:
            return TypedFalsificationContract(
                contract_id=contract_id,
                target_subject_id=target_subject_id,
                parameters=[{"name": match.group("variable"), "type": "integer"}],
                finite_domains={match.group("variable"): {"min": lower, "max": upper}},
                exact_relation={
                    "lhs": match.group("lhs").strip(),
                    "rhs": match.group("rhs").strip(),
                    "relation": relation,
                },
                max_cases=max_cases,
                expected_if_found="Record an exact counterexample.",
                expected_if_not_found="Do not infer a universal theorem.",
                status=FalsificationCompilationStatus.NEEDS_REWRITE,
                compile_reason=(
                    "The finite domain is empty or exceeds the declared case bound."
                ),
            )
        exact_relation = {
            "lhs": match.group("lhs").strip(),
            "rhs": match.group("rhs").strip(),
            "relation": relation,
        }
        return TypedFalsificationContract(
            contract_id=contract_id,
            target_subject_id=target_subject_id,
            parameters=[{"name": match.group("variable"), "type": "integer"}],
            finite_domains={match.group("variable"): {"min": lower, "max": upper}},
            exact_relation=exact_relation,
            counterexample_predicate={
                **exact_relation,
                "relation": self._NEGATED_RELATIONS[relation],
            },
            registered_handler="bounded_integer_search",
            max_cases=max_cases,
            expected_if_found=(
                "Record an exact counterexample and invalidate only the targeted Claim."
            ),
            expected_if_not_found=(
                "Record bounded not-refuted evidence as Insight; do not verify the Claim."
            ),
            status=FalsificationCompilationStatus.EXECUTABLE,
            compile_reason="A registered exact finite integer relation was compiled.",
        )


class FalsificationTaskMaterializer:
    """Compile only an explicit finite typed relation into the fast lane."""

    _FINITE_RELATION = re.compile(
        r"^\s*(?:check|test)\s+"
        r"(?P<variable>[A-Za-z][A-Za-z0-9_]*)\s+in\s*"
        r"\[\s*(?P<lower>-?\d+)\s*,\s*(?P<upper>-?\d+)\s*\]\s*:\s*"
        r"(?P<lhs>.+?)\s*(?P<relation><=|>=|==|!=|=|<|>)\s*"
        r"(?P<rhs>.+?)\s*$",
        re.IGNORECASE,
    )
    _RELATIONS = {
        "=": "eq",
        "==": "eq",
        "!=": "ne",
        "<": "lt",
        "<=": "le",
        ">": "gt",
        ">=": "ge",
    }

    def __init__(self, config: SystemConfig) -> None:
        self.config = config

    def from_strategy(
        self,
        strategy: StrategyCard,
        *,
        target_claim: str,
        target_obligation_id: str | None,
        target_claim_id: str | None = None,
        route_id: str | None = None,
    ) -> FalsificationTaskRecord:
        return self._materialize(
            source_kind="strategy",
            source_record_id=strategy.strategy_id,
            strategy_id=strategy.strategy_id,
            route_id=route_id,
            target_obligation_id=target_obligation_id,
            target_claim_id=target_claim_id,
            request_text=strategy.falsification_test,
            target_claim=target_claim,
        )

    def from_goal_link(
        self,
        link: ClaimGoalLink,
        *,
        request_text: str,
        target_claim: str,
        route_id: str | None = None,
    ) -> FalsificationTaskRecord:
        return self._materialize(
            source_kind="goal_link",
            source_record_id=link.link_id,
            strategy_id=link.subject_id if link.subject_kind == "strategy" else None,
            route_id=route_id,
            target_obligation_id=link.target_obligation_id,
            target_claim_id=link.subject_id if link.subject_kind == "claim" else None,
            request_text=request_text,
            target_claim=target_claim,
        )

    def from_inference_risk(
        self,
        risk: InferenceRiskRecord,
        *,
        request_text: str,
        target_claim: str,
        target_obligation_id: str | None,
    ) -> FalsificationTaskRecord:
        return self._materialize(
            source_kind="inference_risk",
            source_record_id=risk.risk_id,
            strategy_id=None,
            route_id=risk.route_id,
            target_obligation_id=target_obligation_id,
            target_claim_id=risk.subject_id,
            request_text=request_text,
            target_claim=target_claim,
        )

    def _materialize(
        self,
        *,
        source_kind: str,
        source_record_id: str,
        strategy_id: str | None,
        route_id: str | None,
        target_obligation_id: str | None,
        target_claim_id: str | None,
        request_text: str,
        target_claim: str,
    ) -> FalsificationTaskRecord:
        identity = {
            "source_kind": source_kind,
            "source_record_id": source_record_id,
            "request_text": request_text,
            "target_obligation_id": target_obligation_id,
            "target_claim_id": target_claim_id,
        }
        task = FalsificationTaskRecord(
            task_id=f"falsification_task_{stable_hash(identity)[:16]}",
            source_kind=source_kind,
            source_record_id=source_record_id,
            strategy_id=strategy_id,
            route_id=route_id,
            target_obligation_id=target_obligation_id,
            target_claim_id=target_claim_id,
            request_text=request_text,
        )
        match = self._FINITE_RELATION.fullmatch(request_text.strip())
        if match is None:
            task.status = "deferred"
            task.deferred_reason = (
                "Automatic fast-lane admission requires an explicit finite integer "
                "interval and a typed arithmetic relation."
            )
            return task
        lower = int(match.group("lower"))
        upper = int(match.group("upper"))
        if upper < lower:
            task.status = "deferred"
            task.deferred_reason = "The declared finite interval has upper < lower."
            return task
        cases = upper - lower + 1
        lane = self.config.topology.proof_control.falsification_fast_lane
        if cases > lane.max_cases:
            task.status = "deferred"
            task.deferred_reason = (
                "The declared finite interval exceeds the proof-control case limit."
            )
            return task
        variable = match.group("variable")
        spec = ExperimentSpec(
            experiment_id=f"experiment_{stable_hash(identity)[:16]}",
            purpose=ComputationPurpose.FALSIFY_CLAIM,
            target_claim=target_claim,
            reasoning_basis=(
                "The strategy supplied an explicit finite boundary falsification test."
            ),
            why_computation_is_needed=(
                "A typed exact search can find a decisive counterexample cheaply."
            ),
            decision_if_confirmed=(
                "Retain only bounded not-refuted evidence and continue the proof."
            ),
            decision_if_refuted=(
                "Invalidate the targeted claim and revise or abandon the route."
            ),
            noncomputational_alternative=(
                "Ask an independent route agent to construct an exact counterexample."
            ),
            method=ComputationMethod.BOUNDED_INTEGER_SEARCH,
            domains={variable: {"min": lower, "max": upper}},
            arguments={
                "target": {
                    "lhs": match.group("lhs").strip(),
                    "rhs": match.group("rhs").strip(),
                    "relation": self._RELATIONS[match.group("relation")],
                }
            },
            exact_arithmetic=True,
            broad_search=False,
            max_cases=cases,
            requested_by="proof_control",
        )
        task.experiment_spec = spec
        task.computation_plan = ComputationPlan.from_spec(spec)
        return task


def evaluate_fast_lane_eligibility(
    spec: ExperimentSpec,
    context: FastLaneContext,
    config: SystemConfig,
    *,
    registered_methods: Collection[ComputationMethod],
) -> FastLaneEligibility:
    """Check the narrow proof-control lane without weakening broker hard limits."""

    control = config.topology.proof_control
    lane = control.falsification_fast_lane
    checks: tuple[tuple[bool, str], ...] = (
        (
            control.enabled and control.mode == "active",
            "proof control is not active",
        ),
        (lane.enabled, "the proof-control falsification lane is disabled"),
        (
            context.proof_control_fast_lane,
            "the request was not explicitly marked for the proof-control lane",
        ),
        (
            spec.purpose in lane.allowed_purposes,
            "the computation purpose is not eligible for falsification fast lane",
        ),
        (
            spec.method in registered_methods,
            "the method is not a registered typed computation",
        ),
        (
            spec.method != ComputationMethod.SANDBOXED_PYTHON,
            "sandboxed Python is forbidden in the falsification fast lane",
        ),
        (
            not lane.exact_arithmetic_only or spec.exact_arithmetic,
            "the falsification fast lane requires exact arithmetic",
        ),
        (not spec.broad_search, "broad search cannot use the falsification fast lane"),
        (
            spec.max_cases <= lane.max_cases,
            "the request exceeds the proof-control case limit",
        ),
        (
            context.fast_lane_tasks_this_round < lane.max_tasks_per_round,
            "the proof-control per-round fast-lane limit is exhausted",
        ),
        (
            context.requested_runtime_seconds is None
            or context.requested_runtime_seconds <= lane.max_runtime_seconds,
            "the request exceeds the proof-control runtime limit",
        ),
        (
            context.requested_memory_mb is None
            or context.requested_memory_mb <= lane.max_memory_mb,
            "the request exceeds the proof-control memory limit",
        ),
        (
            bool(context.target_obligation_id or context.target_claim_id),
            "the request does not identify a claim or obligation",
        ),
        (
            len(spec.decision_if_refuted.strip()) >= 12,
            "the request does not state a concrete action if refuted",
        ),
        (
            not lane.auto_fact_promotion,
            "fast-lane results may not be promoted automatically to Fact",
        ),
    )
    for passed, reason in checks:
        if not passed:
            return FastLaneEligibility(False, reason)
    return FastLaneEligibility(
        True,
        (
            "Admitted as an exact, bounded falsification task. A negative search "
            "remains bounded evidence and never becomes a Fact automatically."
        ),
    )


def classify_falsification_result(
    result: ExperimentResult,
) -> FalsificationDisposition:
    """Describe the only permitted side effects of a fast-lane result."""

    if (
        result.outcome == ExperimentOutcome.COUNTEREXAMPLE_FOUND
        and result.counterexample is not None
        and result.exact_arithmetic
        and result.independently_verified
    ):
        return FalsificationDisposition(
            event_type="falsification_fast_lane_counterexample",
            memory_tier=MemoryTier.NEGATIVE,
            message_type=MessageType.COUNTEREXAMPLE,
            evidence_type=EvidenceType.COUNTEREXAMPLE,
            conclusive_refutation=True,
            claim_status_changed=False,
            reason=(
                "An exact independently reproduced counterexample is eligible for "
                "NegativeMemory and a Counterexample message after normal broker review."
            ),
        )
    if result.outcome in {
        ExperimentOutcome.NOT_REFUTED,
        ExperimentOutcome.CERTIFIED,
    }:
        return FalsificationDisposition(
            event_type="falsification_fast_lane_inconclusive",
            memory_tier=MemoryTier.INSIGHT,
            message_type=MessageType.COMPUTATION_CERTIFICATE,
            evidence_type=EvidenceType.BOUNDED_EXPERIMENT,
            conclusive_refutation=False,
            claim_status_changed=False,
            reason=(
                "No exact counterexample was found in the declared bounded scope; "
                "the result remains Insight and does not verify the target claim."
            ),
        )
    return FalsificationDisposition(
        event_type="falsification_fast_lane_inconclusive",
        memory_tier=None,
        message_type=None,
        evidence_type=None,
        conclusive_refutation=False,
        claim_status_changed=False,
        reason="The tool was inconclusive or failed, so no claim or memory state changes.",
    )
