from __future__ import annotations

from collections.abc import Collection, Mapping, Sequence
from typing import Literal

from ..communication.route_registry import RouteRegistry
from ..schemas import RouteDescriptor, RouteStatus, stable_hash, utc_now_iso
from .models import (
    ExecutableTaskRecord,
    FalsificationCompilationStatus,
    RouteFreezeRecord,
    TaskStatus,
    TypedFalsificationContract,
    WakeCondition,
    WakeConditionKind,
)


class ExecutableTaskController:
    """Own legal, durable transitions for proof-control work items."""

    TERMINAL_STATUSES = {
        TaskStatus.COMPLETED,
        TaskStatus.INCONCLUSIVE,
        TaskStatus.FAILED,
        TaskStatus.EXPIRED,
    }
    _TRANSITIONS = {
        TaskStatus.CREATED: {
            TaskStatus.NEEDS_REWRITE,
            TaskStatus.ASSIGNED,
            TaskStatus.READY,
            TaskStatus.DEFERRED,
            TaskStatus.BLOCKED,
            TaskStatus.FAILED,
        },
        TaskStatus.NEEDS_REWRITE: {
            TaskStatus.READY,
            TaskStatus.DEFERRED,
            TaskStatus.FAILED,
            TaskStatus.EXPIRED,
        },
        TaskStatus.ASSIGNED: {
            TaskStatus.READY,
            TaskStatus.DEFERRED,
            TaskStatus.FAILED,
            TaskStatus.EXPIRED,
        },
        TaskStatus.READY: {
            TaskStatus.RUNNING,
            TaskStatus.DEFERRED,
            TaskStatus.FAILED,
            TaskStatus.EXPIRED,
        },
        TaskStatus.RUNNING: {
            TaskStatus.COMPLETED,
            TaskStatus.INCONCLUSIVE,
            TaskStatus.DEFERRED,
            TaskStatus.BLOCKED,
            TaskStatus.FAILED,
        },
        TaskStatus.DEFERRED: {
            TaskStatus.ASSIGNED,
            TaskStatus.READY,
            TaskStatus.BLOCKED,
            TaskStatus.FAILED,
            TaskStatus.EXPIRED,
        },
        TaskStatus.BLOCKED: {
            TaskStatus.READY,
            TaskStatus.FAILED,
            TaskStatus.EXPIRED,
        },
    }

    def __init__(
        self,
        tasks: dict[str, ExecutableTaskRecord] | None = None,
    ) -> None:
        self.tasks = tasks if tasks is not None else {}

    def create_falsification_task(
        self,
        contract: TypedFalsificationContract,
        *,
        target_claim_ids: Sequence[str] = (),
        target_obligation_ids: Sequence[str] = (),
        route_ids: Sequence[str] = (),
        created_round: int,
        counterexample_hunter_agent_id: str | None = None,
        provider_available: bool = True,
        expires_round: int | None = None,
    ) -> ExecutableTaskRecord:
        identity = {
            "task_kind": "falsification",
            "contract_id": contract.contract_id,
            "target_claim_ids": sorted(set(target_claim_ids)),
            "target_obligation_ids": sorted(set(target_obligation_ids)),
            "route_ids": sorted(set(route_ids)),
        }
        task_id = f"executable_task_{stable_hash(identity)[:20]}"
        existing = self.tasks.get(task_id)
        if existing is not None:
            return existing
        task = ExecutableTaskRecord(
            task_id=task_id,
            task_kind="falsification",
            status=TaskStatus.CREATED,
            target_claim_ids=identity["target_claim_ids"],
            target_obligation_ids=identity["target_obligation_ids"],
            route_ids=identity["route_ids"],
            assigned_agent_id=counterexample_hunter_agent_id,
            registered_handler=contract.registered_handler,
            typed_contract_ref=contract.contract_id,
            created_round=created_round,
            last_transition_round=created_round,
            expires_round=(
                expires_round if expires_round is not None else created_round + 4
            ),
            transition_history=[
                {
                    "from": None,
                    "to": TaskStatus.CREATED.value,
                    "round": created_round,
                    "reason": "task_created",
                }
            ],
        )
        self.tasks[task.task_id] = task
        if not provider_available:
            return self.defer(
                task.task_id,
                current_round=created_round,
                reason="provider_unavailable",
                wake_kind=WakeConditionKind.PROVIDER_AVAILABLE,
            )
        if contract.status == FalsificationCompilationStatus.EXECUTABLE:
            return self._transition(
                task,
                TaskStatus.READY,
                current_round=created_round,
                reason="registered_typed_handler_available",
            )
        if contract.status == FalsificationCompilationStatus.NEEDS_REWRITE:
            self._add_wake_condition(
                task,
                WakeConditionKind.TASK_RECOMPILED,
                target_id=contract.contract_id,
                current_round=created_round,
            )
            return self._transition(
                task,
                TaskStatus.NEEDS_REWRITE,
                current_round=created_round,
                reason="typed_contract_requires_rewrite",
            )
        if counterexample_hunter_agent_id:
            return self._transition(
                task,
                TaskStatus.ASSIGNED,
                current_round=created_round,
                reason="counterexample_hunter_assigned",
            )
        return self.defer(
            task.task_id,
            current_round=created_round,
            reason="counterexample_hunter_unavailable",
            wake_kind=WakeConditionKind.REVIEWER_AVAILABLE,
        )

    def create_semantic_repair_task(
        self,
        *,
        target_obligation_ids: Sequence[str],
        strategy_ids: Sequence[str] = (),
        created_round: int,
        assigned_agent_id: str | None = None,
    ) -> ExecutableTaskRecord:
        """Create the audit record for a batched statement-normalization call.

        Semantic repair happens synchronously inside the admission phase, so
        the task never uses the +4 round expiry (a repair queued at round 0
        must not silently expire before anything served it). Its terminal
        state is always explicit: completed, failed, or budget-refused.
        """
        identity = {
            "task_kind": "normalize_nodes",
            "target_obligation_ids": sorted(set(target_obligation_ids)),
            "strategy_ids": sorted(set(strategy_ids)),
        }
        task_id = f"executable_task_{stable_hash(identity)[:20]}"
        existing = self.tasks.get(task_id)
        if existing is not None:
            return existing
        task = ExecutableTaskRecord(
            task_id=task_id,
            task_kind="normalize_nodes",
            status=TaskStatus.CREATED,
            target_obligation_ids=identity["target_obligation_ids"],
            route_ids=identity["strategy_ids"],
            assigned_agent_id=assigned_agent_id,
            registered_handler="batched_statement_normalizer",
            created_round=created_round,
            last_transition_round=created_round,
            expires_round=None,
            transition_history=[
                {
                    "from": None,
                    "to": TaskStatus.CREATED.value,
                    "round": created_round,
                    "reason": "semantic_repair_queued",
                }
            ],
        )
        self.tasks[task.task_id] = task
        return self._transition(
            task,
            TaskStatus.READY,
            current_round=created_round,
            reason="batched_normalizer_ready",
        )

    def create_admission_review_task(
        self,
        *,
        task_kind: Literal[
            "blueprint_review",
            "repair_direct_target",
            "edge_review",
            "generate_plan",
            "batch_repair",
        ],
        target_obligation_ids: Sequence[str],
        strategy_ids: Sequence[str] = (),
        created_round: int,
        assigned_agent_id: str | None = None,
        prompt_ref: str | None = None,
    ) -> ExecutableTaskRecord:
        """Create one durable, idempotent route-admission review action."""

        handler_by_kind = {
            "blueprint_review": "route_admission_review_dispatcher",
            "repair_direct_target": "route_admission_review_dispatcher",
            "edge_review": "route_admission_review_dispatcher",
            "generate_plan": "strategy_planner_dispatcher",
            "batch_repair": "batched_statement_normalizer",
        }
        identity = {
            "task_kind": task_kind,
            "target_obligation_ids": sorted(set(target_obligation_ids)),
            "strategy_ids": sorted(set(strategy_ids)),
            "prompt_ref": prompt_ref,
        }
        task_id = f"executable_task_{stable_hash(identity)[:20]}"
        existing = self.tasks.get(task_id)
        if existing is not None:
            return existing
        task = ExecutableTaskRecord(
            task_id=task_id,
            task_kind=task_kind,
            status=TaskStatus.READY,
            target_obligation_ids=identity["target_obligation_ids"],
            route_ids=identity["strategy_ids"],
            assigned_agent_id=assigned_agent_id,
            registered_handler=handler_by_kind[task_kind],
            explicit_prompt_ref=prompt_ref,
            created_round=created_round,
            last_transition_round=created_round,
            expires_round=None,
            transition_history=[
                {
                    "from": None,
                    "to": TaskStatus.READY.value,
                    "round": created_round,
                    "reason": "registered_admission_review_handler_available",
                }
            ],
        )
        self.tasks[task.task_id] = task
        return task

    def create_countermodel_task(
        self,
        *,
        source_record_id: str,
        target_claim_ids: Sequence[str] = (),
        target_obligation_ids: Sequence[str] = (),
        route_ids: Sequence[str] = (),
        created_round: int,
        counterexample_hunter_agent_id: str | None = None,
        explicit_prompt_ref: str | None = None,
        expires_round: int | None = None,
    ) -> ExecutableTaskRecord:
        identity = {
            "task_kind": "countermodel",
            "source_record_id": source_record_id,
            "target_claim_ids": sorted(set(target_claim_ids)),
            "target_obligation_ids": sorted(set(target_obligation_ids)),
            "route_ids": sorted(set(route_ids)),
        }
        task_id = f"executable_task_{stable_hash(identity)[:20]}"
        existing = self.tasks.get(task_id)
        if existing is not None:
            return existing
        task = ExecutableTaskRecord(
            task_id=task_id,
            task_kind="countermodel",
            status=TaskStatus.CREATED,
            target_claim_ids=identity["target_claim_ids"],
            target_obligation_ids=identity["target_obligation_ids"],
            route_ids=identity["route_ids"],
            assigned_agent_id=counterexample_hunter_agent_id,
            registered_handler="countermodel_agent_dispatcher",
            explicit_prompt_ref=explicit_prompt_ref,
            created_round=created_round,
            last_transition_round=created_round,
            expires_round=(
                expires_round if expires_round is not None else created_round + 4
            ),
            transition_history=[
                {
                    "from": None,
                    "to": TaskStatus.CREATED.value,
                    "round": created_round,
                    "reason": "task_created",
                }
            ],
        )
        self.tasks[task.task_id] = task
        if counterexample_hunter_agent_id is not None:
            return self._transition(
                task,
                TaskStatus.ASSIGNED,
                current_round=created_round,
                reason="counterexample_hunter_assigned",
            )
        return self.defer(
            task.task_id,
            current_round=created_round,
            reason="counterexample_hunter_unavailable",
            wake_kind=WakeConditionKind.REVIEWER_AVAILABLE,
        )

    def create_assumption_challenger_task(
        self,
        *,
        challenger_task_id: str,
        family_id: str,
        route_ids: Sequence[str],
        created_round: int,
        target_obligation_ids: Sequence[str] = (),
        assigned_agent_id: str | None = None,
        expires_round: int | None = None,
    ) -> ExecutableTaskRecord:
        identity = {
            "task_kind": "assumption_challenger",
            "challenger_task_id": challenger_task_id,
            "family_id": family_id,
            "route_ids": sorted(set(route_ids)),
            "target_obligation_ids": sorted(set(target_obligation_ids)),
        }
        task_id = f"executable_task_{stable_hash(identity)[:20]}"
        existing = self.tasks.get(task_id)
        if existing is not None:
            return existing
        task = ExecutableTaskRecord(
            task_id=task_id,
            task_kind="assumption_challenger",
            status=TaskStatus.READY,
            target_obligation_ids=identity["target_obligation_ids"],
            route_ids=identity["route_ids"],
            assigned_agent_id=assigned_agent_id,
            registered_handler="assumption_challenger_dispatcher",
            explicit_prompt_ref=challenger_task_id,
            typed_contract_ref=family_id,
            created_round=created_round,
            last_transition_round=created_round,
            expires_round=(
                expires_round if expires_round is not None else created_round + 4
            ),
            transition_history=[
                {
                    "from": None,
                    "to": TaskStatus.READY.value,
                    "round": created_round,
                    "reason": "common_mode_challenger_materialized",
                }
            ],
        )
        self.tasks[task.task_id] = task
        return task

    def create_route_update_task(
        self,
        *,
        target_claim_ids: Sequence[str] = (),
        target_obligation_ids: Sequence[str] = (),
        route_ids: Sequence[str] = (),
        created_round: int,
        explicit_prompt_ref: str | None = None,
    ) -> ExecutableTaskRecord:
        identity = {
            "task_kind": "route_update",
            "target_claim_ids": sorted(set(target_claim_ids)),
            "target_obligation_ids": sorted(set(target_obligation_ids)),
            "route_ids": sorted(set(route_ids)),
            "explicit_prompt_ref": explicit_prompt_ref,
        }
        task_id = f"executable_task_{stable_hash(identity)[:20]}"
        existing = self.tasks.get(task_id)
        if existing is not None:
            return existing
        task = ExecutableTaskRecord(
            task_id=task_id,
            task_kind="route_update",
            status=TaskStatus.READY,
            target_claim_ids=identity["target_claim_ids"],
            target_obligation_ids=identity["target_obligation_ids"],
            route_ids=identity["route_ids"],
            registered_handler="route_update_dispatcher",
            explicit_prompt_ref=explicit_prompt_ref,
            created_round=created_round,
            last_transition_round=created_round,
            expires_round=created_round + 4,
            transition_history=[
                {
                    "from": None,
                    "to": TaskStatus.READY.value,
                    "round": created_round,
                    "reason": "registered_route_update_handler_available",
                }
            ],
        )
        self.tasks[task.task_id] = task
        return task

    def export_state(self) -> dict[str, object]:
        return {
            "schema_version": "0.8.2",
            "tasks": {
                task_id: task.model_dump(mode="json")
                for task_id, task in sorted(self.tasks.items())
            },
        }

    @classmethod
    def from_state(
        cls,
        state: Mapping[str, object] | None,
    ) -> "ExecutableTaskController":
        if not isinstance(state, Mapping):
            return cls()
        raw_tasks = state.get("tasks", state)
        if not isinstance(raw_tasks, Mapping):
            return cls()
        tasks: dict[str, ExecutableTaskRecord] = {}
        for task_id, raw_task in sorted(
            raw_tasks.items(), key=lambda item: str(item[0])
        ):
            if task_id == "schema_version":
                continue
            try:
                task = ExecutableTaskRecord.model_validate(raw_task)
            except (TypeError, ValueError):
                continue
            tasks[str(task_id)] = task
        return cls(tasks)

    def defer(
        self,
        task_id: str,
        *,
        current_round: int,
        reason: str,
        wake_kind: WakeConditionKind,
        target_id: str | None = None,
    ) -> ExecutableTaskRecord:
        task = self.tasks[task_id]
        self._add_wake_condition(
            task,
            wake_kind,
            target_id=target_id,
            current_round=current_round,
        )
        return self._transition(
            task,
            TaskStatus.DEFERRED,
            current_round=current_round,
            reason=reason,
        )

    def mark_running(
        self,
        task_id: str,
        *,
        current_round: int,
    ) -> ExecutableTaskRecord:
        task = self.tasks[task_id]
        if task.status == TaskStatus.ASSIGNED:
            self._transition(
                task,
                TaskStatus.READY,
                current_round=current_round,
                reason="assigned_executor_ready",
            )
        return self._transition(
            task,
            TaskStatus.RUNNING,
            current_round=current_round,
            reason="task_execution_started",
        )

    def complete(
        self,
        task_id: str,
        *,
        current_round: int,
        result_refs: Sequence[str],
        counterexample_found: bool,
    ) -> ExecutableTaskRecord:
        task = self.tasks[task_id]
        if task.status != TaskStatus.RUNNING:
            raise ValueError("only a running task may complete")
        task.result_refs = list(dict.fromkeys(result_refs))
        if not task.result_refs:
            raise ValueError("task completion requires a result reference")
        if counterexample_found:
            task.terminal_reason = "exact_counterexample_found"
            return self._transition(
                task,
                TaskStatus.COMPLETED,
                current_round=current_round,
                reason=task.terminal_reason,
            )
        task.terminal_reason = "bounded_search_found_no_counterexample"
        return self._transition(
            task,
            TaskStatus.INCONCLUSIVE,
            current_round=current_round,
            reason=task.terminal_reason,
        )

    def fail(
        self,
        task_id: str,
        *,
        current_round: int,
        reason: str,
    ) -> ExecutableTaskRecord:
        task = self.tasks[task_id]
        task.terminal_reason = reason.strip() or "task_execution_failed"
        return self._transition(
            task,
            TaskStatus.FAILED,
            current_round=current_round,
            reason=task.terminal_reason,
        )

    def complete_work(
        self,
        task_id: str,
        *,
        current_round: int,
        result_refs: Sequence[str],
        reason: str,
    ) -> ExecutableTaskRecord:
        task = self.tasks[task_id]
        if task.status in {TaskStatus.ASSIGNED, TaskStatus.READY}:
            self.mark_running(task_id, current_round=current_round)
        if task.status != TaskStatus.RUNNING:
            raise ValueError("only an executable or running task may complete")
        task.result_refs = list(dict.fromkeys(result_refs))
        if not task.result_refs:
            raise ValueError("task completion requires a result reference")
        task.terminal_reason = reason.strip() or "task_completed"
        return self._transition(
            task,
            TaskStatus.COMPLETED,
            current_round=current_round,
            reason=task.terminal_reason,
        )

    def complete_inconclusive(
        self,
        task_id: str,
        *,
        current_round: int,
        result_refs: Sequence[str],
        reason: str,
    ) -> ExecutableTaskRecord:
        task = self.tasks[task_id]
        if task.status in {TaskStatus.ASSIGNED, TaskStatus.READY}:
            self.mark_running(task_id, current_round=current_round)
        if task.status != TaskStatus.RUNNING:
            raise ValueError("only an executable or running task may be inconclusive")
        task.result_refs = list(dict.fromkeys(result_refs))
        if not task.result_refs:
            raise ValueError("inconclusive task requires a result reference")
        task.terminal_reason = reason.strip() or "task_inconclusive"
        return self._transition(
            task,
            TaskStatus.INCONCLUSIVE,
            current_round=current_round,
            reason=task.terminal_reason,
        )

    def _add_wake_condition(
        self,
        task: ExecutableTaskRecord,
        kind: WakeConditionKind,
        *,
        target_id: str | None,
        current_round: int,
    ) -> WakeCondition:
        identity = {
            "task_id": task.task_id,
            "kind": kind.value,
            "target_id": target_id,
        }
        condition_id = f"wake_{stable_hash(identity)[:20]}"
        existing = next(
            (
                condition
                for condition in task.wake_conditions
                if condition.condition_id == condition_id
            ),
            None,
        )
        if existing is not None:
            return existing
        condition = WakeCondition(
            condition_id=condition_id,
            kind=kind,
            target_id=target_id,
            earliest_round=current_round + 1,
        )
        task.wake_conditions.append(condition)
        return condition

    def _transition(
        self,
        task: ExecutableTaskRecord,
        target: TaskStatus,
        *,
        current_round: int,
        reason: str,
    ) -> ExecutableTaskRecord:
        if task.status == target:
            return task
        if task.status in self.TERMINAL_STATUSES:
            raise ValueError("terminal task state cannot transition")
        allowed = self._TRANSITIONS.get(task.status, set())
        if target not in allowed:
            raise ValueError(
                f"illegal task transition: {task.status.value} -> {target.value}"
            )
        prior = task.status
        task.status = target
        task.last_transition_round = current_round
        task.transition_history.append(
            {
                "from": prior.value,
                "to": target.value,
                "round": current_round,
                "reason": reason,
            }
        )
        return task


