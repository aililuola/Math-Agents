from __future__ import annotations

from mathproofmesh.proof_control.goal_alignment import GoalAlignmentAnalyzer
from mathproofmesh.proof_control.models import (
    GoalRelation,
    IndexScope,
    ScopeRelation,
    ScopeSignature,
)
from mathproofmesh.proof_graph.store import ProofGraphStore
from mathproofmesh.schemas import (
    GraphEdgeType,
    ObligationKind,
    ProofGraphEdge,
    ProofObligation,
)

from v07_helpers import PROBLEM_HASH, make_message


def _target() -> ProofObligation:
    return ProofObligation(
        obligation_id="main-goal",
        problem_hash=PROBLEM_HASH,
        route_ids=["route-a"],
        kind=ObligationKind.MAIN_GOAL,
        statement="For every n, P(n) holds.",
        normalized_statement="for every n, p(n) holds.",
    )


def _all_scope(subject_id: str) -> ScopeSignature:
    return ScopeSignature(
        subject_id=subject_id,
        index_scope=IndexScope.ALL,
        normalization_confidence=1.0,
    )


def test_same_canonical_statement_and_scope_is_equivalent() -> None:
    target = _target()
    message = make_message(
        message_id="claim-a",
        route_id="route-a",
        agent_id="agent-a",
        statement="For every n, P(n) holds.",
    )
    link = GoalAlignmentAnalyzer().assess_message(
        message,
        target,
        subject_scope=_all_scope(message.message_id),
        target_scope=_all_scope(target.obligation_id),
    )

    assert link.relation == GoalRelation.EQUIVALENT
    assert link.scope_relation == ScopeRelation.SAME
    assert link.implication_outline == ["claim-a", "main-goal"]


def test_lexical_overlap_alone_is_not_sufficiency() -> None:
    target = _target()
    message = make_message(
        message_id="claim-a",
        route_id="route-a",
        agent_id="agent-a",
        statement="P(n) resembles the target but only for sampled n.",
    )
    link = GoalAlignmentAnalyzer().assess_message(
        message,
        target,
        subject_scope=_all_scope(message.message_id),
        target_scope=_all_scope(target.obligation_id),
    )

    assert link.relation == GoalRelation.UNKNOWN
    assert link.implication_outline == []


def test_graph_implication_is_sufficient_and_reverse_is_necessary() -> None:
    graph = ProofGraphStore(problem_hash=PROBLEM_HASH)
    target = graph.add_obligation(_target())
    message = make_message(
        message_id="claim-a",
        route_id="route-a",
        agent_id="agent-a",
        statement="A verified bridge premise.",
    )
    graph.add_claim_node(message)
    graph.add_edge(
        ProofGraphEdge(
            source_id=message.message_id,
            target_id=target.obligation_id,
            edge_type=GraphEdgeType.IMPLIES,
        )
    )
    analyzer = GoalAlignmentAnalyzer(graph)

    sufficient = analyzer.assess_message(
        message,
        target,
        subject_scope=_all_scope(message.message_id),
        target_scope=_all_scope(target.obligation_id),
    )
    assert sufficient.relation == GoalRelation.SUFFICIENT

    reverse_graph = ProofGraphStore(problem_hash=PROBLEM_HASH)
    reverse_target = reverse_graph.add_obligation(_target())
    reverse_message = make_message(
        message_id="claim-b",
        route_id="route-a",
        agent_id="agent-a",
        statement="A consequence of the target.",
    )
    reverse_graph.add_claim_node(reverse_message)
    reverse_graph.add_edge(
        ProofGraphEdge(
            source_id=reverse_target.obligation_id,
            target_id=reverse_message.message_id,
            edge_type=GraphEdgeType.IMPLIES,
        )
    )
    necessary = GoalAlignmentAnalyzer(reverse_graph).assess_message(
        reverse_message,
        reverse_target,
        subject_scope=_all_scope(reverse_message.message_id),
        target_scope=_all_scope(reverse_target.obligation_id),
    )
    assert necessary.relation == GoalRelation.NECESSARY_ONLY
