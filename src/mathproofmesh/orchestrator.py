from __future__ import annotations

import asyncio
import json
import logging
import re
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any, Iterable, Literal, Mapping, Sequence, TypeVar

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
from .computation.contracts import validate_experiment_contract
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
from .critical_calculations import CriticalCalculationGate
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
from .goal_preflight import (
    ClarificationResolver,
    GoalClarificationRequired,
    GoalNormalizationError,
    decision_confidence,
    deterministic_goal_precheck,
    requires_confirmation,
    validate_clarification_decision,
)
from .prompts import PromptBundle, PromptFactory
from .report import write_hierarchical_reports, write_run_report
from .stall_recovery import (
    PostFailureBottleneckExtractor,
    classify_no_artifact_failure,
)
from .task_contracts import (
    apply_task_contract,
    assess_task_deliverables,
    deliverable_instructions,
    infer_task_requirements,
)
from .proof_graph.bridges import BridgeBroker
from .proof_graph.contradictions import ContradictionBroker
from .proof_graph.matching import DuplicateRouteDetector
from .proof_graph.store import ProofGraphStore
from .proof_control.controller import ProofControlLayer
from .proof_control.models import (
    AssumptionChallengeAction,
    AssumptionChallengeOutcome,
    AssumptionChallengeResult,
    ClaimVerificationState,
    ControlActionStatus,
    DependencyKind,
    GateVerdict,
    MetaPivotEffect,
    MetaPivotStatus,
    ProofRole,
    ResumeDecisionKind,
    RouteAdmissionRecord,
    TaskStatus as ProofControlTaskStatus,
    WakeConditionKind,
)
from .proof_control.semantic_view import build_problem_semantic_view, contains_cjk
from .proof_identity import (
    attempt_content_fingerprint,
    canonical_obligation_statement,
    checkpoint_math_fingerprint,
    is_feedback_only_statement,
    progress_signature,
)
from .schemas import (
    ActionKind,
    AgentMetric,
    AttemptStatus,
    BlindReviewPacket,
    BlindVerificationReport,
    BrokerDecision,
    CandidateAssessment,
    CandidateConjecture,
    CandidateConjectureBatch,
    CalculationGateVerdict,
    ClaimBatch,
    ClaimCard,
    ClaimStatus,
    ComputationContractRepair,
    ComputationContractRepairAction,
    ComputationContractRepairStatus,
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
    GoalClarificationRequest,
    InitialExplorationAction,
    InitialExplorationTurn,
    InspirationAssignmentPlan,
    InspirationMechanism,
    InspirationContextMode,
    InspirationProposal,
    InspirationReview,
    InspirationTask,
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
    RouteStatus,
    Severity,
    StrategyCard,
    StrategySet,
    TaskRequirement,
    TaskStatus,
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
from .verification.formal_microcert import formalization_coverage
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


def _reusable_claim_dependency_ids(claim: ClaimCard) -> list[str]:
    local_targets: set[str] = set()
    for value in claim.dependency_refs:
        kind = getattr(value, "kind", None)
        target_id = getattr(value, "target_id", None)
        if isinstance(value, dict):
            kind = value.get("kind")
            target_id = value.get("target_id")
        kind_value = getattr(kind, "value", kind)
        if (
            kind_value
            in {
                DependencyKind.LOCAL_STEP.value,
                DependencyKind.LOCAL_CLAIM.value,
            }
            and target_id
        ):
            local_targets.add(str(target_id))
    return [
        dependency
        for dependency in claim.dependencies
        if dependency not in local_targets
        and not dependency.startswith(("step:", "claim:"))
    ]


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
    proof_control: ProofControlLayer | None = None
    global_no_progress_rounds: int = 0
    global_meta_pivot_used: bool = False
    pivot_grace_used: bool = False
    hard_stopped: bool = False
    last_progress_signature: str | None = None
    certified_counterexample_hashes: list[str] = field(default_factory=list)
    termination_reason: str | None = None
    # Set when route admission rejected every candidate strategy. Carries
    # {"category": ..., "detail": ...}; a repairable systemic category stops
    # the adaptive loop from burning budget on widen/regeneration retries of
    # the same doomed pipeline and makes the final report say what actually
    # happened instead of disguising a gate failure as open mathematics.
    admission_starvation: dict[str, Any] | None = None


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
        clarification_resolver: ClarificationResolver | None = None,
    ) -> None:
        self.config = config
        self.mock_responders = mock_responders or {}
        self.activity_listener = activity_listener
        self.clarification_resolver = clarification_resolver
        self._budget_scaling_baseline = {
            "max_total_calls": config.budget.max_total_calls,
            "max_rounds": config.budget.max_rounds,
            "max_segments_per_path": config.continuation.max_segments_per_path,
        }

    async def solve(self, problem_text: str, *, run_id: str | None = None) -> RunResult:
        if not problem_text or not problem_text.strip():
            raise ValueError("problem_text must be non-empty")

        self._restore_baseline_budget_limits()
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
        memory = LemmaMemory(store)
        tools = ToolBroker(self.config, store, activity)

        store.write_json(
            "structured",
            "config_requested_redacted",
            self.config.redacted_dict(),
        )
        store.write_json("structured", "config_redacted", self.config.redacted_dict())
        run_activity_task = activity.start_task(
            "run",
            title=activity.text(
                "启动多 Agent 数学求解", "Starting the multi-agent mathematics run"
            ),
            detail=activity.text(
                "先执行本地题意预检；确认目标后才允许解题调用",
                "Running local goal preflight before any solving call",
            ),
            stage="run",
            importance=ActivityImportance.MAJOR,
            metrics={
                "agent_count": len(pool.agents),
                "original_statement_hash": stable_hash(problem_text.strip()),
            },
        )
        try:
            problem = await self._prepare_problem_contract(
                problem_text,
                runner=runner,
                prompts=prompts,
                store=store,
                activity=activity,
                parent_task_id=run_activity_task,
            )
        except BaseException:
            activity.finalize()
            await pool.aclose()
            raise

        store.write_json("structured", "problem_contract", problem)
        store.append_event(
            "run_started",
            {
                "problem_id": problem.problem_id,
                "problem_hash": problem.integrity_hash,
                "goal_hash": problem.goal_hash,
                "interpretation_source": problem.interpretation_source,
                "system_name": self.config.system_name,
            },
        )
        activity.update_task(
            run_activity_task,
            title=activity.text(
                "启动多 Agent 数学求解", "Starting the multi-agent mathematics run"
            ),
            detail=activity.text(
                f"已冻结规范化目标；启用 {len(pool.agents)} 个隔离子 Agent",
                f"Canonical goal frozen; {len(pool.agents)} isolated sub-agents are available",
            ),
            stage="run",
            importance=ActivityImportance.MAJOR,
            metrics={
                "agent_count": len(pool.agents),
                "problem_hash": problem.integrity_hash,
                "goal_hash": problem.goal_hash,
                "interpretation_source": problem.interpretation_source,
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
            apply_task_contract(problem, state.triage)
            self._attach_problem_semantic_view(problem, state.triage, store)
            store.write_json("structured", "problem_contract", problem)
            self._apply_difficulty_budget_scaling(state.triage, store)
            allocator = SoftBudgetAllocator(self.config, runner.ledger)
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
                    "task_requirements": [
                        item.value for item in problem.task_requirements
                    ],
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
                state=state,
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
            initial_attempts = self._record_attempts(state, initial_attempts, store)
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

            initial_task_status, _initial_deliverables = assess_task_deliverables(
                problem,
                state,
                store.list_experiment_results(),
                verification_threshold=self.config.budget.verification_pass_threshold,
            )
            early_finish_requirements = set(problem.task_requirements)
            if (
                initial_task_status == TaskStatus.COMPLETED
                and TaskRequirement.CONJECTURE in early_finish_requirements
                and early_finish_requirements
                <= {
                    TaskRequirement.COMPUTATION,
                    TaskRequirement.CONJECTURE,
                }
            ):
                state.execution_status = ExecutionStatus.COMPLETED
                self._checkpoint(
                    store,
                    "requested_deliverables_completed",
                    state,
                    memory,
                    runner,
                )
                result = self._build_result(
                    run_id,
                    RunStatus.COMPLETED,
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
                        "task_status": result.task_status.value,
                        "completion_reason": "requested_nonproof_deliverables_complete",
                        "total_calls": result.total_calls,
                        "total_tokens": result.total_usage.total_tokens,
                    },
                )
                activity.complete_task(
                    run_activity_task,
                    title=activity.text(
                        "用户要求的交付物已完成",
                        "Requested deliverables completed",
                    ),
                    detail=activity.text(
                        "定向计算与候选规律已保存；未把另行证明的义务当成本次失败",
                        "The computation and scoped conjecture were saved without treating a separate proof obligation as failure",
                    ),
                    event_type="run_completed",
                    stage="run",
                    importance=ActivityImportance.MAJOR,
                    metrics={
                        "status": result.status.value,
                        "task_status": result.task_status.value,
                        "total_calls": result.total_calls,
                        "total_tokens": result.total_usage.total_tokens,
                    },
                )
                activity.finalize()
                write_run_report(store, result)
                return result

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
            if state.last_progress_signature is None:
                state.last_progress_signature = self._global_progress_signature(state)

            # Adaptive breadth/depth loop. Actions are selected from verified progress,
            # novelty, uncertainty, gaps, stagnation, and protected future call reserves.
            consecutive_no_action_rounds = 0
            for round_index in range(1, self.config.budget.max_rounds):
                if (
                    state.admission_starvation is not None
                    and state.admission_starvation.get("category")
                    == "systemic_semantic_failure"
                ):
                    # Every route died on the same repairable control-plane
                    # failure. Widening re-runs the identical doomed pipeline,
                    # so stop here with an explicit reason instead.
                    store.append_event(
                        "adaptive_rounds_skipped_admission_starvation",
                        dict(state.admission_starvation),
                    )
                    break
                state.current_round = round_index
                self._reevaluate_route_wakes(
                    state,
                    current_round=round_index,
                    pool=pool,
                    runner=runner,
                )
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
                self._sync_hierarchical_artifacts(
                    state,
                    problem=problem,
                    memory=memory,
                    current_round=round_index,
                    store=store,
                )
                (
                    challenger_attempted,
                    challenger_performed,
                ) = await self._execute_pending_assumption_challengers(
                    state,
                    problem=problem,
                    store=store,
                    runner=runner,
                    prompts=prompts,
                    allocator=allocator,
                    router=router,
                    memory=memory,
                    tools=tools,
                )
                route_update_performed = await self._run_scheduled_route_updates(
                    state,
                    problem=problem,
                    current_round=round_index,
                    runner=runner,
                    prompts=prompts,
                )
                if challenger_attempted:
                    pivot_attempted, pivot_performed = False, False
                else:
                    (
                        pivot_attempted,
                        pivot_performed,
                    ) = await self._execute_pending_meta_pivot(
                        state,
                        problem=problem,
                        store=store,
                        runner=runner,
                        prompts=prompts,
                        allocator=allocator,
                        router=router,
                        memory=memory,
                        tools=tools,
                    )
                if self._has_synthesis_ready_candidate(state):
                    # A supported candidate exists; preserve calls for synthesis/final audit.
                    if allocator.should_protect_finish(
                        state.aggregate_reports.values()
                    ) and self._proof_control_allows_synthesis(state):
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

                graph_signals = self._hierarchical_graph_signals(state)
                if not pivot_attempted and not challenger_attempted:
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
                    route_team_reviews=state.route_team_reviews,
                    meta_review=state.meta_reviews[-1] if state.meta_reviews else None,
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

                performed = (
                    challenger_performed or route_update_performed or pivot_performed
                )
                action_calls_started = runner.ledger.calls_started
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
                        control_route_id = self._route_for_strategy(
                            state, action.strategy_id
                        )
                        if (
                            state.proof_control is not None
                            and control_route_id is not None
                            and not state.proof_control.deepening_currently_allowed(
                                control_route_id
                            )
                        ):
                            action.selected = False
                            action.blocked_reason = (
                                "proof-control Continue Gate requires a route "
                                "rewrite before further deepening"
                            )
                            store.append_event(
                                "adaptive_action_blocked",
                                {
                                    "round_index": round_index,
                                    "action": action.action.value,
                                    "strategy_id": action.strategy_id,
                                    "reason": action.blocked_reason,
                                },
                            )
                            continue
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
                            accepted_attempts = self._record_attempts(
                                state, [attempt], store
                            )
                            if accepted_attempts:
                                await self._extract_claims_many(
                                    problem,
                                    accepted_attempts,
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
                            accepted_attempts = self._record_attempts(
                                state, new_attempts, store
                            )
                            if accepted_attempts:
                                await self._extract_claims_many(
                                    problem,
                                    accepted_attempts,
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

                if (
                    not performed
                    and runner.ledger.calls_started == action_calls_started
                ):
                    fallback_deepen = next(
                        (
                            candidate
                            for candidate in decision.candidates
                            if candidate.action == ActionKind.DEEPEN
                            and candidate.eligible
                            and candidate.strategy_id is not None
                            and not candidate.selected
                        ),
                        None,
                    )
                    if fallback_deepen is not None:
                        estimated_cost = (
                            fallback_deepen.estimated_calls
                            or allocator.estimate_action_calls(
                                ActionKind.DEEPEN,
                                current_path_count=len(state.strategies),
                            )
                        )
                        blocked_reason = allocator.spend_block_reason(
                            allocator.bucket_for_action(ActionKind.DEEPEN),
                            estimated_cost,
                            protect_finish=True,
                            has_candidate=bool(state.attempts),
                        )
                        control_route_id = self._route_for_strategy(
                            state, fallback_deepen.strategy_id
                        )
                        control_allows_deepening = not (
                            state.proof_control is not None
                            and control_route_id is not None
                            and not state.proof_control.deepening_currently_allowed(
                                control_route_id
                            )
                        )
                        if blocked_reason is None and control_allows_deepening:
                            store.append_event(
                                "adaptive_noop_action_backfilled",
                                {
                                    "round_index": round_index,
                                    "action": ActionKind.DEEPEN.value,
                                    "strategy_id": fallback_deepen.strategy_id,
                                    "target_id": fallback_deepen.target_id,
                                    "reason": (
                                        "selected control actions produced no "
                                        "material state change while an "
                                        "evidence-backed route remained executable"
                                    ),
                                },
                            )
                            attempt = await self._deepen_path(
                                problem,
                                fallback_deepen.strategy_id,
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
                                accepted_attempts = self._record_attempts(
                                    state, [attempt], store
                                )
                                if accepted_attempts:
                                    await self._extract_claims_many(
                                        problem,
                                        accepted_attempts,
                                        runner,
                                        prompts,
                                        memory,
                                        store,
                                        budget_bucket="depth",
                                    )
                                    performed = True

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
                performed = (
                    await self._run_scheduled_route_updates(
                        state,
                        problem=problem,
                        current_round=round_index,
                        runner=runner,
                        prompts=prompts,
                    )
                    or performed
                )
                hard_stagnation_stop = self._apply_global_progress_gate(
                    state,
                    round_index=round_index,
                    store=store,
                    activity=activity,
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
                pivot_blocks_stop = (
                    state.proof_control is not None
                    and state.proof_control.active
                    and (
                        state.proof_control.meta_pivot_blocks_stagnation_stop()
                        or state.proof_control.common_mode_blocks_stagnation_stop()
                    )
                )
                if performed:
                    consecutive_no_action_rounds = 0
                elif not pivot_blocks_stop:
                    consecutive_no_action_rounds += 1
                # A single empty round (for example a widen whose candidates
                # were all similarity-filtered) must not abandon the whole
                # remaining budget; require two consecutive empty rounds.
                if hard_stagnation_stop or consecutive_no_action_rounds >= 2:
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
            if self._can_enter_synthesis(
                state
            ) and self._proof_control_allows_synthesis(state):
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
            status = self._run_status(problem, state, store)
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

    async def resume(
        self,
        run_id: str,
        *,
        intervention: str | None = None,
    ) -> RunResult:
        """Resume a stopped run from persisted stage state and verified proof checkpoints."""
        if not self.config.continuation.process_resume_enabled:
            raise RuntimeError(
                "process resume is disabled by continuation.process_resume_enabled"
            )
        self._restore_baseline_budget_limits()
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
        store.write_json(
            "structured",
            "config_resume_requested_redacted",
            self.config.redacted_dict(),
        )
        resume_triage_payload = payload.get("triage")
        if isinstance(resume_triage_payload, dict):
            self._apply_difficulty_budget_scaling(
                TriageResult.model_validate(resume_triage_payload),
                store,
            )
        else:
            store.write_json(
                "structured",
                "config_redacted",
                self.config.redacted_dict(),
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
        apply_task_contract(problem, state.triage)
        store.write_json("structured", "problem_contract", problem)
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
        # lemma_memory is persisted on every mutation and is therefore the
        # freshest source; the stage-checkpoint snapshot only fills gaps.
        # Loading the stale snapshot first let REJECTED claims resurrect as
        # VERIFIED, because content-hash dedup keeps the first status seen.
        persisted_claims: list[Any] = []
        lemma_runtime_payload = (
            store.read_named_json("structured", "lemma_memory_runtime")
            if store.has_named_json("structured", "lemma_memory_runtime")
            else {}
        )
        if store.has_named_json("structured", "lemma_memory"):
            lemma_payload = store.read_named_json("structured", "lemma_memory")
            if isinstance(lemma_payload, list):
                persisted_claims.extend(lemma_payload)
        persisted_claims.extend(payload.get("claims", []))
        memory.add_many([ClaimCard.model_validate(item) for item in persisted_claims])
        memory.restore_runtime_state(
            lemma_runtime_payload if isinstance(lemma_runtime_payload, dict) else {}
        )
        active_checkpoints = store.list_proof_checkpoints()
        if active_checkpoints:
            memory.replace_committed_step_ids(
                step.step_id
                for active_checkpoint in active_checkpoints
                for step in active_checkpoint.verified_steps
            )
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
        stale_capacity = dict(runtime_payload.get("capacity_reservations") or {})
        # Capacity belonged to an in-process call/recovery pair. No private call
        # can be resumed after a process restart, so stale capacity is released.
        runner.ledger.capacity_reservations = {}
        if stale_capacity:
            store.append_event(
                "artifact_recovery_capacity_released_on_resume",
                {"reservation_count": len(stale_capacity)},
            )
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

            if state.proof_control is not None and state.proof_control.active:
                current_config_hash = stable_hash(self.config.model_dump(mode="json"))
                prior_decisions = list(
                    state.proof_control.state.resume_decisions.values()
                )
                prior_decision = (
                    sorted(
                        prior_decisions,
                        key=lambda item: item.decision_id,
                    )[-1]
                    if prior_decisions
                    else None
                )
                prior_config_hash = str(
                    payload.get("proof_control_config_hash")
                    or (
                        prior_decision.config_hash
                        if prior_decision is not None
                        else current_config_hash
                    )
                )
                prior_goal_hash = str(
                    payload.get("proof_control_goal_hash")
                    or (
                        prior_decision.goal_hash
                        if prior_decision is not None
                        else problem.goal_hash
                    )
                )
                self._reevaluate_route_wakes(
                    state,
                    current_round=state.current_round + 1,
                    pool=pool,
                    runner=runner,
                    user_intervention=intervention is not None,
                    config_changed=prior_config_hash != current_config_hash,
                )
                routes = (
                    state.route_registry.routes
                    if state.route_registry is not None
                    else []
                )
                inferred_legacy_hard_stop = bool(
                    routes
                    and all(
                        route.status
                        in {
                            RouteStatus.WAITING,
                            RouteStatus.FROZEN,
                            RouteStatus.FROZEN_STALLED,
                            RouteStatus.TERMINAL,
                            RouteStatus.REFUTED,
                            RouteStatus.MERGED,
                            RouteStatus.ABANDONED,
                            RouteStatus.COMPLETED,
                        }
                        for route in routes
                    )
                    and state.global_no_progress_rounds
                    >= self.config.scheduler.global_no_progress_rounds_before_stop
                )
                hard_stopped = state.hard_stopped or inferred_legacy_hard_stop
                pending_action_ids = [
                    action.action_id
                    for action in state.proof_control.state.control_actions.values()
                    if action.status
                    in {
                        ControlActionStatus.PROPOSED,
                        ControlActionStatus.ADMITTED,
                        ControlActionStatus.EXECUTING,
                    }
                ]
                checkpoint_state = {
                    "current_round": state.current_round,
                    "hard_stopped": hard_stopped,
                    "global_no_progress_rounds": state.global_no_progress_rounds,
                    "mathematical_progress_signature": (state.last_progress_signature),
                    "route_statuses": {
                        route.route_id: route.status.value for route in routes
                    },
                }
                decision = state.proof_control.plan_resume(
                    checkpoint_state=checkpoint_state,
                    config_hash=current_config_hash,
                    goal_hash=problem.goal_hash,
                    hard_stopped=hard_stopped,
                    pending_action_ids=pending_action_ids,
                    terminal_stagnation_signature=state.last_progress_signature,
                    prior_state_hash=(
                        prior_decision.state_hash
                        if prior_decision is not None
                        else None
                    ),
                    prior_config_hash=prior_config_hash,
                    prior_goal_hash=prior_goal_hash,
                    prior_terminal_stagnation_signature=(
                        prior_decision.terminal_stagnation_signature
                        if prior_decision is not None
                        else state.last_progress_signature
                    ),
                    intervention=intervention,
                )
                store.append_event(
                    "run_resume_policy_decided",
                    decision.model_dump(mode="json"),
                )
                if decision.decision == ResumeDecisionKind.NO_RESUMABLE_WORK:
                    state.hard_stopped = True
                    state.research_progress_report = self._build_research_progress_report(
                        problem,
                        state,
                        execution_note=(
                            "硬停止检查点没有待执行动作、可唤醒任务或显式干预；"
                            "本次恢复未调用模型。"
                            if self.config.runtime.output_language.lower().startswith(
                                "zh"
                            )
                            else (
                                "The hard-stopped checkpoint has no pending "
                                "action, wakeable task, or explicit intervention; "
                                "resume made no model calls."
                            )
                        ),
                    )
                    store.write_json(
                        "reports",
                        "research_progress_report",
                        state.research_progress_report,
                    )
                    self._checkpoint(
                        store,
                        "resume_no_resumable_work",
                        state,
                        memory,
                        runner,
                    )
                    state.checkpoints = store.list_proof_checkpoints()
                    result = self._build_result(
                        run_id,
                        self._run_status(problem, state, store),
                        problem,
                        state,
                        pool.metrics(),
                        runner,
                        pool,
                        store,
                        memory,
                        summary_override=(
                            "硬停止状态没有新增可执行工作；已原样返回研究进展，"
                            "模型调用数未增加。"
                            if self.config.runtime.output_language.lower().startswith(
                                "zh"
                            )
                            else (
                                "No new executable work was available after the "
                                "hard stop; research progress was returned without "
                                "additional model calls."
                            )
                        ),
                    )
                    store.write_json("structured", "run_result", result)
                    activity.complete_task(
                        run_task,
                        title=activity.text(
                            "无可恢复工作，未调用模型",
                            "No resumable work; no model calls made",
                        ),
                        detail=decision.reason,
                        stage="run_resume",
                        importance=ActivityImportance.MAJOR,
                        metrics={
                            "resume_decision_id": decision.decision_id,
                            "no_new_model_calls": True,
                            "total_calls": result.total_calls,
                        },
                    )
                    activity.finalize()
                    write_run_report(store, result)
                    return result
                state.hard_stopped = False
                if intervention is not None:
                    state.proof_control.reopen_frozen_routes(
                        intervention=intervention,
                        current_round=state.current_round + 1,
                    )
                    if intervention.strip().casefold().replace("-", "_") == (
                        "reset_stagnation"
                    ):
                        state.global_no_progress_rounds = 0
                        state.global_meta_pivot_used = False
                    elif intervention.strip().casefold().replace("-", "_") == (
                        "reopen_with_pivot"
                    ):
                        state.proof_control.request_meta_pivot(
                            source_stagnation_signature=(
                                state.last_progress_signature
                                or stable_hash(checkpoint_state)
                            ),
                            trigger_round=state.current_round + 1,
                            requested_mechanisms=[
                                "meta_replan",
                                "representation_switch",
                                "auxiliary_construction",
                            ],
                        )

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
                    state=state,
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
            if state.last_progress_signature is None:
                state.last_progress_signature = self._global_progress_signature(state)
            resume_start_round = state.current_round + 1
            resume_no_action_rounds = 0
            for resume_offset in range(max_resume_rounds):
                resume_round = resume_start_round + resume_offset
                state.current_round = resume_round
                self._reevaluate_route_wakes(
                    state,
                    current_round=resume_round,
                    pool=pool,
                    runner=runner,
                )
                route_update_performed = await self._run_scheduled_route_updates(
                    state,
                    problem=problem,
                    current_round=resume_round,
                    runner=runner,
                    prompts=prompts,
                )
                (
                    pivot_attempted,
                    pivot_performed,
                ) = await self._execute_pending_meta_pivot(
                    state,
                    problem=problem,
                    store=store,
                    runner=runner,
                    prompts=prompts,
                    allocator=allocator,
                    router=router,
                    memory=memory,
                    tools=tools,
                )
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
                    route = (
                        state.route_registry.route_for_strategy(strategy.strategy_id)
                        if state.route_registry is not None
                        else None
                    )
                    if route is not None and route.status not in {
                        RouteStatus.ACTIVE,
                        RouteStatus.REPAIR_ONCE,
                    }:
                        continue
                    if (
                        route is not None
                        and state.proof_control is not None
                        and not state.proof_control.deepening_currently_allowed(
                            route.route_id
                        )
                    ):
                        store.append_event(
                            "resume_deepening_blocked",
                            {
                                "route_id": route.route_id,
                                "strategy_id": strategy.strategy_id,
                                "reason": (
                                    "proof-control Continue Gate requires "
                                    "blueprint revision"
                                ),
                            },
                        )
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
                    updated_attempts.extend(
                        self._record_attempts(state, [updated], store)
                    )

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
                route_update_performed = (
                    await self._run_scheduled_route_updates(
                        state,
                        problem=problem,
                        current_round=resume_round,
                        runner=runner,
                        prompts=prompts,
                    )
                    or route_update_performed
                )
                if not pivot_attempted:
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
                hard_stagnation_stop = self._apply_global_progress_gate(
                    state,
                    round_index=resume_round,
                    store=store,
                    activity=activity,
                )
                self._checkpoint(
                    store,
                    f"resume_round_{resume_round}",
                    state,
                    memory,
                    runner,
                )
                if self._has_synthesis_ready_candidate(state):
                    break
                pivot_blocks_stop = (
                    state.proof_control is not None
                    and state.proof_control.active
                    and state.proof_control.meta_pivot_blocks_stagnation_stop()
                )
                round_performed = bool(
                    updated_attempts or route_update_performed or pivot_performed
                )
                if round_performed:
                    resume_no_action_rounds = 0
                elif not pivot_blocks_stop:
                    resume_no_action_rounds += 1
                if hard_stagnation_stop or resume_no_action_rounds >= 2:
                    break

            if (
                state.proof_graph is not None
                and self.config.topology.final_stage.freeze_graph_before_synthesis
                and not state.proof_graph.frozen
            ):
                state.proof_graph.freeze()
                state.graph_frozen = True
            if self._can_enter_synthesis(
                state
            ) and self._proof_control_allows_synthesis(state):
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
            status = self._run_status(problem, state, store)
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
        deep_policy = self.config.deep_exploration_policy.model_copy(
            update={
                "no_progress_high_tier_limit_per_signature": (
                    self.config.scheduler.max_normal_attempts_per_signature
                ),
                "max_partial_repairs_per_signature": (
                    self.config.scheduler.max_repair_attempts_per_signature
                ),
            }
        )
        state.deep_exploration_registry = (
            DeepExplorationRegistry.from_state(
                deep_state,
                deep_policy,
                problem_hash=problem.integrity_hash,
            )
            if self.config.deep_exploration_policy.enabled
            and isinstance(deep_state, dict)
            else (
                DeepExplorationRegistry(
                    deep_policy,
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
        proof_control_config = self.config.topology.proof_control
        if proof_control_config.enabled and proof_control_config.mode != "off":
            if (
                not state.proof_graph.main_goal_obligation_ids()
                and not state.proof_graph.frozen
            ):
                state.proof_graph.add_obligation(
                    ProofObligation(
                        obligation_id=f"obl_main_{problem.goal_hash[:12]}",
                        problem_hash=problem.integrity_hash,
                        route_ids=[],
                        kind=ObligationKind.MAIN_GOAL,
                        statement=problem.exact_statement,
                        normalized_statement=self._normalize_statement(
                            problem.exact_statement
                        ),
                        assumptions=list(problem.hard_constraints),
                        status="open",
                        priority=1.0,
                        centrality=1.0,
                    )
                )
            control_state = payload.get("proof_control_state")
            state.proof_control = ProofControlLayer.from_state(
                control_state if isinstance(control_state, dict) else None,
                config=self.config,
                store=store,
                activity=activity,
                proof_graph=state.proof_graph,
                typed_memory=state.typed_memory,
                message_broker=state.message_broker,
                route_registry=state.route_registry,
            )
            for obligation in state.proof_graph.obligations:
                state.proof_control.register_obligation(obligation)
            if checkpoint_payload is not None and not isinstance(control_state, dict):
                migration = {
                    "event_type": "checkpoint_migrated_to_v0_8",
                    "payload": {
                        "initialized_empty_component": "proof_control_state",
                        "source_schema_version": str(
                            payload.get("schema_version", "0.7")
                        ),
                    },
                }
                state.proof_control.state.events.append(migration)
                store.append_event("checkpoint_migrated_to_v0_8", migration["payload"])
                activity.info(
                    "checkpoint_migrated_to_v0_8",
                    title="Checkpoint migrated to v0.8",
                    detail=(
                        "Missing proof-control sidecar state was initialized "
                        "without changing mathematical artifacts."
                    ),
                    stage="run_resume",
                )
            state.proof_control.persist()
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
            project_root=self.config.runtime.project_root,
        )
        inspiration_state = payload.get("inspiration_engine")
        if isinstance(inspiration_state, dict):
            state.inspiration_engine.restore_state(inspiration_state)
        state.inspiration_engine.proof_control_context_provider = (
            state.proof_control.inspiration_context
            if state.proof_control is not None
            else None
        )

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

        if state.proof_control is not None and state.proof_graph is not None:
            route_ids = [item.route_id for item in registry.routes]
            for main_goal_id in state.proof_graph.main_goal_obligation_ids():
                main_goal = state.proof_graph.get_obligation(main_goal_id)
                main_goal.route_ids = list(
                    dict.fromkeys([*main_goal.route_ids, *route_ids])
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
            "proof_control": state.proof_control,
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
            if state.proof_control is not None:
                state.proof_control.register_claim(claim, route_id=route_id)
            if not registry.owns_agent(route_id, attempt.agent_id, RouteRole.PROVER):
                try:
                    registry.assign_member(
                        route_id, attempt.agent_id, RouteRole.PROVER, current_round
                    )
                except ValueError:
                    continue
            report = state.aggregate_reports.get(attempt.attempt_id)
            team_reviews = (state.route_team_reviews or {}).get(attempt.attempt_id, [])
            scoped_team_reviews = (
                [
                    review
                    for review in team_reviews
                    if review.get("delta_id") == claim.source_delta_id
                ]
                if claim.source_delta_id is not None
                else team_reviews
            )
            team_review = scoped_team_reviews[-1] if scoped_team_reviews else None
            teams_enabled = self.config.topology.route_teams.enabled
            team_global_allowed = team_reviews_allow_global_share(
                team_reviews,
                teams_enabled=teams_enabled,
                delta_id=claim.source_delta_id,
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
            ledger_entry = (
                state.proof_control.state.claim_verification_ledger.get(claim.claim_id)
                if state.proof_control is not None
                else None
            )
            referee_ledger_allows_fact = bool(
                ledger_entry is not None
                and ledger_entry.referee_review_ids
                and ledger_entry.state
                in {
                    ClaimVerificationState.REFEREE_ACCEPTED,
                    ClaimVerificationState.FACT_CANDIDATE,
                    ClaimVerificationState.FACT,
                }
            )
            proof_control_requires_referee_ledger = bool(
                state.proof_control is not None and state.proof_control.active
            )
            claim_fact_risk_free = bool(
                not proof_control_requires_referee_ledger
                or claim.status != ClaimStatus.VERIFIED
                or (
                    state.proof_control is not None
                    and state.proof_control.claim_fact_promotion_allowed(
                        claim,
                        route_id=route_id,
                    )
                )
            )
            if (
                claim.status == ClaimStatus.VERIFIED
                and referee_id is not None
                and team_global_allowed
                and claim_fact_risk_free
                and (
                    not proof_control_requires_referee_ledger
                    or referee_ledger_allows_fact
                )
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
                dependencies=(
                    _reusable_claim_dependency_ids(claim)
                    if proof_control_requires_referee_ledger
                    else claim.dependencies
                ),
                dependency_refs=claim.dependency_refs,
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
            if (
                tier == MemoryTier.FACT
                and proof_control_requires_referee_ledger
                and state.proof_control is not None
                and ledger_entry is not None
                and ledger_entry.state == ClaimVerificationState.REFEREE_ACCEPTED
            ):
                ledger_entry = (
                    state.proof_control.claim_lifecycle.promote_fact_candidate(
                        claim.claim_id
                    )
                )
            publication = broker.publish(
                message,
                referee_agent_id=referee_id,
                current_round=current_round,
            )
            if (
                publication.accepted
                and tier == MemoryTier.FACT
                and state.proof_control is not None
                and ledger_entry is not None
                and ledger_entry.state == ClaimVerificationState.FACT_CANDIDATE
            ):
                state.proof_control.claim_lifecycle.mark_fact(
                    claim.claim_id,
                    evidence_ids=[publication.duplicate_of or message.message_id],
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
                evidence_message_id = publication.duplicate_of or message.message_id
                closed_obligation_ids = [
                    item.obligation_id
                    for item in graph.obligations
                    if evidence_message_id in item.evidence_message_ids
                ]
                state.inspiration_engine.attribute_verified_fact(
                    evidence_message_id,
                    source_route_id=route_id,
                    closed_obligation_ids=closed_obligation_ids,
                    dependency_message_ids=message.dependencies,
                    direct_proposal_ids=(
                        [proposal_id]
                        if proposal_id in state.inspiration_engine.proposals
                        else []
                    ),
                )
        for attempt in state.attempts:
            route_id = self._route_for_strategy(state, attempt.strategy_id)
            if route_id is None or not registry.owns_agent(
                route_id, attempt.agent_id, RouteRole.PROVER
            ):
                continue
            for gap in attempt.unresolved_gaps:
                if is_feedback_only_statement(gap):
                    continue
                canonical_gap = canonical_obligation_statement(gap)
                if not canonical_gap:
                    continue
                gap_hash = stable_hash(
                    (
                        problem.integrity_hash,
                        route_id,
                        self._normalize_statement(canonical_gap),
                    )
                )
                message = MessageEnvelope(
                    message_id=f"msg_gap_{gap_hash[:12]}",
                    problem_hash=problem.integrity_hash,
                    source_agent_id=attempt.agent_id,
                    source_route_id=route_id,
                    source_role=RouteRole.PROVER,
                    message_type=MessageType.PROOF_OBLIGATION,
                    statement=canonical_gap,
                    normalized_statement=self._normalize_statement(canonical_gap),
                    conclusion=canonical_gap,
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
        if state.proof_control is not None:
            state.proof_control.update_after_round(
                strategies=state.strategies,
                attempts=state.attempts,
                reports=state.reports,
                current_round=current_round,
            )

    async def _run_scheduled_route_updates(
        self,
        state: SolveState,
        *,
        problem: ProblemContract,
        current_round: int,
        runner: StructuredAgentRunner,
        prompts: PromptFactory,
    ) -> bool:
        control = state.proof_control
        broker = state.message_broker
        registry = state.route_registry
        if control is None or not control.active or broker is None or registry is None:
            return False

        performed = False
        for task in control.pending_route_update_tasks():
            try:
                route = registry.get(task.target_route_id)
            except KeyError:
                control.fail_route_update_task(
                    task.task_id,
                    reason="target route is no longer registered",
                    current_round=current_round,
                )
                continue
            agents: list[AgentRuntime] = []
            for member in route.members:
                if member.role != RouteRole.PROVER:
                    continue
                try:
                    agent = runner.pool.get(member.agent_id)
                except KeyError:
                    continue
                if not agent.in_cooldown:
                    agents.append(agent)
            if not agents:
                control.defer_route_update_task(
                    task.task_id,
                    reason="target route has no available prover",
                    current_round=current_round,
                    wake_kind=WakeConditionKind.REVIEWER_AVAILABLE,
                )
                continue
            agent = max(agents, key=lambda item: (item.trust_score, item.id))

            receipt_ids: list[str] = []
            task_failed = False
            for message_id in task.message_ids:
                delivery = broker.delivery_record(
                    message_id,
                    task.target_route_id,
                )
                if delivery is None:
                    control.fail_route_update_task(
                        task.task_id,
                        reason=f"delivery {message_id} is missing",
                        current_round=current_round,
                    )
                    task_failed = True
                    break
                existing_receipt = broker.receipt_record(
                    message_id,
                    task.target_route_id,
                )
                if existing_receipt is not None:
                    receipt_ids.append(existing_receipt.receipt_id)
                    continue
                if runner.ledger.remaining_calls <= 0:
                    control.defer_route_update_task(
                        task.task_id,
                        reason="no model call remains for the scheduled route update",
                        current_round=current_round,
                        wake_kind=WakeConditionKind.BUDGET_AVAILABLE,
                    )
                    task_failed = True
                    break
                try:
                    message = broker.present_scheduled_route_update(
                        message_id,
                        task.target_route_id,
                        action_id=task.action_id,
                        current_round=current_round,
                    )
                except ValueError as exc:
                    control.fail_route_update_task(
                        task.task_id,
                        reason=str(exc),
                        current_round=current_round,
                    )
                    task_failed = True
                    break
                control.mark_route_update_presented(
                    task.task_id,
                    current_round=current_round,
                )
                requirement = {
                    "message_id": message.message_id,
                    "target_route_id": task.target_route_id,
                    "delivered_round": int(
                        delivery.get("delivered_round", current_round)
                    ),
                    "receipt_token": str(delivery.get("receipt_token", "")),
                    "required_fields": [
                        "receipt_token",
                        "status",
                        "used",
                        "referenced_in_step_ids",
                        "claimed_closed_obligation_ids",
                        "reason",
                    ],
                }
                try:
                    result = await self._safe_call(
                        runner,
                        "route_prover",
                        prompts.acknowledge_message(
                            problem=problem,
                            route_id=task.target_route_id,
                            message=message,
                            receipt_requirement=requirement,
                            task=(
                                "Read this high-value cross-route message now. "
                                "Return a semantic receipt and do not claim "
                                "mathematical use without a verified downstream step."
                            ),
                        ),
                        fixed_agent=agent,
                        budget_bucket="depth",
                    )
                except (BudgetExhaustedError, ProviderCircuitOpenError) as exc:
                    control.defer_route_update_task(
                        task.task_id,
                        reason=f"{type(exc).__name__}: {exc}",
                        current_round=current_round,
                        wake_kind=(
                            WakeConditionKind.BUDGET_AVAILABLE
                            if isinstance(exc, BudgetExhaustedError)
                            else WakeConditionKind.PROVIDER_AVAILABLE
                        ),
                    )
                    task_failed = True
                    break
                if result is None:
                    control.fail_route_update_task(
                        task.task_id,
                        reason="route prover did not return a valid semantic receipt",
                        current_round=current_round,
                    )
                    task_failed = True
                    break
                acknowledged = acknowledge_route_messages(
                    broker,
                    [message],
                    [result.value],
                    route_id=task.target_route_id,
                    current_round=current_round,
                )
                receipt_ids.extend(item.receipt_id for item in acknowledged)

            if task_failed:
                continue
            used = any(
                broker.utility_record(message_id, task.target_route_id) is not None
                for message_id in task.message_ids
            )
            control.complete_route_update_task(
                task.task_id,
                receipt_ids=receipt_ids,
                used=used,
                current_round=current_round,
            )
            performed = True
        return performed

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

        recovery_lineage_id = None
        if bottleneck_obligation is not None:
            failure_fingerprint = str(
                bottleneck_obligation.first_error_fingerprint or ""
            )
            if failure_fingerprint.startswith("post_failure:"):
                recovery_lineage_id = (
                    "post_failure_recovery_"
                    + stable_hash(
                        {
                            "route_id": route_id,
                            "verified_checkpoint_hash": checkpoint_math_fingerprint(
                                checkpoint
                            ),
                            "failure_fingerprint": failure_fingerprint,
                        }
                    )[:24]
                )

        signature = ExplorationSignature(
            problem_hash=problem.integrity_hash,
            verified_checkpoint_id=checkpoint.checkpoint_id,
            verified_checkpoint_hash=checkpoint_math_fingerprint(checkpoint),
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
            recovery_lineage_id=recovery_lineage_id,
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
            checkpoint_after_hash=checkpoint_math_fingerprint(checkpoint_after),
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
        if state.route_registry is not None:
            if finished.outcome in {
                ExplorationOutcome.VERIFIED_PROGRESS,
                ExplorationOutcome.VERIFIED_MECHANISM_CHANGE,
            }:
                state.route_registry.mark_progress(
                    finished.route_id,
                    checkpoint_math_fingerprint(checkpoint_after),
                )
            elif finished.outcome in {
                ExplorationOutcome.USABLE_PARTIAL,
                ExplorationOutcome.NO_ARTIFACT,
                ExplorationOutcome.NO_VERIFIED_PROGRESS,
                ExplorationOutcome.INTERRUPTED,
            }:
                state.route_registry.mark_no_progress(
                    finished.route_id,
                    signature=finished.signature.signature_hash,
                    reason=reason,
                    recovery_only=finished.recovery_only,
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
            route_status = None
            if state.route_registry is not None:
                route_status = state.route_registry.get(finished.route_id).status.value
            store.append_event(
                "deep_exploration_signature_locked",
                {
                    **event,
                    "route_status": route_status,
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
                if item.proof_steps or item.final_answer
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

    def _record_attempts(
        self,
        state: SolveState,
        attempts: Iterable[ProofAttempt],
        store: ArtifactStore,
    ) -> list[ProofAttempt]:
        """Write-before-dedupe guard for public mathematical attempt content."""

        existing = {
            (item.strategy_id, attempt_content_fingerprint(item)): item
            for item in state.attempts
        }
        accepted: list[ProofAttempt] = []
        for attempt in attempts:
            fingerprint = attempt_content_fingerprint(attempt)
            key = (attempt.strategy_id, fingerprint)
            duplicate = existing.get(key)
            route = (
                state.route_registry.route_for_strategy(attempt.strategy_id)
                if state.route_registry is not None
                else None
            )
            if duplicate is not None:
                if route is not None:
                    route.duplicate_attempt_count += 1
                    route.latest_attempt_id = duplicate.attempt_id
                    route.latest_checkpoint_id = duplicate.latest_checkpoint_id
                    if route.status == RouteStatus.ACTIVE:
                        state.route_registry.mark_no_progress(
                            route.route_id,
                            signature=fingerprint,
                            reason="duplicate public proof state",
                            recovery_only=False,
                        )
                store.append_event(
                    "duplicate_attempt_collapsed",
                    {
                        "attempt_id": attempt.attempt_id,
                        "canonical_attempt_id": duplicate.attempt_id,
                        "strategy_id": attempt.strategy_id,
                        "content_fingerprint": fingerprint,
                    },
                )
                continue
            state.attempts.append(attempt)
            existing[key] = attempt
            accepted.append(attempt)
            if state.proof_control is not None:
                state.proof_control.register_attempt(attempt)
            if route is not None:
                route.latest_attempt_id = attempt.attempt_id
                route.latest_checkpoint_id = attempt.latest_checkpoint_id
        return accepted

    @staticmethod
    def _global_progress_signature(state: SolveState) -> str:
        facts = state.typed_memory.facts if state.typed_memory is not None else []
        negatives = (
            state.typed_memory.negatives if state.typed_memory is not None else []
        )
        obligations = (
            state.proof_graph.obligations if state.proof_graph is not None else []
        )
        certified_attempts = [
            attempt
            for attempt in state.attempts
            if attempt.latest_checkpoint_id is not None
            or (
                state.aggregate_reports.get(attempt.attempt_id) is not None
                and state.aggregate_reports[attempt.attempt_id].verdict
                == VerificationVerdict.PASS
            )
        ]
        return stable_hash(
            {
                "base_progress_signature": progress_signature(
                    attempts=certified_attempts,
                    facts=facts,
                    obligations=obligations,
                    negatives=negatives,
                    final_proof=state.final_proof,
                ),
                "certified_counterexamples": sorted(
                    set(state.certified_counterexample_hashes)
                ),
            }
        )

    def _reevaluate_route_wakes(
        self,
        state: SolveState,
        *,
        current_round: int,
        pool: AgentPool,
        runner: StructuredAgentRunner,
        user_intervention: bool = False,
        config_changed: bool = False,
    ) -> list[str]:
        control = state.proof_control
        if control is None or not control.active:
            return []
        available_agents: list[AgentRuntime] = []
        if runner.ledger.remaining_calls > 0:
            for agent in pool.agents:
                if agent.in_cooldown:
                    continue
                try:
                    pool.provider_circuit.assert_available(agent.provider_scope)
                except ProviderCircuitOpenError:
                    continue
                available_agents.append(agent)
        return control.evaluate_route_wakes(
            current_round=current_round,
            provider_available=bool(available_agents),
            budget_available=runner.ledger.remaining_calls > 0,
            available_fact_ids=(
                [fact.message_id for fact in state.typed_memory.facts]
                if state.typed_memory is not None
                else []
            ),
            reviewer_available=any(
                agent.supports_role("counterexample_hunter")
                or agent.supports_role("referee")
                or agent.supports_role("verifier")
                for agent in available_agents
            ),
            user_intervention=user_intervention,
            config_changed=config_changed,
        )

    def _apply_global_progress_gate(
        self,
        state: SolveState,
        *,
        round_index: int,
        store: ArtifactStore,
        activity: ActivityStream | None = None,
    ) -> bool:
        """Return True when the run must stop after a certified progress plateau."""

        current = self._global_progress_signature(state)
        prior = state.last_progress_signature
        changed = prior is not None and current != prior
        control = state.proof_control
        if (
            control is not None
            and control.active
            and control.state.meta_pivot_state is not None
            and control.state.meta_pivot_state.status == MetaPivotStatus.EXECUTED
        ):
            control.evaluate_meta_pivot(
                progress_signature=current,
                current_round=round_index,
            )
        if prior is None or changed:
            state.global_no_progress_rounds = 0
            state.global_meta_pivot_used = False
            state.hard_stopped = False
        else:
            state.global_no_progress_rounds += 1
            pivot = (
                control.state.meta_pivot_state
                if control is not None and control.active
                else None
            )
            pivot_outcome = (
                control.state.meta_pivot_outcomes.get(pivot.pivot_id)
                if control is not None and pivot is not None
                else None
            )
            if (
                not state.pivot_grace_used
                and pivot is not None
                and pivot.status == MetaPivotStatus.FAILED
                and pivot_outcome is not None
                and pivot_outcome.effect == MetaPivotEffect.EMPTY
            ):
                # An admitted pivot that never materialized consumed this
                # round; hold the counter once per run so the hard stop does
                # not fire on the very round the pivot burned.
                state.pivot_grace_used = True
                state.global_no_progress_rounds -= 1
        state.last_progress_signature = current

        certificate = {
            "round_index": round_index,
            "progress_signature": current,
            "previous_progress_signature": prior,
            "verified_progress": changed,
            "consecutive_no_progress_rounds": state.global_no_progress_rounds,
            "attempt_count": len(state.attempts),
            "verified_fact_count": (
                len(state.typed_memory.facts) if state.typed_memory is not None else 0
            ),
            "resolved_obligation_count": (
                sum(
                    item.status == "closed"
                    or (item.status == "refuted" and bool(item.evidence_message_ids))
                    for item in state.proof_graph.obligations
                )
                if state.proof_graph is not None
                else 0
            ),
        }
        store.write_json(
            "structured",
            f"progress_certificate_round_{round_index}",
            certificate,
        )
        store.append_event("progress_certificate_recorded", certificate)

        scheduler = self.config.scheduler
        if not scheduler.hard_stagnation_enabled:
            return False
        if (
            control is not None
            and control.active
            and control.common_mode_blocks_stagnation_stop()
        ):
            store.append_event(
                "global_stagnation_stop_blocked_by_common_mode_challenger",
                {
                    "round_index": round_index,
                    "progress_signature": current,
                    "task_ids": sorted(
                        task.task_id
                        for task in control.state.assumption_challenger_tasks.values()
                        if task.executable_task_id is not None
                        and control.state.executable_tasks[
                            task.executable_task_id
                        ].status
                        in {
                            ProofControlTaskStatus.CREATED,
                            ProofControlTaskStatus.ASSIGNED,
                            ProofControlTaskStatus.READY,
                            ProofControlTaskStatus.RUNNING,
                        }
                    ),
                },
            )
            return False
        if (
            control is not None
            and control.active
            and control.state.meta_pivot_state is None
            and state.global_meta_pivot_used
        ):
            # A v0.8 checkpoint may have only the legacy Boolean request marker.
            # Reify that request instead of treating it as an executed pivot.
            state.global_meta_pivot_used = False
        if (
            state.global_no_progress_rounds
            >= scheduler.global_no_progress_rounds_before_meta_pivot
            and not state.global_meta_pivot_used
        ):
            state.global_meta_pivot_used = True
            if control is not None and control.active:
                pivot = control.request_meta_pivot(
                    source_stagnation_signature=current,
                    trigger_round=round_index,
                    requested_mechanisms=[
                        "meta_replan",
                        "representation_switch",
                        "auxiliary_construction",
                    ],
                )
            else:
                pivot = None
            if state.route_registry is not None:
                for route in state.route_registry.active_routes(round_index):
                    route.stagnation_rounds = max(
                        route.stagnation_rounds,
                        self.config.topology.inspiration.stagnation_rounds,
                    )
            store.append_event(
                "global_stagnation_meta_pivot_requested",
                {
                    "round_index": round_index,
                    "progress_signature": current,
                    "consecutive_no_progress_rounds": (state.global_no_progress_rounds),
                    "next_action": "one inspiration/meta-replan pivot",
                    "pivot_id": pivot.pivot_id if pivot is not None else None,
                    "pivot_status": (
                        pivot.status.value if pivot is not None else "legacy_requested"
                    ),
                },
            )
            if activity is not None:
                activity.info(
                    "global_stagnation_meta_pivot",
                    title=activity.text(
                        "全局停滞，启动一次元策略转向",
                        "Global plateau: one meta-strategy pivot requested",
                    ),
                    detail=activity.text(
                        "连续两轮没有可验证的数学进展；下一轮只允许一次新机制转向。",
                        "Two rounds produced no certified mathematical progress; one new-mechanism pivot is allowed next.",
                    ),
                    stage="adaptive_round",
                    importance=ActivityImportance.MAJOR,
                    metrics={
                        "round_index": round_index,
                        "progress_signature": current,
                    },
                )
        if (
            state.global_no_progress_rounds
            < scheduler.global_no_progress_rounds_before_stop
        ):
            return False
        if control is not None and control.active:
            pivot = control.state.meta_pivot_state
            if control.meta_pivot_blocks_stagnation_stop():
                store.append_event(
                    "global_stagnation_stop_blocked_by_meta_pivot",
                    {
                        "round_index": round_index,
                        "progress_signature": current,
                        "pivot_id": pivot.pivot_id if pivot is not None else None,
                        "pivot_status": (
                            pivot.status.value if pivot is not None else None
                        ),
                    },
                )
                return False
            if not control.meta_pivot_allows_stagnation_stop(
                progress_signature=current
            ):
                return False
        route_terminal_policy: dict[str, list[str]] | None = None
        if control is not None and control.active:
            route_terminal_policy = control.prepare_routes_for_hard_stop(
                progress_signature=current,
                current_round=round_index,
            )
            if route_terminal_policy["ready_task_ids"]:
                store.append_event(
                    "global_stagnation_stop_blocked_by_executable_task",
                    {
                        "round_index": round_index,
                        "progress_signature": current,
                        "ready_task_ids": route_terminal_policy["ready_task_ids"],
                    },
                )
                return False
        elif state.route_registry is not None:
            for route in state.route_registry.active_routes(round_index):
                state.route_registry.mark_stalled(
                    route.route_id,
                    signature=current,
                    reason="global certified-progress plateau",
                )
        state.hard_stopped = True
        store.append_event(
            "global_stagnation_hard_stop",
            {
                "round_index": round_index,
                "progress_signature": current,
                "consecutive_no_progress_rounds": state.global_no_progress_rounds,
                "reason": (
                    "No new verified checkpoint content, admitted fact, resolved "
                    "obligation, or independently checked counterexample."
                ),
                "meta_pivot_status": (
                    control.state.meta_pivot_state.status.value
                    if control is not None
                    and control.state.meta_pivot_state is not None
                    else None
                ),
                "meta_pivot_failure_reason": (
                    control.state.meta_pivot_state.failure_reason
                    if control is not None
                    and control.state.meta_pivot_state is not None
                    else ""
                ),
                "route_terminal_policy": route_terminal_policy,
            },
        )
        if activity is not None:
            activity.info(
                "global_stagnation_hard_stop",
                title=activity.text(
                    "连续无有效进展，已停止重复求解",
                    "Repeated solving stopped after a certified plateau",
                ),
                detail=activity.text(
                    "未新增已验证检查点、全局事实、已关闭义务或独立反例；当前状态已完整保留。",
                    "No verified checkpoint, admitted fact, closed obligation, or independent counterexample was added; state was preserved.",
                ),
                stage="adaptive_round",
                importance=ActivityImportance.MAJOR,
                metrics={
                    "round_index": round_index,
                    "consecutive_no_progress_rounds": (state.global_no_progress_rounds),
                },
            )
        return True

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
            if state.proof_control is not None:
                result[route.strategy_id].update(
                    state.proof_control.route_signals(route.route_id)
                )
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
            problem_hash=state.inspiration_engine.problem.integrity_hash,
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
            obligation_kinds={
                item.obligation_id: item.kind.value
                for item in state.proof_graph.obligations
                if item.status != "closed"
            },
        )

    async def _execute_pending_assumption_challengers(
        self,
        state: SolveState,
        *,
        problem: ProblemContract,
        store: ArtifactStore,
        runner: StructuredAgentRunner,
        prompts: PromptFactory,
        allocator: SoftBudgetAllocator,
        router: SparseTopologyRouter,
        memory: LemmaMemory,
        tools: ToolBroker,
    ) -> tuple[bool, bool]:
        control = state.proof_control
        registry = state.route_registry
        if control is None or not control.active or registry is None:
            return False, False
        live_statuses = {
            ProofControlTaskStatus.CREATED,
            ProofControlTaskStatus.ASSIGNED,
            ProofControlTaskStatus.READY,
            ProofControlTaskStatus.RUNNING,
            ProofControlTaskStatus.DEFERRED,
            ProofControlTaskStatus.BLOCKED,
        }
        pending = [
            task
            for task in control.state.assumption_challenger_tasks.values()
            if task.executable_task_id is not None
            and control.state.executable_tasks[task.executable_task_id].status
            in live_statuses
            and task.execution_action_id is not None
            and control.state.control_actions[task.execution_action_id].status
            not in {
                ControlActionStatus.EXECUTED,
                ControlActionStatus.FAILED,
                ControlActionStatus.REJECTED,
            }
        ]
        if not pending:
            return False, False

        async def execute(task) -> AssumptionChallengeResult:
            family = control.state.assumption_families[task.family_id]
            default_action = AssumptionChallengeAction.REFUTE
            if runner.ledger.remaining_calls <= allocator.minimum_finish_reserve + 1:
                return AssumptionChallengeResult(
                    result_id=(
                        "assumption_challenge_result_"
                        + stable_hash(
                            {
                                "task_id": task.task_id,
                                "round": state.current_round,
                                "reason": "protected_finish_reserve",
                            }
                        )[:20]
                    ),
                    task_id=task.task_id,
                    family_id=task.family_id,
                    action=default_action,
                    outcome=AssumptionChallengeOutcome.BLOCKED,
                    detail=(
                        "The protected finalization reserve leaves no independent "
                        "challenge-and-review pair."
                    ),
                    completed_round=state.current_round,
                )

            excluded_authors = self._route_authors(registry, task.route_ids)
            try:
                challenger = runner.pool.select(
                    "counterexample_hunter",
                    exclude=excluded_authors,
                    specialty_hints=family.semantic_tags,
                    strict_exclude=True,
                )
            except RuntimeError:
                return AssumptionChallengeResult(
                    result_id=(
                        "assumption_challenge_result_"
                        + stable_hash(
                            {
                                "task_id": task.task_id,
                                "round": state.current_round,
                                "reason": "independent_challenger_unavailable",
                            }
                        )[:20]
                    ),
                    task_id=task.task_id,
                    family_id=task.family_id,
                    action=default_action,
                    outcome=AssumptionChallengeOutcome.BLOCKED,
                    detail="No agent independent of the affected route authors is available.",
                    completed_round=state.current_round,
                )
            task.assigned_agent_id = challenger.id
            executable = control.state.executable_tasks[task.executable_task_id]
            executable.assigned_agent_id = challenger.id
            proposal_call = await self._safe_call(
                runner,
                "counterexample_hunter",
                prompts.challenge_critical_assumption(
                    problem=problem,
                    challenger_task=task,
                    assumption_family=family,
                    member_assumptions=[
                        control.state.critical_assumptions[assumption_id]
                        for assumption_id in family.member_assumption_ids
                        if assumption_id in control.state.critical_assumptions
                    ],
                    dependency_atoms=[
                        control.state.dependency_atoms[atom_id]
                        for atom_id in family.dependency_atom_ids
                        if atom_id in control.state.dependency_atoms
                    ],
                    affected_routes=[
                        registry.get(route_id)
                        for route_id in task.route_ids
                        if any(route.route_id == route_id for route in registry.routes)
                    ],
                    verified_facts=(
                        state.typed_memory.facts
                        if state.typed_memory is not None
                        else []
                    ),
                    premise_eligible=False,
                ),
                fixed_agent=challenger,
                budget_bucket="depth",
            )
            if proposal_call is None:
                return AssumptionChallengeResult(
                    result_id=(
                        "assumption_challenge_result_"
                        + stable_hash(
                            {
                                "task_id": task.task_id,
                                "round": state.current_round,
                                "reason": "challenger_returned_no_artifact",
                            }
                        )[:20]
                    ),
                    task_id=task.task_id,
                    family_id=task.family_id,
                    action=default_action,
                    outcome=AssumptionChallengeOutcome.INCONCLUSIVE,
                    challenger_agent_id=challenger.id,
                    detail="The challenger returned no usable structured artifact.",
                    completed_round=state.current_round,
                )
            proposal = proposal_call.value
            proposal_name = f"assumption_challenge_proposal_{task.task_id}"
            store.write_json("structured", proposal_name, proposal)
            proposal_ref = proposal_call.raw_ref or f"structured:{proposal_name}"

            try:
                reviewer = runner.pool.select(
                    "detailed_verifier",
                    exclude={challenger.id},
                    specialty_hints=family.semantic_tags,
                    prefer_provider_not=challenger.provider,
                    strict_exclude=True,
                )
            except RuntimeError:
                return AssumptionChallengeResult(
                    result_id=(
                        "assumption_challenge_result_"
                        + stable_hash(
                            {
                                "task_id": task.task_id,
                                "proposal_id": proposal.proposal_id,
                                "reason": "independent_reviewer_unavailable",
                            }
                        )[:20]
                    ),
                    task_id=task.task_id,
                    family_id=task.family_id,
                    action=proposal.action,
                    outcome=AssumptionChallengeOutcome.BLOCKED,
                    challenger_agent_id=challenger.id,
                    evidence_refs=[proposal_ref],
                    detail="No independent reviewer is available for the challenge.",
                    completed_round=state.current_round,
                )
            review_call = await self._safe_call(
                runner,
                "detailed_verifier",
                prompts.review_critical_assumption_challenge(
                    problem=problem,
                    challenger_task=task,
                    assumption_family=family,
                    proposal=proposal,
                    dependency_atoms=[
                        control.state.dependency_atoms[atom_id]
                        for atom_id in family.dependency_atom_ids
                        if atom_id in control.state.dependency_atoms
                    ],
                    verified_facts=(
                        state.typed_memory.facts
                        if state.typed_memory is not None
                        else []
                    ),
                    proof_control_constraints={
                        "may_promote_fact": False,
                        "may_close_obligation": False,
                        "requires_dependency_independence_for_avoidance": True,
                    },
                ),
                fixed_agent=reviewer,
                exclude={challenger.id},
                prefer_provider_not=challenger.provider,
                budget_bucket="verification",
            )
            if review_call is None:
                return AssumptionChallengeResult(
                    result_id=(
                        "assumption_challenge_result_"
                        + stable_hash(
                            {
                                "task_id": task.task_id,
                                "proposal_id": proposal.proposal_id,
                                "reason": "review_returned_no_artifact",
                            }
                        )[:20]
                    ),
                    task_id=task.task_id,
                    family_id=task.family_id,
                    action=proposal.action,
                    outcome=AssumptionChallengeOutcome.INCONCLUSIVE,
                    challenger_agent_id=challenger.id,
                    reviewer_agent_id=reviewer.id,
                    evidence_refs=[proposal_ref],
                    detail="Independent review returned no usable structured artifact.",
                    completed_round=state.current_round,
                )
            review = review_call.value
            review_name = f"assumption_challenge_review_{task.task_id}"
            store.write_json("structured", review_name, review)
            review_ref = review_call.raw_ref or f"structured:{review_name}"
            outcome = AssumptionChallengeOutcome.INCONCLUSIVE
            created_route_ids: list[str] = []
            alternative_strategy_ids: list[str] = []
            target_matches = control.common_mode.statements_semantically_match(
                proposal.target_statement,
                family.canonical_statement,
            )
            review_passed = (
                target_matches and review.verdict == "pass" and review.action_supported
            )

            if (
                proposal.action == AssumptionChallengeAction.PROVE
                and bool(proposal.proof_steps)
                and review_passed
                and review.proof_complete
            ):
                outcome = AssumptionChallengeOutcome.VERIFIED
            elif (
                proposal.action == AssumptionChallengeAction.REFUTE
                and proposal.counterexample
                and review_passed
                and review.exact_counterexample_confirmed
            ):
                outcome = AssumptionChallengeOutcome.REFUTED
            elif proposal.action in {
                AssumptionChallengeAction.AVOID,
                AssumptionChallengeAction.WEAKEN,
            }:
                alternative = proposal.alternative_strategy
                weaker_ok = proposal.action == AssumptionChallengeAction.AVOID or bool(
                    proposal.weaker_condition and review.weaker_sufficient_confirmed
                )
                independent = bool(
                    alternative is not None
                    and review_passed
                    and review.independence_confirmed
                    and weaker_ok
                    and control.strategy_is_independent_from_common_mode(alternative)
                    and self._common_mode_alternative_has_new_mechanism(
                        alternative,
                        state.strategies,
                        task.route_ids,
                        registry,
                    )
                )
                if (
                    independent
                    and alternative is not None
                    and len(state.strategies) < self.config.budget.max_paths
                    and all(
                        item.strategy_id != alternative.strategy_id
                        for item in state.strategies
                    )
                ):
                    selected, _records = control.admit_routes([alternative])
                    if selected:
                        state.strategies.extend(selected)
                        assignments = router.assign_explorers(selected)
                        for strategy, agent in assignments:
                            route = registry.register_route(strategy)
                            registry.assign_member(
                                route.route_id,
                                agent.id,
                                RouteRole.PROVER,
                                state.current_round,
                            )
                            created_route_ids.append(route.route_id)
                            alternative_strategy_ids.append(strategy.strategy_id)
                            store.append_event(
                                "common_mode_independent_route_registered",
                                {
                                    "task_id": task.task_id,
                                    "family_id": task.family_id,
                                    "route_id": route.route_id,
                                    "strategy_id": strategy.strategy_id,
                                },
                            )
                        registry.recompute_neighbors()
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
                        accepted_attempts = self._record_attempts(
                            state,
                            attempts,
                            store,
                        )
                        if accepted_attempts:
                            await self._extract_claims_many(
                                problem,
                                accepted_attempts,
                                runner,
                                prompts,
                                memory,
                                store,
                                budget_bucket="breadth",
                            )
                        if created_route_ids:
                            outcome = AssumptionChallengeOutcome.AVOIDED

            result_id = (
                "assumption_challenge_result_"
                + stable_hash(
                    {
                        "task_id": task.task_id,
                        "proposal_id": proposal.proposal_id,
                        "outcome": outcome.value,
                        "created_route_ids": created_route_ids,
                    }
                )[:20]
            )
            return AssumptionChallengeResult(
                result_id=result_id,
                task_id=task.task_id,
                family_id=task.family_id,
                action=proposal.action,
                outcome=outcome,
                challenger_agent_id=challenger.id,
                reviewer_agent_id=reviewer.id,
                evidence_refs=[proposal_ref],
                independent_review_refs=[review_ref],
                created_route_ids=created_route_ids,
                alternative_strategy_ids=alternative_strategy_ids,
                detail=review.concise_feedback,
                completed_round=state.current_round,
            )

        completed = await control.execute_pending_assumption_challengers(
            current_round=state.current_round,
            executor=execute,
        )
        store.append_event(
            "assumption_challenger_dispatch_cycle",
            {
                "round_index": state.current_round,
                "pending_task_ids": sorted(task.task_id for task in pending),
                "completed_task_ids": sorted(task.task_id for task in completed),
            },
        )
        return True, bool(completed)

    @staticmethod
    def _common_mode_alternative_has_new_mechanism(
        candidate: StrategyCard,
        strategies: Sequence[StrategyCard],
        affected_route_ids: Sequence[str],
        registry: RouteRegistry,
    ) -> bool:
        affected_strategy_ids = {
            registry.get(route_id).strategy_id
            for route_id in affected_route_ids
            if any(route.route_id == route_id for route in registry.routes)
        }
        candidate_mechanism = " ".join(
            [
                *candidate.tags,
                candidate.key_original_step or "",
                candidate.core_idea,
            ]
        )
        for existing in strategies:
            if existing.strategy_id not in affected_strategy_ids:
                continue
            existing_mechanism = " ".join(
                [
                    *existing.tags,
                    existing.key_original_step or "",
                    existing.core_idea,
                ]
            )
            if jaccard_similarity(candidate_mechanism, existing_mechanism) >= 0.80:
                return False
        return True

    async def _execute_pending_meta_pivot(
        self,
        state: SolveState,
        *,
        problem: ProblemContract,
        store: ArtifactStore,
        runner: StructuredAgentRunner,
        prompts: PromptFactory,
        allocator: SoftBudgetAllocator,
        router: SparseTopologyRouter,
        memory: LemmaMemory,
        tools: ToolBroker,
    ) -> tuple[bool, bool]:
        control = state.proof_control
        pivot = control.state.meta_pivot_state if control is not None else None
        if (
            control is None
            or not control.active
            or pivot is None
            or pivot.status
            not in {
                MetaPivotStatus.REQUESTED,
                MetaPivotStatus.ADMITTED,
                MetaPivotStatus.EXECUTING,
            }
        ):
            return False, False

        async def execute(_pivot) -> dict[str, list[str]]:
            engine = state.inspiration_engine
            if engine is None or not engine.enabled:
                raise RuntimeError("active Inspiration is unavailable for meta pivot")
            snapshot = self._inspiration_snapshot(
                state,
                remaining_calls=runner.ledger.remaining_calls,
            )
            if snapshot is None:
                raise RuntimeError("meta pivot could not build an Inspiration snapshot")
            if runner.ledger.remaining_calls <= snapshot.finalization_reserve_calls:
                raise BudgetExhaustedError(
                    "meta pivot cannot spend the protected finalization reserve"
                )
            route_ids_before = {
                route.route_id
                for route in (
                    state.route_registry.routes
                    if state.route_registry is not None
                    else []
                )
            }
            fact_ids_before = {
                item.message_id
                for item in (
                    state.typed_memory.facts if state.typed_memory is not None else []
                )
            }
            obligation_ids_before = {
                item.obligation_id
                for item in (
                    state.proof_graph.obligations
                    if state.proof_graph is not None
                    else []
                )
            }
            evidence_before = {
                "proposals": len(engine.proposals),
                "directives": len(engine.meta_directives),
                "materializations": len(engine.materializations),
            }
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
            evidence_after = {
                "proposals": len(engine.proposals),
                "directives": len(engine.meta_directives),
                "materializations": len(engine.materializations),
            }
            if evidence_after == evidence_before:
                raise RuntimeError(
                    "meta pivot produced no executable artifact; budget or "
                    "an eligible agent was unavailable"
                )
            created_route_ids = sorted(
                {
                    route.route_id
                    for route in (
                        state.route_registry.routes
                        if state.route_registry is not None
                        else []
                    )
                }
                - route_ids_before
            )
            result_fact_ids = sorted(
                {
                    item.message_id
                    for item in (
                        state.typed_memory.facts
                        if state.typed_memory is not None
                        else []
                    )
                }
                - fact_ids_before
            )
            result_obligation_ids = sorted(
                {
                    item.obligation_id
                    for item in (
                        state.proof_graph.obligations
                        if state.proof_graph is not None
                        else []
                    )
                }
                - obligation_ids_before
            )
            return {
                "created_route_ids": created_route_ids,
                "result_fact_ids": result_fact_ids,
                "result_obligation_ids": result_obligation_ids,
            }

        result = await control.execute_pending_meta_pivot(
            current_round=state.current_round,
            executor=execute,
        )
        if result.status == MetaPivotStatus.FAILED:
            store.append_event(
                "meta_pivot_unexecuted",
                {
                    "pivot_id": result.pivot_id,
                    "round_index": state.current_round,
                    "failure_reason": result.failure_reason,
                },
            )
            return True, False
        return True, result.status in {
            MetaPivotStatus.EXECUTED,
            MetaPivotStatus.EVALUATED,
        }

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
        selected_tasks = list(tasks)
        (
            tasks,
            assignment_plans,
            task_call_breakdowns,
            assignment_rejected,
        ) = self._plan_inspiration_proposers(
            engine,
            tasks,
            snapshot=snapshot,
            problem=problem,
            runner=runner,
            allocator=allocator,
        )
        admission = admit_inspiration_tasks(
            tasks,
            allocator,
            current_path_count=snapshot.current_path_count,
            has_candidate=self._has_synthesis_ready_candidate(state),
            task_call_breakdowns=task_call_breakdowns or None,
        )
        rejected_reasons = {**assignment_rejected, **admission.rejected}
        store.append_event(
            "inspiration_scheduler_admission",
            {
                "admitted_task_ids": [
                    task.task_id for task in admission.admitted_tasks
                ],
                "rejected": rejected_reasons,
                "decision": admission.decision.model_dump(mode="json"),
                "proposer_assignment_plans": {
                    task_id: plan.model_dump(mode="json")
                    for task_id, plan in assignment_plans.items()
                },
            },
        )
        rejected_tasks = [
            task
            for task in selected_tasks
            if task.task_id not in {item.task_id for item in admission.admitted_tasks}
        ]
        engine.requeue_tasks(rejected_tasks, reasons=rejected_reasons)
        tasks = admission.admitted_tasks
        if not tasks:
            return
        if engine.inspiration_config.mode == "active":
            reserved_tasks = []
            reservation_rejected: list[InspirationTask] = []
            reservation_reasons: dict[str, str] = {}
            for task in tasks:
                breakdown = task_call_breakdowns.get(
                    task.task_id,
                    allocator.inspiration_call_breakdown(),
                )
                reservation, reason = engine.reserve_task_calls(
                    task,
                    snapshot=snapshot,
                    **breakdown,
                )
                if reservation is None:
                    reservation_rejected.append(task)
                    reservation_reasons[task.task_id] = reason
                    store.append_event(
                        "inspiration_task_reservation_rejected",
                        {"task_id": task.task_id, "reason": reason},
                    )
                    continue
                reserved_tasks.append(task)
            engine.requeue_tasks(
                reservation_rejected,
                reasons=reservation_reasons,
            )
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
                assignment_plans=assignment_plans,
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
                deferred_proposal_ids,
            ) = await self._review_inspiration_proposals(
                engine,
                proposals,
                snapshot=snapshot,
                problem=problem,
                runner=runner,
                prompts=prompts,
                proof_control=state.proof_control,
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
            reviewed_proposals = [
                proposal
                for proposal in proposals
                if proposal.proposal_id not in deferred_proposal_ids
            ]
            reviews = await engine.review(
                reviewed_proposals,
                precomputed_reviews=precomputed,
                immediate_counterexamples=counterexamples,
                hidden_assumptions=hidden_assumptions,
            )
            compositions = engine.queue_compositions(
                reviewed_proposals,
                reviews,
                snapshot,
            )
            if compositions:
                # A composed idea is reviewed as its own proposal on the next
                # scheduler turn. Its source ideas remain route-local insights
                # so that one trigger cannot materialize all of them and bypass
                # the configured route-creation cap.
                reviews = engine.defer_composed_sources(reviews, compositions)
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
            proposal_by_strategy = {
                strategy.strategy_id: strategy.inspiration_proposal_id
                for strategy in new_strategies
                if strategy.inspiration_proposal_id
            }
            for attempt in attempts:
                proposal_id = proposal_by_strategy.get(attempt.strategy_id)
                if proposal_id is None:
                    continue
                engine.record_outcome_usage(
                    proposal_id,
                    phase="route",
                    calls=1,
                    tokens=attempt.usage.total_tokens,
                )
            attempts = self._record_attempts(state, attempts, store)
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
                "deferred_proposal_ids": sorted(deferred_proposal_ids),
                "reviews": reviews,
                "compositions_queued": compositions,
                "materializations": materializations,
            },
        )
        self._finish_inspiration_reservations(engine, tasks)

    def _plan_inspiration_proposers(
        self,
        engine: InspirationEngine,
        tasks: Sequence[InspirationTask],
        *,
        snapshot: InspirationSnapshot,
        problem: ProblemContract,
        runner: StructuredAgentRunner,
        allocator: SoftBudgetAllocator,
    ) -> tuple[
        list[InspirationTask],
        dict[str, InspirationAssignmentPlan],
        dict[str, dict[str, int]],
        dict[str, str],
    ]:
        if engine.inspiration_config.mode != "active":
            return list(tasks), {}, {}, {}

        ready: list[InspirationTask] = []
        plans: dict[str, InspirationAssignmentPlan] = {}
        call_breakdowns: dict[str, dict[str, int]] = {}
        rejected: dict[str, str] = {}
        for task in tasks:
            if task.mechanism == InspirationMechanism.INSPIRATION_COMPOSITION:
                ready.append(task)
                call_breakdowns[task.task_id] = allocator.inspiration_call_breakdown(
                    proposer_calls=0,
                    review_candidates=1,
                )
                continue

            requested = (
                1
                if task.mechanism == InspirationMechanism.META_REPLAN
                else min(
                    engine.inspiration_config.active_proposals_per_task,
                    task.max_proposals,
                )
            )
            if (
                task.mechanism == InspirationMechanism.STRUCTURAL_ANALOGY
                and not self._analogy_records_for_task(
                    engine,
                    task,
                    snapshot=snapshot,
                    problem=problem,
                )
            ):
                plan = InspirationAssignmentPlan(
                    task_id=task.task_id,
                    mechanism=task.mechanism,
                    round_index=snapshot.round_index,
                    requested_proposals=requested,
                    deferred_reason=(
                        "no applicable verified local analogy records are available"
                    ),
                )
            else:
                role = self._inspiration_role_for_mechanism(task.mechanism)
                plan = engine.assignment_planner.plan(
                    task,
                    proposer_role=role,
                    pool=runner.pool,
                    round_index=snapshot.round_index,
                    specialty_hints=(snapshot.domain, task.mechanism.value),
                    allow_generalists=(
                        task.mechanism != InspirationMechanism.META_REPLAN
                    ),
                    requested_proposals=requested,
                )
            plans[task.task_id] = engine.register_assignment_plan(plan)
            if not plan.assignments:
                rejected[task.task_id] = (
                    plan.deferred_reason
                    or "no inspiration proposer assignment was available"
                )
                continue
            ready.append(task)
            call_breakdowns[task.task_id] = allocator.inspiration_call_breakdown(
                proposer_calls=len(plan.assignments),
                review_candidates=len(plan.assignments),
            )
        return ready, plans, call_breakdowns, rejected

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
        tasks: Sequence[InspirationTask],
        *,
        snapshot: InspirationSnapshot,
        problem: ProblemContract,
        runner: StructuredAgentRunner,
        prompts: PromptFactory,
        assignment_plans: dict[str, InspirationAssignmentPlan],
    ) -> list[InspirationProposal]:
        if engine.inspiration_config.mode != "active":
            return await engine.generate(tasks)

        async def generate_one(
            task: InspirationTask,
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
                proposal = engine.register_agent_artifact(
                    task,
                    result.value,
                    source_agent_id=agent.id,
                    state=snapshot,
                    proposal_slot=proposal_slot,
                    context_mode=context_mode,
                )
                if proposal is not None:
                    engine.record_outcome_usage(
                        proposal.proposal_id,
                        phase="proposer",
                        tokens=result.usage.total_tokens,
                    )
                return proposal
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
        deterministic_proposals: list[InspirationProposal] = []
        for task in tasks:
            if task.mechanism == InspirationMechanism.INSPIRATION_COMPOSITION:
                proposal = engine.pending_composition_for_task(
                    task.task_id,
                    state=snapshot,
                )
                if proposal is not None:
                    deterministic_proposals.append(proposal)
                continue
            plan = assignment_plans.get(task.task_id)
            if plan is None or not plan.assignments:
                continue
            population = []
            for assignment in plan.assignments:
                try:
                    agent = runner.pool.get(assignment.proposer_agent_id)
                except KeyError:
                    if engine.store is not None:
                        engine.store.append_event(
                            "inspiration_proposer_assignment_unavailable",
                            {
                                "task_id": task.task_id,
                                "agent_id": assignment.proposer_agent_id,
                                "reason": "assigned agent is no longer in the live pool",
                            },
                        )
                    continue
                if agent.in_cooldown:
                    if engine.store is not None:
                        engine.store.append_event(
                            "inspiration_proposer_assignment_unavailable",
                            {
                                "task_id": task.task_id,
                                "agent_id": agent.id,
                                "reason": "assigned agent entered cooldown",
                            },
                        )
                    continue
                population.append(
                    {
                        "proposal_slot": assignment.proposal_slot,
                        "context_mode": assignment.context_mode.value,
                        "agent_id": agent.id,
                        "specialist_match": assignment.specialist_match,
                    }
                )
                pending.append(
                    generate_one(
                        task,
                        proposal_slot=assignment.proposal_slot,
                        context_mode=assignment.context_mode,
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
                        "eligible_agent_ids": plan.eligible_agent_ids,
                        "parallel_generation": True,
                    },
                )
        if not pending:
            return deterministic_proposals
        results = await asyncio.gather(*pending)
        return [
            *deterministic_proposals,
            *(proposal for proposal in results if proposal is not None),
        ]

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
            # Composition proposals are deterministic control artifacts and do
            # not invoke a proposer role. This mapping is defensive only.
            InspirationMechanism.INSPIRATION_COMPOSITION: "inspiration_referee",
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
            records = self._analogy_records_for_task(
                engine,
                task,
                snapshot=snapshot,
                problem=problem,
            )
            return (
                "analogy_agent",
                prompts.structural_analogy_search(
                    problem=problem,
                    verified_local_records=records,
                    negative_transfer_records=[
                        item.model_dump(mode="json")
                        for item in engine.negative_analogy_records.values()
                    ],
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

    @staticmethod
    def _analogy_records_for_task(
        engine: InspirationEngine,
        task: InspirationTask,
        *,
        snapshot: InspirationSnapshot,
        problem: ProblemContract,
    ) -> list[dict[str, Any]]:
        return engine.analogy_library.search(
            query_text=problem.normalized_statement,
            object_tags=problem.definitions,
            mechanism_tags=[
                tag for item in snapshot.route_signatures for tag in item.mechanism_tags
            ],
            obligation_kinds=[
                snapshot.obligation_kinds[item]
                for item in task.target_obligation_ids
                if item in snapshot.obligation_kinds
            ],
            mechanism_chain=[
                tag
                for item in snapshot.route_signatures
                for tag in [
                    *item.representation_tags,
                    *item.mechanism_tags,
                    *item.key_transformations,
                ]
            ],
            graph_motif_tags=(
                ["shared_bottleneck"] if snapshot.shared_bottleneck_ids else []
            ),
            problem_hash=problem.integrity_hash,
            top_k=engine.inspiration_config.analogy_top_k,
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
        proof_control: ProofControlLayer | None,
    ) -> tuple[
        dict[str, InspirationReview],
        dict[str, list[str]],
        dict[str, list[str]],
        set[str],
    ]:
        precomputed: dict[str, InspirationReview] = {}
        counterexamples: dict[str, list[str]] = {}
        hidden: dict[str, list[str]] = {}
        deferred: set[str] = set()

        def defer_review(proposal: InspirationProposal, reason: str) -> None:
            if proof_control is None or not proof_control.active:
                raise RuntimeError(
                    "active inspiration review deferral requires active proof control"
                )
            action = proof_control.defer_inspiration_review(
                proposal_id=proposal.proposal_id,
                task_id=proposal.task_id,
                reason=reason,
                current_round=snapshot.round_index,
            )
            if action.status != ControlActionStatus.EXECUTED:
                raise RuntimeError(
                    "inspiration review deferral was not materialized: "
                    f"{action.status.value}"
                )
            deferred.add(proposal.proposal_id)
            engine.typed_memory.add_insight(proposal)

        for proposal in proposals:
            eligible = [
                agent
                for agent in runner.pool.agents
                if agent.id != proposal.source_agent_id
                and agent.supports_role("inspiration_referee")
                and not agent.in_cooldown
            ]
            review_budget_available = (
                runner.ledger.remaining_calls > snapshot.finalization_reserve_calls
            )
            if engine.inspiration_config.mode != "active":
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
            if (
                proof_control is not None
                and proof_control.active
                and (not eligible or not review_budget_available)
            ):
                defer_review(
                    proposal,
                    (
                        "no independent inspiration referee is available"
                        if not eligible
                        else "inspiration referee budget is exhausted"
                    ),
                )
                continue
            if not eligible or not review_budget_available:
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
                if proof_control is not None and proof_control.active:
                    defer_review(
                        proposal,
                        "inspiration referee call failed or returned no review",
                    )
                    reassignment_candidates = [
                        agent
                        for agent in eligible
                        if agent.id != reviewer.id and not agent.in_cooldown
                    ]
                    if (
                        reassignment_candidates
                        and runner.ledger.remaining_calls
                        > snapshot.finalization_reserve_calls
                    ):
                        reassigned = max(
                            reassignment_candidates,
                            key=lambda item: (item.trust_score, item.id),
                        )
                        action = proof_control.reassign_inspiration_review(
                            proposal_id=proposal.proposal_id,
                            reviewer_agent_id=reassigned.id,
                            current_round=snapshot.round_index,
                        )
                        if action.status != ControlActionStatus.EXECUTED:
                            raise RuntimeError(
                                "inspiration review reassignment was not "
                                f"materialized: {action.status.value}"
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
                            fixed_agent=reassigned,
                            budget_bucket="verification",
                            budget_reservation_id=engine.reservation_id_for_task(
                                proposal.task_id
                            ),
                        )
                        if result is not None:
                            reviewer = reassigned
                            deferred.discard(proposal.proposal_id)
                            proof_control.complete_inspiration_review(
                                proposal_id=proposal.proposal_id,
                                reviewer_agent_id=reviewer.id,
                            )
                        else:
                            defer_review(
                                proposal,
                                "reassigned inspiration referee call failed or "
                                "returned no review",
                            )
                    if result is None:
                        continue
                else:
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
            engine.record_outcome_usage(
                proposal.proposal_id,
                phase="referee",
                tokens=result.usage.total_tokens,
            )

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
            engine.record_outcome_usage(
                proposal.proposal_id,
                phase="skeptic",
                tokens=skeptic_result.usage.total_tokens,
            )
            report = skeptic_result.value
            if report.verdict == VerificationVerdict.FAIL:
                engine.record_quick_falsification(
                    proposal.proposal_id,
                    passed=False,
                    reason="quick skeptic found a blocking issue",
                )
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
            elif report.verdict == VerificationVerdict.PASS:
                engine.record_quick_falsification(
                    proposal.proposal_id,
                    passed=True,
                    reason="quick skeptic found no blocking counterexample",
                )
        return precomputed, counterexamples, hidden, deferred

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
            if state.proof_control is not None and pending:
                pending, _ = state.proof_control.admit_routes(pending)
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
                attempts = self._record_attempts(state, attempts, store)
            if attempts:
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
        if (
            memory_tier == MemoryTier.FACT
            and state.inspiration_engine is not None
            and state.proof_graph is not None
        ):
            evidence_message_id = decision.duplicate_of or verified_message.message_id
            closed_obligation_ids = [
                item.obligation_id
                for item in state.proof_graph.obligations
                if evidence_message_id in item.evidence_message_ids
            ]
            state.inspiration_engine.attribute_verified_fact(
                evidence_message_id,
                source_route_id=source_route_id,
                closed_obligation_ids=closed_obligation_ids,
                dependency_message_ids=verified_message.dependencies,
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

    def _apply_confirmed_counterexample_impact(
        self,
        state: SolveState | None,
        strategy: StrategyCard,
        *,
        route_id: str | None,
        impact: FailureLevel,
        experiment_results: Sequence[dict[str, Any]],
        current_round: int,
        store: ArtifactStore,
    ) -> None:
        confirmed = [
            item
            for item in experiment_results
            if item.get("outcome") == ExperimentOutcome.COUNTEREXAMPLE_FOUND.value
            and item.get("evidence_strength") == EvidenceStrength.COUNTEREXAMPLE.value
            and bool(item.get("independently_verified"))
        ]
        if not confirmed:
            return
        if state is not None:
            state.certified_counterexample_hashes = list(
                dict.fromkeys(
                    [
                        *state.certified_counterexample_hashes,
                        *(
                            str(
                                item.get("result_hash")
                                or item.get("request_hash")
                                or item.get("experiment_id")
                            )
                            for item in confirmed
                        ),
                    ]
                )
            )
        refuted_required: list[str] = []
        for result in confirmed:
            target = canonical_obligation_statement(str(result.get("target_claim", "")))
            if not target:
                continue
            for claim in strategy.critical_claims:
                if canonical_obligation_statement(claim.statement) != target:
                    continue
                claim.status = "refuted"
                evidence_ref = (
                    f"experiment:{result.get('experiment_id', 'unknown')}:"
                    f"{result.get('result_hash', result.get('request_hash', ''))}"
                )
                claim.evidence_refs = list(
                    dict.fromkeys([*claim.evidence_refs, evidence_ref])
                )
                if claim.necessity == "required":
                    refuted_required.append(claim.claim_id)

        route_action = "recorded"
        if (
            state is not None
            and state.route_registry is not None
            and route_id is not None
        ):
            if impact == FailureLevel.STRATEGY or refuted_required:
                state.route_registry.mark_refuted(
                    route_id,
                    "independently checked counterexample refuted a required claim",
                )
                route_action = "refuted"
            elif impact == FailureLevel.PLAN:
                state.route_registry.mark_cooling(
                    route_id,
                    current_round + self.config.scheduler.failed_path_cooldown_rounds,
                    "confirmed counterexample requires a new route plan",
                    requires_revision=True,
                )
                route_action = "repair_required"
        store.append_event(
            "confirmed_counterexample_propagated",
            {
                "strategy_id": strategy.strategy_id,
                "route_id": route_id,
                "failure_level": impact.value,
                "route_action": route_action,
                "refuted_required_claim_ids": refuted_required,
                "experiment_ids": [
                    str(item.get("experiment_id", "")) for item in confirmed
                ],
            },
        )

    @staticmethod
    def _deduplicate_restored_attempts(
        raw_attempts: Iterable[dict[str, Any]],
        store: ArtifactStore,
    ) -> list[ProofAttempt]:
        canonical: dict[tuple[str, str], ProofAttempt] = {}
        collapsed: list[dict[str, str]] = []
        for raw in raw_attempts:
            attempt = ProofAttempt.model_validate(raw)
            key = (attempt.strategy_id, attempt_content_fingerprint(attempt))
            previous = canonical.get(key)
            if previous is None or (
                attempt.round_index,
                attempt.attempt_id,
            ) > (
                previous.round_index,
                previous.attempt_id,
            ):
                if previous is not None:
                    collapsed.append(
                        {
                            "duplicate_attempt_id": previous.attempt_id,
                            "canonical_attempt_id": attempt.attempt_id,
                        }
                    )
                canonical[key] = attempt
            else:
                collapsed.append(
                    {
                        "duplicate_attempt_id": attempt.attempt_id,
                        "canonical_attempt_id": previous.attempt_id,
                    }
                )
        if collapsed:
            store.append_event(
                "restored_attempt_duplicates_collapsed",
                {
                    "duplicate_count": len(collapsed),
                    "aliases": collapsed[:200],
                },
            )
        return sorted(
            canonical.values(),
            key=lambda item: (item.round_index, item.strategy_id, item.attempt_id),
        )

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
            attempts=self._deduplicate_restored_attempts(
                payload.get("attempts", []), store
            ),
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
            global_no_progress_rounds=int(payload.get("global_no_progress_rounds", 0)),
            global_meta_pivot_used=bool(payload.get("global_meta_pivot_used", False)),
            pivot_grace_used=bool(payload.get("pivot_grace_used", False)),
            hard_stopped=bool(payload.get("hard_stopped", False)),
            last_progress_signature=payload.get("last_progress_signature"),
            certified_counterexample_hashes=[
                str(item) for item in payload.get("certified_counterexample_hashes", [])
            ],
            termination_reason=payload.get("termination_reason"),
            admission_starvation=(
                dict(payload["admission_starvation"])
                if isinstance(payload.get("admission_starvation"), dict)
                else None
            ),
        )

    async def _prepare_problem_contract(
        self,
        problem_text: str,
        *,
        runner: StructuredAgentRunner,
        prompts: PromptFactory,
        store: ArtifactStore,
        activity: ActivityStream,
        parent_task_id: str,
    ) -> ProblemContract:
        original = problem_text.strip()
        task_id = activity.start_task(
            "goal_preflight",
            task_id="goal-preflight",
            title=activity.text(
                "题意预检与目标规范化",
                "Problem preflight and goal normalization",
            ),
            detail=activity.text(
                "正在本地检查缺失模数、参数、占位内容与外部上下文",
                "Checking locally for missing parameters, placeholders, and context",
            ),
            stage="goal_preflight",
            parent_task_id=parent_task_id,
            importance=ActivityImportance.MAJOR,
            metrics={"api_call": False, "phase": "local_precheck"},
        )
        local_precheck = deterministic_goal_precheck(original)
        store.write_json("structured", "goal_preflight", local_precheck)

        canonical = original
        interpretation_source = "original"
        interpretation_confidence = 1.0
        interpretation_reasons: list[str] = []
        interpretation_agent_id: str | None = None
        interpretation_raw_ref: str | None = None

        if local_precheck.status == "model_review_required":
            activity.update_task(
                task_id,
                title=activity.text(
                    "发现题意疑点，正在生成候选解释",
                    "Goal warning found; generating candidate interpretations",
                ),
                detail=activity.text(
                    "仅调用一次小型、禁用 Thinking 的结构化检查；不会开始解题",
                    "Running one small non-thinking structured review; solving has not started",
                ),
                stage="goal_preflight",
                parent_task_id=parent_task_id,
                importance=ActivityImportance.MAJOR,
                metrics={
                    "api_call": True,
                    "phase": "model_review",
                    "rule_ids": local_precheck.rule_ids,
                },
            )
            result = await self._safe_call(
                runner,
                "planner",
                prompts.goal_normalization(original, local_precheck),
                budget_bucket="other",
            )
            if result is None:
                activity.fail_task(
                    task_id,
                    title=activity.text(
                        "题意规范化失败",
                        "Goal normalization failed",
                    ),
                    detail=activity.text(
                        "题目存在疑点且无法获得可靠的结构化解释，已在解题前停止",
                        "The goal is unclear and no reliable structured interpretation was available",
                    ),
                    event_type="goal_normalization_failed",
                    stage="goal_preflight",
                    parent_task_id=parent_task_id,
                    importance=ActivityImportance.MAJOR,
                )
                raise GoalNormalizationError(
                    "goal normalization failed before solving; the original statement was not changed"
                )

            assessment = result.value
            interpretation_agent_id = result.agent.id
            interpretation_raw_ref = result.raw_ref
            store.write_json("structured", "goal_normalization", assessment)
            interpretation_reasons = [
                *local_precheck.reasons,
                *assessment.ambiguity_reasons,
            ]

            if requires_confirmation(assessment):
                request = GoalClarificationRequest(
                    original_statement=original,
                    local_precheck=local_precheck,
                    assessment=assessment,
                )
                store.write_json("structured", "goal_clarification_request", request)
                activity.warn_task(
                    task_id,
                    title=activity.text(
                        "等待确认规范化目标",
                        "Waiting for canonical-goal confirmation",
                    ),
                    detail=activity.text(
                        "候选解释会改变或补充数学含义；确认前不会启动后续 Agent",
                        "The candidate changes or completes the meaning; no solver agent will start before confirmation",
                    ),
                    event_type="goal_clarification_required",
                    stage="goal_preflight",
                    parent_task_id=parent_task_id,
                    importance=ActivityImportance.MAJOR,
                    metrics={
                        "request_id": request.request_id,
                        "recommendation_confidence": (
                            assessment.recommendation_confidence
                        ),
                    },
                )
                if self.clarification_resolver is None:
                    raise GoalClarificationRequired(request)
                decision = validate_clarification_decision(
                    request,
                    await self.clarification_resolver(request),
                )
                if decision.canonical_statement == original:
                    raise ValueError(
                        "the confirmed statement must resolve the detected ambiguity"
                    )
                store.write_json("structured", "goal_clarification_decision", decision)
                canonical = decision.canonical_statement
                interpretation_source = decision.source
                interpretation_confidence = decision_confidence(request, decision)
            else:
                # A negative model review is not permission to polish or paraphrase.
                canonical = original
                interpretation_source = "original"
                interpretation_confidence = assessment.recommendation_confidence

        task_requirements = infer_task_requirements(canonical)
        problem = ProblemContract(
            exact_statement=canonical,
            normalized_statement=self._normalize_statement(canonical),
            original_statement=original,
            canonical_statement=canonical,
            interpretation_source=interpretation_source,
            interpretation_confidence=interpretation_confidence,
            interpretation_reasons=list(dict.fromkeys(interpretation_reasons)),
            interpretation_agent_id=interpretation_agent_id,
            interpretation_raw_ref=interpretation_raw_ref,
            problem_kind=ProblemKind.UNKNOWN,
            task_requirements=task_requirements,
            deliverables=deliverable_instructions(task_requirements),
            hard_constraints=[
                "Do not change hypotheses, quantifiers, domains, or requested conclusions.",
                "Distinguish proved claims from conjectures and unresolved gaps.",
                "Every downstream artifact must retain the frozen goal hash.",
                "Any semantic_view is non-authoritative; exact_statement wins on conflict.",
            ],
            allowed_tools=self._allowed_tools(),
            output_language=self.config.runtime.output_language,
        )
        store.write_json(
            "structured",
            "goal_interpretation",
            {
                "original_statement": problem.original_statement,
                "canonical_statement": problem.canonical_statement,
                "interpretation_source": problem.interpretation_source,
                "interpretation_confidence": problem.interpretation_confidence,
                "interpretation_reasons": problem.interpretation_reasons,
                "interpretation_agent_id": problem.interpretation_agent_id,
                "goal_hash": problem.goal_hash,
            },
        )
        activity.complete_task(
            task_id,
            title=activity.text(
                "题意预检完成，目标已冻结",
                "Problem preflight completed; goal frozen",
            ),
            detail=activity.text(
                (
                    "未发现歧义，原题原样保留且未调用模型"
                    if local_precheck.status == "clear"
                    else f"已按 {problem.interpretation_source} 冻结规范化目标"
                ),
                (
                    "No ambiguity found; the original statement was preserved without a model call"
                    if local_precheck.status == "clear"
                    else f"Canonical goal frozen from {problem.interpretation_source}"
                ),
            ),
            event_type="goal_preflight_completed",
            stage="goal_preflight",
            parent_task_id=parent_task_id,
            importance=ActivityImportance.MAJOR,
            metrics={
                "api_call": local_precheck.status == "model_review_required",
                "interpretation_source": problem.interpretation_source,
                "goal_hash": problem.goal_hash,
            },
        )
        return problem

    @staticmethod
    def _attach_problem_semantic_view(
        problem: ProblemContract,
        triage: TriageResult,
        store: ArtifactStore,
    ) -> None:
        candidate = triage.semantic_view_candidate
        if candidate is None or not contains_cjk(problem.exact_statement):
            return
        view = build_problem_semantic_view(problem.exact_statement, candidate)
        store.write_json("structured", "problem_semantic_view", view)
        store.append_event(
            "problem_semantic_view_audited",
            {
                "source_statement_hash": view.source_statement_hash,
                "status": view.status,
                "candidate_confidence": view.candidate_confidence,
                "missing_protected_fragments": view.missing_protected_fragments,
                "authoritative": False,
            },
        )
        if view.status == "usable":
            problem.semantic_view = view

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
        *,
        state: SolveState,
    ) -> list[StrategyCard]:
        scoped_discovery_task = (
            TaskRequirement.CONJECTURE in problem.task_requirements
            and set(problem.task_requirements)
            <= {
                TaskRequirement.COMPUTATION,
                TaskRequirement.CONJECTURE,
            }
        )
        requested = (
            1
            if scoped_discovery_task
            else min(
                self.config.budget.strategies_to_generate,
                max(self.config.budget.initial_paths, triage.suggested_paths),
            )
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
        target = 1 if scoped_discovery_task else self.config.budget.initial_paths
        candidates = self._deduplicate_strategy_cards(
            self._attach_planner_computation_hints(strategy_set.strategies)
        )
        # Hard problems get one extra INDEPENDENT sampling pass with the
        # first batch's mechanisms forbidden. Thinking-mode providers ignore
        # temperature, so negative-constraint resampling is the only real
        # diversity lever; a single-shot batch mode-collapses.
        if (
            result is not None
            and not scoped_discovery_task
            and triage.difficulty in {Difficulty.OLYMPIAD, Difficulty.RESEARCH}
            and runner.ledger.remaining_calls > 1
        ):
            resample = await self._safe_call(
                runner,
                "planner",
                prompts.strategies(
                    problem,
                    triage,
                    requested,
                    prior_strategy_titles=[item.title for item in candidates],
                    forbidden_mechanisms=[
                        f"{item.title}: {item.core_idea[:160]}" for item in candidates
                    ],
                ),
                budget_bucket="breadth",
            )
            if resample is not None:
                candidates = self._deduplicate_strategy_cards(
                    [
                        *candidates,
                        *self._attach_planner_computation_hints(
                            resample.value.strategies
                        ),
                    ]
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
        if state.proof_control is not None:
            admission_candidates = list(selected)
            selected, admission_records = state.proof_control.admit_routes(selected)
            semantic_repair_attempted = False
            if (
                state.proof_control.active
                and not selected
                and admission_records
                and runner.ledger.remaining_calls > 0
            ):
                # Cheapest recovery first: one batched reviewer call that
                # restates unparseable statements, then one re-admission of
                # the SAME mathematical strategies. Only if that fails do we
                # burn a full planner regeneration.
                (
                    selected,
                    admission_records,
                    semantic_repair_attempted,
                ) = await self._attempt_semantic_repair_admission(
                    problem,
                    candidates=admission_candidates,
                    state=state,
                    runner=runner,
                    prompts=prompts,
                    store=store,
                    prior_records=admission_records,
                )
            if (
                state.proof_control.active
                and not selected
                and admission_records
                and self.config.topology.proof_control.route_admission.max_regeneration_attempts
                > 0
                and runner.ledger.remaining_calls > 0
            ):
                review_task_kind = self._admission_regeneration_task_kind(
                    admission_records
                )
                review_task = state.proof_control.executable_task_controller.create_admission_review_task(
                    task_kind=review_task_kind,
                    target_obligation_ids=[
                        obligation_id
                        for record in admission_records
                        for obligation_id in record.target_obligation_ids
                    ],
                    strategy_ids=[record.strategy_id for record in admission_records],
                    created_round=state.current_round,
                    prompt_ref="route_admission_regeneration",
                )
                state.proof_control.executable_task_controller.mark_running(
                    review_task.task_id,
                    current_round=state.current_round,
                )
                regulator_feedback = [
                    "Proof-control route admission rejected every initial strategy.",
                    *[
                        f"{record.strategy_id}: " + "; ".join(record.reasons)
                        for record in admission_records
                    ],
                    (
                        "Regenerate genuinely different strategies that target the "
                        "registered main obligation, state one exact falsification "
                        "test, avoid necessary-only conclusions, and predict a core "
                        "obligation reduction."
                    ),
                ]
                regenerated = await self._safe_call(
                    runner,
                    "planner",
                    prompts.strategies(
                        problem,
                        triage,
                        target,
                        prior_strategy_titles=[
                            item.title for item in strategy_set.strategies
                        ],
                        regulator_feedback=regulator_feedback,
                    ),
                    budget_bucket="breadth",
                )
                regenerated_candidates = (
                    regenerated.value.strategies if regenerated is not None else []
                )
                regenerated_candidates = self._deduplicate_strategy_cards(
                    self._attach_planner_computation_hints(regenerated_candidates)
                )
                regenerated_selected = router.select_diverse_strategies(
                    regenerated_candidates,
                    min(target, len(regenerated_candidates)),
                )
                selected, admission_records = state.proof_control.admit_routes(
                    regenerated_selected
                )
                if regenerated is None:
                    state.proof_control.executable_task_controller.fail(
                        review_task.task_id,
                        current_round=state.current_round,
                        reason="planner_regeneration_failed",
                    )
                else:
                    state.proof_control.executable_task_controller.complete_work(
                        review_task.task_id,
                        current_round=state.current_round,
                        result_refs=[
                            f"regenerated:{len(regenerated_selected)}",
                            f"admitted:{len(selected)}",
                        ],
                        reason="bounded_route_admission_regeneration_completed",
                    )
                store.append_event(
                    "proof_control_strategy_regeneration",
                    {
                        "attempt": 1,
                        "candidate_count": len(regenerated_selected),
                        "admitted_count": len(selected),
                    },
                )
            if state.proof_control.active and not selected and admission_records:
                starvation = self._classify_admission_starvation(admission_records)
                starvation["repair_attempted"] = semantic_repair_attempted
                if semantic_repair_attempted:
                    starvation["repair_exhausted"] = True
                state.admission_starvation = starvation
                state.termination_reason = self._admission_termination_reason(
                    starvation
                )
                store.append_event("admission_starvation_detected", starvation)
                store.append_event(
                    "no_routes_admitted",
                    {
                        "category": starvation["category"],
                        "strategy_count": len(admission_records),
                    },
                )
        store.write_json("structured", "strategy_set", strategy_set)
        store.write_json("structured", "selected_strategies", selected)
        return selected

    def _restore_baseline_budget_limits(self) -> None:
        budget = self.config.budget
        continuation = self.config.continuation
        budget.max_total_calls = self._budget_scaling_baseline["max_total_calls"]
        budget.max_rounds = self._budget_scaling_baseline["max_rounds"]
        continuation.max_segments_per_path = self._budget_scaling_baseline[
            "max_segments_per_path"
        ]

    def _apply_difficulty_budget_scaling(
        self,
        triage: TriageResult,
        store: ArtifactStore,
    ) -> dict[str, Any]:
        """Apply an opt-in, idempotent effective budget for this run.

        42 calls versus 12-segment depth was arithmetically unreachable:
        every real hard run died at segment 1. Token and cost ceilings are
        deliberately NOT scaled — they remain the user's hard spend limits.
        """
        self._restore_baseline_budget_limits()
        budget = self.config.budget
        continuation = self.config.continuation
        eligible = bool(
            budget.scale_budget_with_difficulty
            and triage.difficulty in {Difficulty.OLYMPIAD, Difficulty.RESEARCH}
            and (
                budget.hard_problem_call_multiplier > 1.0
                or budget.hard_problem_extra_rounds > 0
            )
        )
        if eligible:
            budget.max_total_calls = min(
                10000,
                int(
                    self._budget_scaling_baseline["max_total_calls"]
                    * budget.hard_problem_call_multiplier
                ),
            )
            budget.max_rounds = min(
                64,
                self._budget_scaling_baseline["max_rounds"]
                + budget.hard_problem_extra_rounds,
            )
            continuation.max_segments_per_path = min(
                64,
                int(
                    self._budget_scaling_baseline["max_segments_per_path"]
                    * budget.hard_problem_call_multiplier
                ),
            )
        effective = {
            "max_total_calls": budget.max_total_calls,
            "max_rounds": budget.max_rounds,
            "max_segments_per_path": continuation.max_segments_per_path,
        }
        record = {
            "difficulty": triage.difficulty.value,
            "enabled": budget.scale_budget_with_difficulty,
            "applied": effective != self._budget_scaling_baseline,
            "baseline": dict(self._budget_scaling_baseline),
            "effective": effective,
            "max_total_tokens_unchanged": self.config.budget.max_total_tokens,
            "max_cost_usd_unchanged": self.config.budget.max_cost_usd,
        }
        store.write_json("structured", "difficulty_budget_scaling", record)
        store.write_json("structured", "config_redacted", self.config.redacted_dict())
        store.append_event("difficulty_budget_scaling_evaluated", record)
        if record["applied"]:
            store.append_event("difficulty_budget_scaled", record)
        return record

    async def _attempt_semantic_repair_admission(
        self,
        problem: ProblemContract,
        *,
        candidates: list[StrategyCard],
        state: SolveState,
        runner: StructuredAgentRunner,
        prompts: PromptFactory,
        store: ArtifactStore,
        prior_records: list[RouteAdmissionRecord],
    ) -> tuple[list[StrategyCard], list[RouteAdmissionRecord], bool]:
        """Batched form-repair of NEEDS_NORMALIZATION statements + one
        re-admission of the same mathematical strategies.

        Budget contract: calls are booked to the breadth bucket and hard
        capped at min(max_semantic_repair_calls, fraction * total budget).
        Every batch has an explicit ExecutableTaskRecord with a terminal
        reason, so the repair path is auditable and can never dangle.
        """
        control = state.proof_control
        cfg = self.config.topology.proof_control.route_admission
        if control is None or not cfg.semantic_repair_enabled:
            return [], prior_records, False
        backlog = control.normalization_backlog(candidates)
        if not backlog:
            return [], prior_records, False
        cap = min(
            cfg.max_semantic_repair_calls,
            int(
                cfg.semantic_repair_budget_fraction * self.config.budget.max_total_calls
            ),
        )
        if cap < 1 or runner.ledger.remaining_calls <= 1:
            store.append_event(
                "semantic_repair_refused",
                {"reason": "budget", "backlog": len(backlog)},
            )
            return [], prior_records, False
        tasks = control.executable_task_controller
        batch_size = min(
            cfg.blueprint_review.max_nodes_per_batch,
            cfg.blueprint_review.max_batch_repair_items,
        )
        current_candidates = list(candidates)
        current_records = list(prior_records)
        calls_made = 0
        for repair_round in range(cfg.blueprint_review.max_repair_rounds):
            backlog = control.normalization_backlog(current_candidates)
            if not backlog or calls_made >= cap:
                break
            replacements: dict[str, str] = {}
            round_call_cap = min(
                cap - calls_made,
                cfg.blueprint_review.max_review_calls_per_round,
            )
            calls_before_round = calls_made
            for offset in range(0, len(backlog), batch_size):
                if (
                    calls_made - calls_before_round >= round_call_cap
                    or runner.ledger.remaining_calls <= 1
                ):
                    break
                batch = backlog[offset : offset + batch_size]
                task = tasks.create_admission_review_task(
                    task_kind="batch_repair",
                    target_obligation_ids=[item["node_id"] for item in batch],
                    strategy_ids=[item["strategy_id"] for item in batch],
                    created_round=state.current_round,
                    prompt_ref=f"normalize_statements:round:{repair_round}",
                )
                if task.status.value in {
                    "completed",
                    "inconclusive",
                    "failed",
                    "expired",
                }:
                    store.append_event(
                        "semantic_repair_task_replay_suppressed",
                        {
                            "task_id": task.task_id,
                            "status": task.status.value,
                        },
                    )
                    continue
                tasks.mark_running(task.task_id, current_round=state.current_round)
                result = await self._safe_call(
                    runner,
                    "structural_verifier",
                    prompts.normalize_statements(
                        problem,
                        [
                            {
                                "statement": item["statement"],
                                "needs": item["needs"],
                            }
                            for item in batch
                        ],
                    ),
                    budget_bucket="breadth",
                )
                calls_made += 1
                if result is None:
                    tasks.fail(
                        task.task_id,
                        current_round=state.current_round,
                        reason="normalizer_call_failed",
                    )
                    continue
                batch_replacements = 0
                for item in result.value.items:
                    original = item.original_statement.strip()
                    normalized = item.normalized_statement.strip()
                    if (
                        item.is_mathematical_proposition
                        and original
                        and normalized
                        and normalized != original
                    ):
                        replacements[original] = normalized
                        batch_replacements += 1
                tasks.complete_work(
                    task.task_id,
                    current_round=state.current_round,
                    result_refs=[f"normalized:{batch_replacements}"],
                    reason="batched_normalization_completed",
                )
            if calls_made == calls_before_round or not replacements:
                break
            current_candidates = control.apply_normalized_statements(
                current_candidates,
                replacements,
            )
            selected, current_records = control.admit_routes(current_candidates)
            store.append_event(
                "semantic_repair_readmission",
                {
                    "repair_round": repair_round + 1,
                    "candidate_count": len(current_candidates),
                    "admitted_count": len(selected),
                    "normalized": len(replacements),
                },
            )
            if selected:
                store.append_event(
                    "semantic_repair_completed",
                    {
                        "backlog": len(backlog),
                        "normalized": len(replacements),
                        "calls": calls_made,
                        "call_cap": cap,
                        "repair_rounds": repair_round + 1,
                    },
                )
                return selected, current_records, True
        if calls_made == 0:
            return [], prior_records, False
        store.append_event(
            "semantic_repair_completed",
            {
                "backlog": len(backlog),
                "calls": calls_made,
                "call_cap": cap,
                "repair_rounds": cfg.blueprint_review.max_repair_rounds,
            },
        )
        return [], current_records, True

    @staticmethod
    def _admission_regeneration_task_kind(
        records: Sequence[RouteAdmissionRecord],
    ) -> Literal[
        "blueprint_review",
        "repair_direct_target",
        "edge_review",
        "generate_plan",
    ]:
        reasons = " ".join(
            reason for record in records for reason in record.reasons
        ).casefold()
        if "direct target" in reasons or "could not be aligned" in reasons:
            return "repair_direct_target"
        if "edge" in reasons or "blueprint path" in reasons:
            return "edge_review"
        if "blueprint is unavailable" in reasons:
            return "blueprint_review"
        return "generate_plan"

    @staticmethod
    def _classify_admission_starvation(
        records: Sequence[RouteAdmissionRecord],
    ) -> dict[str, Any]:
        """Separate 'the gate is broken' from 'the strategies are bad'.

        A homogeneous batch of parsing/normalization rejections is a
        repairable control-plane failure: regenerating or widening the
        planner re-runs the same doomed pipeline and must be stopped.
        """
        repairable_markers = (
            "no semantically admissible direct target",
            "needs normalization",
            "not materialized",
            "system parsing issue",
            "blueprint is unavailable",
            "could not be aligned",
            "not_truth_apt",
            "missing_explicit",
            "missing_quantifier",
        )
        mathematical_markers = (
            "refuted",
            "duplicate",
            "unrelated",
            "scope_invalid",
            "necessary-only",
        )
        repairable = 0
        mathematical = 0
        for record in records:
            joined = " ".join(record.reasons).casefold()
            if any(marker in joined for marker in repairable_markers):
                repairable += 1
            elif any(marker in joined for marker in mathematical_markers):
                mathematical += 1
        total = max(1, len(records))
        if repairable / total >= 0.8:
            category = "systemic_semantic_failure"
        elif mathematical / total >= 0.8:
            category = "strategy_space_exhausted"
        else:
            category = "mixed_admission_failure"
        return {
            "category": category,
            "repairable_count": repairable,
            "mathematical_count": mathematical,
            "total": len(records),
            "sample_reasons": [
                reason for record in records[:4] for reason in record.reasons[:2]
            ],
        }

    @staticmethod
    def _admission_termination_reason(starvation: Mapping[str, Any]) -> str:
        if starvation.get("repair_exhausted"):
            return "NO_ROUTES_ADMITTED(repair_exhausted)"
        category = str(starvation.get("category") or "unknown")
        return f"NO_ROUTES_ADMITTED({category})"

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

    @staticmethod
    def _computation_stalled_rounds(
        state: SolveState | None,
        strategy: StrategyCard,
        *,
        round_index: int,
    ) -> int:
        if state is None or state.route_registry is None:
            return max(0, round_index)
        route = state.route_registry.route_for_strategy(strategy.strategy_id)
        if route is None:
            return 0
        return max(route.stagnation_rounds, route.no_progress_strikes)

    async def _run_materialized_falsification_tasks(
        self,
        problem: ProblemContract,
        strategy: StrategyCard,
        author: AgentRuntime,
        *,
        state: SolveState | None,
        round_index: int,
        path_id: str,
        parent_checkpoint_id: str | None,
        meta_review_approved: bool,
        runner: StructuredAgentRunner,
        prompts: PromptFactory,
        tools: ToolBroker,
        budget_bucket: str,
    ) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
        if (
            state is None
            or state.proof_control is None
            or not state.proof_control.active
        ):
            return [], []
        route = (
            state.route_registry.route_for_strategy(strategy.strategy_id)
            if state.route_registry is not None
            else None
        )
        route_id = route.route_id if route is not None else None
        lane = self.config.topology.proof_control.falsification_fast_lane
        tasks = state.proof_control.pending_falsification_specs(
            strategy.strategy_id,
            route_id=route_id,
        )[: lane.max_tasks_per_round]
        feedback: list[dict[str, Any]] = []
        results: list[dict[str, Any]] = []
        for task in tasks:
            assert task.experiment_spec is not None
            state.proof_control.mark_falsification_running(task.task_id)
            try:
                decision, result = await self._run_requested_computation(
                    problem,
                    task.experiment_spec.model_copy(deep=True),
                    author,
                    path_id=path_id,
                    parent_checkpoint_id=parent_checkpoint_id,
                    stalled_rounds=self._computation_stalled_rounds(
                        state,
                        strategy,
                        round_index=round_index,
                    ),
                    meta_review_approved=meta_review_approved,
                    runner=runner,
                    prompts=prompts,
                    tools=tools,
                    budget_bucket=budget_bucket,
                    state=state,
                    proof_control_task_id=task.task_id,
                    proof_control_target_obligation_id=(task.target_obligation_id),
                    proof_control_target_claim_id=task.target_claim_id,
                )
            except BaseException as exc:
                state.proof_control.record_falsification_execution_failure(
                    task.task_id,
                    error=exc,
                )
                raise
            feedback.append(decision.model_dump(mode="json"))
            if result is None:
                state.proof_control.record_falsification_decision(
                    task.task_id,
                    decision=decision.decision.value,
                    reason=decision.reason,
                )
                continue
            results.append(result.model_dump(mode="json"))
        return feedback, results

    async def _retry_deferred_computations(
        self,
        problem: ProblemContract,
        strategy: StrategyCard,
        author: AgentRuntime,
        *,
        state: SolveState | None,
        round_index: int,
        path_id: str,
        parent_checkpoint_id: str | None,
        meta_review_approved: bool,
        runner: StructuredAgentRunner,
        prompts: PromptFactory,
        tools: ToolBroker,
        budget_bucket: str,
        max_experiments: int,
    ) -> tuple[list[dict[str, Any]], list[dict[str, Any]], int, bool]:
        feedback: list[dict[str, Any]] = []
        results: list[dict[str, Any]] = []
        executed = 0
        still_deferred = False
        stalled_rounds = self._computation_stalled_rounds(
            state,
            strategy,
            round_index=round_index,
        )
        pending_requests = tools.deferred_for_path(path_id)
        if max_experiments <= 0:
            return feedback, results, executed, bool(pending_requests)
        for pending in pending_requests:
            if executed >= max_experiments:
                break
            spec = pending.spec.model_copy(deep=True)
            spec.path_id = path_id
            spec.parent_checkpoint_id = parent_checkpoint_id
            tools.update_experiment_activity(
                spec,
                phase="deferred_retry",
                detail=(
                    "The saved request is being re-evaluated against current "
                    "route progress and Meta-Reviewer state."
                ),
                event_type="computation_deferred_retry",
                progress=0.1,
                metrics={
                    "defer_count": pending.defer_count,
                    "stalled_rounds": stalled_rounds,
                    "meta_review_approved": meta_review_approved,
                },
            )
            decision, experiment = await self._run_requested_computation(
                problem,
                spec,
                author,
                path_id=path_id,
                parent_checkpoint_id=parent_checkpoint_id,
                stalled_rounds=stalled_rounds,
                meta_review_approved=meta_review_approved,
                runner=runner,
                prompts=prompts,
                tools=tools,
                budget_bucket=budget_bucket,
                state=state,
            )
            feedback.append(decision.model_dump(mode="json"))
            if experiment is not None:
                results.append(experiment.model_dump(mode="json"))
                executed += 1
            elif decision.decision == ComputationDecisionStatus.DEFER:
                still_deferred = True
                break
        return feedback, results, executed, still_deferred

    @staticmethod
    def _successful_pattern_results(
        tools: ToolBroker,
        experiment_results: Sequence[dict[str, Any]],
    ) -> list[dict[str, Any]]:
        successful: list[dict[str, Any]] = []
        for payload in experiment_results:
            if payload.get("outcome") not in {
                ExperimentOutcome.NOT_REFUTED.value,
                ExperimentOutcome.CERTIFIED.value,
            }:
                continue
            request_hash = str(payload.get("request_hash") or "")
            if not request_hash:
                continue
            try:
                spec = tools.cache.load_spec(request_hash)
            except (FileNotFoundError, OSError, ValueError):
                continue
            if spec.purpose == ComputationPurpose.DISCOVER_PATTERN:
                successful.append(payload)
        return successful

    @staticmethod
    def _attach_pattern_evidence(
        candidates: Sequence[CandidateConjecture],
        results_by_id: dict[str, dict[str, Any]],
        *,
        proposal_raw_ref: str | None,
    ) -> None:
        for candidate in candidates:
            candidate.evidence_refs = []
            for experiment_id in candidate.supporting_experiment_ids:
                payload = results_by_id.get(experiment_id)
                if payload is None:
                    continue
                for raw_ref in payload.get("artifact_refs", []):
                    try:
                        evidence = (
                            EvidenceRef(artifact_ref=raw_ref)
                            if isinstance(raw_ref, str)
                            else EvidenceRef.model_validate(raw_ref)
                        )
                    except (TypeError, ValueError):
                        continue
                    if not any(
                        item.artifact_ref == evidence.artifact_ref
                        for item in candidate.evidence_refs
                    ):
                        candidate.evidence_refs.append(evidence)
            if proposal_raw_ref and not any(
                item.artifact_ref == proposal_raw_ref
                for item in candidate.evidence_refs
            ):
                candidate.evidence_refs.append(
                    EvidenceRef(
                        artifact_ref=proposal_raw_ref,
                        summary=(
                            "Raw structured response that proposed this unproved "
                            "candidate conjecture."
                        ),
                    )
                )

    @staticmethod
    def _canonicalize_candidate_experiment_ids(
        candidate: CandidateConjecture,
        alias_to_experiment_id: dict[str, str],
    ) -> CandidateConjecture | None:
        canonical_ids: list[str] = []
        for identifier in candidate.supporting_experiment_ids:
            canonical = alias_to_experiment_id.get(identifier)
            if canonical is None:
                return None
            if canonical not in canonical_ids:
                canonical_ids.append(canonical)
        payload = candidate.model_dump(mode="json")
        payload["supporting_experiment_ids"] = canonical_ids
        payload["content_hash"] = ""
        return CandidateConjecture.model_validate(payload)

    async def _ensure_pattern_conjectures(
        self,
        problem: ProblemContract,
        candidates: Sequence[CandidateConjecture],
        experiment_results: Sequence[dict[str, Any]],
        *,
        agent: AgentRuntime,
        runner: StructuredAgentRunner,
        prompts: PromptFactory,
        tools: ToolBroker,
        store: ArtifactStore,
        budget_bucket: str,
    ) -> tuple[list[CandidateConjecture], UsageRecord, set[str]]:
        pattern_results = self._successful_pattern_results(tools, experiment_results)
        if not pattern_results:
            return list(candidates), UsageRecord(), set()

        results_by_id: dict[str, dict[str, Any]] = {}
        alias_to_id: dict[str, str] = {}
        ambiguous_aliases: set[str] = set()
        for payload in pattern_results:
            observed_experiment_id = str(payload.get("experiment_id") or "")
            request_hash = str(payload.get("request_hash") or "")
            if not observed_experiment_id or not request_hash:
                continue
            canonical_result = tools.cache.get(request_hash)
            experiment_id = (
                canonical_result.experiment_id
                if canonical_result is not None
                else observed_experiment_id
            )
            canonical_payload = dict(payload)
            canonical_payload["experiment_id"] = experiment_id
            results_by_id[experiment_id] = canonical_payload
            aliases = {
                experiment_id,
                observed_experiment_id,
                request_hash,
                *tools.cache.aliases_for(request_hash),
            }
            for alias in aliases:
                previous = alias_to_id.get(alias)
                if previous is not None and previous != experiment_id:
                    ambiguous_aliases.add(alias)
                    continue
                alias_to_id[alias] = experiment_id
        for alias in ambiguous_aliases:
            alias_to_id.pop(alias, None)

        allowed_ids = set(results_by_id)
        accepted: list[CandidateConjecture] = []
        seen_hashes: set[str] = set()
        for raw_candidate in candidates:
            candidate = self._canonicalize_candidate_experiment_ids(
                raw_candidate, alias_to_id
            )
            if candidate is None:
                continue
            referenced = set(candidate.supporting_experiment_ids)
            if not referenced or not referenced.issubset(allowed_ids):
                continue
            if candidate.content_hash in seen_hashes:
                continue
            seen_hashes.add(candidate.content_hash)
            accepted.append(candidate)

        covered = {
            experiment_id
            for candidate in accepted
            for experiment_id in candidate.supporting_experiment_ids
        }
        missing = allowed_ids - covered
        repair_usage = UsageRecord()
        proposal_raw_ref: str | None = None
        if missing:
            bundle = prompts.complete_pattern_conjectures(
                problem,
                [results_by_id[experiment_id] for experiment_id in sorted(missing)],
                [item.model_dump(mode="json") for item in accepted],
            )
            try:
                completion = await self._safe_call(
                    runner,
                    "explorer",
                    bundle,
                    fixed_agent=agent,
                    budget_bucket=budget_bucket,
                )
            except (BudgetExhaustedError, ProviderCircuitOpenError) as exc:
                store.append_event(
                    "pattern_conjecture_completion_failed",
                    {
                        "agent_id": agent.id,
                        "missing_experiment_ids": sorted(missing),
                        "error_type": type(exc).__name__,
                    },
                )
                completion = None
            if completion is not None:
                repair_usage = completion.usage
                proposal_raw_ref = completion.raw_ref
                batch = completion.value
                if isinstance(batch, CandidateConjectureBatch):
                    for raw_candidate in batch.candidate_conjectures:
                        candidate = self._canonicalize_candidate_experiment_ids(
                            raw_candidate, alias_to_id
                        )
                        if candidate is None:
                            continue
                        referenced = set(candidate.supporting_experiment_ids)
                        if (
                            not referenced
                            or not referenced.issubset(allowed_ids)
                            or not referenced.intersection(missing)
                            or candidate.content_hash in seen_hashes
                        ):
                            continue
                        seen_hashes.add(candidate.content_hash)
                        accepted.append(candidate)

        self._attach_pattern_evidence(
            accepted,
            results_by_id,
            proposal_raw_ref=proposal_raw_ref,
        )
        covered = {
            experiment_id
            for candidate in accepted
            for experiment_id in candidate.supporting_experiment_ids
        }
        missing = allowed_ids - covered
        store.append_event(
            (
                "pattern_conjecture_completion_failed"
                if missing
                else "pattern_conjecture_completed"
            ),
            {
                "agent_id": agent.id,
                "experiment_ids": sorted(allowed_ids),
                "candidate_count": len(accepted),
                "missing_experiment_ids": sorted(missing),
                "repair_call_used": bool(proposal_raw_ref),
            },
        )
        return accepted, repair_usage, missing

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
        state: SolveState | None = None,
        proof_control_task_id: str | None = None,
        proof_control_target_obligation_id: str | None = None,
        proof_control_target_claim_id: str | None = None,
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
            proof_control_fast_lane=bool(
                state is not None
                and state.proof_control is not None
                and state.proof_control.active
            ),
            target_obligation_id=(
                proof_control_target_obligation_id
                or (
                    state.proof_graph.main_goal_obligation_ids()[0]
                    if state is not None
                    and state.proof_control is not None
                    and state.proof_graph is not None
                    and state.proof_graph.main_goal_obligation_ids()
                    else None
                )
            ),
            target_claim_id=(
                proof_control_target_claim_id
                or (
                    f"claim_{stable_hash(spec.target_claim)[:12]}"
                    if state is not None and state.proof_control is not None
                    else None
                )
            ),
            fast_lane_tasks_this_round=sum(
                item.parent_checkpoint_id == parent_checkpoint_id
                for item in tools.results_for_path(path_id)
            ),
        )
        decision = tools.decide(spec, context)
        if decision.rule_id in {
            "request.invalid_tool_contract",
            "request.invalid_precision_claim",
            "sandbox.typed_tool_first",
            "sandbox.disabled",
            "tool.unavailable",
        }:
            decision, repaired_spec = await self._repair_computation_contract(
                problem,
                spec,
                decision,
                author,
                context=context,
                runner=runner,
                prompts=prompts,
                tools=tools,
                budget_bucket=budget_bucket,
            )
            if repaired_spec is None:
                return decision, None
            spec = repaired_spec
        if decision.decision != ComputationDecisionStatus.ALLOW:
            return decision, None

        program: ExperimentProgram | None = None
        if spec.method == ComputationMethod.SANDBOXED_PYTHON and not decision.cache_hit:
            tools.update_experiment_activity(
                spec,
                phase="code_generation",
                detail=(
                    "正在生成受约束的 Python 实验程序"
                    if self.config.runtime.output_language.lower().startswith("zh")
                    else "Generating the constrained Python experiment program"
                ),
                event_type="computation_code_generation",
                progress=0.35,
                metrics={
                    "decision": decision.decision.value,
                    "decision_reason": decision.reason,
                    "rule_id": decision.rule_id,
                    "cache_hit": decision.cache_hit,
                },
            )
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
            else:
                failed_decision = decision.model_copy(deep=True)
                failed_decision.decision = ComputationDecisionStatus.REJECT
                failed_decision.reason = (
                    "The bounded sandbox fallback was admitted, but no valid "
                    "ExperimentProgram was generated. Nothing was executed."
                )
                failed_decision.rule_id = "sandbox.codegen_failed"
                tools.cache.save_decision(failed_decision)
                tools.update_experiment_activity(
                    spec,
                    phase="code_generation_failed",
                    detail=failed_decision.reason,
                    status=ActivityStatus.FAILED,
                    event_type="computation_code_generation_failed",
                    progress=1.0,
                    metrics={
                        "decision": failed_decision.decision.value,
                        "decision_reason": failed_decision.reason,
                        "rule_id": failed_decision.rule_id,
                        "cache_hit": False,
                        "contract_repair_status": (
                            failed_decision.contract_repair_status.value
                        ),
                        "original_request_hash": (
                            failed_decision.original_request_hash
                        ),
                        "contract_repair_reason": (
                            failed_decision.contract_repair_reason
                        ),
                    },
                )
                return failed_decision, None

        # Sandbox execution is blocking. Run it off the event loop so the desktop
        # can receive and render the already-created computation node immediately.
        result = await asyncio.to_thread(
            tools.run_experiment,
            spec,
            decision,
            program=program,
        )
        if (
            state is not None
            and state.proof_control is not None
            and (
                proof_control_task_id is not None
                or decision.rule_id == "fast_path.proof_control_falsification"
            )
        ):
            state.proof_control.record_falsification_result(
                result,
                task_id=proof_control_task_id,
            )
        return decision, result

    async def _repair_computation_contract(
        self,
        problem: ProblemContract,
        original_spec: ExperimentSpec,
        original_decision: ComputationDecision,
        author: AgentRuntime,
        *,
        context: ComputationContext,
        runner: StructuredAgentRunner,
        prompts: PromptFactory,
        tools: ToolBroker,
        budget_bucket: str,
    ) -> tuple[ComputationDecision, ExperimentSpec | None]:
        issues = validate_experiment_contract(original_spec)
        if not issues:
            issues = [original_decision.reason]
        if self.config.computation.max_contract_repairs_per_segment <= 0:
            return (
                self._finish_computation_contract_repair(
                    original_spec,
                    original_decision,
                    tools=tools,
                    status=ComputationContractRepairStatus.DISABLED,
                    reason=(
                        "The request was not executed because bounded contract "
                        "repair is disabled in this profile."
                    ),
                    issues=issues,
                ),
                None,
            )
        if runner.ledger.remaining_calls <= 0:
            return (
                self._finish_computation_contract_repair(
                    original_spec,
                    original_decision,
                    tools=tools,
                    status=ComputationContractRepairStatus.FAILED,
                    reason=(
                        "The request was not executed because no LLM call remained "
                        "for the bounded contract repair."
                    ),
                    issues=issues,
                ),
                None,
            )

        repair_agent = author
        repair_role = "explorer"
        for role in ("experimenter", "planner"):
            try:
                repair_agent = runner.pool.select(role, exclude={author.id})
                repair_role = role
                break
            except RuntimeError:
                continue
        if repair_agent is author:
            if "planner" in author.config.roles:
                repair_role = "planner"
            elif "experimenter" in author.config.roles:
                repair_role = "experimenter"

        sandbox_enabled = (
            self.config.computation.sandboxed_python_enabled
            and ComputationMethod.SANDBOXED_PYTHON.value in problem.allowed_tools
        )
        base_bundle = prompts.computation_contract_repair(
            problem,
            original_spec,
            issues,
            repair_agent.id,
            sandbox_enabled=sandbox_enabled,
        )
        bundle = PromptBundle(
            base_bundle.stage,
            base_bundle.system,
            base_bundle.user,
            base_bundle.response_model,
            temperature=0.0,
            max_output_tokens=min(
                self.config.computation.contract_repair_max_output_tokens,
                repair_agent.config.max_output_tokens,
            ),
            output_tier=base_bundle.output_tier,
        )
        tools.update_experiment_activity(
            original_spec,
            phase="contract_repair",
            detail=(
                "The request failed pre-execution validation; compiling one "
                "semantics-preserving replacement."
            ),
            event_type="computation_contract_repair_started",
            progress=0.12,
            metrics={
                "decision": original_decision.decision.value,
                "decision_reason": original_decision.reason,
                "rule_id": original_decision.rule_id,
                "cache_hit": False,
                "contract_repair_status": "running",
                "original_request_hash": original_spec.request_hash,
            },
        )
        repair_result = await self._safe_call(
            runner,
            repair_role,
            bundle,
            fixed_agent=repair_agent,
            budget_bucket=budget_bucket,
        )
        if repair_result is None:
            return (
                self._finish_computation_contract_repair(
                    original_spec,
                    original_decision,
                    tools=tools,
                    status=ComputationContractRepairStatus.FAILED,
                    reason=(
                        "The single bounded repair call returned no valid "
                        "ComputationContractRepair object. Nothing was executed."
                    ),
                    issues=issues,
                    repair_agent_id=repair_agent.id,
                ),
                None,
            )

        repair: ComputationContractRepair = repair_result.value
        if repair.action == ComputationContractRepairAction.ABANDON_AS_UNREPRESENTABLE:
            return (
                self._finish_computation_contract_repair(
                    original_spec,
                    original_decision,
                    tools=tools,
                    status=ComputationContractRepairStatus.ABANDONED,
                    reason=repair.reason,
                    issues=issues,
                    repair=repair,
                    repair_agent_id=repair_result.agent.id,
                    raw_ref=repair_result.raw_ref,
                ),
                None,
            )

        assert repair.repaired_spec is not None
        repaired_spec = repair.repaired_spec
        immutable_fields = (
            "purpose",
            "target_claim",
            "assumptions",
            "reasoning_basis",
            "why_computation_is_needed",
            "decision_if_confirmed",
            "decision_if_refuted",
            "noncomputational_alternative",
            "broad_search",
            "max_cases",
            "seed",
        )
        changed_fields = [
            name
            for name in immutable_fields
            if getattr(repaired_spec, name) != getattr(original_spec, name)
        ]
        if changed_fields:
            return (
                self._finish_computation_contract_repair(
                    original_spec,
                    original_decision,
                    tools=tools,
                    status=ComputationContractRepairStatus.FAILED,
                    reason=(
                        "The repair attempted to change immutable computation "
                        f"semantics: {', '.join(changed_fields)}. Nothing was executed."
                    ),
                    issues=issues,
                    repair=repair,
                    repair_agent_id=repair_result.agent.id,
                    raw_ref=repair_result.raw_ref,
                ),
                None,
            )

        repaired_payload = repaired_spec.model_dump(
            mode="json",
            exclude={"request_hash", "runtime_fingerprint"},
        )
        repaired_payload.update(
            {
                "experiment_id": original_spec.experiment_id,
                "requested_by": author.id,
                "path_id": context.path_id,
                "parent_checkpoint_id": original_spec.parent_checkpoint_id,
                "runtime_fingerprint": {},
                "request_hash": "",
            }
        )
        repaired_spec = ExperimentSpec.model_validate(repaired_payload)
        if (
            problem.allowed_tools
            and repaired_spec.method.value not in problem.allowed_tools
        ):
            repaired_issues = [
                f"method {repaired_spec.method.value} is not enabled for this run"
            ]
        else:
            repaired_issues = validate_experiment_contract(repaired_spec)
        if repaired_issues:
            return (
                self._finish_computation_contract_repair(
                    original_spec,
                    original_decision,
                    tools=tools,
                    status=ComputationContractRepairStatus.FAILED,
                    reason=(
                        "The single replacement request still failed validation: "
                        + "; ".join(repaired_issues)
                    ),
                    issues=[*issues, *repaired_issues],
                    repair=repair,
                    repair_agent_id=repair_result.agent.id,
                    raw_ref=repair_result.raw_ref,
                ),
                None,
            )

        repaired_context = ComputationContext(
            path_id=context.path_id,
            stalled_rounds=context.stalled_rounds,
            meta_review_approved=context.meta_review_approved,
            remaining_llm_calls=runner.ledger.remaining_calls,
        )
        repaired_decision = tools.decide(repaired_spec, repaired_context)
        repaired_decision.contract_repair_status = (
            ComputationContractRepairStatus.SUCCEEDED
        )
        repaired_decision.original_request_hash = original_spec.request_hash
        repaired_decision.contract_repair_reason = repair.reason
        tools.cache.save_decision(repaired_decision)
        record = self._computation_contract_repair_record(
            original_spec,
            repaired_spec,
            original_decision,
            repaired_decision,
            status=ComputationContractRepairStatus.SUCCEEDED,
            reason=repair.reason,
            issues=issues,
            repair=repair,
            repair_agent_id=repair_result.agent.id,
            raw_ref=repair_result.raw_ref,
        )
        tools.store.write_experiment_artifact(
            original_spec.request_hash, "contract_repair", record
        )
        tools.store.write_experiment_artifact(
            repaired_spec.request_hash, "contract_repair", record
        )
        tools.store.append_event("computation_contract_repaired", record)
        tools.update_experiment_activity(
            repaired_spec,
            phase=(
                "contract_repaired"
                if repaired_decision.decision == ComputationDecisionStatus.ALLOW
                else repaired_decision.decision.value
            ),
            detail=(
                "Contract repair succeeded. " + repaired_decision.reason
                if repaired_decision.decision == ComputationDecisionStatus.ALLOW
                else repaired_decision.reason
            ),
            status=(
                ActivityStatus.RUNNING
                if repaired_decision.decision == ComputationDecisionStatus.ALLOW
                else ActivityStatus.WARNING
            ),
            event_type=(
                "computation_contract_repaired"
                if repaired_decision.decision == ComputationDecisionStatus.ALLOW
                else "computation_not_executed"
            ),
            progress=(
                0.25
                if repaired_decision.decision == ComputationDecisionStatus.ALLOW
                else 1.0
            ),
            metrics={
                "decision": repaired_decision.decision.value,
                "decision_reason": repaired_decision.reason,
                "rule_id": repaired_decision.rule_id,
                "cache_hit": repaired_decision.cache_hit,
                "contract_repair_status": (
                    repaired_decision.contract_repair_status.value
                ),
                "original_request_hash": original_spec.request_hash,
                "contract_repair_reason": repair.reason,
            },
        )
        return repaired_decision, repaired_spec

    def _finish_computation_contract_repair(
        self,
        original_spec: ExperimentSpec,
        original_decision: ComputationDecision,
        *,
        tools: ToolBroker,
        status: ComputationContractRepairStatus,
        reason: str,
        issues: list[str],
        repair: ComputationContractRepair | None = None,
        repair_agent_id: str | None = None,
        raw_ref: str | None = None,
    ) -> ComputationDecision:
        final_decision = original_decision.model_copy(deep=True)
        final_decision.decision = ComputationDecisionStatus.REJECT
        final_decision.rule_id = {
            ComputationContractRepairStatus.ABANDONED: (
                "request.contract_unrepresentable"
            ),
            ComputationContractRepairStatus.DISABLED: (
                "request.contract_repair_disabled"
            ),
        }.get(status, "request.contract_repair_failed")
        final_decision.reason = reason
        final_decision.contract_repair_status = status
        final_decision.original_request_hash = original_spec.request_hash
        final_decision.contract_repair_reason = reason
        tools.cache.save_decision(final_decision)
        record = self._computation_contract_repair_record(
            original_spec,
            None,
            original_decision,
            final_decision,
            status=status,
            reason=reason,
            issues=issues,
            repair=repair,
            repair_agent_id=repair_agent_id,
            raw_ref=raw_ref,
        )
        tools.store.write_experiment_artifact(
            original_spec.request_hash, "contract_repair", record
        )
        tools.store.append_event("computation_contract_repair_finished", record)
        tools.update_experiment_activity(
            original_spec,
            phase=final_decision.rule_id,
            detail=reason,
            status=ActivityStatus.WARNING,
            event_type="computation_not_executed",
            progress=1.0,
            metrics={
                "decision": final_decision.decision.value,
                "decision_reason": final_decision.reason,
                "rule_id": final_decision.rule_id,
                "cache_hit": False,
                "contract_repair_status": status.value,
                "original_request_hash": original_spec.request_hash,
                "contract_repair_reason": reason,
            },
        )
        return final_decision

    @staticmethod
    def _computation_contract_repair_record(
        original_spec: ExperimentSpec,
        repaired_spec: ExperimentSpec | None,
        original_decision: ComputationDecision,
        final_decision: ComputationDecision,
        *,
        status: ComputationContractRepairStatus,
        reason: str,
        issues: list[str],
        repair: ComputationContractRepair | None,
        repair_agent_id: str | None,
        raw_ref: str | None,
    ) -> dict[str, Any]:
        return {
            "experiment_id": original_spec.experiment_id,
            "status": status.value,
            "issues": issues,
            "reason": reason,
            "original_request_hash": original_spec.request_hash,
            "original_method": original_spec.method.value,
            "repaired_request_hash": (
                repaired_spec.request_hash if repaired_spec is not None else None
            ),
            "repaired_method": (
                repaired_spec.method.value if repaired_spec is not None else None
            ),
            "original_decision": original_decision.model_dump(mode="json"),
            "final_decision": final_decision.model_dump(mode="json"),
            "repair_response": (
                repair.model_dump(mode="json") if repair is not None else None
            ),
            "repair_agent_id": repair_agent_id,
            "raw_artifact_ref": raw_ref,
        }

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
            control_hints=(
                state.proof_control.route_hints(route_id)
                if state.proof_control is not None
                else None
            ),
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
        if state.proof_control is not None and result.claim_dispositions:
            independent_reports = [
                item
                for item in [result.skeptic_result]
                if isinstance(item, VerificationReport)
                and item.verdict == VerificationVerdict.PASS
                and item.agent_id != author.id
            ]
            state.proof_control.apply_route_referee_records(
                result.claim_dispositions,
                claims=delta.new_claims,
                local_steps=delta.new_steps,
                structurally_verified_step_ids={
                    item.step_id for item in delta.new_steps
                },
                independent_report_ids=[item.report_id for item in independent_reports],
                confidence=(
                    min(item.confidence for item in independent_reports)
                    if independent_reports
                    else 1.0
                ),
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
            "skeptic_failure_level": (
                result.skeptic_result.failure_level.value
                if isinstance(result.skeptic_result, VerificationReport)
                else None
            ),
            "skeptic_confidence": (
                result.skeptic_result.confidence
                if isinstance(result.skeptic_result, VerificationReport)
                else None
            ),
            "skeptic_first_error_step": (
                result.skeptic_result.first_error_step
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
            "claim_dispositions": [
                item.model_dump(mode="json") for item in result.claim_dispositions
            ],
            "diagnostics": list(result.diagnostics),
        }
        if state.route_team_reviews is None:
            state.route_team_reviews = {}
        state.route_team_reviews.setdefault(attempt_id, []).append(summary)
        store.append_event("route_local_review_completed", summary)
        return result

    def _record_route_checkpoint_outcome(
        self,
        state: SolveState | None,
        *,
        attempt_id: str,
        delta: ProofDelta,
        reports: Sequence[VerificationReport],
        accepted: bool,
        feedback: str,
        store: ArtifactStore,
    ) -> None:
        """Persist delta rejection at route scope so a blank Attempt cannot erase it."""

        if state is None:
            return
        if state.route_team_reviews is None:
            state.route_team_reviews = {}
        reviews = state.route_team_reviews.setdefault(attempt_id, [])
        summary = next(
            (
                item
                for item in reversed(reviews)
                if item.get("delta_id") == delta.delta_id
            ),
            None,
        )
        if summary is None:
            summary = {
                "route_id": self._route_for_strategy(state, delta.strategy_id),
                "attempt_id": attempt_id,
                "delta_id": delta.delta_id,
                "prover_agent_id": delta.agent_id,
                "diagnostics": [],
            }
            reviews.append(summary)

        failed = [
            report for report in reports if report.verdict == VerificationVerdict.FAIL
        ]
        uncertain = [
            report
            for report in reports
            if report.verdict == VerificationVerdict.UNCERTAIN
        ]
        failure_order = {
            FailureLevel.NONE: 0,
            FailureLevel.EXECUTION: 1,
            FailureLevel.PLAN: 2,
            FailureLevel.STRATEGY: 3,
        }
        strongest = max(
            failed or uncertain,
            key=lambda report: (
                failure_order[report.failure_level],
                report.confidence,
            ),
            default=None,
        )
        summary.update(
            {
                "checkpoint_status": (
                    "accepted" if accepted else "rejected" if failed else "uncertain"
                ),
                "checkpoint_failure_level": (
                    strongest.failure_level.value
                    if strongest is not None
                    else FailureLevel.NONE.value
                ),
                "checkpoint_failure_confidence": (
                    strongest.confidence if strongest is not None else 0.0
                ),
                "checkpoint_first_error_step": (
                    strongest.first_error_step if strongest is not None else None
                ),
                "checkpoint_feedback": feedback,
            }
        )
        store.append_event(
            "route_checkpoint_outcome_recorded",
            {
                "attempt_id": attempt_id,
                "delta_id": delta.delta_id,
                "checkpoint_status": summary["checkpoint_status"],
                "failure_level": summary["checkpoint_failure_level"],
                "confidence": summary["checkpoint_failure_confidence"],
                "first_error_step": summary["checkpoint_first_error_step"],
            },
        )

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
        path_id = (
            previous_attempt.path_id
            if previous_attempt and previous_attempt.path_id
            else f"path_{strategy.strategy_id}"
        )
        calculation_gate = CriticalCalculationGate(self.config, tools, store)
        strategy_gate = calculation_gate.evaluate_strategy(
            strategy,
            path_id=path_id,
            requested_by=agent.id,
        )
        if not strategy_gate.passed:
            reason = strategy_gate.concise_failure()
            store.append_event(
                "strategy_blocked_by_critical_calculation_gate",
                {
                    "strategy_id": strategy.strategy_id,
                    "path_id": path_id,
                    "agent_id": agent.id,
                    "reason": reason,
                },
            )
            blocked = self._failed_attempt(
                problem,
                strategy,
                agent.id,
                round_index,
                RuntimeError(f"critical calculation gate blocked the route: {reason}"),
            )
            blocked.path_id = path_id
            blocked.dead_ends = [
                "Route admission blocked by the deterministic critical calculation "
                f"gate: {reason}"
            ]
            blocked.unresolved_gaps = [
                "Correct or declare the load-bearing finite calculation before "
                "exploring this route."
            ]
            return blocked
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
                problem,
                strategy,
                source_agent_id=agent.id,
                proof_sketch=(
                    previous_attempt.proof_sketch if previous_attempt else ""
                ),
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
        route_candidate_conjectures: list[CandidateConjecture] = list(
            previous_attempt.candidate_conjectures if previous_attempt else []
        )
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
            proof_control_closed_core_before = (
                {
                    item.obligation_id
                    for item in state.proof_graph.obligations_in_core_closure(
                        route_id=route_id
                    )
                    if item.status == "closed"
                }
                if state is not None
                and state.proof_control is not None
                and state.proof_graph is not None
                and route_id is not None
                else set()
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
                    state.route_registry.mark_stalled(
                        route.route_id,
                        signature=deep_admission.signature_hash,
                        reason=deep_admission.reason,
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
            (
                computation_feedback,
                experiment_results,
            ) = await self._run_materialized_falsification_tasks(
                problem,
                strategy,
                agent,
                state=state,
                round_index=round_index,
                path_id=path_id,
                parent_checkpoint_id=checkpoint.checkpoint_id,
                meta_review_approved=computation_meta_approved,
                runner=runner,
                prompts=prompts,
                tools=tools,
                budget_bucket=budget_bucket,
            )
            (
                deferred_feedback,
                deferred_results,
                compute_cycles,
                deferred_still_blocked,
            ) = await self._retry_deferred_computations(
                problem,
                strategy,
                agent,
                state=state,
                round_index=round_index,
                path_id=path_id,
                parent_checkpoint_id=checkpoint.checkpoint_id,
                meta_review_approved=computation_meta_approved,
                runner=runner,
                prompts=prompts,
                tools=tools,
                budget_bucket=budget_bucket,
                max_experiments=(
                    self.config.computation.max_compute_cycles_per_segment
                ),
            )
            computation_feedback.extend(deferred_feedback)
            experiment_results.extend(deferred_results)
            confirmed_counterexample_pending = any(
                item.get("outcome") == ExperimentOutcome.COUNTEREXAMPLE_FOUND.value
                and item.get("evidence_strength")
                == EvidenceStrength.COUNTEREXAMPLE.value
                and bool(item.get("independently_verified"))
                for item in experiment_results
            )
            delta: ProofDelta | None = None
            result: StructuredCallResult[Any] | None = None
            tried_agents: list[str] = []
            receipts_processed = False
            acknowledged_receipts: list[MessageReceipt] = []
            segment_usage = UsageRecord()
            deep_outcome: ExplorationOutcome | None = None
            deep_outcome_reason = ""

            if deferred_still_blocked:
                deep_outcome = ExplorationOutcome.NO_VERIFIED_PROGRESS
                deep_outcome_reason = (
                    "a durable computation request remains deferred by its "
                    "explicit stall or Meta-Reviewer gate"
                )
                self._finish_deep_exploration(
                    state,
                    deep_admission,
                    outcome=deep_outcome,
                    usage=segment_usage,
                    checkpoint_after=checkpoint,
                    proof_debt_before=proof_debt_before,
                    current_goal_before=current_goal_before_segment,
                    reason=deep_outcome_reason,
                    store=store,
                )
                break

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
                            negative_knowledge=self._negative_knowledge_context(
                                memory, state, strategy.strategy_id
                            ),
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
                    if confirmed_counterexample_pending:
                        self._apply_confirmed_counterexample_impact(
                            state,
                            strategy,
                            route_id=route_id,
                            impact=turn.experiment_impact,
                            experiment_results=experiment_results,
                            current_round=round_index,
                            store=store,
                        )
                if turn.action in {
                    ContinuationAction.SUBMIT_DELTA,
                    ContinuationAction.COMPLETE,
                }:
                    delta = turn.delta
                    if delta is not None:
                        # Bookkeeping fields are the server's, not the
                        # model's: backfill them so a copy typo burns neither
                        # a repair call nor the whole segment. The
                        # mathematical content is untouched.
                        delta = delta.model_copy(
                            update={
                                "problem_hash": problem.integrity_hash,
                                "path_id": checkpoint.path_id,
                                "strategy_id": checkpoint.strategy_id,
                                "parent_checkpoint_id": checkpoint.checkpoint_id,
                                "round_index": round_index,
                                "segment_index": next_segment,
                            }
                        )
                    if (
                        delta is not None
                        and delta.detected_conflicts
                        and not delta.new_steps
                        and cfg.allow_checkpoint_rollback
                    ):
                        rollback_parent = (
                            store.load_proof_checkpoint(
                                checkpoint.path_id,
                                checkpoint.parent_checkpoint_id,
                            )
                            if checkpoint.parent_checkpoint_id is not None
                            else None
                        )
                        rollback_reports = (
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
                            if rollback_parent is not None
                            else []
                        )
                        if state is not None:
                            state.reports.extend(rollback_reports)
                        rollback_confirmed = bool(
                            rollback_parent is not None
                            and self._checkpoint_rollback_confirmed(
                                checkpoint,
                                rollback_parent,
                                author_id=result.agent.id,
                                reports=rollback_reports,
                                confidence_threshold=cfg.checkpoint_pass_threshold,
                            )
                        )
                        store.save_proof_delta(
                            delta.delta_id,
                            delta,
                            rejected=not rollback_confirmed,
                        )
                        if rollback_confirmed and rollback_parent is not None:
                            abandoned = checkpoint
                            restored = store.rollback_proof_checkpoint(
                                checkpoint.path_id,
                                reason="; ".join(delta.detected_conflicts)[:400],
                            )
                            if restored is not None:
                                self._reconcile_checkpoint_rollback(
                                    state=state,
                                    memory=memory,
                                    store=store,
                                    abandoned=abandoned,
                                    restored=restored,
                                    rollback_reports=rollback_reports,
                                )
                                checkpoint = restored
                                targeted_feedback = [
                                    *targeted_feedback,
                                    (
                                        "Independent verification confirmed a "
                                        "contradiction in the latest checkpoint "
                                        "segment. The path was rolled back one "
                                        "segment and its derived evidence was "
                                        "invalidated. Rebuild from the restored "
                                        "checkpoint and avoid the refuted step: "
                                        + "; ".join(delta.detected_conflicts)[:300]
                                    ),
                                ]
                        else:
                            deep_outcome_reason = (
                                "the author's rollback request was not confirmed "
                                "by an independent, step-specific review"
                            )
                            targeted_feedback = [
                                *targeted_feedback,
                                (
                                    "The latest verified checkpoint remains "
                                    "authoritative. A rollback requires an "
                                    "independent verifier to identify and confirm "
                                    "the exact invalid step introduced by its "
                                    "latest segment."
                                ),
                            ]
                            store.append_event(
                                "proof_checkpoint_rollback_not_confirmed",
                                {
                                    "checkpoint_id": checkpoint.checkpoint_id,
                                    "delta_id": delta.delta_id,
                                    "author_id": result.agent.id,
                                    "report_ids": [
                                        report.report_id for report in rollback_reports
                                    ],
                                },
                            )
                        delta = None
                        break
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
                    stalled_rounds=self._computation_stalled_rounds(
                        state,
                        strategy,
                        round_index=round_index,
                    ),
                    meta_review_approved=computation_meta_approved,
                    runner=runner,
                    prompts=prompts,
                    tools=tools,
                    budget_bucket=budget_bucket,
                    state=state,
                )
                computation_feedback.append(decision.model_dump(mode="json"))
                if decision.decision == ComputationDecisionStatus.DEFER:
                    deep_outcome = ExplorationOutcome.NO_VERIFIED_PROGRESS
                    deep_outcome_reason = (
                        "the requested computation was durably queued until its "
                        "explicit gate condition is satisfied"
                    )
                    targeted_feedback = [
                        *targeted_feedback,
                        (
                            f"Computation {turn.experiment_spec.experiment_id} was "
                            f"queued: {decision.reason}"
                        ),
                    ]
                    break
                if experiment is None:
                    deep_outcome = ExplorationOutcome.NO_VERIFIED_PROGRESS
                    deep_outcome_reason = (
                        "the requested computation was rejected and produced no "
                        "admissible evidence"
                    )
                    targeted_feedback = [
                        *targeted_feedback,
                        (
                            f"Computation {turn.experiment_spec.experiment_id} was "
                            f"not executed: {decision.reason}"
                        ),
                    ]
                    break
                if experiment is not None:
                    compute_cycles += 1
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
            (
                delta.candidate_conjectures,
                pattern_completion_usage,
                missing_pattern_results,
            ) = await self._ensure_pattern_conjectures(
                problem,
                delta.candidate_conjectures,
                experiment_results,
                agent=result.agent,
                runner=runner,
                prompts=prompts,
                tools=tools,
                store=store,
                budget_bucket=budget_bucket,
            )
            cumulative_usage = self._sum_usage(
                [cumulative_usage, pattern_completion_usage]
            )
            segment_usage = self._sum_usage([segment_usage, pattern_completion_usage])
            delta.usage = self._sum_usage([result.usage, pattern_completion_usage])
            known_candidate_hashes = {
                item.content_hash for item in route_candidate_conjectures
            }
            for candidate in delta.candidate_conjectures:
                if candidate.content_hash not in known_candidate_hashes:
                    known_candidate_hashes.add(candidate.content_hash)
                    route_candidate_conjectures.append(candidate)
            if missing_pattern_results:
                delta.proof_complete = False
                delta.candidate_final_answer = None
                delta.ready_for_verification = False
                delta.remaining_subgoals = self._deduplicate_strings(
                    [
                        *delta.remaining_subgoals,
                        (
                            "Formulate a concrete candidate conjecture for successful "
                            "discover_pattern experiments: "
                            + ", ".join(sorted(missing_pattern_results))
                        ),
                    ]
                )
                delta.known_risks = self._deduplicate_strings(
                    [
                        *delta.known_risks,
                        "A bounded pattern-discovery result has not yet been "
                        "interpreted as an explicit unproved conjecture.",
                    ]
                )
            candidate_calculation_steps = [
                *delta.new_steps,
                *(step for claim in delta.new_claims for step in claim.proof_steps),
            ]
            calculation_gate_result = CriticalCalculationGate(
                self.config, tools, store
            ).evaluate_steps(
                candidate_calculation_steps,
                scope_type="proof_step",
                path_id=checkpoint.path_id,
                parent_checkpoint_id=checkpoint.checkpoint_id,
                requested_by=result.agent.id,
            )
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

            if state is not None and state.proof_control is not None:
                state.proof_control.register_delta(
                    delta,
                    source_attempt_id=attempt_id,
                )
            # The guard must accept exactly the cross-path IDs the prompt
            # offered the author as usable dependencies: verified lemma
            # library claims (legacy mode) and delivered broker messages
            # (hierarchical mode).
            prompt_shared_ids = {claim.claim_id for claim in relevant} | {
                message.message_id for message in delivered_messages
            }
            local_report = local_delta_verification(
                problem,
                checkpoint,
                delta,
                shared_dependency_ids=prompt_shared_ids,
            )
            policy_issues: list[VerificationIssue] = []
            for gate_failure in calculation_gate_result.failures:
                policy_issues.append(
                    VerificationIssue(
                        phase="critical_calculation_gate",
                        severity=(
                            Severity.CRITICAL
                            if gate_failure.verdict == CalculationGateVerdict.REFUTED
                            else Severity.ERROR
                        ),
                        step_id=gate_failure.scope_id,
                        description=gate_failure.reason,
                        counterexample=(
                            gate_failure.reason
                            if gate_failure.verdict == CalculationGateVerdict.REFUTED
                            else None
                        ),
                        repair_hint=(
                            "Correct the finite claim and its typed request, or replace "
                            "the computed premise with a symbolic derivation."
                        ),
                    )
                )
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
            # UNCERTAIN on a technically hard step is common and repairable;
            # dropping the whole segment for it punished exactly the bold
            # multi-step work hard problems need. One extra arbiter breaks
            # the tie before the segment is discarded.
            if (
                cfg.verify_each_delta
                and local_report.verdict == VerificationVerdict.PASS
                and independent
                and any(
                    report.verdict == VerificationVerdict.UNCERTAIN
                    for report in independent
                )
                and not any(
                    report.verdict == VerificationVerdict.FAIL for report in independent
                )
                and runner.ledger.remaining_calls > 1
            ):
                arbiter_reports = await self._verify_proof_delta(
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
                    exclude_extra={report.agent_id for report in independent},
                )
                if arbiter_reports:
                    reports.extend(arbiter_reports)
                    store.append_event(
                        "delta_uncertain_arbitration",
                        {
                            "delta_id": delta.delta_id,
                            "arbiter_verdicts": [
                                report.verdict.value for report in arbiter_reports
                            ],
                        },
                    )
                    independent = [
                        report
                        for report in reports
                        if report.agent_id != "local-integrity-guard"
                    ]
                    if all(
                        report.verdict == VerificationVerdict.PASS
                        for report in arbiter_reports
                    ):
                        independent = [
                            report
                            for report in independent
                            if report.verdict != VerificationVerdict.UNCERTAIN
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

            proof_control_continue = True
            if (
                state is not None
                and state.proof_control is not None
                and state.proof_graph is not None
                and route_id is not None
            ):
                control_report = next(
                    (
                        item
                        for item in reports
                        if item.verdict != VerificationVerdict.PASS
                    ),
                    max(reports, key=lambda item: item.confidence),
                )
                prior_core_history = state.proof_control.state.core_debt_history.get(
                    route_id, []
                )
                prior_core_debt = prior_core_history[-1] if prior_core_history else None
                state.proof_control.process_verification_report(
                    control_report,
                    route_id=route_id,
                    delta=delta,
                )
                current_core_debt = float(
                    state.proof_control.route_signals(route_id)["core_proof_debt"]
                )
                proof_control_closed_core_after = {
                    item.obligation_id
                    for item in state.proof_graph.obligations_in_core_closure(
                        route_id=route_id
                    )
                    if item.status == "closed"
                }
                proof_control_continue = state.proof_control.allow_deepen(
                    route_id=route_id,
                    segment_index=next_segment,
                    report=control_report,
                    core_obligation_closed=bool(
                        proof_control_closed_core_after
                        - proof_control_closed_core_before
                    ),
                    core_debt_reduced=bool(
                        prior_core_debt is not None
                        and current_core_debt < prior_core_debt - 1e-9
                    ),
                    verified_bridge_gain=bool(
                        accepted
                        and any(
                            state.proof_control.state.proof_roles.get(claim.claim_id)
                            in {
                                ProofRole.CORE_BRIDGE,
                                ProofRole.SUFFICIENT_CONDITION,
                                ProofRole.EQUIVALENT_REDUCTION,
                            }
                            for claim in delta.new_claims
                        )
                    ),
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
                self._record_route_checkpoint_outcome(
                    state,
                    attempt_id=attempt_id,
                    delta=delta,
                    reports=reports,
                    accepted=False,
                    feedback=feedback,
                    store=store,
                )
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
            for claim in claims:
                memory.mark_claim_checkpoint_verified(
                    claim.claim_id,
                    report_ids=[item.report_id for item in reports],
                    confidence=min(item.confidence for item in reports),
                    independent=bool(independent),
                )
            verified_delta_claims.extend(claims)
            checkpoint = merge_verified_delta(
                checkpoint,
                delta,
                reports,
                failover_chain=tried_agents,
            )
            memory.register_committed_step_ids(
                step.step_id for step in checkpoint.verified_steps
            )
            self._record_route_checkpoint_outcome(
                state,
                attempt_id=attempt_id,
                delta=delta,
                reports=reports,
                accepted=True,
                feedback="the proof delta passed checkpoint verification",
                store=store,
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
                used_message_ids = record_verified_message_usage(
                    state.message_broker,
                    delivered_messages,
                    acknowledged_receipts,
                    delta,
                    route_id=route_id,
                    proof_graph=state.proof_graph,
                    proof_debt_before=proof_debt_before,
                )
                if state.proof_control is not None:
                    for message_id in used_message_ids:
                        state.proof_control.record_message_usage(
                            message_id,
                            consumer_route_id=route_id,
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
            if not proof_control_continue:
                break

        attempt = attempt_from_checkpoint(
            checkpoint,
            strategy,
            agent_id=checkpoint.source_agent_id or agent.id,
            round_index=round_index,
            previous_attempt=previous_attempt,
            attempt_id=attempt_id,
            proposed_lemmas=verified_delta_claims,
            candidate_conjectures=route_candidate_conjectures,
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

    @staticmethod
    def _checkpoint_rollback_confirmed(
        abandoned: ProofCheckpoint,
        restored: ProofCheckpoint,
        *,
        author_id: str,
        reports: list[VerificationReport],
        confidence_threshold: float,
    ) -> bool:
        """Require independent, step-specific agreement before rollback."""

        restored_step_ids = {step.step_id for step in restored.verified_steps}
        latest_segment_step_ids = {
            step.step_id for step in abandoned.verified_steps
        } - restored_step_ids
        independent = [report for report in reports if report.agent_id != author_id]
        return bool(latest_segment_step_ids and independent) and all(
            report.problem_integrity_ok
            and report.verdict == VerificationVerdict.PASS
            and report.confidence >= confidence_threshold
            and report.first_error_step in latest_segment_step_ids
            and report.first_error_step in report.checked_dependencies
            for report in independent
        )

    def _reconcile_checkpoint_rollback(
        self,
        *,
        state: SolveState | None,
        memory: LemmaMemory,
        store: ArtifactStore,
        abandoned: ProofCheckpoint,
        restored: ProofCheckpoint,
        rollback_reports: list[VerificationReport],
    ) -> None:
        """Revoke live authority derived only from an abandoned descendant."""

        protected_claim_ids = {
            memory.resolve_claim_id(claim_id)
            for claim_id in restored.verified_claim_ids
        }
        removed_claim_ids = {
            memory.resolve_claim_id(claim_id)
            for claim_id in abandoned.verified_claim_ids
        } - protected_claim_ids
        invalidated_claim_ids = memory.invalidate_checkpoint_claims(
            removed_claim_ids,
            rollback_report_ids=[report.report_id for report in rollback_reports],
        )
        claims_by_id = {claim.claim_id: claim for claim in memory.claims}
        invalidated_message_ids = [
            f"msg_claim_{claims_by_id[claim_id].content_hash[:12]}"
            for claim_id in invalidated_claim_ids
            if claim_id in claims_by_id
        ]

        active_checkpoints = store.list_proof_checkpoints()
        memory.replace_committed_step_ids(
            step.step_id
            for active_checkpoint in active_checkpoints
            for step in active_checkpoint.verified_steps
        )
        if state is not None:
            state.checkpoints = active_checkpoints
            if state.typed_memory is not None:
                dependent_message_ids = state.typed_memory.invalidate_dependents(
                    invalidated_message_ids,
                    reason=f"checkpoint_rolled_back:{abandoned.checkpoint_id}",
                )
                invalidated_message_ids = self._deduplicate_strings(
                    [*invalidated_message_ids, *dependent_message_ids]
                )
            if state.message_broker is not None:
                state.message_broker.invalidate_messages(
                    invalidated_message_ids,
                    reason=f"checkpoint_rolled_back:{abandoned.checkpoint_id}",
                )
            if state.proof_graph is not None:
                state.proof_graph.invalidate_evidence_messages(
                    invalidated_message_ids,
                    reason=f"checkpoint_rolled_back:{abandoned.checkpoint_id}",
                )
            if state.route_registry is not None:
                route = state.route_registry.route_for_strategy(abandoned.strategy_id)
                if route is not None:
                    route.latest_checkpoint_id = restored.checkpoint_id
            if state.proof_control is not None:
                state.proof_control.persist()
            self._persist_hierarchical_route_runtime(state, store)
        store.append_event(
            "proof_checkpoint_rollback_reconciled",
            {
                "abandoned_checkpoint_id": abandoned.checkpoint_id,
                "restored_checkpoint_id": restored.checkpoint_id,
                "invalidated_claim_ids": invalidated_claim_ids,
                "invalidated_message_ids": invalidated_message_ids,
            },
        )

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
        exclude_extra: set[str] | None = None,
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
        excluded = {author.id, *(exclude_extra or set())}
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
                report.problem_integrity_ok = False
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
    ) -> ProofAttempt:
        relevant = router.relevant_claims(memory.claims, strategy, targeted_feedback)
        path_id = (
            previous_attempt.path_id
            if previous_attempt and previous_attempt.path_id
            else f"path_{strategy.strategy_id}"
        )
        (
            computation_feedback,
            experiment_results,
        ) = await self._run_materialized_falsification_tasks(
            problem,
            strategy,
            agent,
            state=state,
            round_index=round_index,
            path_id=path_id,
            parent_checkpoint_id=None,
            meta_review_approved=computation_meta_approved,
            runner=runner,
            prompts=prompts,
            tools=tools,
            budget_bucket=budget_bucket,
        )
        (
            deferred_feedback,
            deferred_results,
            compute_cycles,
            deferred_still_blocked,
        ) = await self._retry_deferred_computations(
            problem,
            strategy,
            agent,
            state=state,
            round_index=round_index,
            path_id=path_id,
            parent_checkpoint_id=None,
            meta_review_approved=computation_meta_approved,
            runner=runner,
            prompts=prompts,
            tools=tools,
            budget_bucket=budget_bucket,
            max_experiments=(self.config.computation.max_compute_cycles_per_segment),
        )
        computation_feedback.extend(deferred_feedback)
        experiment_results.extend(deferred_results)
        if deferred_still_blocked:
            return self._failed_attempt(
                problem,
                strategy,
                agent.id,
                round_index,
                RuntimeError(
                    "a durable computation request remains deferred by its "
                    "stall or Meta-Reviewer gate"
                ),
            )
        confirmed_counterexample_pending = any(
            item.get("outcome") == ExperimentOutcome.COUNTEREXAMPLE_FOUND.value
            and item.get("evidence_strength") == EvidenceStrength.COUNTEREXAMPLE.value
            and bool(item.get("independently_verified"))
            for item in experiment_results
        )
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
                negative_knowledge=self._negative_knowledge_context(
                    memory, state, strategy.strategy_id
                ),
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
                if confirmed_counterexample_pending:
                    self._apply_confirmed_counterexample_impact(
                        None,
                        strategy,
                        route_id=None,
                        impact=turn.experiment_impact,
                        experiment_results=experiment_results,
                        current_round=round_index,
                        store=store,
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
                stalled_rounds=self._computation_stalled_rounds(
                    state,
                    strategy,
                    round_index=round_index,
                ),
                meta_review_approved=computation_meta_approved,
                runner=runner,
                prompts=prompts,
                tools=tools,
                budget_bucket=budget_bucket,
                state=state,
            )
            computation_feedback.append(decision.model_dump(mode="json"))
            if decision.decision == ComputationDecisionStatus.DEFER:
                return self._failed_attempt(
                    problem,
                    strategy,
                    agent.id,
                    round_index,
                    RuntimeError(
                        "computation was durably queued until its gate condition "
                        f"is satisfied: {decision.reason}"
                    ),
                )
            if experiment is None:
                return self._failed_attempt(
                    problem,
                    strategy,
                    agent.id,
                    round_index,
                    RuntimeError(
                        f"computation request was not executed: {decision.reason}"
                    ),
                )
            if experiment is not None:
                compute_cycles += 1
                experiment_results.append(experiment.model_dump(mode="json"))
                confirmed_counterexample_pending = confirmed_counterexample_pending or (
                    experiment.outcome == ExperimentOutcome.COUNTEREXAMPLE_FOUND
                    and experiment.evidence_strength == EvidenceStrength.COUNTEREXAMPLE
                    and experiment.independently_verified
                )

        assert attempt is not None
        (
            attempt.candidate_conjectures,
            pattern_completion_usage,
            missing_pattern_results,
        ) = await self._ensure_pattern_conjectures(
            problem,
            attempt.candidate_conjectures,
            experiment_results,
            agent=agent,
            runner=runner,
            prompts=prompts,
            tools=tools,
            store=store,
            budget_bucket=budget_bucket,
        )
        cumulative_usage = self._sum_usage([cumulative_usage, pattern_completion_usage])
        if missing_pattern_results and attempt.status != AttemptStatus.FAILED:
            attempt.status = AttemptStatus.PARTIAL
            attempt.final_answer = None
            attempt.unresolved_gaps.append(
                "Formulate a concrete candidate conjecture for successful "
                "discover_pattern experiments: "
                + ", ".join(sorted(missing_pattern_results))
            )
        # Authoritative metadata is assigned by the orchestrator, not trusted from model text.
        attempt.problem_hash = problem.integrity_hash
        attempt.strategy_id = strategy.strategy_id
        attempt.agent_id = agent.id
        attempt.round_index = round_index
        attempt.path_id = path_id
        attempt.raw_artifact_ref = latest_raw_ref
        attempt.usage = cumulative_usage
        attempt_calculation_steps = [
            *attempt.proof_steps,
            *(step for claim in attempt.proposed_lemmas for step in claim.proof_steps),
        ]
        calculation_gate_result = CriticalCalculationGate(
            self.config, tools, store
        ).evaluate_steps(
            attempt_calculation_steps,
            scope_type="proof_step",
            path_id=path_id,
            parent_checkpoint_id=None,
            requested_by=agent.id,
        )
        if not calculation_gate_result.passed:
            attempt.status = AttemptStatus.FAILED
            attempt.final_answer = None
            attempt.dead_ends.append(
                "Deterministic critical calculation gate blocked the attempt: "
                + calculation_gate_result.concise_failure()
            )
            attempt.unresolved_gaps.append(
                "Correct or declare every load-bearing finite calculation before "
                "resubmitting this route."
            )
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
                    f"{strategy.title}：{attempt.status.value}；步骤 {len(attempt.proof_steps)}；候选规律 {len(attempt.candidate_conjectures)}；未解缺口 {len(attempt.unresolved_gaps)}",
                    f"{strategy.title}: {attempt.status.value}; {len(attempt.proof_steps)} steps; {len(attempt.candidate_conjectures)} candidate conjectures; {len(attempt.unresolved_gaps)} unresolved gaps",
                ),
                stage="independent_exploration",
                agent_id=agent.id,
                importance=ActivityImportance.NORMAL,
                metrics={
                    "attempt_id": attempt.attempt_id,
                    "strategy_id": strategy.strategy_id,
                    "status": attempt.status.value,
                    "proof_step_count": len(attempt.proof_steps),
                    "candidate_conjecture_count": len(attempt.candidate_conjectures),
                    "unresolved_gap_count": len(attempt.unresolved_gaps),
                },
            )
        return attempt

    @staticmethod
    async def _gather_optional_batch_until_provider_circuit(
        awaitables: Sequence[Any],
    ) -> tuple[list[Any], ProviderCircuitOpenError | None]:
        tasks = [asyncio.create_task(item) for item in awaitables]
        task_indexes = {task: index for index, task in enumerate(tasks)}
        results: list[Any] = [None] * len(tasks)
        pending = set(tasks)
        while pending:
            done, pending = await asyncio.wait(
                pending,
                return_when=asyncio.FIRST_COMPLETED,
            )
            circuit_error: ProviderCircuitOpenError | None = None
            for task in done:
                index = task_indexes[task]
                try:
                    results[index] = task.result()
                except Exception as exc:
                    results[index] = exc
                    if isinstance(exc, ProviderCircuitOpenError):
                        circuit_error = exc
            if circuit_error is None:
                continue
            for task in pending:
                task.cancel()
            await asyncio.gather(*pending, return_exceptions=True)
            for task in pending:
                results[task_indexes[task]] = circuit_error
            return results, circuit_error
        return results, None

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
        summarizers: dict[str, AgentRuntime] = {}
        reserved_agents: set[str] = set()
        for attempt in attempts:
            if attempt.status == AttemptStatus.FAILED:
                continue
            exclude = {attempt.agent_id, *reserved_agents}
            summarizer = runner.pool.select("summarizer", exclude=exclude)
            summarizers[attempt.attempt_id] = summarizer
            reserved_agents.add(summarizer.id)

        async def one(attempt: ProofAttempt) -> ClaimBatch | None:
            if attempt.status == AttemptStatus.FAILED:
                return None
            summarizer = summarizers[attempt.attempt_id]
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

        (
            results,
            circuit_error,
        ) = await self._gather_optional_batch_until_provider_circuit(
            [one(attempt) for attempt in attempts]
        )
        if circuit_error is not None:
            store.append_event(
                "claim_extraction_batch_short_circuited",
                {
                    "provider_scope": circuit_error.provider_scope,
                    "agent_ids": circuit_error.agent_ids,
                    "retry_after_seconds": circuit_error.retry_after_seconds,
                    "attempt_count": len(attempts),
                    "fallback": "retain_explorer_proposed_lemmas",
                },
            )
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
        committed_known = self._committed_dependency_ids(attempt, memory, state)
        structural_reports = await self._call_structural_reviewers(
            problem,
            attempt,
            structural_reviewers,
            runner,
            prompts,
            store,
            known_dependency_ids=committed_known,
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
                known_dependency_ids=committed_known,
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
        # A structural FAIL from LLM judgment alone (no deterministic guard
        # issue) still deserves one detailed pass: deep attempts need
        # first-error-level repair feedback, and a single same-model
        # reviewer's structural hallucination must not be able to bury a
        # route unexamined. Deterministic guard failures stay hard.
        deterministic_structural_failure = any(
            issue.phase
            in {
                "local_dependency_guard",
                "local_completeness_guard",
                "hard_constraint_guard",
                "experiment_audit_guard",
            }
            and issue.severity in {Severity.ERROR, Severity.CRITICAL}
            for report in structural_reports
            for issue in report.issues
        )
        if (
            not may_detail
            and structural_aggregate.verdict == VerificationVerdict.FAIL
            and not deterministic_structural_failure
            and runner.ledger.remaining_calls > 1
        ):
            may_detail = True

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
            detailed_dissent = bool(detailed_reports) and all(
                report.verdict == VerificationVerdict.PASS
                for report in detailed_reports
            )
            aggregate = VerificationReport(
                target_id=attempt.attempt_id,
                target_type="attempt",
                agent_id="system-aggregate",
                stage=VerificationStage.DETAILED,
                problem_integrity_ok=structural_aggregate.problem_integrity_ok,
                # A step-level audit that unanimously passes outranks a
                # single structural reviewer's judgment call: the verdict
                # becomes UNCERTAIN (repairable disagreement), never a
                # silent burial of the route. Deterministic guard failures
                # never reach this branch with detailed reports.
                verdict=(
                    VerificationVerdict.UNCERTAIN
                    if detailed_dissent
                    else VerificationVerdict.FAIL
                ),
                first_error_step=structural_aggregate.first_error_step,
                issues=[
                    *structural_aggregate.issues,
                    *[issue for report in detailed_reports for issue in report.issues],
                ],
                checked_dependencies=sorted(
                    {
                        *structural_aggregate.checked_dependencies,
                        *[
                            dep
                            for report in detailed_reports
                            for dep in report.checked_dependencies
                        ],
                    }
                ),
                failure_level=structural_aggregate.failure_level,
                confidence=(
                    min(structural_aggregate.confidence, 0.6)
                    if detailed_dissent
                    else structural_aggregate.confidence
                ),
                concise_feedback=(
                    (
                        "Structural gate failed but every detailed audit "
                        "passed; treat as a repairable disagreement. "
                        if detailed_dissent
                        else "Structural gate failed. "
                    )
                    + structural_aggregate.concise_feedback
                ),
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

    @staticmethod
    def _negative_knowledge_context(
        memory: LemmaMemory,
        state: SolveState | None,
        strategy_id: str,
        *,
        limit: int = 6,
    ) -> list[dict[str, Any]]:
        """Refuted claims and cross-route dead ends for prompt injection.

        Without this, isolated explorers re-derive already-refuted lemmas
        and re-walk dead ends other routes paid for; failure produced no
        knowledge, only lost budget.
        """
        packets: list[dict[str, Any]] = []
        for claim in memory.rejected()[:limit]:
            packets.append(
                {
                    "kind": "refuted_claim",
                    "statement": claim.statement,
                    "scope_limitations": claim.scope_limitations[:3],
                }
            )
        if state is not None:
            seen: set[str] = set()
            for attempt in reversed(state.attempts):
                if attempt.strategy_id == strategy_id:
                    continue
                for dead_end in attempt.dead_ends:
                    key = dead_end.strip().casefold()
                    if key and key not in seen:
                        seen.add(key)
                        packets.append(
                            {
                                "kind": "dead_end",
                                "strategy_id": attempt.strategy_id,
                                "statement": dead_end,
                            }
                        )
                if len(packets) >= 2 * limit:
                    break
        return packets[: 2 * limit]

    @staticmethod
    def _committed_dependency_ids(
        attempt: ProofAttempt,
        memory: LemmaMemory,
        state: SolveState | None,
    ) -> set[str]:
        """IDs an attempt may legitimately depend on beyond its own steps.

        Multi-round attempts carry checkpoint steps whose dependencies point
        at claims committed in EARLIER segments; those claims live in the
        checkpoint chain and the global lemma memory, not in this call's
        proposed_lemmas. Excluding them made the dependency guard
        deterministically fail every deep proof.
        """
        known: set[str] = set()
        checkpoints = state.checkpoints if state is not None else []
        for checkpoint in checkpoints:
            if attempt.path_id is not None and checkpoint.path_id == attempt.path_id:
                known.update(checkpoint.verified_claim_ids)
                known.update(step.step_id for step in checkpoint.verified_steps)
        known.update(claim.claim_id for claim in memory.verified())
        return known

    async def _call_structural_reviewers(
        self,
        problem: ProblemContract,
        attempt: ProofAttempt,
        reviewers: Sequence[AgentRuntime],
        runner: StructuredAgentRunner,
        prompts: PromptFactory,
        store: ArtifactStore,
        *,
        known_dependency_ids: set[str] | None = None,
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
            self._apply_local_attempt_integrity_guard(
                problem,
                attempt,
                report,
                known_dependency_ids=known_dependency_ids,
            )
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
        route = (
            state.route_registry.route_for_strategy(strategy_id)
            if state.route_registry is not None
            else None
        )
        if route is not None and route.status not in {
            RouteStatus.ACTIVE,
            RouteStatus.REPAIR_ONCE,
        }:
            store.append_event(
                "route_deepening_blocked",
                {
                    "route_id": route.route_id,
                    "strategy_id": strategy_id,
                    "status": route.status.value,
                    "reason": route.frozen_reason or route.revision_summary,
                },
            )
            return None
        previous_candidates = [
            a for a in state.attempts if a.strategy_id == strategy_id
        ]
        if not previous_candidates:
            return None
        previous = max(previous_candidates, key=lambda a: a.round_index)
        # After two stagnant rounds the same explorer keeps replaying its own
        # fixed ideas; hand the checkpoint to a different agent for a fresh
        # rollout of the same verified prefix.
        stagnation_rounds = 0
        if route is not None:
            stagnation_rounds = max(
                getattr(route, "stagnation_rounds", 0) or 0,
                getattr(route, "no_progress_strikes", 0) or 0,
            )
        swap_explorer = stagnation_rounds >= 2 and len(runner.pool.agents) > 1
        try:
            if swap_explorer:
                agent = runner.pool.select(
                    "explorer",
                    exclude={previous.agent_id},
                    specialty_hints=strategy.tags,
                )
                store.append_event(
                    "deepen_explorer_swapped",
                    {
                        "strategy_id": strategy_id,
                        "previous_agent_id": previous.agent_id,
                        "new_agent_id": agent.id,
                        "stagnation_rounds": stagnation_rounds,
                    },
                )
            else:
                agent = runner.pool.get(previous.agent_id)
        except (KeyError, RuntimeError):
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
            review_index = len(state.meta_reviews) - 1
            source = f"meta_review:{review_index}"
            feedback.extend(
                self._feedback_directive(
                    item,
                    kind="unresolved_conflict",
                    status="open",
                    source=source,
                )
                for item in state.meta_reviews[-1].unresolved_conflicts
            )
            feedback.extend(
                self._feedback_directive(
                    item,
                    kind="required_action",
                    status="open",
                    source=source,
                )
                for item in state.meta_reviews[-1].required_actions
            )
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
        if state.proof_control is not None:
            selected, _ = state.proof_control.admit_routes(selected)
        if not selected:
            return []
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

    @staticmethod
    def _final_review_author_ids(
        proof: FinalProof,
        state: SolveState | None,
        synthesizer: AgentRuntime | None,
    ) -> set[str]:
        """Return every agent that authored the winning proof lineage."""

        excluded = {synthesizer.id} if synthesizer is not None else set()
        if state is None:
            return excluded
        source_ids = set(proof.source_attempt_ids)
        author_paths: set[str] = set()
        for attempt in state.attempts:
            if attempt.attempt_id not in source_ids:
                continue
            excluded.add(attempt.agent_id)
            excluded.update(attempt.failover_chain)
            if attempt.path_id:
                author_paths.add(attempt.path_id)
        for checkpoint in state.checkpoints:
            if checkpoint.path_id not in author_paths:
                continue
            if checkpoint.source_agent_id:
                excluded.add(checkpoint.source_agent_id)
            excluded.update(checkpoint.failover_chain)
        return excluded

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
        calculation_gate_result = CriticalCalculationGate(
            self.config, tools, store
        ).evaluate_steps(
            proof.proof_steps,
            scope_type="final_step",
            path_id="final_proof",
            parent_checkpoint_id=None,
            requested_by=(
                synthesizer.id if synthesizer is not None else "final-synthesizer"
            ),
        )
        if not calculation_gate_result.passed:
            reason = calculation_gate_result.concise_failure()
            issues = [
                VerificationIssue(
                    phase="critical_calculation_gate",
                    severity=(
                        Severity.CRITICAL
                        if failure.verdict == CalculationGateVerdict.REFUTED
                        else Severity.ERROR
                    ),
                    step_id=failure.scope_id,
                    description=failure.reason,
                    repair_hint=(
                        "Correct the finite claim and its typed request before final "
                        "mathematical review."
                    ),
                )
                for failure in calculation_gate_result.failures
            ]
            report = VerificationReport(
                target_id="final_proof",
                target_type="final_proof",
                agent_id="local-critical-calculation-gate",
                stage=VerificationStage.FINAL,
                verdict=VerificationVerdict.FAIL,
                first_error_step=(
                    calculation_gate_result.failures[0].scope_id
                    if calculation_gate_result.failures
                    else None
                ),
                issues=issues,
                failure_level=FailureLevel.EXECUTION,
                confidence=1.0,
                concise_feedback=(
                    "Final proof blocked before reviewer calls by the deterministic "
                    f"critical calculation gate: {reason}"
                ),
            )
            store.write_json("structured", "final_critical_calculation_gate", report)
            store.append_event("final_critical_calculation_gate_blocked", report)
            return VerificationBundle(aggregate=report, reports=[report])
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
        # Independence is a hard final-gate invariant. If the pool has no
        # non-author reviewer, verification remains uncertain instead of
        # silently allowing an author to approve their own winning chain.
        exclude = self._final_review_author_ids(proof, state, synthesizer)
        try:
            structural = runner.pool.select(
                "structural_verifier",
                exclude=exclude,
                prefer_provider_not=synthesizer.provider if synthesizer else None,
                strict_exclude=True,
            )
        except RuntimeError:
            store.append_event(
                "final_verification_author_exclusion_exhausted",
                {
                    "reason": "no_independent_structural_reviewer",
                    "excluded_agent_ids": sorted(exclude),
                },
            )
            report = self._synthetic_verification_failure(
                "final_proof",
                "final_proof",
                VerificationStage.FINAL,
                "Final verification could not run because every eligible "
                "reviewer authored the winning proof lineage.",
                uncertain=True,
            )
            store.write_json("structured", f"final_verification_{new_id('v')}", report)
            return VerificationBundle(aggregate=report, reports=[report])
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
            strict_exclude=True,
        )
        if not final_reviewers:
            store.append_event(
                "final_verification_author_exclusion_exhausted",
                {
                    "reason": "no_independent_detailed_reviewer",
                    "excluded_agent_ids": sorted(exclude | {structural.id}),
                },
            )
            unavailable = self._synthetic_verification_failure(
                "final_proof",
                "final_proof",
                VerificationStage.FINAL,
                "Final detailed verification could not run because no "
                "non-author reviewer remained after the structural gate.",
                uncertain=True,
            )
            reports.append(unavailable)
            store.write_json(
                "structured", f"final_verification_{new_id('v')}", unavailable
            )
            return VerificationBundle(aggregate=unavailable, reports=reports)
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
                strict_exclude=True,
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
        """Re-raise fatal run-level exceptions swallowed by gather().

        Budget exhaustion is fatal for the whole run: converting it into a
        synthetic FAILED report both masks the true stop reason and lets
        later stages keep issuing doomed calls.
        """
        for result in results:
            if isinstance(result, (ProviderCircuitOpenError, BudgetExhaustedError)):
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
        *,
        known_dependency_ids: set[str] | None = None,
    ) -> None:
        self._apply_local_target_integrity_guard(problem, attempt, report)
        step_ids = {step.step_id for step in attempt.proof_steps}
        claim_ids = {claim.claim_id for claim in attempt.proposed_lemmas}
        known = step_ids | claim_ids | (known_dependency_ids or set())
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
        constraint_hits = self._hard_constraint_violations(
            problem,
            [
                dep
                for step in attempt.proof_steps
                for dep in step.dependencies
                if dep.startswith("external:")
            ],
        )
        for hit in constraint_hits:
            report.issues.append(
                VerificationIssue(
                    phase="hard_constraint_guard",
                    severity=Severity.CRITICAL,
                    description=hit,
                    repair_hint=(
                        "Remove the forbidden citation and derive the result "
                        "from admissible tools."
                    ),
                )
            )
            report.failure_level = max(
                report.failure_level,
                FailureLevel.STRATEGY,
                key=self._failure_rank,
            )
            report.verdict = VerificationVerdict.FAIL

    @staticmethod
    def _hard_constraint_violations(
        problem: ProblemContract,
        external_dependencies: Sequence[str],
    ) -> list[str]:
        """Deterministic enforcement of explicit citation bans.

        A hard constraint of the form "不得引用X" / "do not use X" bans any
        external:<name> dependency whose name contains X. Free-text
        constraints that name no theorem stay reviewer-enforced.
        """
        markers = (
            "不得引用",
            "不得使用",
            "禁止引用",
            "禁止使用",
            "do not use",
            "do not cite",
            "must not use",
            "must not cite",
            "forbidden:",
        )
        violations: list[str] = []
        dependencies = [dep.casefold() for dep in external_dependencies]
        for constraint in problem.hard_constraints:
            lowered = constraint.casefold()
            for marker in markers:
                index = lowered.find(marker)
                if index < 0:
                    continue
                fragment = lowered[index + len(marker) :]
                fragment = re.split(r"[，,。;；.!?？]", fragment)[0].strip()
                fragment = fragment.strip("\"'“”‘’ 的定理")
                if len(fragment) < 2:
                    continue
                for dep in dependencies:
                    if fragment in dep:
                        violations.append(
                            "External citation violates a hard constraint: "
                            f"{constraint!r} bans {dep!r}."
                        )
        return violations

    def _apply_local_target_integrity_guard(
        self,
        problem: ProblemContract,
        target: ProofAttempt | FinalProof,
        report: VerificationReport,
    ) -> None:
        target_hash = target.problem_hash
        if target_hash != problem.integrity_hash:
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
            report.problem_integrity_ok = False
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
            if refuted or typed_refuted:
                report.issues.append(
                    VerificationIssue(
                        phase="deterministic_tool_guard",
                        severity=Severity.CRITICAL if refuted else Severity.ERROR,
                        description=(
                            "Verifier-requested deterministic check produced an independently confirmed counterexample."
                        ),
                        counterexample=str(
                            payload.get("assignment") or payload.get("counterexample")
                        ),
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
            elif lean_rejected:
                # Formalization failure is an obligation, not a refutation:
                # it caps PASS at UNCERTAIN but must never fail the target.
                report.issues.append(
                    VerificationIssue(
                        phase="deterministic_tool_guard",
                        severity=Severity.WARNING,
                        description=(
                            "Submitted Lean fragment was rejected by the checker; "
                            "the natural-language claim remains unverified by Lean, "
                            "not refuted."
                        ),
                        repair_hint=(
                            "Repair the formalization mapping or drop the Lean "
                            "certificate; the mathematical claim itself is "
                            "unaffected."
                        ),
                    )
                )
                if report.verdict == VerificationVerdict.PASS:
                    report.verdict = VerificationVerdict.UNCERTAIN

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
        if isinstance(target, ProofAttempt):
            referenced_ids = {
                dep for step in target.proof_steps for dep in step.dependencies
            } | {claim.claim_id for claim in target.proposed_lemmas}
        elif isinstance(target, ProofDelta):
            referenced_ids = {
                dep for step in target.new_steps for dep in step.dependencies
            } | {claim.claim_id for claim in target.new_claims}
        else:
            referenced_ids = {
                dep for step in target.proof_steps for dep in step.dependencies
            } | set(target.dependencies)
        for experiment in experiments:
            if (
                experiment.outcome != ExperimentOutcome.COUNTEREXAMPLE_FOUND
                or experiment.evidence_strength != EvidenceStrength.COUNTEREXAMPLE
                or not experiment.independently_verified
            ):
                continue
            claim = self._normalize_statement(experiment.target_claim).casefold()
            # Structured ID binding first: a rewording of the refuted claim
            # must not escape the guard. Verbatim text match stays as the
            # fallback, plus a token-overlap match for paraphrases.
            id_bound = bool(
                experiment.target_claim_id
                and experiment.target_claim_id in referenced_ids
            )
            verbatim = len(claim) >= 8 and claim in normalized_target
            claim_tokens = {tok for tok in re.findall(r"\w{2,}", claim)}
            token_overlap = bool(claim_tokens) and (
                sum(1 for tok in claim_tokens if tok in normalized_target)
                / len(claim_tokens)
                >= 0.8
            )
            if not (id_bound or verbatim or token_overlap):
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
                # lean_check rejection is deliberately NOT here: a failed
                # formalization (syntax error, missing import, sorry marker)
                # is a formalization obligation, never a mathematical
                # refutation of the target claim.
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
        # A PASS that names nothing it checked is a zero-effort review, not
        # an audit; it may not gate a proof forward.
        if (
            stage == VerificationStage.DETAILED
            and report.verdict == VerificationVerdict.PASS
            and not report.checked_dependencies
        ):
            report.issues.append(
                VerificationIssue(
                    phase="verification_protocol",
                    severity=Severity.WARNING,
                    description=(
                        "PASS was returned without any checked_dependencies; "
                        "an audit must enumerate what it verified."
                    ),
                )
            )
            report.verdict = VerificationVerdict.UNCERTAIN
            report.confidence = min(report.confidence, 0.5)

    def _record_verification_bundles(
        self,
        state: SolveState,
        bundles: Iterable[VerificationBundle],
    ) -> None:
        for bundle in bundles:
            state.reports.extend(bundle.reports)
            state.aggregate_reports[bundle.aggregate.target_id] = bundle.aggregate
            if state.proof_control is not None:
                attempt = next(
                    (
                        item
                        for item in state.attempts
                        if item.attempt_id == bundle.aggregate.target_id
                    ),
                    None,
                )
                state.proof_control.process_verification_report(
                    bundle.aggregate,
                    attempt=attempt,
                )
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
            report_source = f"verification_report:{report.report_id}"
            report_status = (
                "rejected"
                if report.verdict == VerificationVerdict.FAIL
                else "uncertain"
            )
            if report.first_error_step:
                feedback.append(
                    self._feedback_directive(
                        f"First disputed step: {report.first_error_step}",
                        kind="verification_issue",
                        status=report_status,
                        source=report_source,
                    )
                )
            if report.concise_feedback:
                feedback.append(
                    self._feedback_directive(
                        report.concise_feedback,
                        kind="verification_feedback",
                        status=report_status,
                        source=report_source,
                    )
                )
            feedback.extend(
                self._feedback_directive(
                    f"{issue.step_id or issue.claim_id or 'global'}: "
                    f"{issue.description}",
                    kind="verification_issue",
                    status=report_status,
                    source=report_source,
                )
                for issue in report.issues[:8]
            )
        feedback.extend(
            self._feedback_directive(
                f"Unresolved gap: {canonical_gap}",
                kind="proof_obligation",
                status="open",
                source=f"attempt:{attempt.attempt_id}",
            )
            for gap in attempt.unresolved_gaps[:8]
            if not is_feedback_only_statement(gap)
            and (canonical_gap := canonical_obligation_statement(gap))
        )
        if state.meta_reviews:
            review_index = len(state.meta_reviews) - 1
            feedback.extend(
                self._feedback_directive(
                    item,
                    kind="required_action",
                    status="open",
                    source=f"meta_review:{review_index}",
                )
                for item in state.meta_reviews[-1].required_actions[:6]
            )
        return self._deduplicate_strings(feedback)

    @staticmethod
    def _feedback_directive(
        text: str,
        *,
        kind: str,
        status: str,
        source: str,
    ) -> str:
        normalized = " ".join(text.split())
        return (
            f"[{kind}][STATUS:{status}][SOURCE:{source}]"
            f"[PREMISE_ELIGIBLE:false] {normalized}"
        )

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
            action = assessment.recommended_action
            if action not in {ActionKind.COOLDOWN_ROUTE, ActionKind.STOP}:
                continue
            if (
                action == ActionKind.STOP
                and review.confidence < self.config.budget.verification_pass_threshold
            ):
                continue
            attempt = attempts.get(assessment.target_id)
            if attempt is None:
                continue
            route = registry.route_for_strategy(attempt.strategy_id)
            if route is None:
                continue
            requires_revision = (
                action == ActionKind.STOP
                or review.failure_level == FailureLevel.STRATEGY
            )
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
                    "action": action.value,
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
        # Reviewer trust must reward EVIDENCE, not agreement with the
        # majority: paying for conformity teaches same-model reviewers to
        # rubber-stamp each other and buries exactly the dissent that finds
        # correlated blind spots. Credit is earned by reports whose FAIL is
        # backed by a deterministic refutation or a concrete counterexample
        # issue; dissent alone is never penalized.
        for report in reports:
            if report.agent_id.startswith("system-"):
                continue
            try:
                reviewer = pool.get(report.agent_id)
            except KeyError:
                continue
            evidence_backed_fail = report.verdict == VerificationVerdict.FAIL and (
                self._has_deterministic_refutation(report)
                or any(bool(issue.counterexample) for issue in report.issues)
            )
            if evidence_backed_fail:
                reviewer.update_trust(0.02)
            elif (
                report.verdict == VerificationVerdict.PASS
                and not report.checked_dependencies
            ):
                reviewer.update_trust(-0.01)

    def _has_synthesis_ready_candidate(self, state: SolveState) -> bool:
        return any(
            self._is_verified_synthesis_candidate(state, attempt)
            for attempt in state.attempts
        )

    def _is_verified_synthesis_candidate(
        self,
        state: SolveState,
        attempt: ProofAttempt,
    ) -> bool:
        report = state.aggregate_reports.get(attempt.attempt_id)
        return bool(
            attempt.status == AttemptStatus.COMPLETE
            and attempt.proof_steps
            and not attempt.unresolved_gaps
            and report is not None
            and report.verdict == VerificationVerdict.PASS
            and report.problem_integrity_ok
            and report.first_error_step is None
            and report.confidence >= self.config.budget.synthesis_threshold
        )

    def _can_enter_synthesis(self, state: SolveState) -> bool:
        if self._has_synthesis_ready_candidate(state):
            return True
        ranked = self._rank_attempts(state.attempts)
        return bool(self._meta_selected_execution_repairs(state, ranked))

    def _proof_control_allows_synthesis(self, state: SolveState) -> bool:
        if state.proof_control is None:
            return True
        selected = self._select_for_synthesis(state)
        verified_selected = [
            attempt
            for attempt in selected
            if self._is_verified_synthesis_candidate(state, attempt)
        ]
        candidate_attempts = verified_selected or selected
        subject_ids: set[str] = set()
        local_subject_ids: set[str] = set()
        candidate_artifact_refs: set[str] = set()
        unresolved_statements: set[str] = set()
        raw_dependencies: set[str] = set()
        for attempt in candidate_attempts:
            subject_ids.add(attempt.strategy_id)
            step_ids = {item.step_id for item in attempt.proof_steps}
            claim_ids = {item.claim_id for item in attempt.proposed_lemmas}
            subject_ids.update(step_ids)
            subject_ids.update(claim_ids)
            local_subject_ids.update(step_ids)
            local_subject_ids.update(claim_ids)
            lineage_checkpoint_ids = set(attempt.checkpoint_ids)
            candidate_artifact_refs.update(
                lineage_attempt.raw_artifact_ref
                for lineage_attempt in state.attempts
                if lineage_attempt.raw_artifact_ref
                and lineage_attempt.strategy_id == attempt.strategy_id
                and lineage_attempt.path_id == attempt.path_id
                and (
                    lineage_attempt.attempt_id == attempt.attempt_id
                    or (
                        lineage_attempt.latest_checkpoint_id is not None
                        and lineage_attempt.latest_checkpoint_id
                        in lineage_checkpoint_ids
                    )
                )
            )
            unresolved_statements.update(
                self._normalize_statement(item) for item in attempt.unresolved_gaps
            )
            raw_dependencies.update(
                dependency
                for step in attempt.proof_steps
                for dependency in step.dependencies
            )
        if state.message_broker is not None and candidate_artifact_refs:
            subject_ids.update(
                message.message_id
                for message in state.message_broker.messages
                if candidate_artifact_refs.intersection(message.artifact_refs)
            )
        obligation_ids = [
            item.obligation_id
            for item in (
                state.proof_graph.obligations if state.proof_graph is not None else []
            )
            if item.status != "closed"
            and self._normalize_statement(item.normalized_statement)
            in unresolved_statements
        ]
        admitted = (
            state.message_broker.admitted_facts()
            if state.message_broker is not None
            else []
        )
        candidate_fact_ids = [
            message.message_id
            for message in admitted
            if message.message_id in raw_dependencies
            or message.content_hash in raw_dependencies
        ]
        admitted_dependency_ids = {
            value
            for message in admitted
            for value in (message.message_id, message.content_hash)
        }
        candidate_dependency_ids = sorted(
            {
                *obligation_ids,
                *(
                    dependency
                    for dependency in raw_dependencies
                    if dependency not in local_subject_ids
                    and dependency not in admitted_dependency_ids
                ),
            }
        )
        conflicts = (
            state.contradiction_broker.unresolved()
            if state.contradiction_broker is not None
            else []
        )
        record = state.proof_control.synthesis_readiness(
            conflicts=conflicts,
            candidate_subject_ids=subject_ids,
            candidate_dependency_ids=candidate_dependency_ids,
            candidate_fact_ids=candidate_fact_ids,
            candidate_proof_verified=bool(verified_selected),
            candidate_verified_subject_ids=subject_ids,
        )
        return record.verdict != GateVerdict.BLOCK

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
        # Draft (tentative) obligations belong to blueprints that never became
        # admitted routes; counting them as open mathematics would disguise a
        # control-plane failure as unfinished proof work.
        open_obligations = (
            [
                {
                    "obligation_id": item.obligation_id,
                    "statement": item.statement,
                    "status": item.status,
                    "route_ids": item.route_ids,
                }
                for item in state.proof_graph.obligations
                if item.status in {"open", "blocked"}
            ]
            if state.proof_graph is not None
            else []
        )
        draft_obligation_count = (
            sum(
                1
                for item in state.proof_graph.obligations
                if item.status == "tentative"
            )
            if state.proof_graph is not None
            else 0
        )
        negative_evidence = (
            [item.statement for item in state.typed_memory.negatives]
            if state.typed_memory is not None
            else []
        )
        verified_local_claim_ids = (
            [item.message_id for item in state.typed_memory.facts]
            if state.typed_memory is not None
            else []
        )
        remaining_gaps = self._deduplicate_strings(
            [gap for attempt in reviewed for gap in attempt.unresolved_gaps]
            + [str(item["statement"]) for item in open_obligations]
        )
        zh = self.config.runtime.output_language.lower().startswith("zh")
        if state.admission_starvation is not None:
            category = state.admission_starvation.get("category", "unknown")
            summary = (
                f"没有任何路线通过准入（{category}）：这是控制面故障或策略空间问题，"
                f"不是数学上留下了未解决的证明义务。候选策略 "
                f"{state.admission_starvation.get('total', 0)} 条全部被拒，"
                f"其中可修复类 {state.admission_starvation.get('repairable_count', 0)} 条。"
                if zh
                else (
                    f"No route passed admission ({category}): this is a "
                    "control-plane or strategy-space failure, not open "
                    "mathematics. "
                    f"{state.admission_starvation.get('total', 0)} candidate "
                    "strategies were all rejected, "
                    f"{state.admission_starvation.get('repairable_count', 0)} "
                    "of them for repairable reasons."
                )
            )
        else:
            summary = (
                f"尚未建立完整证明。保留 {len(verified_attempts)} 条通过局部审查的路线、"
                f"{len(verified_step_ids)} 个已审查步骤、"
                f"{len(verified_local_claim_ids)} 个已验证局部结论、"
                f"{len(refuted_routes)} 条失败路线，"
                f"{len(open_obligations)} 个开放证明义务"
                + (
                    f"（另有 {draft_obligation_count} 个未准入草稿义务，不计入开放数学）。"
                    if draft_obligation_count
                    else "。"
                )
                if zh
                else (
                    "No complete proof was established. Preserved "
                    f"{len(verified_attempts)} locally passed routes, "
                    f"{len(verified_step_ids)} reviewed steps, "
                    f"{len(verified_local_claim_ids)} verified local claims, "
                    f"{len(refuted_routes)} failed routes, and "
                    f"{len(open_obligations)} open proof obligations"
                    + (
                        f" (plus {draft_obligation_count} unadmitted draft "
                        "obligations, not counted as open mathematics)."
                        if draft_obligation_count
                        else "."
                    )
                )
            )
        return ResearchProgressReport(
            problem_hash=problem.integrity_hash,
            termination_reason=state.termination_reason,
            valid_partial_attempt_ids=[item.attempt_id for item in reviewed],
            strongest_partial_attempt_id=(reviewed[0].attempt_id if reviewed else None),
            verified_step_ids=self._deduplicate_strings(verified_step_ids),
            verified_local_claim_ids=verified_local_claim_ids,
            refuted_routes=refuted_routes,
            negative_evidence=self._deduplicate_strings(negative_evidence),
            open_obligations=open_obligations,
            remaining_gaps=remaining_gaps,
            execution_notes=[execution_note],
            summary=summary,
        )

    def _run_status(
        self,
        problem: ProblemContract,
        state: SolveState,
        store: ArtifactStore,
    ) -> RunStatus:
        if (
            state.final_verification is not None
            and state.final_verification.verdict == VerificationVerdict.PASS
            and state.final_verification.confidence
            >= self.config.budget.verification_pass_threshold
        ):
            return RunStatus.VERIFIED
        task_status, _assessments = assess_task_deliverables(
            problem,
            state,
            store.list_experiment_results(),
            verification_threshold=self.config.budget.verification_pass_threshold,
        )
        if task_status.value == "completed":
            return RunStatus.COMPLETED
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
        if (
            status not in {RunStatus.VERIFIED, RunStatus.COMPLETED}
            and state.research_progress_report is None
        ):
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
        experiment_payloads = store.list_experiment_results()
        experiments = [
            ExperimentResult.model_validate(payload) for payload in experiment_payloads
        ]
        coverage_steps = (
            list(state.final_proof.proof_steps)
            if state.final_proof is not None
            else [
                step
                for attempt in state.attempts
                if (
                    (report := state.aggregate_reports.get(attempt.attempt_id))
                    is not None
                    and report.verdict == VerificationVerdict.PASS
                )
                for step in attempt.proof_steps
            ]
        )
        coverage = formalization_coverage(coverage_steps, experiments)
        if state.research_progress_report is not None:
            state.research_progress_report.formalization_coverage = coverage
            store.write_json(
                "reports", "research_progress_report", state.research_progress_report
            )
        task_status, deliverable_assessments = assess_task_deliverables(
            problem,
            state,
            experiment_payloads,
            verification_threshold=self.config.budget.verification_pass_threshold,
        )
        if (
            status == RunStatus.VERIFIED
            and state.final_proof is not None
            and state.inspiration_engine is not None
        ):
            source_ids = set(state.final_proof.source_attempt_ids)
            strategy_ids = {
                attempt.strategy_id
                for attempt in state.attempts
                if attempt.attempt_id in source_ids
            }
            direct_proposal_ids = {
                strategy.inspiration_proposal_id
                for strategy in state.strategies
                if strategy.strategy_id in strategy_ids
                and strategy.inspiration_proposal_id is not None
            }
            source_route_ids = {
                route_id
                for strategy_id in strategy_ids
                if (route_id := self._route_for_strategy(state, strategy_id))
                is not None
            }
            final_dependency_ids = set(state.final_proof.dependencies)
            final_dependency_ids.update(
                dependency
                for step in state.final_proof.proof_steps
                for dependency in step.dependencies
            )
            state.inspiration_engine.mark_final_citations(
                route_ids=source_route_ids,
                obligation_ids=final_dependency_ids,
                message_ids=final_dependency_ids,
                direct_proposal_ids=direct_proposal_ids,
            )
        if state.inspiration_engine is not None:
            state.inspiration_engine.persist_cross_run_learning(
                run_verified=status == RunStatus.VERIFIED
            )
        return RunResult(
            run_id=run_id,
            status=status,
            task_status=task_status,
            deliverable_assessments=deliverable_assessments,
            math_status=math_status,
            execution_status=execution_status,
            termination_reason=state.termination_reason,
            problem=problem,
            final_proof=state.final_proof,
            final_verification=state.final_verification,
            research_progress_report=state.research_progress_report,
            formalization_coverage=coverage,
            attempts=state.attempts,
            claims=memory.claims,
            verification_reports=state.reports,
            meta_reviews=state.meta_reviews,
            proof_checkpoints=store.list_proof_checkpoints(),
            experiments=experiments,
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
        if status == RunStatus.COMPLETED:
            return (
                "用户要求的计算、反例或候选规律等交付物已经完成；未要求的猜想证明不会被伪装成已验证定理。"
                if zh
                else "The requested computation, counterexample, or conjecture deliverables were completed; an unrequested proof is not treated as a missing task."
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
                "schema_version": (
                    "0.8.2" if state.proof_control is not None else "0.7"
                ),
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
                "global_no_progress_rounds": state.global_no_progress_rounds,
                "global_meta_pivot_used": state.global_meta_pivot_used,
                "pivot_grace_used": state.pivot_grace_used,
                "hard_stopped": state.hard_stopped,
                "last_progress_signature": state.last_progress_signature,
                "proof_control_config_hash": stable_hash(
                    self.config.model_dump(mode="json")
                ),
                "proof_control_goal_hash": (
                    state.proof_graph.problem_hash
                    if state.proof_graph is not None
                    else None
                ),
                "certified_counterexample_hashes": (
                    state.certified_counterexample_hashes
                ),
                "termination_reason": state.termination_reason,
                "admission_starvation": state.admission_starvation,
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
                    proof_control=state.proof_control,
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
            if state.proof_control is not None:
                state.proof_control.persist()
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
                    "real_inequality",
                    "number_theory_check",
                ]
            )
        if (
            self.config.computation.enabled
            and self.config.computation.sandboxed_python_enabled
        ):
            tools.append("sandboxed_python")
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
