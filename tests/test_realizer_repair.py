from __future__ import annotations

import pytest

from mathproofmesh.config import RealizerControlConfig
from mathproofmesh.proof_control.models import RealizerFailureType
from mathproofmesh.proof_control.realizer import AbstractRealizerController


def _failed_controller(*, max_repairs: int = 2):
    controller = AbstractRealizerController(
        RealizerControlConfig(max_realizer_repairs_per_structure=max_repairs)
    )
    structure = controller.extract_structure(
        route_id="route-a",
        source_subject_id="source",
        representation_kind="quotient descent",
        components=["class", "representative"],
        proposed_reduction="descend on class complexity",
        removable_components=["representative"],
        preserved_constraints=["class invariant"],
        target_obligation_ids=["main"],
    )
    failed = controller.register_realizer(
        structure_id=structure.structure_id,
        route_id="route-a",
        construction="choose an arbitrary representative",
        admissibility_conditions=["representative is in the class"],
        boundary_conditions=["representative is nonzero"],
        descent_measure="class complexity in N",
        expected_strict_decrease="complexity decreases",
        falsification_tests=["test the zero boundary"],
    )
    controller.record_realizer_failure(
        failed.candidate_id,
        RealizerFailureType.ADMISSIBILITY,
        "the arbitrary representative can be zero",
    )
    return controller, structure, failed


def test_replace_realizer_preserves_structure_and_requires_falsification() -> None:
    controller, structure, failed = _failed_controller()

    repaired = controller.repair_realizer(
        structure_id=structure.structure_id,
        failed_candidate_id=failed.candidate_id,
        repair_operator="replace_realizer_preserve_structure",
        required_constraints=["representative must be nonzero"],
        targeted_obligation_ids=["main"],
        construction="choose the least nonzero representative",
        admissibility_conditions=["representative is nonzero and in the class"],
        boundary_conditions=["zero is excluded"],
        descent_measure="class complexity in N",
        expected_strict_decrease="complexity decreases",
        falsification_tests=["test the least nonzero boundary"],
    )

    assert repaired.task.failed_candidate_id == failed.candidate_id
    assert repaired.candidate.structure_id == structure.structure_id
    assert repaired.candidate.status == "candidate"
    assert repaired.candidate.falsification_tests
    assert structure.status == "candidate"


def test_repair_budget_and_duplicate_candidate_are_enforced() -> None:
    controller, structure, failed = _failed_controller(max_repairs=1)
    controller.create_repair_task(
        structure_id=structure.structure_id,
        failed_candidate_id=failed.candidate_id,
        repair_operator="repair_boundary_conditions",
        required_constraints=["exclude zero"],
        targeted_obligation_ids=["main"],
    )

    with pytest.raises(ValueError, match="repair budget exhausted"):
        controller.create_repair_task(
            structure_id=structure.structure_id,
            failed_candidate_id=failed.candidate_id,
            repair_operator="alternative_representative",
            required_constraints=["exclude zero"],
            targeted_obligation_ids=["main"],
        )

    with pytest.raises(ValueError, match="duplicate realizer candidate"):
        controller.register_realizer(
            structure_id=structure.structure_id,
            route_id="route-a",
            construction=failed.construction,
            admissibility_conditions=failed.admissibility_conditions,
            boundary_conditions=failed.boundary_conditions,
            descent_measure=failed.descent_measure,
            expected_strict_decrease=failed.expected_strict_decrease,
            falsification_tests=["a different test does not change the candidate"],
        )
