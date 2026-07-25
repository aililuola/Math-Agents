from __future__ import annotations

from mathproofmesh.proof_control.models import (
    ClaimGoalLink,
    GoalRelation,
    ProofRole,
    ScopeRelation,
)
from mathproofmesh.proof_control.proof_roles import ProofRoleClassifier
from mathproofmesh.schemas import (
    ClaimStatus,
    EvidenceType,
    MemoryTier,
    MessageType,
)

from v07_helpers import make_message


def _link(relation: GoalRelation) -> ClaimGoalLink:
    return ClaimGoalLink(
        subject_id="subject",
        subject_kind="message",
        target_obligation_id="main",
        relation=relation,
        scope_relation=ScopeRelation.SAME,
        alignment_confidence=1.0,
    )


def test_role_priority_covers_counterexample_equivalence_and_core_bridge() -> None:
    classifier = ProofRoleClassifier()
    counterexample = make_message(
        message_id="counterexample",
        route_id="route-a",
        agent_id="agent-a",
        statement="n=3 is an exact counterexample",
        message_type=MessageType.COUNTEREXAMPLE,
        evidence_type=EvidenceType.COUNTEREXAMPLE,
        memory_tier=MemoryTier.NEGATIVE,
        status=ClaimStatus.VERIFIED,
        confidence=1.0,
    )
    ordinary = make_message(
        message_id="subject",
        route_id="route-a",
        agent_id="agent-a",
    )

    assert (
        classifier.classify(counterexample, _link(GoalRelation.SUFFICIENT), None)
        == ProofRole.COUNTEREXAMPLE
    )
    assert (
        classifier.classify(ordinary, _link(GoalRelation.EQUIVALENT), None)
        == ProofRole.EQUIVALENT_REDUCTION
    )
    assert (
        classifier.classify(ordinary, _link(GoalRelation.SUFFICIENT), None)
        == ProofRole.CORE_BRIDGE
    )


def test_necessary_and_heuristic_are_not_core_progress() -> None:
    classifier = ProofRoleClassifier()
    ordinary = make_message(
        message_id="subject",
        route_id="route-a",
        agent_id="agent-a",
    )

    assert (
        classifier.classify(ordinary, _link(GoalRelation.NECESSARY_ONLY), None)
        == ProofRole.NECESSARY_CONDITION
    )
    assert (
        classifier.classify(ordinary, _link(GoalRelation.HEURISTIC_ONLY), None)
        == ProofRole.SEARCH_HEURISTIC
    )
