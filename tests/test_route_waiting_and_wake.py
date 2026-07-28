from __future__ import annotations

import pytest

from mathproofmesh.communication.route_registry import RouteRegistry
from mathproofmesh.proof_control.falsification import FalsificationContractCompiler
from mathproofmesh.proof_control.models import (
    RouteFreezeRecord,
    RouteStatus,
    TaskStatus,
)
from mathproofmesh.proof_control.tasks import (
    ExecutableTaskController,
    RouteWakeController,
)

from v07_helpers import PROBLEM_HASH, make_strategy


def _waiting_runtime():
    registry = RouteRegistry(problem_hash=PROBLEM_HASH)
    route = registry.register_route(make_strategy(82), route_id="route-waiting")
    tasks = ExecutableTaskController()
    contract = FalsificationContractCompiler().compile(
        "Search for a counterexample configuration.",
        target_subject_id="claim-a",
    )
    task = tasks.create_falsification_task(
        contract,
        target_claim_ids=["claim-a"],
        route_ids=[route.route_id],
        created_round=2,
        provider_available=False,
    )
    controller = RouteWakeController(registry, tasks.tasks)
    return registry, route, tasks, task, controller


def test_deferred_task_with_wake_sets_route_waiting() -> None:
    _registry, route, _tasks, task, controller = _waiting_runtime()

    controller.wait_for_task(route.route_id, task.task_id, current_round=2)

    assert task.status == TaskStatus.DEFERRED
    assert route.status == RouteStatus.WAITING


def test_waiting_route_wakes_when_condition_satisfied() -> None:
    _registry, route, _tasks, task, controller = _waiting_runtime()
    controller.wait_for_task(route.route_id, task.task_id, current_round=2)

    woken = controller.evaluate(
        current_round=3,
        provider_available=True,
    )

    assert [item.route_id for item in woken] == [route.route_id]
    assert route.status == RouteStatus.ACTIVE
    assert task.status == TaskStatus.READY


def test_route_cannot_freeze_with_automatic_wake_condition() -> None:
    _registry, route, _tasks, task, controller = _waiting_runtime()
    controller.wait_for_task(route.route_id, task.task_id, current_round=2)

    with pytest.raises(ValueError, match="wake"):
        controller.freeze(
            RouteFreezeRecord(
                route_id=route.route_id,
                blocker_task_ids=[task.task_id],
                wake_condition_ids=[
                    condition.condition_id for condition in task.wake_conditions
                ],
                requires_user_intervention=False,
                reason="The provider is temporarily unavailable.",
                created_round=2,
            )
        )


def test_frozen_route_requires_explicit_intervention() -> None:
    registry = RouteRegistry(problem_hash=PROBLEM_HASH)
    route = registry.register_route(make_strategy(83), route_id="route-frozen")
    controller = RouteWakeController(registry, {})

    record = controller.freeze(
        RouteFreezeRecord(
            route_id=route.route_id,
            blocker_task_ids=[],
            wake_condition_ids=[],
            requires_user_intervention=True,
            reason="No automatic repair remains.",
            created_round=4,
        )
    )

    assert route.status == RouteStatus.FROZEN
    assert record.requires_user_intervention


def test_route_waiting_resume_roundtrip() -> None:
    registry, route, tasks, task, controller = _waiting_runtime()
    controller.wait_for_task(route.route_id, task.task_id, current_round=2)

    restored_registry = RouteRegistry.from_state(registry.export_state())
    restored_tasks = ExecutableTaskController.from_state(tasks.export_state())
    restored = RouteWakeController(
        restored_registry,
        restored_tasks.tasks,
        freeze_records=controller.freeze_records,
    )

    assert restored_registry.get(route.route_id).status == RouteStatus.WAITING
    assert restored.tasks[task.task_id].status == TaskStatus.DEFERRED
