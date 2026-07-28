from __future__ import annotations

import pytest

from mathproofmesh.proof_control.message_utility import MessageUtilityController
from mathproofmesh.proof_control.models import MessageExpectedEffect
from mathproofmesh.proof_graph.store import ProofGraphStore
from mathproofmesh.schemas import (
    ClaimStatus,
    EvidenceType,
    MemoryTier,
    MessageType,
    ObligationKind,
    ProofObligation,
)

from v07_helpers import PROBLEM_HASH, make_message


def _graph() -> ProofGraphStore:
    graph = ProofGraphStore(problem_hash=PROBLEM_HASH)
    graph.add_obligation(
        ProofObligation(
            obligation_id="bridge-1",
            problem_hash=PROBLEM_HASH,
            route_ids=["route-b"],
            kind=ObligationKind.LEMMA,
            statement="prove bridge B",
            normalized_statement="prove bridge b",
        )
    )
    return graph


def _cross_route_message():
    return make_message(
        message_id="message-a",
        route_id="route-a",
        agent_id="agent-a",
        target_routes=["route-b"],
        statement="A candidate bridge lemma",
    )


def test_contract_requires_known_target_and_explicit_effect() -> None:
    controller = MessageUtilityController(proof_graph=_graph())
    message = _cross_route_message()
    contract = controller.register_contract(
        message,
        target_obligation_ids=["bridge-1"],
        expected_effect=MessageExpectedEffect.CLOSE,
        current_round=2,
    )

    assert contract.message_id == message.message_id
    assert contract.target_obligation_ids == ["bridge-1"]
    assert controller.requires_contract(message)

    with pytest.raises(ValueError, match="unknown obligations"):
        controller.register_contract(
            message.model_copy(update={"message_id": "message-b"}),
            target_obligation_ids=["missing"],
            expected_effect=MessageExpectedEffect.REDUCE,
            current_round=2,
        )


def test_delivery_without_verified_mathematical_use_has_zero_utility() -> None:
    controller = MessageUtilityController(proof_graph=_graph())
    message = _cross_route_message()
    controller.register_contract(
        message,
        target_obligation_ids=["bridge-1"],
        expected_effect=MessageExpectedEffect.CLOSE,
        current_round=0,
    )

    receipt = controller.record_usage(
        message_id=message.message_id,
        consumer_route_id="route-b",
        referenced_step_ids=["model-claimed-step"],
        closed_obligation_ids=["model-claimed-obligation"],
        verified_step_ids=[],
        actually_closed_obligation_ids=[],
    )

    assert receipt.verified_use is False
    assert receipt.utility_score == 0.0
    assert controller.route_utility("route-b") == 0.0


def test_trusted_verified_artifacts_create_usage_credit() -> None:
    controller = MessageUtilityController(proof_graph=_graph())
    message = _cross_route_message()
    controller.register_contract(
        message,
        target_obligation_ids=["bridge-1"],
        expected_effect=MessageExpectedEffect.CLOSE,
        current_round=0,
    )

    receipt = controller.record_usage(
        message_id=message.message_id,
        consumer_route_id="route-b",
        referenced_step_ids=["step-verified"],
        closed_obligation_ids=["bridge-1"],
        verified_step_ids=["step-verified"],
        actually_closed_obligation_ids=["bridge-1"],
    )

    assert receipt.verified_use is True
    assert receipt.referenced_step_ids == ["step-verified"]
    assert receipt.closed_obligation_ids == ["bridge-1"]
    assert controller.route_utility("route-b") > 0.0


def test_expired_unused_contract_counts_as_no_use() -> None:
    controller = MessageUtilityController(proof_graph=_graph())
    message = _cross_route_message()
    contract = controller.register_contract(
        message,
        target_obligation_ids=["bridge-1"],
        expected_effect=MessageExpectedEffect.REDUCE,
        current_round=0,
    )

    assert controller.expire_contracts(contract.expires_round + 1) == [
        contract.contract_id
    ]
    assert controller.no_use_count("route-a") == 1


def test_counterexample_is_contract_exempt() -> None:
    message = make_message(
        message_id="counterexample",
        route_id="route-a",
        agent_id="agent-a",
        target_routes=["route-b"],
        statement="n=4 refutes the claim",
        message_type=MessageType.COUNTEREXAMPLE,
        evidence_type=EvidenceType.COUNTEREXAMPLE,
        memory_tier=MemoryTier.NEGATIVE,
        status=ClaimStatus.VERIFIED,
        confidence=1.0,
    )

    assert MessageUtilityController().requires_contract(message) is False
