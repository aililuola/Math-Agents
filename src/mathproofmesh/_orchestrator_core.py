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


class CoreOrchestratorMixin:
    def __init__(
        self,
        config: SystemConfig,
        *,
        mock_responders: dict[str, MockResponder] | None = None,
        activity_listener: ActivityListener | None = None,
    ) -> None:
        self.config = config
        self.mock_responders = mock_responders or {}
        self.activity_listener = activity_listener

    async def solve(self, problem_text: str, *, run_id: str | None = None) -> RunResult:
        """Run the sparse exploration, verification, repair, and final-audit pipeline."""
        if not problem_text or not problem_text.strip():
            raise ValueError("problem_text must be non-empty")

        run_id = run_id or self._make_run_id(problem_text)
        store = ArtifactStore(self.config.runtime.run_root, run_id)
        activity = ActivityStream(
            store,
            language=self.config.runtime.output_language,
            listener=self.activity_listener,
            persist=self.config.runtime.activity_persist,
        )
        pool = AgentPool(self.config, mock_responders=self.mock_responders)
        runner = StructuredAgentRunner(self.config, pool, store, activity=activity)
        prompts = PromptFactory(self.config.runtime.output_language)
        router = SparseTopologyRouter(self.config, pool, store)
        budget_manager = AdaptiveBudgetManager(self.config)
        allocator = SoftBudgetAllocator(self.config, runner.ledger)
        memory = LemmaMemory(store)
        tools = ToolBroker(self.config, store)

        problem = ProblemContract(
            exact_statement=problem_text,
            normalized_statement=self._normalize_statement(problem_text),
            problem_kind=ProblemKind.UNKNOWN,
            deliverables=["Give a correct answer and an explicit, auditable derivation or proof."],
            hard_constraints=[
                "Do not change hypotheses, quantifiers, domains, or requested conclusions.",
                "Distinguish proved claims from conjectures and unresolved gaps.",
            ],
            allowed_tools=self._allowed_tools(),
            output_language=self.config.runtime.output_language,
        )
        store.write_json("structured", "problem_contract", problem)
        store.write_json("structured", "config_redacted", self.config.redacted_dict())
        store.append_event(
            "run_started",
            {
                "problem_id": problem.problem_id,
                "problem_hash": problem.integrity_hash,
                "system_name": self.config.system_name,
            },
        )
        run_task = activity.start_task(
            "run",
            title=activity.text("启动多 Agent 数学求解", "Starting the multi-agent mathematics run"),
            detail=activity.text(
                f"已冻结原题；启用 {len(pool.agents)} 个隔离子 Agent",
                f"Problem frozen; {len(pool.agents)} isolated sub-agents are available",
            ),
            stage="run",
            importance=ActivityImportance.MAJOR,
            metrics={"agent_count": len(pool.agents), "problem_hash": problem.integrity_hash},
        )
        state = SolveState(
            triage=None,
            strategies=[],
            attempts=[],
            reports=[],
            aggregate_reports={},
            meta_reviews=[],
        )

        try:
            triage_task = activity.start_task(
                "stage",
                title=activity.text("分析题型、难度与证明风险", "Analyzing problem type and proof risks"),
                stage="triage",
                parent_task_id=run_task,
                importance=ActivityImportance.MAJOR,
            )
            state.triage = await self._triage(problem, runner, prompts, store)
            problem.problem_kind = state.triage.problem_kind
            activity.complete_task(
                triage_task,
                title=activity.text("题目分析完成", "Problem triage completed"),
                detail=activity.text(
                    f"题型 {state.triage.problem_kind.value}；难度 {state.triage.difficulty.value}",
                    f"Kind {state.triage.problem_kind.value}; difficulty {state.triage.difficulty.value}",
                ),
                stage="triage",
                parent_task_id=run_task,
                importance=ActivityImportance.MAJOR,
            )
            self._checkpoint(store, "triage", state, memory, runner)

            strategy_task = activity.start_task(
                "stage",
                title=activity.text("生成差异化证明路线", "Generating diverse proof strategies"),
                stage="strategy_generation",
                parent_task_id=run_task,
                importance=ActivityImportance.MAJOR,
            )
            state.strategies = await self._initial_strategies(
                problem, state.triage, runner, prompts, router, store
            )
            assignments = router.assign_explorers(state.strategies)
            activity.complete_task(
                strategy_task,
                title=activity.text("证明路线已生成并分配", "Proof strategies generated and assigned"),
                detail=activity.text(
                    f"保留 {len(state.strategies)} 条相互区分的路线",
                    f"Selected {len(state.strategies)} distinct routes",
                ),
                stage="strategy_generation",
                parent_task_id=run_task,
                importance=ActivityImportance.MAJOR,
                metrics={"strategy_count": len(state.strategies)},
            )

            exploration_task = activity.start_task(
                "stage",
                title=activity.text("并行探索不同证明方向", "Exploring proof directions in parallel"),
                stage="initial_exploration",
                parent_task_id=run_task,
                importance=ActivityImportance.MAJOR,
                metrics={"path_count": len(assignments)},
            )
            initial_attempts = await self._parallel_initial_exploration(
                problem, assignments, runner, prompts, router, memory, store
            )
            state.attempts.extend(initial_attempts)
            await self._extract_claims_many(
                problem,
                initial_attempts,
                runner,
                prompts,
                memory,
                store,
                budget_bucket="breadth",
            )
            counts = {
                "complete": sum(a.status == AttemptStatus.COMPLETE for a in initial_attempts),
                "partial": sum(a.status == AttemptStatus.PARTIAL for a in initial_attempts),
                "failed": sum(a.status == AttemptStatus.FAILED for a in initial_attempts),
            }
            activity.complete_task(
                exploration_task,
                title=activity.text("首轮并行探索完成", "Initial parallel exploration completed"),
                detail=activity.text(
                    f"完整 {counts['complete']}；部分 {counts['partial']}；失败 {counts['failed']}；候选引理 {len(memory.claims)}",
                    f"Complete {counts['complete']}; partial {counts['partial']}; failed {counts['failed']}; claims {len(memory.claims)}",
                ),
                stage="initial_exploration",
                parent_task_id=run_task,
                importance=ActivityImportance.MAJOR,
                metrics={**counts, "claim_count": len(memory.claims)},
            )
            self._checkpoint(store, "initial_exploration", state, memory, runner)

            candidates = self._rank_attempts(state.attempts)[: self.config.budget.candidates_to_verify]
            verify_task = activity.start_task(
                "stage",
                title=activity.text("验证候选证明", "Verifying candidate proofs"),
                detail=activity.text(
                    "依次执行结构审查与逐步数学核查",
                    "Running structural and detailed mathematical audits",
                ),
                stage="initial_verification",
                parent_task_id=run_task,
                importance=ActivityImportance.MAJOR,
                metrics={"candidate_count": len(candidates)},
            )
            bundles = await self._verify_attempts_many(
                problem, candidates, runner, prompts, router, memory, tools, store
            )
            self._record_verification_bundles(state, bundles)
            verdicts = {verdict.value: 0 for verdict in VerificationVerdict}
            for bundle in bundles:
                verdicts[bundle.aggregate.verdict.value] += 1
            activity.complete_task(
                verify_task,
                title=activity.text("首轮候选验证完成", "Initial candidate verification completed"),
                detail=activity.text(
                    f"通过 {verdicts['pass']}；不通过 {verdicts['fail']}；待定 {verdicts['uncertain']}",
                    f"Pass {verdicts['pass']}; fail {verdicts['fail']}; uncertain {verdicts['uncertain']}",
                ),
                stage="initial_verification",
                parent_task_id=run_task,
                importance=ActivityImportance.MAJOR,
                metrics=verdicts,
            )

            if state.attempts and runner.ledger.remaining_calls > 0:
                review = await self._meta_review(
                    problem, state.attempts, state.aggregate_reports, runner, prompts, store
                )
                state.meta_reviews.append(review)
                activity.info(
                    "meta_review_completed",
                    title=activity.text("综合复核完成", "Meta-review completed"),
                    detail=activity.text(
                        "已有候选可进入综合" if review.can_synthesize else "仍需继续探索或修订",
                        "A candidate is ready for synthesis" if review.can_synthesize else "Further exploration or repair is required",
                    ),
                    stage="meta_review",
                    parent_task_id=run_task,
                    importance=ActivityImportance.MAJOR,
                    metrics={
                        "can_synthesize": review.can_synthesize,
                        "selected_target_id": review.selected_target_id,
                        "failure_level": review.failure_level.value,
                    },
                )
            self._checkpoint(store, "initial_verification", state, memory, runner)

            for round_index in range(1, self.config.budget.max_rounds):
                if self._has_synthesis_ready_candidate(state) and allocator.should_protect_finish(
                    state.aggregate_reports.values()
                ):
                    break
                stats = budget_manager.build_path_stats(
                    state.strategies, state.attempts, list(state.aggregate_reports.values())
                )
                decision = budget_manager.decide(
                    stats,
                    current_path_count=len(state.strategies),
                    remaining_calls=runner.ledger.remaining_calls,
                    final_verified=False,
                    max_actions=2,
                    bucket_pressure=allocator.pressure_snapshot(),
                )
                store.write_json("structured", f"budget_decision_round_{round_index}", decision)
                store.append_event("budget_decision", decision)
                performed = await self._perform_adaptive_actions(
                    problem,
                    round_index,
                    state,
                    decision.actions,
                    runner,
                    prompts,
                    router,
                    memory,
                    tools,
                    store,
                    allocator,
                )
                if performed and runner.ledger.remaining_calls > allocator.minimum_finish_reserve:
                    state.meta_reviews.append(
                        await self._meta_review(
                            problem, state.attempts, state.aggregate_reports, runner, prompts, store
                        )
                    )
                self._checkpoint(store, f"adaptive_round_{round_index}", state, memory, runner)
                activity.info(
                    "adaptive_round_completed",
                    title=activity.text(
                        f"自适应调度第 {round_index} 轮完成",
                        f"Adaptive scheduling round {round_index} completed",
                    ),
                    detail=activity.text(
                        "已执行高价值动作" if performed else "没有发现值得继续投入的动作",
                        "High-value actions executed" if performed else "No further high-value action found",
                    ),
                    stage="adaptive_round",
                    parent_task_id=run_task,
                    importance=ActivityImportance.MAJOR,
                    metrics={"round_index": round_index, "performed": performed},
                )
                if not performed:
                    break

            synthesizer = None
            if state.attempts:
                activity.info(
                    "synthesis_started",
                    title=activity.text("综合候选路线形成最终证明", "Synthesizing a final proof"),
                    stage="synthesis",
                    parent_task_id=run_task,
                    importance=ActivityImportance.MAJOR,
                )
                state.final_proof, synthesizer = await self._synthesize(
                    problem, state, runner, prompts, router, memory, store
                )
            if state.final_proof is not None:
                final_bundle = await self._verify_final(
                    problem,
                    state.final_proof,
                    synthesizer,
                    runner,
                    prompts,
                    router,
                    memory,
                    tools,
                    store,
                )
                state.reports.extend(final_bundle.reports)
                state.final_verification = final_bundle.aggregate
                revisions = 0
                while (
                    state.final_verification.verdict != VerificationVerdict.PASS
                    and revisions < self.config.budget.max_revisions
                    and state.final_verification.failure_level != FailureLevel.STRATEGY
                    and allocator.can_spend(
                        "synthesis",
                        allocator.estimate_revision_cycle_calls(),
                        protect_finish=False,
                        has_candidate=True,
                    )
                ):
                    revised = await self._revise_final(
                        problem,
                        state.final_proof,
                        state.final_verification,
                        synthesizer,
                        runner,
                        prompts,
                        memory,
                        store,
                        revisions + 1,
                    )
                    if revised is None:
                        break
                    state.final_proof = revised
                    final_bundle = await self._verify_final(
                        problem,
                        state.final_proof,
                        synthesizer,
                        runner,
                        prompts,
                        router,
                        memory,
                        tools,
                        store,
                    )
                    state.reports.extend(final_bundle.reports)
                    state.final_verification = final_bundle.aggregate
                    revisions += 1
                activity.info(
                    "final_audit_completed",
                    title=activity.text("最终独立审计完成", "Final independent audit completed"),
                    detail=activity.text(
                        f"结论 {state.final_verification.verdict.value}；置信度 {state.final_verification.confidence:.2f}",
                        f"Verdict {state.final_verification.verdict.value}; confidence {state.final_verification.confidence:.2f}",
                    ),
                    stage="final_verification",
                    parent_task_id=run_task,
                    importance=ActivityImportance.MAJOR,
                    metrics={
                        "verdict": state.final_verification.verdict.value,
                        "confidence": state.final_verification.confidence,
                        "revision_count": revisions,
                    },
                )

            return self._persist_result(
                run_id,
                self._run_status(state),
                problem,
                state,
                pool,
                runner,
                router,
                memory,
                store,
                activity,
                run_task,
            )
        except BudgetExhaustedError as exc:
            state.budget_exhausted = True
            logger.warning("Budget exhausted: %s", exc)
            store.append_event("budget_exhausted", {"error": str(exc)})
            return self._persist_result(
                run_id,
                RunStatus.BUDGET_EXHAUSTED,
                problem,
                state,
                pool,
                runner,
                router,
                memory,
                store,
                activity,
                run_task,
                summary_override=(
                    "Global call/token/cost budget was exhausted; partial artifacts were preserved."
                ),
            )
        except Exception as exc:
            logger.exception("MathProofMesh run failed")
            store.append_event("run_failed", {"type": type(exc).__name__, "error": str(exc)})
            return self._persist_result(
                run_id,
                RunStatus.FAILED,
                problem,
                state,
                pool,
                runner,
                router,
                memory,
                store,
                activity,
                run_task,
                summary_override=f"Run failed with {type(exc).__name__}: {exc}",
            )
        finally:
            await pool.aclose()

