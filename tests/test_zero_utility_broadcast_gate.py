from __future__ import annotations

from mathproofmesh.proof_control.controller import ProofControlLayer
from mathproofmesh.proof_control.message_utility import MessageUtilityController
from mathproofmesh.proof_control.models import (
    BroadcastDecision,
    MessageExpectedEffect,
)
from mathproofmesh.proof_control.state import ProofControlState
from mathproofmesh.schemas import (
    ClaimStatus,
    EvidenceType,
    MemoryTier,
    MessagePriority,
    MessageType,
    ObligationKind,
    ProofObligation,
)

from v07_helpers import (
    PROBLEM_HASH,
    make_broker_runtime,
    make_message,
    make_proof_control_config,
)


def _contract(controller, message, *, reduction: float = 0.0):
    return controller.register_contract(
        message,
        target_obligation_ids=["goal-g"],
        expected_effect=MessageExpectedEffect.REDUCE,
        expected_core_debt_reduction=reduction,
        current_round=1,
    )


def test_zero_utility_normal_message_kept_local() -> None:
    controller = MessageUtilityController()
    message = make_message(
        message_id="message-zero",
        route_id="route-a",
        agent_id="author-a",
        target_routes=["route-b"],
    )
    contract = _contract(controller, message)

    decision = controller.decide_broadcast(
        message,
        contract=contract,
        priority=MessagePriority.NORMAL,
        current_round=1,
    )

    assert decision.decision == BroadcastDecision.KEEP_LOCAL
    assert decision.reason == "zero_expected_cross_route_utility"
    assert not decision.consumes_neighbor_quota


def test_critical_counterexample_broadcasts_without_debt_estimate() -> None:
    controller = MessageUtilityController()
    message = make_message(
        message_id="message-counterexample",
        route_id="route-a",
        agent_id="author-a",
        target_routes=["route-b"],
        message_type=MessageType.COUNTEREXAMPLE,
        evidence_type=EvidenceType.COUNTEREXAMPLE,
        memory_tier=MemoryTier.NEGATIVE,
        status=ClaimStatus.VERIFIED,
    )

    decision = controller.decide_broadcast(
        message,
        contract=None,
        priority=MessagePriority.CRITICAL,
        current_round=1,
    )

    assert decision.decision == BroadcastDecision.BROADCAST


def test_high_priority_fact_broadcasts() -> None:
    controller = MessageUtilityController()
    message = make_message(
        message_id="message-fact",
        route_id="route-a",
        agent_id="author-a",
        target_routes=["route-b"],
        message_type=MessageType.VERIFIED_LEMMA,
        evidence_type=EvidenceType.NATURAL_PROOF_AUDITED,
        memory_tier=MemoryTier.FACT,
        status=ClaimStatus.VERIFIED,
    )
    contract = controller.register_contract(
        message,
        target_obligation_ids=["goal-g"],
        expected_effect=MessageExpectedEffect.CLOSE,
        current_round=1,
    )

    decision = controller.decide_broadcast(
        message,
        contract=contract,
        priority=MessagePriority.HIGH,
        current_round=1,
    )

    assert decision.decision == BroadcastDecision.BROADCAST


def test_positive_expected_reduction_broadcasts_normal_message() -> None:
    controller = MessageUtilityController()
    message = make_message(
        message_id="message-positive",
        route_id="route-a",
        agent_id="author-a",
        target_routes=["route-b"],
    )
    contract = _contract(controller, message, reduction=0.25)

    decision = controller.decide_broadcast(
        message,
        contract=contract,
        priority=MessagePriority.NORMAL,
        current_round=1,
    )

    assert decision.decision == BroadcastDecision.BROADCAST


def test_local_message_does_not_consume_neighbor_quota(tmp_path) -> None:
    config = make_proof_control_config(tmp_path / "runs", mode="active")
    store, registry, memory, graph, broker = make_broker_runtime(
        config,
        tmp_path / "runtime",
        route_count=2,
    )
    graph.add_obligation(
        ProofObligation(
            obligation_id="goal-g",
            problem_hash=PROBLEM_HASH,
            route_ids=["route-a", "route-b"],
            kind=ObligationKind.MAIN_GOAL,
            statement="Every admissible object satisfies the target relation.",
            normalized_statement=(
                "every admissible object satisfies the target relation"
            ),
        )
    )
    control = ProofControlLayer(
        config,
        store,
        None,
        graph,
        memory,
        broker,
        registry,
    )
    message = make_message(
        message_id="message-local-only",
        route_id="route-a",
        agent_id="author-a",
        target_routes=["route-b"],
        statement="A tentative reduction may simplify the target relation.",
    )

    broker_decision = broker.publish(
        message,
        referee_agent_id=None,
        current_round=1,
    )

    assert broker_decision.accepted
    assert broker_decision.selected_targets == []
    assert broker.delivery_record(message.message_id, "route-b") is None
    assert broker._round_global_counts[1] == 0
    broadcast = next(iter(control.state.broadcast_decisions.values()))
    assert broadcast.decision == BroadcastDecision.KEEP_LOCAL
    assert not broadcast.consumes_neighbor_quota


def test_broadcast_decision_resume_is_stable() -> None:
    state = ProofControlState()
    first = MessageUtilityController(broadcast_decisions=state.broadcast_decisions)
    message = make_message(
        message_id="message-resume",
        route_id="route-a",
        agent_id="author-a",
        target_routes=["route-b"],
    )
    contract = _contract(first, message)
    original = first.decide_broadcast(
        message,
        contract=contract,
        priority=MessagePriority.NORMAL,
        current_round=1,
    )
    restored = ProofControlState.from_state(state.export_state())
    resumed = MessageUtilityController(broadcast_decisions=restored.broadcast_decisions)

    replay = resumed.decide_broadcast(
        message,
        contract=contract,
        priority=MessagePriority.HIGH,
        current_round=8,
    )

    assert replay == original
    assert len(restored.broadcast_decisions) == 1
