from __future__ import annotations

from collections.abc import Sequence
from typing import Any

from ..config import NearMissControlConfig
from ..schemas import VerificationReport, VerificationVerdict, stable_hash
from .models import NearMissRecord, ProcessFailureDiagnostic

_PROCESS_PHASE_MARKERS = (
    "budget",
    "checkpoint",
    "cooldown",
    "json",
    "network",
    "parse",
    "protocol",
    "provider",
    "reviewer",
    "schema",
    "serialization",
)
_PROCESS_TEXT_MARKERS = (
    "entire proof is incomplete",
    "final answer is empty",
    "whole proof is incomplete",
    "budget exhausted",
    "checkpoint format",
    "json format",
    "network failure",
    "provider cooldown",
    "reviewer call failed",
)
_EXECUTION_MARKERS = (
    "budget",
    "cooldown",
    "network",
    "provider",
    "reviewer",
)


class NearMissLedger:
    def __init__(
        self,
        config: NearMissControlConfig | None = None,
        *,
        records: dict[str, NearMissRecord] | None = None,
    ) -> None:
        self.config = config or NearMissControlConfig()
        self.records = records if records is not None else {}
        self.repaired_ids: set[str] = set()

    def extract_deterministic(
        self,
        report: VerificationReport,
        *,
        route_id: str,
        source_target_id: str | None = None,
        target_obligation_id: str | None = None,
        abstract_idea: str,
        concrete_candidate: str,
        preserved_properties: Sequence[str],
        failed_constraints: Sequence[str] = (),
        salvageable_components: Sequence[str] = (),
        suggested_repair_operators: Sequence[str] = (),
        suggested_induction_measures: Sequence[str] = (),
    ) -> NearMissRecord | None:
        if report.verdict not in {
            VerificationVerdict.FAIL,
            VerificationVerdict.UNCERTAIN,
        }:
            return None
        if not report.problem_integrity_ok:
            return None
        if report.confidence < self.config.min_verifier_confidence:
            return None
        if not report.first_error_step and not report.issues:
            return None
        if self._process_reason(report) is not None:
            return None
        if not target_obligation_id or not target_obligation_id.strip():
            return None
        if not self._specific_mathematical_text(abstract_idea, report):
            return None
        if not self._specific_mathematical_text(concrete_candidate, report):
            return None
        salvage = list(dict.fromkeys([*preserved_properties, *salvageable_components]))
        if self.config.extraction_requires_salvageable_component and not salvage:
            return None
        issue_constraints = [
            item.description for item in report.issues if item.description
        ]
        constraints = list(dict.fromkeys([*failed_constraints, *issue_constraints]))
        if not constraints:
            return None
        first_type = (
            report.issues[0].phase if report.issues else report.failure_level.value
        )
        repair_hints = [item.repair_hint for item in report.issues if item.repair_hint]
        repair_module, default_operator = self._repair_route(report)
        operators = list(
            dict.fromkeys(
                [
                    *suggested_repair_operators,
                    *repair_hints,
                    default_operator,
                ]
            )
        )
        if not operators:
            return None
        return NearMissRecord(
            near_miss_id=(
                "near_miss_"
                + stable_hash(
                    {
                        "route_id": route_id,
                        "target": source_target_id or report.target_id,
                        "first_error": report.first_error_step,
                        "constraints": constraints,
                    }
                )[:12]
            ),
            route_id=route_id,
            target_obligation_id=target_obligation_id,
            source_target_id=source_target_id or report.target_id,
            abstract_idea=abstract_idea,
            concrete_candidate=concrete_candidate,
            preserved_properties=list(preserved_properties),
            failed_constraints=constraints,
            first_failure_type=first_type,
            salvageable_components=salvage,
            suggested_repair_operators=operators,
            suggested_induction_measures=list(suggested_induction_measures),
            verifier_report_ids=[report.report_id],
            verifier_confidence=report.confidence,
            repair_module=repair_module,
        )

    def process_diagnostic(
        self,
        report: VerificationReport,
        *,
        route_id: str,
        target_obligation_id: str | None = None,
    ) -> ProcessFailureDiagnostic | None:
        reason = self._process_reason(report)
        if reason is None:
            return None
        normalized = reason.casefold()
        domain = (
            "execution"
            if any(marker in normalized for marker in _EXECUTION_MARKERS)
            else "process"
        )
        return ProcessFailureDiagnostic(
            diagnostic_id=(
                "process_failure_"
                + stable_hash(
                    {
                        "report_id": report.report_id,
                        "route_id": route_id,
                        "target_obligation_id": target_obligation_id,
                        "reason": reason,
                    }
                )[:12]
            ),
            source_report_id=report.report_id,
            route_id=route_id,
            target_obligation_id=target_obligation_id,
            domain=domain,
            reason=reason,
            evidence=[
                item.description for item in report.issues if item.description.strip()
            ],
        )

    @staticmethod
    def _specific_mathematical_text(
        value: str,
        report: VerificationReport,
    ) -> bool:
        normalized = " ".join(value.split()).casefold()
        if len(normalized) < 8:
            return False
        if normalized == report.target_id.casefold():
            return False
        return normalized not in {
            "preserve the route mechanism before the first failed step",
            "repair the proof",
            "complete the proof",
            "try again",
        }

    @staticmethod
    def _process_reason(report: VerificationReport) -> str | None:
        phases = " ".join(item.phase for item in report.issues).casefold()
        descriptions = " ".join(
            [
                report.concise_feedback,
                *(item.description for item in report.issues),
            ]
        ).casefold()
        if any(marker in phases for marker in _PROCESS_PHASE_MARKERS):
            return report.concise_feedback
        if any(marker in descriptions for marker in _PROCESS_TEXT_MARKERS):
            return report.concise_feedback
        return None

    @staticmethod
    def _repair_route(report: VerificationReport) -> tuple[str, str]:
        semantic_failure = " ".join(
            [
                *(item.phase for item in report.issues),
                *(item.description for item in report.issues),
            ]
        ).casefold()
        if any(
            marker in semantic_failure
            for marker in (
                "admissib",
                "boundary",
                "degener",
                "lower bound",
                "lower-bound",
                "upper bound",
                "upper-bound",
                "realizer",
            )
        ):
            return (
                "realizer_repair",
                "repair_candidate_under_failed_constraint",
            )
        if any(
            marker in semantic_failure
            for marker in (
                "first occurrence",
                "first-occurrence",
                "induct",
                "recurrence",
                "repeated feature",
                "structural recurrence",
            )
        ):
            return (
                "induction_selector",
                "select_well_founded_measure_and_split_base_case",
            )
        if any(
            marker in semantic_failure
            for marker in (
                "implication",
                "logical gap",
                "logical_gap",
                "missing bridge",
            )
        ):
            return (
                "minimal_bridge",
                "materialize_minimal_implication_bridge",
            )
        if any(
            marker in semantic_failure
            for marker in (
                "quantifier",
                "scope",
                "target mismatch",
            )
        ):
            return (
                "scope_goal_rewrite",
                "rewrite_scope_or_rebind_goal",
            )
        return (
            "bounded_local_repair",
            "repair_first_failed_mathematical_step",
        )

    async def extract_ambiguous(
        self,
        *,
        runner: Any,
        prompt_factory: Any,
        context: dict[str, Any],
    ) -> NearMissRecord | None:
        if not self.config.allow_model_extraction_on_ambiguous:
            return None
        result = await runner.call(
            "route_referee",
            prompt_factory.extract_near_miss(**context),
        )
        artifact = getattr(result, "artifact", result)
        return artifact if isinstance(artifact, NearMissRecord) else None

    def add(self, record: NearMissRecord) -> NearMissRecord:
        existing = self.records.get(record.near_miss_id)
        if existing is not None:
            return existing
        if self.config.max_records == 0:
            raise ValueError("near-miss storage is disabled")
        if len(self.records) >= self.config.max_records:
            oldest_key = next(iter(self.records))
            self.records.pop(oldest_key)
            self.repaired_ids.discard(oldest_key)
        self.records[record.near_miss_id] = record
        return record

    def relevant_for_route(
        self,
        route_id: str,
        *,
        target_obligation_ids: Sequence[str] = (),
    ) -> list[NearMissRecord]:
        targets = set(target_obligation_ids)
        records = [
            item
            for item in self.records.values()
            if item.route_id == route_id
            and item.near_miss_id not in self.repaired_ids
            and (
                not targets
                or item.target_obligation_id is None
                or item.target_obligation_id in targets
            )
        ]
        return sorted(
            records,
            key=lambda item: (
                -item.verifier_confidence,
                item.near_miss_id,
            ),
        )[: self.config.max_route_context_items]

    def mark_repaired(self, near_miss_id: str) -> None:
        if near_miss_id not in self.records:
            raise KeyError(near_miss_id)
        self.repaired_ids.add(near_miss_id)
