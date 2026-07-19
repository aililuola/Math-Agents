# ruff: noqa: F401
from __future__ import annotations

import logging
from typing import Any

from .activity import ActivityImportance, ActivityListener, ActivityStatus, ActivityStream
from .agents import BudgetExhaustedError, StructuredAgentRunner
from .budget import AdaptiveBudgetManager, SoftBudgetAllocator
from .config import SystemConfig
from .llm.mock import MockResponder
from .llm.pool import AgentPool
from .memory import LemmaMemory
from .prompts import PromptFactory
from .report import write_run_report
from .schemas import (
    ActionKind,
    AttemptStatus,
    FailureLevel,
    ProblemContract,
    ProblemKind,
    RunResult,
    RunStatus,
    VerificationVerdict,
)
from .store import ArtifactStore
from .tools import ToolBroker
from .topology import SparseTopologyRouter

from ._orchestrator_types import SolveState

logger = logging.getLogger(__name__)


class RuntimeOrchestratorMixin:
    async def _perform_adaptive_actions(
        self,
        problem: ProblemContract,
        round_index: int,
        state: SolveState,
        actions: list[Any],
        runner: StructuredAgentRunner,
        prompts: PromptFactory,
        router: SparseTopologyRouter,
        memory: LemmaMemory,
        tools: ToolBroker,
        store: ArtifactStore,
        allocator: SoftBudgetAllocator,
    ) -> bool:
        performed = False
        for action in actions:
            if action.action in {ActionKind.STOP, ActionKind.SYNTHESIZE}:
                continue
            bucket = allocator.bucket_for_action(action.action)
            if not allocator.can_spend(
                bucket,
                allocator.estimate_action_calls(action.action),
                protect_finish=True,
                has_candidate=bool(state.attempts),
            ):
                continue
            if action.action == ActionKind.DEEPEN and action.strategy_id:
                attempt = await self._deepen_path(
                    problem,
                    action.strategy_id,
                    round_index,
                    state,
                    runner,
                    prompts,
                    router,
                    memory,
                    store,
                )
                if attempt is not None:
                    state.attempts.append(attempt)
                    await self._extract_claims_many(
                        problem,
                        [attempt],
                        runner,
                        prompts,
                        memory,
                        store,
                        budget_bucket="depth",
                    )
                    performed = True
            elif action.action == ActionKind.WIDEN:
                attempts = await self._widen(
                    problem,
                    state.triage,
                    round_index,
                    state,
                    runner,
                    prompts,
                    router,
                    memory,
                    store,
                )
                if attempts:
                    state.attempts.extend(attempts)
                    await self._extract_claims_many(
                        problem,
                        attempts,
                        runner,
                        prompts,
                        memory,
                        store,
                        budget_bucket="breadth",
                    )
                    performed = True
            elif action.action == ActionKind.VERIFY and action.target_id:
                target = next((a for a in state.attempts if a.attempt_id == action.target_id), None)
                if target is not None:
                    bundle = await self._verify_attempt(
                        problem, target, runner, prompts, router, memory, tools, store
                    )
                    self._record_verification_bundles(state, [bundle])
                    performed = True

        unverified = [
            attempt
            for attempt in self._rank_attempts(state.attempts)
            if attempt.attempt_id not in state.aggregate_reports
        ]
        if (
            unverified
            and allocator.can_spend(
                "verification",
                allocator.estimate_action_calls(ActionKind.VERIFY),
                protect_finish=True,
                has_candidate=True,
            )
            and self._attempt_local_quality(unverified[0]) >= 0.48
        ):
            bundle = await self._verify_attempt(
                problem, unverified[0], runner, prompts, router, memory, tools, store
            )
            self._record_verification_bundles(state, [bundle])
            performed = True
        return performed

    def _persist_result(
        self,
        run_id: str,
        status: RunStatus,
        problem: ProblemContract,
        state: SolveState,
        pool: AgentPool,
        runner: StructuredAgentRunner,
        router: SparseTopologyRouter,
        memory: LemmaMemory,
        store: ArtifactStore,
        activity: ActivityStream,
        run_task: str,
        *,
        summary_override: str | None = None,
    ) -> RunResult:
        result = self._build_result(
            run_id,
            status,
            problem,
            state,
            pool.metrics(),
            runner,
            pool,
            store,
            memory,
            summary_override=summary_override,
        )
        router.export()
        store.write_json("structured", "run_result", result)
        store.append_event(
            "run_completed",
            {
                "status": result.status.value,
                "total_calls": result.total_calls,
                "total_tokens": result.total_usage.total_tokens,
            },
        )
        if status == RunStatus.FAILED:
            activity.close_open_tasks(
                status=ActivityStatus.FAILED,
                detail=activity.text("运行异常，保留当前产物", "Run failed; current artifacts preserved"),
                exclude_task_ids={run_task},
            )
            activity.fail_task(
                run_task,
                title=activity.text("运行异常终止", "Run terminated with an error"),
                detail=result.summary,
                stage="run",
                importance=ActivityImportance.MAJOR,
            )
        elif status == RunStatus.BUDGET_EXHAUSTED:
            activity.close_open_tasks(
                status=ActivityStatus.WARNING,
                detail=activity.text("预算耗尽，保留当前产物", "Budget exhausted; current artifacts preserved"),
                exclude_task_ids={run_task},
            )
            activity.warn_task(
                run_task,
                title=activity.text("预算耗尽，保留部分结果", "Budget exhausted; partial results preserved"),
                detail=result.summary,
                stage="run",
                importance=ActivityImportance.MAJOR,
            )
        else:
            activity.complete_task(
                run_task,
                title=activity.text("多 Agent 求解结束", "Multi-agent run completed"),
                detail=activity.text(
                    f"状态 {result.status.value}；调用 {result.total_calls} 次；共 {result.total_usage.total_tokens:,} tokens",
                    f"Status {result.status.value}; {result.total_calls} calls; {result.total_usage.total_tokens:,} tokens",
                ),
                stage="run",
                importance=ActivityImportance.MAJOR,
                metrics={
                    "status": result.status.value,
                    "total_calls": result.total_calls,
                    "total_tokens": result.total_usage.total_tokens,
                },
            )
        activity.finalize()
        write_run_report(store, result)
        return result
