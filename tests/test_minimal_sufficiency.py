from __future__ import annotations

from mathproofmesh.proof_control.goal_alignment import MinimalSufficiencyAnalyzer
from mathproofmesh.proof_control.models import (
    ClaimGoalLink,
    GoalRelation,
    ScopeRelation,
)


def _link(
    link_id: str,
    *,
    score: float,
    scope: ScopeRelation,
) -> ClaimGoalLink:
    return ClaimGoalLink(
        link_id=link_id,
        subject_id=link_id,
        subject_kind="claim",
        target_obligation_id="main",
        relation=GoalRelation.SUFFICIENT,
        scope_relation=scope,
        implication_outline=[link_id, "main"],
        minimality_score=score,
        alignment_confidence=0.9,
    )


def test_weaker_lower_cost_sufficient_target_dominates_overstrong_target() -> None:
    analyzer = MinimalSufficiencyAnalyzer()
    strong = _link(
        "strong",
        score=0.4,
        scope=ScopeRelation.CLAIM_STRONGER,
    )
    minimal = _link("minimal", score=0.9, scope=ScopeRelation.SAME)

    assert analyzer.compare_targets(strong, minimal) is minimal
    assert analyzer.dominated_strong_targets([strong, minimal]) == [strong]


def test_weaker_bridge_is_candidate_metadata_not_goal_replacement() -> None:
    strong = _link(
        "strong",
        score=0.4,
        scope=ScopeRelation.CLAIM_STRONGER,
    )
    proposal = MinimalSufficiencyAnalyzer().propose_weaker_bridge(
        strong,
        candidate_statement="C_min",
        implication_outline=["C_min", "main"],
        required_bridge_obligation_ids=["bridge-1"],
    )

    assert proposal.status == "candidate"
    assert proposal.relation_to_original == "strictly_weaker"
    assert proposal.target_obligation_id == strong.target_obligation_id
    assert proposal.required_bridge_obligation_ids == ["bridge-1"]
