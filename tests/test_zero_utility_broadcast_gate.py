from __future__ import annotations

from mathproofmesh.proof_control.message_utility import MessageUtilityController
from mathproofmesh.proof_control.models import (
    BroadcastDecision,
    MessageExpectedEffect,
)
from mathproofmesh.schemas import (
    ClaimStatus,
    EvidenceType,
    MemoryTier,
    MessagePriority,
    MessageType,
)

from v07_helpers import make_message


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
