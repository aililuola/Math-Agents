from __future__ import annotations

from mathproofmesh.proof_control.models import (
    ResumeDecisionKind,
    TaskStatus,
)
from mathproofmesh.proof_control.resume_policy import ResumePlanner
from mathproofmesh.proof_control.tasks import ExecutableTaskController


def _decision(planner: ResumePlanner, **overrides):
    values = {
        "checkpoint_state": {"round": 7, "hard_stopped": True},
        "config_hash": "config-a",
        "goal_hash": "goal-a",
        "hard_stopped": True,
        "pending_action_ids": [],
        "executable_tasks": [],
        "terminal_stagnation_signature": "stall-a",
        "prior_state_hash": None,
        "prior_config_hash": "config-a",
        "prior_goal_hash": "goal-a",
        "prior_terminal_stagnation_signature": "stall-a",
        "intervention": None,
    }
    values.update(overrides)
    return planner.decide(**values)


def test_hard_stopped_run_without_new_work_uses_zero_model_calls() -> None:
    planner = ResumePlanner()

    decision = _decision(planner)

    assert decision.decision == ResumeDecisionKind.NO_RESUMABLE_WORK
    assert decision.pending_action_ids == []
    assert decision.wakeable_task_ids == []
    assert not planner.provider_call_allowed(decision)


def test_pending_task_allows_resume() -> None:
    tasks = ExecutableTaskController()
    task = tasks.create_route_update_task(
        target_obligation_ids=["goal-a"],
        route_ids=["route-a"],
        created_round=7,
    )

    decision = _decision(
        ResumePlanner(),
        executable_tasks=[task],
    )

    assert task.status == TaskStatus.READY
    assert decision.decision == ResumeDecisionKind.RESUME_WORK
    assert decision.wakeable_task_ids == [task.task_id]


def test_config_change_allows_resume() -> None:
    decision = _decision(
        ResumePlanner(),
        config_hash="config-b",
        prior_config_hash="config-a",
    )

    assert decision.decision == ResumeDecisionKind.RESUME_WORK


def test_reopen_with_pivot_creates_intervention() -> None:
    decision = _decision(
        ResumePlanner(),
        intervention="reopen_with_pivot",
    )

    assert decision.decision == ResumeDecisionKind.RESUME_WORK
    assert decision.intervention_action_id
    assert "reopen_with_pivot" in decision.required_interventions


def test_repeated_normal_resume_is_idempotent() -> None:
    planner = ResumePlanner()
    first = _decision(planner)
    second = _decision(
        planner,
        prior_state_hash=first.state_hash,
    )

    assert first.decision == ResumeDecisionKind.NO_RESUMABLE_WORK
    assert second.decision == ResumeDecisionKind.NO_RESUMABLE_WORK
    assert second.state_hash == first.state_hash
    assert second.decision_id == first.decision_id