class WakeScheduler:
    """Re-evaluate only explicit wake predicates; never wake by narrative guess."""

    def __init__(self, tasks: Mapping[str, ExecutableTaskRecord]) -> None:
        self.tasks = tasks

    def evaluate(
        self,
        *,
        current_round: int,
        provider_available: bool = False,
        budget_available: bool = False,
        available_fact_ids: Collection[str] = (),
        changed_obligation_ids: Collection[str] = (),
        reviewer_available: bool = False,
        recompiled_task_ids: Collection[str] = (),
        user_intervention: bool = False,
        config_changed: bool = False,
    ) -> list[ExecutableTaskRecord]:
        woken: list[ExecutableTaskRecord] = []
        for task in sorted(self.tasks.values(), key=lambda item: item.task_id):
            if task.status not in {
                TaskStatus.DEFERRED,
                TaskStatus.NEEDS_REWRITE,
                TaskStatus.BLOCKED,
            }:
                continue
            if task.expires_round is not None and current_round > task.expires_round:
                prior = task.status
                task.status = TaskStatus.EXPIRED
                task.last_transition_round = current_round
                task.terminal_reason = "task_expired_before_wake"
                task.transition_history.append(
                    {
                        "from": prior.value,
                        "to": TaskStatus.EXPIRED.value,
                        "round": current_round,
                        "reason": task.terminal_reason,
                    }
                )
                continue
            pending = [
                condition
                for condition in task.wake_conditions
                if not condition.satisfied
                and (
                    condition.earliest_round is None
                    or current_round >= condition.earliest_round
                )
            ]
            for condition in pending:
                if self._satisfied(
                    condition,
                    provider_available=provider_available,
                    budget_available=budget_available,
                    available_fact_ids=available_fact_ids,
                    changed_obligation_ids=changed_obligation_ids,
                    reviewer_available=reviewer_available,
                    recompiled_task_ids=recompiled_task_ids,
                    user_intervention=user_intervention,
                    config_changed=config_changed,
                ):
                    condition.satisfied = True
                    condition.satisfied_at = utc_now_iso()
            if task.wake_conditions and all(
                condition.satisfied for condition in task.wake_conditions
            ):
                prior = task.status
                task.status = TaskStatus.READY
                task.last_transition_round = current_round
                task.transition_history.append(
                    {
                        "from": prior.value,
                        "to": TaskStatus.READY.value,
                        "round": current_round,
                        "reason": "wake_conditions_satisfied",
                    }
                )
                woken.append(task)
        return woken

    @staticmethod
    def _satisfied(
        condition: WakeCondition,
        *,
        provider_available: bool,
        budget_available: bool,
        available_fact_ids: Collection[str],
        changed_obligation_ids: Collection[str],
        reviewer_available: bool,
        recompiled_task_ids: Collection[str],
        user_intervention: bool,
        config_changed: bool,
    ) -> bool:
        checks: dict[WakeConditionKind, bool] = {
            WakeConditionKind.PROVIDER_AVAILABLE: provider_available,
            WakeConditionKind.BUDGET_AVAILABLE: budget_available,
            WakeConditionKind.DEPENDENCY_FACT_AVAILABLE: (
                condition.target_id in available_fact_ids
            ),
            WakeConditionKind.OBLIGATION_STATE_CHANGED: (
                condition.target_id in changed_obligation_ids
            ),
            WakeConditionKind.REVIEWER_AVAILABLE: reviewer_available,
            WakeConditionKind.TASK_RECOMPILED: (
                condition.target_id in recompiled_task_ids
            ),
            WakeConditionKind.USER_INTERVENTION: user_intervention,
            WakeConditionKind.CONFIG_CHANGED: config_changed,
            # ``evaluate`` has already enforced earliest_round.
            WakeConditionKind.ROUND_ADVANCED: True,
        }
        return checks[condition.kind]


