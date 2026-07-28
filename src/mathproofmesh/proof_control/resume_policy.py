from __future__ import annotations

from collections.abc import Mapping, Sequence
from typing import Any

from ..schemas import stable_hash
from .models import (
    ExecutableTaskRecord,
    ResumeDecision,
    ResumeDecisionKind,
    TaskStatus,
)


class ResumePlanner:
    """Decide whether a checkpoint has real executable work before provider use."""

    _WAKEABLE_STATUSES = {
        TaskStatus.CREATED,
        TaskStatus.ASSIGNED,
        TaskStatus.READY,
    }
    _DEFERRED_STATUSES = {
        TaskStatus.NEEDS_REWRITE,
        TaskStatus.DEFERRED,
        TaskStatus.BLOCKED,
    }
    _INTERVENTIONS = {
        "reopen_with_pivot",
        "reset_stagnation",
        "replay_stage",
        "inject_fact",
        "inject_counterexample",
        "select_subgoal",
    }

    def __init__(
        self,
        decisions: dict[str, ResumeDecision] | None = None,
    ) -> None:
        self.decisions = decisions if decisions is not None else {}

    def decide(
        self,
        *,
        checkpoint_state: Mapping[str, Any],
        config_hash: str,
        goal_hash: str,
        hard_stopped: bool,
        pending_action_ids: Sequence[str],
        executable_tasks: Sequence[ExecutableTaskRecord],
        terminal_stagnation_signature: str | None,
        prior_state_hash: str | None,
        prior_config_hash: str | None,
        prior_goal_hash: str | None,
        prior_terminal_stagnation_signature: str | None,
        intervention: str | None,
    ) -> ResumeDecision:
        normalized_intervention = (
            intervention.strip().casefold().replace("-", "_")
            if intervention is not None
            else None
        )
        if (
            normalized_intervention is not None
            and normalized_intervention not in self._INTERVENTIONS
        ):
            raise ValueError(f"unsupported resume intervention: {intervention}")

        pending_ids = sorted(set(pending_action_ids))
        wakeable_ids = sorted(
            task.task_id
            for task in executable_tasks
            if task.status in self._WAKEABLE_STATUSES
            and bool(task.registered_handler or task.assigned_agent_id)
        )
        deferred_ids = sorted(
            task.task_id
            for task in executable_tasks
            if task.status in self._DEFERRED_STATUSES
            and any(not condition.satisfied for condition in task.wake_conditions)
        )
        state_payload = {
            "checkpoint_state": dict(checkpoint_state),
            "config_hash": config_hash,
            "goal_hash": goal_hash,
            "pending_action_ids": pending_ids,
            "executable_tasks": [
                task.model_dump(mode="json")
                for task in sorted(executable_tasks, key=lambda item: item.task_id)
            ],
            "terminal_stagnation_signature": terminal_stagnation_signature,
            "intervention": normalized_intervention,
        }
        state_hash = stable_hash(state_payload)
        state_changed = prior_state_hash is not None and prior_state_hash != state_hash
        config_changed = (
            prior_config_hash is not None and prior_config_hash != config_hash
        )
        goal_changed = prior_goal_hash is not None and prior_goal_hash != goal_hash
        stagnation_changed = (
            prior_terminal_stagnation_signature is not None
            and prior_terminal_stagnation_signature != terminal_stagnation_signature
        )
        has_work = bool(
            pending_ids
            or wakeable_ids
            or normalized_intervention
            or state_changed
            or config_changed
            or goal_changed
            or stagnation_changed
        )
        if not hard_stopped or has_work:
            kind = ResumeDecisionKind.RESUME_WORK
            reason = self._resume_reason(
                pending_ids=pending_ids,
                wakeable_ids=wakeable_ids,
                intervention=normalized_intervention,
                state_changed=state_changed,
                config_changed=config_changed,
                goal_changed=goal_changed,
                stagnation_changed=stagnation_changed,
            )
        else:
            kind = ResumeDecisionKind.NO_RESUMABLE_WORK
            reason = (
                "The hard-stopped checkpoint has no pending action, wakeable task, "
                "state change, configuration change, goal change, or intervention."
            )

        intervention_action_id = (
            "resume_intervention_"
            + stable_hash(
                {
                    "state_hash": state_hash,
                    "intervention": normalized_intervention,
                }
            )[:20]
            if normalized_intervention is not None
            else None
        )
        decision_id = (
            "resume_decision_"
            + stable_hash(
                {
                    "decision": kind.value,
                    "state_hash": state_hash,
                    "intervention_action_id": intervention_action_id,
                }
            )[:20]
        )
        existing = self.decisions.get(decision_id)
        if existing is not None:
            return existing
        decision = ResumeDecision(
            decision_id=decision_id,
            decision=kind,
            state_hash=state_hash,
            config_hash=config_hash,
            goal_hash=goal_hash,
            hard_stopped=hard_stopped,
            pending_action_ids=pending_ids,
            wakeable_task_ids=wakeable_ids,
            deferred_task_ids=deferred_ids,
            required_interventions=(
                [normalized_intervention] if normalized_intervention is not None else []
            ),
            intervention_action_id=intervention_action_id,
            terminal_stagnation_signature=terminal_stagnation_signature,
            reason=reason,
        )
        self.decisions[decision.decision_id] = decision
        return decision

    @staticmethod
    def provider_call_allowed(decision: ResumeDecision) -> bool:
        return decision.decision == ResumeDecisionKind.RESUME_WORK

    @staticmethod
    def _resume_reason(
        *,
        pending_ids: Sequence[str],
        wakeable_ids: Sequence[str],
        intervention: str | None,
        state_changed: bool,
        config_changed: bool,
        goal_changed: bool,
        stagnation_changed: bool,
    ) -> str:
        reasons: list[str] = []
        if pending_ids:
            reasons.append("pending control action")
        if wakeable_ids:
            reasons.append("wakeable executable task")
        if intervention:
            reasons.append(f"explicit intervention {intervention}")
        if state_changed:
            reasons.append("checkpoint state change")
        if config_changed:
            reasons.append("configuration change")
        if goal_changed:
            reasons.append("goal change")
        if stagnation_changed:
            reasons.append("terminal stagnation signature change")
        return (
            "Resume admitted because of " + ", ".join(reasons or ["active state"]) + "."
        )
