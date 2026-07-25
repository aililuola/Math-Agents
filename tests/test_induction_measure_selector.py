from __future__ import annotations

from mathproofmesh.proof_control.induction import InductionMeasureSelector


def test_occurrence_barrier_selects_occurrence_count_not_plain_index() -> None:
    selector = InductionMeasureSelector()
    triggers = selector.detect_trigger(
        "Ordinary induction on n fails at the first occurrence of the feature.",
        "The same feature appears repeatedly in an earlier object.",
    )
    proposals = selector.propose_candidates(
        route_id="route-a",
        target_obligation_ids=["main"],
        trigger_features=triggers,
        hints=["use the number of occurrences"],
    )

    occurrence = next(
        item for item in proposals if item.measure_name == "occurrence_count"
    )
    assert selector.validate_well_foundedness(occurrence)
    assert occurrence.base_cases
    assert "decreas" in occurrence.strict_decrease_argument
    assert occurrence.why_natural_index_is_insufficient
    assert occurrence.target_obligation_ids == ["main"]


def test_invalid_measure_cannot_be_accepted() -> None:
    selector = InductionMeasureSelector()
    proposal = selector.propose_candidates(
        route_id="route-a",
        target_obligation_ids=["main"],
        trigger_features=["natural_index_insufficient"],
    )[0]
    proposal.strict_decrease_argument = ""

    assert selector.validate_well_foundedness(proposal) is False
