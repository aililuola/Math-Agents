from __future__ import annotations

from collections.abc import Sequence

from ..config import RealizerControlConfig
from ..schemas import stable_hash
from .models import (
    AbstractStructureProposal,
    RealizerCandidate,
    RealizerFailureType,
    RealizerRepairResult,
    RealizerRepairTask,
)


class AbstractRealizerController:
    REPAIR_OPERATORS = (
        "replace_realizer_preserve_structure",
        "minimal_admissible_realizer",
        "alternative_representative",
        "repair_boundary_conditions",
    )

    def __init__(
        self,
        config: RealizerControlConfig | None = None,
        *,
        structures: dict[str, AbstractStructureProposal] | None = None,
        candidates: dict[str, RealizerCandidate] | None = None,
        repair_tasks: dict[str, RealizerRepairTask] | None = None,
    ) -> None:
        self.config = config or RealizerControlConfig()
        self.structures = structures if structures is not None else {}
        self.candidates = candidates if candidates is not None else {}
        self.repair_tasks = repair_tasks if repair_tasks is not None else {}

    def extract_structure(
        self,
        *,
        route_id: str,
        source_subject_id: str,
        representation_kind: str,
        components: Sequence[str],
        proposed_reduction: str,
        removable_components: Sequence[str],
        preserved_constraints: Sequence[str],
        target_obligation_ids: Sequence[str],
        evidence_refs: Sequence[str] = (),
    ) -> AbstractStructureProposal:
        proposal = AbstractStructureProposal(
            route_id=route_id,
            source_subject_id=source_subject_id,
            representation_kind=representation_kind,
            components=list(components),
            proposed_reduction=proposed_reduction,
            removable_components=list(removable_components),
            preserved_constraints=list(preserved_constraints),
            target_obligation_ids=list(target_obligation_ids),
            evidence_refs=list(evidence_refs),
        )
        self.structures[proposal.structure_id] = proposal
        return proposal

    def register_realizer(
        self,
        *,
        structure_id: str,
        route_id: str,
        construction: str,
        admissibility_conditions: Sequence[str],
        boundary_conditions: Sequence[str],
        descent_measure: str,
        expected_strict_decrease: str,
        falsification_tests: Sequence[str],
        evidence_refs: Sequence[str] = (),
    ) -> RealizerCandidate:
        if structure_id not in self.structures:
            raise KeyError(structure_id)
        if self.config.require_explicit_admissibility and not admissibility_conditions:
            raise ValueError("realizer requires explicit admissibility conditions")
        if self.config.require_well_founded_descent and (
            not descent_measure or not expected_strict_decrease
        ):
            raise ValueError("realizer requires an explicit strict descent contract")
        if self.config.require_falsification_test and not falsification_tests:
            raise ValueError("realizer requires a falsification test")
        candidate = RealizerCandidate(
            structure_id=structure_id,
            route_id=route_id,
            construction=construction,
            admissibility_conditions=list(admissibility_conditions),
            boundary_conditions=list(boundary_conditions),
            descent_measure=descent_measure,
            expected_strict_decrease=expected_strict_decrease,
            falsification_tests=list(falsification_tests),
            evidence_refs=list(evidence_refs),
        )
        fingerprint = self._candidate_fingerprint(candidate)
        if any(
            self._candidate_fingerprint(item) == fingerprint
            for item in self.candidates.values()
        ):
            raise ValueError("duplicate realizer candidate")
        self.candidates[candidate.candidate_id] = candidate
        return candidate

    def record_realizer_failure(
        self,
        candidate_id: str,
        failure_type: RealizerFailureType,
        reason: str,
        *,
        evidence_refs: Sequence[str] = (),
    ) -> RealizerCandidate:
        candidate = self.candidates[candidate_id]
        candidate.status = "failed"
        candidate.failure_type = failure_type
        candidate.failure_reason = reason
        candidate.evidence_refs = list(
            dict.fromkeys([*candidate.evidence_refs, *evidence_refs])
        )
        structure = self.structures[candidate.structure_id]
        if (
            not self.config.preserve_abstract_proposal_after_candidate_failure
            and self._all_candidates_failed(candidate.structure_id)
        ):
            structure.status = "exhausted"
        return candidate

    def record_realizer_success(self, candidate_id: str) -> RealizerCandidate:
        candidate = self.candidates[candidate_id]
        candidate.status = "verified"
        candidate.failure_type = None
        candidate.failure_reason = None
        self.structures[candidate.structure_id].status = "validated_structure"
        return candidate

    def abstract_structure_still_viable(self, structure_id: str) -> bool:
        structure = self.structures[structure_id]
        if structure.status in {"refuted_structure", "exhausted"}:
            return False
        candidates = [
            item
            for item in self.candidates.values()
            if item.structure_id == structure_id
        ]
        if any(item.status == "verified" for item in candidates):
            return True
        if candidates and all(item.status == "failed" for item in candidates):
            return self.config.preserve_abstract_proposal_after_candidate_failure
        return True

    def create_repair_task(
        self,
        *,
        structure_id: str,
        failed_candidate_id: str,
        repair_operator: str,
        required_constraints: Sequence[str],
        targeted_obligation_ids: Sequence[str],
    ) -> RealizerRepairTask:
        if repair_operator not in self.REPAIR_OPERATORS:
            raise ValueError(f"unknown realizer repair operator: {repair_operator}")
        failed = self.candidates[failed_candidate_id]
        if failed.structure_id != structure_id or failed.status != "failed":
            raise ValueError("repair requires a failed candidate from the structure")
        if not failed.failure_reason or not required_constraints:
            raise ValueError("repair requires a concrete failed constraint")
        existing = [
            item
            for item in self.repair_tasks.values()
            if item.structure_id == structure_id
        ]
        if len(existing) >= self.config.max_realizer_repairs_per_structure:
            raise ValueError("realizer repair budget exhausted for structure")
        task = RealizerRepairTask(
            structure_id=structure_id,
            failed_candidate_id=failed_candidate_id,
            repair_operator=repair_operator,
            required_constraints=list(required_constraints),
            targeted_obligation_ids=list(targeted_obligation_ids),
        )
        self.repair_tasks[task.task_id] = task
        return task

    def repair_realizer(
        self,
        *,
        structure_id: str,
        failed_candidate_id: str,
        repair_operator: str,
        required_constraints: Sequence[str],
        targeted_obligation_ids: Sequence[str],
        construction: str,
        admissibility_conditions: Sequence[str],
        boundary_conditions: Sequence[str],
        descent_measure: str,
        expected_strict_decrease: str,
        falsification_tests: Sequence[str],
    ) -> RealizerRepairResult:
        task = self.create_repair_task(
            structure_id=structure_id,
            failed_candidate_id=failed_candidate_id,
            repair_operator=repair_operator,
            required_constraints=required_constraints,
            targeted_obligation_ids=targeted_obligation_ids,
        )
        try:
            candidate = self.register_realizer(
                structure_id=structure_id,
                route_id=self.candidates[failed_candidate_id].route_id,
                construction=construction,
                admissibility_conditions=admissibility_conditions,
                boundary_conditions=boundary_conditions,
                descent_measure=descent_measure,
                expected_strict_decrease=expected_strict_decrease,
                falsification_tests=falsification_tests,
            )
        except Exception:
            self.repair_tasks.pop(task.task_id, None)
            raise
        return RealizerRepairResult(task=task, candidate=candidate)

    def _all_candidates_failed(self, structure_id: str) -> bool:
        candidates = [
            item
            for item in self.candidates.values()
            if item.structure_id == structure_id
        ]
        return bool(candidates) and all(item.status == "failed" for item in candidates)

    @staticmethod
    def _candidate_fingerprint(candidate: RealizerCandidate) -> str:
        return stable_hash(
            {
                "structure_id": candidate.structure_id,
                "construction": candidate.construction,
                "admissibility_conditions": sorted(candidate.admissibility_conditions),
                "boundary_conditions": sorted(candidate.boundary_conditions),
                "descent_measure": candidate.descent_measure,
                "expected_strict_decrease": candidate.expected_strict_decrease,
            }
        )
