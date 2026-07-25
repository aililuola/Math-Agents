from __future__ import annotations

from mathproofmesh.proof_control.action_dispatcher import ControlActionDispatcher
from mathproofmesh.proof_control.models import (
    ControlActionResult,
    ControlActionStatus,
    ControlActionType,
)

from v07_helpers import PROBLEM_HASH


async def test_dispatcher_executes_an_idempotent_action_once() -> None:
    calls: list[str] = []
    checkpoints: list[ControlActionStatus] = []
    dispatcher = ControlActionDispatcher(
        problem_hash=PROBLEM_HASH,
        source_exists=lambda value: value == "diagnostic-a",
        route_exists=lambda value: value == "route-a",
        obligation_exists=lambda value: value == "obligation-a",
        checkpoint_writer=lambda action: checkpoints.append(action.status),
    )

    async def bind_target(action):
        calls.append(action.action_id)
        return ControlActionResult(
            result_refs=["route-target-binding-a"],
            postcondition_met=True,
        )

    dispatcher.register_handler(
        ControlActionType.BIND_ROUTE_TARGET,
        bind_target,
        postcondition=lambda _action, result: (
            "route-target-binding-a" in result.result_refs
        ),
    )
    first = dispatcher.propose(
        ControlActionType.BIND_ROUTE_TARGET,
        source_record_ids=["diagnostic-a"],
        route_ids=["route-a"],
        target_obligation_ids=["obligation-a"],
        payload={"relation": "sufficient", "confidence": 0.9},
        current_round=2,
    )
    duplicate = dispatcher.propose(
        ControlActionType.BIND_ROUTE_TARGET,
        source_record_ids=["diagnostic-a"],
        route_ids=["route-a"],
        target_obligation_ids=["obligation-a"],
        payload={"confidence": 0.9, "relation": "sufficient"},
        current_round=3,
    )

    assert duplicate.action_id == first.action_id
    assert dispatcher.admit(first.action_id).status == ControlActionStatus.ADMITTED
    executed = await dispatcher.execute(first.action_id, current_round=3)
    repeated = await dispatcher.execute(first.action_id, current_round=4)

    assert executed.status == ControlActionStatus.EXECUTED
    assert repeated.action_id == executed.action_id
    assert calls == [first.action_id]
    assert checkpoints == [
        ControlActionStatus.PROPOSED,
        ControlActionStatus.ADMITTED,
        ControlActionStatus.EXECUTING,
        ControlActionStatus.EXECUTED,
    ]


async def test_resume_uses_postcondition_before_reexecuting_an_action() -> None:
    actions = {}
    first_dispatcher = ControlActionDispatcher(
        problem_hash=PROBLEM_HASH,
        actions=actions,
    )
    action = first_dispatcher.propose(
        ControlActionType.MATERIALIZE_BOTTLENECK_CLUSTER,
        payload={"cluster_id": "cluster-a"},
    )
    action.status = ControlActionStatus.EXECUTING
    action.result_refs = ["cluster-a"]

    calls = 0
    restored = ControlActionDispatcher(
        problem_hash=PROBLEM_HASH,
        actions=actions,
    )

    def materialize(_action):
        nonlocal calls
        calls += 1
        return "cluster-a"

    restored.register_handler(
        ControlActionType.MATERIALIZE_BOTTLENECK_CLUSTER,
        materialize,
        postcondition=lambda _action, result: "cluster-a" in result.result_refs,
    )

    resumed = await restored.resume_pending(current_round=5)

    assert [item.status for item in resumed] == [ControlActionStatus.EXECUTED]
    assert calls == 0


def test_dispatcher_rejects_missing_sources_and_targets() -> None:
    dispatcher = ControlActionDispatcher(
        problem_hash=PROBLEM_HASH,
        source_exists=lambda _value: False,
        route_exists=lambda _value: False,
        obligation_exists=lambda _value: False,
    )
    action = dispatcher.propose(
        ControlActionType.CREATE_COUNTERMODEL_TASK,
        source_record_ids=["missing-diagnostic"],
        route_ids=["missing-route"],
        target_obligation_ids=["missing-obligation"],
    )

    rejected = dispatcher.admit(action.action_id)

    assert rejected.status == ControlActionStatus.REJECTED
    assert "unknown source record" in rejected.failure_reason
