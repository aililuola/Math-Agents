from __future__ import annotations

import asyncio
import json
import logging
import re
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Iterable, Sequence, TypeVar

from pydantic import BaseModel

from .activity import (
    ActivityImportance,
    ActivityListener,
    ActivityStatus,
    ActivityStream,
)
from .agents import (
    BudgetExhaustedError,
    StructuredAgentRunner,
    StructuredCallResult,
    StructuredOutputError,
)
from .budget import AdaptiveBudgetManager, SoftBudgetAllocator
from .communication.broker import MessageBroker
from .communication.route_registry import RouteRegistry
from .config import SystemConfig
from .computation.policy import ComputationContext
from .context_policy import (
    ContextPurpose,
    build_admissible_fact_context,
    explicit_dependency_refs,
    select_legacy_claim_context,
)
from .continuation import (
    attempt_from_checkpoint,
    checkpoint_to_route_message,
    local_delta_verification,
    make_genesis_checkpoint,
    merge_verified_delta,
    normalize_delta_claims,
)
from .deep_exploration import (
    DeepExplorationRegistry,
    ExplorationAdmission,
    ExplorationEvidence,
    ExplorationOutcome,
    ExplorationSignature,
)
from .llm.mock import MockResponder
from .llm.pool import AgentPool, AgentRuntime, ProviderCircuitOpenError
from .inspiration.engine import InspirationEngine
from .inspiration.context import build_inspiration_prompt_context
from .inspiration.trigger_policy import InspirationSnapshot
from .memory import LemmaMemory, TypedMemory
from .prompts import PromptBundle, PromptFactory
from .report import write_hierarchical_reports, write_run_report
from .stall_recovery import (
    PostFailureBottleneckExtractor,
    classify_no_artifact_failure,
)
from .proof_graph.bridges import BridgeBroker
from .proof_graph.contradictions import ContradictionBroker
from .proof_graph.matching import DuplicateRouteDetector
from .proof_graph.store import ProofGraphStore
from .schemas import (
    ActionKind,
    AgentMetric,
    AttemptStatus,
    BlindReviewPacket,
    BlindVerificationReport,
    BrokerDecision,
    CandidateAssessment,
    ClaimBatch,
    ClaimCard,
    ClaimStatus,
    ComputationDecision,
    ComputationDecisionStatus,
    ComputationHint,
    ComputationMethod,
    ComputationPurpose,
    ContinuationAction,
    ContinuationTurn,
    Difficulty,
    EvidenceType,
    EvidenceRef,
    EvidenceStrength,
    ExperimentOutcome,
    ExperimentProgram,
    ExperimentResult,
    ExperimentSpec,
    ExecutionStatus,
    FailureLevel,
    FinalProof,
    InitialExplorationAction,
    InitialExplorationTurn,
    InspirationMechanism,
    InspirationContextMode,
    InspirationProposal,
    InspirationReview,
    MetaReview,
    MemoryTier,
    MessageEnvelope,
    MessageReceipt,
    MessageType,
    MathStatus,
    ObligationKind,
    PostFailureBottleneckDiagnostic,
    ProblemContract,
    ProblemKind,
    ProofAttempt,
    ProofCheckpoint,
    ProofDelta,
    ProofObligation,
    ReceiptStatus,
    ResearchProgressReport,
    RunResult,
    RunStatus,
    RouteRole,
    Severity,
    StrategyCard,
    StrategySet,
    TriageResult,
    ToolAuditReport,
    UsageRecord,
    VerificationIssue,
    VerificationReport,
    VerificationStage,
    VerificationVerdict,
    WorkingProofCheckpoint,
    new_id,
    stable_hash,
)
from .store import ArtifactStore
from .teams.role_runner import RoleRunner
from .teams.route_team import RouteTeam
from .tools import ToolBroker
from .topology import (
    SparseTopologyRouter,
    jaccard_similarity,
    select_sparse_route_neighbors,
    strategy_text,
)
from .verification import (
    AgentCapabilityProfile,
    ValidationEscalationExecutor,
    ValidationEscalator,
    infer_capability_domain,
)
from .verification.escalation import ValidationLevel, ValidationStepResult
from .synthesis_phase import (
    apply_blind_context_integrity_guard,
    build_blind_review_packet,
)
from .broker_phase import record_verified_message_usage
from .inspiration_phase import admit_inspiration_tasks
from .cross_route_phase import team_reviews_allow_global_share
from .resume_phase import export_hierarchical_checkpoint
from .route_pipeline import acknowledge_route_messages, build_route_prompt_context

logger = logging.getLogger(__name__)
T = TypeVar("T", bound=BaseModel)


@dataclass(slots=True)
class VerificationBundle:
    aggregate: VerificationReport
    reports: list[VerificationReport]


@dataclass(slots=True)
class SolveState:
    triage: TriageResult | None
    strategies: list[StrategyCard]
    attempts: list[ProofAttempt]
    reports: list[VerificationReport]
    aggregate_reports: dict[str, VerificationReport]
    meta_reviews: list[MetaReview]
    checkpoints: list[ProofCheckpoint]
    resumed: bool = False
    resumed_from_checkpoint_id: str | None = None
    final_proof: FinalProof | None = None
    final_verification: VerificationReport | None = None
    budget_exhausted: bool = False
    route_registry: RouteRegistry | None = None
    message_broker: MessageBroker | None = None
    proof_graph: ProofGraphStore | None = None
    typed_memory: TypedMemory | None = None
    inspiration_engine: InspirationEngine | None = None
    bridge_broker: BridgeBroker | None = None
    contradiction_broker: ContradictionBroker | None = None
    duplicate_route_detector: DuplicateRouteDetector | None = None
    current_round: int = 0
    graph_frozen: bool = False
    proof_debt_history: dict[str, list[float]] | None = None
    capability_profile: AgentCapabilityProfile | None = None
    validation_escalator: ValidationEscalator | None = None
    final_repair_failed: bool = False
    route_team_reviews: dict[str, list[dict[str, Any]]] | None = None
    capability_domain: str = "algebra"
    math_status: MathStatus = MathStatus.INCONCLUSIVE
    execution_status: ExecutionStatus = ExecutionStatus.COMPLETED
    research_progress_report: ResearchProgressReport | None = None
    deep_exploration_registry: DeepExplorationRegistry | None = None


