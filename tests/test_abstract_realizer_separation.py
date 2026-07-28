from __future__ import annotations

from mathproofmesh.proof_control.models import RealizerFailureType
from mathproofmesh.proof_control.realizer import AbstractRealizerController


def _structure(controller: AbstractRealizerController):
    return controller.extract_structure(
        route_id="route-a",
        source_subject_id="construction-idea",
        representation_kind="descent graph",
        components=["state", "admissible move"],
        proposed_reduction="reduce the target to a smaller admissible state",
        removable_components=["the first concrete representative"],
        preserved_constraints=["same invariant", "strict descent"],
        target_obligation_ids=["main"],
    )


def _candidate(
    controller: AbstractRealizerController,
    structure_id: str,
    construction: str,
):
    return controller.register_realizer(
        structure_id=structure_id,
        route_id="route-a",
        construction=construction,
        admissibility_conditions=["the representative lies in the domain"],
        boundary_conditions=["the lower bound is respected"],
        descent_measure="state complexity in N",
        expected_strict_decrease="complexity decreases by at least one",
        falsification_tests=["test the minimal boundary state"],
    )


def test_failed_candidate_does_not_refute_abstract_structure() -> None:
    controller = AbstractRealizerController()
    structure = _structure(controller)
    first = _candidate(
        controller, structure.structure_id, "choose the least representative"
    )

    controller.record_realizer_failure(
        first.candidate_id,
        RealizerFailureType.LOWER_BOUND,
        "the least representative violates the strict lower bound",
    )

    assert first.status == "failed"
    assert structure.status == "candidate"
    assert controller.abstract_structure_still_viable(structure.structure_id)


def test_second_realizer_can_validate_preserved_structure() -> None:
    controller = AbstractRealizerController()
    structure = _structure(controller)
    first = _candidate(
        controller, structure.structure_id, "choose the least representative"
    )
    controller.record_realizer_failure(
        first.candidate_id,
        RealizerFailureType.LOWER_BOUND,
        "the least representative violates the strict lower bound",
    )
    second = _candidate(
        controller,
        structure.structure_id,
        "choose the least admissible representative above the boundary",
    )

    controller.record_realizer_success(second.candidate_id)

    assert structure.status == "validated_structure"
    assert controller.abstract_structure_still_viable(structure.structure_id)
