from __future__ import annotations

from mathproofmesh.broker_phase import record_verified_message_usage
from mathproofmesh.communication.receipts import build_receipt
from mathproofmesh.proof_control.controller import ProofControlLayer
from mathproofmesh.proof_control.models import (
    ControlActionStatus,
    ControlActionType,
)
from mathproofmesh.schemas import (
    ClaimStatus,
    EvidenceType,
    MemoryTier,
    MessageType,
    ProofDelta,
    ProofStep,
)

from v07_helpers import (
    PROBLEM_HASH,
    make_broker_runtime,
    make_fact,
    make_message,
    make_proof_control_config,
)


def _active_runtime(tmp_path):
    config = make_proof_control_config(tmp_path / "runs", mode="active")
    return config, make_broker_runtime(config, tmp_path / "runtime")


def _low_message(identifier: str):
    return make_message(
        message_id=identifier,
        route_id="route-a",
        agent_id="author-a",
        statement=f"Route-local failure summary {identifier}.",
        target_routes=["route-b"],
        message_type=MessageType.FAILURE_RECORD,
        evidence_type=EvidenceType.UNVERIFIED_IDEA,
        memory_tier=MemoryTier.INSIGHT,
        status=ClaimStatus.PROPOSED,
    )


def test_high_priority_fact_reserves_message_slot(tmp_path) -> None:
    config, runtime = _active_runtime(tmp_path)
    config.topology.cross_route.max_messages_per_route_per_round = 2
    _store, _registry, _memory, _graph, broker = runtime

    broker.publish(_low_message("low-a"), referee_agent_id=None, current_round=1)
    broker.publish(_low_message("low-b"), referee_agent_id=None, current_round=1)
    fact = make_fact(message_id="high-fact", target_routes=["route-b"])
    decision = broker.publish(
        fact,
        referee_agent_id="referee-a",
        current_round=1,
    )

    assert decision.accepted
    assert decision.selected_targets == ["route-b"]
    delivery = broker.delivery_record(fact.message_id, "route-b")
    assert delivery is not None
    assert delivery["priority"] == "high"


def test_route_update_turn_created_when_no_natural_opportunity(tmp_path) -> None:
    config, runtime = _active_runtime(tmp_path)
    store, registry, memory, graph, broker = runtime
    fact = make_fact(message_id="update-fact", target_routes=["route-b"])
    broker.publish(fact, referee_agent_id="referee-a", current_round=1)
    control = ProofControlLayer(
        config,
        store,
        None,
        graph,
        memory,
        broker,
        registry,
    )

    actions = control.schedule_pending_route_updates(current_round=1)

    assert len(actions) == 1
    assert actions[0].action_type == ControlActionType.SCHEDULE_ROUTE_UPDATE
    assert actions[0].status == ControlActionStatus.EXECUTED
    task = next(iter(control.state.route_update_tasks.values()))
    assert task.target_route_id == "route-b"
    assert task.message_ids == [fact.message_id]
    delivery = broker.delivery_record(fact.message_id, "route-b")
    assert delivery is not None
    assert delivery["delivery_state"] == "scheduled"
    assert delivery["processing_opportunities"] == 0


def test_delivery_without_opportunity_not_counted_as_consumed(tmp_path) -> None:
    _config, runtime = _active_runtime(tmp_path)
    _store, _registry, _memory, _graph, broker = runtime
    fact = make_fact(
        message_id="no-opportunity",
        target_routes=["route-b"],
        ttl_rounds=1,
    )
    broker.publish(fact, referee_agent_id="referee-a", current_round=1)

    broker.expire(current_round=3)

    delivery = broker.delivery_record(fact.message_id, "route-b")
    assert delivery is not None
    assert delivery["delivery_state"] == "expired_without_opportunity"
    assert delivery["prompt_consumed"] is False
    assert broker.utility_for_route("route-b") == 0.0


def test_fact_delivery_produces_semantic_receipt(tmp_path) -> None:
    _config, runtime = _active_runtime(tmp_path)
    _store, _registry, _memory, _graph, broker = runtime
    fact = make_fact(message_id="receipt-fact", target_routes=["route-b"])
    broker.publish(fact, referee_agent_id="referee-a", current_round=1)

    delivered = broker.inbox("route-b", current_round=1)
    receipt = build_receipt(fact, "route-b", delivered_round=1)
    broker.acknowledge(receipt)

    assert delivered == [fact]
    assert receipt.status.value == "accepted"
    assert receipt.semantic_hash == fact.expected_semantic_hash()
    delivery = broker.delivery_record(fact.message_id, "route-b")
    assert delivery is not None
    assert delivery["delivery_state"] == "acknowledged"


def test_mathematical_use_requires_verified_downstream_effect(tmp_path) -> None:
    _config, runtime = _active_runtime(tmp_path)
    _store, _registry, _memory, graph, broker = runtime
    fact = make_fact(message_id="verified-use", target_routes=["route-b"])
    broker.publish(fact, referee_agent_id="referee-a", current_round=1)
    broker.inbox("route-b", current_round=1)
    receipt = build_receipt(
        fact,
        "route-b",
        delivered_round=1,
        referenced_in_step_ids=["verified-step", "invented-step"],
    )
    broker.acknowledge(receipt)
    delta = ProofDelta(
        problem_hash=PROBLEM_HASH,
        path_id="path-b",
        strategy_id="strategy-1",
        parent_checkpoint_id="checkpoint-b",
        agent_id="author-b",
        round_index=1,
        segment_index=1,
        new_steps=[
            ProofStep(
                step_id="verified-step",
                statement="Apply the admitted identity.",
                justification="The cross-route Fact supplies this equality.",
                dependencies=[fact.message_id],
            )
        ],
        remaining_subgoals=["Finish the route."],
    )

    used = record_verified_message_usage(
        broker,
        [fact],
        [receipt],
        delta,
        route_id="route-b",
        proof_graph=graph,
        proof_debt_before=2.0,
    )

    assert used == [fact.message_id]
    utility = broker.utility_record(fact.message_id, "route-b")
    assert utility is not None
    assert utility["referenced_step_ids"] == ["verified-step"]
    delivery = broker.delivery_record(fact.message_id, "route-b")
    assert delivery is not None
    assert delivery["delivery_state"] == "used"
