from __future__ import annotations

from dataclasses import dataclass
from typing import Sequence

from .budget import SoftBudgetAllocator
from .schemas import (
    ActionKind,
    BudgetAction,
    BudgetDecision,
    InspirationMechanism,
    InspirationTask,
)


MECHANISM_ACTION: dict[InspirationMechanism, ActionKind] = {
    InspirationMechanism.REPRESENTATION_SWITCH: ActionKind.SWITCH_REPRESENTATION,
    InspirationMechanism.STRUCTURAL_ANALOGY: ActionKind.SEARCH_ANALOGY,
    InspirationMechanism.AUXILIARY_CONSTRUCTION: ActionKind.INVENT_CONSTRUCTION,
    InspirationMechanism.INVARIANT_HYPOTHESIS: ActionKind.GENERATE_INVARIANT,
    InspirationMechanism.REVERSE_GOAL_ANALYSIS: ActionKind.REVERSE_GOAL,
    InspirationMechanism.BRIDGE_LEMMA: ActionKind.BRIDGE,
    InspirationMechanism.META_REPLAN: ActionKind.META_REPLAN,
    InspirationMechanism.SURPRISE_EXPLORATION: ActionKind.SURPRISE_WIDEN,
}


@dataclass(frozen=True, slots=True)
class InspirationAdmission:
    admitted_tasks: list[InspirationTask]
    rejected: dict[str, str]
    decision: BudgetDecision


def admit_inspiration_tasks(
    tasks: Sequence[InspirationTask],
    allocator: SoftBudgetAllocator,
    *,
    current_path_count: int,
    has_candidate: bool,
) -> InspirationAdmission:
    candidates = [
        BudgetAction(
            action=MECHANISM_ACTION[task.mechanism],
            target_id=task.task_id,
            score=max(0.0, 1.0 - 0.05 * index),
            rank=index + 1,
            reason=task.reason,
            estimated_calls=allocator.estimate_action_calls(
                MECHANISM_ACTION[task.mechanism],
                current_path_count=current_path_count,
            ),
        )
        for index, task in enumerate(tasks)
    ]
    decision = BudgetDecision(
        actions=[],
        candidates=candidates,
        global_uncertainty=1.0,
        coverage=0.0,
        rationale="Inspiration tasks require scheduler admission before model calls.",
    )
    admitted_decision = allocator.admit_decision(
        decision,
        current_path_count=current_path_count,
        has_candidate=has_candidate,
        max_actions=len(tasks),
    )
    admitted_ids = {
        action.target_id
        for action in admitted_decision.actions
        if action.target_id and action.action != ActionKind.STOP
    }
    rejected = {
        action.target_id: action.blocked_reason or "scheduler did not admit this task"
        for action in admitted_decision.candidates
        if action.target_id and action.target_id not in admitted_ids
    }
    return InspirationAdmission(
        admitted_tasks=[task for task in tasks if task.task_id in admitted_ids],
        rejected=rejected,
        decision=admitted_decision,
    )
