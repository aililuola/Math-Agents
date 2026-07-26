from __future__ import annotations

import asyncio

from mathproofmesh.communication.broker import MessageBroker
from mathproofmesh.proof_control.controller import ProofControlLayer
from mathproofmesh.proof_control.models import (
    ControlActionStatus,
    MetaPivotStatus,
)
from mathproofmesh.proof_control.state import ProofControlState

from v07_helpers import (
    make_broker_runtime,
    make_fact,
    make_proof_control_config,
)


def _active_control_runtime(tmp_path):
    config = make_proof_control_config(tmp_path / "runs", mode="active")
    store, registry, memory, graph, broker = make_broker_runtime(
        config,
        tmp_path / "runtime",
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
    return config, store, registry, memory, graph, broker, control


def test_mid_pivot_checkpoint_recovers_without_reexecuting_authority(
    tmp_path,
) -> None:
    (
        config,
        store,
        registry,
        memory,
        graph,
        broker,
        control,
    ) = _active_control_runtime(tmp_path)
    pivot = control.request_meta_pivot(
        source_stagnation_signature="stagnation-before-resume",
        trigger_round=2,
        requested_mechanisms=["representation_switch"],
    )
    assert pivot.action_id is not None

    pivot.status = MetaPivotStatus.EXECUTED
    pivot.created_route_ids = ["route-created-once"]
    pivot.executed_round = 3
    action = control.state.control_actions[pivot.action_id]
    action.status = ControlActionStatus.EXECUTING
    action.result_refs = [pivot.pivot_id, "route-created-once"]

    restored = ProofControlLayer.from_state(
        control.export_state(),
        config=config,
        store=store,
        activity=None,
        proof_graph=graph,
        typed_memory=memory,
        message_broker=broker,
        route_registry=registry,
    )
    calls = 0

    async def execute(_pivot):
        nonlocal calls
        calls += 1
        return {"created_route_ids": ["route-duplicate"]}

    recovered = asyncio.run(
        restored.execute_pending_meta_pivot(
            current_round=4,
            executor=execute,
        )
    )

    assert calls == 0
    assert recovered.status == MetaPivotStatus.EXECUTED
    assert recovered.created_route_ids == ["route-created-once"]
    recovered_action = restored.state.control_actions[pivot.action_id]
    assert recovered_action.status == ControlActionStatus.EXECUTED
    assert recovered_action.result_refs.count("route-created-once") == 1


def test_queued_message_before_checkpoint_schedules_one_route_update_after_resume(
    tmp_path,
) -> None:
    config = make_proof_control_config(tmp_path / "runs", mode="active")
    store, registry, memory, graph, broker = make_broker_runtime(
        config,
        tmp_path / "runtime",
    )
    fact = make_fact(
        message_id="queued-before-checkpoint",
        target_routes=["route-b"],
    )
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

    restored_broker = MessageBroker.from_state(
        broker.export_state(),
        config=config,
        store=store,
        activity=None,
        route_registry=registry,
        proof_graph=graph,
        typed_memory=memory,
    )
    restored_control = ProofControlLayer.from_state(
        control.export_state(),
        config=config,
        store=store,
        activity=None,
        proof_graph=graph,
        typed_memory=memory,
        message_broker=restored_broker,
        route_registry=registry,
    )

    first = restored_control.schedule_pending_route_updates(current_round=2)
    second = restored_control.schedule_pending_route_updates(current_round=2)

    assert len(first) == 1
    assert first[0].status == ControlActionStatus.EXECUTED
    assert second == []
    assert len(restored_control.state.route_update_tasks) == 1
    task = next(iter(restored_control.state.route_update_tasks.values()))
    assert task.message_ids == [fact.message_id]
    delivery = restored_broker.delivery_record(fact.message_id, "route-b")
    assert delivery is not None
    assert delivery["delivery_state"] == "scheduled"


def test_v080_sidecar_without_v081_fields_migrates_to_empty_defaults() -> None:
    restored = ProofControlState.from_state(
        {
            "schema_version": "0.8.0",
            "goal_links": {},
            "events": [],
        }
    )

    assert restored.control_actions == {}
    assert restored.route_update_tasks == {}
    assert restored.inspiration_review_deferrals == {}
    assert restored.meta_pivot_state is None
    assert restored.export_state()["schema_version"] == "0.8.2"
