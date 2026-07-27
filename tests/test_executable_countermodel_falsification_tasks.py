from __future__ import annotations

from mathproofmesh.proof_control.falsification import FalsificationContractCompiler
from mathproofmesh.proof_control.models import (
    FalsificationCompilationStatus,
    TaskStatus,
    WakeConditionKind,
)
from mathproofmesh.proof_control.tasks import ExecutableTaskController, WakeScheduler


def test_falsification_text_compiles_to_typed_contract() -> None:
    contract = FalsificationContractCompiler().compile(
        "check x in [-3, 3]: x * x >= 0",
        target_subject_id="claim-a",
        max_cases=32,
    )

    assert contract.status == FalsificationCompilationStatus.EXECUTABLE
    assert contract.registered_handler == "bounded_integer_search"
    assert contract.finite_domains == {"x": {"min": -3, "max": 3}}
    assert contract.exact_relation["relation"] == "ge"


def test_non_automatable_task_is_not_assigned_without_an_executable_handler() -> None:
    contract = FalsificationContractCompiler().compile(
        "Look for a configuration that violates the proposed relation.",
        target_subject_id="claim-a",
    )
    tasks = ExecutableTaskController()

    task = tasks.create_falsification_task(
        contract,
        target_claim_ids=["claim-a"],
        route_ids=["route-a"],
        created_round=2,
        counterexample_hunter_agent_id="agent-hunter",
    )

    assert contract.status == FalsificationCompilationStatus.NON_AUTOMATABLE
    assert task.status == TaskStatus.DEFERRED
    assert task.assigned_agent_id == "agent-hunter"
    assert task.typed_contract_ref == contract.contract_id
    assert task.registered_handler is None
    assert [item.kind for item in task.wake_conditions] == [
        WakeConditionKind.TASK_RECOMPILED
    ]


def test_deferred_task_has_wake_condition() -> None:
    contract = FalsificationContractCompiler().compile(
        "Look for a configuration that violates the proposed relation.",
        target_subject_id="claim-a",
    )
    task = ExecutableTaskController().create_falsification_task(
        contract,
        target_claim_ids=["claim-a"],
        route_ids=["route-a"],
        created_round=2,
        provider_available=False,
    )

    assert task.status == TaskStatus.DEFERRED
    assert task.wake_conditions
    assert task.wake_conditions[0].kind == WakeConditionKind.PROVIDER_AVAILABLE
    assert task.terminal_reason is None


def test_task_wakes_after_provider_available() -> None:
    contract = FalsificationContractCompiler().compile(
        "Look for a configuration that violates the proposed relation.",
        target_subject_id="claim-a",
    )
    controller = ExecutableTaskController()
    task = controller.create_falsification_task(
        contract,
        target_claim_ids=["claim-a"],
        route_ids=["route-a"],
        created_round=2,
        provider_available=False,
    )

    woken = WakeScheduler(controller.tasks).evaluate(
        current_round=3,
        provider_available=True,
    )

    assert [item.task_id for item in woken] == [task.task_id]
    assert task.status == TaskStatus.READY
    assert task.wake_conditions[0].satisfied


def test_no_task_remains_pending_without_wake_or_terminal_reason() -> None:
    controller = ExecutableTaskController()
    contract = FalsificationContractCompiler().compile(
        "Look for a configuration that violates the proposed relation.",
        target_subject_id="claim-a",
    )
    task = controller.create_falsification_task(
        contract,
        target_claim_ids=["claim-a"],
        route_ids=["route-a"],
        created_round=2,
        counterexample_hunter_agent_id=None,
        provider_available=False,
    )

    assert task.status != TaskStatus.CREATED
    assert task.wake_conditions or task.terminal_reason


def test_no_counterexample_does_not_verify_universal_claim() -> None:
    contract = FalsificationContractCompiler().compile(
        "check x in [-3, 3]: x * x >= 0",
        target_subject_id="claim-a",
    )
    controller = ExecutableTaskController()
    task = controller.create_falsification_task(
        contract,
        target_claim_ids=["claim-a"],
        route_ids=["route-a"],
        created_round=2,
    )
    controller.mark_running(task.task_id, current_round=2)

    completed = controller.complete(
        task.task_id,
        current_round=2,
        result_refs=["bounded-result-a"],
        counterexample_found=False,
    )

    assert completed.status == TaskStatus.INCONCLUSIVE
    assert completed.terminal_reason == "bounded_search_found_no_counterexample"
    assert not completed.verifies_target_claim
