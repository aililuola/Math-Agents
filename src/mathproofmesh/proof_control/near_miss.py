from __future__ import annotations

from collections.abc import Sequence
from typing import Any

from ..config import NearMissControlConfig
from ..schemas import VerificationReport, VerificationVerdict, stable_hash
from .models import NearMissRecord


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
        salvage = list(dict.fromkeys([*preserved_properties, *salvageable_components]))
        if self.config.extraction_requires_salvageable_component and not salvage:
            return None
        issue_constraints = [
            item.description for item in report.issues if item.description
        ]
        constraints = list(dict.fromkeys([*failed_constraints, *issue_constraints]))
        first_type = (
            report.issues[0].phase if report.issues else report.failure_level.value
        )
        repair_hints = [item.repair_hint for item in report.issues if item.repair_hint]
        operators = list(dict.fromkeys([*suggested_repair_operators, *repair_hints]))
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