class ProofMeshOrchestrator:
    """
    Sparse, verification-first multi-agent orchestrator for difficult mathematics.

    The default graph is deliberately not a round-table chat:
      planner -> isolated explorers -> independent structural/detailed reviewers
      -> meta-reviewer -> targeted widen/deepen -> synthesizer -> final verifier.

    Raw model outputs are immutable artifacts. Inter-agent transfers use typed packets
    with provenance, content hashes, dependency IDs, and verification status.
    """

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
        prompts = PromptFactory(
            self.config.runtime.output_language,
            computation_enabled=self.config.computation.enabled,
        )
        router = SparseTopologyRouter(self.config, pool, store)
        budget_manager = AdaptiveBudgetManager(self.config)
        allocator = SoftBudgetAllocator(self.config, runner.ledger)
        memory = LemmaMemory(store)
        tools = ToolBroker(self.config, store, activity)

        problem = ProblemContract(
            exact_statement=problem_text,
            normalized_statement=self._normalize_statement(problem_text),
            problem_kind=ProblemKind.UNKNOWN,
            deliverables=[
                "Give a correct answer and an explicit, auditable derivation or proof."
            ],
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
        run_activity_task = activity.start_task(
            "run",
            title=activity.text(
                "启动多 Agent 数学求解", "Starting the multi-agent mathematics run"
            ),
            detail=activity.text(
                f"已冻结原题；启用 {len(pool.agents)} 个隔离子 Agent",
                f"Problem contract frozen; {len(pool.agents)} isolated sub-agents are available",
            ),
            stage="run",
            importance=ActivityImportance.MAJOR,
            metrics={
                "agent_count": len(pool.agents),
                "problem_hash": problem.integrity_hash,
            },
        )
        activity.info(
            "problem_contract_ready",
            title=activity.text("题目契约已建立", "Problem contract created"),
            detail=activity.text(
                "后续阶段不得改变原题的条件、量词、定义域或目标结论",
                "Later stages may not alter hypotheses, quantifiers, domains, or the requested conclusion",
            ),
            stage="run",
            parent_task_id=run_activity_task,
            importance=ActivityImportance.NORMAL,
        )

        state = SolveState(
            triage=None,
            strategies=[],
            attempts=[],
            reports=[],
            aggregate_reports={},
            meta_reviews=[],
            checkpoints=[],
            proof_debt_history={},
            route_team_reviews={},
        )
        self._initialize_hierarchical_runtime(
            state,
            problem=problem,
            store=store,
            activity=activity,
            memory=memory,
        )

        try:
            triage_task = activity.start_task(
                "stage",
                title=activity.text(
                    "分析题型、难度与证明风险",
                    "Analyzing the problem type, difficulty, and risks",
                ),
                detail=activity.text(
                    "识别题目结构并决定适合的证明组织方式",
                    "Classifying the task and choosing an appropriate proof organization",
                ),
                stage="triage",
                parent_task_id=run_activity_task,
                importance=ActivityImportance.MAJOR,
            )
            state.triage = await self._triage(problem, runner, prompts, store)
            problem.problem_kind = state.triage.problem_kind
            state.capability_domain = infer_capability_domain(
                problem.exact_statement,
                state.triage.rationale,
                *state.triage.key_risks,
                *state.triage.likely_tools,
            )
            pool.set_capability_context(
                state.capability_profile,
                domain=state.capability_domain,
            )
            activity.complete_task(
                triage_task,
                title=activity.text("题目分析完成", "Problem triage completed"),
                detail=activity.text(
                    f"题型 {state.triage.problem_kind.value}；难度 {state.triage.difficulty.value}；建议 {state.triage.suggested_paths} 条路线",
                    f"Kind {state.triage.problem_kind.value}; difficulty {state.triage.difficulty.value}; {state.triage.suggested_paths} paths suggested",
                ),
                stage="triage",
                parent_task_id=run_activity_task,
                importance=ActivityImportance.MAJOR,
                metrics={
                    "problem_kind": state.triage.problem_kind.value,
                    "difficulty": state.triage.difficulty.value,
                    "suggested_paths": state.triage.suggested_paths,
                },
            )
            self._checkpoint(store, "triage", state, memory, runner)

            strategy_task = activity.start_task(
                "stage",
                title=activity.text(
                    "生成差异化证明路线", "Generating diverse proof strategies"
                ),
                detail=activity.text(
                    "要求各路线采用不同的关键数学机制，避免重复探索",
                    "Requiring distinct mathematical mechanisms to avoid duplicate exploration",
                ),
                stage="strategy_generation",
                parent_task_id=run_activity_task,
                importance=ActivityImportance.MAJOR,
            )
            state.strategies = await self._initial_strategies(
                problem,
                state.triage,
                runner,
                prompts,
                router,
                store,
            )
            assignments = router.assign_explorers(state.strategies)
            self._ensure_hierarchical_routes(
                state,
                router,
                assignments=assignments,
                round_index=0,
                activity=activity,
                store=store,
            )
            strategy_names = "；".join(
                strategy.title for strategy in state.strategies[:4]
            )
            if len(state.strategies) > 4:
                strategy_names += "；…"
            activity.complete_task(
                strategy_task,
                title=activity.text(
                    "证明路线已生成并分配", "Proof strategies generated and assigned"
                ),
                detail=activity.text(
                    f"保留 {len(state.strategies)} 条路线：{strategy_names}",
                    f"Selected {len(state.strategies)} diverse routes for isolated exploration",
                ),
                stage="strategy_generation",
                parent_task_id=run_activity_task,
                importance=ActivityImportance.MAJOR,
                metrics={"strategy_count": len(state.strategies)},
            )
            for strategy, agent in assignments:
                activity.info(
                    "strategy_assignment",
                    title=activity.text("分配探索路线", "Explorer route assigned"),
                    detail=strategy.title,
                    stage="strategy_assignment",
                    parent_task_id=strategy_task,
                    agent_id=agent.id,
                    importance=ActivityImportance.NORMAL,
                    metrics={"strategy_id": strategy.strategy_id},
                )
            self._checkpoint(store, "strategy_assignment", state, memory, runner)

            exploration_task = activity.start_task(
                "stage",
                title=activity.text(
                    "并行探索不同证明方向",
                    "Exploring different proof directions in parallel",
                ),
                detail=activity.text(
                    f"{len(assignments)} 个子 Agent 首轮相互隔离，分别深挖一条路线",
                    f"{len(assignments)} sub-agents are independently deepening separate routes",
                ),
                stage="initial_exploration",
                parent_task_id=run_activity_task,
                importance=ActivityImportance.MAJOR,
                metrics={"path_count": len(assignments)},
            )
            initial_attempts = await self._parallel_initial_exploration(
                problem,
                state,
                assignments,
                runner,
                prompts,
                router,
                memory,
                store,
                tools,
            )
            state.attempts.extend(initial_attempts)
            complete_count = sum(
                a.status == AttemptStatus.COMPLETE for a in initial_attempts
            )
            partial_count = sum(
                a.status == AttemptStatus.PARTIAL for a in initial_attempts
            )
            failed_count = sum(
                a.status == AttemptStatus.FAILED for a in initial_attempts
            )
            activity.complete_task(
                exploration_task,
                title=activity.text(
                    "首轮并行探索完成", "Initial parallel exploration completed"
                ),
                detail=activity.text(
                    f"完整候选 {complete_count} 条；部分结果 {partial_count} 条；失败路线 {failed_count} 条",
                    f"Complete {complete_count}; partial {partial_count}; failed {failed_count}",
                ),
                stage="initial_exploration",
                parent_task_id=run_activity_task,
                importance=ActivityImportance.MAJOR,
                metrics={
                    "complete": complete_count,
                    "partial": partial_count,
                    "failed": failed_count,
                },
            )
            claims_before = len(memory.claims)
            claims_task = activity.start_task(
                "stage",
                title=activity.text("归纳可复用引理", "Extracting reusable lemmas"),
                detail=activity.text(
                    "将各路线结果压缩成带假设、依赖、来源和作用域的结构化 ClaimCard",
                    "Compressing path results into structured ClaimCards with assumptions, dependencies, provenance, and scope",
                ),
                stage="claim_extraction",
                parent_task_id=run_activity_task,
                importance=ActivityImportance.MAJOR,
            )
            await self._extract_claims_many(
                problem,
                initial_attempts,
                runner,
                prompts,
                memory,
                store,
                budget_bucket="breadth",
            )
            claims_added = len(memory.claims) - claims_before
            activity.complete_task(
                claims_task,
                title=activity.text("引理归纳完成", "Claim extraction completed"),
                detail=activity.text(
                    f"新增 {claims_added} 条候选引理；当前引理库共 {len(memory.claims)} 条",
                    f"Added {claims_added} candidate claims; lemma memory now contains {len(memory.claims)} items",
                ),
                stage="claim_extraction",
                parent_task_id=run_activity_task,
                importance=ActivityImportance.MAJOR,
                metrics={
                    "claims_added": claims_added,
                    "claim_count": len(memory.claims),
                },
            )
            self._checkpoint(store, "initial_exploration", state, memory, runner)

            candidates = self._rank_attempts(state.attempts)[
                : self.config.budget.candidates_to_verify
            ]
            verification_task = activity.start_task(
                "stage",
                title=activity.text("验证候选证明", "Verifying candidate proofs"),
                detail=activity.text(
                    f"对排名靠前的 {len(candidates)} 条候选依次执行结构审查与逐步核查",
                    f"Running structural and step-level audits on {len(candidates)} leading candidates",
                ),
                stage="initial_verification",
                parent_task_id=run_activity_task,
                importance=ActivityImportance.MAJOR,
                metrics={"candidate_count": len(candidates)},
            )
            verification_bundles = await self._verify_attempts_many(
                problem,
                candidates,
                runner,
                prompts,
                router,
                memory,
                tools,
                store,
                state=state,
            )
            self._record_verification_bundles(state, verification_bundles)
            self._sync_hierarchical_artifacts(
                state,
                problem=problem,
                memory=memory,
                current_round=0,
                store=store,
            )
            verdict_counts = {verdict.value: 0 for verdict in VerificationVerdict}
            for bundle in verification_bundles:
                verdict_counts[bundle.aggregate.verdict.value] += 1
            activity.complete_task(
                verification_task,
                title=activity.text(
                    "首轮候选验证完成", "Initial candidate verification completed"
                ),
                detail=activity.text(
                    f"通过 {verdict_counts['pass']}；不通过 {verdict_counts['fail']}；待定 {verdict_counts['uncertain']}",
                    f"Pass {verdict_counts['pass']}; fail {verdict_counts['fail']}; uncertain {verdict_counts['uncertain']}",
                ),
                stage="initial_verification",
                parent_task_id=run_activity_task,
                importance=ActivityImportance.MAJOR,
                metrics=verdict_counts,
            )

            if state.attempts and runner.ledger.remaining_calls > 0:
                meta_task = activity.start_task(
                    "stage",
                    title=activity.text(
                        "汇总审查结果", "Consolidating review evidence"
                    ),
                    detail=activity.text(
                        "定位最早断裂的步骤，并判断应继续深挖、拓宽还是进入综合",
                        "Locating the first broken step and deciding whether to deepen, widen, or synthesize",
                    ),
                    stage="meta_review",
                    parent_task_id=run_activity_task,
                    importance=ActivityImportance.MAJOR,
                )
                review = await self._meta_review(
                    problem,
                    state.attempts,
                    state.aggregate_reports,
                    runner,
                    prompts,
                    store,
                )
                state.meta_reviews.append(review)
                self._apply_meta_route_controls(state, review, 0, store)
                meta_detail_zh = (
                    "已有候选可进入综合"
                    if review.can_synthesize
                    else f"暂不综合；失败层级 {review.failure_level.value}"
                )
                meta_detail_en = (
                    "A candidate is ready for synthesis"
                    if review.can_synthesize
                    else f"Synthesis deferred; failure level {review.failure_level.value}"
                )
                activity.complete_task(
                    meta_task,
                    title=activity.text("综合复核完成", "Meta-review completed"),
                    detail=activity.text(meta_detail_zh, meta_detail_en),
                    stage="meta_review",
                    parent_task_id=run_activity_task,
                    importance=ActivityImportance.MAJOR,
                    metrics={
                        "can_synthesize": review.can_synthesize,
                        "selected_target_id": review.selected_target_id,
                        "failure_level": review.failure_level.value,
                    },
                )
            self._checkpoint(store, "initial_verification", state, memory, runner)

            # Adaptive breadth/depth loop. Actions are selected from verified progress,
            # novelty, uncertainty, gaps, stagnation, and protected future call reserves.
            for round_index in range(1, self.config.budget.max_rounds):
                state.current_round = round_index
                round_task = activity.start_task(
                    "adaptive_round",
                    title=activity.text(
                        f"自适应调度第 {round_index} 轮",
                        f"Adaptive scheduling round {round_index}",
                    ),
                    detail=activity.text(
                        "根据进展、新颖性、不确定性和剩余预算平衡广度、深度与验证",
                        "Balancing breadth, depth, and verification from progress, novelty, uncertainty, and remaining budget",
                    ),
                    stage="adaptive_round",
                    parent_task_id=run_activity_task,
                    importance=ActivityImportance.MAJOR,
                    metrics={"round_index": round_index},
                )
                if self._has_synthesis_ready_candidate(state):
                    # A supported candidate exists; preserve calls for synthesis/final audit.
                    if allocator.should_protect_finish(
                        state.aggregate_reports.values()
                    ):
                        activity.complete_task(
                            round_task,
                            title=activity.text(
                                "保留预算并进入最终综合",
                                "Preserving budget and moving to final synthesis",
                            ),
                            detail=activity.text(
                                "已出现支持充分的候选，不再扩大搜索",
                                "A sufficiently supported candidate exists, so search expansion stops",
                            ),
                            stage="adaptive_round",
                            parent_task_id=run_activity_task,
                            importance=ActivityImportance.MAJOR,
                        )
                        break

                self._sync_hierarchical_artifacts(
                    state,
                    problem=problem,
                    memory=memory,
                    current_round=round_index,
                    store=store,
                )
                graph_signals = self._hierarchical_graph_signals(state)
                await self._run_inspiration_round(
                    state,
                    problem=problem,
                    remaining_calls=runner.ledger.remaining_calls,
                    store=store,
                    runner=runner,
                    prompts=prompts,
                    allocator=allocator,
                    router=router,
                    memory=memory,
                    tools=tools,
                )
                graph_signals = self._hierarchical_graph_signals(state)
                stats = budget_manager.build_path_stats(
                    state.strategies,
                    state.attempts,
                    state.reports,
                    state.aggregate_reports,
                    graph_signals=graph_signals,
                )
                decision = budget_manager.decide(
                    stats,
                    current_path_count=len(state.strategies),
                    remaining_calls=runner.ledger.remaining_calls,
                    current_round=round_index,
                    final_verified=False,
                    max_actions=self.config.scheduler.max_actions_per_round,
                    bucket_pressure=allocator.pressure_snapshot(),
                )
                decision = allocator.admit_decision(
                    decision,
                    current_path_count=len(state.strategies),
                    has_candidate=bool(state.attempts),
                    max_actions=self.config.scheduler.max_actions_per_round,
                )
                store.write_json(
                    "structured",
                    f"budget_decision_round_{round_index}",
                    decision,
                )
                store.append_event("budget_decision", decision)
                action_names = (
                    ", ".join(action.action.value for action in decision.actions)
                    or "none"
                )
                activity.info(
                    "budget_decision",
                    title=activity.text(
                        "本轮调度决策已形成", "Adaptive decision formed"
                    ),
                    detail=activity.text(
                        f"计划动作：{action_names}；全局不确定性 {decision.global_uncertainty:.2f}",
                        f"Actions: {action_names}; global uncertainty {decision.global_uncertainty:.2f}",
                    ),
                    stage="adaptive_round",
                    parent_task_id=round_task,
                    importance=ActivityImportance.NORMAL,
                    metrics={
                        "actions": [action.action.value for action in decision.actions],
                        "global_uncertainty": decision.global_uncertainty,
                        "coverage": decision.coverage,
                        "failure_rate": decision.failure_rate,
                        "forced_widen": decision.forced_widen,
                        "finish_reserve_calls": decision.finish_reserve_calls,
                    },
                )
                if self.config.scheduler.diagnostics_enabled:
                    diagnostics = decision.candidates[
                        : self.config.scheduler.diagnostic_candidate_limit
                    ]
                    diagnostic_text = "; ".join(
                        (
                            f"#{candidate.rank} {candidate.action.value}"
                            f"({candidate.strategy_id or 'global'}), "
                            f"score={candidate.score:.3f}, "
                            f"estimated_calls={candidate.estimated_calls}: "
                            + (
                                "selected"
                                if candidate.selected
                                else candidate.blocked_reason or "not selected"
                            )
                        )
                        for candidate in diagnostics
                    )
                    activity.info(
                        "budget_candidate_diagnostics",
                        title=activity.text(
                            "调度候选排名与阻断原因",
                            "Scheduler candidate ranking and blocking reasons",
                        ),
                        detail=diagnostic_text,
                        stage="adaptive_round",
                        parent_task_id=round_task,
                        importance=ActivityImportance.DETAIL,
                        metrics={
                            "candidates": [
                                candidate.model_dump(mode="json")
                                for candidate in diagnostics
                            ]
                        },
                    )

                performed = False
                for action in decision.actions:
                    if action.action in {ActionKind.STOP, ActionKind.SYNTHESIZE}:
                        continue
                    estimated_cost = (
                        action.estimated_calls
                        or allocator.estimate_action_calls(
                            action.action,
                            current_path_count=len(state.strategies),
                            widen_path_count=action.planned_paths or None,
                        )
                    )
                    bucket = allocator.bucket_for_action(action.action)
                    blocked_reason = allocator.spend_block_reason(
                        bucket,
                        estimated_cost,
                        protect_finish=True,
                        has_candidate=bool(state.attempts),
                    )
                    if blocked_reason is not None:
                        action.selected = False
                        action.blocked_reason = blocked_reason
                        store.append_event(
                            "adaptive_action_blocked",
                            {
                                "round_index": round_index,
                                "action": action.action.value,
                                "strategy_id": action.strategy_id,
                                "estimated_calls": estimated_cost,
                                "reason": blocked_reason,
                            },
                        )
                        continue

                    action_label_zh = {
                        ActionKind.DEEPEN: "深挖现有路线",
                        ActionKind.WIDEN: "扩展新的证明方向",
                        ActionKind.VERIFY: "追加独立验证",
                        ActionKind.REVISE: "定向修订",
                    }.get(action.action, action.action.value)
                    activity.info(
                        "adaptive_action",
                        title=activity.text(
                            action_label_zh, f"Adaptive action: {action.action.value}"
                        ),
                        detail=action.reason,
                        stage="adaptive_round",
                        parent_task_id=round_task,
                        importance=ActivityImportance.NORMAL,
                        metrics={
                            "action": action.action.value,
                            "strategy_id": action.strategy_id,
                            "target_id": action.target_id,
                            "score": action.score,
                        },
                    )

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
                            tools,
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
                        new_attempts = await self._widen(
                            problem,
                            state.triage,
                            round_index,
                            state,
                            runner,
                            prompts,
                            router,
                            memory,
                            store,
                            tools,
                            requested_count=action.planned_paths or None,
                        )
                        if new_attempts:
                            state.attempts.extend(new_attempts)
                            await self._extract_claims_many(
                                problem,
                                new_attempts,
                                runner,
                                prompts,
                                memory,
                                store,
                                budget_bucket="breadth",
                            )
                            performed = True
                    elif action.action == ActionKind.VERIFY and action.target_id:
                        target = next(
                            (
                                a
                                for a in state.attempts
                                if a.attempt_id == action.target_id
                            ),
                            None,
                        )
                        if target is not None:
                            bundle = await self._verify_attempt(
                                problem,
                                target,
                                runner,
                                prompts,
                                router,
                                memory,
                                tools,
                                store,
                                state=state,
                            )
                            self._record_verification_bundles(state, [bundle])
                            performed = True
                    elif action.action in {
                        ActionKind.BRIDGE,
                        ActionKind.RESOLVE_CONFLICT,
                        ActionKind.SEARCH_COUNTEREXAMPLE,
                        ActionKind.MERGE_ROUTE,
                        ActionKind.COOLDOWN_ROUTE,
                        ActionKind.SWITCH_REPRESENTATION,
                        ActionKind.TRIGGER_INSPIRATION,
                        ActionKind.SEARCH_ANALOGY,
                        ActionKind.INVENT_CONSTRUCTION,
                        ActionKind.GENERATE_INVARIANT,
                        ActionKind.REVERSE_GOAL,
                        ActionKind.META_REPLAN,
                        ActionKind.SURPRISE_WIDEN,
                    }:
                        performed = (
                            await self._execute_hierarchical_action(
                                state,
                                action.action,
                                strategy_id=action.strategy_id,
                                current_round=round_index,
                                store=store,
                                problem=problem,
                                runner=runner,
                                prompts=prompts,
                                router=router,
                                memory=memory,
                                tools=tools,
                            )
                            or performed
                        )

                # Verify the best newly generated candidate when its marginal value is high.
                unverified = [
                    attempt
                    for attempt in self._rank_attempts(state.attempts)
                    if attempt.attempt_id not in state.aggregate_reports
                ]
                if (
                    unverified
                    and allocator.can_spend(
                        "verification",
                        allocator.estimate_action_calls(
                            ActionKind.VERIFY,
                            current_path_count=len(state.strategies),
                        ),
                        protect_finish=True,
                        has_candidate=True,
                    )
                    and self._attempt_local_quality(unverified[0]) >= 0.48
                ):
                    bundle = await self._verify_attempt(
                        problem,
                        unverified[0],
                        runner,
                        prompts,
                        router,
                        memory,
                        tools,
                        store,
                        state=state,
                    )
                    self._record_verification_bundles(state, [bundle])
                    performed = True

                if (
                    performed
                    and runner.ledger.remaining_calls > allocator.minimum_finish_reserve
                ):
                    review = await self._meta_review(
                        problem,
                        state.attempts,
                        state.aggregate_reports,
                        runner,
                        prompts,
                        store,
                    )
                    state.meta_reviews.append(review)
                    self._apply_meta_route_controls(state, review, round_index, store)

                self._sync_hierarchical_artifacts(
                    state,
                    problem=problem,
                    memory=memory,
                    current_round=round_index,
                    store=store,
                )

                self._checkpoint(
                    store, f"adaptive_round_{round_index}", state, memory, runner
                )
                activity.complete_task(
                    round_task,
                    title=activity.text(
                        f"自适应调度第 {round_index} 轮完成",
                        f"Adaptive scheduling round {round_index} completed",
                    ),
                    detail=activity.text(
                        "已执行本轮高价值动作"
                        if performed
                        else "没有发现值得继续投入的动作",
                        "High-value actions were executed"
                        if performed
                        else "No further high-value action was found",
                    ),
                    stage="adaptive_round",
                    parent_task_id=run_activity_task,
                    importance=ActivityImportance.MAJOR,
                    metrics={"round_index": round_index, "performed": performed},
                )
                if not performed:
                    break

            if (
                state.proof_graph is not None
                and self.config.topology.final_stage.freeze_graph_before_synthesis
                and not state.proof_graph.frozen
            ):
                state.proof_graph.freeze()
                state.graph_frozen = True

            # Final synthesis is a proof-producing stage, not a generic summarizer.
            # When no candidate meets the evidence gate, preserve the mathematics in
            # a separate research-progress report instead of fabricating closure.
            if self._can_enter_synthesis(state):
                synthesis_task = activity.start_task(
                    "stage",
                    title=activity.text(
                        "综合候选路线形成最终证明",
                        "Synthesizing candidate routes into a final proof",
                    ),
                    detail=activity.text(
                        "仅组合假设与作用域相容、且有验证支持的步骤",
                        "Combining only compatible, verification-supported steps",
                    ),
                    stage="synthesis",
                    parent_task_id=run_activity_task,
                    importance=ActivityImportance.MAJOR,
                )
                state.final_proof, synthesizer = await self._synthesize(
                    problem,
                    state,
                    runner,
                    prompts,
                    router,
                    memory,
                    store,
                )
                if state.final_proof is not None:
                    activity.complete_task(
                        synthesis_task,
                        title=activity.text(
                            "最终证明草稿已形成", "Final proof draft created"
                        ),
                        detail=activity.text(
                            f"包含 {len(state.final_proof.proof_steps)} 个可审计步骤，来源路线 {len(state.final_proof.source_attempt_ids)} 条",
                            f"Contains {len(state.final_proof.proof_steps)} auditable steps from {len(state.final_proof.source_attempt_ids)} source routes",
                        ),
                        stage="synthesis",
                        parent_task_id=run_activity_task,
                        importance=ActivityImportance.MAJOR,
                        metrics={
                            "proof_step_count": len(state.final_proof.proof_steps),
                            "source_attempt_count": len(
                                state.final_proof.source_attempt_ids
                            ),
                        },
                    )
                    final_verify_task = activity.start_task(
                        "stage",
                        title=activity.text(
                            "执行最终独立审计", "Running the final independent audit"
                        ),
                        detail=activity.text(
                            "先检查题意与依赖结构，再逐步核查关键推导",
                            "Checking theorem integrity and dependencies before the step-level audit",
                        ),
                        stage="final_verification",
                        parent_task_id=run_activity_task,
                        importance=ActivityImportance.MAJOR,
                    )
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
                        state=state,
                    )
                    state.reports.extend(final_bundle.reports)
                    state.final_verification = final_bundle.aggregate
                    initial_final_status = (
                        ActivityStatus.COMPLETED
                        if state.final_verification.verdict == VerificationVerdict.PASS
                        else ActivityStatus.WARNING
                    )
                    activity.update_task(
                        final_verify_task,
                        title=activity.text("最终审计已返回", "Final audit returned"),
                        detail=activity.text(
                            f"结论 {state.final_verification.verdict.value}；置信度 {state.final_verification.confidence:.2f}",
                            f"Verdict {state.final_verification.verdict.value}; confidence {state.final_verification.confidence:.2f}",
                        ),
                        status=initial_final_status,
                        event_type="final_audit_result",
                        stage="final_verification",
                        parent_task_id=run_activity_task,
                        importance=ActivityImportance.MAJOR,
                        progress=(
                            1.0
                            if initial_final_status == ActivityStatus.COMPLETED
                            else None
                        ),
                        metrics={
                            "verdict": state.final_verification.verdict.value,
                            "confidence": state.final_verification.confidence,
                            "failure_level": state.final_verification.failure_level.value,
                        },
                    )

                    revisions = 0
                    while (
                        state.final_verification.verdict != VerificationVerdict.PASS
                        and revisions < self.config.budget.max_revisions
                        and allocator.can_spend(
                            "synthesis",
                            allocator.estimate_revision_cycle_calls(),
                            protect_finish=False,
                            has_candidate=True,
                        )
                    ):
                        # Strategy-level failure is not repaired by polishing the same proof.
                        if (
                            state.final_verification.failure_level
                            == FailureLevel.STRATEGY
                        ):
                            activity.warn_task(
                                final_verify_task,
                                title=activity.text(
                                    "最终审计发现策略级缺口",
                                    "Final audit found a strategy-level gap",
                                ),
                                detail=activity.text(
                                    "停止仅靠文字修订同一证明，保留未验证状态",
                                    "Stopping local proof polishing and preserving the unverified status",
                                ),
                                event_type="strategy_level_gap",
                                stage="final_verification",
                                parent_task_id=run_activity_task,
                                importance=ActivityImportance.MAJOR,
                            )
                            break
                        revision_task = activity.start_task(
                            "final_revision",
                            title=activity.text(
                                f"执行第 {revisions + 1} 次定向修订",
                                f"Running targeted revision {revisions + 1}",
                            ),
                            detail=activity.text(
                                "只修复审计定位的首个关键缺口",
                                "Repairing only the first decisive gap identified by the audit",
                            ),
                            stage="final_revision",
                            parent_task_id=final_verify_task,
                            importance=ActivityImportance.MAJOR,
                            metrics={"revision_index": revisions + 1},
                        )
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
                            state=state,
                        )
                        if revised is None:
                            state.final_repair_failed = True
                            activity.fail_task(
                                revision_task,
                                title=activity.text(
                                    "定向修订未形成有效草稿",
                                    "Targeted revision did not produce a valid draft",
                                ),
                                detail=activity.text(
                                    "保留上一版证明与审计结论",
                                    "Keeping the previous proof and audit result",
                                ),
                                event_type="final_revision_failed",
                                stage="final_revision",
                                parent_task_id=final_verify_task,
                                importance=ActivityImportance.MAJOR,
                            )
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
                            state=state,
                        )
                        state.reports.extend(final_bundle.reports)
                        state.final_verification = final_bundle.aggregate
                        revisions += 1
                        revision_status = (
                            ActivityStatus.COMPLETED
                            if state.final_verification.verdict
                            == VerificationVerdict.PASS
                            else ActivityStatus.WARNING
                        )
                        activity.update_task(
                            revision_task,
                            title=activity.text(
                                f"第 {revisions} 次修订与复核完成",
                                f"Revision {revisions} and re-audit completed",
                            ),
                            detail=activity.text(
                                f"最新结论 {state.final_verification.verdict.value}；置信度 {state.final_verification.confidence:.2f}",
                                f"Latest verdict {state.final_verification.verdict.value}; confidence {state.final_verification.confidence:.2f}",
                            ),
                            status=revision_status,
                            event_type="final_revision_completed",
                            stage="final_revision",
                            parent_task_id=final_verify_task,
                            importance=ActivityImportance.MAJOR,
                            progress=(
                                1.0
                                if revision_status == ActivityStatus.COMPLETED
                                else None
                            ),
                            metrics={
                                "revision_index": revisions,
                                "verdict": state.final_verification.verdict.value,
                                "confidence": state.final_verification.confidence,
                            },
                        )
                        activity.update_task(
                            final_verify_task,
                            title=activity.text(
                                "最终审计状态已更新", "Final audit status updated"
                            ),
                            detail=activity.text(
                                f"结论 {state.final_verification.verdict.value}；已修订 {revisions} 次",
                                f"Verdict {state.final_verification.verdict.value}; revisions {revisions}",
                            ),
                            status=revision_status,
                            event_type="final_audit_updated",
                            stage="final_verification",
                            parent_task_id=run_activity_task,
                            importance=ActivityImportance.MAJOR,
                            progress=(
                                1.0
                                if revision_status == ActivityStatus.COMPLETED
                                else None
                            ),
                            metrics={
                                "verdict": state.final_verification.verdict.value,
                                "revision_count": revisions,
                            },
                        )
                    if (
                        revisions > 0
                        and state.final_verification.verdict != VerificationVerdict.PASS
                    ):
                        state.final_repair_failed = True
                        store.append_event(
                            "final_repair_inspiration_trigger_ready",
                            {
                                "revision_count": revisions,
                                "verdict": state.final_verification.verdict.value,
                            },
                        )
                else:
                    activity.fail_task(
                        synthesis_task,
                        title=activity.text(
                            "未形成可提交的最终证明",
                            "No submit-ready final proof was produced",
                        ),
                        detail=activity.text(
                            "已保留所有部分路线、失败信息和验证记录",
                            "All partial routes, failures, and verification artifacts were preserved",
                        ),
                        event_type="synthesis_failed",
                        stage="synthesis",
                        parent_task_id=run_activity_task,
                        importance=ActivityImportance.MAJOR,
                    )
            else:
                state.research_progress_report = self._build_research_progress_report(
                    problem,
                    state,
                    execution_note=(
                        "未达到最终证明综合门槛；保留已审查的局部进展。"
                        if self.config.runtime.output_language.lower().startswith("zh")
                        else "Final-proof synthesis gate was not met; reviewed partial progress was preserved."
                    ),
                )
                store.write_json(
                    "reports",
                    "research_progress_report",
                    state.research_progress_report,
                )
                activity.info(
                    "research_progress_report_ready",
                    title=activity.text(
                        "未达到最终综合门槛，已生成研究进展报告",
                        "Synthesis gate not met; research progress report created",
                    ),
                    detail=activity.text(
                        "局部步骤、反例、失败路线与剩余缺口均已保留",
                        "Partial steps, counterevidence, failed routes, and remaining gaps were preserved",
                    ),
                    stage="synthesis",
                    parent_task_id=run_activity_task,
                    importance=ActivityImportance.MAJOR,
                )
                if runner.ledger.remaining_calls == 0:
                    state.budget_exhausted = True
                    state.execution_status = ExecutionStatus.BUDGET_EXHAUSTED

            self._checkpoint(store, "final", state, memory, runner)
            status = self._run_status(state)
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
            activity.complete_task(
                run_activity_task,
                title=activity.text("多 Agent 求解结束", "Multi-agent run completed"),
                detail=activity.text(
                    f"状态 {result.status.value}；调用 {result.total_calls} 次；共 {result.total_usage.total_tokens:,} tokens",
                    f"Status {result.status.value}; {result.total_calls} calls; {result.total_usage.total_tokens:,} tokens",
                ),
                event_type="run_completed",
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
        except ProviderCircuitOpenError as exc:
            state.execution_status = ExecutionStatus.NETWORK_INTERRUPTED
            state.math_status = MathStatus.INCONCLUSIVE
            state.research_progress_report = self._build_research_progress_report(
                problem,
                state,
                execution_note=(
                    f"公共模型服务连接中断，已暂停；约 {exc.retry_after_seconds:.0f} 秒后可恢复。"
                    if self.config.runtime.output_language.lower().startswith("zh")
                    else (
                        "The shared model-provider transport was interrupted; the run "
                        f"was paused and may be resumed after about {exc.retry_after_seconds:.0f}s."
                    )
                ),
            )
            self._checkpoint(store, "paused_external_failure", state, memory, runner)
            store.write_json(
                "reports", "research_progress_report", state.research_progress_report
            )
            store.append_event(
                "provider_circuit_open",
                {
                    "provider_scope": exc.provider_scope,
                    "agent_ids": exc.agent_ids,
                    "retry_after_seconds": exc.retry_after_seconds,
                    "resume_stage": "paused_external_failure",
                },
            )
            result = self._build_result(
                run_id,
                RunStatus.PAUSED_EXTERNAL_FAILURE,
                problem,
                state,
                pool.metrics(),
                runner,
                pool,
                store,
                memory,
                summary_override=(
                    "公共 API 连接故障已触发 provider 熔断；数学结论仍为 inconclusive，"
                    "所有外部检查点和局部结果已保存，可恢复运行。"
                    if self.config.runtime.output_language.lower().startswith("zh")
                    else (
                        "A shared provider transport failure opened the circuit. The "
                        "mathematical status remains inconclusive; external checkpoints "
                        "and partial results were saved for resume."
                    )
                ),
            )
            router.export()
            store.write_json("structured", "run_result", result)
            activity.close_open_tasks(
                status=ActivityStatus.WARNING,
                detail=activity.text(
                    "公共网络故障触发暂停；已保存检查点，未继续消耗备用 Agent",
                    "A shared transport failure paused the run; checkpoints were saved and backup agents were not consumed",
                ),
                exclude_task_ids={run_activity_task},
            )
            activity.warn_task(
                run_activity_task,
                title=activity.text(
                    "外部服务中断，运行已安全暂停",
                    "External service interrupted; run safely paused",
                ),
                detail=str(exc),
                event_type="provider_circuit_open",
                stage="run",
                importance=ActivityImportance.MAJOR,
                metrics={
                    "provider_scope": exc.provider_scope,
                    "agent_ids": exc.agent_ids,
                    "retry_after_seconds": exc.retry_after_seconds,
                },
            )
            activity.finalize()
            write_run_report(store, result)
            return result
        except BudgetExhaustedError as exc:
            state.budget_exhausted = True
            logger.warning("Budget exhausted: %s", exc)
            store.append_event("budget_exhausted", {"error": str(exc)})
            result = self._build_result(
                run_id,
                RunStatus.BUDGET_EXHAUSTED,
                problem,
                state,
                pool.metrics(),
                runner,
                pool,
                store,
                memory,
                summary_override=(
                    "全局调用、Token 或费用预算已耗尽；所有可用的局部结果均已保留。"
                    if self.config.runtime.output_language.lower().startswith("zh")
                    else "Global call/token/cost budget was exhausted; partial artifacts were preserved."
                ),
            )
            router.export()
            store.write_json("structured", "run_result", result)
            activity.close_open_tasks(
                status=ActivityStatus.WARNING,
                detail=activity.text(
                    "由于预算耗尽，本阶段停止并保留当前产物",
                    "This stage stopped because the budget was exhausted; current artifacts were preserved",
                ),
                exclude_task_ids={run_activity_task},
            )
            activity.warn_task(
                run_activity_task,
                title=activity.text(
                    "预算耗尽，保留部分结果",
                    "Budget exhausted; partial results preserved",
                ),
                detail=activity.text(
                    f"已完成 {result.total_calls} 次调用；当前状态 {result.status.value}",
                    f"Completed {result.total_calls} calls; current status {result.status.value}",
                ),
                event_type="budget_exhausted",
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
        except Exception as exc:
            logger.exception("MathProofMesh run failed")
            store.append_event(
                "run_failed", {"type": type(exc).__name__, "error": str(exc)}
            )
            result = self._build_result(
                run_id,
                RunStatus.FAILED,
                problem,
                state,
                pool.metrics(),
                runner,
                pool,
                store,
                memory,
                summary_override=(
                    f"运行因 {type(exc).__name__} 异常终止；当前产物已保留：{exc}"
                    if self.config.runtime.output_language.lower().startswith("zh")
                    else f"Run failed with {type(exc).__name__}: {exc}"
                ),
            )
            router.export()
            store.write_json("structured", "run_result", result)
            activity.close_open_tasks(
                status=ActivityStatus.FAILED,
                detail=activity.text(
                    "由于运行异常，本阶段中止；已保留当前产物",
                    "This stage was interrupted by a run error; current artifacts were preserved",
                ),
                exclude_task_ids={run_activity_task},
            )
            activity.fail_task(
                run_activity_task,
                title=activity.text("运行异常终止", "Run terminated with an error"),
                detail=activity.text(
                    f"错误类型 {type(exc).__name__}；已保存当前阶段的全部可用产物",
                    f"Error type {type(exc).__name__}; all available artifacts were preserved",
                ),
                event_type="run_failed",
                stage="run",
                importance=ActivityImportance.MAJOR,
                metrics={"error_type": type(exc).__name__},
            )
            activity.finalize()
            write_run_report(store, result)
            return result
        finally:
            await pool.aclose()

    async def resume(self, run_id: str) -> RunResult:
        """Resume a stopped run from persisted stage state and verified proof checkpoints."""
        if not self.config.continuation.process_resume_enabled:
            raise RuntimeError(
                "process resume is disabled by continuation.process_resume_enabled"
            )
        store = ArtifactStore(self.config.runtime.run_root, run_id)
        if not store.has_named_json("structured", "problem_contract"):
            raise FileNotFoundError(
                f"run {run_id!r} has no structured/problem_contract.json and cannot be resumed"
            )
        checkpoint_payload = store.latest_stage_checkpoint()
        if checkpoint_payload is None:
            # A process can stop after the immutable problem contract or runtime ledger
            # has been persisted but before the first stage-level checkpoint exists.
            # Resume conservatively from the frozen problem instead of forcing the user
            # to start a new run ID and lose the existing audit trail.
            resume_stage, payload = "problem_contract", {}
        else:
            resume_stage, payload = checkpoint_payload
        # Recover durable structured artifacts when stage snapshots were disabled
        # or the process stopped between a structured write and the next stage checkpoint.
        if not payload.get("triage"):
            for triage_name in ("triage", "triage_fallback"):
                if store.has_named_json("structured", triage_name):
                    payload["triage"] = store.read_named_json("structured", triage_name)
                    break
        if not payload.get("strategies") and store.has_named_json(
            "structured", "selected_strategies"
        ):
            payload["strategies"] = store.read_named_json(
                "structured", "selected_strategies"
            )
        problem = ProblemContract.model_validate(
            store.read_named_json("structured", "problem_contract")
        )

        activity = ActivityStream(
            store,
            language=self.config.runtime.output_language,
            listener=self.activity_listener,
            persist=self.config.runtime.activity_persist,
        )
        pool = AgentPool(self.config, mock_responders=self.mock_responders)
        runner = StructuredAgentRunner(self.config, pool, store, activity=activity)
        allocator = SoftBudgetAllocator(self.config, runner.ledger)
        prompts = PromptFactory(
            self.config.runtime.output_language,
            computation_enabled=self.config.computation.enabled,
        )
        router = SparseTopologyRouter(self.config, pool, store)
        memory = LemmaMemory(store)
        tools = ToolBroker(self.config, store, activity)

        state = self._restore_state_from_checkpoint(payload, store)
        self._initialize_hierarchical_runtime(
            state,
            problem=problem,
            store=store,
            activity=activity,
            memory=memory,
            checkpoint_payload=payload,
        )
        state.resumed = True
        latest_proof_checkpoint = max(
            state.checkpoints,
            key=lambda checkpoint: checkpoint.created_at,
            default=None,
        )
        state.resumed_from_checkpoint_id = (
            latest_proof_checkpoint.checkpoint_id if latest_proof_checkpoint else None
        )
        persisted_claims: list[Any] = list(payload.get("claims", []))
        if store.has_named_json("structured", "lemma_memory"):
            lemma_payload = store.read_named_json("structured", "lemma_memory")
            if isinstance(lemma_payload, list):
                persisted_claims.extend(lemma_payload)
        memory.add_many([ClaimCard.model_validate(item) for item in persisted_claims])
        if self.config.topology.mode == "hierarchical_sparse" and not all(
            isinstance(payload.get(key), dict)
            for key in ("typed_memory", "message_broker")
        ):
            verified_count = len(memory.verified())
            if verified_count:
                store.append_event(
                    "legacy_claims_quarantined",
                    {
                        "verified_count": verified_count,
                        "policy": "not_admitted_without_typed_broker_provenance",
                    },
                )
        runtime_payload = (
            store.read_named_json("checkpoints", "runtime_ledger")
            if store.has_named_json("checkpoints", "runtime_ledger")
            else payload
        )
        runner.ledger.calls_started = int(runtime_payload.get("calls_started", 0) or 0)
        stage_calls = runtime_payload.get("stage_calls") or {}
        runner.ledger.stage_calls = {
            str(key): int(value or 0) for key, value in stage_calls.items()
        }
        bucket_calls = runtime_payload.get("bucket_calls") or {}
        for key in runner.ledger.bucket_calls:
            if key in bucket_calls:
                runner.ledger.bucket_calls[key] = int(bucket_calls[key] or 0)
        runner.ledger.reservation_calls = {
            str(key): int(value or 0)
            for key, value in dict(
                runtime_payload.get("reservation_calls") or {}
            ).items()
        }
        if state.inspiration_engine is not None:
            state.inspiration_engine.reconcile_call_reservations(
                runner.ledger.reservation_calls
            )
        pool.restore_metrics(
            runtime_payload.get("agent_metrics") or payload.get("agent_metrics") or []
        )
        pool.restore_provider_circuit_state(
            dict(runtime_payload.get("provider_circuit") or {})
        )
        runner.persist_runtime_state()

        run_task = activity.start_task(
            "run_resume",
            title=activity.text(
                "恢复多 Agent 数学求解", "Resuming the multi-agent mathematics run"
            ),
            detail=activity.text(
                f"已加载阶段 {resume_stage}；从最近已验证证明状态继续",
                f"Loaded stage {resume_stage}; continuing from the latest verified proof state",
            ),
            stage="run_resume",
            importance=ActivityImportance.MAJOR,
            metrics={
                "resume_stage": resume_stage,
                "resumed_from_checkpoint_id": state.resumed_from_checkpoint_id,
            },
        )
        store.append_event(
            "run_resumed",
            {
                "resume_stage": resume_stage,
                "resumed_from_checkpoint_id": state.resumed_from_checkpoint_id,
                "restored_calls_started": runner.ledger.calls_started,
            },
        )

        try:
            if (
                state.final_proof is not None
                and state.final_verification is not None
                and state.final_verification.verdict == VerificationVerdict.PASS
                and state.final_verification.confidence
                >= self.config.budget.verification_pass_threshold
            ):
                state.checkpoints = store.list_proof_checkpoints()
                result = self._build_result(
                    run_id,
                    RunStatus.VERIFIED,
                    problem,
                    state,
                    pool.metrics(),
                    runner,
                    pool,
                    store,
                    memory,
                    summary_override=(
                        f"已从阶段 {resume_stage} 和证明检查点 "
                        f"{state.resumed_from_checkpoint_id or '无'} 恢复已完成结果，"
                        "未产生新的模型调用。"
                        if self.config.runtime.output_language.lower().startswith("zh")
                        else (
                            f"Completed run recovered from stage {resume_stage} and proof "
                            f"checkpoint {state.resumed_from_checkpoint_id or 'none'} without "
                            "new model calls."
                        )
                    ),
                )
                store.write_json("structured", "run_result", result)
                store.append_event(
                    "run_resume_noop_completed",
                    {
                        "status": result.status.value,
                        "total_calls": result.total_calls,
                        "resumed_from_checkpoint_id": state.resumed_from_checkpoint_id,
                    },
                )
                activity.complete_task(
                    run_task,
                    title=activity.text(
                        "已恢复完成状态，无需重复调用模型",
                        "Completed state restored without repeating model calls",
                    ),
                    detail=activity.text(
                        f"最终审计已通过；调用计数保持 {result.total_calls}",
                        f"Final audit already passed; call count remains {result.total_calls}",
                    ),
                    stage="run_resume",
                    importance=ActivityImportance.MAJOR,
                    metrics={
                        "status": result.status.value,
                        "no_new_model_calls": True,
                        "resumed_from_checkpoint_id": state.resumed_from_checkpoint_id,
                    },
                )
                activity.finalize()
                write_run_report(store, result)
                return result

            if state.triage is None:
                state.triage = await self._triage(problem, runner, prompts, store)
            state.capability_domain = infer_capability_domain(
                problem.exact_statement,
                state.triage.rationale,
                *state.triage.key_risks,
                *state.triage.likely_tools,
            )
            pool.set_capability_context(
                state.capability_profile,
                domain=state.capability_domain,
            )
            if not state.strategies:
                state.strategies = await self._initial_strategies(
                    problem,
                    state.triage,
                    runner,
                    prompts,
                    router,
                    store,
                )
            self._ensure_hierarchical_routes(
                state,
                router,
                round_index=state.current_round,
                activity=activity,
                store=store,
            )
            # Persist the repaired topology before any resumed route can run. If the
            # process stops again here, the next resume sees one coherent v0.7 state
            # rather than repeating the legacy pre-strategy checkpoint.
            self._checkpoint(
                store,
                "resume_routes_ensured",
                state,
                memory,
                runner,
            )

            max_resume_rounds = max(1, self.config.budget.max_rounds)
            for resume_round in range(max_resume_rounds):
                state.current_round = resume_round
                updated_attempts: list[ProofAttempt] = []
                for strategy in state.strategies:
                    path_attempts = [
                        attempt
                        for attempt in state.attempts
                        if attempt.strategy_id == strategy.strategy_id
                    ]
                    previous = (
                        max(path_attempts, key=lambda item: item.round_index)
                        if path_attempts
                        else None
                    )
                    if (
                        previous is not None
                        and previous.status == AttemptStatus.COMPLETE
                    ):
                        continue
                    if runner.ledger.remaining_calls <= 0:
                        break
                    try:
                        agent = (
                            runner.pool.get(previous.agent_id)
                            if previous is not None
                            else runner.pool.select(
                                "explorer", specialty_hints=strategy.tags
                            )
                        )
                    except (KeyError, RuntimeError):
                        agent = runner.pool.select(
                            "explorer", specialty_hints=strategy.tags
                        )
                    feedback = (
                        self._targeted_feedback(previous, state) if previous else []
                    )
                    updated = await self._explore_path(
                        problem,
                        strategy,
                        agent,
                        state=state,
                        round_index=(
                            previous.round_index + 1 if previous else resume_round
                        ),
                        runner=runner,
                        prompts=prompts,
                        router=router,
                        memory=memory,
                        store=store,
                        tools=tools,
                        targeted_feedback=feedback,
                        previous_attempt=previous,
                        budget_bucket="depth",
                        computation_meta_approved=any(
                            strategy.strategy_id
                            in review.broad_computation_approved_strategy_ids
                            for review in state.meta_reviews[-1:]
                        ),
                    )
                    state.attempts = [
                        item
                        for item in state.attempts
                        if item.attempt_id != updated.attempt_id
                    ]
                    state.attempts.append(updated)
                    updated_attempts.append(updated)

                if updated_attempts:
                    await self._extract_claims_many(
                        problem,
                        updated_attempts,
                        runner,
                        prompts,
                        memory,
                        store,
                        budget_bucket="depth",
                    )

                candidates = self._rank_attempts(state.attempts)[
                    : self.config.budget.candidates_to_verify
                ]
                verification_bundles = await self._verify_attempts_many(
                    problem,
                    candidates,
                    runner,
                    prompts,
                    router,
                    memory,
                    tools,
                    store,
                    state=state,
                )
                self._record_verification_bundles(state, verification_bundles)
                self._sync_hierarchical_artifacts(
                    state,
                    problem=problem,
                    memory=memory,
                    current_round=resume_round,
                    store=store,
                )
                await self._run_inspiration_round(
                    state,
                    problem=problem,
                    remaining_calls=runner.ledger.remaining_calls,
                    store=store,
                    runner=runner,
                    prompts=prompts,
                    allocator=allocator,
                    router=router,
                    memory=memory,
                    tools=tools,
                )
                if state.attempts and runner.ledger.remaining_calls > 0:
                    review = await self._meta_review(
                        problem,
                        state.attempts,
                        state.aggregate_reports,
                        runner,
                        prompts,
                        store,
                    )
                    state.meta_reviews.append(review)
                    self._apply_meta_route_controls(state, review, resume_round, store)
                self._checkpoint(
                    store,
                    f"resume_round_{resume_round}",
                    state,
                    memory,
                    runner,
                )
                if self._has_synthesis_ready_candidate(state):
                    break
                if not updated_attempts:
                    break

            if (
                state.proof_graph is not None
                and self.config.topology.final_stage.freeze_graph_before_synthesis
                and not state.proof_graph.frozen
            ):
                state.proof_graph.freeze()
                state.graph_frozen = True
            if self._can_enter_synthesis(state):
                state.final_proof, synthesizer = await self._synthesize(
                    problem,
                    state,
                    runner,
                    prompts,
                    router,
                    memory,
                    store,
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
                        state=state,
                    )
                    state.reports.extend(final_bundle.reports)
                    state.final_verification = final_bundle.aggregate
            else:
                state.research_progress_report = self._build_research_progress_report(
                    problem,
                    state,
                    execution_note=(
                        "恢复运行后仍未达到最终证明综合门槛。"
                        if self.config.runtime.output_language.lower().startswith("zh")
                        else "The resumed run still did not meet the final-proof synthesis gate."
                    ),
                )
                store.write_json(
                    "reports",
                    "research_progress_report",
                    state.research_progress_report,
                )
                if runner.ledger.remaining_calls == 0:
                    state.budget_exhausted = True
                    state.execution_status = ExecutionStatus.BUDGET_EXHAUSTED

            state.checkpoints = store.list_proof_checkpoints()
            self._checkpoint(store, "resume_final", state, memory, runner)
            status = self._run_status(state)
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
                summary_override=(
                    f"运行已从阶段 {resume_stage} 和证明检查点 "
                    f"{state.resumed_from_checkpoint_id or '无'} 恢复；"
                    f"{self._result_summary(status, state)}"
                    if self.config.runtime.output_language.lower().startswith("zh")
                    else (
                        f"Run resumed from stage {resume_stage} and proof checkpoint "
                        f"{state.resumed_from_checkpoint_id or 'none'}; "
                        f"{self._result_summary(status, state)}"
                    )
                ),
            )
            router.export()
            store.write_json("structured", "run_result", result)
            store.append_event(
                "run_resume_completed",
                {
                    "status": result.status.value,
                    "total_calls": result.total_calls,
                    "resumed_from_checkpoint_id": state.resumed_from_checkpoint_id,
                },
            )
            activity.complete_task(
                run_task,
                title=activity.text("恢复运行完成", "Resumed run completed"),
                detail=activity.text(
                    f"状态 {result.status.value}；检查点 {state.resumed_from_checkpoint_id or '无'}",
                    f"Status {result.status.value}; checkpoint {state.resumed_from_checkpoint_id or 'none'}",
                ),
                stage="run_resume",
                importance=ActivityImportance.MAJOR,
                metrics={
                    "status": result.status.value,
                    "resumed_from_checkpoint_id": state.resumed_from_checkpoint_id,
                },
            )
            activity.finalize()
            write_run_report(store, result)
            return result
        except ProviderCircuitOpenError as exc:
            state.execution_status = ExecutionStatus.NETWORK_INTERRUPTED
            state.math_status = MathStatus.INCONCLUSIVE
            state.research_progress_report = self._build_research_progress_report(
                problem,
                state,
                execution_note=(
                    "恢复期间公共 API 连接再次中断；运行已停在最近外部检查点。"
                    if self.config.runtime.output_language.lower().startswith("zh")
                    else "The shared provider connection failed during resume; the run stopped at the latest external checkpoint."
                ),
            )
            self._checkpoint(store, "paused_external_failure", state, memory, runner)
            store.write_json(
                "reports", "research_progress_report", state.research_progress_report
            )
            result = self._build_result(
                run_id,
                RunStatus.PAUSED_EXTERNAL_FAILURE,
                problem,
                state,
                pool.metrics(),
                runner,
                pool,
                store,
                memory,
                summary_override=(
                    "恢复期间 provider 熔断；数学状态仍为 inconclusive，可在网络恢复后再次 resume。"
                    if self.config.runtime.output_language.lower().startswith("zh")
                    else "The provider circuit opened during resume; mathematical status remains inconclusive and the run can be resumed after recovery."
                ),
            )
            store.write_json("structured", "run_result", result)
            activity.close_open_tasks(
                status=ActivityStatus.WARNING,
                detail=activity.text(
                    "外部服务中断；已保存恢复检查点",
                    "External service interrupted; resume checkpoint saved",
                ),
                exclude_task_ids={run_task},
            )
            activity.warn_task(
                run_task,
                title=activity.text("恢复运行已安全暂停", "Resumed run safely paused"),
                detail=str(exc),
                event_type="provider_circuit_open",
                stage="run_resume",
                importance=ActivityImportance.MAJOR,
            )
            activity.finalize()
            write_run_report(store, result)
            return result
        except BudgetExhaustedError as exc:
            state.budget_exhausted = True
            result = self._build_result(
                run_id,
                RunStatus.BUDGET_EXHAUSTED,
                problem,
                state,
                pool.metrics(),
                runner,
                pool,
                store,
                memory,
                summary_override=(
                    f"恢复运行的预算已耗尽；当前检查点和局部结果已保留：{exc}"
                    if self.config.runtime.output_language.lower().startswith("zh")
                    else f"Resume budget exhausted: {exc}"
                ),
            )
            store.write_json("structured", "run_result", result)
            activity.emit(
                "run_resume_budget_exhausted",
                status=ActivityStatus.WARNING,
                title=activity.text("恢复运行预算耗尽", "Resume budget exhausted"),
                detail=str(exc),
                stage="run_resume",
                task_id=run_task,
                importance=ActivityImportance.MAJOR,
            )
            activity.finalize()
            write_run_report(store, result)
            return result
        except Exception as exc:
            logger.exception("MathProofMesh resume failed")
            store.append_event(
                "run_resume_failed",
                {"type": type(exc).__name__, "error": str(exc)},
            )
            result = self._build_result(
                run_id,
                RunStatus.FAILED,
                problem,
                state,
                pool.metrics(),
                runner,
                pool,
                store,
                memory,
                summary_override=(
                    f"恢复运行因 {type(exc).__name__} 异常终止；最近的已验证检查点已保留：{exc}"
                    if self.config.runtime.output_language.lower().startswith("zh")
                    else f"Resume failed with {type(exc).__name__}: {exc}"
                ),
            )
            store.write_json("structured", "run_result", result)
            activity.close_open_tasks(
                status=ActivityStatus.FAILED,
                detail=activity.text(
                    "恢复运行异常中止；已保留最近的已验证检查点",
                    "Resume stopped with an error; the latest verified checkpoint was preserved",
                ),
                exclude_task_ids={run_task},
            )
            activity.fail_task(
                run_task,
                title=activity.text(
                    "恢复运行异常终止", "Resume terminated with an error"
                ),
                detail=f"{type(exc).__name__}: {exc}",
                event_type="run_resume_failed",
                stage="run_resume",
                importance=ActivityImportance.MAJOR,
            )
            activity.finalize()
            write_run_report(store, result)
            return result
        finally:
            await pool.aclose()

    def _initialize_hierarchical_runtime(
        self,
        state: SolveState,
        *,
        problem: ProblemContract,
        store: ArtifactStore,
        activity: ActivityStream,
        memory: LemmaMemory,
        checkpoint_payload: dict[str, Any] | None = None,
    ) -> None:
        if self.config.topology.mode != "hierarchical_sparse":
            return
        payload = checkpoint_payload or {}
        registry_state = payload.get("route_registry")
        state.route_registry = (
            RouteRegistry.from_state(registry_state, self.config)
            if isinstance(registry_state, dict)
            else RouteRegistry(self.config, problem_hash=problem.integrity_hash)
        )
        deep_state = payload.get("deep_exploration_registry")
        if store.has_named_json("structured", "deep_exploration_registry"):
            try:
                deep_state = store.read_named_json(
                    "structured", "deep_exploration_registry"
                )
            except (OSError, ValueError):
                pass
        state.deep_exploration_registry = (
            DeepExplorationRegistry.from_state(
                deep_state,
                self.config.deep_exploration_policy,
                problem_hash=problem.integrity_hash,
            )
            if self.config.deep_exploration_policy.enabled
            and isinstance(deep_state, dict)
            else (
                DeepExplorationRegistry(
                    self.config.deep_exploration_policy,
                    problem_hash=problem.integrity_hash,
                )
                if self.config.deep_exploration_policy.enabled
                else None
            )
        )
        if state.deep_exploration_registry is not None:
            store.write_json(
                "structured",
                "deep_exploration_registry",
                state.deep_exploration_registry.export_state(),
            )
        memory_state = payload.get("typed_memory")
        state.typed_memory = (
            TypedMemory.from_state(
                memory_state,
                store=store,
                config=self.config,
                lemma_memory=memory,
            )
            if isinstance(memory_state, dict)
            else TypedMemory(store, self.config, lemma_memory=memory)
        )
        graph_state = payload.get("proof_graph")
        state.proof_graph = (
            ProofGraphStore.from_state(graph_state, config=self.config, store=store)
            if isinstance(graph_state, dict)
            else ProofGraphStore(
                self.config, store, problem_hash=problem.integrity_hash
            )
        )
        broker_state = payload.get("message_broker")
        state.message_broker = (
            MessageBroker.from_state(
                broker_state,
                config=self.config,
                store=store,
                activity=activity,
                route_registry=state.route_registry,
                proof_graph=state.proof_graph,
                typed_memory=state.typed_memory,
            )
            if isinstance(broker_state, dict)
            else MessageBroker(
                self.config,
                store,
                activity,
                state.route_registry,
                state.proof_graph,
                state.typed_memory,
            )
        )
        bridge_state = payload.get("bridge_broker")
        state.bridge_broker = (
            BridgeBroker.from_state(
                bridge_state,
                config=self.config,
                proof_graph=state.proof_graph,
            )
            if isinstance(bridge_state, dict)
            else BridgeBroker(self.config, state.proof_graph)
        )
        contradiction_state = payload.get("contradiction_broker")
        state.contradiction_broker = (
            ContradictionBroker.from_state(
                contradiction_state,
                config=self.config,
                proof_graph=state.proof_graph,
            )
            if isinstance(contradiction_state, dict)
            else ContradictionBroker(self.config, state.proof_graph)
        )
        state.duplicate_route_detector = DuplicateRouteDetector(self.config)
        capability_state = payload.get("agent_capability")
        state.capability_profile = (
            AgentCapabilityProfile.from_state(
                capability_state,
                config=self.config.topology.agent_capability,
            )
            if isinstance(capability_state, dict)
            else AgentCapabilityProfile(self.config.topology.agent_capability)
        )
        state.validation_escalator = ValidationEscalator(
            self.config.topology.validation_escalation
        )
        state.proof_debt_history = {
            str(key): [float(item) for item in value]
            for key, value in dict(payload.get("proof_debt_history", {})).items()
        }
        state.current_round = int(payload.get("current_round", state.current_round))
        state.graph_frozen = state.proof_graph.frozen
        state.final_repair_failed = bool(payload.get("final_repair_failed", False))

        for strategy in state.strategies:
            route = state.route_registry.route_for_strategy(strategy.strategy_id)
            if route is None:
                route = state.route_registry.register_route(strategy)
            if strategy.assigned_agent_id and not state.route_registry.owns_agent(
                route.route_id, strategy.assigned_agent_id, RouteRole.PROVER
            ):
                try:
                    state.route_registry.assign_member(
                        route.route_id,
                        strategy.assigned_agent_id,
                        RouteRole.PROVER,
                        state.current_round,
                    )
                except ValueError:
                    pass

        state.inspiration_engine = InspirationEngine(
            self.config,
            problem=problem,
            proof_graph=state.proof_graph,
            typed_memory=state.typed_memory,
            route_registry=state.route_registry,
            broker=state.message_broker,
            store=store,
            activity=activity,
            project_root=".",
        )
        inspiration_state = payload.get("inspiration_engine")
        if isinstance(inspiration_state, dict):
            state.inspiration_engine.restore_state(inspiration_state)

        if checkpoint_payload is not None and not all(
            isinstance(payload.get(key), dict)
            for key in (
                "route_registry",
                "typed_memory",
                "proof_graph",
                "message_broker",
                "inspiration_engine",
            )
        ):
            store.append_event(
                "checkpoint_migrated_to_v0_7",
                {
                    "initialized_empty_components": [
                        key
                        for key in (
                            "route_registry",
                            "typed_memory",
                            "proof_graph",
                            "message_broker",
                            "inspiration_engine",
                        )
                        if not isinstance(payload.get(key), dict)
                    ]
                },
            )
            activity.info(
                "checkpoint_migrated_to_v0_7",
                title="Checkpoint migrated to v0.7",
                detail="Missing hierarchical topology state was initialized conservatively.",
                stage="run_resume",
            )

    def _ensure_hierarchical_routes(
        self,
        state: SolveState,
        router: SparseTopologyRouter,
        *,
        assignments: Sequence[tuple[StrategyCard, AgentRuntime]] | None = None,
        round_index: int,
        activity: ActivityStream | None,
        store: ArtifactStore,
    ) -> None:
        if self.config.topology.mode != "hierarchical_sparse":
            return
        registry = state.route_registry
        if registry is None:
            raise RuntimeError(
                "hierarchical_sparse requires an initialized RouteRegistry"
            )

        assigned_by_strategy = {
            strategy.strategy_id: agent for strategy, agent in (assignments or [])
        }
        for strategy in state.strategies:
            existing = registry.route_for_strategy(strategy.strategy_id)
            route = existing or registry.register_route(strategy)
            if existing is None:
                store.append_event("route_registered", route)
                if activity is not None:
                    activity.info(
                        "route_registered",
                        title="Hierarchical route registered",
                        detail=strategy.title,
                        stage="route_team",
                        agent_id=(
                            assigned_by_strategy[strategy.strategy_id].id
                            if strategy.strategy_id in assigned_by_strategy
                            else strategy.assigned_agent_id
                        ),
                        metrics={
                            "route_id": route.route_id,
                            "strategy_id": strategy.strategy_id,
                            "recovered": state.resumed,
                        },
                    )

        needs_assignment: list[StrategyCard] = []
        for strategy in state.strategies:
            route = registry.route_for_strategy(strategy.strategy_id)
            if route is None:
                raise RuntimeError(
                    "hierarchical route registration failed for strategy "
                    f"{strategy.strategy_id}"
                )
            if any(member.role == RouteRole.PROVER for member in route.members):
                continue
            if strategy.strategy_id in assigned_by_strategy:
                continue
            if strategy.assigned_agent_id:
                try:
                    assigned_by_strategy[strategy.strategy_id] = router.pool.get(
                        strategy.assigned_agent_id
                    )
                    continue
                except KeyError:
                    pass
            needs_assignment.append(strategy)

        if needs_assignment:
            assigned_by_strategy.update(
                {
                    strategy.strategy_id: agent
                    for strategy, agent in router.assign_explorers(needs_assignment)
                }
            )

        for strategy in state.strategies:
            route = registry.route_for_strategy(strategy.strategy_id)
            if route is None or any(
                member.role == RouteRole.PROVER for member in route.members
            ):
                continue
            agent = assigned_by_strategy.get(strategy.strategy_id)
            if agent is None:
                raise RuntimeError(
                    "hierarchical route has no available Prover for strategy "
                    f"{strategy.strategy_id}"
                )
            registry.assign_member(
                route.route_id, agent.id, RouteRole.PROVER, round_index
            )
            store.append_event(
                "route_member_assigned",
                {
                    "route_id": route.route_id,
                    "agent_id": agent.id,
                    "role": RouteRole.PROVER.value,
                    "round_index": round_index,
                    "recovered": state.resumed,
                },
            )

        routes = registry.routes
        limit = self.config.topology.cross_route.max_neighbors_per_route
        strategies = {item.strategy_id: item for item in state.strategies}
        neighborhoods = select_sparse_route_neighbors(
            [
                (route.route_id, strategies[route.strategy_id])
                for route in routes
                if route.strategy_id in strategies
            ],
            max_neighbors=limit,
        )
        for route_id, neighbors in neighborhoods.items():
            registry.set_neighbors(route_id, neighbors)
        self._persist_hierarchical_route_runtime(state, store)

    def _ensure_hierarchical_prover(
        self,
        state: SolveState | None,
        strategy: StrategyCard,
        agent: AgentRuntime,
        *,
        round_index: int,
        activity: ActivityStream | None,
        store: ArtifactStore,
    ) -> str | None:
        if self.config.topology.mode != "hierarchical_sparse":
            return None
        if state is None or state.route_registry is None:
            raise RuntimeError(
                "hierarchical_sparse cannot explore without an initialized RouteRegistry"
            )
        registry = state.route_registry
        changed = False
        route = registry.route_for_strategy(strategy.strategy_id)
        if route is None:
            route = registry.register_route(strategy)
            changed = True
            store.append_event("route_registered", route)
            if activity is not None:
                activity.info(
                    "route_registered",
                    title="Missing hierarchical route repaired before exploration",
                    detail=strategy.title,
                    stage="route_team",
                    agent_id=agent.id,
                    metrics={
                        "route_id": route.route_id,
                        "strategy_id": strategy.strategy_id,
                        "recovered": state.resumed,
                    },
                )
            registry.recompute_neighbors()
        if not registry.owns_agent(route.route_id, agent.id, RouteRole.PROVER):
            registry.assign_member(
                route.route_id,
                agent.id,
                RouteRole.PROVER,
                round_index,
            )
            changed = True
            store.append_event(
                "route_member_assigned",
                {
                    "route_id": route.route_id,
                    "agent_id": agent.id,
                    "role": RouteRole.PROVER.value,
                    "round_index": round_index,
                    "actual_selected_prover": True,
                },
            )
        if changed:
            self._persist_hierarchical_route_runtime(state, store)
        return route.route_id

    @staticmethod
    def _persist_hierarchical_route_runtime(
        state: SolveState,
        store: ArtifactStore,
    ) -> None:
        components = {
            "route_registry": state.route_registry,
            "message_broker": state.message_broker,
            "typed_memory": state.typed_memory,
            "proof_graph": state.proof_graph,
        }
        for name, component in components.items():
            if component is not None:
                store.write_json("structured", name, component.export_state())

    def _route_for_strategy(self, state: SolveState, strategy_id: str) -> str | None:
        if state.route_registry is None:
            return None
        route = state.route_registry.route_for_strategy(strategy_id)
        return route.route_id if route else None

    def _sync_hierarchical_artifacts(
        self,
        state: SolveState,
        *,
        problem: ProblemContract,
        memory: LemmaMemory,
        current_round: int,
        store: ArtifactStore,
    ) -> None:
        broker = state.message_broker
        graph = state.proof_graph
        registry = state.route_registry
        if broker is None or graph is None or registry is None or graph.frozen:
            return
        attempts_by_id = {item.attempt_id: item for item in state.attempts}
        for claim in memory.claims:
            attempt = attempts_by_id.get(claim.source_attempt_id or "")
            if attempt is None:
                continue
            route_id = self._route_for_strategy(state, attempt.strategy_id)
            if route_id is None:
                continue
            if not registry.owns_agent(route_id, attempt.agent_id, RouteRole.PROVER):
                try:
                    registry.assign_member(
                        route_id, attempt.agent_id, RouteRole.PROVER, current_round
                    )
                except ValueError:
                    continue
            report = state.aggregate_reports.get(attempt.attempt_id)
            team_reviews = (state.route_team_reviews or {}).get(attempt.attempt_id, [])
            team_review = team_reviews[-1] if team_reviews else None
            teams_enabled = self.config.topology.route_teams.enabled
            team_global_allowed = team_reviews_allow_global_share(
                team_reviews,
                teams_enabled=teams_enabled,
            )
            if teams_enabled:
                referee_id = (
                    str(team_review.get("referee_agent_id"))
                    if team_review and team_review.get("referee_agent_id")
                    else None
                )
            else:
                referee_id = (
                    report.agent_id
                    if report is not None
                    and report.agent_id not in {"system-aggregate", attempt.agent_id}
                    else next(
                        (
                            item.agent_id
                            for item in state.reports
                            if item.target_id == attempt.attempt_id
                            and item.agent_id != attempt.agent_id
                        ),
                        None,
                    )
                )
            if referee_id is not None and not registry.owns_agent(
                route_id, referee_id, RouteRole.REFEREE
            ):
                try:
                    registry.assign_member(
                        route_id, referee_id, RouteRole.REFEREE, current_round
                    )
                except ValueError:
                    referee_id = None
            if teams_enabled:
                skeptic_id = (
                    str(team_review.get("skeptic_agent_id"))
                    if team_review and team_review.get("skeptic_agent_id")
                    else None
                )
            else:
                skeptic_id = next(
                    (
                        item.agent_id
                        for item in state.reports
                        if item.target_id == attempt.attempt_id
                        and item.stage == VerificationStage.DETAILED
                        and item.agent_id not in {attempt.agent_id, referee_id}
                    ),
                    None,
                )
            if skeptic_id is not None and not registry.owns_agent(
                route_id, skeptic_id, RouteRole.SKEPTIC
            ):
                try:
                    registry.assign_member(
                        route_id, skeptic_id, RouteRole.SKEPTIC, current_round
                    )
                except ValueError:
                    skeptic_id = None
            tier = MemoryTier.INSIGHT
            evidence = EvidenceType.UNVERIFIED_IDEA
            message_type = MessageType.CLAIM_PROPOSAL
            verification_status = claim.status
            confidence = claim.verification_confidence or 0.0
            if (
                claim.status == ClaimStatus.VERIFIED
                and referee_id is not None
                and team_global_allowed
            ):
                tier = MemoryTier.FACT
                evidence = EvidenceType.NATURAL_PROOF_AUDITED
                message_type = MessageType.VERIFIED_LEMMA
            elif claim.status == ClaimStatus.REJECTED:
                tier = MemoryTier.NEGATIVE
                message_type = MessageType.FAILURE_RECORD
            normalized = self._normalize_statement(claim.statement or claim.conclusion)
            has_implicit_quantifier = any(
                marker in normalized.casefold()
                for marker in ("for all", "for every", "there exists", "∀", "∃")
            )
            if has_implicit_quantifier and not claim.scope_limitations:
                tier = MemoryTier.INSIGHT
                evidence = EvidenceType.UNVERIFIED_IDEA
                message_type = MessageType.CLAIM_PROPOSAL
                verification_status = ClaimStatus.UNCERTAIN
            message = MessageEnvelope(
                message_id=f"msg_claim_{claim.content_hash[:12]}",
                problem_hash=problem.integrity_hash,
                source_agent_id=attempt.agent_id,
                source_route_id=route_id,
                source_role=RouteRole.PROVER,
                message_type=message_type,
                statement=claim.statement,
                normalized_statement=normalized,
                assumptions=claim.assumptions,
                conclusion=claim.conclusion,
                dependencies=claim.dependencies,
                scope_limitations=claim.scope_limitations,
                evidence_type=evidence,
                memory_tier=tier,
                verification_status=verification_status,
                verification_confidence=confidence,
                normalization_confidence=(0.7 if has_implicit_quantifier else 1.0),
                artifact_refs=[ref.artifact_ref for ref in claim.evidence_refs],
                round_created=current_round,
                ttl_rounds=self.config.topology.cross_route.message_ttl_rounds,
            )
            if broker.contains(message, current_round=current_round):
                continue
            publication = broker.publish(
                message,
                referee_agent_id=referee_id,
                current_round=current_round,
            )
            if (
                publication.accepted
                and tier == MemoryTier.FACT
                and state.inspiration_engine is not None
            ):
                source_strategy = next(
                    (
                        item
                        for item in state.strategies
                        if item.strategy_id == attempt.strategy_id
                    ),
                    None,
                )
                proposal_id = (
                    source_strategy.inspiration_proposal_id
                    if source_strategy is not None
                    else None
                )
                if proposal_id is None and attempt.strategy_id.startswith(
                    "strategy_inspiration_"
                ):
                    proposal_id = attempt.strategy_id.removeprefix("strategy_")
                if proposal_id in state.inspiration_engine.proposals:
                    state.inspiration_engine.mark_verified(
                        proposal_id, message.message_id
                    )
        for attempt in state.attempts:
            route_id = self._route_for_strategy(state, attempt.strategy_id)
            if route_id is None or not registry.owns_agent(
                route_id, attempt.agent_id, RouteRole.PROVER
            ):
                continue
            for gap in attempt.unresolved_gaps:
                gap_hash = stable_hash(
                    (problem.integrity_hash, route_id, self._normalize_statement(gap))
                )
                message = MessageEnvelope(
                    message_id=f"msg_gap_{gap_hash[:12]}",
                    problem_hash=problem.integrity_hash,
                    source_agent_id=attempt.agent_id,
                    source_route_id=route_id,
                    source_role=RouteRole.PROVER,
                    message_type=MessageType.PROOF_OBLIGATION,
                    statement=gap,
                    normalized_statement=self._normalize_statement(gap),
                    conclusion=gap,
                    evidence_type=EvidenceType.UNVERIFIED_IDEA,
                    memory_tier=MemoryTier.INSIGHT,
                    verification_status=ClaimStatus.PROPOSED,
                    normalization_confidence=1.0,
                    round_created=current_round,
                    ttl_rounds=self.config.topology.cross_route.message_ttl_rounds,
                )
                if not broker.contains(message, current_round=current_round):
                    broker.publish(
                        message,
                        referee_agent_id=None,
                        current_round=current_round,
                    )

        for checkpoint in state.checkpoints:
            route_id = self._route_for_strategy(state, checkpoint.strategy_id)
            source_agent_id = checkpoint.source_agent_id
            if route_id is None or not source_agent_id:
                continue
            if not registry.owns_agent(route_id, source_agent_id, RouteRole.PROVER):
                try:
                    registry.assign_member(
                        route_id, source_agent_id, RouteRole.PROVER, current_round
                    )
                except ValueError:
                    continue
            checkpoint_message = checkpoint_to_route_message(
                checkpoint,
                route_id=route_id,
                source_agent_id=source_agent_id,
                round_index=current_round,
                ttl_rounds=self.config.topology.cross_route.message_ttl_rounds,
            )
            if not broker.contains(checkpoint_message, current_round=current_round):
                broker.publish(
                    checkpoint_message,
                    referee_agent_id=None,
                    current_round=current_round,
                )

        self._update_route_progress_state(state, current_round=current_round)

        if state.bridge_broker is not None:
            state.bridge_broker.detect(
                current_round=current_round,
                allowed_fact_ids=[item.message_id for item in state.typed_memory.facts]
                if state.typed_memory
                else [],
                forbidden_negative_ids=[
                    item.message_id
                    for item in (
                        state.typed_memory.negatives if state.typed_memory else []
                    )
                    if isinstance(item, MessageEnvelope)
                ],
                budget_available=True,
            )
        if state.contradiction_broker is not None:
            state.contradiction_broker.detect(current_round=current_round)

    def _materialize_post_failure_bottleneck(
        self,
        state: SolveState | None,
        diagnostic: PostFailureBottleneckDiagnostic,
    ) -> ProofObligation | None:
        """Keep a no-artifact diagnosis route-local and outside the Fact gate."""

        if (
            state is None
            or state.proof_graph is None
            or state.proof_graph.frozen
            or diagnostic.route_id is None
        ):
            return None
        obligation = ProofObligation(
            obligation_id=f"obl_stall_{diagnostic.failure_fingerprint[:12]}",
            problem_hash=diagnostic.problem_hash,
            route_ids=[diagnostic.route_id],
            kind=ObligationKind.SUBGOAL,
            statement=diagnostic.smallest_blocked_claim,
            normalized_statement=self._normalize_statement(
                diagnostic.smallest_blocked_claim
            ),
            status="blocked",
            priority=0.95,
            centrality=0.75,
            first_error_fingerprint=(
                f"post_failure:{diagnostic.failure_fingerprint[:24]}"
            ),
        )
        materialized = state.proof_graph.add_obligation(obligation)
        if state.route_registry is not None:
            try:
                route = state.route_registry.get(diagnostic.route_id)
            except KeyError:
                route = None
            if route is not None:
                route.requires_revision = True
                route.revision_summary = (
                    "A no-artifact route failure was reduced to the explicit "
                    f"obligation {materialized.obligation_id}."
                )
                if (
                    self.config.continuation.post_failure_trigger_inspiration
                    and diagnostic.requires_inspiration
                ):
                    route.stagnation_rounds = max(
                        route.stagnation_rounds,
                        self.config.topology.inspiration.stagnation_rounds,
                    )
        return materialized

    def _admit_deep_exploration(
        self,
        state: SolveState | None,
        *,
        problem: ProblemContract,
        strategy: StrategyCard,
        checkpoint: ProofCheckpoint,
        route_id: str | None,
        round_index: int,
        meta_approved: bool,
        remaining_calls: int,
        remaining_tokens: int | None,
        store: ArtifactStore,
    ) -> tuple[ExplorationAdmission | None, ExplorationSignature | None]:
        if (
            state is None
            or state.deep_exploration_registry is None
            or route_id is None
            or not self.config.deep_exploration_policy.enabled
        ):
            return None, None

        obligations = (
            [
                item
                for item in state.proof_graph.obligations
                if item.status not in {"closed", "refuted"}
                and (not item.route_ids or route_id in item.route_ids)
            ]
            if state.proof_graph is not None
            else []
        )
        obligations.sort(
            key=lambda item: (
                not str(item.first_error_fingerprint or "").startswith("post_failure:"),
                -item.priority,
                -item.centrality,
                item.obligation_id,
            )
        )
        bottleneck_obligation = obligations[0] if obligations else None
        if bottleneck_obligation is not None and str(
            bottleneck_obligation.first_error_fingerprint or ""
        ).startswith("post_failure:"):
            target_statement = bottleneck_obligation.statement
            target_obligation_id = bottleneck_obligation.obligation_id
        elif checkpoint.current_goal:
            target_statement = checkpoint.current_goal
            target_obligation_id = (
                bottleneck_obligation.obligation_id
                if bottleneck_obligation is not None
                and self._normalize_statement(bottleneck_obligation.statement)
                == self._normalize_statement(checkpoint.current_goal)
                else None
            )
        elif checkpoint.remaining_subgoals:
            target_statement = checkpoint.remaining_subgoals[0]
            target_obligation_id = None
        elif bottleneck_obligation is not None:
            target_statement = bottleneck_obligation.statement
            target_obligation_id = bottleneck_obligation.obligation_id
        else:
            target_statement = strategy.bottleneck
            target_obligation_id = None

        route = state.route_registry.get(route_id) if state.route_registry else None
        mechanism_tags = [
            strategy.title,
            strategy.core_idea,
            strategy.bottleneck,
            *strategy.tags,
            *strategy.expected_lemmas,
            *(route.mechanism_signature if route is not None else []),
        ]
        representation_tags: list[str] = []
        construction_tags: list[str] = []
        invariant_tags: list[str] = []
        transformation_tags: list[str] = []
        proposal = None
        review = None
        if strategy.inspiration_proposal_id and state.inspiration_engine is not None:
            proposal = state.inspiration_engine.proposals.get(
                strategy.inspiration_proposal_id
            )
            review = state.inspiration_engine.reviews.get(
                strategy.inspiration_proposal_id
            )
        if proposal is not None:
            novelty = proposal.novelty_signature
            representation_tags.extend(novelty.representation_tags)
            mechanism_tags.extend(novelty.mechanism_tags)
            transformation_tags.extend(novelty.key_transformations)
            transformation_tags.extend(novelty.proof_principles)
            if proposal.representation is not None:
                representation_tags.extend(
                    [
                        proposal.representation.representation_name,
                        *proposal.representation.preserved_invariants,
                    ]
                )
            if proposal.construction is not None:
                construction_tags.extend(proposal.construction.constructed_objects)
            if proposal.invariant is not None:
                invariant_tags.extend(
                    [
                        proposal.invariant.state_definition,
                        proposal.invariant.candidate_expression,
                        proposal.invariant.behavior,
                    ]
                )
            if proposal.reverse_goal is not None:
                transformation_tags.extend(
                    proposal.reverse_goal.sufficient_intermediate_claims
                )

        signature = ExplorationSignature(
            problem_hash=problem.integrity_hash,
            verified_checkpoint_id=checkpoint.checkpoint_id,
            verified_checkpoint_hash=checkpoint.content_hash,
            target_obligation_id=target_obligation_id,
            target_statement=target_statement,
            mechanism_tags=mechanism_tags,
            representation_tags=representation_tags,
            construction_tags=construction_tags,
            invariant_tags=invariant_tags,
            transformation_tags=transformation_tags,
            assumptions=[
                *checkpoint.active_assumptions,
                *strategy.prerequisites,
            ],
            route_id=route_id,
        )
        referee_confirmed = bool(
            review is not None
            and review.semantically_distinct
            and review.recommendation != "reject"
        )
        evidence = ExplorationEvidence(
            has_verified_checkpoint=bool(
                checkpoint.verified_steps or checkpoint.verified_claim_ids
            ),
            explicit_critical_target=bool(
                checkpoint.current_goal
                or checkpoint.remaining_subgoals
                or bottleneck_obligation is not None
            ),
            meta_approved=(
                meta_approved
                or bool(
                    proposal is not None
                    and proposal.mechanism == InspirationMechanism.META_REPLAN
                    and referee_confirmed
                )
            ),
            final_reserve_available=(
                remaining_calls
                >= self.config.deep_exploration_policy.min_remaining_calls_for_128k
                and (
                    remaining_tokens is None
                    or remaining_tokens
                    >= self.config.deep_exploration_policy.min_remaining_tokens_for_128k
                )
            ),
            novelty_review_passed=referee_confirmed,
            referee_confirmed_mechanism_change=referee_confirmed,
        )

        if referee_confirmed:
            for parent_hash in list(state.deep_exploration_registry.locked_signatures):
                parent = state.deep_exploration_registry._latest_by_signature(
                    parent_hash
                )
                if (
                    parent is None
                    or parent.route_id != route_id
                    or parent.signature.verified_checkpoint_hash
                    != signature.verified_checkpoint_hash
                ):
                    continue
                pivot = state.deep_exploration_registry.register_pivot(
                    route_id=route_id,
                    parent_signature_hash=parent_hash,
                    new_signature=signature,
                    referee_confirmed=True,
                )
                if pivot is not None:
                    store.append_event(
                        "local_bottleneck_pivot_registered",
                        pivot.model_dump(mode="json"),
                    )
                    break

        admission = state.deep_exploration_registry.admit(
            signature,
            route_id=route_id,
            round_index=round_index,
            evidence=evidence,
        )
        store.write_json(
            "structured",
            "deep_exploration_registry",
            state.deep_exploration_registry.export_state(),
        )
        return admission, signature

    def _finish_deep_exploration(
        self,
        state: SolveState | None,
        admission: ExplorationAdmission | None,
        *,
        outcome: ExplorationOutcome,
        usage: UsageRecord,
        checkpoint_after: ProofCheckpoint,
        proof_debt_before: float | None,
        current_goal_before: str | None,
        reason: str,
        store: ArtifactStore,
        current_goal_override: str | None = None,
    ) -> None:
        if (
            state is None
            or state.deep_exploration_registry is None
            or admission is None
            or admission.lease_id is None
        ):
            return
        record = state.deep_exploration_registry.attempts.get(admission.lease_id)
        if record is None or record.outcome != ExplorationOutcome.RUNNING:
            return
        proof_debt_after = (
            state.proof_graph.proof_debt(record.route_id)
            if state.proof_graph is not None
            else None
        )
        current_goal_after = (
            current_goal_override
            if current_goal_override is not None
            else checkpoint_after.current_goal
        )
        finished = state.deep_exploration_registry.finish(
            admission.lease_id,
            outcome,
            usage=usage,
            checkpoint_after_hash=checkpoint_after.content_hash,
            proof_debt_changed=(
                proof_debt_before is not None
                and proof_debt_after is not None
                and abs(proof_debt_before - proof_debt_after) > 1e-9
            ),
            current_goal_changed=(
                self._normalize_statement(current_goal_before or "")
                != self._normalize_statement(current_goal_after or "")
            ),
            reason=reason,
        )
        event = {
            "lease_id": finished.lease_id,
            "route_id": finished.route_id,
            "signature_hash": finished.signature.signature_hash,
            "granted_tier": finished.granted_tier,
            "max_output_tokens": finished.max_output_tokens,
            "outcome": finished.outcome.value,
            "usage": finished.usage.model_dump(mode="json"),
            "proof_debt_changed": finished.proof_debt_changed,
            "current_goal_changed": finished.current_goal_changed,
            "reason": reason,
        }
        store.append_event("deep_exploration_finished", event)
        store.write_json(
            "structured",
            "deep_exploration_registry",
            state.deep_exploration_registry.export_state(),
        )
        if state.deep_exploration_registry.locked_signatures.get(
            finished.signature.signature_hash
        ):
            store.append_event(
                "deep_exploration_signature_locked",
                {
                    **event,
                    "next_action": (
                        "use the verified parent checkpoint and request a "
                        "referee-confirmed mechanism pivot"
                    ),
                },
            )

    @staticmethod
    def _is_referee_confirmed_inspiration(
        state: SolveState | None, strategy: StrategyCard
    ) -> bool:
        if (
            state is None
            or state.inspiration_engine is None
            or not strategy.inspiration_proposal_id
        ):
            return False
        review = state.inspiration_engine.reviews.get(strategy.inspiration_proposal_id)
        return bool(
            review is not None
            and review.semantically_distinct
            and review.recommendation != "reject"
        )

    def _update_route_progress_state(
        self, state: SolveState, *, current_round: int
    ) -> None:
        registry = state.route_registry
        typed_memory = state.typed_memory
        if registry is None:
            return
        for route in registry.routes:
            attempts = sorted(
                (
                    item
                    for item in state.attempts
                    if item.strategy_id == route.strategy_id
                ),
                key=lambda item: (item.round_index, item.attempt_id),
            )
            if not attempts:
                continue
            latest = attempts[-1]
            route.latest_attempt_id = latest.attempt_id
            route.latest_checkpoint_id = latest.latest_checkpoint_id
            route.failure_count = sum(
                state.aggregate_reports.get(item.attempt_id) is not None
                and state.aggregate_reports[item.attempt_id].verdict
                == VerificationVerdict.FAIL
                for item in attempts
            )
            progress_rounds = {
                item.round_index
                for item in attempts
                if state.aggregate_reports.get(item.attempt_id) is not None
                and state.aggregate_reports[item.attempt_id].verdict
                == VerificationVerdict.PASS
            }
            if typed_memory is not None:
                progress_rounds.update(
                    item.round_created
                    for item in typed_memory.facts_for_route(route.route_id)
                )
            if progress_rounds:
                route.stagnation_rounds = max(0, current_round - max(progress_rounds))
            else:
                route.stagnation_rounds = max(
                    0, current_round - attempts[0].round_index + 1
                )
            if (
                self.config.continuation.post_failure_trigger_inspiration
                and state.proof_graph is not None
                and any(
                    item.status == "blocked"
                    and (item.first_error_fingerprint or "").startswith("post_failure:")
                    and route.route_id in item.route_ids
                    for item in state.proof_graph.obligations
                )
            ):
                route.stagnation_rounds = max(
                    route.stagnation_rounds,
                    self.config.topology.inspiration.stagnation_rounds,
                )

    def _hierarchical_graph_signals(
        self, state: SolveState
    ) -> dict[str, dict[str, object]]:
        if (
            state.route_registry is None
            or state.proof_graph is None
            or state.typed_memory is None
        ):
            return {}
        graph = state.proof_graph
        registry = state.route_registry
        shared = graph.find_shared_bottlenecks()
        result: dict[str, dict[str, object]] = {}
        for route in registry.routes:
            debt = graph.proof_debt(route.route_id)
            history = (state.proof_debt_history or {}).setdefault(route.route_id, [])
            reduction = max(0.0, history[-1] - debt) if history else 0.0
            if not history or history[-1] != debt:
                history.append(debt)
            route_obligations = [
                item for item in graph.obligations if route.route_id in item.route_ids
            ]
            shared_count = sum(
                1
                for group in shared
                if any(route.route_id in item.route_ids for item in group)
            )
            contradictions = (
                [
                    item
                    for item in state.contradiction_broker.unresolved()
                    if route.route_id in item.route_ids
                ]
                if state.contradiction_broker is not None
                else []
            )
            counterexamples = [
                item
                for item in state.typed_memory.negatives_for_route(route.route_id)
                if isinstance(item, MessageEnvelope)
                and item.evidence_type == EvidenceType.COUNTEREXAMPLE
            ]
            redundancy = 0.0
            if state.duplicate_route_detector is not None:
                for other in registry.routes:
                    if other.route_id == route.route_id:
                        continue
                    redundancy = max(
                        redundancy,
                        state.duplicate_route_detector.similarity(route, other),
                    )
            accepted_receipts = (
                [
                    item
                    for item in state.message_broker.receipts
                    if item.target_route_id == route.route_id
                    and item.status.value == "accepted"
                ]
                if state.message_broker is not None
                else []
            )
            result[route.strategy_id] = {
                "proof_debt": debt,
                "proof_debt_reduction": reduction,
                "verified_fact_gain": len(
                    state.typed_memory.facts_for_route(route.route_id)
                ),
                "shared_obligation_count": shared_count,
                "high_centrality_obligation_count": sum(
                    item.centrality >= 0.6 and item.status != "closed"
                    for item in route_obligations
                ),
                "contradiction_count": len(contradictions),
                "counterexample_count": len(counterexamples),
                "message_utility": min(1.0, len(accepted_receipts) / 3),
                "route_redundancy": redundancy,
                "bridge_opportunity": min(1.0, shared_count / 2),
                "negative_memory_hits": len(
                    state.typed_memory.negatives_for_route(route.route_id)
                ),
                "inspiration_trigger_count": len(
                    state.inspiration_engine.triggers
                    if state.inspiration_engine is not None
                    else {}
                ),
                "novelty_score": max(0.0, 1.0 - redundancy),
                "representation_diversity": max(0.0, 1.0 - redundancy),
                "analogy_opportunity": (
                    1.0
                    if state.inspiration_engine is not None
                    and state.inspiration_engine.analogy_library.records
                    else 0.0
                ),
                "construction_opportunity": 1.0
                if any(item.status != "closed" for item in route_obligations)
                else 0.0,
                "surprise_budget_remaining": (
                    state.inspiration_engine.surprise_explorer.state.remaining_calls
                    if state.inspiration_engine is not None
                    else 0
                ),
            }
        return result

    def _inspiration_snapshot(
        self,
        state: SolveState,
        *,
        remaining_calls: int,
    ) -> InspirationSnapshot | None:
        if (
            state.inspiration_engine is None
            or state.route_registry is None
            or state.proof_graph is None
        ):
            return None
        routes = state.route_registry.active_routes(state.current_round)
        post_failure_obligations = (
            [
                item
                for item in state.proof_graph.obligations
                if item.status == "blocked"
                and (item.first_error_fingerprint or "").startswith("post_failure:")
            ]
            if self.config.continuation.post_failure_trigger_inspiration
            else []
        )
        post_failure_route_ids = self._deduplicate_strings(
            [
                route_id
                for item in post_failure_obligations
                for route_id in item.route_ids
                if any(route.route_id == route_id for route in routes)
            ]
        )
        attempt_by_strategy = {
            item.strategy_id: item
            for item in sorted(state.attempts, key=lambda item: item.round_index)
        }
        failed_routes: list[str] = []
        stagnation: dict[str, int] = {}
        first_errors: list[str] = []
        for route in routes:
            attempt = attempt_by_strategy.get(route.strategy_id)
            if attempt is None:
                continue
            report = state.aggregate_reports.get(attempt.attempt_id)
            if report is not None and report.verdict == VerificationVerdict.FAIL:
                failed_routes.append(route.route_id)
            stagnation[route.route_id] = route.stagnation_rounds
            if report is not None and report.first_error_step:
                first_errors.append(report.first_error_step)
        first_errors.extend(
            item.first_error_fingerprint
            for item in post_failure_obligations
            if item.first_error_fingerprint
        )
        shared = state.proof_graph.find_shared_bottlenecks()
        signatures = [
            state.inspiration_engine.mechanism_normalizer.signature_from_route_tags(
                route.mechanism_signature,
                targeted_obligation_ids=[
                    item.obligation_id
                    for item in state.proof_graph.obligations
                    if route.route_id in item.route_ids and item.status != "closed"
                ],
            )
            for route in routes
        ]
        redundancy = 0.0
        if state.duplicate_route_detector is not None:
            for index, left in enumerate(routes):
                for right in routes[index + 1 :]:
                    redundancy = max(
                        redundancy,
                        state.duplicate_route_detector.similarity(left, right),
                    )
        total_tokens = sum(item.usage.total_tokens for item in state.attempts)
        route_budget_share: dict[str, float] = {}
        for route in routes:
            spent = sum(
                item.usage.total_tokens
                for item in state.attempts
                if item.strategy_id == route.strategy_id
            )
            route_budget_share[route.route_id] = spent / max(1, total_tokens)
        histories = state.proof_debt_history or {}
        debt_reduction = sum(
            max(0.0, values[-2] - values[-1])
            for values in histories.values()
            if len(values) >= 2
        )
        message_utility_by_route: dict[str, float] = {}
        if state.message_broker is not None:
            message_utility_by_route = {
                route.route_id: state.message_broker.utility_for_route(route.route_id)
                for route in routes
            }
        return InspirationSnapshot(
            round_index=state.current_round,
            domain=(state.triage.problem_kind.value if state.triage else "unknown"),
            active_route_ids=[item.route_id for item in routes],
            failed_route_ids=failed_routes,
            stagnation_rounds_by_route=stagnation,
            verified_fact_gain_recent=(
                sum(
                    item.round_created == state.current_round
                    for item in state.typed_memory.facts
                )
                if state.typed_memory
                else 0
            ),
            proof_debt_by_route={
                route.route_id: state.proof_graph.proof_debt(route.route_id)
                for route in routes
            },
            proof_debt_reduction_recent=debt_reduction,
            proof_debt_history=[
                value for values in histories.values() for value in values
            ],
            first_error_fingerprints=first_errors,
            route_redundancy=redundancy,
            shared_bottleneck_ids=[
                item.obligation_id for group in shared for item in group
            ],
            route_budget_share=route_budget_share,
            message_utility_by_route=message_utility_by_route,
            unresolved_conflict_ids=(
                [
                    item.contradiction_id
                    for item in state.contradiction_broker.unresolved()
                ]
                if state.contradiction_broker is not None
                else []
            ),
            final_repair_failed=state.final_repair_failed,
            manual_trigger=bool(post_failure_route_ids),
            manual_trigger_route_ids=post_failure_route_ids,
            manual_evidence_refs=[
                item.obligation_id for item in post_failure_obligations
            ],
            remaining_calls=remaining_calls,
            finalization_reserve_calls=(
                state.inspiration_engine.surprise_explorer.state.finalization_reserve_calls
            ),
            current_path_count=len(state.strategies),
            max_paths=self.config.budget.max_paths,
            route_signatures=signatures,
            open_obligation_ids=[
                item.obligation_id
                for item in state.proof_graph.obligations
                if item.status != "closed"
            ],
        )

    async def _run_inspiration_round(
        self,
        state: SolveState,
        *,
        problem: ProblemContract,
        remaining_calls: int,
        store: ArtifactStore,
        runner: StructuredAgentRunner,
        prompts: PromptFactory,
        allocator: SoftBudgetAllocator,
        router: SparseTopologyRouter,
        memory: LemmaMemory,
        tools: ToolBroker,
    ) -> None:
        snapshot = self._inspiration_snapshot(state, remaining_calls=remaining_calls)
        engine = state.inspiration_engine
        if snapshot is None or engine is None:
            return
        triggers = engine.detect_triggers(snapshot)
        tasks = engine.select_tasks(triggers, snapshot)
        admission = admit_inspiration_tasks(
            tasks,
            allocator,
            current_path_count=snapshot.current_path_count,
            has_candidate=self._has_synthesis_ready_candidate(state),
        )
        store.append_event(
            "inspiration_scheduler_admission",
            {
                "admitted_task_ids": [
                    task.task_id for task in admission.admitted_tasks
                ],
                "rejected": admission.rejected,
                "decision": admission.decision.model_dump(mode="json"),
            },
        )
        tasks = admission.admitted_tasks
        if not tasks:
            return
        if engine.inspiration_config.mode == "active":
            breakdown = allocator.inspiration_call_breakdown()
            reserved_tasks = []
            for task in tasks:
                reservation, reason = engine.reserve_task_calls(
                    task,
                    snapshot=snapshot,
                    **breakdown,
                )
                if reservation is None:
                    store.append_event(
                        "inspiration_task_reservation_rejected",
                        {"task_id": task.task_id, "reason": reason},
                    )
                    continue
                reserved_tasks.append(task)
            tasks = reserved_tasks
            if not tasks:
                return
        try:
            proposals = await self._generate_inspiration_proposals(
                engine,
                tasks,
                snapshot=snapshot,
                problem=problem,
                runner=runner,
                prompts=prompts,
            )
        except Exception:
            self._finish_inspiration_reservations(engine, tasks, interrupted=True)
            raise
        proposals = engine.select_proposals_for_review(
            proposals,
            existing_signatures=snapshot.route_signatures,
        )
        manual_trigger_ids = {
            trigger.trigger_id
            for trigger in triggers
            if trigger.trigger_type.value == "manual"
        }
        if (
            any(proposal.trigger_id in manual_trigger_ids for proposal in proposals)
            and state.proof_graph is not None
        ):
            consumed: list[str] = []
            for obligation_id in snapshot.manual_evidence_refs:
                try:
                    obligation = state.proof_graph.get_obligation(obligation_id)
                except KeyError:
                    continue
                if obligation.status == "blocked" and (
                    obligation.first_error_fingerprint or ""
                ).startswith("post_failure:"):
                    obligation.status = "open"
                    consumed.append(obligation_id)
            if consumed:
                store.append_event(
                    "post_failure_inspiration_trigger_consumed",
                    {
                        "round_index": state.current_round,
                        "obligation_ids": consumed,
                        "trigger_ids": sorted(manual_trigger_ids),
                    },
                )
        try:
            (
                precomputed,
                counterexamples,
                hidden_assumptions,
            ) = await self._review_inspiration_proposals(
                engine,
                proposals,
                snapshot=snapshot,
                problem=problem,
                runner=runner,
                prompts=prompts,
            )
        except Exception:
            self._finish_inspiration_reservations(engine, tasks, interrupted=True)
            raise
        for task in tasks:
            reservation_id = engine.reservation_id_for_task(task.task_id)
            if reservation_id is None:
                continue
            engine.record_reserved_calls(
                task.task_id,
                runner.ledger.reservation_calls.get(reservation_id, 0),
                phase="proposal_review_pipeline",
            )
        try:
            reviews = await engine.review(
                proposals,
                precomputed_reviews=precomputed,
                immediate_counterexamples=counterexamples,
                hidden_assumptions=hidden_assumptions,
            )
            materializations = engine.materialize(reviews, snapshot)
        except Exception:
            self._finish_inspiration_reservations(engine, tasks, interrupted=True)
            raise
        newly_created_ids = {
            item.proposal_id
            for item in materializations
            if item.action == "route_created"
        }
        new_strategies: list[StrategyCard] = []
        for strategy in engine.materialized_strategies.values():
            if all(
                item.strategy_id != strategy.strategy_id for item in state.strategies
            ):
                state.strategies.append(strategy)
                if strategy.inspiration_proposal_id in newly_created_ids:
                    new_strategies.append(strategy)
        if new_strategies:
            assignments = router.assign_explorers(new_strategies)
            if state.route_registry is not None:
                for strategy, agent in assignments:
                    route = state.route_registry.register_route(strategy)
                    state.route_registry.assign_member(
                        route.route_id,
                        agent.id,
                        RouteRole.PROVER,
                        state.current_round,
                    )
                state.route_registry.recompute_neighbors()
            calls_before_routes = runner.ledger.calls_started
            try:
                attempts = await self._parallel_round_exploration(
                    problem,
                    state,
                    assignments,
                    state.current_round,
                    runner,
                    prompts,
                    router,
                    memory,
                    store,
                    tools,
                    max_segments_this_call=1,
                )
            except Exception:
                self._finish_inspiration_reservations(engine, tasks, interrupted=True)
                raise
            route_calls = max(0, runner.ledger.calls_started - calls_before_routes)
            route_task_ids = list(
                dict.fromkeys(
                    proposal.task_id
                    for strategy in new_strategies
                    if (
                        proposal := engine.proposals.get(
                            strategy.inspiration_proposal_id or ""
                        )
                    )
                    is not None
                    and proposal.task_id is not None
                )
            )
            if route_task_ids and route_calls:
                base, extra = divmod(route_calls, len(route_task_ids))
                for index, task_id in enumerate(route_task_ids):
                    engine.record_reserved_calls(
                        task_id,
                        base + int(index < extra),
                        phase="first_route_attempt",
                    )
            state.attempts.extend(attempts)
            store.append_event(
                "inspiration_route_attempted",
                {
                    "strategy_ids": [item.strategy_id for item in new_strategies],
                    "attempt_ids": [item.attempt_id for item in attempts],
                    "proposal_ids": [
                        item.inspiration_proposal_id for item in new_strategies
                    ],
                },
            )
        store.write_json(
            "inspiration",
            f"round_{state.current_round}",
            {
                "triggers": triggers,
                "tasks": tasks,
                "proposals": proposals,
                "reviews": reviews,
                "materializations": materializations,
            },
        )
        self._finish_inspiration_reservations(engine, tasks)

    @staticmethod
    def _finish_inspiration_reservations(
        engine: InspirationEngine,
        tasks: Sequence[Any],
        *,
        interrupted: bool = False,
    ) -> None:
        for task in tasks:
            engine.finish_task_reservation(
                task.task_id,
                interrupted=interrupted,
            )

    async def _generate_inspiration_proposals(
        self,
        engine: InspirationEngine,
        tasks: Sequence[Any],
        *,
        snapshot: InspirationSnapshot,
        problem: ProblemContract,
        runner: StructuredAgentRunner,
        prompts: PromptFactory,
    ) -> list[InspirationProposal]:
        if engine.inspiration_config.mode != "active":
            return await engine.generate(tasks)
        final_reserve = snapshot.finalization_reserve_calls
        configured_calls = engine.inspiration_config.active_proposals_per_task

        async def generate_one(
            task: Any,
            *,
            proposal_slot: int,
            context_mode: InspirationContextMode,
            agent: AgentRuntime,
        ) -> InspirationProposal | None:
            role, bundle = self._inspiration_agent_prompt(
                engine,
                task,
                snapshot=snapshot,
                problem=problem,
                prompts=prompts,
                context_mode=context_mode,
                proposal_slot=proposal_slot,
            )
            result = await self._safe_call(
                runner,
                role,
                bundle,
                fixed_agent=agent,
                budget_bucket="breadth",
                budget_reservation_id=engine.reservation_id_for_task(task.task_id),
            )
            if result is None:
                return None
            try:
                return engine.register_agent_artifact(
                    task,
                    result.value,
                    source_agent_id=agent.id,
                    state=snapshot,
                    proposal_slot=proposal_slot,
                    context_mode=context_mode,
                )
            except (TypeError, ValueError) as exc:
                if engine.store is not None:
                    engine.store.append_event(
                        "inspiration_agent_artifact_rejected",
                        {
                            "task_id": task.task_id,
                            "agent_id": agent.id,
                            "proposal_slot": proposal_slot,
                            "context_mode": context_mode.value,
                            "reason": str(exc),
                        },
                    )
                return None

        pending: list[Any] = []
        for task in tasks:
            calls_per_task = min(configured_calls, task.max_proposals)
            cold_calls = min(
                engine.inspiration_config.cold_context_proposals_per_task,
                calls_per_task,
            )
            if (
                task.mechanism == InspirationMechanism.STRUCTURAL_ANALOGY
                and not engine.analogy_library.records
            ):
                continue
            if runner.ledger.remaining_calls - final_reserve < calls_per_task:
                continue
            role = self._inspiration_role_for_mechanism(task.mechanism)
            candidates = [
                agent
                for agent in runner.pool.agents
                if agent.supports_role(role) and not agent.in_cooldown
            ]
            if not candidates:
                continue
            selected_agents: list[AgentRuntime] = []
            excluded: set[str] = set()
            for _index in range(min(calls_per_task, len(candidates))):
                selected = runner.pool.select(role, exclude=excluded)
                selected_agents.append(selected)
                excluded.add(selected.id)
            cold_start = calls_per_task - cold_calls
            population = []
            for proposal_slot in range(calls_per_task):
                context_mode = (
                    InspirationContextMode.COLD
                    if proposal_slot >= cold_start
                    else InspirationContextMode.WARM
                )
                agent = selected_agents[proposal_slot % len(selected_agents)]
                population.append(
                    {
                        "proposal_slot": proposal_slot,
                        "context_mode": context_mode.value,
                        "agent_id": agent.id,
                    }
                )
                pending.append(
                    generate_one(
                        task,
                        proposal_slot=proposal_slot,
                        context_mode=context_mode,
                        agent=agent,
                    )
                )
            if engine.store is not None:
                engine.store.append_event(
                    "inspiration_candidate_population_started",
                    {
                        "task_id": task.task_id,
                        "mechanism": task.mechanism.value,
                        "population": population,
                        "parallel_generation": True,
                    },
                )
        if not pending:
            return []
        results = await asyncio.gather(*pending)
        return [proposal for proposal in results if proposal is not None]

    @staticmethod
    def _inspiration_role_for_mechanism(
        mechanism: InspirationMechanism,
    ) -> str:
        return {
            InspirationMechanism.REPRESENTATION_SWITCH: "representation_switchboard",
            InspirationMechanism.STRUCTURAL_ANALOGY: "analogy_agent",
            InspirationMechanism.AUXILIARY_CONSTRUCTION: "construction_inventor",
            InspirationMechanism.INVARIANT_HYPOTHESIS: "invariant_hypothesis_agent",
            InspirationMechanism.REVERSE_GOAL_ANALYSIS: "reverse_goal_analyzer",
            InspirationMechanism.BRIDGE_LEMMA: "reverse_goal_analyzer",
            InspirationMechanism.META_REPLAN: "meta_strategist",
            InspirationMechanism.SURPRISE_EXPLORATION: "representation_switchboard",
        }[mechanism]

    def _inspiration_agent_prompt(
        self,
        engine: InspirationEngine,
        task: Any,
        *,
        snapshot: InspirationSnapshot,
        problem: ProblemContract,
        prompts: PromptFactory,
        context_mode: InspirationContextMode,
        proposal_slot: int,
    ) -> tuple[str, PromptBundle]:
        context = build_inspiration_prompt_context(
            engine,
            task,
            snapshot=snapshot,
            context_mode=context_mode,
            proposal_slot=proposal_slot,
        )
        mechanism = task.mechanism
        if mechanism == InspirationMechanism.REPRESENTATION_SWITCH:
            return (
                "representation_switchboard",
                prompts.representation_switchboard(problem, **context),
            )
        if mechanism == InspirationMechanism.STRUCTURAL_ANALOGY:
            records = engine.analogy_library.search(
                query_text=problem.normalized_statement,
                object_tags=problem.definitions,
                mechanism_tags=[
                    tag
                    for item in snapshot.route_signatures
                    for tag in item.mechanism_tags
                ],
                top_k=engine.inspiration_config.analogy_top_k,
            )
            return (
                "analogy_agent",
                prompts.structural_analogy_search(
                    problem=problem,
                    verified_local_records=records,
                    **context,
                ),
            )
        if mechanism == InspirationMechanism.AUXILIARY_CONSTRUCTION:
            return (
                "construction_inventor",
                prompts.invent_auxiliary_construction(problem=problem, **context),
            )
        if mechanism == InspirationMechanism.INVARIANT_HYPOTHESIS:
            return (
                "invariant_hypothesis_agent",
                prompts.hypothesize_invariant(problem=problem, **context),
            )
        if mechanism in {
            InspirationMechanism.REVERSE_GOAL_ANALYSIS,
            InspirationMechanism.BRIDGE_LEMMA,
        }:
            return (
                "reverse_goal_analyzer",
                prompts.reverse_goal_analysis(problem=problem, **context),
            )
        if mechanism == InspirationMechanism.META_REPLAN:
            return (
                "meta_strategist",
                prompts.persistent_meta_strategy(problem=problem, **context),
            )
        return (
            "representation_switchboard",
            prompts.surprise_exploration(problem=problem, **context),
        )

    async def _review_inspiration_proposals(
        self,
        engine: InspirationEngine,
        proposals: Sequence[InspirationProposal],
        *,
        snapshot: InspirationSnapshot,
        problem: ProblemContract,
        runner: StructuredAgentRunner,
        prompts: PromptFactory,
    ) -> tuple[
        dict[str, InspirationReview],
        dict[str, list[str]],
        dict[str, list[str]],
    ]:
        precomputed: dict[str, InspirationReview] = {}
        counterexamples: dict[str, list[str]] = {}
        hidden: dict[str, list[str]] = {}
        for proposal in proposals:
            eligible = [
                agent
                for agent in runner.pool.agents
                if agent.id != proposal.source_agent_id
                and agent.supports_role("inspiration_referee")
                and not agent.in_cooldown
            ]
            if (
                engine.inspiration_config.mode != "active"
                or not eligible
                or runner.ledger.remaining_calls <= snapshot.finalization_reserve_calls
            ):
                local = engine.referee.review(
                    proposal,
                    reviewer_agent_id="local_deterministic_referee",
                    open_obligation_ids=snapshot.open_obligation_ids,
                    existing_signatures=snapshot.route_signatures,
                )
                precomputed[proposal.proposal_id] = (
                    local.model_copy(update={"recommendation": "store_insight"})
                    if engine.inspiration_config.require_inspiration_referee
                    else local
                )
                continue
            reviewer = runner.pool.select(
                "inspiration_referee",
                exclude={proposal.source_agent_id},
            )
            result = await self._safe_call(
                runner,
                "inspiration_referee",
                prompts.inspiration_referee(
                    problem=problem,
                    proposal=proposal.model_dump(mode="json"),
                    open_obligation_ids=snapshot.open_obligation_ids,
                    existing_novelty_signatures=[
                        item.model_dump(mode="json")
                        for item in snapshot.route_signatures
                    ],
                ),
                fixed_agent=reviewer,
                budget_bucket="verification",
                budget_reservation_id=engine.reservation_id_for_task(proposal.task_id),
            )
            if result is None:
                local = engine.referee.review(
                    proposal,
                    reviewer_agent_id="local_deterministic_referee",
                    open_obligation_ids=snapshot.open_obligation_ids,
                    existing_signatures=snapshot.route_signatures,
                )
                precomputed[proposal.proposal_id] = (
                    local.model_copy(update={"recommendation": "store_insight"})
                    if engine.inspiration_config.require_inspiration_referee
                    else local
                )
                continue
            review = result.value
            review.proposal_id = proposal.proposal_id
            review.reviewer_agent_id = reviewer.id
            precomputed[proposal.proposal_id] = review

            skeptic_candidates = [
                agent
                for agent in runner.pool.agents
                if agent.id not in {proposal.source_agent_id, reviewer.id}
                and agent.supports_role("route_skeptic")
                and not agent.in_cooldown
            ]
            if (
                review.recommendation == "reject"
                or not skeptic_candidates
                or runner.ledger.remaining_calls <= snapshot.finalization_reserve_calls
            ):
                continue
            skeptic = max(
                skeptic_candidates, key=lambda item: (item.trust_score, item.id)
            )
            skeptic_result = await self._safe_call(
                runner,
                "route_skeptic",
                prompts.route_skeptic(
                    problem=problem,
                    inspiration_proposal=proposal.model_dump(mode="json"),
                    repair_request=False,
                    task="quick falsification only",
                ),
                fixed_agent=skeptic,
                budget_bucket="verification",
                budget_reservation_id=engine.reservation_id_for_task(proposal.task_id),
            )
            if skeptic_result is None:
                continue
            report = skeptic_result.value
            if report.verdict == VerificationVerdict.FAIL:
                descriptions = [item.description for item in report.issues]
                immediate = [
                    item for item in descriptions if "counterexample" in item.casefold()
                ]
                counterexamples[proposal.proposal_id] = immediate
                hidden[proposal.proposal_id] = [
                    item for item in descriptions if item not in immediate
                ]
                if not immediate:
                    precomputed[proposal.proposal_id] = review.model_copy(
                        update={"recommendation": "store_insight"}
                    )
        return precomputed, counterexamples, hidden

    async def _execute_hierarchical_action(
        self,
        state: SolveState,
        action: ActionKind,
        *,
        strategy_id: str | None,
        current_round: int,
        store: ArtifactStore,
        problem: ProblemContract,
        runner: StructuredAgentRunner,
        prompts: PromptFactory,
        router: SparseTopologyRouter,
        memory: LemmaMemory,
        tools: ToolBroker,
    ) -> bool:
        if state.route_registry is None:
            return False
        if action == ActionKind.MERGE_ROUTE and state.duplicate_route_detector:
            matches = state.duplicate_route_detector.detect(state.route_registry.routes)
            match = next(
                (
                    item
                    for item in matches
                    if strategy_id is None
                    or state.route_registry.get(item.source_route_id).strategy_id
                    == strategy_id
                ),
                None,
            )
            if match is not None:
                state.route_registry.merge_routes(
                    match.source_route_id, match.target_route_id
                )
                match_payload = {
                    "source_route_id": match.source_route_id,
                    "target_route_id": match.target_route_id,
                    "similarity": match.similarity,
                    "survivor_route_id": match.survivor_route_id,
                    "reason": match.reason,
                }
                store.append_event("route_duplicate_detected", match_payload)
                store.append_event("route_merged", match_payload)
                return True
        if action == ActionKind.COOLDOWN_ROUTE and strategy_id:
            route_id = self._route_for_strategy(state, strategy_id)
            if route_id:
                state.route_registry.mark_cooling(
                    route_id,
                    current_round + self.config.scheduler.failed_path_cooldown_rounds,
                    "scheduler cooldown",
                )
                store.append_event(
                    "route_cooled",
                    {"route_id": route_id, "round_index": current_round},
                )
                return True
        if action in {
            ActionKind.BRIDGE,
            ActionKind.RESOLVE_CONFLICT,
            ActionKind.SEARCH_COUNTEREXAMPLE,
        }:
            return await self._execute_cross_route_verification_task(
                state,
                action,
                strategy_id=strategy_id,
                current_round=current_round,
                store=store,
                problem=problem,
                runner=runner,
                prompts=prompts,
            )
        if (
            action
            in {
                ActionKind.SWITCH_REPRESENTATION,
                ActionKind.TRIGGER_INSPIRATION,
                ActionKind.SEARCH_ANALOGY,
                ActionKind.INVENT_CONSTRUCTION,
                ActionKind.GENERATE_INVARIANT,
                ActionKind.REVERSE_GOAL,
                ActionKind.META_REPLAN,
                ActionKind.SURPRISE_WIDEN,
            }
            and state.inspiration_engine is not None
        ):
            materialized_this_round = any(
                state.inspiration_engine.triggers.get(proposal.trigger_id) is not None
                and state.inspiration_engine.triggers[proposal.trigger_id].round_index
                == current_round
                and materialization.action
                in {"attached", "route_created", "bridge_requested"}
                for proposal_id, materialization in state.inspiration_engine.materializations.items()
                for proposal in [state.inspiration_engine.proposals[proposal_id]]
            )
            pending = [
                strategy
                for strategy in state.inspiration_engine.materialized_strategies.values()
                if all(
                    attempt.strategy_id != strategy.strategy_id
                    for attempt in state.attempts
                )
            ]
            if not pending:
                return materialized_this_round
            assignments = router.assign_explorers(pending)
            for strategy, agent in assignments:
                route_id = self._route_for_strategy(state, strategy.strategy_id)
                if route_id is not None and not state.route_registry.owns_agent(
                    route_id, agent.id, RouteRole.PROVER
                ):
                    try:
                        state.route_registry.assign_member(
                            route_id, agent.id, RouteRole.PROVER, current_round
                        )
                    except ValueError:
                        continue
            attempts = await self._parallel_round_exploration(
                problem,
                state,
                assignments,
                current_round,
                runner,
                prompts,
                router,
                memory,
                store,
                tools,
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
                return True
            return materialized_this_round
        return False

    async def _execute_cross_route_verification_task(
        self,
        state: SolveState,
        action: ActionKind,
        *,
        strategy_id: str | None,
        current_round: int,
        store: ArtifactStore,
        problem: ProblemContract,
        runner: StructuredAgentRunner,
        prompts: PromptFactory,
    ) -> bool:
        registry = state.route_registry
        broker = state.message_broker
        graph = state.proof_graph
        if registry is None or broker is None or graph is None or graph.frozen:
            return False

        role_runner = RoleRunner(runner.pool, registry)
        target: Any
        source_route_id: str
        source_role: RouteRole
        role_name: str
        bundle: PromptBundle
        excluded_authors: set[str]

        if action == ActionKind.BRIDGE:
            bridge = state.bridge_broker
            if bridge is None:
                return False
            target = next(
                (
                    item
                    for item in bridge.tasks
                    if item.task_id not in bridge.completed_task_ids
                    and (
                        strategy_id is None
                        or any(
                            registry.get(route_id).strategy_id == strategy_id
                            for route_id in item.route_ids
                        )
                    )
                ),
                None,
            )
            if target is None:
                return False
            source_route_id = target.route_ids[0]
            source_role = RouteRole.BRIDGE_PROVER
            role_name = "bridge_prover"
            excluded_authors = self._route_authors(registry, target.route_ids)
            bundle = prompts.bridge_lemma(
                problem=problem,
                shared_obligation=target.model_dump(mode="json"),
                verified_facts=[
                    item.model_dump(mode="json")
                    for item in (state.typed_memory.facts if state.typed_memory else [])
                    if item.message_id in target.allowed_fact_ids
                ],
                failure_records=list(target.forbidden_negative_ids),
            )
        elif action == ActionKind.RESOLVE_CONFLICT:
            conflicts = state.contradiction_broker
            if conflicts is None:
                return False
            target = next(
                (
                    item
                    for item in conflicts.unresolved()
                    if strategy_id is None
                    or any(
                        registry.get(route_id).strategy_id == strategy_id
                        for route_id in item.route_ids
                    )
                ),
                None,
            )
            if target is None:
                return False
            source_route_id = target.route_ids[0]
            source_role = RouteRole.CONFLICT_RESOLVER
            role_name = "conflict_resolver"
            excluded_authors = self._route_authors(registry, target.route_ids)
            related = [
                item.model_dump(mode="json")
                for item in graph.claim_nodes
                if item.message_id in target.message_ids
            ]
            bundle = prompts.resolve_contradiction(
                problem=problem,
                contradiction=target.model_dump(mode="json"),
                scoped_messages=related,
            )
        else:
            target_route_id = (
                self._route_for_strategy(state, strategy_id) if strategy_id else None
            )
            candidates = [
                item
                for item in graph.claim_nodes
                if target_route_id is None
                or item.source_route_id == target_route_id
                or target_route_id in item.target_route_ids
            ]
            target = next(
                (
                    item
                    for item in candidates
                    if item.evidence_type != EvidenceType.COUNTEREXAMPLE
                ),
                None,
            )
            if target is None:
                return False
            source_route_id = target.source_route_id
            source_role = RouteRole.COUNTEREXAMPLE_HUNTER
            role_name = "counterexample_hunter"
            excluded_authors = {target.source_agent_id}
            bundle = prompts.counterexample_search(
                problem=problem,
                exact_scoped_claim=target.model_dump(mode="json"),
                deterministic_replay_required=True,
            )

        assignment = role_runner.select(
            source_route_id,
            source_role,
            round_index=current_round,
            exclude=excluded_authors,
        )
        if assignment.agent_id is None:
            store.append_event(
                "route_role_unavailable",
                {
                    "route_id": source_route_id,
                    "role": source_role.value,
                    "reason": assignment.reason,
                },
            )
            return False
        agent = role_runner.runtime(assignment)
        store.append_event(
            "route_member_assigned",
            {
                "route_id": source_route_id,
                "agent_id": agent.id,
                "role": source_role.value,
                "round_index": current_round,
            },
        )
        result = await self._safe_call(
            runner,
            role_name,
            bundle,
            fixed_agent=agent,
            budget_bucket=("verification" if action != ActionKind.BRIDGE else "depth"),
        )
        if result is None:
            return False

        candidate = result.value
        if not isinstance(candidate, MessageEnvelope):
            return False
        if action == ActionKind.BRIDGE:
            expected_statement = target.normalized_goal
        elif action == ActionKind.RESOLVE_CONFLICT:
            expected_statement = target.normalized_statement
        else:
            expected_statement = target.normalized_statement
        if action != ActionKind.RESOLVE_CONFLICT and (
            self._normalize_statement(candidate.normalized_statement)
            != self._normalize_statement(expected_statement)
        ):
            store.append_event(
                "cross_route_task_rejected",
                {
                    "action": action.value,
                    "reason": "agent changed the exact scoped target",
                },
            )
            return False

        referee = role_runner.select(
            source_route_id,
            RouteRole.REFEREE,
            round_index=current_round,
            exclude={agent.id},
        )
        if referee.agent_id is None:
            return False
        referee_agent = role_runner.runtime(referee)
        review_result = await self._safe_call(
            runner,
            "route_referee",
            prompts.route_referee(
                problem=problem,
                proposed_message=candidate.model_dump(mode="json"),
                author_agent_id=agent.id,
                permitted_target=expected_statement,
            ),
            fixed_agent=referee_agent,
            budget_bucket="verification",
        )
        if review_result is None or not review_result.value.accepted:
            return False

        payload = candidate.model_dump(mode="json")
        message_type = MessageType.VERIFIED_LEMMA
        evidence_type = EvidenceType.NATURAL_PROOF_AUDITED
        memory_tier = MemoryTier.FACT
        verification_status = ClaimStatus.VERIFIED
        dependencies: list[str] = []
        target_routes: list[str] = []
        if action == ActionKind.BRIDGE:
            dependencies = list(target.allowed_fact_ids)
            target_routes = [
                route_id for route_id in target.route_ids if route_id != source_route_id
            ]
        elif action == ActionKind.RESOLVE_CONFLICT:
            allowed = {
                "a refutes b",
                "b refutes a",
                "same statement with different scopes",
                "both unsupported",
                "both compatible after variable/quantifier normalization",
                "requires external tool/formal check",
            }
            resolution = self._normalize_statement(candidate.conclusion)
            if resolution not in allowed:
                return False
            message_type = MessageType.CONTRADICTION_NOTICE
            memory_tier = MemoryTier.INSIGHT
            dependencies = list(target.message_ids)
            target_routes = [
                route_id for route_id in target.route_ids if route_id != source_route_id
            ]
        else:
            message_type = MessageType.COUNTEREXAMPLE
            evidence_type = EvidenceType.COUNTEREXAMPLE
            memory_tier = MemoryTier.NEGATIVE
            verification_status = ClaimStatus.REJECTED
            dependencies = [target.message_id]
            target_routes = sorted(
                set([target.source_route_id, *target.target_route_ids])
                - {source_route_id}
            )
        payload.update(
            {
                "problem_hash": problem.integrity_hash,
                "source_agent_id": agent.id,
                "source_route_id": source_route_id,
                "source_role": source_role.value,
                "target_route_ids": target_routes,
                "message_type": message_type.value,
                "normalized_statement": self._normalize_statement(expected_statement),
                "dependencies": dependencies,
                "evidence_type": evidence_type.value,
                "memory_tier": memory_tier.value,
                "verification_status": verification_status.value,
                "verification_confidence": max(
                    self.config.topology.typed_memory.fact_pass_threshold,
                    candidate.verification_confidence,
                ),
                "normalization_confidence": 1.0,
                "raw_source_ref": result.raw_ref,
                "round_created": current_round,
                "ttl_rounds": self.config.topology.cross_route.message_ttl_rounds,
                "content_hash": "",
            }
        )
        verified_message = MessageEnvelope.model_validate(payload)
        decision = broker.publish(
            verified_message,
            referee_agent_id=referee_agent.id,
            current_round=current_round,
        )
        if not decision.accepted:
            return False
        if verified_message.evidence_type == EvidenceType.COUNTEREXAMPLE:
            self._cool_routes_for_counterexample(
                state,
                verified_message,
                current_round=current_round,
                store=store,
            )
        if action == ActionKind.BRIDGE and state.bridge_broker is not None:
            state.bridge_broker.accept_verified_result(target.task_id, verified_message)
        elif (
            action == ActionKind.RESOLVE_CONFLICT
            and state.contradiction_broker is not None
        ):
            state.contradiction_broker.resolve(
                target.contradiction_id,
                resolution_message_id=verified_message.message_id,
            )
        return True

    def _cool_routes_for_counterexample(
        self,
        state: SolveState,
        message: MessageEnvelope,
        *,
        current_round: int,
        store: ArtifactStore,
    ) -> None:
        registry = state.route_registry
        if registry is None:
            return
        refuted_statements = {self._normalize_statement(message.conclusion)}
        refuted_statements.add(self._normalize_statement(message.normalized_statement))
        affected = set(message.target_route_ids) | {message.source_route_id}
        if state.typed_memory is not None:
            affected.update(
                state.typed_memory.affected_routes_for_counterexample(message)
            )
            refuted_statements.update(
                state.typed_memory.refuted_statements_for_counterexample(message)
            )
        for route in registry.routes:
            normalized_assumptions = [
                self._normalize_statement(item) for item in route.shared_assumptions
            ]
            if any(
                target == assumption or target in assumption or assumption in target
                for target in refuted_statements
                for assumption in normalized_assumptions
                if target and assumption
            ):
                affected.add(route.route_id)
        for route_id in sorted(affected):
            try:
                registry.mark_cooling(
                    route_id,
                    current_round + self.config.scheduler.failed_path_cooldown_rounds,
                    f"confirmed counterexample to shared premise: {message.statement}",
                    requires_revision=True,
                )
            except KeyError:
                continue
        store.append_event(
            "counterexample_route_cooldown",
            {
                "message_id": message.message_id,
                "affected_route_ids": sorted(affected),
                "requires_explicit_revision": True,
            },
        )

    @staticmethod
    def _route_authors(registry: RouteRegistry, route_ids: Iterable[str]) -> set[str]:
        return {
            member.agent_id
            for route_id in route_ids
            for member in registry.get(route_id).members
            if member.role == RouteRole.PROVER
        }

    def _restore_state_from_checkpoint(
        self,
        payload: dict[str, Any],
        store: ArtifactStore,
    ) -> SolveState:
        triage_raw = payload.get("triage")
        aggregate_raw = payload.get("aggregate_reports") or {}
        return SolveState(
            triage=TriageResult.model_validate(triage_raw) if triage_raw else None,
            strategies=[
                StrategyCard.model_validate(item)
                for item in payload.get("strategies", [])
            ],
            attempts=[
                ProofAttempt.model_validate(item)
                for item in payload.get("attempts", [])
            ],
            reports=[
                VerificationReport.model_validate(item)
                for item in payload.get("reports", [])
            ],
            aggregate_reports={
                str(key): VerificationReport.model_validate(value)
                for key, value in aggregate_raw.items()
            },
            meta_reviews=[
                MetaReview.model_validate(item)
                for item in payload.get("meta_reviews", [])
            ],
            checkpoints=store.list_proof_checkpoints(),
            resumed=bool(payload.get("resumed", False)),
            resumed_from_checkpoint_id=payload.get("resumed_from_checkpoint_id"),
            final_proof=(
                FinalProof.model_validate(payload["final_proof"])
                if payload.get("final_proof")
                else None
            ),
            final_verification=(
                VerificationReport.model_validate(payload["final_verification"])
                if payload.get("final_verification")
                else None
            ),
            budget_exhausted=bool(payload.get("budget_exhausted", False)),
            math_status=MathStatus(
                payload.get("math_status", MathStatus.INCONCLUSIVE.value)
            ),
            execution_status=ExecutionStatus(
                payload.get("execution_status", ExecutionStatus.COMPLETED.value)
            ),
            research_progress_report=(
                ResearchProgressReport.model_validate(
                    payload["research_progress_report"]
                )
                if payload.get("research_progress_report")
                else None
            ),
            current_round=int(payload.get("current_round", 0)),
            graph_frozen=bool(payload.get("graph_frozen", False)),
            proof_debt_history={
                str(key): [float(item) for item in value]
                for key, value in dict(payload.get("proof_debt_history", {})).items()
            },
            route_team_reviews={
                str(key): [dict(item) for item in value]
                for key, value in dict(payload.get("route_team_reviews", {})).items()
            },
            capability_domain=str(payload.get("capability_domain", "algebra")),
        )

    async def _triage(
        self,
        problem: ProblemContract,
        runner: StructuredAgentRunner,
        prompts: PromptFactory,
        store: ArtifactStore,
    ) -> TriageResult:
        result = await self._safe_call(
            runner,
            "planner",
            prompts.triage(problem),
            budget_bucket="breadth",
        )
        if result is not None:
            triage = result.value
            store.write_json("structured", "triage", triage)
            return triage
        triage = TriageResult(
            problem_kind=ProblemKind.UNKNOWN,
            difficulty=Difficulty.HARD,
            key_risks=[
                "The solver may change the statement or omit cases.",
                "A plausible key step may remain unproved.",
            ],
            likely_tools=self._allowed_tools(),
            suggested_paths=self.config.budget.initial_paths,
            suggested_rounds=self.config.budget.max_rounds,
            proof_mode="hybrid",
            rationale="Deterministic fallback triage after planner failure.",
            confidence=0.25,
        )
        store.write_json("structured", "triage_fallback", triage)
        return triage

    async def _initial_strategies(
        self,
        problem: ProblemContract,
        triage: TriageResult,
        runner: StructuredAgentRunner,
        prompts: PromptFactory,
        router: SparseTopologyRouter,
        store: ArtifactStore,
    ) -> list[StrategyCard]:
        requested = min(
            self.config.budget.strategies_to_generate,
            max(self.config.budget.initial_paths, triage.suggested_paths),
        )
        result = await self._safe_call(
            runner,
            "planner",
            prompts.strategies(problem, triage, requested),
            budget_bucket="breadth",
        )
        strategy_set = (
            result.value
            if result is not None
            else self._fallback_strategy_set(problem, requested)
        )
        target = self.config.budget.initial_paths
        candidates = self._deduplicate_strategy_cards(
            self._attach_planner_computation_hints(strategy_set.strategies)
        )
        selected = router.select_diverse_strategies(
            candidates, min(target, len(candidates))
        )
        if (
            result is not None
            and len(selected) < target
            and runner.ledger.remaining_calls > 0
        ):
            missing = target - len(selected)
            supplement = await self._safe_call(
                runner,
                "planner",
                prompts.strategies(
                    problem,
                    triage,
                    missing,
                    prior_strategy_titles=[item.title for item in selected],
                    regulator_feedback=[
                        "Return only mechanisms genuinely different from the listed routes; do not rename an existing idea."
                    ],
                ),
                budget_bucket="breadth",
            )
            if supplement is not None:
                candidates = self._deduplicate_strategy_cards(
                    [
                        *selected,
                        *self._attach_planner_computation_hints(
                            supplement.value.strategies
                        ),
                    ]
                )
                selected = router.select_diverse_strategies(
                    candidates, min(target, len(candidates))
                )
        if len(selected) < target:
            fallbacks = self._fallback_strategy_set(problem, target).strategies
            candidates = self._deduplicate_strategy_cards([*selected, *fallbacks])
            selected = router.select_diverse_strategies(
                candidates, min(target, len(candidates))
            )
        store.write_json("structured", "strategy_set", strategy_set)
        store.write_json("structured", "selected_strategies", selected)
        return selected

    def _attach_planner_computation_hints(
        self, strategies: Iterable[StrategyCard]
    ) -> list[StrategyCard]:
        """Turn explicit numerical-check language into inert, auditable hints."""
        simulation_markers = (
            "模拟",
            "枚举",
            "数值检验",
            "具体检验",
            "检验若干",
            "测试周期",
            "simulate",
            "enumerate",
            "numerically test",
            "test a period",
            "check a period",
        )
        broad_markers = ("寻找规律", "猜测规律", "find a pattern", "discover a pattern")
        enriched: list[StrategyCard] = []
        for strategy in strategies:
            text = " ".join(
                filter(
                    None,
                    [
                        strategy.title,
                        strategy.core_idea,
                        strategy.bottleneck,
                        strategy.falsification_test,
                        strategy.key_original_step or "",
                    ],
                )
            )
            folded = text.casefold()
            if strategy.computation_hints or not any(
                marker in folded for marker in simulation_markers
            ):
                enriched.append(strategy)
                continue
            if "周期" in folded or "period" in folded:
                method = ComputationMethod.CANDIDATE_PERIOD_CHECK
            elif any(marker in folded for marker in ("贪心", "greedy sequence")):
                method = ComputationMethod.BOUNDED_GREEDY_SEQUENCE
            else:
                method = ComputationMethod.BOUNDED_INTEGER_SEARCH
            broad = any(marker in folded for marker in broad_markers)
            target = strategy.falsification_test.strip() or (
                f"Check the finite numerical assertion used by strategy {strategy.strategy_id}."
            )
            hint = ComputationHint(
                purpose=(
                    ComputationPurpose.DISCOVER_PATTERN
                    if broad
                    else ComputationPurpose.FALSIFY_CLAIM
                ),
                target_claim=target,
                suggested_method=method,
                decision_use=(
                    "Reject or revise this route if a bounded exact check finds a counterexample; "
                    "otherwise retain only not_refuted evidence and continue the proof."
                ),
                broad_search=broad,
            )
            enriched.append(strategy.model_copy(update={"computation_hints": [hint]}))
        return enriched

    def _deduplicate_strategy_cards(
        self, strategies: Iterable[StrategyCard]
    ) -> list[StrategyCard]:
        result: list[StrategyCard] = []
        seen_ids: set[str] = set()
        seen_signatures: set[str] = set()
        threshold = self.config.topology.broker.duplicate_strategy_threshold
        token_sets: list[set[str]] = []
        for strategy in strategies:
            if strategy.strategy_id in seen_ids:
                continue
            signature = RouteRegistry.strategy_signature(strategy)
            tokens = RouteRegistry._tokens(
                " ".join(
                    [
                        strategy.title,
                        strategy.core_idea,
                        strategy.falsification_test,
                        *strategy.tags,
                        *strategy.prerequisites,
                    ]
                )
            )
            if signature in seen_signatures:
                continue
            if any(
                len(tokens & prior) / max(1, len(tokens | prior)) >= threshold
                for prior in token_sets
            ):
                continue
            seen_ids.add(strategy.strategy_id)
            seen_signatures.add(signature)
            token_sets.append(tokens)
            result.append(strategy)
        return result

    async def _parallel_initial_exploration(
        self,
        problem: ProblemContract,
        state: SolveState,
        assignments: list[tuple[StrategyCard, AgentRuntime]],
        runner: StructuredAgentRunner,
        prompts: PromptFactory,
        router: SparseTopologyRouter,
        memory: LemmaMemory,
        store: ArtifactStore,
        tools: ToolBroker,
    ) -> list[ProofAttempt]:
        async def one(strategy: StrategyCard, agent: AgentRuntime) -> ProofAttempt:
            return await self._explore_path(
                problem,
                strategy,
                agent,
                state=state,
                round_index=0,
                runner=runner,
                prompts=prompts,
                router=router,
                memory=memory,
                store=store,
                tools=tools,
                targeted_feedback=[],
                previous_attempt=None,
                budget_bucket="breadth",
            )

        results = await asyncio.gather(
            *(one(strategy, agent) for strategy, agent in assignments),
            return_exceptions=True,
        )
        self._raise_if_provider_circuit(results)
        attempts: list[ProofAttempt] = []
        for (strategy, agent), result in zip(assignments, results):
            if isinstance(result, Exception):
                store.append_event(
                    "exploration_failed",
                    {
                        "strategy_id": strategy.strategy_id,
                        "agent_id": agent.id,
                        "error": str(result),
                    },
                )
                attempts.append(
                    self._failed_attempt(problem, strategy, agent.id, 0, result)
                )
            else:
                attempts.append(result)
        return attempts

    async def _run_requested_computation(
        self,
        problem: ProblemContract,
        spec: ExperimentSpec,
        author: AgentRuntime,
        *,
        path_id: str,
        parent_checkpoint_id: str | None,
        stalled_rounds: int,
        meta_review_approved: bool,
        runner: StructuredAgentRunner,
        prompts: PromptFactory,
        tools: ToolBroker,
        budget_bucket: str,
    ) -> tuple[ComputationDecision, ExperimentResult | None]:
        # Provenance is authoritative and deliberately excluded from the semantic
        # request hash so the same mathematical request can be reused from cache.
        spec.requested_by = author.id
        spec.path_id = path_id
        spec.parent_checkpoint_id = parent_checkpoint_id
        context = ComputationContext(
            path_id=path_id,
            stalled_rounds=stalled_rounds,
            meta_review_approved=meta_review_approved,
            remaining_llm_calls=runner.ledger.remaining_calls,
        )
        decision = tools.decide(spec, context)
        if decision.decision != ComputationDecisionStatus.ALLOW:
            return decision, None

        program: ExperimentProgram | None = None
        if spec.method == ComputationMethod.SANDBOXED_PYTHON:
            try:
                code_agent = runner.pool.select("experimenter", exclude={author.id})
            except RuntimeError:
                try:
                    code_agent = runner.pool.select("planner", exclude={author.id})
                except RuntimeError:
                    code_agent = runner.pool.select("planner")
            code_result = await self._safe_call(
                runner,
                "experimenter"
                if "experimenter" in code_agent.config.roles
                else "planner",
                prompts.experiment_codegen(problem, spec, code_agent.id),
                fixed_agent=code_agent,
                budget_bucket=budget_bucket,
            )
            if code_result is not None:
                program = code_result.value
                program.experiment_id = spec.experiment_id

        result = tools.run_experiment(spec, decision, program=program)
        return decision, result

    @staticmethod
    def _experiment_context(
        tools: ToolBroker,
        path_id: str,
        *,
        parent_checkpoint_id: str | None = None,
        audit_records: Sequence[dict[str, Any]] | None = None,
    ) -> list[dict[str, Any]]:
        results = tools.results_for_path(path_id)
        if parent_checkpoint_id is not None:
            results = [
                result
                for result in results
                if result.parent_checkpoint_id == parent_checkpoint_id
            ]
        audits = {
            str(record.get("request_hash")): record for record in (audit_records or [])
        }
        context: list[dict[str, Any]] = []
        for result in results:
            payload = result.model_dump(mode="json")
            if result.request_hash in audits:
                payload["independent_replay_audit"] = audits[result.request_hash]
            context.append(payload)
        return context

    def _hierarchical_route_prompt_context(
        self,
        state: SolveState | None,
        *,
        strategy_id: str,
        current_round: int,
    ) -> tuple[str | None, list[MessageEnvelope], dict[str, Any]]:
        if self.config.topology.mode != "hierarchical_sparse":
            return None, [], {}
        if not self.config.topology.typed_communication.enabled:
            raise RuntimeError(
                "hierarchical_sparse route prompts require typed communication"
            )
        if (
            state is None
            or state.route_registry is None
            or state.message_broker is None
            or state.typed_memory is None
        ):
            raise RuntimeError(
                "hierarchical_sparse route prompt context is not initialized"
            )
        route_id = self._route_for_strategy(state, strategy_id)
        if route_id is None:
            raise RuntimeError(
                f"hierarchical_sparse route is missing for strategy {strategy_id}"
            )

        delivered, context = build_route_prompt_context(
            self.config,
            route_id=route_id,
            current_round=current_round,
            broker=state.message_broker,
            typed_memory=state.typed_memory,
            proof_graph=state.proof_graph,
        )
        return route_id, delivered, context

    @staticmethod
    def _acknowledge_route_messages(
        broker: MessageBroker,
        delivered: Sequence[MessageEnvelope],
        receipts: Sequence[MessageReceipt],
        *,
        route_id: str,
        current_round: int,
    ) -> list[MessageReceipt]:
        return acknowledge_route_messages(
            broker,
            delivered,
            receipts,
            route_id=route_id,
            current_round=current_round,
        )

    async def _run_active_route_team(
        self,
        state: SolveState | None,
        *,
        problem: ProblemContract,
        strategy: StrategyCard,
        checkpoint: ProofCheckpoint,
        delta: ProofDelta,
        attempt_id: str,
        author: AgentRuntime,
        round_index: int,
        experiment_results: Sequence[dict[str, Any]],
        route_context: dict[str, Any],
        runner: StructuredAgentRunner,
        prompts: PromptFactory,
        store: ArtifactStore,
        tools: ToolBroker,
    ) -> Any | None:
        if (
            state is None
            or state.route_registry is None
            or not self.config.topology.route_teams.enabled
            or self.config.topology.mode != "hierarchical_sparse"
        ):
            return None
        route_id = self._route_for_strategy(state, strategy.strategy_id)
        if route_id is None:
            return None

        team = RouteTeam(
            self.config,
            RoleRunner(runner.pool, state.route_registry),
        )
        risk_artifact = delta.model_dump(mode="json")
        if experiment_results:
            risk_artifact["evidence_type"] = EvidenceType.BOUNDED_EXPERIMENT.value
            risk_artifact["tool_requests"] = [
                item.get("request_hash") or item.get("experiment_id")
                for item in experiment_results
            ]
        plan = team.plan(
            route_id,
            author.id,
            risk_artifact,
            round_index=round_index,
            entering_global_fact_gate=bool(delta.new_claims or delta.proof_complete),
        )
        store.append_event(
            "route_team_started",
            {
                "route_id": route_id,
                "attempt_id": attempt_id,
                "delta_id": delta.delta_id,
                "prover_agent_id": author.id,
                "skeptic_agent_id": (
                    plan.skeptic.agent_id if plan.skeptic is not None else None
                ),
                "tool_agent_id": (
                    plan.tool_specialist.agent_id
                    if plan.tool_specialist is not None
                    else None
                ),
                "referee_agent_id": plan.referee.agent_id,
                "risk_score": plan.risk.score,
                "risk_reasons": list(plan.risk.reasons),
            },
        )

        async def skeptic_handler(assignment: Any, artifact: Any) -> Any:
            skeptic = team.role_runner.runtime(assignment)
            call = await self._safe_call(
                runner,
                "route_skeptic",
                prompts.route_skeptic(
                    problem=problem,
                    route_id=route_id,
                    strategy=strategy,
                    parent_checkpoint=checkpoint,
                    candidate_delta=artifact,
                    fact_inbox=route_context.get("fact_inbox", []),
                    negative_memory=route_context.get("negative_memory", []),
                    open_obligations=route_context.get("open_obligations", []),
                    repair_request=False,
                ),
                fixed_agent=skeptic,
                budget_bucket="verification",
            )
            if call is None:
                return None
            report: VerificationReport = call.value
            report.target_id = delta.delta_id
            report.target_type = "proof_delta"
            report.agent_id = skeptic.id
            report.stage = VerificationStage.DETAILED
            report.raw_artifact_ref = call.raw_ref
            report.usage = call.usage
            store.write_json(
                "structured",
                f"route_skeptic_{delta.delta_id}_{skeptic.id}",
                report,
            )
            store.append_event("route_skeptic_completed", report)
            return report

        async def tool_handler(assignment: Any, artifact: Any) -> Any:
            specialist = team.role_runner.runtime(assignment)
            expected_request_hashes = {
                str(item.get("request_hash"))
                for item in experiment_results
                if item.get("request_hash")
            }
            replay_audits = [
                item
                for item in tools.audit_key_results()
                if str(item.get("request_hash")) in expected_request_hashes
            ]
            call = await self._safe_call(
                runner,
                "tool_specialist",
                prompts.route_tool_audit(
                    problem=problem,
                    route_id=route_id,
                    strategy=strategy,
                    candidate_delta=artifact,
                    experiment_results=list(experiment_results),
                    deterministic_replay_audits=replay_audits,
                    authoritative_agent_id=specialist.id,
                ),
                fixed_agent=specialist,
                budget_bucket="verification",
            )
            if call is None:
                return None
            audit: ToolAuditReport = call.value
            replayed_hashes = {str(item.get("request_hash")) for item in replay_audits}
            reported_experiments_match = (
                set(audit.experiment_ids) == expected_request_hashes
            )
            deterministic_pass = (
                bool(expected_request_hashes)
                and replayed_hashes == expected_request_hashes
                and reported_experiments_match
                and all(bool(item.get("valid", False)) for item in replay_audits)
                and all(
                    item.get("outcome") != ExperimentOutcome.COUNTEREXAMPLE_FOUND.value
                    or bool(item.get("independently_verified"))
                    for item in experiment_results
                )
            )
            audit.agent_id = specialist.id
            audit.route_id = route_id
            audit.experiment_ids = sorted(expected_request_hashes)
            audit.all_results_replayed_independently = (
                audit.all_results_replayed_independently and deterministic_pass
            )
            if not audit.all_results_replayed_independently:
                audit.verdict = "fail"
                audit.issues = list(
                    dict.fromkeys(
                        [
                            *audit.issues,
                            "deterministic replay or authoritative experiment-ID gate did not pass",
                        ]
                    )
                )
            audit.replay_artifact_refs = list(
                dict.fromkeys(
                    [
                        *audit.replay_artifact_refs,
                        *[
                            str(item.get("artifact_ref"))
                            for item in replay_audits
                            if item.get("artifact_ref")
                        ],
                    ]
                )
            )
            store.write_json(
                "structured",
                f"route_tool_audit_{delta.delta_id}_{specialist.id}",
                audit,
            )
            store.append_event("route_tool_audit_completed", audit)
            return audit

        async def referee_handler(assignment: Any, sanitized: Any) -> Any:
            referee = team.role_runner.runtime(assignment)
            message_id = f"route_artifact_{delta.delta_id}"
            call = await self._safe_call(
                runner,
                "route_referee",
                prompts.route_referee(
                    problem=problem,
                    candidate_message_id=message_id,
                    route_id=route_id,
                    author_agent_id=author.id,
                    sanitized_artifact=sanitized,
                    required_fact_threshold=(
                        self.config.topology.typed_memory.fact_pass_threshold
                    ),
                ),
                fixed_agent=referee,
                budget_bucket="verification",
            )
            if call is None:
                return None
            decision: BrokerDecision = call.value
            decision.message_id = message_id
            store.write_json(
                "structured",
                f"route_referee_{delta.delta_id}_{referee.id}",
                decision,
            )
            store.append_event("route_referee_completed", decision)
            return decision

        result = await team.run(
            plan,
            delta,
            skeptic_handler=skeptic_handler,
            tool_handler=tool_handler,
            referee_handler=referee_handler,
        )
        reviewer_verdicts = []
        if isinstance(result.skeptic_result, VerificationReport):
            reviewer_verdicts.append(result.skeptic_result.verdict.value)
        if isinstance(result.referee_result, BrokerDecision):
            reviewer_verdicts.append(
                "pass" if result.referee_result.accepted else "fail"
            )
        referee_runtime = (
            team.role_runner.runtime(plan.referee)
            if plan.referee.agent_id is not None
            else None
        )
        assigned_agent_ids = {
            author.id,
            plan.skeptic.agent_id if plan.skeptic is not None else None,
            plan.tool_specialist.agent_id if plan.tool_specialist is not None else None,
            plan.referee.agent_id,
        }
        cross_provider_candidates = [
            candidate
            for candidate in runner.pool.agents
            if candidate.id not in assigned_agent_ids
            and candidate.provider != author.provider
            and candidate.supports_role("route_referee")
            and not candidate.in_cooldown
        ]
        existing_cross_provider_review = (
            referee_runtime is not None
            and referee_runtime.provider != author.provider
            and isinstance(result.referee_result, BrokerDecision)
        )
        escalation_plan = (
            state.validation_escalator
            or ValidationEscalator(self.config.topology.validation_escalation)
        ).plan(
            risk_score=plan.risk.score,
            reviewer_verdicts=reviewer_verdicts,
            cross_provider_available=(
                existing_cross_provider_review or bool(cross_provider_candidates)
            ),
            tool_or_formal_available=result.tool_result is not None,
            before_fact_promotion=bool(delta.new_claims or delta.proof_complete),
        )
        cross_provider_referee = (
            referee_runtime if existing_cross_provider_review else None
        )
        cross_provider_decision = (
            result.referee_result if existing_cross_provider_review else None
        )
        if (
            ValidationLevel.CROSS_PROVIDER in escalation_plan.levels
            and cross_provider_decision is None
            and cross_provider_candidates
        ):
            cross_provider_referee = max(
                cross_provider_candidates,
                key=lambda candidate: (
                    runner.pool.capability_score(candidate, "route_referee"),
                    candidate.trust_score,
                    candidate.id,
                ),
            )
            cross_call = await self._safe_call(
                runner,
                "route_referee",
                prompts.route_referee(
                    problem=problem,
                    candidate_message_id=f"route_artifact_{delta.delta_id}",
                    route_id=route_id,
                    author_agent_id=author.id,
                    sanitized_artifact={
                        "artifact": delta.model_dump(mode="json"),
                        "skeptic_result": result.skeptic_result,
                        "tool_result": result.tool_result,
                    },
                    required_fact_threshold=(
                        self.config.topology.typed_memory.fact_pass_threshold
                    ),
                    validation_level=ValidationLevel.CROSS_PROVIDER.value,
                ),
                fixed_agent=cross_provider_referee,
                budget_bucket="verification",
            )
            if cross_call is not None:
                cross_provider_decision = cross_call.value
                cross_provider_decision.message_id = f"route_artifact_{delta.delta_id}"
                store.write_json(
                    "structured",
                    f"route_cross_provider_referee_{delta.delta_id}_{cross_provider_referee.id}",
                    cross_provider_decision,
                )
                store.append_event(
                    "route_cross_provider_referee_completed",
                    {
                        "agent_id": cross_provider_referee.id,
                        "provider": cross_provider_referee.provider,
                        **cross_provider_decision.model_dump(mode="json"),
                    },
                )
        execution = await ValidationEscalationExecutor().execute(
            escalation_plan,
            {
                ValidationLevel.DETERMINISTIC: lambda: ValidationStepResult(
                    level=ValidationLevel.DETERMINISTIC,
                    executed=True,
                    passed=(
                        delta.problem_hash == problem.integrity_hash
                        and delta.parent_checkpoint_id == checkpoint.checkpoint_id
                        and delta.segment_index == checkpoint.segment_index + 1
                    ),
                    evidence_refs=[delta.delta_id],
                ),
                ValidationLevel.BLIND_SAME_MODEL: lambda: ValidationStepResult(
                    level=ValidationLevel.BLIND_SAME_MODEL,
                    executed=isinstance(result.skeptic_result, VerificationReport),
                    passed=(
                        isinstance(result.skeptic_result, VerificationReport)
                        and result.skeptic_result.verdict == VerificationVerdict.PASS
                        and result.skeptic_result.agent_id != author.id
                    ),
                    evidence_refs=(
                        [result.skeptic_result.raw_artifact_ref]
                        if isinstance(result.skeptic_result, VerificationReport)
                        and result.skeptic_result.raw_artifact_ref
                        else []
                    ),
                ),
                ValidationLevel.ADVERSARIAL_BLIND: lambda: ValidationStepResult(
                    level=ValidationLevel.ADVERSARIAL_BLIND,
                    executed=isinstance(result.referee_result, BrokerDecision),
                    passed=(
                        isinstance(result.referee_result, BrokerDecision)
                        and result.referee_result.accepted
                        and plan.referee.agent_id
                        not in {
                            author.id,
                            plan.skeptic.agent_id if plan.skeptic else None,
                            plan.tool_specialist.agent_id
                            if plan.tool_specialist
                            else None,
                        }
                    ),
                    evidence_refs=[f"route_referee:{delta.delta_id}"],
                ),
                ValidationLevel.CROSS_PROVIDER: lambda: ValidationStepResult(
                    level=ValidationLevel.CROSS_PROVIDER,
                    executed=(
                        cross_provider_referee is not None
                        and isinstance(cross_provider_decision, BrokerDecision)
                    ),
                    passed=(
                        cross_provider_referee is not None
                        and cross_provider_referee.provider != author.provider
                        and isinstance(cross_provider_decision, BrokerDecision)
                        and cross_provider_decision.accepted
                    ),
                    evidence_refs=[f"route_cross_provider_referee:{delta.delta_id}"],
                ),
                ValidationLevel.TOOL_OR_FORMAL: lambda: ValidationStepResult(
                    level=ValidationLevel.TOOL_OR_FORMAL,
                    executed=result.tool_result is not None,
                    passed=(
                        isinstance(result.tool_result, ToolAuditReport)
                        and result.tool_result.verdict == "pass"
                        and result.tool_result.mathematical_mapping_checked
                        and result.tool_result.all_results_replayed_independently
                    ),
                    evidence_refs=[
                        str(item.get("request_hash") or item.get("experiment_id"))
                        for item in experiment_results
                    ],
                ),
            },
        )
        result.global_share_allowed = (
            result.global_share_allowed and execution.fact_promotion_allowed
        )
        store.append_event(
            "validation_escalation_executed",
            {
                "target_id": delta.delta_id,
                **execution.model_dump(mode="json"),
            },
        )
        summary = {
            "route_id": route_id,
            "attempt_id": attempt_id,
            "delta_id": delta.delta_id,
            "prover_agent_id": author.id,
            "skeptic_agent_id": (
                plan.skeptic.agent_id if plan.skeptic is not None else None
            ),
            "tool_agent_id": (
                plan.tool_specialist.agent_id
                if plan.tool_specialist is not None
                else None
            ),
            "referee_agent_id": plan.referee.agent_id,
            "skeptic_verdict": (
                result.skeptic_result.verdict.value
                if isinstance(result.skeptic_result, VerificationReport)
                else None
            ),
            "referee_accepted": (
                result.referee_result.accepted
                if isinstance(result.referee_result, BrokerDecision)
                else False
            ),
            "global_share_allowed": result.global_share_allowed,
            "validation_passed": execution.passed,
            "validation_execution": execution.model_dump(mode="json"),
            "diagnostics": list(result.diagnostics),
        }
        if state.route_team_reviews is None:
            state.route_team_reviews = {}
        state.route_team_reviews.setdefault(attempt_id, []).append(summary)
        store.append_event("route_local_review_completed", summary)
        return result

    async def _explore_path(
        self,
        problem: ProblemContract,
        strategy: StrategyCard,
        agent: AgentRuntime,
        *,
        state: SolveState | None = None,
        round_index: int,
        runner: StructuredAgentRunner,
        prompts: PromptFactory,
        router: SparseTopologyRouter,
        memory: LemmaMemory,
        store: ArtifactStore,
        tools: ToolBroker,
        targeted_feedback: list[str],
        previous_attempt: ProofAttempt | None,
        budget_bucket: str,
        computation_meta_approved: bool = False,
        deep_exploration_meta_approved: bool = False,
        max_segments_this_call: int | None = None,
    ) -> ProofAttempt:
        if self.config.continuation.enabled:
            return await self._explore_path_segmented(
                problem,
                strategy,
                agent,
                state=state,
                round_index=round_index,
                runner=runner,
                prompts=prompts,
                router=router,
                memory=memory,
                store=store,
                tools=tools,
                targeted_feedback=targeted_feedback,
                previous_attempt=previous_attempt,
                budget_bucket=budget_bucket,
                computation_meta_approved=computation_meta_approved,
                deep_exploration_meta_approved=deep_exploration_meta_approved,
                max_segments_this_call=max_segments_this_call,
            )
        return await self._explore_path_legacy(
            problem,
            strategy,
            agent,
            round_index=round_index,
            runner=runner,
            prompts=prompts,
            router=router,
            memory=memory,
            store=store,
            tools=tools,
            targeted_feedback=targeted_feedback,
            previous_attempt=previous_attempt,
            budget_bucket=budget_bucket,
            computation_meta_approved=computation_meta_approved,
        )

    async def _explore_path_segmented(
        self,
        problem: ProblemContract,
        strategy: StrategyCard,
        agent: AgentRuntime,
        *,
        state: SolveState | None,
        round_index: int,
        runner: StructuredAgentRunner,
        prompts: PromptFactory,
        router: SparseTopologyRouter,
        memory: LemmaMemory,
        store: ArtifactStore,
        tools: ToolBroker,
        targeted_feedback: list[str],
        previous_attempt: ProofAttempt | None,
        budget_bucket: str,
        computation_meta_approved: bool,
        deep_exploration_meta_approved: bool = False,
        max_segments_this_call: int | None,
    ) -> ProofAttempt:
        cfg = self.config.continuation
        self._ensure_hierarchical_prover(
            state,
            strategy,
            agent,
            round_index=round_index,
            activity=runner.activity,
            store=store,
        )
        path_id = (
            previous_attempt.path_id
            if previous_attempt and previous_attempt.path_id
            else f"path_{strategy.strategy_id}"
        )
        checkpoint = store.load_latest_proof_checkpoint(path_id)
        if checkpoint is not None:
            if (
                checkpoint.problem_hash != problem.integrity_hash
                or checkpoint.strategy_id != strategy.strategy_id
            ):
                raise ValueError(
                    f"resume checkpoint {checkpoint.checkpoint_id} does not match the current problem/strategy"
                )
            resumed_from = checkpoint.checkpoint_id
            store.append_event(
                "proof_path_resumed",
                {
                    "path_id": path_id,
                    "strategy_id": strategy.strategy_id,
                    "checkpoint_id": checkpoint.checkpoint_id,
                    "segment_index": checkpoint.segment_index,
                },
            )
            if runner.activity is not None:
                runner.activity.info(
                    "proof_checkpoint_resume",
                    title=runner.activity.text(
                        "从最近已验证检查点继续",
                        "Continuing from the latest verified checkpoint",
                    ),
                    detail=runner.activity.text(
                        f"{strategy.title}：检查点 {checkpoint.checkpoint_id}，下一段从步骤 {checkpoint.segment_index + 1} 开始",
                        f"{strategy.title}: checkpoint {checkpoint.checkpoint_id}; next segment {checkpoint.segment_index + 1}",
                    ),
                    stage="proof_continuation",
                    agent_id=agent.id,
                    importance=ActivityImportance.MAJOR,
                    metrics={
                        "path_id": path_id,
                        "checkpoint_id": checkpoint.checkpoint_id,
                        "segment_index": checkpoint.segment_index,
                    },
                )
        else:
            checkpoint = make_genesis_checkpoint(
                problem, strategy, source_agent_id=agent.id
            )
            store.commit_proof_checkpoint(checkpoint)
            resumed_from = None

        attempt_id = new_id("attempt")
        if (
            checkpoint.proof_complete
            or checkpoint.segment_index >= cfg.max_segments_per_path
        ):
            return attempt_from_checkpoint(
                checkpoint,
                strategy,
                agent_id=checkpoint.source_agent_id or agent.id,
                round_index=round_index,
                previous_attempt=previous_attempt,
                attempt_id=attempt_id,
                resumed_from_checkpoint_id=resumed_from,
            )

        cumulative_usage = UsageRecord()
        latest_raw_ref: str | None = None
        verified_delta_claims: list[ClaimCard] = []
        failover_chain: list[str] = []

        segment_limit = cfg.segments_per_explore_call
        if max_segments_this_call is not None:
            segment_limit = min(segment_limit, max_segments_this_call)
        for _ in range(segment_limit):
            if (
                checkpoint.proof_complete
                or checkpoint.segment_index >= cfg.max_segments_per_path
            ):
                break
            relevant = (
                []
                if self.config.topology.mode == "hierarchical_sparse"
                else router.relevant_claims(memory.claims, strategy, targeted_feedback)
            )
            route = (
                state.route_registry.route_for_strategy(strategy.strategy_id)
                if state is not None and state.route_registry is not None
                else None
            )
            route_id = route.route_id if route is not None else None
            if self.config.topology.mode == "hierarchical_sparse" and route_id is None:
                raise RuntimeError(
                    "hierarchical_sparse route is missing for strategy "
                    f"{strategy.strategy_id}"
                )
            proof_debt_before = (
                state.proof_graph.proof_debt(route_id)
                if state is not None
                and state.proof_graph is not None
                and route_id is not None
                else None
            )
            next_segment = checkpoint.segment_index + 1
            current_goal_before_segment = checkpoint.current_goal
            prior_working = store.load_latest_working_checkpoint(path_id)
            if (
                prior_working is not None
                and prior_working.parent_verified_checkpoint_id
                != checkpoint.checkpoint_id
            ):
                prior_working = None
            deep_admission, deep_signature = self._admit_deep_exploration(
                state,
                problem=problem,
                strategy=strategy,
                checkpoint=checkpoint,
                route_id=route_id,
                round_index=round_index,
                meta_approved=deep_exploration_meta_approved,
                remaining_calls=runner.ledger.remaining_calls,
                remaining_tokens=(
                    None
                    if self.config.budget.max_total_tokens is None
                    else max(
                        0,
                        self.config.budget.max_total_tokens
                        - runner.pool.total_tokens(),
                    )
                ),
                store=store,
            )
            if deep_admission is not None and not deep_admission.allowed:
                feedback = (
                    "Deep exploration admission deferred this exact mathematical "
                    f"state: {deep_admission.reason}. Use a verified checkpoint or a "
                    "referee-confirmed different mechanism before requesting another "
                    "high-token attempt."
                )
                targeted_feedback = [*targeted_feedback, feedback]
                store.append_event(
                    "deep_exploration_admission_deferred",
                    {
                        **deep_admission.model_dump(mode="json"),
                        "route_id": route_id,
                        "checkpoint_id": checkpoint.checkpoint_id,
                    },
                )
                if route is not None:
                    route.stagnation_rounds = max(
                        route.stagnation_rounds,
                        self.config.topology.inspiration.stagnation_rounds,
                    )
                break
            if deep_admission is not None:
                store.append_event(
                    "deep_exploration_admitted",
                    {
                        **deep_admission.model_dump(mode="json"),
                        "route_id": route_id,
                        "checkpoint_id": checkpoint.checkpoint_id,
                        "target_statement": (
                            deep_signature.target_statement
                            if deep_signature is not None
                            else ""
                        ),
                    },
                )
                if runner.activity is not None:
                    runner.activity.info(
                        "deep_exploration_admitted",
                        title="Deep exploration tier admitted",
                        detail=(
                            f"{strategy.title}: {deep_admission.max_output_tokens:,} "
                            f"tokens; {deep_admission.reason}"
                        ),
                        stage="route_prove",
                        agent_id=agent.id,
                        importance=ActivityImportance.NORMAL,
                        metrics={
                            "route_id": route_id,
                            "signature_hash": deep_admission.signature_hash,
                            "requested_tier": deep_admission.requested_tier,
                            "granted_tier": deep_admission.granted_tier,
                            "max_output_tokens": deep_admission.max_output_tokens,
                            "parallel_distinct_signatures_allowed": True,
                        },
                    )
                if deep_admission.novelty_review_required and route is not None:
                    route.stagnation_rounds = max(
                        route.stagnation_rounds,
                        self.config.topology.inspiration.stagnation_rounds,
                    )
                    store.append_event(
                        "deep_exploration_novelty_review_requested",
                        {
                            "route_id": route.route_id,
                            "signature_hash": deep_admission.signature_hash,
                            "temporary_max_output_tokens": (
                                deep_admission.max_output_tokens
                            ),
                            "reason": deep_admission.reason,
                        },
                    )
            route_id, delivered_messages, route_context = (
                self._hierarchical_route_prompt_context(
                    state,
                    strategy_id=strategy.strategy_id,
                    current_round=round_index,
                )
            )
            experiment_results: list[dict[str, Any]] = []
            computation_feedback: list[dict[str, Any]] = []
            compute_cycles = 0
            confirmed_counterexample_pending = False
            delta: ProofDelta | None = None
            result: StructuredCallResult[Any] | None = None
            tried_agents: list[str] = []
            receipts_processed = False
            acknowledged_receipts: list[MessageReceipt] = []
            segment_usage = UsageRecord()
            deep_outcome: ExplorationOutcome | None = None
            deep_outcome_reason = ""

            while True:

                def bundle_factory(current_agent: AgentRuntime) -> PromptBundle:
                    if route_id is not None:
                        bundle = prompts.route_prove(
                            problem,
                            authorized_output_tier=(
                                deep_admission.granted_tier
                                if deep_admission is not None
                                and deep_admission.granted_tier is not None
                                else 0
                            ),
                            strategy=strategy,
                            checkpoint=checkpoint,
                            previous_working_checkpoint=prior_working,
                            agent_id=current_agent.id,
                            # In hierarchical mode all cross-route knowledge must
                            # pass through Broker + TypedMemory. LemmaMemory is a
                            # legacy verifier/migration store, never a route inbox.
                            verified_legacy_claims=[],
                            targeted_feedback=targeted_feedback,
                            experiment_results=experiment_results,
                            computation_feedback=computation_feedback,
                            continuation_limits={
                                "checkpoint_policy": cfg.checkpoint_policy,
                                "max_new_steps": cfg.max_new_steps_per_call,
                                "max_new_claims": cfg.max_new_claims_per_call,
                                "max_compute_cycles": self.config.computation.max_compute_cycles_per_segment,
                            },
                            authoritative_ids={
                                "problem_hash": problem.integrity_hash,
                                "path_id": checkpoint.path_id,
                                "strategy_id": strategy.strategy_id,
                                "parent_checkpoint_id": checkpoint.checkpoint_id,
                                "agent_id": current_agent.id,
                                "round_index": round_index,
                                "segment_index": next_segment,
                            },
                            remaining_call_budget=runner.ledger.remaining_calls,
                            **route_context,
                        )
                    else:
                        if self.config.topology.mode == "hierarchical_sparse":
                            raise RuntimeError(
                                "hierarchical_sparse cannot fall back to the legacy "
                                "proof_continuation prompt"
                            )
                        bundle = prompts.continue_proof(
                            problem,
                            strategy.model_dump(mode="json"),
                            checkpoint,
                            current_agent.id,
                            round_index,
                            next_segment,
                            [claim.model_dump(mode="json") for claim in relevant],
                            targeted_feedback,
                            max_new_steps=cfg.max_new_steps_per_call,
                            max_new_claims=cfg.max_new_claims_per_call,
                            checkpoint_policy=cfg.checkpoint_policy,
                            remaining_call_budget=runner.ledger.remaining_calls,
                            experiment_results=experiment_results,
                            computation_feedback=computation_feedback,
                        )
                    return PromptBundle(
                        bundle.stage,
                        bundle.system,
                        bundle.user,
                        bundle.response_model,
                        temperature=bundle.temperature,
                        max_output_tokens=min(
                            cfg.max_output_tokens_per_segment,
                            current_agent.config.max_output_tokens,
                            (
                                deep_admission.max_output_tokens
                                if deep_admission is not None
                                and deep_admission.max_output_tokens is not None
                                else cfg.max_output_tokens_per_segment
                            ),
                        ),
                        output_tier=bundle.output_tier,
                    )

                try:
                    result, just_tried = await runner.call_with_failover(
                        "explorer",
                        bundle_factory,
                        primary_agent=agent,
                        specialty_hints=strategy.tags,
                        budget_bucket=budget_bucket,
                        max_failover_agents=cfg.max_failover_agents,
                        allow_failover=cfg.resume_on_disconnect
                        and cfg.allow_cross_agent_failover,
                        failover_only_on_retryable=True,
                    )
                except (BudgetExhaustedError, ProviderCircuitOpenError) as exc:
                    self._finish_deep_exploration(
                        state,
                        deep_admission,
                        outcome=ExplorationOutcome.EXTERNAL_FAILURE,
                        usage=segment_usage,
                        checkpoint_after=checkpoint,
                        proof_debt_before=proof_debt_before,
                        current_goal_before=current_goal_before_segment,
                        reason=type(exc).__name__,
                        store=store,
                    )
                    raise
                except Exception as exc:
                    failed_usage = getattr(exc, "usage", None)
                    if isinstance(failed_usage, UsageRecord):
                        cumulative_usage = self._sum_usage(
                            [cumulative_usage, failed_usage]
                        )
                        segment_usage = self._sum_usage([segment_usage, failed_usage])
                    if classify_no_artifact_failure(exc) is not None:
                        deep_outcome = ExplorationOutcome.NO_ARTIFACT
                        deep_outcome_reason = (
                            "the route call returned no usable structured artifact"
                        )
                    else:
                        deep_outcome = ExplorationOutcome.EXTERNAL_FAILURE
                        deep_outcome_reason = type(exc).__name__
                    store.append_event(
                        "proof_continuation_failed",
                        {
                            "path_id": path_id,
                            "checkpoint_id": checkpoint.checkpoint_id,
                            "segment_index": next_segment,
                            "error_type": type(exc).__name__,
                            "error": str(exc),
                        },
                    )
                    failed_agents = list(getattr(exc, "tried_agents", []) or [])
                    failover_chain = self._deduplicate_strings(
                        [*failover_chain, *failed_agents]
                    )
                    bottleneck = await PostFailureBottleneckExtractor(
                        self.config,
                        runner,
                        prompts,
                        store,
                    ).extract(
                        exc,
                        problem=problem,
                        strategy=strategy,
                        checkpoint=checkpoint,
                        route_id=route_id,
                        previous_working_checkpoint=prior_working,
                        typed_public_context=route_context,
                        has_candidate=bool(
                            checkpoint.verified_steps
                            or checkpoint.final_answer
                            or (state is not None and state.attempts)
                        ),
                    )
                    if bottleneck is not None:
                        cumulative_usage = self._sum_usage(
                            [cumulative_usage, bottleneck.diagnostic.usage]
                        )
                        self._materialize_post_failure_bottleneck(
                            state, bottleneck.diagnostic
                        )
                        alternatives = ", ".join(
                            bottleneck.diagnostic.alternative_mechanism_tags
                        )
                        targeted_feedback = [
                            *targeted_feedback,
                            (
                                "Route-local post-failure bottleneck, diagnosed only "
                                "from public checkpoint state: "
                                f"{bottleneck.diagnostic.smallest_blocked_claim}. "
                                "The failed call's private reasoning was not recovered. "
                                f"Alternative mechanisms: {alternatives}."
                            ),
                        ]
                    else:
                        targeted_feedback = [
                            *targeted_feedback,
                            f"Continuation call failed after retry/failover: {type(exc).__name__}. Resume from checkpoint {checkpoint.checkpoint_id}.",
                        ]
                    break

                latest_raw_ref = result.raw_ref
                cumulative_usage = self._sum_usage([cumulative_usage, result.usage])
                segment_usage = self._sum_usage([segment_usage, result.usage])
                tried_agents = self._deduplicate_strings([*tried_agents, *just_tried])
                failover_chain = self._deduplicate_strings(
                    [*failover_chain, *just_tried]
                )
                agent = result.agent
                turn = (
                    result.value
                    if isinstance(result.value, ContinuationTurn)
                    else ContinuationTurn(
                        action=(
                            ContinuationAction.COMPLETE
                            if result.value.proof_complete
                            else ContinuationAction.SUBMIT_DELTA
                        ),
                        delta=result.value,
                    )
                )
                if (
                    delivered_messages
                    and route_id is not None
                    and state is not None
                    and state.message_broker is not None
                    and not receipts_processed
                ):
                    acknowledged_receipts = self._acknowledge_route_messages(
                        state.message_broker,
                        delivered_messages,
                        turn.message_receipts,
                        route_id=route_id,
                        current_round=round_index,
                    )
                    receipts_processed = True
                    if self.config.topology.typed_communication.require_receipt and any(
                        receipt.status != ReceiptStatus.ACCEPTED
                        for receipt in acknowledged_receipts
                    ):
                        targeted_feedback = [
                            *targeted_feedback,
                            "A cross-route message was not acknowledged with an exact semantic receipt; no checkpoint was advanced.",
                        ]
                        break
                if (
                    confirmed_counterexample_pending
                    and turn.action != ContinuationAction.REQUEST_COMPUTATION
                    and turn.experiment_impact is None
                ):
                    targeted_feedback = [
                        *targeted_feedback,
                        "A confirmed counterexample must be acknowledged by classifying its impact as execution, plan, or strategy before this path can advance.",
                    ]
                    break
                if turn.experiment_impact is not None:
                    store.append_event(
                        "experiment_impact_classified",
                        {
                            "path_id": path_id,
                            "strategy_id": strategy.strategy_id,
                            "failure_level": turn.experiment_impact,
                            "reason": turn.reason,
                        },
                    )
                if turn.action in {
                    ContinuationAction.SUBMIT_DELTA,
                    ContinuationAction.COMPLETE,
                }:
                    delta = turn.delta
                    break
                if turn.action == ContinuationAction.ABANDON:
                    deep_outcome = ExplorationOutcome.NO_VERIFIED_PROGRESS
                    deep_outcome_reason = turn.reason or "route abandoned"
                    targeted_feedback = [
                        *targeted_feedback,
                        turn.reason or "Explorer abandoned this continuation segment.",
                    ]
                    break
                if (
                    compute_cycles
                    >= self.config.computation.max_compute_cycles_per_segment
                ):
                    targeted_feedback = [
                        *targeted_feedback,
                        "Per-segment computation cycle limit reached; no checkpoint was advanced.",
                    ]
                    break
                assert turn.experiment_spec is not None
                decision, experiment = await self._run_requested_computation(
                    problem,
                    turn.experiment_spec,
                    agent,
                    path_id=path_id,
                    parent_checkpoint_id=checkpoint.checkpoint_id,
                    stalled_rounds=round_index,
                    meta_review_approved=computation_meta_approved,
                    runner=runner,
                    prompts=prompts,
                    tools=tools,
                    budget_bucket=budget_bucket,
                )
                compute_cycles += 1
                computation_feedback.append(decision.model_dump(mode="json"))
                if experiment is not None:
                    experiment_results.append(experiment.model_dump(mode="json"))
                    confirmed_counterexample_pending = (
                        confirmed_counterexample_pending
                        or (
                            experiment.outcome == ExperimentOutcome.COUNTEREXAMPLE_FOUND
                            and experiment.evidence_strength
                            == EvidenceStrength.COUNTEREXAMPLE
                            and experiment.independently_verified
                        )
                    )
                    if confirmed_counterexample_pending:
                        deep_outcome = ExplorationOutcome.USEFUL_COUNTEREXAMPLE
                        deep_outcome_reason = (
                            "an independently checked counterexample changed the route"
                        )

            if delta is None or result is None:
                self._finish_deep_exploration(
                    state,
                    deep_admission,
                    outcome=(
                        deep_outcome
                        or (
                            ExplorationOutcome.USEFUL_COUNTEREXAMPLE
                            if confirmed_counterexample_pending
                            else ExplorationOutcome.NO_VERIFIED_PROGRESS
                        )
                    ),
                    usage=segment_usage,
                    checkpoint_after=checkpoint,
                    proof_debt_before=proof_debt_before,
                    current_goal_before=current_goal_before_segment,
                    reason=deep_outcome_reason
                    or "no checkpoint-eligible delta returned",
                    store=store,
                )
                break
            delta.problem_hash = problem.integrity_hash
            delta.path_id = checkpoint.path_id
            delta.strategy_id = strategy.strategy_id
            delta.parent_checkpoint_id = checkpoint.checkpoint_id
            delta.agent_id = result.agent.id
            delta.round_index = round_index
            delta.segment_index = next_segment
            delta.raw_artifact_ref = result.raw_ref
            delta.usage = result.usage
            store.save_proof_delta(delta.delta_id, delta)
            working_checkpoint = WorkingProofCheckpoint(
                parent_verified_checkpoint_id=checkpoint.checkpoint_id,
                problem_hash=problem.integrity_hash,
                path_id=checkpoint.path_id,
                strategy_id=strategy.strategy_id,
                source_agent_id=result.agent.id,
                segment_index=next_segment,
                delta=delta,
            )
            store.save_working_checkpoint(working_checkpoint)

            local_report = local_delta_verification(problem, checkpoint, delta)
            policy_issues: list[VerificationIssue] = []
            if len(delta.new_steps) > cfg.max_new_steps_per_call:
                policy_issues.append(
                    VerificationIssue(
                        phase="checkpoint_integrity",
                        severity=Severity.ERROR,
                        description=(
                            f"Delta returned {len(delta.new_steps)} steps, exceeding the configured "
                            f"limit {cfg.max_new_steps_per_call}."
                        ),
                    )
                )
            if len(delta.new_claims) > cfg.max_new_claims_per_call:
                policy_issues.append(
                    VerificationIssue(
                        phase="checkpoint_integrity",
                        severity=Severity.ERROR,
                        description=(
                            f"Delta returned {len(delta.new_claims)} claims, exceeding the configured "
                            f"limit {cfg.max_new_claims_per_call}."
                        ),
                    )
                )
            if (
                cfg.checkpoint_policy == "verified_subgoal"
                and not delta.completed_subgoal
                and not delta.proof_complete
                and not delta.detected_conflicts
            ):
                policy_issues.append(
                    VerificationIssue(
                        phase="checkpoint_policy",
                        severity=Severity.ERROR,
                        description=(
                            "The verified_subgoal policy requires completed_subgoal before "
                            "advancing the persistent checkpoint."
                        ),
                        repair_hint=(
                            "Complete one coherent subgoal, or use checkpoint_policy=verified_delta "
                            "when smaller verified increments are intended."
                        ),
                    )
                )
            if policy_issues:
                # VerificationReport validates assignments. Add the concrete issues
                # before changing the verdict so the model invariant is never broken.
                local_report.issues.extend(policy_issues)
                local_report.failure_level = FailureLevel.EXECUTION
                local_report.concise_feedback = policy_issues[0].description
                local_report.verdict = VerificationVerdict.FAIL
            store.write_json(
                "structured",
                f"checkpoint_local_verification_{delta.delta_id}",
                local_report,
            )

            reports = [local_report]
            if local_report.verdict == VerificationVerdict.PASS:
                team_result = await self._run_active_route_team(
                    state,
                    problem=problem,
                    strategy=strategy,
                    checkpoint=checkpoint,
                    delta=delta,
                    attempt_id=attempt_id,
                    author=result.agent,
                    round_index=round_index,
                    experiment_results=experiment_results,
                    route_context=route_context,
                    runner=runner,
                    prompts=prompts,
                    store=store,
                    tools=tools,
                )
                if team_result is not None and isinstance(
                    team_result.skeptic_result, VerificationReport
                ):
                    reports.append(team_result.skeptic_result)
            if (
                local_report.verdict == VerificationVerdict.PASS
                and cfg.verify_each_delta
            ):
                reports.extend(
                    await self._verify_proof_delta(
                        problem,
                        strategy,
                        checkpoint,
                        delta,
                        result.agent,
                        runner,
                        prompts,
                        memory,
                        tools,
                        store,
                        state=state,
                    )
                )

            independent = [
                report
                for report in reports
                if report.agent_id != "local-integrity-guard"
            ]
            accepted = local_report.verdict == VerificationVerdict.PASS
            if cfg.verify_each_delta:
                accepted = (
                    accepted
                    and bool(independent)
                    and all(
                        report.verdict == VerificationVerdict.PASS
                        for report in independent
                    )
                    and min(report.confidence for report in independent)
                    >= cfg.checkpoint_pass_threshold
                )

            if not accepted:
                working_checkpoint.status = (
                    "rejected"
                    if any(
                        report.verdict == VerificationVerdict.FAIL for report in reports
                    )
                    else "uncertain"
                )
                working_checkpoint.verification_report_ids = [
                    report.report_id for report in reports
                ]
                if cfg.retain_rejected_deltas:
                    store.save_proof_delta(delta.delta_id, delta, rejected=True)
                feedback = next(
                    (
                        report.concise_feedback
                        for report in reports
                        if report.verdict != VerificationVerdict.PASS
                    ),
                    "The candidate delta did not pass checkpoint verification.",
                )
                working_checkpoint.feedback = [feedback]
                store.save_working_checkpoint(working_checkpoint)
                store.append_event(
                    "proof_checkpoint_rejected",
                    {
                        "delta_id": delta.delta_id,
                        "checkpoint_id": checkpoint.checkpoint_id,
                        "path_id": checkpoint.path_id,
                        "feedback": feedback,
                    },
                )
                if runner.activity is not None:
                    runner.activity.emit(
                        "proof_checkpoint_rejected",
                        status=ActivityStatus.WARNING,
                        title=runner.activity.text(
                            "候选证明段未通过检查点验证",
                            "Candidate proof segment failed checkpoint verification",
                        ),
                        detail=feedback,
                        stage="checkpoint_verification",
                        agent_id=delta.agent_id,
                        importance=ActivityImportance.NORMAL,
                        metrics={
                            "delta_id": delta.delta_id,
                            "path_id": checkpoint.path_id,
                        },
                    )
                targeted_feedback = [*targeted_feedback, feedback]
                current_goal_changed = bool(
                    delta.current_goal
                    and delta.current_goal.strip()
                    and delta.current_goal.strip()
                    != (checkpoint.current_goal or "").strip()
                )
                proof_debt_after_candidate = (
                    state.proof_graph.proof_debt(route_id)
                    if state is not None
                    and state.proof_graph is not None
                    and route_id is not None
                    else None
                )
                proof_debt_changed = bool(
                    proof_debt_before is not None
                    and proof_debt_after_candidate is not None
                    and abs(proof_debt_before - proof_debt_after_candidate) > 1e-9
                )
                partial_is_usable = (
                    current_goal_changed
                    or proof_debt_changed
                    or bool(delta.detected_conflicts)
                ) and all(
                    report.verdict != VerificationVerdict.FAIL for report in reports
                )
                self._finish_deep_exploration(
                    state,
                    deep_admission,
                    outcome=(
                        ExplorationOutcome.USABLE_PARTIAL
                        if partial_is_usable
                        else ExplorationOutcome.NO_VERIFIED_PROGRESS
                    ),
                    usage=segment_usage,
                    checkpoint_after=checkpoint,
                    proof_debt_before=proof_debt_before,
                    current_goal_before=current_goal_before_segment,
                    current_goal_override=delta.current_goal,
                    reason=feedback,
                    store=store,
                )
                break

            claims = normalize_delta_claims(
                delta,
                attempt_id=attempt_id,
                raw_ref=result.raw_ref,
            )
            for claim in claims:
                if independent:
                    claim.verification_confidence = min(
                        report.confidence for report in independent
                    )
            memory.add_many(claims)
            verified_delta_claims.extend(claims)
            checkpoint = merge_verified_delta(
                checkpoint,
                delta,
                reports,
                failover_chain=tried_agents,
            )
            store.commit_proof_checkpoint(checkpoint)
            self._finish_deep_exploration(
                state,
                deep_admission,
                outcome=(
                    ExplorationOutcome.VERIFIED_MECHANISM_CHANGE
                    if deep_signature is not None
                    and self._is_referee_confirmed_inspiration(state, strategy)
                    else ExplorationOutcome.VERIFIED_PROGRESS
                ),
                usage=segment_usage,
                checkpoint_after=checkpoint,
                proof_debt_before=proof_debt_before,
                current_goal_before=current_goal_before_segment,
                reason="the proof delta passed checkpoint verification",
                store=store,
            )
            if (
                route_id is not None
                and state is not None
                and state.message_broker is not None
                and acknowledged_receipts
            ):
                record_verified_message_usage(
                    state.message_broker,
                    delivered_messages,
                    acknowledged_receipts,
                    delta,
                    route_id=route_id,
                    proof_graph=state.proof_graph,
                    proof_debt_before=proof_debt_before,
                )
            store.write_json(
                "structured",
                f"proof_checkpoint_{checkpoint.checkpoint_id}",
                checkpoint,
            )
            if runner.activity is not None:
                runner.activity.info(
                    "proof_checkpoint_committed",
                    title=runner.activity.text(
                        "已提交新的证明检查点",
                        "A new proof checkpoint was committed",
                    ),
                    detail=runner.activity.text(
                        f"{strategy.title}：已验证至第 {checkpoint.segment_index} 段，共 {len(checkpoint.verified_steps)} 个步骤",
                        f"{strategy.title}: verified through segment {checkpoint.segment_index}, {len(checkpoint.verified_steps)} steps total",
                    ),
                    stage="checkpoint_verification",
                    agent_id=delta.agent_id,
                    importance=ActivityImportance.MAJOR,
                    metrics={
                        "checkpoint_id": checkpoint.checkpoint_id,
                        "path_id": checkpoint.path_id,
                        "segment_index": checkpoint.segment_index,
                        "proof_complete": checkpoint.proof_complete,
                    },
                )

        attempt = attempt_from_checkpoint(
            checkpoint,
            strategy,
            agent_id=checkpoint.source_agent_id or agent.id,
            round_index=round_index,
            previous_attempt=previous_attempt,
            attempt_id=attempt_id,
            proposed_lemmas=verified_delta_claims,
            raw_artifact_ref=latest_raw_ref,
            usage=cumulative_usage,
            resumed_from_checkpoint_id=resumed_from,
            failover_chain=failover_chain,
        )
        if not checkpoint.proof_complete and targeted_feedback:
            attempt.unresolved_gaps = self._deduplicate_strings(
                [*attempt.unresolved_gaps, *targeted_feedback]
            )
        store.write_json("structured", f"attempt_{attempt.attempt_id}", attempt)
        store.append_event("attempt_completed", attempt)
        return attempt

    async def _verify_proof_delta(
        self,
        problem: ProblemContract,
        strategy: StrategyCard,
        checkpoint: ProofCheckpoint,
        delta: ProofDelta,
        author: AgentRuntime,
        runner: StructuredAgentRunner,
        prompts: PromptFactory,
        memory: LemmaMemory,
        tools: ToolBroker,
        store: ArtifactStore,
        *,
        state: SolveState | None = None,
    ) -> list[VerificationReport]:
        reports: list[VerificationReport] = []
        experiment_audit = tools.audit_key_results(
            path_id=checkpoint.path_id,
            report_name=f"checkpoint_experiment_audit_{delta.delta_id}",
        )
        auditable_experiments = self._experiment_context(
            tools,
            checkpoint.path_id,
            audit_records=experiment_audit,
        )
        verification_query = json.dumps(
            {
                "checkpoint": checkpoint.model_dump(mode="json"),
                "delta": delta.model_dump(mode="json"),
            },
            ensure_ascii=False,
            sort_keys=True,
        )
        fact_context = self._admissible_fact_context(
            state=state,
            legacy_memory=memory,
            query=verification_query,
            max_chars=max(2000, self.config.topology.max_context_chars // 4),
            purpose=ContextPurpose.DELTA_VERIFICATION,
            required_refs=explicit_dependency_refs(
                {
                    "checkpoint": checkpoint.model_dump(mode="json"),
                    "delta": delta.model_dump(mode="json"),
                }
            ),
        )
        excluded = {author.id}
        replicas = self.config.continuation.delta_verifier_replicas
        for _ in range(replicas):
            try:
                verifier = runner.pool.select(
                    "detailed_verifier",
                    exclude=excluded,
                    specialty_hints=strategy.tags,
                    prefer_provider_not=author.provider,
                )
            except RuntimeError:
                break

            def bundle_factory(current_agent: AgentRuntime) -> PromptBundle:
                return prompts.verify_delta(
                    problem,
                    strategy.model_dump(mode="json"),
                    checkpoint,
                    delta,
                    current_agent.id,
                    fact_context,
                    auditable_experiments,
                )

            try:
                result, tried = await runner.call_with_failover(
                    "detailed_verifier",
                    bundle_factory,
                    primary_agent=verifier,
                    specialty_hints=strategy.tags,
                    budget_bucket="verification",
                    max_failover_agents=self.config.continuation.max_failover_agents,
                    allow_failover=self.config.continuation.allow_cross_agent_failover,
                    failover_only_on_retryable=True,
                    exclude_agent_ids={author.id},
                )
            except (BudgetExhaustedError, ProviderCircuitOpenError):
                raise
            except Exception as exc:
                store.append_event(
                    "checkpoint_verifier_failed",
                    {
                        "delta_id": delta.delta_id,
                        "verifier_id": verifier.id,
                        "error_type": type(exc).__name__,
                        "error": str(exc),
                    },
                )
                continue

            if result.agent.id == author.id:
                store.append_event(
                    "checkpoint_verifier_not_independent",
                    {
                        "delta_id": delta.delta_id,
                        "author_id": author.id,
                        "verifier_id": result.agent.id,
                    },
                )
                continue

            report: VerificationReport = result.value
            report.target_id = delta.delta_id
            report.target_type = "proof_delta"
            report.agent_id = result.agent.id
            report.stage = VerificationStage.DETAILED
            if delta.problem_hash != problem.integrity_hash:
                report.problem_integrity_ok = False
                report.issues.append(
                    VerificationIssue(
                        phase="problem_integrity_guard",
                        severity=Severity.CRITICAL,
                        description=(
                            "Proof delta problem hash does not match the immutable problem."
                        ),
                        repair_hint="Regenerate the delta for the exact original problem.",
                    )
                )
                report.verdict = VerificationVerdict.FAIL
            report.raw_artifact_ref = result.raw_ref
            report.usage = result.usage
            if report.tool_requests:
                tool_results = tools.execute_many(report.tool_requests)
                report.tool_results = tool_results
                self._apply_deterministic_tool_guard(report)
                if runner.ledger.remaining_calls > 0:
                    follow_up = prompts.verify_delta(
                        problem,
                        strategy.model_dump(mode="json"),
                        checkpoint,
                        delta,
                        result.agent.id,
                        fact_context,
                        auditable_experiments,
                        [item.model_dump(mode="json") for item in tool_results],
                    )
                    follow_result = await self._safe_call(
                        runner,
                        "detailed_verifier",
                        follow_up,
                        fixed_agent=result.agent,
                        budget_bucket="verification",
                    )
                    if follow_result is not None:
                        interpreted: VerificationReport = follow_result.value
                        interpreted.target_id = delta.delta_id
                        interpreted.target_type = "proof_delta"
                        interpreted.agent_id = result.agent.id
                        interpreted.stage = VerificationStage.DETAILED
                        interpreted.raw_artifact_ref = follow_result.raw_ref
                        interpreted.usage = self._sum_usage(
                            [result.usage, follow_result.usage]
                        )
                        interpreted.tool_requests = report.tool_requests
                        interpreted.tool_results = tool_results
                        self._apply_deterministic_tool_guard(interpreted)
                        report = interpreted
                if (
                    any(not item.ok for item in tool_results)
                    and report.verdict == VerificationVerdict.PASS
                ):
                    report.issues.append(
                        VerificationIssue(
                            phase="deterministic_tool_guard",
                            severity=Severity.WARNING,
                            description=(
                                "A requested deterministic tool was inconclusive; it cannot support checkpoint acceptance."
                            ),
                        )
                    )
                    report.verdict = VerificationVerdict.UNCERTAIN
                    report.concise_feedback = (
                        "A requested tool was inconclusive. " + report.concise_feedback
                    )
            self._apply_experiment_counterexample_guard(
                delta,
                report,
                tools.results_for_path(checkpoint.path_id),
            )
            self._apply_experiment_audit_guard(report, experiment_audit)
            reports.append(report)
            excluded.update(tried)
            store.write_json(
                "structured",
                f"checkpoint_verification_{delta.delta_id}_{report.agent_id}",
                report,
            )
            store.append_event("checkpoint_verification_completed", report)
        return reports

    async def _explore_path_legacy(
        self,
        problem: ProblemContract,
        strategy: StrategyCard,
        agent: AgentRuntime,
        *,
        round_index: int,
        runner: StructuredAgentRunner,
        prompts: PromptFactory,
        router: SparseTopologyRouter,
        memory: LemmaMemory,
        store: ArtifactStore,
        tools: ToolBroker,
        targeted_feedback: list[str],
        previous_attempt: ProofAttempt | None,
        budget_bucket: str,
        computation_meta_approved: bool,
    ) -> ProofAttempt:
        relevant = router.relevant_claims(memory.claims, strategy, targeted_feedback)
        path_id = (
            previous_attempt.path_id
            if previous_attempt and previous_attempt.path_id
            else f"path_{strategy.strategy_id}"
        )
        experiment_results: list[dict[str, Any]] = []
        computation_feedback: list[dict[str, Any]] = []
        compute_cycles = 0
        confirmed_counterexample_pending = False
        cumulative_usage = UsageRecord()
        latest_raw_ref: str | None = None
        attempt: ProofAttempt | None = None
        while True:
            bundle = prompts.explore(
                problem,
                strategy.model_dump(mode="json"),
                agent.id,
                round_index,
                [c.model_dump(mode="json") for c in relevant],
                targeted_feedback,
                self._attempt_context_dict(previous_attempt, full=False)
                if previous_attempt
                else None,
                runner.ledger.remaining_calls,
                experiment_results,
                computation_feedback,
            )
            result = await self._safe_call(
                runner,
                "explorer",
                bundle,
                fixed_agent=agent,
                budget_bucket=budget_bucket,
            )
            if result is None:
                return self._failed_attempt(
                    problem,
                    strategy,
                    agent.id,
                    round_index,
                    RuntimeError("explorer returned no valid structured result"),
                )
            cumulative_usage = self._sum_usage([cumulative_usage, result.usage])
            latest_raw_ref = result.raw_ref
            turn = (
                result.value
                if isinstance(result.value, InitialExplorationTurn)
                else InitialExplorationTurn(
                    action=InitialExplorationAction.SUBMIT_ATTEMPT,
                    attempt=result.value,
                )
            )
            if (
                confirmed_counterexample_pending
                and turn.action != InitialExplorationAction.REQUEST_COMPUTATION
                and turn.experiment_impact is None
            ):
                return self._failed_attempt(
                    problem,
                    strategy,
                    agent.id,
                    round_index,
                    RuntimeError(
                        "confirmed counterexample was not classified as execution, plan, or strategy"
                    ),
                )
            if turn.experiment_impact is not None:
                store.append_event(
                    "experiment_impact_classified",
                    {
                        "path_id": path_id,
                        "strategy_id": strategy.strategy_id,
                        "failure_level": turn.experiment_impact,
                        "reason": turn.reason,
                    },
                )
            if turn.action == InitialExplorationAction.SUBMIT_ATTEMPT:
                attempt = turn.attempt
                break
            if turn.action == InitialExplorationAction.ABANDON:
                return self._failed_attempt(
                    problem,
                    strategy,
                    agent.id,
                    round_index,
                    RuntimeError(turn.reason or "explorer abandoned the route"),
                )
            if compute_cycles >= self.config.computation.max_compute_cycles_per_segment:
                return self._failed_attempt(
                    problem,
                    strategy,
                    agent.id,
                    round_index,
                    RuntimeError("per-segment computation cycle limit reached"),
                )
            assert turn.experiment_spec is not None
            decision, experiment = await self._run_requested_computation(
                problem,
                turn.experiment_spec,
                agent,
                path_id=path_id,
                parent_checkpoint_id=None,
                stalled_rounds=round_index,
                meta_review_approved=computation_meta_approved,
                runner=runner,
                prompts=prompts,
                tools=tools,
                budget_bucket=budget_bucket,
            )
            compute_cycles += 1
            computation_feedback.append(decision.model_dump(mode="json"))
            if experiment is not None:
                experiment_results.append(experiment.model_dump(mode="json"))
                confirmed_counterexample_pending = confirmed_counterexample_pending or (
                    experiment.outcome == ExperimentOutcome.COUNTEREXAMPLE_FOUND
                    and experiment.evidence_strength == EvidenceStrength.COUNTEREXAMPLE
                    and experiment.independently_verified
                )

        assert attempt is not None
        # Authoritative metadata is assigned by the orchestrator, not trusted from model text.
        attempt.problem_hash = problem.integrity_hash
        attempt.strategy_id = strategy.strategy_id
        attempt.agent_id = agent.id
        attempt.round_index = round_index
        attempt.path_id = path_id
        attempt.raw_artifact_ref = latest_raw_ref
        attempt.usage = cumulative_usage
        for lemma in attempt.proposed_lemmas:
            lemma.source_attempt_id = attempt.attempt_id
            lemma.source_agent_id = agent.id
            if latest_raw_ref and not any(
                e.artifact_ref == latest_raw_ref for e in lemma.evidence_refs
            ):
                lemma.evidence_refs.append(
                    EvidenceRef(
                        artifact_ref=latest_raw_ref,
                        summary="Raw explorer response containing the proposed lemma.",
                    )
                )
        store.write_json("structured", f"attempt_{attempt.attempt_id}", attempt)
        store.append_event("attempt_completed", attempt)
        if runner.activity is not None:
            runner.activity.info(
                "proof_route_result",
                title=runner.activity.text(
                    "一条证明路线已返回", "A proof route returned"
                ),
                detail=runner.activity.text(
                    f"{strategy.title}：{attempt.status.value}；步骤 {len(attempt.proof_steps)}；未解缺口 {len(attempt.unresolved_gaps)}",
                    f"{strategy.title}: {attempt.status.value}; {len(attempt.proof_steps)} steps; {len(attempt.unresolved_gaps)} unresolved gaps",
                ),
                stage="independent_exploration",
                agent_id=agent.id,
                importance=ActivityImportance.NORMAL,
                metrics={
                    "attempt_id": attempt.attempt_id,
                    "strategy_id": strategy.strategy_id,
                    "status": attempt.status.value,
                    "proof_step_count": len(attempt.proof_steps),
                    "unresolved_gap_count": len(attempt.unresolved_gaps),
                },
            )
        return attempt

    async def _extract_claims_many(
        self,
        problem: ProblemContract,
        attempts: Sequence[ProofAttempt],
        runner: StructuredAgentRunner,
        prompts: PromptFactory,
        memory: LemmaMemory,
        store: ArtifactStore,
        *,
        budget_bucket: str,
    ) -> None:
        async def one(attempt: ProofAttempt) -> ClaimBatch | None:
            if attempt.status == AttemptStatus.FAILED:
                return None
            exclude = {attempt.agent_id} if len(runner.pool.agents) > 1 else set()
            summarizer = runner.pool.select("summarizer", exclude=exclude)
            bundle = prompts.summarize_claims(
                problem,
                attempt,
                self._claim_dedup_index(memory.claims),
            )
            result = await self._safe_call(
                runner,
                "summarizer",
                bundle,
                fixed_agent=summarizer,
                budget_bucket=budget_bucket,
            )
            if result is None:
                # Explorer-supplied lemmas remain proposed; no silent loss.
                if attempt.proposed_lemmas:
                    self._normalize_claims(
                        attempt.proposed_lemmas, attempt, attempt.raw_artifact_ref
                    )
                    memory.add_many(attempt.proposed_lemmas)
                return None
            batch: ClaimBatch = result.value
            batch.attempt_id = attempt.attempt_id
            self._normalize_claims(batch.claims, attempt, attempt.raw_artifact_ref)
            memory.add_many(batch.claims)
            store.write_json("structured", f"claim_batch_{attempt.attempt_id}", batch)
            return batch

        results = await asyncio.gather(
            *(one(a) for a in attempts), return_exceptions=True
        )
        self._raise_if_provider_circuit(results)
        for attempt, result in zip(attempts, results):
            if isinstance(result, Exception):
                store.append_event(
                    "claim_extraction_failed",
                    {"attempt_id": attempt.attempt_id, "error": str(result)},
                )
                if attempt.proposed_lemmas:
                    self._normalize_claims(
                        attempt.proposed_lemmas, attempt, attempt.raw_artifact_ref
                    )
                    memory.add_many(attempt.proposed_lemmas)

    async def _verify_attempts_many(
        self,
        problem: ProblemContract,
        attempts: Sequence[ProofAttempt],
        runner: StructuredAgentRunner,
        prompts: PromptFactory,
        router: SparseTopologyRouter,
        memory: LemmaMemory,
        tools: ToolBroker,
        store: ArtifactStore,
        *,
        state: SolveState | None = None,
    ) -> list[VerificationBundle]:
        eligible = [
            attempt
            for attempt in attempts
            if attempt.proof_steps and attempt.status != AttemptStatus.FAILED
        ]
        for attempt in attempts:
            if attempt not in eligible:
                store.append_event(
                    "empty_attempt_verification_skipped",
                    {
                        "attempt_id": attempt.attempt_id,
                        "strategy_id": attempt.strategy_id,
                        "status": attempt.status.value,
                        "proof_step_count": len(attempt.proof_steps),
                    },
                )
        results = await asyncio.gather(
            *(
                self._verify_attempt(
                    problem,
                    attempt,
                    runner,
                    prompts,
                    router,
                    memory,
                    tools,
                    store,
                    state=state,
                )
                for attempt in eligible
            ),
            return_exceptions=True,
        )
        self._raise_if_provider_circuit(results)
        bundles: list[VerificationBundle] = []
        for attempt, result in zip(eligible, results):
            if isinstance(result, Exception):
                store.append_event(
                    "verification_pipeline_failed",
                    {"attempt_id": attempt.attempt_id, "error": str(result)},
                )
                aggregate = self._synthetic_verification_failure(
                    attempt.attempt_id,
                    "attempt",
                    VerificationStage.DETAILED,
                    f"Verification pipeline failed: {result}",
                )
                bundles.append(
                    VerificationBundle(aggregate=aggregate, reports=[aggregate])
                )
            else:
                bundles.append(result)
        return bundles

    async def _verify_attempt(
        self,
        problem: ProblemContract,
        attempt: ProofAttempt,
        runner: StructuredAgentRunner,
        prompts: PromptFactory,
        router: SparseTopologyRouter,
        memory: LemmaMemory,
        tools: ToolBroker,
        store: ArtifactStore,
        *,
        state: SolveState | None = None,
    ) -> VerificationBundle:
        reports: list[VerificationReport] = []
        experiment_audit = (
            tools.audit_key_results(
                path_id=attempt.path_id,
                report_name=f"attempt_experiment_audit_{attempt.attempt_id}",
            )
            if attempt.path_id
            else []
        )

        structural_reviewers = router.select_reviewers(
            attempt,
            role="structural_verifier",
            count=1,
        )
        structural_reports = await self._call_structural_reviewers(
            problem,
            attempt,
            structural_reviewers,
            runner,
            prompts,
            store,
        )
        reports.extend(structural_reports)

        # A structural fail/uncertainty triggers one independent confirmation instead of
        # broadcasting to every reviewer.
        if (
            self.config.topology.conditional_cross_review
            and structural_reports
            and structural_reports[0].verdict != VerificationVerdict.PASS
            and runner.ledger.remaining_calls > 0
        ):
            extra = router.select_reviewers(
                attempt,
                role="structural_verifier",
                count=1,
                exclude_extra={r.agent_id for r in structural_reports},
            )
            extra_reports = await self._call_structural_reviewers(
                problem,
                attempt,
                extra,
                runner,
                prompts,
                store,
            )
            structural_reports.extend(extra_reports)
            reports.extend(extra_reports)

        structural_aggregate = self._aggregate_reports(
            attempt.attempt_id,
            "attempt",
            VerificationStage.STRUCTURAL,
            structural_reports,
        )
        self._apply_experiment_audit_guard(structural_aggregate, experiment_audit)
        reports.append(structural_aggregate)
        store.write_json(
            "structured",
            f"structural_aggregate_{attempt.attempt_id}",
            structural_aggregate,
        )

        detailed_reports: list[VerificationReport] = []
        may_detail = structural_aggregate.verdict == VerificationVerdict.PASS
        if not self.config.verification.detailed_only_after_structural_pass:
            may_detail = structural_aggregate.verdict != VerificationVerdict.FAIL

        if may_detail and runner.ledger.remaining_calls > 0:
            replica_count = router.verification_replicas(attempt, structural_reports)
            detailed_reviewers = router.select_reviewers(
                attempt,
                role="detailed_verifier",
                count=replica_count,
                exclude_extra={r.agent_id for r in structural_reports},
            )
            detailed_reports = await self._call_detailed_reviewers(
                problem,
                attempt,
                structural_aggregate,
                detailed_reviewers,
                runner,
                prompts,
                memory,
                tools,
                store,
                stage="detailed",
                experiment_audit=experiment_audit,
                state=state,
            )
            reports.extend(detailed_reports)

            disagreement = router.pairwise_disagreement(detailed_reports)
            need_extra = bool(detailed_reports) and (
                disagreement >= self.config.topology.disagreement_threshold
                or any(r.verdict != VerificationVerdict.PASS for r in detailed_reports)
                or min(r.confidence for r in detailed_reports)
                < self.config.budget.verification_pass_threshold
            )
            if (
                self.config.topology.conditional_cross_review
                and need_extra
                and len(detailed_reports)
                < self.config.budget.high_risk_verifier_replicas
                and runner.ledger.remaining_calls > 0
            ):
                extra = router.select_reviewers(
                    attempt,
                    role="detailed_verifier",
                    count=1,
                    exclude_extra={r.agent_id for r in reports},
                )
                extra_reports = await self._call_detailed_reviewers(
                    problem,
                    attempt,
                    structural_aggregate,
                    extra,
                    runner,
                    prompts,
                    memory,
                    tools,
                    store,
                    stage="detailed",
                    experiment_audit=experiment_audit,
                    state=state,
                )
                detailed_reports.extend(extra_reports)
                reports.extend(extra_reports)

        if structural_aggregate.verdict == VerificationVerdict.FAIL:
            aggregate = VerificationReport(
                target_id=attempt.attempt_id,
                target_type="attempt",
                agent_id="system-aggregate",
                stage=VerificationStage.DETAILED,
                problem_integrity_ok=structural_aggregate.problem_integrity_ok,
                verdict=VerificationVerdict.FAIL,
                first_error_step=structural_aggregate.first_error_step,
                issues=structural_aggregate.issues,
                checked_dependencies=structural_aggregate.checked_dependencies,
                failure_level=structural_aggregate.failure_level,
                confidence=structural_aggregate.confidence,
                concise_feedback="Detailed verification skipped because the structural gate failed. "
                + structural_aggregate.concise_feedback,
            )
        elif not detailed_reports:
            aggregate = VerificationReport(
                target_id=attempt.attempt_id,
                target_type="attempt",
                agent_id="system-aggregate",
                stage=VerificationStage.DETAILED,
                problem_integrity_ok=structural_aggregate.problem_integrity_ok,
                verdict=VerificationVerdict.UNCERTAIN,
                issues=structural_aggregate.issues,
                checked_dependencies=structural_aggregate.checked_dependencies,
                failure_level=structural_aggregate.failure_level,
                confidence=max(0.1, structural_aggregate.confidence * 0.5),
                concise_feedback="Structural gate did not lead to a completed detailed audit.",
            )
        else:
            aggregate = self._aggregate_reports(
                attempt.attempt_id,
                "attempt",
                VerificationStage.DETAILED,
                detailed_reports,
            )
            if (
                structural_aggregate.verdict == VerificationVerdict.UNCERTAIN
                and aggregate.verdict == VerificationVerdict.PASS
            ):
                aggregate.verdict = VerificationVerdict.UNCERTAIN
                aggregate.confidence = min(
                    aggregate.confidence, structural_aggregate.confidence
                )
                aggregate.concise_feedback = (
                    "Detailed reviewers passed, but the structural gate remains uncertain. "
                    + aggregate.concise_feedback
                )

        reports.append(aggregate)
        store.write_json(
            "structured", f"verification_aggregate_{attempt.attempt_id}", aggregate
        )
        memory.mark_attempt_verified(attempt.attempt_id, aggregate)
        self._update_agent_trust(attempt, reports, aggregate, runner.pool)
        if runner.activity is not None:
            runner.activity.info(
                "candidate_verification_result",
                title=runner.activity.text(
                    "候选证明审查完成", "Candidate proof audit completed"
                ),
                detail=runner.activity.text(
                    f"路线 {attempt.strategy_id}：{aggregate.verdict.value}；置信度 {aggregate.confidence:.2f}",
                    f"Route {attempt.strategy_id}: {aggregate.verdict.value}; confidence {aggregate.confidence:.2f}",
                ),
                stage="detailed_verification",
                importance=ActivityImportance.NORMAL,
                metrics={
                    "attempt_id": attempt.attempt_id,
                    "strategy_id": attempt.strategy_id,
                    "verdict": aggregate.verdict.value,
                    "confidence": aggregate.confidence,
                    "failure_level": aggregate.failure_level.value,
                },
            )
        return VerificationBundle(aggregate=aggregate, reports=reports)

    async def _call_structural_reviewers(
        self,
        problem: ProblemContract,
        attempt: ProofAttempt,
        reviewers: Sequence[AgentRuntime],
        runner: StructuredAgentRunner,
        prompts: PromptFactory,
        store: ArtifactStore,
    ) -> list[VerificationReport]:
        async def one(reviewer: AgentRuntime) -> VerificationReport:
            bundle = prompts.structural_verify(
                problem,
                attempt.model_dump(mode="json"),
                reviewer.id,
            )
            result = await self._safe_call(
                runner,
                "structural_verifier",
                bundle,
                fixed_agent=reviewer,
                budget_bucket="verification",
            )
            if result is None:
                return self._synthetic_verification_failure(
                    attempt.attempt_id,
                    "attempt",
                    VerificationStage.STRUCTURAL,
                    f"Structural verifier {reviewer.id} failed to return a valid report.",
                    uncertain=True,
                )
            report: VerificationReport = result.value
            self._normalize_report(
                report,
                target_id=attempt.attempt_id,
                target_type="attempt",
                agent_id=reviewer.id,
                stage=VerificationStage.STRUCTURAL,
                raw_ref=result.raw_ref,
                usage=result.usage,
            )
            self._apply_local_attempt_integrity_guard(problem, attempt, report)
            store.write_json("structured", f"report_{report.report_id}", report)
            return report

        results = await asyncio.gather(
            *(one(r) for r in reviewers), return_exceptions=True
        )
        self._raise_if_provider_circuit(results)
        reports: list[VerificationReport] = []
        for reviewer, result in zip(reviewers, results):
            if isinstance(result, Exception):
                reports.append(
                    self._synthetic_verification_failure(
                        attempt.attempt_id,
                        "attempt",
                        VerificationStage.STRUCTURAL,
                        f"Structural verifier {reviewer.id} raised: {result}",
                        uncertain=True,
                    )
                )
            else:
                reports.append(result)
        return reports

    async def _call_detailed_reviewers(
        self,
        problem: ProblemContract,
        target: ProofAttempt | FinalProof,
        structural_report: VerificationReport,
        reviewers: Sequence[AgentRuntime],
        runner: StructuredAgentRunner,
        prompts: PromptFactory,
        memory: LemmaMemory,
        tools: ToolBroker,
        store: ArtifactStore,
        *,
        stage: str,
        experiment_audit: Sequence[dict[str, Any]] | None = None,
        prompt_target: dict[str, Any] | None = None,
        state: SolveState | None = None,
    ) -> list[VerificationReport]:
        target_id = (
            target.attempt_id if isinstance(target, ProofAttempt) else "final_proof"
        )
        target_type = "attempt" if isinstance(target, ProofAttempt) else "final_proof"
        audit_by_hash = {
            str(record.get("request_hash")): record
            for record in (experiment_audit or [])
        }
        auditable_experiments = (
            self._experiment_context(
                tools,
                target.path_id,
                audit_records=experiment_audit,
            )
            if isinstance(target, ProofAttempt) and target.path_id
            else [
                {
                    **result.model_dump(mode="json"),
                    **(
                        {"independent_replay_audit": audit_by_hash[result.request_hash]}
                        if result.request_hash in audit_by_hash
                        else {}
                    ),
                }
                for result in tools.results
            ]
        )
        sanitized_target = prompt_target or target.model_dump(mode="json")
        sanitized_query = json.dumps(
            sanitized_target, ensure_ascii=False, sort_keys=True
        )
        fact_context = self._admissible_fact_context(
            state=state,
            legacy_memory=memory,
            query=sanitized_query,
            max_chars=max(2000, self.config.topology.max_context_chars // 4),
            purpose=(
                ContextPurpose.FINAL_VERIFICATION
                if stage.startswith("final")
                else ContextPurpose.ATTEMPT_VERIFICATION
            ),
            required_refs=explicit_dependency_refs(sanitized_target),
        )
        guard_experiments = (
            tools.results_for_path(target.path_id)
            if isinstance(target, ProofAttempt) and target.path_id
            else tools.results
        )

        async def one(reviewer: AgentRuntime) -> VerificationReport:
            bundle = prompts.detailed_verify(
                problem,
                sanitized_target,
                structural_report,
                fact_context,
                reviewer.id,
                experiment_results=auditable_experiments,
                stage=stage,
            )
            result = await self._safe_call(
                runner,
                "final_verifier" if stage == "final" else "detailed_verifier",
                bundle,
                fixed_agent=reviewer,
                budget_bucket="verification",
            )
            if result is None:
                return self._synthetic_verification_failure(
                    target_id,
                    target_type,
                    VerificationStage.FINAL
                    if stage == "final"
                    else VerificationStage.DETAILED,
                    f"Detailed verifier {reviewer.id} failed to return a valid report.",
                    uncertain=True,
                )
            report: VerificationReport = result.value
            self._normalize_report(
                report,
                target_id=target_id,
                target_type=target_type,
                agent_id=reviewer.id,
                stage=VerificationStage.FINAL
                if stage == "final"
                else VerificationStage.DETAILED,
                raw_ref=result.raw_ref,
                usage=result.usage,
            )
            self._apply_local_target_integrity_guard(problem, target, report)
            self._apply_experiment_counterexample_guard(
                target,
                report,
                guard_experiments,
            )
            self._apply_experiment_audit_guard(report, experiment_audit or [])

            if report.tool_requests:
                tool_results = tools.execute_many(report.tool_requests)
                report.tool_results = tool_results
                self._apply_deterministic_tool_guard(report)
                # Let the same independent reviewer interpret its narrowly-scoped tool output.
                if runner.ledger.remaining_calls > 0:
                    follow_up = prompts.detailed_verify(
                        problem,
                        sanitized_target,
                        structural_report,
                        fact_context,
                        reviewer.id,
                        [t.model_dump(mode="json") for t in tool_results],
                        auditable_experiments,
                        stage=stage,
                    )
                    follow_result = await self._safe_call(
                        runner,
                        "final_verifier" if stage == "final" else "detailed_verifier",
                        follow_up,
                        fixed_agent=reviewer,
                        budget_bucket="verification",
                    )
                    if follow_result is not None:
                        interpreted: VerificationReport = follow_result.value
                        self._normalize_report(
                            interpreted,
                            target_id=target_id,
                            target_type=target_type,
                            agent_id=reviewer.id,
                            stage=(
                                VerificationStage.FINAL
                                if stage == "final"
                                else VerificationStage.DETAILED
                            ),
                            raw_ref=follow_result.raw_ref,
                            usage=self._sum_usage([result.usage, follow_result.usage]),
                        )
                        interpreted.tool_requests = report.tool_requests
                        interpreted.tool_results = tool_results
                        self._apply_local_target_integrity_guard(
                            problem, target, interpreted
                        )
                        self._apply_deterministic_tool_guard(interpreted)
                        self._apply_experiment_counterexample_guard(
                            target,
                            interpreted,
                            guard_experiments,
                        )
                        self._apply_experiment_audit_guard(
                            interpreted, experiment_audit or []
                        )
                        report = interpreted
            store.write_json("structured", f"report_{report.report_id}", report)
            return report

        results = await asyncio.gather(
            *(one(r) for r in reviewers), return_exceptions=True
        )
        self._raise_if_provider_circuit(results)
        reports: list[VerificationReport] = []
        for reviewer, result in zip(reviewers, results):
            if isinstance(result, Exception):
                reports.append(
                    self._synthetic_verification_failure(
                        target_id,
                        target_type,
                        VerificationStage.FINAL
                        if stage == "final"
                        else VerificationStage.DETAILED,
                        f"Detailed verifier {reviewer.id} raised: {result}",
                        uncertain=True,
                    )
                )
            else:
                reports.append(result)
        return reports

    @staticmethod
    def _expand_blind_verification(
        report: BlindVerificationReport,
        *,
        verifier_id: str,
        stage: VerificationStage,
        raw_ref: str | None,
        usage: UsageRecord,
    ) -> VerificationReport:
        """Attach system provenance only after an identity-free model call."""

        return VerificationReport(
            target_id="final_proof",
            target_type="final_proof",
            agent_id=verifier_id,
            stage=stage,
            problem_integrity_ok=report.problem_integrity_ok,
            verdict=report.verdict,
            first_error_step=report.first_error_step,
            issues=report.issues,
            checked_dependencies=report.checked_dependencies,
            tool_requests=report.tool_requests,
            tool_results=report.tool_results,
            failure_level=report.failure_level,
            confidence=report.confidence,
            concise_feedback=report.concise_feedback,
            raw_artifact_ref=raw_ref,
            usage=usage,
        )

    async def _call_blind_final_reviewers(
        self,
        problem: ProblemContract,
        proof: FinalProof,
        packet: BlindReviewPacket,
        reviewers: Sequence[AgentRuntime],
        runner: StructuredAgentRunner,
        prompts: PromptFactory,
        tools: ToolBroker,
        store: ArtifactStore,
        *,
        experiment_audit: Sequence[dict[str, Any]] | None = None,
    ) -> list[VerificationReport]:
        audit_by_hash = {
            str(record.get("request_hash")): record
            for record in (experiment_audit or [])
        }
        auditable_experiments = [
            {
                **experiment.model_dump(mode="json"),
                **(
                    {"independent_replay_audit": audit_by_hash[experiment.request_hash]}
                    if experiment.request_hash in audit_by_hash
                    else {}
                ),
            }
            for experiment in tools.results
        ]

        async def one(reviewer: AgentRuntime) -> VerificationReport:
            first = await self._safe_call(
                runner,
                "final_verifier",
                prompts.blind_detailed_review(
                    packet,
                    experiment_results=auditable_experiments,
                ),
                fixed_agent=reviewer,
                budget_bucket="verification",
            )
            if first is None:
                return self._synthetic_verification_failure(
                    "final_proof",
                    "final_proof",
                    VerificationStage.FINAL,
                    f"Final verifier {reviewer.id} failed to return a valid report.",
                    uncertain=True,
                )

            blind: BlindVerificationReport = first.value
            raw_ref = first.raw_ref
            usage = first.usage
            if blind.tool_requests:
                tool_results = tools.execute_many(blind.tool_requests)
                blind.tool_results = tool_results
                if runner.ledger.remaining_calls > 0:
                    follow_up = await self._safe_call(
                        runner,
                        "final_verifier",
                        prompts.blind_detailed_review(
                            packet,
                            tool_results=[
                                item.model_dump(mode="json") for item in tool_results
                            ],
                            experiment_results=auditable_experiments,
                        ),
                        fixed_agent=reviewer,
                        budget_bucket="verification",
                    )
                    if follow_up is not None:
                        interpreted: BlindVerificationReport = follow_up.value
                        interpreted.tool_requests = blind.tool_requests
                        interpreted.tool_results = tool_results
                        blind = interpreted
                        raw_ref = follow_up.raw_ref
                        usage = self._sum_usage([first.usage, follow_up.usage])

            report = self._expand_blind_verification(
                blind,
                verifier_id=reviewer.id,
                stage=VerificationStage.FINAL,
                raw_ref=raw_ref,
                usage=usage,
            )
            self._apply_local_target_integrity_guard(problem, proof, report)
            self._apply_deterministic_tool_guard(report)
            self._apply_experiment_counterexample_guard(proof, report, tools.results)
            self._apply_experiment_audit_guard(report, experiment_audit or [])
            store.write_json("structured", f"report_{report.report_id}", report)
            return report

        results = await asyncio.gather(
            *(one(reviewer) for reviewer in reviewers), return_exceptions=True
        )
        self._raise_if_provider_circuit(results)
        reports: list[VerificationReport] = []
        for reviewer, result in zip(reviewers, results):
            if isinstance(result, Exception):
                reports.append(
                    self._synthetic_verification_failure(
                        "final_proof",
                        "final_proof",
                        VerificationStage.FINAL,
                        f"Final verifier {reviewer.id} raised: {result}",
                        uncertain=True,
                    )
                )
            else:
                reports.append(result)
        return reports

    async def _meta_review(
        self,
        problem: ProblemContract,
        attempts: Sequence[ProofAttempt],
        aggregate_reports: dict[str, VerificationReport],
        runner: StructuredAgentRunner,
        prompts: PromptFactory,
        store: ArtifactStore,
    ) -> MetaReview:
        ranked = self._rank_attempts(attempts)
        candidates = ranked[: max(1, min(5, len(ranked)))]
        reports = [
            aggregate_reports[a.attempt_id]
            for a in candidates
            if a.attempt_id in aggregate_reports
        ]
        result = await self._safe_call(
            runner,
            "meta_reviewer",
            prompts.meta_review(
                problem,
                self._fit_json_items(
                    [self._attempt_context_dict(a, full=False) for a in candidates],
                    max_chars=max(
                        3000, int(self.config.topology.max_context_chars * 0.65)
                    ),
                ),
                self._fit_json_items(
                    [r.model_dump(mode="json") for r in reports],
                    max_chars=max(
                        2000, int(self.config.topology.max_context_chars * 0.30)
                    ),
                ),
            ),
            budget_bucket="verification",
        )
        if result is not None:
            review: MetaReview = result.value
            valid_ids = {a.attempt_id for a in candidates}
            if review.selected_target_id not in valid_ids:
                review.selected_target_id = (
                    candidates[0].attempt_id if candidates else None
                )
            store.write_json(
                "structured",
                f"meta_review_{len(list(store.root.glob('structured/meta_review_*.json')))}",
                review,
            )
            return review
        review = self._local_meta_review(candidates, aggregate_reports)
        store.write_json("structured", f"meta_review_fallback_{new_id('r')}", review)
        return review

    async def _deepen_path(
        self,
        problem: ProblemContract,
        strategy_id: str,
        round_index: int,
        state: SolveState,
        runner: StructuredAgentRunner,
        prompts: PromptFactory,
        router: SparseTopologyRouter,
        memory: LemmaMemory,
        store: ArtifactStore,
        tools: ToolBroker,
    ) -> ProofAttempt | None:
        strategy = next(
            (s for s in state.strategies if s.strategy_id == strategy_id), None
        )
        if strategy is None:
            return None
        previous_candidates = [
            a for a in state.attempts if a.strategy_id == strategy_id
        ]
        if not previous_candidates:
            return None
        previous = max(previous_candidates, key=lambda a: a.round_index)
        try:
            agent = runner.pool.get(previous.agent_id)
        except KeyError:
            agent = runner.pool.select("explorer", specialty_hints=strategy.tags)
        feedback = self._targeted_feedback(previous, state)
        computation_meta_approved = any(
            strategy_id in review.broad_computation_approved_strategy_ids
            for review in state.meta_reviews[-1:]
        )
        router.add_edge(
            source="meta-reviewer",
            target=agent.id,
            stage="targeted_deepening",
            payload_type="ContextPack",
            reason="repair only the selected path using verified lemmas and focused first-error feedback",
        )
        return await self._explore_path(
            problem,
            strategy,
            agent,
            state=state,
            round_index=round_index,
            runner=runner,
            prompts=prompts,
            router=router,
            memory=memory,
            store=store,
            tools=tools,
            targeted_feedback=feedback,
            previous_attempt=previous,
            budget_bucket="depth",
            computation_meta_approved=computation_meta_approved,
            deep_exploration_meta_approved=True,
        )

    async def _widen(
        self,
        problem: ProblemContract,
        triage: TriageResult | None,
        round_index: int,
        state: SolveState,
        runner: StructuredAgentRunner,
        prompts: PromptFactory,
        router: SparseTopologyRouter,
        memory: LemmaMemory,
        store: ArtifactStore,
        tools: ToolBroker,
        *,
        requested_count: int | None = None,
    ) -> list[ProofAttempt]:
        if len(state.strategies) >= self.config.budget.max_paths:
            return []
        triage = triage or self._fallback_triage()
        feedback: list[str] = []
        if state.meta_reviews:
            feedback.extend(state.meta_reviews[-1].unresolved_conflicts)
            feedback.extend(state.meta_reviews[-1].required_actions)
        count = min(
            requested_count or self.config.scheduler.widen_paths_per_action,
            self.config.budget.max_paths - len(state.strategies),
        )
        result = await self._safe_call(
            runner,
            "planner",
            prompts.strategies(
                problem,
                triage,
                count,
                prior_strategy_titles=[s.title for s in state.strategies],
                regulator_feedback=feedback,
            ),
            budget_bucket="breadth",
        )
        if result is None:
            candidates = self._fallback_strategy_set(problem, count).strategies
        else:
            candidates = result.value.strategies

        candidates = self._attach_planner_computation_hints(candidates)

        genuinely_new: list[StrategyCard] = []
        deduplicated = self._deduplicate_strategy_cards(
            [*state.strategies, *candidates]
        )
        existing_ids = {item.strategy_id for item in state.strategies}
        for candidate in deduplicated:
            if candidate.strategy_id in existing_ids:
                continue
            max_similarity = max(
                (
                    jaccard_similarity(
                        strategy_text(candidate), strategy_text(existing)
                    )
                    for existing in state.strategies
                ),
                default=0.0,
            )
            if max_similarity < self.config.topology.strategy_similarity_threshold:
                genuinely_new.append(candidate)
        if not genuinely_new:
            return []
        selected = router.select_diverse_strategies(genuinely_new, count)
        state.strategies.extend(selected)
        assignments = router.assign_explorers(selected)
        if state.route_registry is not None:
            for strategy, agent in assignments:
                route = state.route_registry.register_route(strategy)
                state.route_registry.assign_member(
                    route.route_id, agent.id, RouteRole.PROVER, round_index
                )
                store.append_event("route_registered", route)
            state.route_registry.recompute_neighbors()
        return await self._parallel_round_exploration(
            problem,
            state,
            assignments,
            round_index,
            runner,
            prompts,
            router,
            memory,
            store,
            tools,
        )

    async def _parallel_round_exploration(
        self,
        problem: ProblemContract,
        state: SolveState,
        assignments: list[tuple[StrategyCard, AgentRuntime]],
        round_index: int,
        runner: StructuredAgentRunner,
        prompts: PromptFactory,
        router: SparseTopologyRouter,
        memory: LemmaMemory,
        store: ArtifactStore,
        tools: ToolBroker,
        *,
        max_segments_this_call: int | None = None,
    ) -> list[ProofAttempt]:
        results = await asyncio.gather(
            *(
                self._explore_path(
                    problem,
                    strategy,
                    agent,
                    state=state,
                    round_index=round_index,
                    runner=runner,
                    prompts=prompts,
                    router=router,
                    memory=memory,
                    store=store,
                    tools=tools,
                    targeted_feedback=[],
                    previous_attempt=None,
                    budget_bucket="breadth",
                    max_segments_this_call=max_segments_this_call,
                )
                for strategy, agent in assignments
            ),
            return_exceptions=True,
        )
        self._raise_if_provider_circuit(results)
        attempts: list[ProofAttempt] = []
        for (strategy, agent), result in zip(assignments, results):
            if isinstance(result, Exception):
                attempts.append(
                    self._failed_attempt(
                        problem, strategy, agent.id, round_index, result
                    )
                )
            else:
                attempts.append(result)
        return attempts

    async def _synthesize(
        self,
        problem: ProblemContract,
        state: SolveState,
        runner: StructuredAgentRunner,
        prompts: PromptFactory,
        router: SparseTopologyRouter,
        memory: LemmaMemory,
        store: ArtifactStore,
    ) -> tuple[FinalProof | None, AgentRuntime | None]:
        selected = self._select_for_synthesis(state)
        if not selected:
            return None, None
        review = (
            state.meta_reviews[-1]
            if state.meta_reviews
            else self._local_meta_review(
                selected,
                state.aggregate_reports,
            )
        )
        exclude = (
            {a.agent_id for a in selected} if len(runner.pool.agents) > 1 else set()
        )
        synthesizer = runner.pool.select("synthesizer", exclude=exclude)
        for attempt in selected:
            router.add_edge(
                source=attempt.agent_id,
                target=synthesizer.id,
                stage="synthesis",
                payload_type="ProofAttempt+VerificationReport",
                reason="only selected, supported paths are sent to the synthesizer",
            )
        selected_contexts = self._fit_attempt_contexts(
            selected,
            max_chars=max(5000, int(self.config.topology.max_context_chars * 0.70)),
        )
        claim_query = " ".join(
            f"{attempt.final_answer or ''} "
            + " ".join(step.statement for step in attempt.proof_steps)
            for attempt in selected
        )
        claim_context = self._admissible_fact_context(
            state=state,
            legacy_memory=memory,
            query=claim_query,
            max_chars=max(2000, int(self.config.topology.max_context_chars * 0.25)),
            purpose=ContextPurpose.SYNTHESIS,
            required_refs=explicit_dependency_refs(
                [attempt.model_dump(mode="json") for attempt in selected]
            ),
        )
        open_obligations = (
            [
                {
                    "obligation_id": item.obligation_id,
                    "statement": item.statement,
                    "status": item.status,
                }
                for item in state.proof_graph.obligations
                if item.status != "closed"
            ]
            if state.proof_graph is not None
            else []
        )
        forbidden_claims = (
            [
                item.statement
                for item in state.typed_memory.negatives
                if isinstance(item, MessageEnvelope)
            ]
            if state.typed_memory is not None
            else []
        )

        def bundle_factory(current_agent: AgentRuntime) -> PromptBundle:
            return prompts.synthesize(
                problem,
                selected_contexts,
                claim_context,
                review,
                current_agent.id,
                open_obligations=open_obligations,
                forbidden_claims=forbidden_claims,
            )

        try:
            result, _tried_agents = await runner.call_with_failover(
                "synthesizer",
                bundle_factory,
                primary_agent=synthesizer,
                specialty_hints=["proof_synthesis", "rigorous_exposition"],
                budget_bucket="synthesis",
                max_failover_agents=self.config.continuation.max_failover_agents,
                allow_failover=self.config.continuation.allow_cross_agent_failover,
                failover_only_on_retryable=True,
                exclude_agent_ids=exclude,
            )
        except (BudgetExhaustedError, ProviderCircuitOpenError):
            raise
        except (StructuredOutputError, RuntimeError, ValueError) as exc:
            logger.warning("Agent call failed at synthesis (synthesizer): %s", exc)
            store.append_event(
                "agent_stage_failed",
                {
                    "stage": "synthesis",
                    "role": "synthesizer",
                    "agent_id": synthesizer.id,
                    "error": str(exc),
                },
            )
            result = None
        if result is None:
            return None, synthesizer
        else:
            if result.agent.id != synthesizer.id:
                for attempt in selected:
                    router.add_edge(
                        source=attempt.agent_id,
                        target=result.agent.id,
                        stage="synthesis_failover",
                        payload_type="ProofAttempt+VerificationReport",
                        reason="primary synthesizer failed; backup agent completed synthesis",
                    )
            synthesizer = result.agent
            proof: FinalProof = result.value
            proof.problem_hash = problem.integrity_hash
            proof.source_attempt_ids = [
                attempt_id
                for attempt_id in proof.source_attempt_ids
                if any(a.attempt_id == attempt_id for a in selected)
            ] or [a.attempt_id for a in selected]
        store.write_json("structured", "final_proof_draft", proof)
        return proof, synthesizer

    def _build_blind_review_packet(
        self,
        problem: ProblemContract,
        proof: FinalProof,
        memory: LemmaMemory,
        typed_memory: TypedMemory | None = None,
        message_broker: MessageBroker | None = None,
        artifact_store: ArtifactStore | None = None,
    ) -> BlindReviewPacket:
        return build_blind_review_packet(
            problem,
            proof,
            memory,
            topology_mode=self.config.topology.mode,
            typed_memory=typed_memory,
            message_broker=message_broker,
            artifact_store=artifact_store,
        )

    async def _verify_final(
        self,
        problem: ProblemContract,
        proof: FinalProof,
        synthesizer: AgentRuntime | None,
        runner: StructuredAgentRunner,
        prompts: PromptFactory,
        router: SparseTopologyRouter,
        memory: LemmaMemory,
        tools: ToolBroker,
        store: ArtifactStore,
        *,
        state: SolveState | None = None,
    ) -> VerificationBundle:
        reports: list[VerificationReport] = []
        hierarchical = self.config.topology.mode == "hierarchical_sparse"
        if hierarchical and (
            state is None or state.typed_memory is None or state.message_broker is None
        ):
            store.append_event(
                "global_fact_context_fail_closed",
                {
                    "purpose": ContextPurpose.BLIND_REVIEW.value,
                    "reason": "typed memory or message broker is unavailable",
                },
            )
        blind_packet = self._build_blind_review_packet(
            problem,
            proof,
            memory,
            state.typed_memory if state is not None else None,
            state.message_broker if state is not None else None,
            store,
        )
        blind_structural = (
            hierarchical and self.config.topology.final_stage.blind_structural_review
        )
        blind_detailed = (
            hierarchical and self.config.topology.final_stage.blind_detailed_review
        )
        if blind_structural or blind_detailed:
            store.write_json("structured", "blind_final_review_packet", blind_packet)
        experiment_audit = tools.audit_key_results()
        final_cross_provider_candidates = [
            candidate
            for candidate in runner.pool.agents
            if synthesizer is not None
            and candidate.id != synthesizer.id
            and candidate.provider != synthesizer.provider
            and candidate.supports_role("final_verifier")
        ]
        escalation = ValidationEscalator(
            self.config.topology.validation_escalation
        ).plan(
            risk_score=max(0.0, 1.0 - proof.confidence),
            cross_provider_available=bool(final_cross_provider_candidates),
            tool_or_formal_available=(bool(experiment_audit)),
            final_proof=True,
        )
        store.append_event(
            "validation_escalation_planned",
            {"target_id": "final_proof", **escalation.model_dump(mode="json")},
        )
        failed_experiment_audits = [
            record for record in experiment_audit if not record.get("valid", False)
        ]
        exclude = {synthesizer.id} if synthesizer is not None else set()
        structural = runner.pool.select(
            "structural_verifier",
            exclude=exclude,
            prefer_provider_not=synthesizer.provider if synthesizer else None,
        )
        router.add_edge(
            source=synthesizer.id if synthesizer else "synthesizer",
            target=structural.id,
            stage="final_structural_verification",
            payload_type="FinalProof",
            reason="independent final theorem-integrity and dependency gate",
        )
        structural_prompt = (
            prompts.blind_structural_review(blind_packet)
            if blind_structural
            else prompts.structural_verify(
                problem,
                proof.model_dump(mode="json"),
                structural.id,
            )
        )
        result = await self._safe_call(
            runner,
            "structural_verifier",
            structural_prompt,
            fixed_agent=structural,
            budget_bucket="verification",
        )
        if result is None:
            structural_report = self._synthetic_verification_failure(
                "final_proof",
                "final_proof",
                VerificationStage.STRUCTURAL,
                "Final structural verifier failed to return a valid report.",
                uncertain=True,
            )
        else:
            if blind_structural:
                structural_report = self._expand_blind_verification(
                    result.value,
                    verifier_id=structural.id,
                    stage=VerificationStage.STRUCTURAL,
                    raw_ref=result.raw_ref,
                    usage=result.usage,
                )
            else:
                structural_report = result.value
                self._normalize_report(
                    structural_report,
                    target_id="final_proof",
                    target_type="final_proof",
                    agent_id=structural.id,
                    stage=VerificationStage.STRUCTURAL,
                    raw_ref=result.raw_ref,
                    usage=result.usage,
                )
            self._apply_local_target_integrity_guard(problem, proof, structural_report)
            apply_blind_context_integrity_guard(blind_packet, structural_report)
        if failed_experiment_audits:
            structural_report.issues.append(
                VerificationIssue(
                    phase="final_experiment_audit",
                    severity=Severity.CRITICAL,
                    description=(
                        f"{len(failed_experiment_audits)} proof-relevant experiment artifact(s) failed hash, tool-version, or deterministic replay validation."
                    ),
                    repair_hint=(
                        "Re-run the affected experiment with the pinned tool and independently review its mathematical mapping."
                    ),
                )
            )
            structural_report.failure_level = FailureLevel.EXECUTION
            structural_report.verdict = VerificationVerdict.FAIL
            structural_report.confidence = 1.0
            structural_report.concise_feedback = (
                "Final experiment replay audit failed. "
                + structural_report.concise_feedback
            )
        reports.append(structural_report)

        if structural_report.verdict != VerificationVerdict.PASS:
            execution = await ValidationEscalationExecutor().execute(
                escalation,
                {
                    ValidationLevel.DETERMINISTIC: lambda: ValidationStepResult(
                        level=ValidationLevel.DETERMINISTIC,
                        executed=True,
                        passed=False,
                        evidence_refs=[structural_report.raw_artifact_ref]
                        if structural_report.raw_artifact_ref
                        else [],
                        diagnostic="structural theorem-integrity gate did not pass",
                    )
                },
            )
            store.append_event(
                "validation_escalation_executed",
                {
                    "target_id": "final_proof",
                    **execution.model_dump(mode="json"),
                },
            )
            aggregate = VerificationReport(
                target_id="final_proof",
                target_type="final_proof",
                agent_id="system-aggregate",
                stage=VerificationStage.FINAL,
                problem_integrity_ok=structural_report.problem_integrity_ok,
                verdict=structural_report.verdict,
                first_error_step=structural_report.first_error_step,
                issues=structural_report.issues,
                checked_dependencies=structural_report.checked_dependencies,
                failure_level=structural_report.failure_level,
                confidence=structural_report.confidence,
                concise_feedback="Final detailed audit was blocked by the structural gate. "
                + structural_report.concise_feedback,
            )
            reports.append(aggregate)
            store.write_json(
                "structured", f"final_verification_{new_id('v')}", aggregate
            )
            return VerificationBundle(aggregate=aggregate, reports=reports)

        final_reviewers = runner.pool.select_many(
            "final_verifier",
            self.config.budget.base_verifier_replicas,
            exclude=exclude | {structural.id},
        )
        if not final_reviewers:
            final_reviewers = [runner.pool.select("final_verifier", exclude=exclude)]
        for reviewer in final_reviewers:
            router.add_edge(
                source=synthesizer.id if synthesizer else "synthesizer",
                target=reviewer.id,
                stage="final_detailed_verification",
                payload_type="FinalProof",
                reason="independent first-error, step-level final audit",
            )
        if blind_detailed:
            detailed = await self._call_blind_final_reviewers(
                problem,
                proof,
                blind_packet,
                final_reviewers,
                runner,
                prompts,
                tools,
                store,
                experiment_audit=experiment_audit,
            )
        else:
            detailed = await self._call_detailed_reviewers(
                problem,
                proof,
                structural_report,
                final_reviewers,
                runner,
                prompts,
                memory,
                tools,
                store,
                stage="final",
                experiment_audit=experiment_audit,
                state=state,
            )
        reports.extend(detailed)

        if (
            self.config.topology.conditional_cross_review
            and detailed
            and (
                any(r.verdict != VerificationVerdict.PASS for r in detailed)
                or min(r.confidence for r in detailed)
                < self.config.budget.verification_pass_threshold
            )
            and len(detailed) < self.config.budget.high_risk_verifier_replicas
            and runner.ledger.remaining_calls > 0
        ):
            extra = runner.pool.select_many(
                "final_verifier",
                1,
                exclude=exclude | {structural.id} | {r.agent_id for r in detailed},
            )
            if blind_detailed:
                extra_reports = await self._call_blind_final_reviewers(
                    problem,
                    proof,
                    blind_packet,
                    extra,
                    runner,
                    prompts,
                    tools,
                    store,
                    experiment_audit=experiment_audit,
                )
            else:
                extra_reports = await self._call_detailed_reviewers(
                    problem,
                    proof,
                    structural_report,
                    extra,
                    runner,
                    prompts,
                    memory,
                    tools,
                    store,
                    stage="final",
                    experiment_audit=experiment_audit,
                    state=state,
                )
            detailed.extend(extra_reports)
            reports.extend(extra_reports)

        if (
            ValidationLevel.CROSS_PROVIDER in escalation.levels
            and synthesizer is not None
            and not any(
                report.problem_integrity_ok
                and report.verdict == VerificationVerdict.PASS
                and runner.pool.get(report.agent_id).provider != synthesizer.provider
                for report in detailed
                if report.agent_id in {agent.id for agent in runner.pool.agents}
            )
        ):
            excluded_reviewers = (
                exclude | {structural.id} | {report.agent_id for report in detailed}
            )
            available_cross_reviewers = [
                candidate
                for candidate in final_cross_provider_candidates
                if candidate.id not in excluded_reviewers and not candidate.in_cooldown
            ]
            if available_cross_reviewers:
                cross_reviewer = max(
                    available_cross_reviewers,
                    key=lambda candidate: (
                        runner.pool.capability_score(candidate, "final_verifier"),
                        candidate.trust_score,
                        candidate.id,
                    ),
                )
                if blind_detailed:
                    cross_reports = await self._call_blind_final_reviewers(
                        problem,
                        proof,
                        blind_packet,
                        [cross_reviewer],
                        runner,
                        prompts,
                        tools,
                        store,
                        experiment_audit=experiment_audit,
                    )
                else:
                    cross_reports = await self._call_detailed_reviewers(
                        problem,
                        proof,
                        structural_report,
                        [cross_reviewer],
                        runner,
                        prompts,
                        memory,
                        tools,
                        store,
                        stage="final_cross_provider",
                        experiment_audit=experiment_audit,
                        state=state,
                    )
                detailed.extend(cross_reports)
                reports.extend(cross_reports)

        aggregate = self._aggregate_reports(
            "final_proof",
            "final_proof",
            VerificationStage.FINAL,
            detailed,
        )
        detailed_passes = [
            report
            for report in detailed
            if report.problem_integrity_ok
            and report.verdict == VerificationVerdict.PASS
            and (synthesizer is None or report.agent_id != synthesizer.id)
        ]

        def cross_provider_passed() -> ValidationStepResult:
            if synthesizer is None:
                return ValidationStepResult(
                    level=ValidationLevel.CROSS_PROVIDER,
                    executed=False,
                    passed=False,
                    diagnostic="synthesizer provider is unavailable",
                )
            different_provider_reports: list[VerificationReport] = []
            for report in detailed_passes:
                try:
                    reviewer = runner.pool.get(report.agent_id)
                except KeyError:
                    continue
                if reviewer.provider != synthesizer.provider:
                    different_provider_reports.append(report)
            return ValidationStepResult(
                level=ValidationLevel.CROSS_PROVIDER,
                executed=bool(different_provider_reports),
                passed=bool(different_provider_reports),
                evidence_refs=[
                    report.raw_artifact_ref
                    for report in different_provider_reports
                    if report.raw_artifact_ref
                ],
            )

        execution = await ValidationEscalationExecutor().execute(
            escalation,
            {
                ValidationLevel.DETERMINISTIC: lambda: ValidationStepResult(
                    level=ValidationLevel.DETERMINISTIC,
                    executed=True,
                    passed=(
                        structural_report.problem_integrity_ok
                        and structural_report.verdict == VerificationVerdict.PASS
                        and not failed_experiment_audits
                    ),
                    evidence_refs=[structural_report.raw_artifact_ref]
                    if structural_report.raw_artifact_ref
                    else [],
                ),
                ValidationLevel.BLIND_SAME_MODEL: lambda: ValidationStepResult(
                    level=ValidationLevel.BLIND_SAME_MODEL,
                    executed=bool(detailed),
                    passed=bool(detailed_passes),
                    evidence_refs=[
                        report.raw_artifact_ref
                        for report in detailed_passes
                        if report.raw_artifact_ref
                    ],
                ),
                ValidationLevel.ADVERSARIAL_BLIND: lambda: ValidationStepResult(
                    level=ValidationLevel.ADVERSARIAL_BLIND,
                    executed=bool(detailed),
                    passed=(
                        bool(detailed_passes)
                        and all(
                            report.verdict == VerificationVerdict.PASS
                            for report in detailed
                        )
                    ),
                    evidence_refs=[
                        report.raw_artifact_ref
                        for report in detailed
                        if report.raw_artifact_ref
                    ],
                ),
                ValidationLevel.CROSS_PROVIDER: cross_provider_passed,
                ValidationLevel.TOOL_OR_FORMAL: lambda: ValidationStepResult(
                    level=ValidationLevel.TOOL_OR_FORMAL,
                    executed=bool(experiment_audit),
                    passed=bool(experiment_audit) and not failed_experiment_audits,
                    evidence_refs=[
                        str(record.get("request_hash") or record.get("experiment_id"))
                        for record in experiment_audit
                    ],
                ),
            },
        )
        store.append_event(
            "validation_escalation_executed",
            {
                "target_id": "final_proof",
                **execution.model_dump(mode="json"),
            },
        )
        store.write_json("structured", "final_validation_execution", execution)
        if aggregate.verdict == VerificationVerdict.PASS and not execution.passed:
            aggregate.verdict = VerificationVerdict.UNCERTAIN
            aggregate.concise_feedback = (
                "The mathematical reviewers passed, but the configured validation "
                "escalation ladder did not complete: "
                + "; ".join(execution.diagnostics)
            )
        # Final PASS has a configured minimum confidence floor.
        if (
            aggregate.verdict == VerificationVerdict.PASS
            and aggregate.confidence < self.config.budget.verification_pass_threshold
        ):
            aggregate.verdict = VerificationVerdict.UNCERTAIN
            aggregate.concise_feedback = (
                "All available reviewers passed, but aggregate confidence is below the configured threshold. "
                + aggregate.concise_feedback
            )
        reports.append(aggregate)
        store.write_json("structured", f"final_verification_{new_id('v')}", aggregate)
        return VerificationBundle(aggregate=aggregate, reports=reports)

    async def _revise_final(
        self,
        problem: ProblemContract,
        proof: FinalProof,
        verification: VerificationReport,
        synthesizer: AgentRuntime | None,
        runner: StructuredAgentRunner,
        prompts: PromptFactory,
        memory: LemmaMemory,
        store: ArtifactStore,
        revision_index: int,
        *,
        state: SolveState | None = None,
    ) -> FinalProof | None:
        reviser = synthesizer or runner.pool.select("synthesizer")
        result = await self._safe_call(
            runner,
            "synthesizer",
            prompts.revise_final(
                problem,
                proof,
                verification,
                self._admissible_fact_context(
                    state=state,
                    legacy_memory=memory,
                    query=proof.model_dump_json(),
                    max_chars=max(2000, self.config.topology.max_context_chars // 4),
                    purpose=ContextPurpose.FINAL_REVISION,
                    required_refs=explicit_dependency_refs(proof),
                ),
                reviser.id,
            ),
            fixed_agent=reviser,
            budget_bucket="synthesis",
        )
        if result is None:
            return None
        revised: FinalProof = result.value
        revised.problem_hash = problem.integrity_hash
        store.write_json(
            "structured", f"final_proof_revision_{revision_index}", revised
        )
        return revised

    async def _safe_call(
        self,
        runner: StructuredAgentRunner,
        role: str,
        bundle: PromptBundle,
        *,
        fixed_agent: AgentRuntime | None = None,
        exclude: set[str] | None = None,
        specialty_hints: list[str] | None = None,
        prefer_provider_not: str | None = None,
        budget_bucket: str,
        budget_reservation_id: str | None = None,
    ) -> StructuredCallResult[Any] | None:
        try:
            return await runner.call(
                role,
                bundle,
                fixed_agent=fixed_agent,
                exclude=exclude,
                specialty_hints=specialty_hints,
                prefer_provider_not=prefer_provider_not,
                budget_bucket=budget_bucket,
                budget_reservation_id=budget_reservation_id,
            )
        except (BudgetExhaustedError, ProviderCircuitOpenError):
            raise
        except (StructuredOutputError, RuntimeError, ValueError) as exc:
            logger.warning("Agent call failed at %s (%s): %s", bundle.stage, role, exc)
            runner.store.append_event(
                "agent_stage_failed",
                {
                    "stage": bundle.stage,
                    "role": role,
                    "agent_id": fixed_agent.id if fixed_agent else None,
                    "error": str(exc),
                },
            )
            return None

    @staticmethod
    def _raise_if_provider_circuit(results: Iterable[Any]) -> None:
        for result in results:
            if isinstance(result, ProviderCircuitOpenError):
                raise result

    def _aggregate_reports(
        self,
        target_id: str,
        target_type: str,
        stage: VerificationStage,
        reports: Sequence[VerificationReport],
    ) -> VerificationReport:
        if not reports:
            return self._synthetic_verification_failure(
                target_id,
                target_type,
                stage,
                "No independent verification report was available.",
                uncertain=True,
            )
        if any(not report.problem_integrity_ok for report in reports):
            verdict = VerificationVerdict.FAIL
        else:
            verdicts = [r.verdict for r in reports]
            deterministic_fail = any(
                self._has_deterministic_refutation(r) for r in reports
            )
            if deterministic_fail:
                verdict = VerificationVerdict.FAIL
            elif all(v == VerificationVerdict.PASS for v in verdicts):
                verdict = VerificationVerdict.PASS
            elif all(v == VerificationVerdict.FAIL for v in verdicts):
                verdict = VerificationVerdict.FAIL
            else:
                verdict = VerificationVerdict.UNCERTAIN

        issues: list[VerificationIssue] = []
        seen_issue_keys: set[tuple[str | None, str]] = set()
        for report in reports:
            for issue in report.issues:
                key = (issue.step_id or issue.claim_id, issue.description)
                if key not in seen_issue_keys:
                    issues.append(issue)
                    seen_issue_keys.add(key)
        if verdict == VerificationVerdict.FAIL and not issues:
            issues.append(
                VerificationIssue(
                    phase="verification_aggregation",
                    severity=Severity.ERROR,
                    description=(
                        "Verification aggregation produced FAIL without a concrete issue."
                    ),
                    repair_hint="Inspect the contributing verification reports.",
                )
            )

        failure_level = max(
            (r.failure_level for r in reports),
            key=self._failure_rank,
            default=FailureLevel.NONE,
        )
        if verdict == VerificationVerdict.PASS:
            confidence = min(r.confidence for r in reports)
        elif verdict == VerificationVerdict.FAIL:
            fail_confidences = [
                r.confidence for r in reports if r.verdict == VerificationVerdict.FAIL
            ]
            confidence = max(fail_confidences or [r.confidence for r in reports])
        else:
            # Uncertainty confidence is the confidence that more checking is needed, not a pass probability.
            spread = max(r.confidence for r in reports) - min(
                r.confidence for r in reports
            )
            confidence = min(0.95, 0.55 + 0.25 * spread + 0.05 * len(reports))

        feedback_parts = []
        for report in reports:
            text = report.concise_feedback.strip()
            if text and text not in feedback_parts:
                feedback_parts.append(text)
        first_error = next(
            (r.first_error_step for r in reports if r.first_error_step), None
        )
        return VerificationReport(
            target_id=target_id,
            target_type=target_type,  # type: ignore[arg-type]
            agent_id="system-aggregate",
            stage=stage,
            problem_integrity_ok=all(r.problem_integrity_ok for r in reports),
            verdict=verdict,
            first_error_step=first_error,
            issues=issues,
            checked_dependencies=sorted(
                {
                    dependency
                    for report in reports
                    for dependency in report.checked_dependencies
                }
            ),
            tool_requests=[
                request for report in reports for request in report.tool_requests
            ],
            tool_results=[
                result for report in reports for result in report.tool_results
            ],
            failure_level=failure_level,
            confidence=max(0.0, min(1.0, confidence)),
            concise_feedback=" | ".join(feedback_parts)[:12000]
            or "Independent reports were aggregated conservatively.",
        )

    def _apply_local_attempt_integrity_guard(
        self,
        problem: ProblemContract,
        attempt: ProofAttempt,
        report: VerificationReport,
    ) -> None:
        self._apply_local_target_integrity_guard(problem, attempt, report)
        step_ids = {step.step_id for step in attempt.proof_steps}
        claim_ids = {claim.claim_id for claim in attempt.proposed_lemmas}
        known = step_ids | claim_ids
        missing: set[str] = set()
        for step in attempt.proof_steps:
            for dep in step.dependencies:
                if dep not in known and not dep.startswith("external:"):
                    missing.add(dep)
        if missing:
            report.issues.append(
                VerificationIssue(
                    phase="local_dependency_guard",
                    severity=Severity.ERROR,
                    description=f"Missing dependency IDs: {sorted(missing)}",
                    repair_hint="Add the missing derivation or replace the dependency with a verified claim ID.",
                )
            )
            report.failure_level = max(
                report.failure_level,
                FailureLevel.PLAN,
                key=self._failure_rank,
            )
            report.verdict = VerificationVerdict.FAIL
            report.concise_feedback = (
                "Deterministic dependency validation found missing IDs. "
                + report.concise_feedback
            )
        if attempt.status == AttemptStatus.COMPLETE and not attempt.final_answer:
            report.issues.append(
                VerificationIssue(
                    phase="local_completeness_guard",
                    severity=Severity.ERROR,
                    description="Attempt is marked complete but has no final_answer.",
                )
            )
            report.verdict = VerificationVerdict.FAIL

    def _apply_local_target_integrity_guard(
        self,
        problem: ProblemContract,
        target: ProofAttempt | FinalProof,
        report: VerificationReport,
    ) -> None:
        target_hash = target.problem_hash
        if target_hash != problem.integrity_hash:
            report.problem_integrity_ok = False
            report.failure_level = FailureLevel.STRATEGY
            report.issues.append(
                VerificationIssue(
                    phase="problem_integrity_guard",
                    severity=Severity.CRITICAL,
                    description=(
                        f"Target problem_hash={target_hash} does not match immutable hash="
                        f"{problem.integrity_hash}."
                    ),
                    repair_hint="Re-solve the exact original problem without modifying its statement.",
                )
            )
            report.verdict = VerificationVerdict.FAIL
            report.concise_feedback = (
                "Problem-integrity hash mismatch. " + report.concise_feedback
            )

    def _apply_deterministic_tool_guard(self, report: VerificationReport) -> None:
        for result in report.tool_results:
            if not result.ok or not isinstance(result.result, dict):
                continue
            payload = result.result
            refuted = (
                result.kind == "numeric_counterexample"
                and payload.get("counterexample_found") is True
            )
            typed_refuted = (
                result.kind
                in {
                    "modular_exhaustive",
                    "bounded_integer_search",
                    "recurrence_check",
                    "bounded_greedy_sequence",
                    "candidate_period_check",
                    "exact_geometry",
                }
                and payload.get("outcome") == "counterexample_found"
                and payload.get("independently_verified") is True
            )
            lean_rejected = (
                result.kind == "lean_check" and payload.get("accepted") is False
            )
            if refuted or typed_refuted or lean_rejected:
                report.issues.append(
                    VerificationIssue(
                        phase="deterministic_tool_guard",
                        severity=Severity.CRITICAL if refuted else Severity.ERROR,
                        description=(
                            "Verifier-requested deterministic check produced an independently confirmed counterexample."
                            if refuted or typed_refuted
                            else "Submitted Lean fragment was rejected by the configured checker."
                        ),
                        counterexample=str(
                            payload.get("assignment") or payload.get("counterexample")
                        )
                        if refuted or typed_refuted
                        else None,
                        repair_hint="Check the formalization mapping, then repair or remove the refuted inference.",
                    )
                )
                report.failure_level = max(
                    report.failure_level,
                    FailureLevel.EXECUTION,
                    key=self._failure_rank,
                )
                report.verdict = VerificationVerdict.FAIL
                if report.first_error_step is None:
                    report.first_error_step = "deterministic_tool_check"
                report.concise_feedback = (
                    "A deterministic check refuted a requested subclaim. "
                    + report.concise_feedback
                )

    def _apply_experiment_audit_guard(
        self,
        report: VerificationReport,
        audit_records: Sequence[dict[str, Any]],
    ) -> None:
        failed = [record for record in audit_records if not record.get("valid", False)]
        if not failed:
            return
        report.issues.append(
            VerificationIssue(
                phase="experiment_replay_guard",
                severity=Severity.CRITICAL,
                description=(
                    f"{len(failed)} experiment artifact(s) failed independent hash, tool-version, or deterministic replay validation."
                ),
                repair_hint=(
                    "Discard the affected evidence, rerun it with the pinned typed tool, and review its mathematical mapping again."
                ),
            )
        )
        report.failure_level = max(
            report.failure_level,
            FailureLevel.EXECUTION,
            key=self._failure_rank,
        )
        report.verdict = VerificationVerdict.FAIL
        report.confidence = 1.0
        if report.first_error_step is None:
            report.first_error_step = "experiment_replay_guard"
        report.concise_feedback = (
            "Independent experiment replay failed. " + report.concise_feedback
        )

    def _apply_experiment_counterexample_guard(
        self,
        target: ProofAttempt | ProofDelta | FinalProof,
        report: VerificationReport,
        experiments: Sequence[ExperimentResult],
    ) -> None:
        if isinstance(target, ProofAttempt):
            mathematical_text = "\n".join(
                [target.final_answer or ""]
                + [step.statement for step in target.proof_steps]
            )
        elif isinstance(target, ProofDelta):
            mathematical_text = "\n".join(
                [target.candidate_final_answer or ""]
                + [step.statement for step in target.new_steps]
            )
        else:
            mathematical_text = "\n".join(
                [target.answer] + [step.statement for step in target.proof_steps]
            )
        normalized_target = self._normalize_statement(mathematical_text).casefold()
        for experiment in experiments:
            if (
                experiment.outcome != ExperimentOutcome.COUNTEREXAMPLE_FOUND
                or experiment.evidence_strength != EvidenceStrength.COUNTEREXAMPLE
                or not experiment.independently_verified
            ):
                continue
            claim = self._normalize_statement(experiment.target_claim).casefold()
            if len(claim) < 8 or claim not in normalized_target:
                continue
            report.issues.append(
                VerificationIssue(
                    phase="experiment_counterexample_guard",
                    severity=Severity.CRITICAL,
                    description=(
                        "An independently rechecked experiment refutes a claim still used in the submitted proof."
                    ),
                    counterexample=str(experiment.counterexample),
                    repair_hint=(
                        "Remove or repair the refuted claim, then rebuild every dependent proof step."
                    ),
                )
            )
            report.failure_level = max(
                report.failure_level,
                FailureLevel.EXECUTION,
                key=self._failure_rank,
            )
            report.verdict = VerificationVerdict.FAIL
            if report.first_error_step is None:
                report.first_error_step = "experiment_counterexample"
            report.concise_feedback = (
                "A confirmed counterexample overrides the model verdict. "
                + report.concise_feedback
            )
            break

    @staticmethod
    def _has_deterministic_refutation(report: VerificationReport) -> bool:
        return any(
            result.ok
            and isinstance(result.result, dict)
            and (
                (
                    result.kind == "numeric_counterexample"
                    and result.result.get("counterexample_found") is True
                )
                or (
                    result.kind
                    in {
                        "modular_exhaustive",
                        "bounded_integer_search",
                        "recurrence_check",
                        "bounded_greedy_sequence",
                        "candidate_period_check",
                        "exact_geometry",
                    }
                    and result.result.get("outcome") == "counterexample_found"
                    and result.result.get("independently_verified") is True
                )
                or (
                    result.kind == "lean_check"
                    and result.result.get("accepted") is False
                )
            )
            for result in report.tool_results
        )

    def _normalize_report(
        self,
        report: VerificationReport,
        *,
        target_id: str,
        target_type: str,
        agent_id: str,
        stage: VerificationStage,
        raw_ref: str,
        usage: UsageRecord,
    ) -> None:
        report.target_id = target_id
        report.target_type = target_type  # type: ignore[assignment]
        report.agent_id = agent_id
        report.stage = stage
        report.raw_artifact_ref = raw_ref
        report.usage = usage
        if report.verdict == VerificationVerdict.FAIL and not report.issues:
            report.issues.append(
                VerificationIssue(
                    phase="normalization",
                    severity=Severity.ERROR,
                    description="Verifier returned FAIL without a concrete issue.",
                )
            )
        if (
            self.config.verification.require_first_error_step
            and report.verdict == VerificationVerdict.FAIL
            and report.first_error_step is None
        ):
            report.issues.append(
                VerificationIssue(
                    phase="verification_protocol",
                    severity=Severity.WARNING,
                    description="The verifier did not identify the first erroneous step.",
                )
            )

    def _record_verification_bundles(
        self,
        state: SolveState,
        bundles: Iterable[VerificationBundle],
    ) -> None:
        for bundle in bundles:
            state.reports.extend(bundle.reports)
            state.aggregate_reports[bundle.aggregate.target_id] = bundle.aggregate
            if state.capability_profile is None or state.triage is None:
                continue
            domain = state.capability_domain
            role_by_stage = {
                VerificationStage.STRUCTURAL: "structural_verifier",
                VerificationStage.DETAILED: "detailed_verifier",
                VerificationStage.FINAL: "detailed_verifier",
                VerificationStage.LEMMA: "route_referee",
            }
            for report in bundle.reports:
                if report.agent_id == "system-aggregate":
                    continue
                role = role_by_stage.get(report.stage, "detailed_verifier")
                state.capability_profile.update(
                    report.agent_id,
                    domain,
                    role,
                    kind="recent_task",
                    success=(report.verdict == bundle.aggregate.verdict),
                )

    def _select_for_synthesis(self, state: SolveState) -> list[ProofAttempt]:
        ranked = self._rank_attempts(state.attempts)
        passed = [
            attempt
            for attempt in ranked
            if attempt.status == AttemptStatus.COMPLETE and bool(attempt.proof_steps)
            if state.aggregate_reports.get(attempt.attempt_id)
            and state.aggregate_reports[attempt.attempt_id].verdict
            == VerificationVerdict.PASS
            and state.aggregate_reports[attempt.attempt_id].confidence
            >= self.config.budget.synthesis_threshold
        ]
        repairable_execution = (
            [] if passed else self._meta_selected_execution_repairs(state, ranked)
        )
        selected: list[ProofAttempt] = []
        for group in (passed, repairable_execution):
            for attempt in group:
                if attempt in selected:
                    continue
                selected.append(attempt)
                if len(selected) >= 3:
                    return selected
        return selected

    @staticmethod
    def _meta_selected_execution_repairs(
        state: SolveState,
        ranked: Sequence[ProofAttempt],
    ) -> list[ProofAttempt]:
        if not state.meta_reviews:
            return []
        review = state.meta_reviews[-1]
        if not review.can_synthesize or review.selected_target_id is None:
            return []
        assessment = next(
            (
                item
                for item in review.assessments
                if item.target_id == review.selected_target_id
            ),
            None,
        )
        if assessment is None or assessment.recommended_action != ActionKind.SYNTHESIZE:
            return []
        attempt = next(
            (item for item in ranked if item.attempt_id == review.selected_target_id),
            None,
        )
        if (
            attempt is None
            or attempt.status == AttemptStatus.FAILED
            or not attempt.proof_steps
        ):
            return []
        report = state.aggregate_reports.get(attempt.attempt_id)
        if (
            report is None
            or report.verdict != VerificationVerdict.FAIL
            or report.failure_level != FailureLevel.EXECUTION
        ):
            return []
        if report.first_error_step is not None:
            return []
        allowed_issue_phases = {"completeness", "verification_protocol"}
        if not any(issue.phase == "completeness" for issue in report.issues):
            return []
        if any(
            issue.phase not in allowed_issue_phases
            or issue.severity == Severity.CRITICAL
            for issue in report.issues
        ):
            return []
        # The source attempt remains failed. It is admitted only as repair material;
        # the synthesized proof must still pass the independent final audit.
        return [attempt]

    def _rank_attempts(self, attempts: Sequence[ProofAttempt]) -> list[ProofAttempt]:
        return sorted(
            attempts,
            key=lambda a: (
                self._attempt_local_quality(a),
                a.round_index,
                -a.usage.total_tokens,
            ),
            reverse=True,
        )

    def _attempt_local_quality(self, attempt: ProofAttempt) -> float:
        status_score = {
            AttemptStatus.COMPLETE: 0.36,
            AttemptStatus.PARTIAL: 0.18,
            AttemptStatus.FAILED: 0.0,
        }[attempt.status]
        step_score = min(0.24, 0.025 * len(attempt.proof_steps))
        key_steps = sum(1 for step in attempt.proof_steps if step.is_key_step)
        key_score = min(0.08, 0.02 * key_steps)
        lemma_score = min(0.08, 0.025 * len(attempt.proposed_lemmas))
        gap_penalty = min(0.25, 0.05 * len(attempt.unresolved_gaps))
        dead_end_penalty = min(0.12, 0.03 * len(attempt.dead_ends))
        return max(
            0.0,
            min(
                1.0,
                status_score
                + step_score
                + key_score
                + lemma_score
                - gap_penalty
                - dead_end_penalty,
            ),
        )

    def _targeted_feedback(self, attempt: ProofAttempt, state: SolveState) -> list[str]:
        feedback: list[str] = []
        report = state.aggregate_reports.get(attempt.attempt_id)
        if report is not None:
            if report.first_error_step:
                feedback.append(f"First disputed step: {report.first_error_step}")
            feedback.append(report.concise_feedback)
            feedback.extend(
                f"{issue.step_id or issue.claim_id or 'global'}: {issue.description}"
                for issue in report.issues[:8]
            )
        feedback.extend(f"Unresolved gap: {gap}" for gap in attempt.unresolved_gaps[:8])
        if state.meta_reviews:
            feedback.extend(state.meta_reviews[-1].required_actions[:6])
        return self._deduplicate_strings(feedback)

    def _apply_meta_route_controls(
        self,
        state: SolveState,
        review: MetaReview,
        current_round: int,
        store: ArtifactStore,
    ) -> None:
        registry = state.route_registry
        if registry is None:
            return
        attempts = {item.attempt_id: item for item in state.attempts}
        for assessment in review.assessments:
            if assessment.recommended_action != ActionKind.COOLDOWN_ROUTE:
                continue
            attempt = attempts.get(assessment.target_id)
            if attempt is None:
                continue
            route = registry.route_for_strategy(attempt.strategy_id)
            if route is None:
                continue
            requires_revision = review.failure_level == FailureLevel.STRATEGY
            reason = "; ".join(assessment.weaknesses[:3]) or review.summary
            registry.mark_cooling(
                route.route_id,
                current_round + self.config.scheduler.failed_path_cooldown_rounds,
                reason,
                requires_revision=requires_revision,
            )
            store.append_event(
                "meta_route_control_applied",
                {
                    "route_id": route.route_id,
                    "strategy_id": attempt.strategy_id,
                    "action": ActionKind.COOLDOWN_ROUTE.value,
                    "until_round": route.cooldown_until_round,
                    "requires_revision": requires_revision,
                    "reason": reason,
                },
            )

    def _local_meta_review(
        self,
        attempts: Sequence[ProofAttempt],
        reports: dict[str, VerificationReport],
    ) -> MetaReview:
        assessments: list[CandidateAssessment] = []
        for attempt in attempts:
            report = reports.get(attempt.attempt_id)
            verification_component = 0.0
            if report:
                verification_component = {
                    VerificationVerdict.PASS: 0.3 * report.confidence,
                    VerificationVerdict.UNCERTAIN: 0.1 * report.confidence,
                    VerificationVerdict.FAIL: -0.25 * report.confidence,
                    VerificationVerdict.SKIPPED: 0.0,
                }[report.verdict]
            score = max(
                0.0,
                min(1.0, self._attempt_local_quality(attempt) + verification_component),
            )
            if (
                report
                and report.verdict == VerificationVerdict.PASS
                and attempt.status == AttemptStatus.COMPLETE
            ):
                action = ActionKind.SYNTHESIZE
            elif (
                report
                and report.verdict == VerificationVerdict.FAIL
                and report.failure_level == FailureLevel.STRATEGY
            ):
                action = ActionKind.WIDEN
            else:
                action = ActionKind.DEEPEN
            assessments.append(
                CandidateAssessment(
                    target_id=attempt.attempt_id,
                    score=score,
                    strengths=[f"{len(attempt.proof_steps)} explicit proof steps"],
                    weaknesses=list(attempt.unresolved_gaps[:4]),
                    recommended_action=action,
                )
            )
        assessments.sort(key=lambda x: x.score, reverse=True)
        selected = assessments[0].target_id if assessments else None
        can_synthesize = bool(
            selected
            and any(
                a.attempt_id == selected and a.status == AttemptStatus.COMPLETE
                for a in attempts
            )
            and reports.get(selected)
            and reports[selected].verdict == VerificationVerdict.PASS
        )
        return MetaReview(
            selected_target_id=selected,
            assessments=assessments,
            shared_agreements=[],
            unresolved_conflicts=[],
            required_actions=[],
            failure_level=(
                reports[selected].failure_level
                if selected and selected in reports
                else FailureLevel.NONE
            ),
            can_synthesize=can_synthesize,
            confidence=assessments[0].score if assessments else 0.0,
            summary="Deterministic evidence-weighted fallback meta-review.",
        )

    def _fallback_strategy_set(
        self, problem: ProblemContract, count: int
    ) -> StrategySet:
        templates = [
            (
                "Direct invariant or monotonicity route",
                "Identify a quantity preserved or monotonically changed by the hypotheses, then derive the target directly.",
                "Invariant/ordering mechanism",
                [
                    "State the candidate invariant",
                    "Prove preservation",
                    "Connect it to the conclusion",
                ],
                "Finding an invariant strong enough to force the conclusion",
                "Test the proposed invariant on small and extremal instances.",
                ["invariant", "direct"],
            ),
            (
                "Extremal-minimal counterexample route",
                "Assume failure and choose a minimal or extremal counterexample; use its extremality to force a contradiction.",
                "Minimal-counterexample mechanism",
                [
                    "Existence of an extremal counterexample",
                    "Reduction preserving hypotheses",
                ],
                "Constructing a strictly smaller valid counterexample",
                "Check whether the reduction truly preserves every hypothesis.",
                ["extremal", "contradiction"],
            ),
            (
                "Structural decomposition route",
                "Decompose the object into independently checkable lemmas and solve the dependency DAG bottom-up.",
                "Lemma-DAG decomposition",
                ["Base structural lemma", "Compatibility lemma", "Assembly lemma"],
                "Avoiding circular dependencies between sublemmas",
                "Topologically sort dependencies and check each interface assumption.",
                ["decomposition", "dag"],
            ),
            (
                "Algebraic or analytic transformation route",
                "Transform the statement into an equivalent algebraic, generating-function, inequality, or analytic form.",
                "Representation-change mechanism",
                ["Prove equivalence of formulations", "Solve transformed statement"],
                "The transformation may lose boundary cases or introduce extra assumptions",
                "Verify both directions of equivalence and all domain restrictions.",
                ["algebra", "transformation"],
            ),
            (
                "Constructive algorithmic route",
                "Build the requested object or sequence explicitly and prove termination, feasibility, and optimality.",
                "Construction/algorithm mechanism",
                ["Construction", "Invariant", "Termination", "Optimality"],
                "Showing the construction handles every allowed input",
                "Run the construction on boundary cases and verify termination measure decreases.",
                ["construction", "algorithm"],
            ),
            (
                "Dual or complementary formulation route",
                "Pass to a complement, dual object, contrapositive, or equivalent game/value formulation where the obstruction is simpler.",
                "Duality/complement mechanism",
                ["Equivalence lemma", "Dual bound", "Transfer back"],
                "Proving exact equivalence rather than a one-way implication",
                "Check a small instance in both formulations and verify inverse mapping.",
                ["duality", "contrapositive"],
            ),
        ]
        strategies: list[StrategyCard] = []
        for index, template in enumerate(templates[: max(1, count)]):
            title, core, basis, lemmas, bottleneck, falsification, tags = template
            strategies.append(
                StrategyCard(
                    title=title,
                    core_idea=core,
                    independence_basis=basis,
                    expected_lemmas=lemmas,
                    bottleneck=bottleneck,
                    key_original_step=lemmas[-1] if lemmas else None,
                    falsification_test=falsification,
                    estimated_success=max(0.2, 0.55 - 0.04 * index),
                    estimated_cost=min(0.9, 0.45 + 0.05 * index),
                    tags=tags,
                )
            )
        return StrategySet(
            strategies=strategies,
            coverage_notes="Deterministic fallback spans invariant, extremal, decomposition, transformation, construction, and duality mechanisms.",
            omitted_directions=[],
        )

    def _fallback_triage(self) -> TriageResult:
        return TriageResult(
            problem_kind=ProblemKind.UNKNOWN,
            difficulty=Difficulty.HARD,
            key_risks=["unproved key step"],
            likely_tools=self._allowed_tools(),
            suggested_paths=self.config.budget.initial_paths,
            suggested_rounds=self.config.budget.max_rounds,
            proof_mode="hybrid",
            rationale="Fallback triage",
            confidence=0.2,
        )

    def _failed_attempt(
        self,
        problem: ProblemContract,
        strategy: StrategyCard,
        agent_id: str,
        round_index: int,
        error: Exception,
    ) -> ProofAttempt:
        return ProofAttempt(
            problem_hash=problem.integrity_hash,
            strategy_id=strategy.strategy_id,
            agent_id=agent_id,
            round_index=round_index,
            status=AttemptStatus.FAILED,
            dead_ends=[f"Agent execution failed: {type(error).__name__}: {error}"],
            unresolved_gaps=["No valid structured proof attempt was produced."],
            self_confidence=0.0,
        )

    def _synthetic_verification_failure(
        self,
        target_id: str,
        target_type: str,
        stage: VerificationStage,
        message: str,
        *,
        uncertain: bool = False,
    ) -> VerificationReport:
        verdict = (
            VerificationVerdict.UNCERTAIN if uncertain else VerificationVerdict.FAIL
        )
        issues = []
        if verdict == VerificationVerdict.FAIL:
            issues.append(
                VerificationIssue(
                    phase="orchestration",
                    severity=Severity.ERROR,
                    description=message,
                )
            )
        return VerificationReport(
            target_id=target_id,
            target_type=target_type,  # type: ignore[arg-type]
            agent_id="system-fallback",
            stage=stage,
            verdict=verdict,
            issues=issues,
            failure_level=FailureLevel.EXECUTION,
            confidence=0.25 if uncertain else 0.65,
            concise_feedback=message,
        )

    def _normalize_claims(
        self,
        claims: Sequence[ClaimCard],
        attempt: ProofAttempt,
        raw_ref: str | None,
    ) -> None:
        for claim in claims:
            claim.source_attempt_id = attempt.attempt_id
            claim.source_agent_id = attempt.agent_id
            # Extracted claims always enter as proposed. Only an aggregate verifier may upgrade them.
            claim.status = ClaimStatus.PROPOSED
            if raw_ref and not any(
                e.artifact_ref == raw_ref for e in claim.evidence_refs
            ):
                claim.evidence_refs.append(
                    EvidenceRef(
                        artifact_ref=raw_ref,
                        summary="Immutable raw source response for this claim.",
                    )
                )

    @staticmethod
    def _fit_json_items(
        items: Sequence[dict[str, Any]],
        *,
        max_chars: int,
        preserve_first: bool = True,
    ) -> list[dict[str, Any]]:
        """Keep whole typed packets under a soft character budget; never truncate JSON fields."""
        selected: list[dict[str, Any]] = []
        used = 0
        for index, item in enumerate(items):
            encoded = json.dumps(item, ensure_ascii=False, separators=(",", ":"))
            size = len(encoded)
            mandatory = preserve_first and index == 0
            if not mandatory and used + size > max_chars:
                continue
            selected.append(item)
            used += size
        return selected

    def _fit_attempt_contexts(
        self,
        attempts: Sequence[ProofAttempt],
        *,
        max_chars: int,
    ) -> list[dict[str, Any]]:
        """Retain the best path in full; add other paths as compact packets while budget permits."""
        if not attempts:
            return []
        packets = [self._attempt_context_dict(attempts[0], full=True)]
        packets.extend(self._attempt_context_dict(a, full=False) for a in attempts[1:])
        return self._fit_json_items(packets, max_chars=max_chars, preserve_first=True)

    def _select_claim_context(
        self,
        claims: Sequence[ClaimCard],
        query: str,
        *,
        max_chars: int,
    ) -> list[dict[str, Any]]:
        return select_legacy_claim_context(
            claims,
            query=query,
            max_chars=max_chars,
            max_items=self.config.topology.max_verified_claims_per_context,
        )

    def _admissible_fact_context(
        self,
        *,
        state: SolveState | None,
        legacy_memory: LemmaMemory,
        query: str,
        max_chars: int,
        purpose: ContextPurpose,
        required_refs: Sequence[str] = (),
    ) -> list[dict[str, Any]]:
        return build_admissible_fact_context(
            self.config,
            legacy_memory=legacy_memory,
            typed_memory=state.typed_memory if state is not None else None,
            message_broker=state.message_broker if state is not None else None,
            query=query,
            max_chars=max_chars,
            max_items=self.config.topology.max_verified_claims_per_context,
            purpose=purpose,
            required_refs=required_refs,
        )

    def _claim_dedup_index(self, claims: Sequence[ClaimCard]) -> list[dict[str, Any]]:
        """Minimal lossless-for-dedup index instead of rebroadcasting every full proof packet."""
        compact = [
            {
                "claim_id": claim.claim_id,
                "content_hash": claim.content_hash,
                "statement": claim.statement,
                "conclusion": claim.conclusion,
                "status": claim.status.value,
                "source_attempt_id": claim.source_attempt_id,
            }
            for claim in claims
        ]
        return self._fit_json_items(
            compact,
            max_chars=max(2000, self.config.topology.max_context_chars // 4),
            preserve_first=False,
        )

    def _attempt_context_dict(
        self, attempt: ProofAttempt, *, full: bool
    ) -> dict[str, Any]:
        data = attempt.model_dump(mode="json")
        if full:
            return data
        return {
            "attempt_id": data["attempt_id"],
            "problem_hash": data["problem_hash"],
            "strategy_id": data["strategy_id"],
            "agent_id": data["agent_id"],
            "round_index": data["round_index"],
            "status": data["status"],
            "final_answer": data["final_answer"],
            "proof_steps": [
                {
                    "step_id": step["step_id"],
                    "statement": step["statement"],
                    "justification": step["justification"],
                    "dependencies": step["dependencies"],
                    "is_key_step": step["is_key_step"],
                    "confidence": step["confidence"],
                }
                for step in data["proof_steps"]
            ],
            "unresolved_gaps": data["unresolved_gaps"],
            "dead_ends": data["dead_ends"],
            "raw_artifact_ref": data["raw_artifact_ref"],
        }

    def _update_agent_trust(
        self,
        attempt: ProofAttempt,
        reports: Sequence[VerificationReport],
        aggregate: VerificationReport,
        pool: AgentPool,
    ) -> None:
        try:
            prover = pool.get(attempt.agent_id)
            if aggregate.verdict == VerificationVerdict.PASS:
                prover.update_trust(0.03)
            elif aggregate.verdict == VerificationVerdict.FAIL:
                prover.update_trust(-0.03)
        except KeyError:
            pass
        for report in reports:
            if report.agent_id.startswith("system-"):
                continue
            try:
                reviewer = pool.get(report.agent_id)
            except KeyError:
                continue
            if aggregate.verdict == VerificationVerdict.UNCERTAIN:
                continue
            reviewer.update_trust(
                0.01 if report.verdict == aggregate.verdict else -0.015
            )

    def _has_synthesis_ready_candidate(self, state: SolveState) -> bool:
        return any(
            attempt.status == AttemptStatus.COMPLETE
            and bool(attempt.proof_steps)
            and state.aggregate_reports.get(attempt.attempt_id) is not None
            and state.aggregate_reports[attempt.attempt_id].verdict
            == VerificationVerdict.PASS
            and state.aggregate_reports[attempt.attempt_id].confidence
            >= self.config.budget.synthesis_threshold
            for attempt in state.attempts
        )

    def _can_enter_synthesis(self, state: SolveState) -> bool:
        if self._has_synthesis_ready_candidate(state):
            return True
        ranked = self._rank_attempts(state.attempts)
        return bool(self._meta_selected_execution_repairs(state, ranked))

    def _build_research_progress_report(
        self,
        problem: ProblemContract,
        state: SolveState,
        *,
        execution_note: str,
    ) -> ResearchProgressReport:
        reviewed = [
            attempt
            for attempt in state.attempts
            if attempt.proof_steps
            and (report := state.aggregate_reports.get(attempt.attempt_id)) is not None
            and report.verdict
            in {
                VerificationVerdict.PASS,
                VerificationVerdict.UNCERTAIN,
            }
        ]

        def evidence_rank(attempt: ProofAttempt) -> tuple[int, int, int, int, int]:
            report = state.aggregate_reports[attempt.attempt_id]
            verdict_rank = 2 if report.verdict == VerificationVerdict.PASS else 1
            route = (
                state.route_registry.route_for_strategy(attempt.strategy_id)
                if state.route_registry is not None
                else None
            )
            closed_obligations = (
                sum(
                    item.status == "closed" and route.route_id in item.route_ids
                    for item in state.proof_graph.obligations
                )
                if route is not None and state.proof_graph is not None
                else 0
            )
            independent_passes = sum(
                item.target_id == attempt.attempt_id
                and item.agent_id != attempt.agent_id
                and not item.agent_id.startswith("system-")
                and item.verdict == VerificationVerdict.PASS
                for item in state.reports
            )
            key_steps = sum(1 for step in attempt.proof_steps if step.is_key_step)
            return (
                verdict_rank,
                closed_obligations,
                independent_passes,
                len(attempt.proof_steps),
                key_steps,
            )

        reviewed.sort(key=evidence_rank, reverse=True)
        verified_attempts = [
            attempt
            for attempt in reviewed
            if state.aggregate_reports[attempt.attempt_id].verdict
            == VerificationVerdict.PASS
        ]
        verified_step_ids = [
            step.step_id
            for attempt in verified_attempts
            for step in attempt.proof_steps
        ]
        refuted_routes: list[dict[str, Any]] = []
        for attempt in state.attempts:
            report = state.aggregate_reports.get(attempt.attempt_id)
            if attempt.status != AttemptStatus.FAILED and not (
                report is not None and report.verdict == VerificationVerdict.FAIL
            ):
                continue
            refuted_routes.append(
                {
                    "attempt_id": attempt.attempt_id,
                    "strategy_id": attempt.strategy_id,
                    "failure_level": (
                        report.failure_level.value if report is not None else "unknown"
                    ),
                    "first_error_step": (
                        report.first_error_step if report is not None else None
                    ),
                    "dead_ends": list(attempt.dead_ends),
                }
            )
        open_obligations = (
            [
                {
                    "obligation_id": item.obligation_id,
                    "statement": item.statement,
                    "status": item.status,
                    "route_ids": item.route_ids,
                }
                for item in state.proof_graph.obligations
                if item.status != "closed"
            ]
            if state.proof_graph is not None
            else []
        )
        negative_evidence = (
            [item.statement for item in state.typed_memory.negatives]
            if state.typed_memory is not None
            else []
        )
        remaining_gaps = self._deduplicate_strings(
            [gap for attempt in reviewed for gap in attempt.unresolved_gaps]
            + [str(item["statement"]) for item in open_obligations]
        )
        zh = self.config.runtime.output_language.lower().startswith("zh")
        summary = (
            f"尚未建立完整证明。保留 {len(verified_attempts)} 条通过局部审查的路线、"
            f"{len(verified_step_ids)} 个已审查步骤、{len(refuted_routes)} 条失败路线，"
            f"以及 {len(open_obligations)} 个开放证明义务。"
            if zh
            else (
                "No complete proof was established. Preserved "
                f"{len(verified_attempts)} locally passed routes, "
                f"{len(verified_step_ids)} reviewed steps, {len(refuted_routes)} failed "
                f"routes, and {len(open_obligations)} open proof obligations."
            )
        )
        return ResearchProgressReport(
            problem_hash=problem.integrity_hash,
            valid_partial_attempt_ids=[item.attempt_id for item in reviewed],
            strongest_partial_attempt_id=(reviewed[0].attempt_id if reviewed else None),
            verified_step_ids=self._deduplicate_strings(verified_step_ids),
            verified_local_claim_ids=(
                [item.message_id for item in state.typed_memory.facts]
                if state.typed_memory is not None
                else []
            ),
            refuted_routes=refuted_routes,
            negative_evidence=self._deduplicate_strings(negative_evidence),
            open_obligations=open_obligations,
            remaining_gaps=remaining_gaps,
            execution_notes=[execution_note],
            summary=summary,
        )

    def _run_status(self, state: SolveState) -> RunStatus:
        if (
            state.final_verification is not None
            and state.final_verification.verdict == VerificationVerdict.PASS
            and state.final_verification.confidence
            >= self.config.budget.verification_pass_threshold
        ):
            return RunStatus.VERIFIED
        if state.budget_exhausted:
            return RunStatus.BUDGET_EXHAUSTED
        return RunStatus.UNVERIFIED

    def _build_result(
        self,
        run_id: str,
        status: RunStatus,
        problem: ProblemContract,
        state: SolveState,
        metrics: list[AgentMetric],
        runner: StructuredAgentRunner,
        pool: AgentPool,
        store: ArtifactStore,
        memory: LemmaMemory,
        *,
        summary_override: str | None = None,
    ) -> RunResult:
        total_usage = self._sum_usage([metric.usage for metric in metrics])
        summary = summary_override or self._result_summary(status, state)
        if status != RunStatus.VERIFIED and state.research_progress_report is None:
            state.research_progress_report = self._build_research_progress_report(
                problem,
                state,
                execution_note=summary,
            )
            store.write_json(
                "reports", "research_progress_report", state.research_progress_report
            )
        math_status = (
            MathStatus.VERIFIED if status == RunStatus.VERIFIED else state.math_status
        )
        execution_status = state.execution_status
        if status == RunStatus.BUDGET_EXHAUSTED:
            execution_status = ExecutionStatus.BUDGET_EXHAUSTED
        elif status == RunStatus.FAILED:
            execution_status = ExecutionStatus.FAILED
        return RunResult(
            run_id=run_id,
            status=status,
            math_status=math_status,
            execution_status=execution_status,
            problem=problem,
            final_proof=state.final_proof,
            final_verification=state.final_verification,
            research_progress_report=state.research_progress_report,
            attempts=state.attempts,
            claims=memory.claims,
            verification_reports=state.reports,
            meta_reviews=state.meta_reviews,
            proof_checkpoints=store.list_proof_checkpoints(),
            experiments=[
                ExperimentResult.model_validate(payload)
                for payload in store.list_experiment_results()
            ],
            resumed=state.resumed,
            resumed_from_checkpoint_id=state.resumed_from_checkpoint_id,
            agent_metrics=metrics,
            total_calls=runner.ledger.calls_started,
            total_usage=total_usage,
            run_directory=str(store.root),
            summary=summary,
        )

    def _result_summary(self, status: RunStatus, state: SolveState) -> str:
        zh = self.config.runtime.output_language.lower().startswith("zh")
        if status == RunStatus.VERIFIED:
            return (
                "最终证明已通过独立结构审查和逐步审查。"
                if zh
                else "A final proof passed independent structural and step-level verification under the configured threshold."
            )
        if status == RunStatus.UNVERIFIED:
            verdict = (
                state.final_verification.verdict.value
                if state.final_verification
                else "missing"
            )
            return (
                f"尚未建立完整可验证证明；最终审查状态为 {verdict}。请查看研究进展报告。"
                if zh
                else f"No complete verified proof was established; final verification is {verdict}. Inspect the research progress report."
            )
        if status == RunStatus.BUDGET_EXHAUSTED:
            return (
                "推理预算已耗尽；所有局部路线和证据均已保留。"
                if zh
                else "The configured inference budget was exhausted; all partial attempts and evidence were preserved."
            )
        if status == RunStatus.PAUSED_EXTERNAL_FAILURE:
            return (
                "外部模型服务中断，运行已暂停；数学状态保持 inconclusive。"
                if zh
                else "The external model service was interrupted; the run is paused and mathematical status remains inconclusive."
            )
        return (
            "运行异常终止；请查看结构化错误记录。"
            if zh
            else "The run failed; inspect the structured error record."
        )

    def _checkpoint(
        self,
        store: ArtifactStore,
        stage: str,
        state: SolveState,
        memory: LemmaMemory,
        runner: StructuredAgentRunner,
    ) -> None:
        if not self.config.runtime.checkpoint_every_stage:
            return
        store.checkpoint(
            stage,
            {
                "schema_version": "0.7",
                "triage": state.triage,
                "strategies": state.strategies,
                "attempts": state.attempts,
                "reports": state.reports,
                "aggregate_reports": state.aggregate_reports,
                "meta_reviews": state.meta_reviews,
                "proof_checkpoints": store.list_proof_checkpoints(),
                "experiments": store.list_experiment_results(),
                "resumed": state.resumed,
                "resumed_from_checkpoint_id": state.resumed_from_checkpoint_id,
                "final_proof": state.final_proof,
                "final_verification": state.final_verification,
                "budget_exhausted": state.budget_exhausted,
                "math_status": state.math_status,
                "execution_status": state.execution_status,
                "research_progress_report": state.research_progress_report,
                **export_hierarchical_checkpoint(
                    current_round=state.current_round,
                    graph_frozen=state.graph_frozen,
                    final_repair_failed=state.final_repair_failed,
                    proof_debt_history=state.proof_debt_history,
                    route_team_reviews=state.route_team_reviews,
                    capability_domain=state.capability_domain,
                    route_registry=state.route_registry,
                    typed_memory=state.typed_memory,
                    proof_graph=state.proof_graph,
                    message_broker=state.message_broker,
                    bridge_broker=state.bridge_broker,
                    contradiction_broker=state.contradiction_broker,
                    inspiration_engine=state.inspiration_engine,
                    capability_profile=state.capability_profile,
                    deep_exploration_registry=state.deep_exploration_registry,
                ),
                "claims": memory.claims,
                "calls_started": runner.ledger.calls_started,
                "stage_calls": runner.ledger.stage_calls,
                "bucket_calls": runner.ledger.bucket_calls,
                "reservation_calls": runner.ledger.reservation_calls,
                "agent_metrics": runner.pool.metrics(),
                "provider_circuit": runner.pool.provider_circuit_state(),
            },
        )
        if state.route_registry is not None:
            route_registry_state = state.route_registry.export_state()
            broker_state = (
                state.message_broker.export_state()
                if state.message_broker is not None
                else {}
            )
            graph_state = (
                state.proof_graph.export_state()
                if state.proof_graph is not None
                else {}
            )
            typed_memory_state = (
                state.typed_memory.export_state()
                if state.typed_memory is not None
                else {}
            )
            bridge_state = (
                state.bridge_broker.export_state()
                if state.bridge_broker is not None
                else {}
            )
            contradiction_state = (
                state.contradiction_broker.export_state()
                if state.contradiction_broker is not None
                else {}
            )
            inspiration_state = (
                state.inspiration_engine.export_state()
                if state.inspiration_engine is not None
                else {}
            )
            store.write_json("structured", "route_registry", route_registry_state)
            store.write_json("structured", "message_broker", broker_state)
            store.write_json("structured", "proof_graph", graph_state)
            store.write_json("structured", "typed_memory", typed_memory_state)
            store.write_json(
                "structured",
                "route_team_reviews",
                state.route_team_reviews or {},
            )
            store.write_json(
                "structured",
                "inspiration_engine",
                inspiration_state,
            )
            store.write_json(
                "structured",
                "message_receipts",
                broker_state.get("receipts", {}),
            )
            if state.deep_exploration_registry is not None:
                store.write_json(
                    "structured",
                    "deep_exploration_registry",
                    state.deep_exploration_registry.export_state(),
                )
            write_hierarchical_reports(
                store,
                route_registry=route_registry_state,
                message_broker=broker_state,
                proof_graph=graph_state,
                typed_memory=typed_memory_state,
                bridge_broker=bridge_state,
                contradiction_broker=contradiction_state,
                inspiration_engine=inspiration_state,
                deep_exploration=(
                    state.deep_exploration_registry.export_state()
                    if state.deep_exploration_registry is not None
                    else {}
                ),
                legacy_claims=[
                    claim.model_dump(mode="json") for claim in memory.claims
                ],
            )
        runner.persist_runtime_state()

    def _allowed_tools(self) -> list[str]:
        tools: list[str] = []
        if self.config.verification.enable_sympy_tools:
            tools.extend(["sympy_simplify", "sympy_equivalent", "polynomial_factor"])
        if self.config.verification.enable_numeric_counterexamples:
            tools.append("numeric_counterexample")
        if (
            self.config.computation.enabled
            and self.config.computation.typed_tools_enabled
        ):
            tools.extend(
                [
                    "modular_exhaustive",
                    "bounded_integer_search",
                    "graph_certificate",
                    "recurrence_check",
                    "bounded_greedy_sequence",
                    "candidate_period_check",
                    "exact_geometry",
                ]
            )
        if self.config.verification.enable_lean:
            tools.append("lean_check")
        return list(dict.fromkeys(tools))

    @staticmethod
    def _normalize_statement(text: str) -> str:
        return re.sub(r"\s+", " ", text).strip()

    @staticmethod
    def _make_run_id(problem_text: str) -> str:
        timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        return f"run_{timestamp}_{stable_hash(problem_text)[:10]}"

    @staticmethod
    def _failure_rank(level: FailureLevel) -> int:
        return {
            FailureLevel.NONE: 0,
            FailureLevel.EXECUTION: 1,
            FailureLevel.PLAN: 2,
            FailureLevel.STRATEGY: 3,
        }[level]

    @staticmethod
    def _sum_usage(usages: Iterable[UsageRecord]) -> UsageRecord:
        usage_list = list(usages)
        return UsageRecord(
            input_tokens=sum(u.input_tokens for u in usage_list),
            output_tokens=sum(u.output_tokens for u in usage_list),
            total_tokens=sum(u.total_tokens for u in usage_list),
            estimated_cost_usd=sum(u.estimated_cost_usd for u in usage_list),
            latency_ms=sum(u.latency_ms for u in usage_list),
        )

    @staticmethod
    def _deduplicate_strings(values: Iterable[str]) -> list[str]:
        seen: set[str] = set()
        result: list[str] = []
        for value in values:
            normalized = value.strip()
            if normalized and normalized not in seen:
                seen.add(normalized)
                result.append(normalized)
        return result