class RouteWakeController:
    """Keep deferred work wakeable without misclassifying a route as terminal."""

    def __init__(
        self,
        route_registry: RouteRegistry,
        tasks: dict[str, ExecutableTaskRecord],
        *,
        freeze_records: Mapping[str, RouteFreezeRecord] | None = None,
    ) -> None:
        self.route_registry = route_registry
        self.tasks = tasks
        self.freeze_records = dict(freeze_records or {})
        self.scheduler = WakeScheduler(self.tasks)

    def wait_for_task(
        self,
        route_id: str,
        task_id: str,
        *,
        current_round: int,
    ) -> RouteDescriptor:
        route = self.route_registry.get(route_id)
        try:
            task = self.tasks[task_id]
        except KeyError as exc:
            raise KeyError(f"unknown executable task: {task_id}") from exc
        if route_id not in task.route_ids:
            raise ValueError("waiting task does not belong to the route")
        if route.status in {
            RouteStatus.FROZEN,
            RouteStatus.TERMINAL,
            RouteStatus.FROZEN_STALLED,
            RouteStatus.REFUTED,
            RouteStatus.MERGED,
            RouteStatus.ABANDONED,
            RouteStatus.COMPLETED,
        }:
            raise ValueError("terminal or frozen route cannot enter automatic waiting")
        if task.status in ExecutableTaskController.TERMINAL_STATUSES:
            raise ValueError("a route cannot wait on a terminal task")
        if not any(not condition.satisfied for condition in task.wake_conditions):
            raise ValueError("waiting requires an unsatisfied wake condition")
        route.status = RouteStatus.WAITING
        route.frozen_reason = (
            f"waiting for executable task {task_id} from round {current_round}"
        )
        route.frozen_signature = stable_hash(
            {
                "route_id": route_id,
                "task_id": task_id,
                "wake_condition_ids": [
                    condition.condition_id
                    for condition in task.wake_conditions
                    if not condition.satisfied
                ],
            }
        )
        self.route_registry.recompute_neighbors()
        return route

    def evaluate(
        self,
        *,
        current_round: int,
        provider_available: bool = False,
        budget_available: bool = False,
        available_fact_ids: Collection[str] = (),
        changed_obligation_ids: Collection[str] = (),
        reviewer_available: bool = False,
        recompiled_task_ids: Collection[str] = (),
        user_intervention: bool = False,
        config_changed: bool = False,
    ) -> list[RouteDescriptor]:
        already_wakeable = [
            task
            for task in self.tasks.values()
            if task.status
            in {
                TaskStatus.CREATED,
                TaskStatus.ASSIGNED,
                TaskStatus.READY,
            }
        ]
        newly_woken = self.scheduler.evaluate(
            current_round=current_round,
            provider_available=provider_available,
            budget_available=budget_available,
            available_fact_ids=available_fact_ids,
            changed_obligation_ids=changed_obligation_ids,
            reviewer_available=reviewer_available,
            recompiled_task_ids=recompiled_task_ids,
            user_intervention=user_intervention,
            config_changed=config_changed,
        )
        tasks = {
            task.task_id: task for task in [*already_wakeable, *newly_woken]
        }.values()
        woken: dict[str, RouteDescriptor] = {}
        for task in tasks:
            for route_id in task.route_ids:
                try:
                    route = self.route_registry.get(route_id)
                except KeyError:
                    continue
                if route.status != RouteStatus.WAITING:
                    continue
                self.route_registry.reactivate(
                    route_id,
                    revision_summary=(
                        f"wake conditions satisfied for executable task {task.task_id}"
                    ),
                )
                woken[route_id] = route
        return [woken[key] for key in sorted(woken)]

    def freeze(self, record: RouteFreezeRecord) -> RouteFreezeRecord:
        route = self.route_registry.get(record.route_id)
        condition_index = {
            condition.condition_id: condition
            for task in self.tasks.values()
            for condition in task.wake_conditions
        }
        unknown_condition_ids = sorted(
            set(record.wake_condition_ids) - set(condition_index)
        )
        if unknown_condition_ids:
            raise ValueError(
                "route freeze references unknown wake condition(s): "
                + ", ".join(unknown_condition_ids)
            )
        unknown_task_ids = sorted(set(record.blocker_task_ids) - set(self.tasks))
        if unknown_task_ids:
            raise ValueError(
                "route freeze references unknown blocker task(s): "
                + ", ".join(unknown_task_ids)
            )
        referenced_conditions = [
            condition_index[condition_id]
            for condition_id in record.wake_condition_ids
            if condition_id in condition_index
        ]
        automatic_wakes = [
            condition
            for condition in referenced_conditions
            if not condition.satisfied
            and condition.kind != WakeConditionKind.USER_INTERVENTION
        ]
        blocker_tasks = [
            self.tasks[task_id]
            for task_id in record.blocker_task_ids
            if task_id in self.tasks
        ]
        blocker_automatic_wakes = [
            condition
            for task in blocker_tasks
            for condition in task.wake_conditions
            if not condition.satisfied
            and condition.kind != WakeConditionKind.USER_INTERVENTION
        ]
        if automatic_wakes or blocker_automatic_wakes:
            raise ValueError("route with an automatic wake condition cannot be frozen")
        if not record.requires_user_intervention:
            raise ValueError("frozen route requires explicit user intervention")
        route.status = RouteStatus.FROZEN
        route.frozen_reason = record.reason.strip() or "explicit intervention required"
        route.frozen_signature = stable_hash(record.model_dump(mode="json"))
        route.cooldown_until_round = None
        self.freeze_records[record.route_id] = record
        self.route_registry.recompute_neighbors()
        return record
