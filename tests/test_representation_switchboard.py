from __future__ import annotations

from mathproofmesh.inspiration.representation_switchboard import (
    RepresentationSwitchboard,
)
from mathproofmesh.schemas import ObligationKind, ProblemContract, ProofObligation


def test_geometry_switchboard_generates_auditable_nonmechanical_candidates() -> None:
    problem = ProblemContract(
        exact_statement="In triangle ABC prove the stated circle incidence.",
        normalized_statement="triangle circle incidence",
    )
    obligation = ProofObligation(
        problem_hash=problem.integrity_hash,
        route_ids=["geometry-route"],
        kind=ObligationKind.SUBGOAL,
        statement="prove the circle incidence",
        normalized_statement="prove circle incidence",
    )
    switchboard = RepresentationSwitchboard()
    candidates = switchboard.generate(
        problem, [obligation], domain="geometry", max_candidates=3
    )
    assert [item.representation_name for item in candidates] == [
        "synthetic_geometry",
        "coordinate_geometry",
        "complex_plane",
    ]
    for candidate in candidates:
        assert switchboard.validate_candidate(candidate) == []
        assert candidate.object_mapping
        assert candidate.failure_risks
        assert candidate.fast_failure_tests
        assert candidate.novelty_signature.targeted_obligation_ids == [
            obligation.obligation_id
        ]


def test_existing_representation_is_skipped_when_alternatives_exist() -> None:
    from mathproofmesh.schemas import NoveltySignature

    problem = ProblemContract(
        exact_statement="For integers prove a divisibility claim.",
        normalized_statement="integer divisibility",
    )
    names = RepresentationSwitchboard().applicable_representations(
        problem,
        domain="number_theory",
        existing_signatures=[
            NoveltySignature(representation_tags=["modular_congruence"])
        ],
    )
    assert "modular_congruence" not in names
    assert "p_adic_valuation" in names
