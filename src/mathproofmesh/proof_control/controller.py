from __future__ import annotations

import inspect
from collections.abc import Callable, Collection, Iterable, Mapping, Sequence
from typing import Any

from ..activity import ActivityStream
from ..communication.broker import MessageBroker
from ..communication.route_registry import RouteRegistry
from ..config import SystemConfig
from ..memory import TypedMemory
from ..proof_graph.contradictions import ContradictionRecord
from ..proof_graph.store import ProofGraphStore
from ..proof_identity import normalize_text, obligation_identity_text
from ..schemas import (
    ClaimCard,
    ClaimStatus,
    ComputationMethod,
    GraphEdgeType,
    MemoryTier,
    MessageEnvelope,
    MessageType,
    ObligationKind,
    ExperimentOutcome,
    ExperimentResult,
    ProofAttempt,
    ProofDelta,
    ProofGraphEdge,
    ProofObligation,
    ProofStep,
    RouteStatus,
    StrategyCard,
    VerificationReport,
    VerificationVerdict,
    stable_hash,
)
from ..store import ArtifactStore
from .action_dispatcher import ControlActionDispatcher
from .bottleneck import BottleneckCompressor
from .claim_lifecycle import ClaimLifecycleController
from .common_mode import CriticalAssumptionMatrix
from .dependencies import DependencyResolver, migrate_legacy_dependencies
from .domains import classify_obligation_domain
from .failure_control import BlueprintRewriter, FailureClassifier
from .falsification import (
    FalsificationContractCompiler,
    FalsificationTaskMaterializer,
    classify_falsification_result,
)
from .gates import (
    ContinueDeepeningGate,
    RouteAdmissionGate,
    SynthesisReadinessGate,
)
from .goal_alignment import (
    GoalAlignmentAnalyzer,
    GoalAlignmentContract,
    PremiseClosureAnalyzer,
)
from .induction import InductionMeasureSelector
from .inference_risk import InferenceRiskScanner
from .message_utility import MessageUtilityController
from .models import (
    AlignmentExceptionCode,
    BlueprintRewriteRequest,
    BottleneckBridgeTask,
    BottleneckCluster,
    BroadcastDecision,
    ClaimRefereeDisposition,
    ClaimRefereeRecord,
    ClaimGoalLink,
    ClaimVerificationLedgerEntry,
    ClaimVerificationState,
    ControlActionRecord,
    ControlActionResult,
    ControlActionStatus,
    ControlActionType,
    CountermodelTaskRecord,
    DependencyRef,
    DependencyResolutionResult,
    ExecutableTaskRecord,
    FalsificationTaskRecord,
    GateVerdict,
    GoalAlignmentContractResult,
    GoalRelation,
    InductionBlueprintNode,
    InferenceRiskRecord,
    InferenceRiskType,
    InspirationReviewDeferral,
    MetaPivotState,
    MetaPivotEffect,
    MetaPivotOutcome,
    MetaPivotStatus,
    MessageExpectedEffect,
    MinimalBridgeProposal,
    NegativePatternRecord,
    ObligationDomain,
    ObligationDomainRecord,
    ObligationSemanticQuality,
    ProofRole,
    RealizerFailureType,
    ResumeDecision,
    RouteAdmissionRecord,
    RouteFreezeRecord,
    RouteTargetBinding,
    RouteUpdateTask,
    ScopeRelation,
    ScopeSignature,
    SynthesisReadinessRecord,
    StrategyBlueprintCompilation,
    StrategyRevisionReason,
    StructuredVerifierIssue,
    TaskStatus,
    VerifierIssueCode,
    WakeCondition,
    WakeConditionKind,
    RewriteSemanticVerdict,
)
from .near_miss import NearMissLedger
from .proof_roles import ProofRoleClassifier, core_proof_debt
from .realizer import AbstractRealizerController
from .resume_policy import ResumePlanner
from .scope_guard import ScopeGuard
from .semantic_quality import ObligationSemanticGate
from .state import ProofControlState
from .route_target import choose_nearest_target_obligation
from .strategy_blueprint import (
    BlueprintSemanticGate,
    OriginalStrategyArchive,
    RewriteSemanticGate,
    StrategyBlueprintCompiler,
)
from .tasks import ExecutableTaskController, RouteWakeController, WakeScheduler


_WEAKER_BRIDGE_ID_PREFIX = "obl_weaker_bridge_"


class ProofControlLayer:
    """Sidecar-only proof control over existing v0.7 authorities."""

    def __init__(
        self,
        config: SystemConfig,
        store: ArtifactStore,
        activity: ActivityStream | None,
        proof_graph: ProofGraphStore,
        typed_memory: TypedMemory,
        message_broker: MessageBroker,
        route_registry: RouteRegistry,
        *,
        state: ProofControlState | None = None,
    ) -> None:
        self.config = config
        self.control_config = config.topology.proof_control
        self.store = store
        self.activity = activity
        self.proof_graph = proof_graph
        self.typed_memory = typed_memory
        self.message_broker = message_broker
        self.route_registry = route_registry
        self.state = state or ProofControlState()
        self._meta_pivot_executor: Callable[..., Any] | None = None
        self._meta_pivot_execution_round = 0
        self.action_dispatcher = ControlActionDispatcher(
            problem_hash=proof_graph.problem_hash,
            actions=self.state.control_actions,
            mode=self.control_config.mode,
            source_exists=self._control_source_exists,
            route_exists=self._control_route_exists,
            obligation_exists=self._control_obligation_exists,
            checkpoint_writer=lambda _action: self.persist(),
        )

        self.scope_guard = ScopeGuard(self.control_config.scope_guard)
        self.obligation_semantic_gate = ObligationSemanticGate()
        self.goal_alignment = GoalAlignmentAnalyzer(proof_graph, self.scope_guard)
        self.goal_alignment_contract = GoalAlignmentContract(
            self.control_config.goal_alignment,
            self.control_config.route_admission,
            strict_fail_closed=self.control_config.strict_fail_closed,
        )
        self.premise_closure = PremiseClosureAnalyzer()
        self.claim_lifecycle = (
            typed_memory.lemma_memory.attach_claim_lifecycle(
                self.state.claim_verification_ledger
            )
            if typed_memory.lemma_memory is not None
            else ClaimLifecycleController({}, self.state.claim_verification_ledger)
        )
        self.risk_scanner = InferenceRiskScanner()
        self.role_classifier = ProofRoleClassifier()
        self.realizers = AbstractRealizerController(
            self.control_config.realizer,
            structures=self.state.abstract_structures,
            candidates=self.state.realizer_candidates,
            repair_tasks=self.state.realizer_repair_tasks,
        )
        self.induction = InductionMeasureSelector(self.control_config.induction)
        self.failure_classifier = FailureClassifier(self.control_config.failure)
        self.blueprint_rewriter = BlueprintRewriter(self.control_config.failure)
        self.blueprint_rewriter.requests = self.state.blueprint_rewrites
        self.strategy_blueprint_compiler = StrategyBlueprintCompiler()
        self.blueprint_semantic_gate = BlueprintSemanticGate()
        self.rewrite_semantic_gate = RewriteSemanticGate()
        self.strategy_archive = OriginalStrategyArchive(
            self.state.original_strategy_archive,
            self.state.strategy_lineage,
        )
        self.bottlenecks = BottleneckCompressor(self.control_config.bottleneck)
        self.common_mode = CriticalAssumptionMatrix(self.control_config.common_mode)
        self.falsification_tasks = FalsificationTaskMaterializer(config)
        self.falsification_contracts = FalsificationContractCompiler()
        self.executable_task_controller = ExecutableTaskController(
            self.state.executable_tasks
        )
        self.wake_scheduler = WakeScheduler(self.state.executable_tasks)
        self.route_wake_controller = RouteWakeController(
            self.route_registry,
            self.state.executable_tasks,
            freeze_records=self.state.route_freeze_records,
        )
        self.state.route_freeze_records = self.route_wake_controller.freeze_records
        self.resume_planner = ResumePlanner(self.state.resume_decisions)
        self.message_utility = MessageUtilityController(
            self.control_config.message_utility,
            proof_graph=proof_graph,
            contracts=self.state.utility_contracts,
            receipts=self.state.usage_receipts,
            broadcast_decisions=self.state.broadcast_decisions,
        )
        self.near_misses = NearMissLedger(
            self.control_config.near_miss,
            records=self.state.near_misses,
        )
        self.route_admission_gate = RouteAdmissionGate(
            self.control_config.route_admission
        )
        self.continue_gate = ContinueDeepeningGate(
            self.control_config.continue_gate,
            prior_records=self.state.continue_gate_records,
        )
        self.readiness_gate = SynthesisReadinessGate(
            self.control_config.synthesis_readiness
        )
        self.action_dispatcher.register_handler(
            ControlActionType.CREATE_SUB_OBLIGATION,
            self._handle_create_sub_obligation,
            postcondition=self._sub_obligation_postcondition,
        )
        self.action_dispatcher.register_handler(
            ControlActionType.BIND_ROUTE_TARGET,
            self._handle_bind_route_target,
            postcondition=self._route_target_binding_postcondition,
        )
        self.action_dispatcher.register_handler(
            ControlActionType.CREATE_COUNTERMODEL_TASK,
            self._handle_create_countermodel_task,
            postcondition=self._countermodel_task_postcondition,
        )
        self.action_dispatcher.register_handler(
            ControlActionType.CLOSE_BY_DIRECT_PREMISE,
            self._handle_direct_premise_request,
            postcondition=self._direct_premise_postcondition,
        )
        self.action_dispatcher.register_handler(
            ControlActionType.REWRITE_BLUEPRINT,
            self._handle_rewrite_blueprint,
            postcondition=self._blueprint_rewrite_postcondition,
        )
        self.action_dispatcher.register_handler(
            ControlActionType.WEAKEN_TARGET,
            self._handle_weaken_target,
            postcondition=self._weaken_target_postcondition,
        )
        self.action_dispatcher.register_handler(
            ControlActionType.CREATE_MINIMAL_BRIDGE,
            self._handle_create_minimal_bridge,
            postcondition=self._minimal_bridge_postcondition,
        )
        self.action_dispatcher.register_handler(
            ControlActionType.ACTIVATE_INDUCTION_MEASURE,
            self._handle_activate_induction_measure,
            postcondition=self._induction_activation_postcondition,
        )
        self.action_dispatcher.register_handler(
            ControlActionType.CREATE_ASSUMPTION_CHALLENGER,
            self._handle_create_assumption_challenger,
            postcondition=self._assumption_challenger_postcondition,
        )
        self.action_dispatcher.register_handler(
            ControlActionType.MATERIALIZE_BOTTLENECK_CLUSTER,
            self._handle_materialize_bottleneck_cluster,
            postcondition=self._bottleneck_cluster_postcondition,
        )
        self.action_dispatcher.register_handler(
            ControlActionType.MATERIALIZE_FALSIFICATION_TASK,
            self._handle_materialize_falsification_task,
            postcondition=self._falsification_task_postcondition,
        )
        self.action_dispatcher.register_handler(
            ControlActionType.SCHEDULE_ROUTE_UPDATE,
            self._handle_schedule_route_update,
            postcondition=self._route_update_postcondition,
        )
        self.action_dispatcher.register_handler(
            ControlActionType.DEFER_INSPIRATION_REVIEW,
            self._handle_defer_inspiration_review,
            postcondition=self._inspiration_review_deferred_postcondition,
        )
        self.action_dispatcher.register_handler(
            ControlActionType.REASSIGN_INSPIRATION_REVIEW,
            self._handle_reassign_inspiration_review,
            postcondition=self._inspiration_review_reassigned_postcondition,
        )
        self.action_dispatcher.register_handler(
            ControlActionType.EXECUTE_META_PIVOT,
            self._handle_execute_meta_pivot,
            postcondition=self._meta_pivot_postcondition,
        )

        self.message_broker.set_proof_control_message_gate(self._message_gate)
        self.message_broker.set_proof_control_broadcast_gate(
            self._message_broadcast_allowed
        )
        self.proof_graph.set_proof_control_pre_close_policy(self._pre_close_policy)

    @property
    def active(self) -> bool:
        return self.control_config.enabled and self.control_config.mode == "active"

    @property
    def shadow(self) -> bool:
        return self.control_config.enabled and self.control_config.mode == "shadow"

    def archive_strategy(
        self,
        strategy: StrategyCard,
        *,
        first_seen_round: int = 0,
        raw_artifact_ref: str | None = None,
    ) -> None:
        if not self.control_config.enabled or self.control_config.mode == "off":
            return
        created = strategy.strategy_id not in self.state.original_strategy_archive
        entry = self.strategy_archive.archive(
            strategy,
            first_seen_round=first_seen_round,
            raw_artifact_ref=(
                raw_artifact_ref or f"planner-strategy:{strategy.strategy_id}"
            ),
        )
        if created:
            self._emit(
                "strategy_archived",
                {
                    "strategy_id": strategy.strategy_id,
                    "mechanism_hash": entry.mechanism_signature.normalized_hash,
                    "domain_objects": entry.domain_objects,
                },
            )

    def compile_strategy_blueprint(
        self,
        strategy: StrategyCard,
    ) -> StrategyBlueprintCompilation | None:
        if not self.control_config.enabled or self.control_config.mode == "off":
            return None
        self.archive_strategy(strategy)
        existing = self.state.strategy_blueprints.get(strategy.strategy_id)
        if existing is not None:
            return StrategyBlueprintCompilation(
                blueprint=existing,
                nodes=[
                    self.state.blueprint_nodes[item]
                    for item in existing.node_ids
                    if item in self.state.blueprint_nodes
                ],
                edges=[
                    self.state.blueprint_edges[item]
                    for item in existing.edge_ids
                    if item in self.state.blueprint_edges
                ],
            )
        main_goal = self._main_goal()
        if main_goal is None:
            return None
        compilation = self.strategy_blueprint_compiler.compile(
            strategy,
            problem_hash=self.proof_graph.problem_hash,
            main_goal=main_goal,
            open_obligations=self.proof_graph.obligations,
            admitted_facts=self.message_broker.admitted_facts(),
            negative_memory=self.typed_memory.negatives,
        )
        assessment = self.blueprint_semantic_gate.validate(
            compilation.blueprint,
            nodes=compilation.nodes,
            edges=compilation.edges,
            strategy=strategy,
            main_goal=main_goal,
        )
        self.state.strategy_blueprints[strategy.strategy_id] = compilation.blueprint
        # The main-goal node is shared by every blueprint; never let a later
        # compilation overwrite its provenance record.
        self.state.blueprint_nodes.update(
            {
                item.node_id: item
                for item in compilation.nodes
                if not (
                    item.source_field == "main_goal"
                    and item.node_id in self.state.blueprint_nodes
                )
            }
        )
        self.state.blueprint_edges.update(
            {item.edge_id: item for item in compilation.edges}
        )
        if not assessment.accepted:
            compilation.blueprint.status = "needs_review"
            compilation.review_reasons = list(assessment.reasons)
            self._emit(
                "strategy_blueprint_rejected",
                {
                    "strategy_id": strategy.strategy_id,
                    "blueprint_id": compilation.blueprint.blueprint_id,
                    "reasons": assessment.reasons,
                },
            )
            return compilation
        self._materialize_tentative_blueprint(strategy, compilation)
        self._emit(
            "strategy_blueprint_compiled",
            {
                "strategy_id": strategy.strategy_id,
                "blueprint_id": compilation.blueprint.blueprint_id,
                "node_ids": compilation.blueprint.node_ids,
                "edge_ids": compilation.blueprint.edge_ids,
            },
        )
        return compilation

    def _materialize_tentative_blueprint(
        self,
        strategy: StrategyCard,
        compilation: StrategyBlueprintCompilation,
    ) -> None:
        existing_ids = {item.obligation_id for item in self.proof_graph.obligations}
        route_id = self._blueprint_route_id(strategy)
        materialized: list[str] = []
        kind_map = {
            "claim": ObligationKind.SUBGOAL,
            "lemma": ObligationKind.LEMMA,
            "construction": ObligationKind.CONSTRUCTION,
            "case_split": ObligationKind.CASE_BRANCH,
            "countermodel_task": ObligationKind.COMPUTATION_QUESTION,
            "computation_task": ObligationKind.COMPUTATION_QUESTION,
            "given": ObligationKind.SUBGOAL,
        }
        for node in compilation.nodes:
            if (
                node.node_id == compilation.blueprint.main_goal_node_id
                or node.node_id in existing_ids
            ):
                continue
            candidate = ProofObligation(
                obligation_id=node.node_id,
                problem_hash=self.proof_graph.problem_hash,
                route_ids=[route_id],
                kind=kind_map.get(node.kind.value, ObligationKind.LEMMA),
                statement=node.statement,
                normalized_statement=node.normalized_statement,
                assumptions=list(node.assumptions),
                quantifiers=list(node.quantifiers),
                status="tentative",
                priority=0.75,
                centrality=0.65,
            )
            quality = self._assess_obligation_quality(
                candidate,
                source_kind=(
                    "countermodel_task"
                    if node.kind.value == "countermodel_task"
                    else "computation"
                    if node.kind.value == "computation_task"
                    else "strategy_blueprint"
                ),
                executable_first_step=node.executable_first_step,
            )
            if not quality.accepted:
                continue
            self.proof_graph.add_obligation(candidate)
            materialized.append(candidate.obligation_id)
        compilation.materialized_obligation_ids = materialized

    def _retract_blocked_blueprint(
        self,
        strategy: StrategyCard,
        compilation: StrategyBlueprintCompilation | None,
        *,
        reason: str,
    ) -> None:
        """Undo draft materialization for a strategy whose admission failed.

        Keeps the active proof graph and every count derived from it (open
        obligations, research gaps) free of blueprints that never became
        routes.
        """
        if compilation is None or not compilation.materialized_obligation_ids:
            return
        route_id = self._blueprint_route_id(strategy)
        retracted: list[str] = []
        for obligation_id in compilation.materialized_obligation_ids:
            removed = self.proof_graph.retract_tentative_obligation(
                obligation_id,
                route_id=route_id,
                reason=reason,
            )
            if removed is not None:
                retracted.append(obligation_id)
        if retracted:
            self._emit(
                "blueprint_draft_retracted",
                {
                    "strategy_id": strategy.strategy_id,
                    "blueprint_id": compilation.blueprint.blueprint_id,
                    "obligation_ids": retracted,
                    "reason": reason,
                },
            )

    def _blueprint_route_id(self, strategy: StrategyCard) -> str:
        route = self.route_registry.route_for_strategy(strategy.strategy_id)
        return (
            route.route_id
            if route is not None
            else "route_"
            + stable_hash((self.proof_graph.problem_hash, strategy.strategy_id))[:20]
        )

    def _binding_from_blueprint(
        self,
        strategy: StrategyCard,
        compilation: StrategyBlueprintCompilation,
    ) -> RouteTargetBinding:
        blueprint = compilation.blueprint
        direct_id = next(
            (
                node_id
                for node_id in blueprint.direct_target_node_ids
                if self._control_obligation_exists(node_id)
                and (
                    node_id not in self.state.obligation_semantic_quality
                    or self.state.obligation_semantic_quality[node_id].accepted
                )
            ),
            None,
        )
        if direct_id is None:
            details: list[str] = []
            for node_id in blueprint.direct_target_node_ids[:6]:
                node = self.state.blueprint_nodes.get(node_id)
                statement = (node.statement if node is not None else node_id)[:90]
                quality = self.state.obligation_semantic_quality.get(node_id)
                if quality is not None and quality.needs_normalization:
                    needs = ", ".join(quality.normalization_needs) or "structure"
                    details.append(
                        f"candidate needs normalization ({needs}); keep the "
                        f"mathematics, restate with explicit objects/quantifiers/"
                        f"relation: {statement!r}"
                    )
                elif quality is not None and quality.rejection_reasons:
                    details.append(
                        f"candidate rejected "
                        f"({', '.join(quality.rejection_reasons[:4])}): {statement!r}"
                    )
                else:
                    details.append(f"candidate not materialized: {statement!r}")
            raise ValueError(
                "blueprint has no semantically admissible direct target "
                "[system parsing issue, not a mathematical defect — keep the "
                "same mathematical strategy]; " + "; ".join(details)
            )
        outgoing = {
            edge.source_node_id: edge.target_node_id
            for edge in compilation.edges
            if self._control_obligation_exists(edge.source_node_id)
            and self._control_obligation_exists(edge.target_node_id)
        }
        path = [direct_id]
        while path[-1] != blueprint.main_goal_node_id:
            next_id = outgoing.get(path[-1])
            if next_id is None or next_id in path:
                break
            path.append(next_id)
        target = self.proof_graph.get_obligation(direct_id)
        matched_claim_ids = [
            item.claim_id
            for item in strategy.critical_claims
            if obligation_identity_text(item.statement)
            == obligation_identity_text(target.normalized_statement)
        ]
        binding = RouteTargetBinding(
            binding_id=(
                "route_target_"
                + stable_hash(
                    {
                        "problem_hash": self.proof_graph.problem_hash,
                        "strategy_id": strategy.strategy_id,
                        "blueprint_id": blueprint.blueprint_id,
                        "direct_target": direct_id,
                    }
                )[:16]
            ),
            strategy_id=strategy.strategy_id,
            route_id=(
                self.route_registry.route_for_strategy(strategy.strategy_id).route_id
                if self.route_registry.route_for_strategy(strategy.strategy_id)
                is not None
                else None
            ),
            direct_target_obligation_id=direct_id,
            ancestor_obligation_ids=path[1:],
            main_goal_obligation_id=blueprint.main_goal_node_id,
            direct_claim_ids=matched_claim_ids,
            bridge_obligation_ids=path[1:-1],
            relation_to_direct_target=GoalRelation.SUFFICIENT,
            relation_to_main_goal=GoalRelation.NECESSARY_ONLY,
            scope_relation_to_direct_target=ScopeRelation.SAME,
            blueprint_path_complete=(
                bool(path) and path[-1] == blueprint.main_goal_node_id
            ),
            binding_confidence=blueprint.compilation_confidence,
        )
        self.state.route_target_bindings[binding.binding_id] = binding
        return binding

    def _activate_blueprint(
        self,
        strategy: StrategyCard,
        compilation: StrategyBlueprintCompilation,
    ) -> None:
        route_id = self._blueprint_route_id(strategy)
        for node in compilation.nodes:
            if node.node_id == compilation.blueprint.main_goal_node_id:
                continue
            if not self._control_obligation_exists(node.node_id):
                continue
            obligation = self.proof_graph.get_obligation(node.node_id)
            if route_id not in obligation.route_ids:
                obligation.route_ids.append(route_id)
            if obligation.status == "tentative":
                obligation.status = "open"
        for edge in compilation.edges:
            if not (
                self._control_obligation_exists(edge.source_node_id)
                and self._control_obligation_exists(edge.target_node_id)
            ):
                continue
            self._ensure_dependency_edge(
                edge.target_node_id,
                edge.source_node_id,
            )
        compilation.blueprint.status = "accepted"

    def register_strategy(
        self,
        strategy: StrategyCard,
        *,
        blueprint_binding: RouteTargetBinding | None = None,
    ) -> ClaimGoalLink | None:
        existing = self._goal_link_for_subject(strategy.strategy_id)
        existing_binding = self._route_target_binding_for_strategy(strategy.strategy_id)
        existing_contract = self._alignment_contract_for_subject(strategy.strategy_id)
        if (
            existing is not None
            and existing_binding is not None
            and existing_contract is not None
        ):
            for obligation in self.proof_graph.obligations:
                self._ensure_obligation_domain(obligation)
            self.materialize_strategy_falsification(
                strategy,
                target_obligation_id=existing_binding.direct_target_obligation_id,
                route_id=existing_binding.route_id,
                current_round=0,
                authority_source_id=existing.link_id,
            )
            return existing
        for obligation in self.proof_graph.obligations:
            self._ensure_obligation_domain(obligation)
        binding = blueprint_binding
        if binding is None:
            try:
                binding = choose_nearest_target_obligation(
                    strategy,
                    self.proof_graph,
                    self.state.goal_links,
                    self.state.obligation_domains,
                    self.state.obligation_semantic_quality,
                )
            except ValueError as exc:
                self._emit(
                    "goal_alignment_blocked",
                    {
                        "subject_id": strategy.strategy_id,
                        "reason": str(exc),
                    },
                )
                return None
        generated_subgoal_id: str | None = None
        main_goal = self.proof_graph.get_obligation(binding.main_goal_obligation_id)
        if (
            blueprint_binding is None
            and binding.direct_target_obligation_id == binding.main_goal_obligation_id
            and obligation_identity_text(strategy.bottleneck)
            != obligation_identity_text(main_goal.normalized_statement)
            and bool(strategy.expected_lemmas)
            and bool(strategy.falsification_test.strip())
            and bool(strategy.key_original_step and strategy.key_original_step.strip())
        ):
            generated_subgoal_id = self._dispatch_strategy_sub_obligation(
                strategy,
                main_goal_id=binding.main_goal_obligation_id,
            )
            if generated_subgoal_id is not None:
                self._ensure_obligation_domain(
                    self.proof_graph.get_obligation(generated_subgoal_id),
                    source_kind="mathematical",
                )
                binding = binding.model_copy(
                    update={
                        "direct_target_obligation_id": generated_subgoal_id,
                        "ancestor_obligation_ids": [binding.main_goal_obligation_id],
                        "bridge_obligation_ids": [],
                        "blueprint_path_complete": True,
                        "binding_confidence": max(
                            binding.binding_confidence,
                            0.9,
                        ),
                    }
                )
        route = self.route_registry.route_for_strategy(strategy.strategy_id)
        if route is not None:
            binding = binding.model_copy(update={"route_id": route.route_id})
        target = self.proof_graph.get_obligation(binding.direct_target_obligation_id)
        link = existing
        if link is None and blueprint_binding is not None:
            link = ClaimGoalLink(
                subject_id=strategy.strategy_id,
                subject_kind="strategy",
                target_obligation_id=target.obligation_id,
                relation=GoalRelation.SUFFICIENT,
                scope_relation=ScopeRelation.SAME,
                implication_outline=[
                    strategy.strategy_id,
                    *binding.ancestor_obligation_ids,
                ],
                remaining_obligation_ids_if_proved=list(
                    binding.ancestor_obligation_ids
                ),
                required_bridge_obligation_ids=list(binding.bridge_obligation_ids),
                minimality_score=0.9,
                alignment_confidence=binding.binding_confidence,
                assessment_source="deterministic",
                evidence_refs=[
                    self.state.strategy_blueprints[strategy.strategy_id].blueprint_id
                ],
            )
        if link is None:
            link = self.goal_alignment.assess_strategy(strategy, target)
        registered = (
            link if existing is not None else self._register_goal_link(strategy, link)
        )
        binding = binding.model_copy(
            update={
                "relation_to_direct_target": registered.relation,
                "relation_to_main_goal": (
                    registered.relation
                    if binding.direct_target_obligation_id
                    == binding.main_goal_obligation_id
                    else GoalRelation.NECESSARY_ONLY
                ),
                "scope_relation_to_direct_target": registered.scope_relation,
                "binding_confidence": min(
                    binding.binding_confidence,
                    registered.alignment_confidence,
                ),
            }
        )
        self._dispatch_route_target_binding(binding, registered)
        countermodel_action_id = self._dispatch_countermodel_if_required(
            registered,
            route_id=binding.route_id,
        )
        closure = self.premise_closure.scan(
            target,
            given_assumptions=target.assumptions,
            verified_facts=self.message_broker.admitted_facts(),
            verified_claims=self.typed_memory.verified(),
        )
        self.state.premise_closure_records[closure.record_id] = closure
        exception_code = None
        exception_evidence_ids: list[str] = []
        if closure.verified:
            direct_action = self.action_dispatcher.propose(
                ControlActionType.CLOSE_BY_DIRECT_PREMISE,
                source_record_ids=[closure.record_id],
                route_ids=[binding.route_id] if binding.route_id is not None else [],
                target_obligation_ids=[target.obligation_id],
                payload={"premise_closure_record_id": closure.record_id},
            )
            self.action_dispatcher.execute_sync(
                direct_action.action_id, current_round=0
            )
            exception_code = AlignmentExceptionCode.DIRECT_PREMISE_CLOSURE
            exception_evidence_ids = [
                closure.record_id,
                *closure.supporting_ids,
            ]
        contract = self.goal_alignment_contract.validate(
            registered,
            exception_code=exception_code,
            exception_evidence_ids=exception_evidence_ids,
            countermodel_action_id=countermodel_action_id,
        )
        self.state.goal_alignment_contracts[contract.contract_id] = contract
        self._emit(
            "goal_alignment_contract_evaluated",
            contract.model_dump(mode="json"),
        )
        self.materialize_strategy_falsification(
            strategy,
            target_obligation_id=target.obligation_id,
            route_id=binding.route_id,
            current_round=0,
            authority_source_id=registered.link_id,
        )
        return registered

    def register_claim(
        self,
        claim: ClaimCard,
        *,
        route_id: str | None = None,
    ) -> ClaimGoalLink | None:
        if claim.claim_id in self.claim_lifecycle.claims:
            self.claim_lifecycle.register_claim(claim)
        existing = self._goal_link_for_subject(claim.claim_id)
        if existing is not None:
            return existing
        signature = self.scope_guard.extract_from_claim(claim)
        self.state.scope_signatures[claim.claim_id] = signature
        target = self._main_goal()
        if target is None:
            return None
        link = self.goal_alignment.assess_claim(
            claim,
            target,
            subject_scope=signature,
            target_scope=self._scope_for_obligation(target),
        )
        registered = self._register_goal_link(claim, link)
        self._register_risks(
            self.risk_scanner.scan_claim(
                claim,
                conclusion_scope=signature,
                route_id=route_id,
            )
        )
        return registered

    def claim_fact_promotion_allowed(
        self,
        claim: ClaimCard,
        *,
        route_id: str | None = None,
    ) -> bool:
        self.register_claim(claim, route_id=route_id)
        signature = self.state.scope_signatures.get(claim.claim_id)
        ledger_entry = self.state.claim_verification_ledger.get(claim.claim_id)
        referee_scope_verified = bool(
            ledger_entry is not None
            and any(
                (record := self.state.claim_referee_records.get(review_id)) is not None
                and record.disposition == ClaimRefereeDisposition.ACCEPT
                and record.scope_valid
                and record.quantifiers_valid
                for review_id in ledger_entry.referee_review_ids
            )
        )
        scope_known = referee_scope_verified or bool(
            signature is not None
            and signature.normalization_confidence
            >= self.control_config.scope_guard.risk_confidence_threshold
        )
        referenced_by_count = sum(
            claim.claim_id in item.dependencies
            for item in self.claim_lifecycle.claims.values()
            if item.claim_id != claim.claim_id
        )
        self._register_risks(
            self.risk_scanner.critical_step_semantic_scan(
                subject_id=claim.claim_id,
                scope_known=scope_known,
                centrality=1.0,
                referenced_by_count=referenced_by_count,
                preparing_fact_promotion=True,
                route_id=route_id,
            )
        )
        return not any(
            risk.subject_id == claim.claim_id and risk.blocks_fact_promotion
            for risk in self.state.inference_risks.values()
        )

    def register_message(self, message: MessageEnvelope) -> ClaimGoalLink | None:
        existing = self._goal_link_for_subject(message.message_id)
        if existing is not None:
            return existing
        signature = self.scope_guard.extract_from_message(message)
        self.state.scope_signatures[message.message_id] = signature
        target = self._matching_or_main_obligation(message)
        if target is None:
            return None
        link = self.goal_alignment.assess_message(
            message,
            target,
            subject_scope=signature,
            target_scope=self._scope_for_obligation(target),
        )
        registered = self._register_goal_link(message, link)
        self._register_risks(
            self.risk_scanner.scan_goal_link(link, route_id=message.source_route_id)
        )
        return registered

    def register_obligation(
        self,
        obligation: ProofObligation,
        *,
        source_kind: str | None = None,
        source_statement: str | None = None,
        executable_first_step: str | None = None,
    ) -> ClaimGoalLink | None:
        quality = self._assess_obligation_quality(
            obligation,
            source_kind=source_kind,
            source_statement=source_statement,
            executable_first_step=executable_first_step,
        )
        if not quality.accepted:
            return None
        signature = self._scope_for_obligation(obligation)
        if obligation.kind == ObligationKind.MAIN_GOAL:
            self.state.proof_roles[obligation.obligation_id] = ProofRole.CORE_BRIDGE
            self._emit(
                "proof_role_assigned",
                {
                    "subject_id": obligation.obligation_id,
                    "proof_role": ProofRole.CORE_BRIDGE.value,
                },
            )
            return None
        existing = self._goal_link_for_subject(obligation.obligation_id)
        if existing is not None:
            return existing
        target = self._main_goal()
        if target is None:
            return None
        link = self.goal_alignment.assess_obligation(
            obligation,
            target,
            subject_scope=signature,
            target_scope=self._scope_for_obligation(target),
        )
        return self._register_goal_link(obligation, link)

    def _assess_obligation_quality(
        self,
        obligation: ProofObligation,
        *,
        source_kind: str | None,
        source_statement: str | None = None,
        executable_first_step: str | None = None,
    ) -> ObligationSemanticQuality:
        quality = self.obligation_semantic_gate.assess(
            obligation,
            source_kind=source_kind,
            main_goal=self._main_goal(),
            source_statement=source_statement,
            executable_first_step=executable_first_step,
        )
        self.state.obligation_semantic_quality[obligation.obligation_id] = quality
        domain = self._ensure_obligation_domain(
            obligation,
            source_kind=source_kind,
        )
        if domain.domain != quality.domain:
            domain = domain.model_copy(
                update={
                    "domain": quality.domain,
                    "inferred_from": "semantic_quality_gate",
                    "confidence": max(domain.confidence, quality.score),
                }
            )
            self.state.obligation_domains[obligation.obligation_id] = domain
        if quality.semantic_quarantine:
            self.state.semantic_quarantine[obligation.obligation_id] = quality
            self._emit(
                "obligation_semantic_quarantined",
                quality.model_dump(mode="json"),
            )
        else:
            self.state.semantic_quarantine.pop(obligation.obligation_id, None)
            self._emit(
                "obligation_semantic_accepted",
                quality.model_dump(mode="json"),
            )
        return quality

    def register_attempt(self, attempt: ProofAttempt) -> None:
        if not self.control_config.enabled or self.control_config.mode == "off":
            return
        route_id = self._route_id_for_strategy(attempt.strategy_id)
        local_steps = {
            item.step_id: item
            for item in [
                *attempt.proof_steps,
                *(
                    step
                    for claim in attempt.proposed_lemmas
                    for step in claim.proof_steps
                ),
            ]
        }
        local_claims = {item.claim_id: item for item in attempt.proposed_lemmas}
        self._normalize_step_dependencies(
            attempt.proof_steps,
            source_attempt_id=attempt.attempt_id,
            source_delta_id=None,
            route_id=route_id,
            local_step_ids=local_steps,
            local_claim_ids=local_claims,
        )
        for claim in attempt.proposed_lemmas:
            if claim.source_attempt_id is None:
                claim.source_attempt_id = attempt.attempt_id
            self._normalize_claim_dependencies(
                claim,
                source_attempt_id=attempt.attempt_id,
                source_delta_id=claim.source_delta_id,
                route_id=route_id,
                local_steps=local_steps,
                local_claims=local_claims,
            )
            self.register_claim(claim, route_id=route_id)
        self._register_steps(
            attempt.proof_steps,
            route_id=route_id,
            target_obligation_ids=self._main_goal_ids(),
        )
        self._register_induction_hints(
            route_id=route_id,
            source_id=attempt.attempt_id,
            source_agent_id=attempt.agent_id,
            target_obligation_ids=self._induction_target_obligation_ids(
                route_id,
                [*attempt.unresolved_gaps, *attempt.dead_ends],
            ),
            texts=[
                *(item.statement for item in attempt.proof_steps),
                *attempt.unresolved_gaps,
                *attempt.dead_ends,
            ],
        )

    def register_delta(
        self,
        delta: ProofDelta,
        *,
        source_attempt_id: str | None = None,
    ) -> None:
        if not self.control_config.enabled or self.control_config.mode == "off":
            return
        route_id = self._route_id_for_strategy(delta.strategy_id)
        local_steps = {
            item.step_id: item
            for item in [
                *delta.new_steps,
                *(step for claim in delta.new_claims for step in claim.proof_steps),
            ]
        }
        local_claims = {item.claim_id: item for item in delta.new_claims}
        self._normalize_step_dependencies(
            delta.new_steps,
            source_attempt_id=source_attempt_id,
            source_delta_id=delta.delta_id,
            route_id=route_id,
            local_step_ids=local_steps,
            local_claim_ids=local_claims,
        )
        for claim in delta.new_claims:
            if claim.source_attempt_id is None and source_attempt_id is not None:
                claim.source_attempt_id = source_attempt_id
            if claim.source_delta_id is None:
                claim.source_delta_id = delta.delta_id
            self._normalize_claim_dependencies(
                claim,
                source_attempt_id=claim.source_attempt_id,
                source_delta_id=delta.delta_id,
                route_id=route_id,
                local_steps=local_steps,
                local_claims=local_claims,
            )
            self.register_claim(claim, route_id=route_id)
        self._register_steps(
            delta.new_steps,
            route_id=route_id,
            target_obligation_ids=self._main_goal_ids(),
        )
        self._register_induction_hints(
            route_id=route_id,
            source_id=delta.delta_id,
            source_agent_id=delta.agent_id,
            target_obligation_ids=self._induction_target_obligation_ids(
                route_id,
                [
                    *(item for item in [delta.current_goal] if item),
                    *delta.remaining_subgoals,
                    *delta.known_risks,
                ],
            ),
            texts=[
                *(item.statement for item in delta.new_steps),
                *delta.remaining_subgoals,
                *delta.known_risks,
            ],
        )
        self._register_abstract_realizer_if_explicit(delta, route_id=route_id)

    def apply_route_referee_records(
        self,
        records: Sequence[ClaimRefereeRecord],
        *,
        claims: Sequence[ClaimCard],
        local_steps: Sequence[ProofStep] = (),
        structurally_verified_step_ids: Collection[str] = (),
        independent_report_ids: Sequence[str] = (),
        confidence: float = 1.0,
    ) -> list[ClaimVerificationLedgerEntry]:
        if not self.control_config.enabled or self.control_config.mode == "off":
            return []
        claims_by_id = {item.claim_id: item for item in claims}
        transient_claim_ids = [
            claim.claim_id
            for claim in claims
            if claim.claim_id not in self.claim_lifecycle.claims
        ]
        for claim_id, claim in claims_by_id.items():
            self.claim_lifecycle.claims.setdefault(claim_id, claim)
        try:
            for claim in claims:
                self.claim_lifecycle.register_claim(claim)
                self.register_claim(claim)
            applied: list[ClaimVerificationLedgerEntry] = []
            for supplied_record in records:
                claim = claims_by_id.get(supplied_record.claim_id)
                if claim is None:
                    self._emit(
                        "claim_referee_record_deferred",
                        {
                            "review_id": supplied_record.review_id,
                            "claim_id": supplied_record.claim_id,
                            "reason": "claim_not_present_in_reviewed_artifact",
                        },
                    )
                    continue
                record = supplied_record
                resolution = self.resolve_claim_dependencies(
                    claim,
                    local_steps=local_steps,
                    structurally_verified_step_ids=structurally_verified_step_ids,
                    local_claims=claims,
                    structurally_verified_claim_ids=(
                        claims_by_id if independent_report_ids else ()
                    ),
                )
                if (
                    record.disposition == ClaimRefereeDisposition.ACCEPT
                    and not resolution.resolved
                ):
                    record = record.model_copy(
                        update={
                            "disposition": (
                                ClaimRefereeDisposition.NEEDS_ADDITIONAL_REVIEW
                            ),
                            "dependencies_valid": False,
                            "reason": (
                                f"{record.reason} Dependency resolution remains open."
                            ).strip(),
                        }
                    )
                existing = self.state.claim_referee_records.get(record.review_id)
                if existing is not None and existing != record:
                    raise ValueError(
                        f"conflicting claim referee review ID: {record.review_id}"
                    )
                self.state.claim_referee_records[record.review_id] = record
                if independent_report_ids and self.claim_lifecycle.ledger[
                    claim.claim_id
                ].state not in {
                    ClaimVerificationState.INVALIDATED,
                    ClaimVerificationState.REJECTED,
                }:
                    self.claim_lifecycle.record_checkpoint_verification(
                        claim.claim_id,
                        report_ids=list(independent_report_ids),
                        confidence=confidence,
                        independent=True,
                    )
                entry = self.claim_lifecycle.apply_referee_record(record)
                applied.append(entry)
                self._emit(
                    "claim_referee_record_applied",
                    {
                        **record.model_dump(mode="json"),
                        "ledger_state": entry.state.value,
                    },
                )
            self.persist()
            return applied
        finally:
            for claim_id in transient_claim_ids:
                self.claim_lifecycle.claims.pop(claim_id, None)

    def resolve_claim_dependencies(
        self,
        claim: ClaimCard,
        *,
        local_steps: Sequence[ProofStep] = (),
        structurally_verified_step_ids: Collection[str] = (),
        invalidated_local_step_ids: Collection[str] = (),
        local_claims: Sequence[ClaimCard] = (),
        structurally_verified_claim_ids: Collection[str] = (),
    ) -> DependencyResolutionResult:
        refs = [
            item
            if isinstance(item, DependencyRef)
            else DependencyRef.model_validate(item)
            for item in claim.dependency_refs
        ]
        broker_facts = self.message_broker.admitted_facts()
        all_local_claims = {
            **self.claim_lifecycle.claims,
            **{item.claim_id: item for item in local_claims},
        }
        verified_states = {
            ClaimVerificationState.LOCALLY_VERIFIED,
            ClaimVerificationState.INDEPENDENTLY_VERIFIED,
            ClaimVerificationState.REFEREE_ACCEPTED,
            ClaimVerificationState.FACT_CANDIDATE,
            ClaimVerificationState.FACT,
        }
        verified_claim_ids = {
            claim_id
            for claim_id, entry in self.claim_lifecycle.ledger.items()
            if entry.state in verified_states
        } | set(structurally_verified_claim_ids)
        return DependencyResolver(
            local_steps={item.step_id: item for item in local_steps},
            structurally_verified_step_ids=structurally_verified_step_ids,
            invalidated_local_step_ids=invalidated_local_step_ids,
            local_claims=all_local_claims,
            verified_local_claim_ids=verified_claim_ids,
            broker_fact_ids={item.message_id for item in broker_facts},
            broker_fact_hashes={item.content_hash for item in broker_facts},
            message_ids={item.message_id for item in self.message_broker.messages},
            obligation_ids={
                item.obligation_id for item in self.proof_graph.obligations
            },
            external_result_ids={item.artifact_ref for item in claim.evidence_refs},
            source_attempt_id=claim.source_attempt_id,
            source_delta_id=claim.source_delta_id,
        ).resolve_all(refs)

    def _normalize_claim_dependencies(
        self,
        claim: ClaimCard,
        *,
        source_attempt_id: str | None,
        source_delta_id: str | None,
        route_id: str | None,
        local_steps: Mapping[str, ProofStep],
        local_claims: Mapping[str, ClaimCard],
    ) -> None:
        refs = self._normalize_dependencies(
            claim.dependencies,
            claim.dependency_refs,
            source_attempt_id=source_attempt_id,
            source_delta_id=source_delta_id,
            route_id=route_id,
            local_step_ids=local_steps,
            local_claim_ids=local_claims,
            subject_id=claim.claim_id,
        )
        claim.dependency_refs = refs
        for step in claim.proof_steps:
            step.dependency_refs = self._normalize_dependencies(
                step.dependencies,
                step.dependency_refs,
                source_attempt_id=source_attempt_id,
                source_delta_id=source_delta_id,
                route_id=route_id,
                local_step_ids=local_steps,
                local_claim_ids=local_claims,
                subject_id=step.step_id,
            )

    def _normalize_step_dependencies(
        self,
        steps: Sequence[ProofStep],
        *,
        source_attempt_id: str | None,
        source_delta_id: str | None,
        route_id: str | None,
        local_step_ids: Mapping[str, ProofStep],
        local_claim_ids: Mapping[str, ClaimCard],
    ) -> None:
        for step in steps:
            step.dependency_refs = self._normalize_dependencies(
                step.dependencies,
                step.dependency_refs,
                source_attempt_id=source_attempt_id,
                source_delta_id=source_delta_id,
                route_id=route_id,
                local_step_ids=local_step_ids,
                local_claim_ids=local_claim_ids,
                subject_id=step.step_id,
            )

    def _normalize_dependencies(
        self,
        legacy_ids: Sequence[str],
        supplied_refs: Sequence[Any],
        *,
        source_attempt_id: str | None,
        source_delta_id: str | None,
        route_id: str | None,
        local_step_ids: Collection[str],
        local_claim_ids: Collection[str],
        subject_id: str,
    ) -> list[DependencyRef]:
        refs = [
            item
            if isinstance(item, DependencyRef)
            else DependencyRef.model_validate(item)
            for item in supplied_refs
        ]
        migration = migrate_legacy_dependencies(
            legacy_ids,
            source_attempt_id=source_attempt_id,
            source_delta_id=source_delta_id,
            source_route_id=route_id,
            local_step_ids=local_step_ids,
            local_claim_ids=local_claim_ids,
            broker_fact_ids={
                item.message_id for item in self.message_broker.admitted_facts()
            },
        )
        refs.extend(migration.dependency_refs)
        deduplicated = {
            (
                item.kind.value,
                item.target_id,
                item.source_attempt_id,
                item.source_delta_id,
                item.source_route_id,
                item.content_hash,
            ): item
            for item in refs
        }
        if migration.normalization_task is not None:
            task = migration.normalization_task.model_copy(
                update={
                    "task_id": (
                        "dependency_normalization_"
                        + stable_hash(
                            {
                                "base_task_id": migration.normalization_task.task_id,
                                "subject_id": subject_id,
                            }
                        )[:20]
                    )
                }
            )
            self.state.dependency_normalization_tasks[task.task_id] = task
            self._emit(
                "ambiguous_dependency",
                {
                    "subject_id": subject_id,
                    **task.model_dump(mode="json"),
                },
            )
        return list(deduplicated.values())

    def process_verification_report(
        self,
        report: VerificationReport,
        *,
        route_id: str | None = None,
        attempt: ProofAttempt | None = None,
        delta: ProofDelta | None = None,
    ) -> None:
        route_id = route_id or (
            self._route_id_for_strategy(attempt.strategy_id)
            if attempt is not None
            else self._route_id_for_strategy(delta.strategy_id)
            if delta is not None
            else None
        )
        structured_issues = self._structured_verifier_issues(report)
        self._register_risks(
            risk
            for issue in structured_issues
            for risk in self.risk_scanner.map_verifier_issue(
                issue,
                route_id=route_id,
            )
        )
        if route_id is None:
            return
        subject_ids = {
            report.target_id,
            *(
                item.step_id
                for item in (
                    attempt.proof_steps
                    if attempt is not None
                    else delta.new_steps
                    if delta is not None
                    else []
                )
            ),
        }
        if report.verdict == VerificationVerdict.PASS:
            current_issue_ids = {item.issue_id for item in structured_issues}
            for risk in self.state.inference_risks.values():
                if risk.subject_id in subject_ids and risk.status == "open":
                    if current_issue_ids.intersection(risk.source_issue_ids):
                        continue
                    if risk.risk_type in self._property_strengthening_risk_types():
                        if not self._verified_risk_bridges(risk):
                            continue
                        risk.status = "accepted_with_bridge"
                    else:
                        risk.status = "cleared"
                    self._emit(
                        "inference_risk_cleared",
                        {"risk_id": risk.risk_id, "subject_id": risk.subject_id},
                    )
        else:
            strategy_link = self._goal_link_for_route(route_id)
            related_risks = [
                item
                for item in self.state.inference_risks.values()
                if item.route_id in {None, route_id}
                and item.status == "open"
                and item.subject_id in subject_ids
            ]
            near_miss = self._extract_near_miss(
                report,
                route_id=route_id,
                attempt=attempt,
                delta=delta,
            )
            failure = self.failure_classifier.classify(
                report,
                route_id=route_id,
                goal_link=strategy_link,
                risks=related_risks,
                near_miss=near_miss,
            )
            self.state.failure_records[failure.record_id] = failure
            self._emit("failure_classified", failure.model_dump(mode="json"))
            self._record_realizer_failure(report, route_id=route_id)
        self._update_core_debt(route_id)

    def _structured_verifier_issues(
        self,
        report: VerificationReport,
    ) -> list[StructuredVerifierIssue]:
        structured: dict[str, StructuredVerifierIssue] = {}
        for raw_issue in report.structured_issues:
            try:
                issue = (
                    raw_issue
                    if isinstance(raw_issue, StructuredVerifierIssue)
                    else StructuredVerifierIssue.model_validate(raw_issue)
                )
            except (TypeError, ValueError):
                continue
            structured[issue.issue_id] = issue

        for issue in report.issues:
            raw_code = issue.issue_code
            if raw_code is None:
                raw_code = self.risk_scanner.infer_issue_code(
                    " ".join(
                        value
                        for value in (
                            issue.description,
                            issue.repair_hint or "",
                            issue.premise_summary,
                            issue.conclusion_summary,
                        )
                        if value
                    )
                )
            try:
                code = (
                    raw_code
                    if isinstance(raw_code, VerifierIssueCode)
                    else VerifierIssueCode(str(raw_code))
                    if raw_code is not None
                    else None
                )
            except ValueError:
                code = VerifierIssueCode.OTHER
            if code is None:
                continue
            mapped = StructuredVerifierIssue(
                issue_id=issue.issue_id,
                report_id=report.report_id,
                target_id=issue.claim_id or report.target_id,
                step_id=issue.step_id,
                code=code,
                premise_summary=(issue.premise_summary or issue.description),
                conclusion_summary=(
                    issue.conclusion_summary
                    or issue.repair_hint
                    or "the reported conclusion"
                ),
                confidence=report.confidence,
            )
            structured[mapped.issue_id] = mapped
        return list(structured.values())

    def resolve_inference_risk_with_bridges(
        self,
        risk_id: str,
        *,
        bridge_obligation_ids: Sequence[str],
    ) -> InferenceRiskRecord:
        risk = self.state.inference_risks[risk_id]
        supplied = sorted(set(bridge_obligation_ids))
        if not supplied:
            raise ValueError("at least one verified bridge obligation is required")
        risk.required_bridge_obligation_ids = supplied
        if not self._verified_risk_bridges(risk):
            raise ValueError("bridge obligations are not closed by reusable evidence")
        risk.status = "accepted_with_bridge"
        self._emit(
            "inference_risk_cleared_by_bridge",
            {
                "risk_id": risk.risk_id,
                "bridge_obligation_ids": supplied,
            },
        )
        self.persist()
        return risk

    def update_after_round(
        self,
        *,
        strategies: Sequence[StrategyCard],
        current_round: int,
    ) -> None:
        route_ids = [item.route_id for item in self.route_registry.routes]
        for obligation in self.proof_graph.obligations:
            self._ensure_obligation_domain(obligation)
        for main_goal_id in self.proof_graph.main_goal_obligation_ids():
            main_goal = self.proof_graph.get_obligation(main_goal_id)
            main_goal.route_ids = list(
                dict.fromkeys([*main_goal.route_ids, *route_ids])
            )
        for obligation in self.proof_graph.obligations:
            self.register_obligation(obligation)
        for message in self.message_broker.messages:
            self.register_message(message)

        if (
            self.control_config.bottleneck.enabled
            and current_round
            % self.control_config.bottleneck.compression_interval_rounds
            == 0
        ):
            groups = self.bottlenecks.deterministic_clusters(
                self.proof_graph,
                scope_signatures=self.state.scope_signatures,
                obligation_domains=self.state.obligation_domains,
                semantic_quality=self.state.obligation_semantic_quality,
            )
            existing_members = {
                tuple(item.member_obligation_ids)
                for item in self.state.bottleneck_clusters.values()
            }
            for cluster in self.bottlenecks.materialize_clusters(
                self.proof_graph, groups
            ):
                key = tuple(cluster.member_obligation_ids)
                if key in existing_members:
                    continue
                action = self.materialize_bottleneck_cluster(
                    cluster,
                    current_round=current_round,
                )
                if action.status != ControlActionStatus.EXECUTED:
                    continue
                existing_members.add(key)
            for cluster in self.state.bottleneck_clusters.values():
                previous = cluster.status
                self.bottlenecks.refresh_cluster_status(self.proof_graph, cluster)
                if previous != "resolved" and cluster.status == "resolved":
                    self._emit(
                        "bottleneck_cluster_resolved",
                        {"cluster_id": cluster.cluster_id},
                    )

        assumptions = self.common_mode.build(
            self.route_registry.routes,
            strategies,
            self.message_broker.messages,
            [
                item
                for item in self.proof_graph.obligations
                if self._eligible_mathematical_obligation(item)
            ],
        )
        self.state.assumption_domains = dict(self.common_mode.domain_records)
        self.state.critical_assumptions.clear()
        self.state.critical_assumptions.update(assumptions)
        self.state.assumption_families = dict(self.common_mode.families)
        for family in self.common_mode.risk_families()[
            : self.control_config.common_mode.max_challengers_per_round
        ]:
            self._emit(
                "common_mode_assumption_family_detected",
                family.model_dump(mode="json"),
            )
            action = self.action_dispatcher.propose(
                ControlActionType.CREATE_ASSUMPTION_CHALLENGER,
                source_record_ids=list(family.member_assumption_ids),
                route_ids=list(family.route_ids),
                payload={"family_id": family.family_id},
                current_round=current_round,
            )
            self.action_dispatcher.execute_sync(
                action.action_id,
                current_round=current_round,
            )

        for route in self.route_registry.routes:
            self._update_core_debt(route.route_id)
        for contract_id in self.message_utility.expire_contracts(current_round):
            self._emit(
                "message_utility_contract_expired",
                {"contract_id": contract_id, "round": current_round},
            )
        self.schedule_pending_route_updates(current_round=current_round)
        self.persist()

    def route_signals(self, route_id: str) -> dict[str, object]:
        history = self.state.core_debt_history.get(route_id, [])
        debt = history[-1] if history else self._update_core_debt(route_id)
        reduction = max(0.0, history[-2] - history[-1]) if len(history) >= 2 else 0.0
        core_items = [
            item
            for item in self.proof_graph.obligations_in_core_closure(route_id=route_id)
            if self._eligible_mathematical_obligation(item)
        ]
        closed_core = [item for item in core_items if item.status == "closed"]
        route = self.route_registry.get(route_id)
        admission = self.state.route_admissions.get(route.strategy_id)
        common_risk = max(
            (
                item.common_mode_risk
                for item in self.state.critical_assumptions.values()
                if route_id in item.route_ids
                and item.verification_status != ClaimStatus.VERIFIED
            ),
            default=0.0,
        )
        return {
            "core_proof_debt": debt,
            "core_proof_debt_reduction": reduction,
            "core_open_obligation_count": sum(
                item.status in {"open", "tentative", "blocked"} for item in core_items
            ),
            "core_verified_bridge_gain": float(len(closed_core)),
            "goal_alignment_score": (
                admission.alignment_score if admission is not None else 0.5
            ),
            "common_mode_risk": common_risk,
            "message_utility": self.message_utility.route_utility(route_id),
        }

    def route_hints(self, route_id: str) -> dict[str, Any]:
        target_ids = [
            item.obligation_id
            for item in self.proof_graph.obligations_in_core_closure(
                route_id=route_id,
                open_only=True,
            )
            if self._eligible_mathematical_obligation(item)
        ]
        route = self.route_registry.get(route_id)
        return {
            "authority": "non_authoritative_control_hints",
            "near_miss_repairs": [
                item.model_dump(mode="json")
                for item in self.near_misses.relevant_for_route(
                    route_id,
                    target_obligation_ids=target_ids,
                )
            ],
            "bottleneck_clusters": [
                item.model_dump(mode="json")
                for item in self.state.bottleneck_clusters.values()
                if route_id in item.route_ids and item.status == "active"
            ],
            "critical_assumptions": [
                item.model_dump(mode="json")
                for item in self.state.critical_assumptions.values()
                if route_id in item.route_ids
                and item.verification_status != ClaimStatus.VERIFIED
            ],
            "active_induction_schemes": [
                {
                    **item.model_dump(mode="json"),
                    "authority": "proof_plan_not_fact",
                }
                for item in self.state.induction_blueprints.values()
                if item.route_id == route_id and item.status == "active"
            ],
            "falsification_tasks": [
                item.model_dump(mode="json")
                for item in self.state.falsification_tasks.values()
                if item.route_id in {None, route_id}
                and item.status in {"admitted", "deferred", "running"}
            ],
            "core_proof_debt": self.route_signals(route_id)["core_proof_debt"],
            "active_goal_link": (
                link.model_dump(mode="json")
                if (link := self._goal_link_for_subject(route.strategy_id)) is not None
                else None
            ),
        }

    def admit_routes(
        self, strategies: Sequence[StrategyCard]
    ) -> tuple[list[StrategyCard], list[RouteAdmissionRecord]]:
        if not self.control_config.enabled or self.control_config.mode == "off":
            return list(strategies), []
        admitted: list[StrategyCard] = []
        records: list[RouteAdmissionRecord] = []
        existing_signatures = [
            route.mechanism_signature for route in self.route_registry.routes
        ]
        for obligation in self.proof_graph.obligations:
            self._ensure_obligation_domain(obligation)
        core_ids = {
            obligation_id
            for obligation_id in self.proof_graph.core_dependency_closure()
            if self.state.obligation_domains[obligation_id].domain
            == ObligationDomain.MATHEMATICAL
            and (
                obligation_id not in self.state.obligation_semantic_quality
                or self.state.obligation_semantic_quality[
                    obligation_id
                ].eligible_for_core_debt
            )
        }
        obligations = {
            item.obligation_id: item
            for item in self.proof_graph.obligations
            if self.state.obligation_domains[item.obligation_id].domain
            == ObligationDomain.MATHEMATICAL
            and (
                item.obligation_id not in self.state.obligation_semantic_quality
                or self.state.obligation_semantic_quality[
                    item.obligation_id
                ].eligible_for_core_debt
            )
        }
        for strategy in strategies:
            compilation = self.compile_strategy_blueprint(strategy)
            binding = None
            if compilation is not None and compilation.blueprint.status == "accepted":
                try:
                    binding = self._binding_from_blueprint(strategy, compilation)
                except ValueError as exc:
                    compilation.blueprint.status = "needs_review"
                    compilation.review_reasons = list(
                        dict.fromkeys([*compilation.review_reasons, str(exc)])
                    )
            link = (
                self.register_strategy(strategy, blueprint_binding=binding)
                if binding is not None
                else None
            )
            obligations = {
                item.obligation_id: item
                for item in self.proof_graph.obligations
                if self.state.obligation_domains[item.obligation_id].domain
                == ObligationDomain.MATHEMATICAL
                and (
                    item.obligation_id not in self.state.obligation_semantic_quality
                    or self.state.obligation_semantic_quality[
                        item.obligation_id
                    ].eligible_for_core_debt
                )
            }
            if compilation is None or compilation.blueprint.status != "accepted":
                record = RouteAdmissionRecord(
                    strategy_id=strategy.strategy_id,
                    verdict=(
                        GateVerdict.BLOCK if self.active else GateVerdict.SHADOW_BLOCK
                    ),
                    alignment_score=0.0,
                    target_obligation_ids=[],
                    reasons=(
                        compilation.review_reasons
                        if compilation is not None and compilation.review_reasons
                        else ["strategy blueprint is unavailable or invalid"]
                    ),
                )
                if self.active:
                    self._retract_blocked_blueprint(
                        strategy,
                        compilation,
                        reason="blueprint not admissible",
                    )
            elif link is None:
                record = RouteAdmissionRecord(
                    strategy_id=strategy.strategy_id,
                    verdict=(
                        GateVerdict.BLOCK if self.active else GateVerdict.SHADOW_BLOCK
                    ),
                    alignment_score=0.0,
                    target_obligation_ids=[],
                    reasons=["blueprint direct target could not be aligned"],
                )
                if self.active:
                    self._retract_blocked_blueprint(
                        strategy,
                        compilation,
                        reason="direct target alignment failed",
                    )
            else:
                binding = binding or self._route_target_binding_for_strategy(
                    strategy.strategy_id
                )
                contract = self._alignment_contract_for_subject(strategy.strategy_id)
                record = self.route_admission_gate.evaluate(
                    strategy,
                    goal_link=link,
                    target_obligations=obligations,
                    core_obligation_ids=core_ids,
                    existing_mechanism_signatures=existing_signatures,
                    critical_assumptions=list(self.state.critical_assumptions.values()),
                    expected_core_obligation_reduction=(
                        (
                            (
                                binding.direct_target_obligation_id in core_ids
                                or binding.blueprint_path_complete
                            )
                            if binding is not None
                            else link.target_obligation_id in core_ids
                        )
                        and bool(strategy.expected_lemmas)
                    ),
                    target_binding=binding,
                    alignment_contract=contract,
                )
                if record.verdict == GateVerdict.REWRITE:
                    self._ensure_route_admission_rewrite(
                        strategy,
                        link=link,
                        binding=binding,
                        contract=contract,
                        record=record,
                    )
            self.state.route_admissions[strategy.strategy_id] = record
            records.append(record)
            self._emit(
                "route_admission_evaluated",
                record.model_dump(mode="json"),
            )
            if record.verdict in {GateVerdict.BLOCK, GateVerdict.REWRITE}:
                self._emit(
                    "route_admission_blocked",
                    record.model_dump(mode="json"),
                )
                if self.active:
                    self._retract_blocked_blueprint(
                        strategy,
                        compilation,
                        reason=f"route admission {record.verdict.value}",
                    )
            elif record.verdict == GateVerdict.SHADOW_BLOCK:
                self._emit(
                    "route_admission_shadow_blocked",
                    record.model_dump(mode="json"),
                )
            if record.verdict in {GateVerdict.PASS, GateVerdict.SHADOW_BLOCK}:
                if (
                    compilation is not None
                    and compilation.blueprint.status == "accepted"
                ):
                    self._activate_blueprint(strategy, compilation)
                admitted.append(strategy)
                existing_signatures.append(strategy.tags)
        self.persist()
        return admitted, records

    def normalization_backlog(
        self,
        strategies: Sequence[StrategyCard],
    ) -> list[dict[str, Any]]:
        """Statements the deterministic gate marked NEEDS_NORMALIZATION.

        These are believed-mathematical statements whose objects, relation,
        or quantifier/scope could not be extracted; a batched reviewer can
        restate them without touching the mathematics.
        """
        backlog: list[dict[str, Any]] = []
        seen: set[str] = set()
        for strategy in strategies:
            blueprint = self.state.strategy_blueprints.get(strategy.strategy_id)
            if blueprint is None:
                continue
            for node_id in blueprint.node_ids:
                quality = self.state.obligation_semantic_quality.get(node_id)
                if quality is None or not quality.needs_normalization:
                    continue
                node = self.state.blueprint_nodes.get(node_id)
                if node is None:
                    continue
                key = node.statement.strip()
                if not key or key in seen:
                    continue
                seen.add(key)
                backlog.append(
                    {
                        "statement": key,
                        "needs": list(quality.normalization_needs),
                        "strategy_id": strategy.strategy_id,
                        "node_id": node_id,
                    }
                )
        return backlog

    @staticmethod
    def apply_normalized_statements(
        strategies: Sequence[StrategyCard],
        replacements: dict[str, str],
    ) -> list[StrategyCard]:
        """Rewrite strategy statements with reviewer-normalized forms.

        Replacement is by exact original text; anything the reviewer marked
        non-mathematical or left unchanged is not touched.
        """

        def swap(text: str) -> str:
            replacement = replacements.get(text.strip())
            return replacement if replacement else text

        updated: list[StrategyCard] = []
        for strategy in strategies:
            updated.append(
                strategy.model_copy(
                    update={
                        "expected_lemmas": [
                            swap(item) for item in strategy.expected_lemmas
                        ],
                        "bottleneck": swap(strategy.bottleneck),
                        "critical_claims": [
                            claim.model_copy(
                                update={"statement": swap(claim.statement)}
                            )
                            for claim in strategy.critical_claims
                        ],
                    }
                )
            )
        return updated

    def allow_deepen(
        self,
        *,
        route_id: str,
        segment_index: int,
        report: VerificationReport | None,
        core_obligation_closed: bool,
        core_debt_reduced: bool,
        verified_bridge_gain: bool,
    ) -> bool:
        record = self.continue_gate.evaluate(
            route_id=route_id,
            segment_index=segment_index,
            core_obligation_closed=core_obligation_closed,
            core_debt_reduced=core_debt_reduced,
            verified_bridge_gain=verified_bridge_gain,
            first_error_fingerprint=(
                self._failure_fingerprint(report) if report is not None else None
            ),
        )
        self.state.continue_gate_records.append(record)
        if record.verdict in {GateVerdict.BLOCK, GateVerdict.SHADOW_BLOCK}:
            self._emit(
                (
                    "continue_gate_blocked"
                    if record.verdict == GateVerdict.BLOCK
                    else "continue_gate_shadow_blocked"
                ),
                record.model_dump(mode="json"),
            )
            if record.verdict == GateVerdict.BLOCK:
                self._request_blueprint_rewrite_after_stagnation(route_id)
        self.persist()
        return record.verdict != GateVerdict.BLOCK

    def deepening_currently_allowed(self, route_id: str) -> bool:
        latest = next(
            (
                item
                for item in reversed(self.state.continue_gate_records)
                if item.route_id == route_id
            ),
            None,
        )
        return latest is None or latest.verdict != GateVerdict.BLOCK

    def synthesis_readiness(
        self,
        *,
        conflicts: Sequence[ContradictionRecord] = (),
        candidate_subject_ids: Collection[str] = (),
        candidate_dependency_ids: Sequence[str] = (),
        candidate_fact_ids: Sequence[str] = (),
        candidate_proof_verified: bool = False,
        candidate_verified_subject_ids: Collection[str] = (),
    ) -> SynthesisReadinessRecord:
        subject_ids = set(candidate_subject_ids)
        links = [
            item
            for item in self.state.goal_links.values()
            if not subject_ids or item.subject_id in subject_ids
        ]
        admitted_fact_ids = [
            item.message_id for item in self.message_broker.admitted_facts()
        ]
        record = self.readiness_gate.evaluate(
            self.proof_graph,
            inference_risks=list(self.state.inference_risks.values()),
            goal_links=links,
            conflicts=conflicts,
            critical_assumptions=list(self.state.critical_assumptions.values()),
            candidate_dependency_ids=candidate_dependency_ids,
            candidate_fact_ids=candidate_fact_ids,
            candidate_proof_verified=candidate_proof_verified,
            candidate_verified_subject_ids=candidate_verified_subject_ids,
            broker_admitted_fact_ids=admitted_fact_ids,
            obligation_domains=self.state.obligation_domains,
            obligation_semantic_quality=self.state.obligation_semantic_quality,
        )
        self.state.synthesis_readiness_records.append(record)
        if record.verdict in {GateVerdict.BLOCK, GateVerdict.SHADOW_BLOCK}:
            self._emit(
                (
                    "synthesis_readiness_blocked"
                    if record.verdict == GateVerdict.BLOCK
                    else "synthesis_readiness_shadow_blocked"
                ),
                record.model_dump(mode="json"),
            )
        self.persist()
        return record

    def record_message_usage(self, message_id: str, *, consumer_route_id: str) -> None:
        trusted = self.message_broker.utility_record(message_id, consumer_route_id)
        if trusted is None:
            return
        contract = self.message_utility.contract_for_message(message_id)
        if contract is None:
            return
        receipt = self.message_utility.record_usage(
            message_id=message_id,
            consumer_route_id=consumer_route_id,
            referenced_step_ids=trusted.get("referenced_step_ids", []),
            closed_obligation_ids=trusted.get("closed_obligation_ids", []),
            refuted_claim_ids=trusted.get("refuted_claim_ids", []),
            produced_message_ids=trusted.get("produced_message_ids", []),
            blueprint_rewrite_request_ids=trusted.get(
                "blueprint_rewrite_request_ids", []
            ),
            cited_by_final_proof=bool(trusted.get("cited_by_final_proof", False)),
            verified_step_ids=trusted.get("referenced_step_ids", []),
            actually_closed_obligation_ids=trusted.get("closed_obligation_ids", []),
            actually_refuted_claim_ids=trusted.get("refuted_claim_ids", []),
            verified_produced_message_ids=trusted.get("produced_message_ids", []),
            executed_blueprint_rewrite_ids=trusted.get(
                "blueprint_rewrite_request_ids", []
            ),
        )
        self.state.usage_receipts[receipt.usage_receipt_id] = receipt
        self._emit("message_usage_verified", receipt.model_dump(mode="json"))
        self.persist()

    def materialize_strategy_falsification(
        self,
        strategy: StrategyCard,
        *,
        target_obligation_id: str,
        route_id: str | None,
        current_round: int,
        target_claim_id: str | None = None,
        authority_source_id: str | None = None,
    ) -> ControlActionRecord:
        target = self.proof_graph.get_obligation(target_obligation_id)
        target_claim = target.normalized_statement
        if (
            target_claim_id is not None
            and target_claim_id in self.claim_lifecycle.claims
        ):
            target_claim = self.claim_lifecycle.claims[target_claim_id].statement
        task = self.falsification_tasks.from_strategy(
            strategy,
            target_claim=target_claim,
            target_obligation_id=target_obligation_id,
            target_claim_id=target_claim_id,
            route_id=route_id,
        )
        contract = self.falsification_contracts.compile(
            strategy.falsification_test,
            target_subject_id=target_claim_id or target_obligation_id,
            max_cases=(self.control_config.falsification_fast_lane.max_cases),
        )
        self.state.typed_falsification_contracts[contract.contract_id] = contract
        executable_task = self.executable_task_controller.create_falsification_task(
            contract,
            target_claim_ids=([target_claim_id] if target_claim_id is not None else []),
            target_obligation_ids=[target_obligation_id],
            route_ids=[route_id] if route_id is not None else [],
            created_round=current_round,
            counterexample_hunter_agent_id=self._counterexample_hunter_agent_id(),
        )
        self._mark_task_routes_waiting(executable_task, current_round=current_round)
        task.typed_contract_id = contract.contract_id
        task.executable_task_id = executable_task.task_id
        existing = self.state.falsification_tasks.get(task.task_id)
        if existing is not None:
            task = existing
            if task.typed_contract_id is None:
                task.typed_contract_id = contract.contract_id
            if task.executable_task_id is None:
                task.executable_task_id = executable_task.task_id
        else:
            self.state.falsification_tasks[task.task_id] = task
        action = self.action_dispatcher.propose(
            ControlActionType.MATERIALIZE_FALSIFICATION_TASK,
            source_record_ids=[authority_source_id or task.source_record_id],
            route_ids=[route_id] if route_id is not None else [],
            target_obligation_ids=[target_obligation_id],
            payload={"task_id": task.task_id},
            current_round=current_round,
        )
        task.action_id = action.action_id
        return self.action_dispatcher.execute_sync(
            action.action_id,
            current_round=current_round,
        )

    def pending_falsification_specs(
        self,
        strategy_id: str,
        *,
        route_id: str | None = None,
    ) -> list[FalsificationTaskRecord]:
        pending = [
            task
            for task in self.state.falsification_tasks.values()
            if task.strategy_id == strategy_id
            and task.status == "admitted"
            and task.result_experiment_id is None
            and task.experiment_spec is not None
        ]
        for task in pending:
            if task.route_id is None and route_id is not None:
                task.route_id = route_id
        return sorted(pending, key=lambda item: item.task_id)

    def mark_falsification_running(self, task_id: str) -> None:
        task = self.state.falsification_tasks[task_id]
        if task.status == "admitted":
            task.status = "running"
            if task.executable_task_id is not None:
                executable = self.state.executable_tasks[task.executable_task_id]
                self.executable_task_controller.mark_running(
                    executable.task_id,
                    current_round=executable.last_transition_round,
                )
            self.persist()

    def record_falsification_result(
        self,
        result: ExperimentResult,
        *,
        task_id: str | None = None,
    ) -> None:
        self.state.fast_lane_outcomes[result.experiment_id] = result.outcome.value
        task = (
            self.state.falsification_tasks.get(task_id)
            if task_id is not None
            else next(
                (
                    item
                    for item in self.state.falsification_tasks.values()
                    if item.experiment_spec is not None
                    and item.experiment_spec.experiment_id == result.experiment_id
                ),
                None,
            )
        )
        if (
            task is not None
            and task.experiment_spec is not None
            and task.experiment_spec.experiment_id != result.experiment_id
        ):
            raise ValueError(
                "falsification result does not match the materialized task"
            )
        disposition = classify_falsification_result(result)
        if task is not None:
            task.result_experiment_id = result.experiment_id
            executable = (
                self.state.executable_tasks.get(task.executable_task_id)
                if task.executable_task_id is not None
                else None
            )
            if executable is not None and executable.status in {
                TaskStatus.ASSIGNED,
                TaskStatus.READY,
            }:
                self.executable_task_controller.mark_running(
                    executable.task_id,
                    current_round=executable.last_transition_round,
                )
            if disposition.conclusive_refutation:
                task.status = "counterexample_found"
                if (
                    task.target_claim_id is not None
                    and task.target_claim_id in self.claim_lifecycle.claims
                ):
                    self.claim_lifecycle.invalidate_claim(
                        task.target_claim_id,
                        reason="exact_counterexample",
                        evidence_ids=[result.experiment_id],
                    )
            elif result.outcome in {
                ExperimentOutcome.NOT_REFUTED,
                ExperimentOutcome.CERTIFIED,
            }:
                task.status = "not_refuted"
            else:
                task.status = "failed"
            if executable is not None and executable.status == TaskStatus.RUNNING:
                if task.status == "failed":
                    self.executable_task_controller.fail(
                        executable.task_id,
                        current_round=executable.last_transition_round,
                        reason=disposition.reason,
                    )
                else:
                    self.executable_task_controller.complete(
                        executable.task_id,
                        current_round=executable.last_transition_round,
                        result_refs=[result.experiment_id],
                        counterexample_found=disposition.conclusive_refutation,
                    )
        self._emit(
            disposition.event_type,
            {
                "experiment_id": result.experiment_id,
                "task_id": task.task_id if task is not None else None,
                "outcome": result.outcome.value,
                "conclusive_refutation": disposition.conclusive_refutation,
                "reason": disposition.reason,
            },
        )
        self.persist()

    def record_falsification_decision(
        self,
        task_id: str,
        *,
        decision: str,
        reason: str,
    ) -> None:
        task = self.state.falsification_tasks[task_id]
        task.status = "deferred"
        task.deferred_reason = reason
        if task.executable_task_id is not None:
            executable = self.state.executable_tasks[task.executable_task_id]
            if executable.status not in ExecutableTaskController.TERMINAL_STATUSES:
                self.executable_task_controller.defer(
                    executable.task_id,
                    current_round=executable.last_transition_round,
                    reason=reason,
                    wake_kind=(
                        WakeConditionKind.BUDGET_AVAILABLE
                        if "budget" in decision.casefold()
                        else WakeConditionKind.PROVIDER_AVAILABLE
                    ),
                )
                self._mark_task_routes_waiting(
                    executable,
                    current_round=executable.last_transition_round,
                )
        self._emit(
            "falsification_task_deferred",
            {
                "task_id": task_id,
                "decision": decision,
                "reason": reason,
            },
        )
        self.persist()

    def record_falsification_execution_failure(
        self,
        task_id: str,
        *,
        error: BaseException,
    ) -> None:
        task = self.state.falsification_tasks[task_id]
        task.status = "failed"
        task.deferred_reason = f"{type(error).__name__}: {error}"
        if task.executable_task_id is not None:
            executable = self.state.executable_tasks[task.executable_task_id]
            if executable.status not in ExecutableTaskController.TERMINAL_STATUSES:
                self.executable_task_controller.fail(
                    executable.task_id,
                    current_round=executable.last_transition_round,
                    reason=task.deferred_reason,
                )
        self._emit(
            "falsification_task_failed",
            {
                "task_id": task_id,
                "decision": "execution_error",
                "reason": task.deferred_reason,
            },
        )
        self.persist()

    def materialize_bottleneck_cluster(
        self,
        cluster: BottleneckCluster,
        *,
        current_round: int,
    ) -> ControlActionRecord:
        action = self.action_dispatcher.propose(
            ControlActionType.MATERIALIZE_BOTTLENECK_CLUSTER,
            source_record_ids=list(cluster.member_obligation_ids),
            route_ids=list(cluster.route_ids),
            target_obligation_ids=list(cluster.member_obligation_ids),
            payload={"cluster": cluster.model_dump(mode="json")},
            current_round=current_round,
        )
        return self.action_dispatcher.execute_sync(
            action.action_id,
            current_round=current_round,
        )

    def review_induction_measure(
        self,
        proposal_id: str,
        *,
        reviewer_agent_id: str,
        approved: bool,
        review_evidence_ids: Sequence[str],
        current_round: int,
        rejection_reason: str = "",
    ) -> ControlActionRecord:
        proposal = self.state.induction_measures[proposal_id]
        if not approved:
            proposal.status = "rejected"
            proposal.reviewer_agent_id = reviewer_agent_id or None
            proposal.review_evidence_ids = list(dict.fromkeys(review_evidence_ids))
            proposal.rejection_reason = (
                rejection_reason.strip() or "independent review rejected the measure"
            )
            self._emit(
                "induction_measure_rejected",
                proposal.model_dump(mode="json"),
            )
            self.persist()
            action = self.action_dispatcher.propose(
                ControlActionType.ACTIVATE_INDUCTION_MEASURE,
                source_record_ids=[proposal.proposal_id],
                route_ids=[proposal.route_id],
                target_obligation_ids=proposal.target_obligation_ids,
                payload={
                    "proposal_id": proposal.proposal_id,
                    "reviewer_agent_id": reviewer_agent_id,
                    "review_evidence_ids": list(review_evidence_ids),
                    "approved": False,
                },
                current_round=current_round,
            )
            return self.action_dispatcher.defer(
                action.action_id,
                reason=proposal.rejection_reason,
            )
        if not reviewer_agent_id.strip():
            raise ValueError("induction activation requires an independent reviewer")
        if (
            proposal.source_agent_id is not None
            and reviewer_agent_id == proposal.source_agent_id
        ):
            raise ValueError(
                "induction activation reviewer must be independent of the proposer"
            )
        evidence = list(dict.fromkeys(review_evidence_ids))
        if not evidence:
            raise ValueError("induction activation requires review evidence")
        if not self.induction.validate_well_foundedness(proposal):
            raise ValueError("induction measure is not explicitly well founded")
        action = self.action_dispatcher.propose(
            ControlActionType.ACTIVATE_INDUCTION_MEASURE,
            source_record_ids=[proposal.proposal_id],
            route_ids=[proposal.route_id],
            target_obligation_ids=proposal.target_obligation_ids,
            payload={
                "proposal_id": proposal.proposal_id,
                "reviewer_agent_id": reviewer_agent_id,
                "review_evidence_ids": evidence,
                "approved": True,
            },
            current_round=current_round,
        )
        return self.action_dispatcher.execute_sync(
            action.action_id,
            current_round=current_round,
        )

    def materialize_minimal_bridge(
        self,
        proposal: MinimalBridgeProposal,
        *,
        route_id: str | None,
        binding_id: str | None,
        current_round: int,
    ) -> ControlActionRecord:
        stored = self.state.minimal_bridge_proposals.get(proposal.proposal_id)
        if stored is None:
            self.state.minimal_bridge_proposals[proposal.proposal_id] = proposal
            stored = proposal
        action = self.action_dispatcher.propose(
            ControlActionType.CREATE_MINIMAL_BRIDGE,
            source_record_ids=[stored.proposal_id],
            route_ids=[route_id] if route_id is not None else [],
            target_obligation_ids=[stored.target_obligation_id],
            payload={
                "proposal_id": stored.proposal_id,
                "binding_id": binding_id,
            },
            current_round=current_round,
        )
        stored.action_id = action.action_id
        return self.action_dispatcher.execute_sync(
            action.action_id,
            current_round=current_round,
        )

    def dispatch_blueprint_rewrite(
        self,
        request_id: str,
        *,
        strategy_id: str,
        binding_id: str | None,
        current_round: int,
    ) -> ControlActionRecord:
        request = self.state.blueprint_rewrites[request_id]
        route_ids = (
            [request.route_id] if self._control_route_exists(request.route_id) else []
        )
        target_ids = [
            target_id
            for target_id in [
                *request.proposed_weaker_targets,
                *request.proposed_bridge_obligation_ids,
            ]
            if self._control_obligation_exists(target_id)
        ]
        if binding_id is not None:
            binding = self.state.route_target_bindings[binding_id]
            target_ids.extend(
                [
                    binding.direct_target_obligation_id,
                    binding.main_goal_obligation_id,
                ]
            )
        action = self.action_dispatcher.propose(
            ControlActionType.REWRITE_BLUEPRINT,
            source_record_ids=[request.request_id],
            route_ids=route_ids,
            target_obligation_ids=list(dict.fromkeys(target_ids)),
            payload={
                "blueprint_rewrite_request_id": request.request_id,
                "strategy_id": strategy_id,
                "binding_id": binding_id,
            },
            current_round=current_round,
        )
        request.execution_action_id = action.action_id
        return self.action_dispatcher.execute_sync(
            action.action_id,
            current_round=current_round,
        )

    def schedule_pending_route_updates(
        self,
        *,
        current_round: int,
    ) -> list[ControlActionRecord]:
        """Reserve one explicit processing turn per route with queued high-value work."""

        pending_by_route: dict[str, list[dict[str, Any]]] = {}
        for delivery in self.message_broker.deliveries_requiring_route_update():
            route_id = str(delivery["target_route_id"])
            pending_by_route.setdefault(route_id, []).append(delivery)

        actions: list[ControlActionRecord] = []
        for route_id, deliveries in sorted(pending_by_route.items()):
            selected = deliveries[:1]
            message_ids = [str(item["message_id"]) for item in selected]
            priority = str(selected[0]["priority"])
            action = self.action_dispatcher.propose(
                ControlActionType.SCHEDULE_ROUTE_UPDATE,
                source_record_ids=message_ids,
                route_ids=[route_id],
                payload={
                    "target_route_id": route_id,
                    "message_ids": message_ids,
                    "priority": priority,
                    "scheduled_round": current_round,
                },
                current_round=current_round,
            )
            actions.append(
                self.action_dispatcher.execute_sync(
                    action.action_id,
                    current_round=current_round,
                )
            )
        return actions

    def pending_route_update_tasks(self) -> list[RouteUpdateTask]:
        for task in self.state.route_update_tasks.values():
            if task.status != "deferred" or task.executable_task_id is None:
                continue
            executable = self.state.executable_tasks.get(task.executable_task_id)
            if executable is not None and executable.status == TaskStatus.READY:
                task.status = "scheduled"
                task.failure_reason = ""
        return sorted(
            (
                task
                for task in self.state.route_update_tasks.values()
                if task.status in {"scheduled", "presented"}
            ),
            key=lambda item: (
                item.scheduled_round,
                item.target_route_id,
                item.task_id,
            ),
        )

    def mark_route_update_presented(
        self,
        task_id: str,
        *,
        current_round: int | None = None,
    ) -> RouteUpdateTask:
        task = self.state.route_update_tasks[task_id]
        if task.status == "scheduled":
            task.status = "presented"
            if task.executable_task_id is not None:
                executable = self.state.executable_tasks[task.executable_task_id]
                if executable.status in {TaskStatus.ASSIGNED, TaskStatus.READY}:
                    self.executable_task_controller.mark_running(
                        executable.task_id,
                        current_round=(
                            current_round
                            if current_round is not None
                            else task.scheduled_round
                        ),
                    )
            self._emit(
                "route_update_presented",
                task.model_dump(mode="json"),
            )
            self.persist()
        return task

    def complete_route_update_task(
        self,
        task_id: str,
        *,
        receipt_ids: Sequence[str],
        used: bool = False,
        current_round: int | None = None,
    ) -> RouteUpdateTask:
        task = self.state.route_update_tasks[task_id]
        receipts = list(dict.fromkeys(receipt_ids))
        if not receipts:
            raise ValueError("route update completion requires a semantic receipt")
        task.receipt_ids = receipts
        task.status = "used" if used else "acknowledged"
        task.failure_reason = ""
        if task.executable_task_id is not None:
            executable = self.state.executable_tasks[task.executable_task_id]
            if executable.status not in ExecutableTaskController.TERMINAL_STATUSES:
                self.executable_task_controller.complete_work(
                    executable.task_id,
                    current_round=(
                        current_round
                        if current_round is not None
                        else task.scheduled_round
                    ),
                    result_refs=receipts,
                    reason="route_update_acknowledged",
                )
        self._emit(
            "route_update_completed",
            task.model_dump(mode="json"),
        )
        self.persist()
        return task

    def fail_route_update_task(
        self,
        task_id: str,
        *,
        reason: str,
        current_round: int | None = None,
    ) -> RouteUpdateTask:
        task = self.state.route_update_tasks[task_id]
        task.status = "failed"
        task.failure_reason = reason.strip() or "route update failed"
        if task.executable_task_id is not None:
            executable = self.state.executable_tasks[task.executable_task_id]
            if executable.status not in ExecutableTaskController.TERMINAL_STATUSES:
                self.executable_task_controller.fail(
                    executable.task_id,
                    current_round=(
                        current_round
                        if current_round is not None
                        else task.scheduled_round
                    ),
                    reason=task.failure_reason,
                )
        self._emit(
            "route_update_failed",
            task.model_dump(mode="json"),
        )
        self.persist()
        return task

    def defer_route_update_task(
        self,
        task_id: str,
        *,
        reason: str,
        current_round: int,
        wake_kind: WakeConditionKind,
    ) -> RouteUpdateTask:
        task = self.state.route_update_tasks[task_id]
        if task.executable_task_id is None:
            raise ValueError("route update has no executable task")
        executable = self.executable_task_controller.defer(
            task.executable_task_id,
            current_round=current_round,
            reason=reason,
            wake_kind=wake_kind,
        )
        task.status = "deferred"
        task.failure_reason = reason.strip() or "route update deferred"
        self._mark_task_routes_waiting(executable, current_round=current_round)
        self._emit(
            "route_update_deferred",
            task.model_dump(mode="json"),
        )
        self.persist()
        return task

    def defer_inspiration_review(
        self,
        *,
        proposal_id: str,
        task_id: str | None,
        reason: str,
        current_round: int,
    ) -> ControlActionRecord:
        reason = reason.strip()
        if not reason:
            raise ValueError("inspiration review deferral requires a reason")
        deferral_id = (
            "inspiration_review_"
            + stable_hash(
                {
                    "problem_hash": self.proof_graph.problem_hash,
                    "proposal_id": proposal_id,
                    "task_id": task_id,
                }
            )[:16]
        )
        record = self.state.inspiration_review_deferrals.get(deferral_id)
        if record is None:
            record = InspirationReviewDeferral(
                deferral_id=deferral_id,
                proposal_id=proposal_id,
                task_id=task_id,
                reason=reason,
            )
            self.state.inspiration_review_deferrals[deferral_id] = record
        elif not record.reviewed:
            record.reason = reason
        action = self.action_dispatcher.propose(
            ControlActionType.DEFER_INSPIRATION_REVIEW,
            source_record_ids=[record.deferral_id],
            payload={
                "deferral_id": record.deferral_id,
                "proposal_id": proposal_id,
                "reason": reason,
            },
            current_round=current_round,
        )
        record.defer_action_id = action.action_id
        return self.action_dispatcher.execute_sync(
            action.action_id,
            current_round=current_round,
        )

    def complete_inspiration_review(
        self,
        *,
        proposal_id: str,
        reviewer_agent_id: str,
    ) -> InspirationReviewDeferral | None:
        record = next(
            (
                item
                for item in reversed(
                    list(self.state.inspiration_review_deferrals.values())
                )
                if item.proposal_id == proposal_id
            ),
            None,
        )
        if record is None:
            return None
        reviewer_agent_id = reviewer_agent_id.strip()
        if not reviewer_agent_id:
            raise ValueError("completed inspiration review requires a reviewer")
        if (
            record.assigned_reviewer_agent_id is not None
            and record.assigned_reviewer_agent_id != reviewer_agent_id
        ):
            raise ValueError("completed inspiration review used the wrong assignee")
        record.review_status = "completed"
        record.reviewed = True
        record.assigned_reviewer_agent_id = reviewer_agent_id
        self._emit(
            "inspiration_review_completed",
            record.model_dump(mode="json"),
        )
        self.persist()
        return record

    def reassign_inspiration_review(
        self,
        *,
        proposal_id: str,
        reviewer_agent_id: str,
        current_round: int,
    ) -> ControlActionRecord:
        reviewer_agent_id = reviewer_agent_id.strip()
        if not reviewer_agent_id:
            raise ValueError("inspiration review reassignment requires a reviewer")
        record = next(
            (
                item
                for item in reversed(
                    list(self.state.inspiration_review_deferrals.values())
                )
                if item.proposal_id == proposal_id
            ),
            None,
        )
        if record is None:
            raise ValueError("proposal has no deferred inspiration review")
        action = self.action_dispatcher.propose(
            ControlActionType.REASSIGN_INSPIRATION_REVIEW,
            source_record_ids=[record.deferral_id],
            payload={
                "deferral_id": record.deferral_id,
                "proposal_id": proposal_id,
                "reviewer_agent_id": reviewer_agent_id,
            },
            current_round=current_round,
        )
        record.reassign_action_id = action.action_id
        return self.action_dispatcher.execute_sync(
            action.action_id,
            current_round=current_round,
        )

    def inspiration_context(
        self,
        *,
        target_obligation_ids: Sequence[str] = (),
    ) -> dict[str, Any]:
        """Expose bounded mathematical control evidence, never protocol diagnostics."""

        targets = set(target_obligation_ids)

        def mathematical_target(obligation_id: str | None) -> bool:
            if obligation_id is None:
                return False
            if targets and obligation_id not in targets:
                return False
            try:
                obligation = self.proof_graph.get_obligation(obligation_id)
            except KeyError:
                return False
            return self._eligible_mathematical_obligation(obligation)

        mathematical_families = []
        for family in self.state.assumption_families.values():
            members = [
                self.state.critical_assumptions.get(assumption_id)
                for assumption_id in family.member_assumption_ids
            ]
            if not members or any(
                member is None or member.domain.value != "mathematical"
                for member in members
            ):
                continue
            mathematical_families.append(family.model_dump(mode="json"))

        return {
            "authority": "non_authoritative_control_context",
            "common_mode_assumption_families": mathematical_families[:8],
            "verified_near_miss_salvage": [
                {
                    "near_miss_id": item.near_miss_id,
                    "target_obligation_id": item.target_obligation_id,
                    "abstract_idea": item.abstract_idea,
                    "concrete_candidate": item.concrete_candidate,
                    "failed_constraints": list(item.failed_constraints),
                    "salvageable_components": list(item.salvageable_components),
                    "repair_module": item.repair_module,
                    "suggested_repair_operators": list(item.suggested_repair_operators),
                    "verifier_confidence": item.verifier_confidence,
                }
                for item in self.state.near_misses.values()
                if mathematical_target(item.target_obligation_id)
            ][:8],
            "active_induction_measures": [
                item.model_dump(mode="json")
                for item in self.state.induction_blueprints.values()
                if item.status == "active"
                and any(
                    mathematical_target(target_id)
                    for target_id in item.target_obligation_ids
                )
            ][:8],
            "minimal_bridge_requests": [
                item.model_dump(mode="json")
                for item in self.state.minimal_bridge_proposals.values()
                if item.status in {"candidate", "reviewed"}
                and mathematical_target(item.target_obligation_id)
            ][:8],
        }

    def request_meta_pivot(
        self,
        *,
        source_stagnation_signature: str,
        trigger_round: int,
        requested_mechanisms: Sequence[str],
    ) -> MetaPivotState:
        signature = source_stagnation_signature.strip()
        mechanisms = list(
            dict.fromkeys(
                mechanism.strip()
                for mechanism in requested_mechanisms
                if mechanism.strip()
            )
        )
        if not signature:
            raise ValueError("meta pivot requires a stagnation signature")
        if not mechanisms:
            raise ValueError("meta pivot requires at least one mechanism")
        existing = self.state.meta_pivot_state
        if existing is not None and existing.source_stagnation_signature == signature:
            return existing
        pivot = MetaPivotState(
            pivot_id=(
                "meta_pivot_"
                + stable_hash(
                    {
                        "problem_hash": self.proof_graph.problem_hash,
                        "source_stagnation_signature": signature,
                    }
                )[:16]
            ),
            status=MetaPivotStatus.REQUESTED,
            trigger_round=trigger_round,
            source_stagnation_signature=signature,
            requested_mechanisms=mechanisms,
        )
        self.state.meta_pivot_state = pivot
        action = self.action_dispatcher.propose(
            ControlActionType.EXECUTE_META_PIVOT,
            source_record_ids=[pivot.pivot_id],
            payload={
                "pivot_id": pivot.pivot_id,
                "source_stagnation_signature": signature,
                "requested_mechanisms": mechanisms,
            },
            current_round=trigger_round,
        )
        pivot.action_id = action.action_id
        self._emit(
            "meta_pivot_requested",
            pivot.model_dump(mode="json"),
        )
        self.persist()
        return pivot

    async def execute_pending_meta_pivot(
        self,
        *,
        current_round: int,
        executor: Callable[..., Any],
    ) -> MetaPivotState:
        pivot = self.state.meta_pivot_state
        if pivot is None:
            raise ValueError("no meta pivot has been requested")
        if pivot.status in {
            MetaPivotStatus.EVALUATED,
            MetaPivotStatus.FAILED,
        }:
            return pivot
        if pivot.action_id is None:
            raise ValueError("meta pivot has no dispatcher action")
        if pivot.status == MetaPivotStatus.EXECUTED:
            action = self.state.control_actions[pivot.action_id]
            if action.status == ControlActionStatus.EXECUTING:
                await self.action_dispatcher.execute(
                    pivot.action_id,
                    current_round=current_round,
                )
            return pivot
        action = self.action_dispatcher.admit(
            pivot.action_id,
            current_round=current_round,
        )
        if action.status == ControlActionStatus.ADMITTED:
            pivot.status = MetaPivotStatus.ADMITTED
            self._emit(
                "meta_pivot_admitted",
                pivot.model_dump(mode="json"),
            )
            self.persist()
        elif action.status in {
            ControlActionStatus.REJECTED,
            ControlActionStatus.DEFERRED,
            ControlActionStatus.FAILED,
        }:
            pivot.status = MetaPivotStatus.FAILED
            pivot.failure_reason = (
                action.failure_reason
                or action.admission_reason
                or "meta pivot action was not admitted"
            )
            self._emit(
                "meta_pivot_failed",
                pivot.model_dump(mode="json"),
            )
            self.persist()
            return pivot

        self._meta_pivot_executor = executor
        self._meta_pivot_execution_round = current_round
        try:
            await self.action_dispatcher.execute(
                pivot.action_id,
                current_round=current_round,
            )
        except Exception:
            return pivot
        finally:
            self._meta_pivot_executor = None
            self._meta_pivot_execution_round = 0
        return pivot

    def evaluate_meta_pivot(
        self,
        *,
        progress_signature: str,
        current_round: int,
    ) -> MetaPivotState:
        pivot = self.state.meta_pivot_state
        if pivot is None:
            raise ValueError("no meta pivot exists")
        if pivot.status == MetaPivotStatus.EVALUATED:
            return pivot
        if pivot.status != MetaPivotStatus.EXECUTED:
            raise ValueError("meta pivot must execute before evaluation")
        pivot.no_progress_after_pivot = (
            progress_signature == pivot.source_stagnation_signature
        )
        pivot.status = MetaPivotStatus.EVALUATED
        pivot.evaluated_round = current_round
        self._emit(
            "meta_pivot_evaluated",
            pivot.model_dump(mode="json"),
        )
        self.persist()
        return pivot

    def meta_pivot_blocks_stagnation_stop(self) -> bool:
        pivot = self.state.meta_pivot_state
        return pivot is not None and pivot.status in {
            MetaPivotStatus.REQUESTED,
            MetaPivotStatus.ADMITTED,
            MetaPivotStatus.EXECUTING,
            MetaPivotStatus.EXECUTED,
        }

    def meta_pivot_allows_stagnation_stop(
        self,
        *,
        progress_signature: str,
    ) -> bool:
        pivot = self.state.meta_pivot_state
        if pivot is None:
            return False
        if pivot.status == MetaPivotStatus.FAILED:
            return True
        return (
            pivot.status == MetaPivotStatus.EVALUATED
            and pivot.no_progress_after_pivot is True
            and pivot.source_stagnation_signature == progress_signature
        )

    def export_state(self) -> dict[str, Any]:
        return self.state.export_state()

    def persist(self) -> None:
        payload = self.export_state()
        self.store.write_json("structured", "proof_control", payload)
        self.store.write_json("reports", "proof_control_summary", self.summary())

    def summary(self) -> dict[str, Any]:
        links = list(self.state.goal_links.values())
        aligned_relations = {GoalRelation.EQUIVALENT, GoalRelation.SUFFICIENT}
        # A lemma being weaker than the main goal is the defining property of
        # a lemma, not an alignment failure; only INCOMPARABLE scope blocks.
        invalid_scopes = {ScopeRelation.INCOMPARABLE}
        alignment_pass = sum(
            item.relation in aligned_relations
            and item.scope_relation not in invalid_scopes
            for item in links
        )
        alignment_block = sum(
            item.relation
            in {
                GoalRelation.NECESSARY_ONLY,
                GoalRelation.HEURISTIC_ONLY,
                GoalRelation.UNRELATED,
            }
            or item.scope_relation in invalid_scopes
            for item in links
        )
        lemma_weaker_expected = sum(
            item.scope_relation == ScopeRelation.CLAIM_WEAKER for item in links
        )
        facts = self.message_broker.admitted_facts()
        core_fact_roles = {
            ProofRole.CORE_BRIDGE,
            ProofRole.SUFFICIENT_CONDITION,
            ProofRole.EQUIVALENT_REDUCTION,
        }
        core_fact_count = sum(
            self.state.proof_roles.get(item.message_id) in core_fact_roles
            for item in facts
        )
        scope_risk_types = {
            InferenceRiskType.EVENTUAL_TO_GLOBAL,
            InferenceRiskType.POINTWISE_TO_UNIFORM,
            InferenceRiskType.PROJECTION_TO_ORIGINAL,
            InferenceRiskType.LOCAL_TO_GLOBAL,
            InferenceRiskType.EXISTENCE_TO_UNIFORM_EXISTENCE,
            InferenceRiskType.PAIRWISE_TO_COMMON_WITNESS,
            *self._property_strengthening_risk_types(),
        }
        debt_auc_by_route = {
            route_id: self._history_auc(history)
            for route_id, history in sorted(self.state.core_debt_history.items())
        }
        clustered_savings = sum(
            max(0, len(set(item.member_obligation_ids)) - 1)
            for item in self.state.bottleneck_clusters.values()
            if item.status != "split"
        )
        obligation_count = len(self.proof_graph.obligations)
        broker_state = self.message_broker.export_state()
        successful_repairs = sum(
            any(
                candidate.structure_id == task.structure_id
                and candidate.candidate_id != task.failed_candidate_id
                and candidate.status == "verified"
                for candidate in self.state.realizer_candidates.values()
            )
            for task in self.state.realizer_repair_tasks.values()
        )
        fast_lane_total = len(self.state.fast_lane_outcomes)
        fast_lane_hits = sum(
            outcome == ExperimentOutcome.COUNTEREXAMPLE_FOUND.value
            for outcome in self.state.fast_lane_outcomes.values()
        )
        route_records = list(self.state.route_admissions.values())
        continue_records = self.state.continue_gate_records
        synthesis_records = self.state.synthesis_readiness_records
        return {
            "schema_version": self.state.schema_version,
            "mode": self.control_config.mode,
            "goal_links": len(links),
            "goal_alignment": {
                "pass": alignment_pass,
                "block": alignment_block,
                "ambiguous": max(0, len(links) - alignment_pass - alignment_block),
                "lemma_weaker_expected": lemma_weaker_expected,
            },
            "overstrong_targets": sum(
                item.scope_relation == ScopeRelation.CLAIM_STRONGER for item in links
            ),
            "fact_roles": {
                "core": core_fact_count,
                "auxiliary": max(0, len(facts) - core_fact_count),
            },
            "open_inference_risks": sum(
                item.status == "open" for item in self.state.inference_risks.values()
            ),
            "scope_risk_count": sum(
                item.status == "open" and item.risk_type in scope_risk_types
                for item in self.state.inference_risks.values()
            ),
            "countermodel_task_count": sum(
                item.countermodel_task_id is not None
                for item in self.state.inference_risks.values()
            ),
            "failure_classes": self._failure_distribution(),
            "blueprint_rewrites": len(self.state.blueprint_rewrites),
            "bottleneck_clusters": len(self.state.bottleneck_clusters),
            "bottleneck_compression_ratio": (
                clustered_savings / obligation_count if obligation_count else 0.0
            ),
            "common_mode_assumptions": sum(
                item.verification_status != ClaimStatus.VERIFIED
                and item.common_mode_risk
                >= self.control_config.common_mode.risk_threshold
                for item in self.state.critical_assumptions.values()
            ),
            "message_contracts": len(self.state.utility_contracts),
            "message_delivery": {
                "contracted": len(self.state.utility_contracts),
                "delivered": len(broker_state.get("deliveries", {})),
                "used": sum(
                    item.verified_use for item in self.state.usage_receipts.values()
                ),
            },
            "verified_message_uses": sum(
                item.verified_use for item in self.state.usage_receipts.values()
            ),
            "near_misses": len(self.state.near_misses),
            "near_miss_repair_success_rate": (
                successful_repairs / len(self.state.realizer_repair_tasks)
                if self.state.realizer_repair_tasks
                else 0.0
            ),
            "fast_lane_counterexample_hit_rate": (
                fast_lane_hits / fast_lane_total if fast_lane_total else 0.0
            ),
            "route_admission_records": len(self.state.route_admissions),
            "route_admission_rejection_rewrite_rate": (
                sum(
                    item.verdict in {GateVerdict.BLOCK, GateVerdict.REWRITE}
                    for item in route_records
                )
                / len(route_records)
                if route_records
                else 0.0
            ),
            "continue_gate_blocks": sum(
                item.verdict == GateVerdict.BLOCK for item in continue_records
            ),
            "continue_gate_block_rate": (
                sum(item.verdict == GateVerdict.BLOCK for item in continue_records)
                / len(continue_records)
                if continue_records
                else 0.0
            ),
            "synthesis_readiness_blocks": sum(
                item.verdict == GateVerdict.BLOCK for item in synthesis_records
            ),
            "synthesis_readiness_block_rate": (
                sum(item.verdict == GateVerdict.BLOCK for item in synthesis_records)
                / len(synthesis_records)
                if synthesis_records
                else 0.0
            ),
            "meta_pivot": (
                self.state.meta_pivot_state.model_dump(mode="json")
                if self.state.meta_pivot_state is not None
                else None
            ),
            "core_debt_history": self.state.core_debt_history,
            "core_proof_debt_auc": {
                "by_route": debt_auc_by_route,
                "total": sum(debt_auc_by_route.values()),
            },
        }

    @classmethod
    def from_state(
        cls,
        state: Mapping[str, Any] | None,
        *,
        config: SystemConfig,
        store: ArtifactStore,
        activity: ActivityStream | None,
        proof_graph: ProofGraphStore,
        typed_memory: TypedMemory,
        message_broker: MessageBroker,
        route_registry: RouteRegistry,
    ) -> ProofControlLayer:
        return cls(
            config,
            store,
            activity,
            proof_graph,
            typed_memory,
            message_broker,
            route_registry,
            state=ProofControlState.from_state(state),
        )

    def _register_goal_link(
        self,
        subject: StrategyCard | ClaimCard | MessageEnvelope | ProofObligation,
        link: ClaimGoalLink,
    ) -> ClaimGoalLink:
        if link.scope_relation == ScopeRelation.CLAIM_WEAKER:
            bridge_origin = self._weaker_bridge_origin(subject)
            if bridge_origin is None:
                link = self._materialize_weaker_claim_bridge(subject, link)
            else:
                self._emit(
                    "weaker_claim_bridge_recursion_blocked",
                    {
                        "subject_id": link.subject_id,
                        "target_obligation_id": link.target_obligation_id,
                        "origin_link_id": bridge_origin,
                        "reason": "subject_is_materialized_weaker_bridge",
                    },
                )
        self.state.goal_links[link.link_id] = link
        self._emit("goal_link_created", link.model_dump(mode="json"))
        if link.scope_relation == ScopeRelation.CLAIM_STRONGER:
            self._emit(
                "overstrong_target_detected",
                {
                    "subject_id": link.subject_id,
                    "target_obligation_id": link.target_obligation_id,
                },
            )
            self._emit(
                "minimal_bridge_requested",
                {
                    "request_id": (
                        "minimal_bridge_request_"
                        + stable_hash((link.subject_id, link.target_obligation_id))[:12]
                    ),
                    "overstrong_subject_id": link.subject_id,
                    "target_obligation_id": link.target_obligation_id,
                    "status": "pending_audited_candidate",
                },
            )
        if link.scope_relation == ScopeRelation.INCOMPARABLE:
            self._emit(
                "scope_mismatch_detected",
                link.model_dump(mode="json"),
            )
        role = self.role_classifier.classify(subject, link, self.proof_graph)
        self.state.proof_roles[link.subject_id] = role
        self._emit(
            "proof_role_assigned",
            {"subject_id": link.subject_id, "proof_role": role.value},
        )
        self._register_risks(self.risk_scanner.scan_goal_link(link))
        return link

    def _weaker_bridge_origin(
        self,
        subject: StrategyCard | ClaimCard | MessageEnvelope | ProofObligation,
    ) -> str | None:
        if not isinstance(subject, ProofObligation):
            return None
        obligation_id = subject.obligation_id
        for existing in self.state.goal_links.values():
            if obligation_id in existing.required_bridge_obligation_ids:
                return existing.link_id
        if obligation_id.startswith(_WEAKER_BRIDGE_ID_PREFIX):
            return "legacy_or_restored_bridge"
        return None

    def _materialize_weaker_claim_bridge(
        self,
        subject: StrategyCard | ClaimCard | MessageEnvelope | ProofObligation,
        link: ClaimGoalLink,
    ) -> ClaimGoalLink:
        """Represent the missing weaker-claim implication as open work.

        A weaker lemma is not a scope error. It is useful partial progress
        provided the remaining implication to the requested target stays
        explicit and cannot be mistaken for a closed obligation.
        """

        try:
            target = self.proof_graph.get_obligation(link.target_obligation_id)
        except KeyError:
            return link
        if isinstance(subject, StrategyCard):
            subject_statement = subject.bottleneck or subject.core_idea
        elif isinstance(subject, (ClaimCard, MessageEnvelope)):
            subject_statement = subject.conclusion or subject.statement
        else:
            subject_statement = subject.statement
        subject_statement = normalize_text(subject_statement)
        target_statement = normalize_text(target.statement)
        if (
            not subject_statement
            or not target_statement
            or obligation_identity_text(subject_statement)
            == obligation_identity_text(target_statement)
        ):
            return link
        bridge_id = (
            _WEAKER_BRIDGE_ID_PREFIX
            + stable_hash(
                {
                    "subject_id": link.subject_id,
                    "target_obligation_id": link.target_obligation_id,
                    "subject_statement": subject_statement,
                    "target_statement": target_statement,
                }
            )[:20]
        )
        if self.proof_graph.frozen:
            self._emit(
                "weaker_claim_bridge_deferred",
                {
                    "bridge_obligation_id": bridge_id,
                    "subject_id": link.subject_id,
                    "target_obligation_id": link.target_obligation_id,
                    "reason": "proof_graph_frozen",
                },
            )
            return link
        route_ids = set(target.route_ids)
        if isinstance(subject, MessageEnvelope):
            route_ids.add(subject.source_route_id)
        elif isinstance(subject, ProofObligation):
            route_ids.update(subject.route_ids)
        elif isinstance(subject, StrategyCard):
            route_ids.update(
                binding.route_id
                for binding in self.state.route_target_bindings.values()
                if binding.strategy_id == subject.strategy_id
                and binding.route_id is not None
            )
        bridge = self.proof_graph.add_obligation(
            ProofObligation(
                obligation_id=bridge_id,
                problem_hash=target.problem_hash,
                route_ids=sorted(route_ids),
                kind=ObligationKind.LEMMA,
                statement=f"If {subject_statement}, then {target_statement}",
                normalized_statement=normalize_text(
                    f"If {subject_statement}, then {target_statement}"
                ),
                status="open",
                priority=max(0.5, target.priority),
                centrality=target.centrality,
            )
        )
        required = list(
            dict.fromkeys([*link.required_bridge_obligation_ids, bridge.obligation_id])
        )
        remaining = list(
            dict.fromkeys(
                [*link.remaining_obligation_ids_if_proved, bridge.obligation_id]
            )
        )
        updated = link.model_copy(
            update={
                "required_bridge_obligation_ids": required,
                "remaining_obligation_ids_if_proved": remaining,
            }
        )
        self._emit(
            "weaker_claim_bridge_registered",
            {
                "link_id": link.link_id,
                "subject_id": link.subject_id,
                "target_obligation_id": link.target_obligation_id,
                "bridge_obligation_id": bridge.obligation_id,
                "status": bridge.status,
            },
        )
        return updated

    def _register_steps(
        self,
        steps: Sequence[ProofStep],
        *,
        route_id: str | None,
        target_obligation_ids: Sequence[str],
    ) -> None:
        del target_obligation_ids
        for step in steps:
            if step.step_id in self.state.scope_signatures:
                continue
            signature = self.scope_guard._extract(
                subject_id=step.step_id,
                text=f"{step.statement}\n{step.justification}",
                base_confidence=0.35,
            )
            self.state.scope_signatures[step.step_id] = signature
            self.state.proof_roles[step.step_id] = ProofRole.TECHNICAL_LEMMA
            self._emit(
                "proof_role_assigned",
                {
                    "subject_id": step.step_id,
                    "proof_role": ProofRole.TECHNICAL_LEMMA.value,
                },
            )
            self._register_risks(
                self.risk_scanner.scan_step(
                    step,
                    conclusion_scope=signature,
                    route_id=route_id,
                )
            )

    def _register_induction_hints(
        self,
        *,
        route_id: str | None,
        source_id: str,
        source_agent_id: str | None,
        target_obligation_ids: Sequence[str],
        texts: Sequence[str],
    ) -> None:
        if route_id is None or not self.control_config.induction.enabled:
            return
        targets = [
            target_id
            for target_id in dict.fromkeys(target_obligation_ids)
            if self._control_obligation_exists(target_id)
            and self.proof_graph.get_obligation(target_id).status
            in {"open", "tentative", "blocked"}
        ]
        if not targets:
            return
        triggers = self.induction.detect_trigger(*texts)
        if not triggers:
            return
        existing_sources = {
            (tuple(item.trigger_features), tuple(item.target_obligation_ids))
            for item in self.state.induction_measures.values()
            if item.route_id == route_id
        }
        for proposal in self.induction.propose_candidates(
            route_id=route_id,
            target_obligation_ids=targets,
            trigger_features=triggers,
            hints=[source_id, *texts[:4]],
            source_record_ids=[source_id],
            source_agent_id=source_agent_id,
        ):
            signature = (
                tuple(proposal.trigger_features),
                tuple(proposal.target_obligation_ids),
            )
            if signature in existing_sources:
                continue
            self.state.induction_measures[proposal.proposal_id] = proposal
            existing_sources.add(signature)
            self._emit(
                "induction_measure_proposed",
                proposal.model_dump(mode="json"),
            )

    def _induction_target_obligation_ids(
        self,
        route_id: str | None,
        candidate_statements: Sequence[str],
    ) -> list[str]:
        if route_id is None:
            return []
        candidates = {
            obligation_identity_text(statement)
            for statement in candidate_statements
            if obligation_identity_text(statement)
        }
        open_route_obligations = [
            item
            for item in self.proof_graph.obligations
            if item.status in {"open", "tentative", "blocked"}
            and route_id in item.route_ids
        ]
        exact = [
            item.obligation_id
            for item in open_route_obligations
            if obligation_identity_text(item.normalized_statement) in candidates
        ]
        if exact:
            return exact[:1]
        try:
            route = self.route_registry.get(route_id)
        except KeyError:
            return []
        binding = self._route_target_binding_for_strategy(route.strategy_id)
        if binding is not None:
            target = self.proof_graph.get_obligation(
                binding.direct_target_obligation_id
            )
            if target.status in {"open", "tentative", "blocked"}:
                return [target.obligation_id]
        non_main = [
            item
            for item in open_route_obligations
            if item.kind != ObligationKind.MAIN_GOAL
        ]
        if len(non_main) == 1:
            return [non_main[0].obligation_id]
        return []

    def _register_abstract_realizer_if_explicit(
        self, delta: ProofDelta, *, route_id: str | None
    ) -> None:
        if route_id is None or not self.control_config.realizer.enabled:
            return
        text = " ".join(
            [
                *(item.statement for item in delta.new_steps),
                *(item.justification for item in delta.new_steps),
            ]
        ).casefold()
        if not any(
            marker in text
            for marker in (
                "construct",
                "construction",
                "represent",
                "realizer",
                "witness",
            )
        ):
            return
        if any(
            item.source_subject_id == delta.delta_id
            for item in self.state.abstract_structures.values()
        ):
            return
        structure = self.realizers.extract_structure(
            route_id=route_id,
            source_subject_id=delta.delta_id,
            representation_kind="explicit_construction",
            components=[item.step_id for item in delta.new_steps],
            proposed_reduction=(
                delta.completed_subgoal
                or delta.current_goal
                or "reduce the active construction obligation"
            ),
            removable_components=[],
            preserved_constraints=list(delta.active_assumptions or []),
            target_obligation_ids=self._main_goal_ids(),
            evidence_refs=(
                [delta.raw_artifact_ref] if delta.raw_artifact_ref is not None else []
            ),
        )
        self._emit(
            "abstract_structure_registered",
            structure.model_dump(mode="json"),
        )

    def _register_risks(self, risks: Iterable[InferenceRiskRecord]) -> None:
        existing = {
            (
                item.subject_id,
                item.risk_type,
                item.deterministic_rule_id,
            )
            for item in self.state.inference_risks.values()
        }
        for risk in risks:
            key = (
                risk.subject_id,
                risk.risk_type,
                risk.deterministic_rule_id,
            )
            if key in existing:
                continue
            self.state.inference_risks[risk.risk_id] = risk
            existing.add(key)
            self._emit("inference_risk_opened", risk.model_dump(mode="json"))
            if (
                risk.risk_type in self._property_strengthening_risk_types()
                and risk.confidence
                >= self.control_config.scope_guard.risk_confidence_threshold
            ):
                pattern_id = f"negative_pattern_{stable_hash(key)[:16]}"
                self.state.negative_patterns[pattern_id] = NegativePatternRecord(
                    pattern_id=pattern_id,
                    source_risk_id=risk.risk_id,
                    risk_type=risk.risk_type,
                    description=risk.explanation,
                    deterministic_signature=stable_hash(
                        {
                            "risk_type": risk.risk_type.value,
                            "rule_id": risk.deterministic_rule_id,
                            "premise_relations": [
                                item.model_dump(mode="json")
                                for item in risk.premise_relation_signatures
                            ],
                            "conclusion_relation": (
                                risk.conclusion_relation_signature.model_dump(
                                    mode="json"
                                )
                                if risk.conclusion_relation_signature is not None
                                else None
                            ),
                        }
                    ),
                    evidence_ids=[risk.risk_id],
                )
            countermodel_count = sum(
                item.countermodel_task_id is not None
                for item in self.state.inference_risks.values()
            )
            if risk.status != "open" or risk.countermodel_task_id is not None:
                continue
            target_ids = self._risk_target_obligation_ids(risk)
            action = self.action_dispatcher.propose(
                ControlActionType.CREATE_COUNTERMODEL_TASK,
                source_record_ids=[risk.risk_id],
                route_ids=[risk.route_id] if risk.route_id is not None else [],
                target_obligation_ids=target_ids,
                payload={"source_record_id": risk.risk_id},
            )
            if not target_ids:
                self.action_dispatcher.defer(
                    action.action_id,
                    reason="no mathematical target obligation is available",
                )
                continue
            if (
                countermodel_count
                >= self.control_config.scope_guard.max_countermodel_tasks_per_round
            ):
                self.action_dispatcher.defer(
                    action.action_id,
                    reason="countermodel task cap reached",
                )
                continue
            self.action_dispatcher.execute_sync(action.action_id, current_round=0)

    def _extract_near_miss(
        self,
        report: VerificationReport,
        *,
        route_id: str,
        attempt: ProofAttempt | None,
        delta: ProofDelta | None,
    ):
        steps = (
            attempt.proof_steps
            if attempt is not None
            else delta.new_steps
            if delta is not None
            else []
        )
        target_obligation_id = self._near_miss_target_obligation_id(
            report,
            route_id=route_id,
        )
        record = self.near_misses.extract_deterministic(
            report,
            route_id=route_id,
            target_obligation_id=target_obligation_id,
            abstract_idea=(steps[0].justification if steps else ""),
            concrete_candidate=(
                attempt.final_answer
                if attempt is not None and attempt.final_answer
                else delta.candidate_final_answer
                if delta is not None and delta.candidate_final_answer
                else steps[-1].statement
                if steps
                else ""
            ),
            preserved_properties=[
                item.statement
                for item in steps
                if item.step_id != report.first_error_step
            ][:4],
            failed_constraints=[item.description for item in report.issues],
            salvageable_components=[
                item.step_id
                for item in steps
                if item.step_id != report.first_error_step
            ][:4],
        )
        if record is not None:
            added = self.near_misses.add(record)
            self.state.near_misses[added.near_miss_id] = added
            self._emit("near_miss_recorded", added.model_dump(mode="json"))
            if added.repair_module == "induction_selector":
                self._register_induction_hints(
                    route_id=route_id,
                    source_id=added.near_miss_id,
                    source_agent_id=report.agent_id,
                    target_obligation_ids=[added.target_obligation_id],
                    texts=[
                        added.abstract_idea,
                        added.concrete_candidate,
                        *added.failed_constraints,
                    ],
                )
        else:
            diagnostic = self.near_misses.process_diagnostic(
                report,
                route_id=route_id,
                target_obligation_id=target_obligation_id,
            )
            if diagnostic is not None:
                self.state.process_diagnostics[diagnostic.diagnostic_id] = diagnostic
                self._emit(
                    "process_failure_diagnostic_recorded",
                    diagnostic.model_dump(mode="json"),
                )
        return record

    def _near_miss_target_obligation_id(
        self,
        report: VerificationReport,
        *,
        route_id: str,
    ) -> str | None:
        candidate_ids = [
            report.target_id,
            *(issue.claim_id for issue in report.issues if issue.claim_id is not None),
        ]
        try:
            route = self.route_registry.get(route_id)
        except KeyError:
            route = None
        if route is not None:
            binding = self._route_target_binding_for_strategy(route.strategy_id)
            if binding is not None:
                candidate_ids.append(binding.direct_target_obligation_id)
        candidate_ids.extend(
            item.obligation_id
            for item in self.proof_graph.obligations_in_core_closure(
                route_id=route_id,
                open_only=True,
            )
        )
        candidate_ids.extend(self._main_goal_ids())
        for candidate_id in dict.fromkeys(candidate_ids):
            try:
                obligation = self.proof_graph.get_obligation(candidate_id)
            except KeyError:
                continue
            if obligation.status in {
                "open",
                "tentative",
                "blocked",
            } and self._eligible_mathematical_obligation(obligation):
                return obligation.obligation_id
        return None

    def _record_realizer_failure(
        self, report: VerificationReport, *, route_id: str
    ) -> None:
        candidate = next(
            (
                item
                for item in self.state.realizer_candidates.values()
                if item.route_id == route_id and item.status == "candidate"
            ),
            None,
        )
        if candidate is None:
            return
        issue_text = " ".join(item.description for item in report.issues).casefold()
        failure_type = (
            RealizerFailureType.ADMISSIBILITY
            if "admiss" in issue_text
            else RealizerFailureType.LOWER_BOUND
            if "lower bound" in issue_text
            else RealizerFailureType.UPPER_BOUND
            if "upper bound" in issue_text
            else RealizerFailureType.SCOPE
            if "scope" in issue_text or "quantifier" in issue_text
            else RealizerFailureType.UNKNOWN
        )
        failed = self.realizers.record_realizer_failure(
            candidate.candidate_id,
            failure_type,
            report.concise_feedback,
        )
        self._emit("realizer_failed", failed.model_dump(mode="json"))
        try:
            repair = self.realizers.create_repair_task(
                structure_id=failed.structure_id,
                failed_candidate_id=failed.candidate_id,
                repair_operator="replace_realizer_preserve_structure",
                required_constraints=(
                    [item.description for item in report.issues]
                    or [report.concise_feedback]
                ),
                targeted_obligation_ids=self._main_goal_ids(),
            )
        except ValueError:
            return
        self.state.realizer_repair_tasks[repair.task_id] = repair
        self._emit(
            "realizer_repair_requested",
            repair.model_dump(mode="json"),
        )

    def _message_gate(
        self, message: MessageEnvelope, current_round: int
    ) -> tuple[bool, str | None]:
        link = self.register_message(message)
        reasons: list[str] = []
        signature = self.state.scope_signatures.get(message.message_id)
        if message.memory_tier == MemoryTier.FACT and signature is not None:
            if (
                self.control_config.scope_guard.block_fact_promotion_on_open_scope_risk
                and not self.scope_guard.can_promote_fact(signature)
            ):
                reasons.append("message scope confidence is too low for Fact promotion")
            target = self._matching_or_main_obligation(message)
            if (
                target is not None
                and self.control_config.scope_guard.block_obligation_close_on_scope_mismatch
                and not self.scope_guard.can_close_obligation(
                    signature, self._scope_for_obligation(target)
                )
            ):
                reasons.append("message scope cannot close the target obligation")
            if link is not None and link.relation in {
                GoalRelation.NECESSARY_ONLY,
                GoalRelation.HEURISTIC_ONLY,
                GoalRelation.UNRELATED,
            }:
                reasons.append(
                    f"{link.relation.value} message cannot be promoted as sufficient"
                )
            if any(
                item.subject_id in {message.message_id, *message.dependencies}
                and item.status == "open"
                for item in self.state.inference_risks.values()
            ):
                reasons.append("message has an open proof-control inference risk")

        destinations = set(message.target_route_ids) or set(
            self.route_registry.neighbors(message.source_route_id, current_round)
        )
        utility_enabled = self.control_config.message_utility.enabled
        requires_contract = (
            utility_enabled
            and self.control_config.message_utility.require_utility_contract_for_cross_route
            and bool(destinations)
            and message.message_type
            not in MessageUtilityController.EXEMPT_MESSAGE_TYPES
        )
        if (
            requires_contract
            and self.message_utility.contract_for_message(
                message.message_id, current_round=current_round
            )
            is None
        ):
            targets = self._utility_targets(message, destinations)
            if not targets:
                self._emit(
                    "message_utility_target_unavailable",
                    {
                        "message_id": message.message_id,
                        "decision": BroadcastDecision.KEEP_LOCAL.value,
                        "reason": "no proof-obligation utility target",
                    },
                )
            else:
                try:
                    contract = self.message_utility.register_contract(
                        message,
                        target_obligation_ids=targets,
                        expected_effect=self._expected_message_effect(message),
                        required_assumptions=message.assumptions,
                        current_round=current_round,
                    )
                    self.state.utility_contracts[contract.contract_id] = contract
                    self._emit(
                        "message_utility_contract_created",
                        contract.model_dump(mode="json"),
                    )
                except ValueError as exc:
                    reasons.append(str(exc))
        if reasons:
            self._emit(
                "proof_control_message_admission_blocked",
                {
                    "message_id": message.message_id,
                    "mode": self.control_config.mode,
                    "reasons": reasons,
                },
            )
            if self.active:
                return False, "; ".join(reasons)
        return True, None

    def _message_broadcast_allowed(
        self,
        message: MessageEnvelope,
        current_round: int,
    ) -> bool:
        contract = self.message_utility.contract_for_message(
            message.message_id,
            current_round=current_round,
        )
        decision = self.message_utility.decide_broadcast(
            message,
            contract=contract,
            priority=self.message_broker.message_priority(message),
            current_round=current_round,
        )
        self.state.broadcast_decisions[decision.decision_id] = decision
        self._emit(
            "message_broadcast_decided",
            decision.model_dump(mode="json"),
        )
        if self.shadow:
            return True
        return decision.decision == BroadcastDecision.BROADCAST

    def _pre_close_policy(
        self, message: MessageEnvelope, obligation: ProofObligation
    ) -> tuple[bool, str | None]:
        message_scope = self.state.scope_signatures.get(message.message_id)
        if message_scope is None:
            message_scope = self.scope_guard.extract_from_message(message)
            self.state.scope_signatures[message.message_id] = message_scope
        obligation_scope = self._scope_for_obligation(obligation)
        allowed = self.scope_guard.can_close_obligation(message_scope, obligation_scope)
        risky_subject_ids = {message.message_id, *message.dependencies}
        blocking_risks = [
            item
            for item in self.state.inference_risks.values()
            if item.status == "open"
            and item.subject_id in risky_subject_ids
            and item.confidence
            >= self.control_config.scope_guard.risk_confidence_threshold
        ]
        if blocking_risks:
            payload = {
                "message_id": message.message_id,
                "obligation_id": obligation.obligation_id,
                "mode": self.control_config.mode,
                "reason": "open high-confidence inference risk blocks closure",
                "risk_ids": [item.risk_id for item in blocking_risks],
            }
            self._emit("inference_risk_close_blocked", payload)
            if self.active:
                return False, str(payload["reason"])
        if not allowed:
            payload = {
                "message_id": message.message_id,
                "obligation_id": obligation.obligation_id,
                "mode": self.control_config.mode,
                "reason": "scope signature does not entail the obligation scope",
            }
            self._emit("scope_mismatch_detected", payload)
            if self.active:
                return False, str(payload["reason"])
        return True, None

    def _utility_targets(
        self, message: MessageEnvelope, destinations: set[str]
    ) -> list[str]:
        matches = [
            item.obligation_id
            for item in self.proof_graph.obligations
            if item.status in {"open", "tentative", "blocked"}
            and self._eligible_mathematical_obligation(item)
            and (
                item.normalized_statement == message.normalized_statement
                or bool(set(item.route_ids) & destinations)
            )
        ]
        if not matches:
            matches = self._main_goal_ids()
        return list(dict.fromkeys(matches))[
            : self.control_config.message_utility.max_target_obligations
        ]

    @staticmethod
    def _expected_message_effect(
        message: MessageEnvelope,
    ) -> MessageExpectedEffect:
        if message.message_type == MessageType.COUNTEREXAMPLE:
            return MessageExpectedEffect.REFUTE
        if message.message_type == MessageType.STRATEGY_REWRITE_REQUEST:
            return MessageExpectedEffect.REWRITE
        if message.message_type in {
            MessageType.REPAIR_REQUEST,
            MessageType.BRIDGE_LEMMA_REQUEST,
        }:
            return MessageExpectedEffect.PROVIDE_CONSTRUCTION
        if message.memory_tier == MemoryTier.FACT:
            return MessageExpectedEffect.CLOSE
        return MessageExpectedEffect.REDUCE

    def _matching_or_main_obligation(
        self, message: MessageEnvelope
    ) -> ProofObligation | None:
        match = next(
            (
                item
                for item in self.proof_graph.obligations
                if self._eligible_mathematical_obligation(item)
                and item.normalized_statement == message.normalized_statement
                and item.assumptions == message.assumptions
            ),
            None,
        )
        return match or self._main_goal()

    def _ensure_obligation_domain(
        self,
        obligation: ProofObligation,
        *,
        source_kind: str | None = None,
    ) -> ObligationDomainRecord:
        existing = self.state.obligation_domains.get(obligation.obligation_id)
        if existing is not None and (
            source_kind is None
            or existing.inferred_from == source_kind.strip().casefold()
        ):
            return existing
        record = classify_obligation_domain(
            obligation,
            source_kind=source_kind,
        )
        self.state.obligation_domains[obligation.obligation_id] = record
        self._emit(
            "obligation_domain_classified",
            record.model_dump(mode="json"),
        )
        return record

    def _eligible_mathematical_obligation(
        self,
        obligation: ProofObligation,
    ) -> bool:
        domain = self._ensure_obligation_domain(obligation)
        quality = self.state.obligation_semantic_quality.get(obligation.obligation_id)
        return domain.eligible_for_mathematical_control and (
            quality is None or quality.eligible_for_core_debt
        )

    def _scope_for_obligation(self, obligation: ProofObligation) -> ScopeSignature:
        signature = self.state.scope_signatures.get(obligation.obligation_id)
        if signature is None:
            signature = self.scope_guard.extract_from_obligation(obligation)
            self.state.scope_signatures[obligation.obligation_id] = signature
        return signature

    def _main_goal(self) -> ProofObligation | None:
        ids = self.proof_graph.main_goal_obligation_ids()
        return self.proof_graph.get_obligation(ids[0]) if ids else None

    def _main_goal_ids(self) -> list[str]:
        return self.proof_graph.main_goal_obligation_ids()

    def _goal_link_for_subject(self, subject_id: str) -> ClaimGoalLink | None:
        return next(
            (
                item
                for item in self.state.goal_links.values()
                if item.subject_id == subject_id
            ),
            None,
        )

    def _goal_link_for_route(self, route_id: str) -> ClaimGoalLink | None:
        try:
            strategy_id = self.route_registry.get(route_id).strategy_id
        except KeyError:
            return None
        return self._goal_link_for_subject(strategy_id)

    def _route_id_for_strategy(self, strategy_id: str) -> str | None:
        route = self.route_registry.route_for_strategy(strategy_id)
        return route.route_id if route is not None else None

    def _update_core_debt(self, route_id: str) -> float:
        debt = core_proof_debt(
            self.proof_graph,
            route_id,
            config=self.control_config.core_debt,
            proof_roles=self.state.proof_roles,
            inference_risks=self.state.inference_risks,
            critical_assumptions=self.state.critical_assumptions,
            obligation_domains=self.state.obligation_domains,
            obligation_semantic_quality=self.state.obligation_semantic_quality,
        )
        history = self.state.core_debt_history.setdefault(route_id, [])
        if not history or abs(history[-1] - debt) > 1e-9:
            history.append(debt)
            self._emit(
                "core_proof_debt_updated",
                {
                    "route_id": route_id,
                    "core_proof_debt": debt,
                    "history_length": len(history),
                },
            )
        return debt

    def _request_blueprint_rewrite_after_stagnation(self, route_id: str) -> None:
        if not self.control_config.continue_gate.force_blueprint_rewrite_after_block:
            return
        failure = next(
            (
                item
                for item in reversed(list(self.state.failure_records.values()))
                if item.route_id == route_id
            ),
            None,
        )
        if failure is None:
            return
        try:
            request = self.blueprint_rewriter.build_request(
                route_id=route_id,
                failure_record_id=failure.record_id,
                preserved_fact_ids=[
                    item.message_id for item in self.message_broker.admitted_facts()
                ],
                preserved_step_ids=[],
                invalidated_plan_elements=[failure.target_id],
                current_overstrong_targets=[],
                proposed_weaker_targets=[],
                proposed_bridge_obligation_ids=[],
                representation_change_required=(
                    failure.control_failure_class.value == "framing"
                ),
            )
        except ValueError:
            return
        self.state.blueprint_rewrites[request.request_id] = request
        self._emit(
            "blueprint_rewrite_requested",
            request.model_dump(mode="json"),
        )
        route = self.route_registry.get(route_id)
        binding = self._route_target_binding_for_strategy(route.strategy_id)
        self.dispatch_blueprint_rewrite(
            request.request_id,
            strategy_id=route.strategy_id,
            binding_id=binding.binding_id if binding is not None else None,
            current_round=max(
                (
                    item.segment_index
                    for item in self.state.continue_gate_records
                    if item.route_id == route_id
                ),
                default=0,
            ),
        )

    @staticmethod
    def _failure_fingerprint(report: VerificationReport) -> str | None:
        if report.first_error_step:
            return report.first_error_step
        if report.issues:
            return normalize_text(report.issues[0].description)
        return None

    def _failure_distribution(self) -> dict[str, int]:
        result: dict[str, int] = {}
        for item in self.state.failure_records.values():
            key = item.control_failure_class.value
            result[key] = result.get(key, 0) + 1
        return dict(sorted(result.items()))

    def _dispatch_strategy_sub_obligation(
        self,
        strategy: StrategyCard,
        *,
        main_goal_id: str,
    ) -> str | None:
        obligation_id = (
            "obl_route_"
            + stable_hash(
                {
                    "problem_hash": self.proof_graph.problem_hash,
                    "strategy_id": strategy.strategy_id,
                    "statement": normalize_text(strategy.bottleneck),
                }
            )[:16]
        )
        route = self.route_registry.route_for_strategy(strategy.strategy_id)
        obligation = ProofObligation(
            obligation_id=obligation_id,
            problem_hash=self.proof_graph.problem_hash,
            route_ids=[route.route_id] if route is not None else [],
            kind=ObligationKind.SUBGOAL,
            statement=strategy.bottleneck,
            normalized_statement=normalize_text(strategy.bottleneck),
            assumptions=list(strategy.prerequisites),
            priority=0.75,
            centrality=0.6,
        )
        action = self.action_dispatcher.propose(
            ControlActionType.CREATE_SUB_OBLIGATION,
            route_ids=[route.route_id] if route is not None else [],
            target_obligation_ids=[main_goal_id],
            payload={
                "strategy_id": strategy.strategy_id,
                "parent_main_goal_id": main_goal_id,
                "source_kind": "strategy",
                "executable_first_step": strategy.key_original_step,
                "obligation": obligation.model_dump(mode="json"),
            },
        )
        executed = self.action_dispatcher.execute_sync(
            action.action_id,
            current_round=0,
        )
        if self.shadow:
            return None
        if not executed.result_refs:
            raise RuntimeError("sub-obligation action did not materialize a target")
        return executed.result_refs[0]

    def _handle_create_sub_obligation(
        self, action: ControlActionRecord
    ) -> ControlActionResult:
        obligation = ProofObligation.model_validate(action.payload["obligation"])
        parent_id = action.payload.get("parent_main_goal_id")
        parent = (
            self.proof_graph.get_obligation(parent_id)
            if isinstance(parent_id, str) and self._control_obligation_exists(parent_id)
            else None
        )
        quality = self._assess_obligation_quality(
            obligation,
            source_kind=str(action.payload.get("source_kind", "strategy")),
            source_statement=parent.normalized_statement
            if parent is not None
            else None,
            executable_first_step=str(
                action.payload.get("executable_first_step") or ""
            ),
        )
        if not quality.accepted:
            return ControlActionResult(
                postcondition_met=False,
                detail=(
                    "sub-obligation failed semantic quality: "
                    + ", ".join(quality.rejection_reasons)
                ),
            )
        materialized = self.proof_graph.add_obligation(obligation)
        if isinstance(parent_id, str) and parent_id:
            self._ensure_implication_edge(materialized.obligation_id, parent_id)
        return ControlActionResult(
            result_refs=[materialized.obligation_id],
            postcondition_met=True,
        )

    def _sub_obligation_postcondition(
        self,
        _action: ControlActionRecord,
        result: ControlActionResult,
    ) -> bool:
        for obligation_id in result.result_refs:
            try:
                obligation = self.proof_graph.get_obligation(obligation_id)
            except KeyError:
                return False
            if obligation.kind == ObligationKind.MAIN_GOAL:
                return False
            quality = self.state.obligation_semantic_quality.get(obligation_id)
            if quality is None or not quality.accepted:
                return False
        return bool(result.result_refs)

    def _ensure_dependency_edge(
        self,
        parent_obligation_id: str,
        dependency_obligation_id: str,
    ) -> None:
        if parent_obligation_id == dependency_obligation_id:
            return
        if any(
            edge.source_id == parent_obligation_id
            and edge.target_id == dependency_obligation_id
            and edge.edge_type == GraphEdgeType.DEPENDS_ON
            for edge in self.proof_graph.edges
        ):
            return
        self.proof_graph.add_edge(
            ProofGraphEdge(
                source_id=parent_obligation_id,
                target_id=dependency_obligation_id,
                edge_type=GraphEdgeType.DEPENDS_ON,
            )
        )

    def _ensure_implication_edge(
        self,
        source_obligation_id: str,
        target_obligation_id: str,
    ) -> None:
        if source_obligation_id == target_obligation_id:
            return
        if any(
            edge.source_id == source_obligation_id
            and edge.target_id == target_obligation_id
            and edge.edge_type == GraphEdgeType.IMPLIES
            for edge in self.proof_graph.edges
        ):
            return
        self.proof_graph.add_edge(
            ProofGraphEdge(
                source_id=source_obligation_id,
                target_id=target_obligation_id,
                edge_type=GraphEdgeType.IMPLIES,
            )
        )

    def _dispatch_route_target_binding(
        self,
        binding: RouteTargetBinding,
        link: ClaimGoalLink,
    ) -> ControlActionRecord:
        action = self.action_dispatcher.propose(
            ControlActionType.BIND_ROUTE_TARGET,
            source_record_ids=[link.link_id],
            route_ids=[binding.route_id] if binding.route_id is not None else [],
            target_obligation_ids=[
                binding.direct_target_obligation_id,
                binding.main_goal_obligation_id,
            ],
            payload=binding.model_dump(mode="json"),
        )
        self.action_dispatcher.execute_sync(action.action_id, current_round=0)
        return action

    def _handle_bind_route_target(
        self, action: ControlActionRecord
    ) -> ControlActionResult:
        binding = RouteTargetBinding.model_validate(action.payload)
        self.state.route_target_bindings[binding.binding_id] = binding
        return ControlActionResult(
            result_refs=[binding.binding_id],
            postcondition_met=True,
        )

    def _route_target_binding_postcondition(
        self,
        _action: ControlActionRecord,
        result: ControlActionResult,
    ) -> bool:
        return bool(result.result_refs) and all(
            result_ref in self.state.route_target_bindings
            for result_ref in result.result_refs
        )

    def _dispatch_countermodel_if_required(
        self,
        link: ClaimGoalLink,
        *,
        route_id: str | None,
    ) -> str | None:
        if (
            link.relation != GoalRelation.UNKNOWN
            or not self.control_config.goal_alignment.run_countermodel_on_unknown_relation
        ):
            return None
        action = self.action_dispatcher.propose(
            ControlActionType.CREATE_COUNTERMODEL_TASK,
            source_record_ids=[link.link_id],
            route_ids=[route_id] if route_id is not None else [],
            target_obligation_ids=[link.target_obligation_id],
            payload={
                "source_record_id": link.link_id,
                "goal_link_id": link.link_id,
            },
        )
        self.action_dispatcher.execute_sync(action.action_id, current_round=0)
        return action.action_id

    def _handle_create_countermodel_task(
        self, action: ControlActionRecord
    ) -> ControlActionResult:
        source_record_id = str(action.payload["source_record_id"])
        link_id = action.payload.get("goal_link_id")
        link = self.state.goal_links.get(str(link_id)) if link_id is not None else None
        target_obligation_id = (
            link.target_obligation_id
            if link is not None
            else action.target_obligation_ids[0]
        )
        task_id = f"countermodel_task_{action.idempotency_key[:16]}"
        risk = self.state.inference_risks.get(source_record_id)
        assigned_agent_id = self._counterexample_hunter_agent_id()
        executable = self.executable_task_controller.create_countermodel_task(
            source_record_id=source_record_id,
            target_claim_ids=(
                [risk.subject_id]
                if risk is not None and risk.subject_id in self.claim_lifecycle.claims
                else []
            ),
            target_obligation_ids=[target_obligation_id],
            route_ids=list(action.route_ids),
            created_round=action.created_round,
            counterexample_hunter_agent_id=assigned_agent_id,
            explicit_prompt_ref=f"countermodel_prompt:{source_record_id}",
        )
        self._mark_task_routes_waiting(
            executable,
            current_round=action.created_round,
        )
        task = CountermodelTaskRecord(
            task_id=task_id,
            source_record_id=source_record_id,
            source_goal_link_id=str(link_id) if link_id is not None else None,
            target_obligation_id=target_obligation_id,
            route_ids=list(action.route_ids),
            status="assigned" if assigned_agent_id is not None else "deferred",
            reason=(
                "counterexample hunter assigned"
                if assigned_agent_id is not None
                else "awaiting an available counterexample hunter"
            ),
            assigned_agent_id=assigned_agent_id,
            executable_task_id=executable.task_id,
        )
        self.state.countermodel_tasks[task_id] = task
        if link is not None:
            link.countermodel_status = (
                "pending" if assigned_agent_id is not None else "deferred"
            )
        if risk is not None:
            risk.countermodel_task_id = task_id
        return ControlActionResult(
            result_refs=[task_id, executable.task_id],
            postcondition_met=True,
        )

    def _countermodel_task_postcondition(
        self,
        _action: ControlActionRecord,
        result: ControlActionResult,
    ) -> bool:
        tasks = [
            self.state.countermodel_tasks[result_ref]
            for result_ref in result.result_refs
            if result_ref in self.state.countermodel_tasks
        ]
        if len(tasks) != 1:
            return False
        task = tasks[0]
        executable = (
            self.state.executable_tasks.get(task.executable_task_id)
            if task.executable_task_id is not None
            else None
        )
        return (
            task.status
            in {
                "assigned",
                "ready",
                "running",
                "deferred",
                "inapplicable",
                "completed",
                "inconclusive",
                "failed",
                "expired",
            }
            and executable is not None
            and task.task_id in result.result_refs
            and executable.task_id in result.result_refs
            and (
                executable.assigned_agent_id is not None
                or executable.registered_handler is not None
                or bool(executable.wake_conditions)
                or executable.status in ExecutableTaskController.TERMINAL_STATUSES
            )
        )

    def _handle_direct_premise_request(
        self, action: ControlActionRecord
    ) -> ControlActionResult:
        record_id = str(action.payload["premise_closure_record_id"])
        record = self.state.premise_closure_records[record_id]
        return ControlActionResult(
            result_refs=[record.record_id],
            postcondition_met=record.verified,
            detail=(
                ""
                if record.verified
                else "direct-premise request lacks exact verified evidence"
            ),
        )

    def _direct_premise_postcondition(
        self,
        _action: ControlActionRecord,
        result: ControlActionResult,
    ) -> bool:
        return bool(result.result_refs) and all(
            result_ref in self.state.premise_closure_records
            and self.state.premise_closure_records[result_ref].verified
            for result_ref in result.result_refs
        )

    def _handle_create_assumption_challenger(
        self, action: ControlActionRecord
    ) -> ControlActionResult:
        family_id = str(action.payload["family_id"])
        family = self.state.assumption_families[family_id]
        task = self.common_mode.challenger_for_family(family)
        task.action_id = action.action_id
        self.state.assumption_challenger_tasks[task.task_id] = task
        family.challenger_task_id = task.task_id
        for assumption_id in family.member_assumption_ids:
            assumption = self.state.critical_assumptions[assumption_id]
            assumption.challenger_task_id = task.task_id
        self._emit(
            "assumption_challenger_created",
            task.model_dump(mode="json"),
        )
        return ControlActionResult(
            result_refs=[family.family_id, task.task_id],
            postcondition_met=True,
        )

    def _assumption_challenger_postcondition(
        self,
        action: ControlActionRecord,
        result: ControlActionResult,
    ) -> bool:
        family = self.state.assumption_families.get(
            str(action.payload.get("family_id", ""))
        )
        if family is None or family.challenger_task_id is None:
            return False
        task = self.state.assumption_challenger_tasks.get(family.challenger_task_id)
        return (
            task is not None
            and task.action_id == action.action_id
            and not task.premise_eligible
            and task.task_id in result.result_refs
            and set(task.assumption_ids) == set(family.member_assumption_ids)
        )

    def _handle_materialize_bottleneck_cluster(
        self, action: ControlActionRecord
    ) -> ControlActionResult:
        cluster = BottleneckCluster.model_validate(action.payload["cluster"])
        for member_id in cluster.member_obligation_ids:
            obligation = self.proof_graph.get_obligation(member_id)
            if not self._eligible_mathematical_obligation(obligation):
                return ControlActionResult(
                    postcondition_met=False,
                    detail="non-mathematical obligations cannot enter a bottleneck cluster",
                )
        if cluster.canonical_obligation_id not in cluster.member_obligation_ids:
            return ControlActionResult(
                detail="bottleneck canonical obligation is not a cluster member"
            )
        cluster.alias_map = {
            member_id: cluster.canonical_obligation_id
            for member_id in cluster.member_obligation_ids
        }
        cluster.member_statuses = {
            member_id: self.proof_graph.get_obligation(member_id).status
            for member_id in cluster.member_obligation_ids
        }
        cluster.materialization_action_id = action.action_id
        task_id = (
            "bottleneck_bridge_"
            + stable_hash(
                {
                    "cluster_id": cluster.cluster_id,
                    "canonical_obligation_id": cluster.canonical_obligation_id,
                }
            )[:16]
        )
        task = BottleneckBridgeTask(
            task_id=task_id,
            cluster_id=cluster.cluster_id,
            target_obligation_id=cluster.canonical_obligation_id,
            member_obligation_ids=list(cluster.member_obligation_ids),
            route_ids=list(cluster.route_ids),
            required_action=(
                "Resolve the canonical mathematical bottleneck while preserving "
                "all member obligations as auditable aliases."
            ),
        )
        cluster.bridge_task_id = task.task_id
        self.state.bottleneck_clusters[cluster.cluster_id] = cluster
        self.state.bottleneck_bridge_tasks[task.task_id] = task
        self.state.bottleneck_aliases.update(cluster.alias_map)
        self._emit(
            "bottleneck_cluster_created",
            cluster.model_dump(mode="json"),
        )
        return ControlActionResult(
            result_refs=[
                cluster.cluster_id,
                task.task_id,
                cluster.canonical_obligation_id,
            ],
            postcondition_met=True,
        )

    def _bottleneck_cluster_postcondition(
        self,
        action: ControlActionRecord,
        result: ControlActionResult,
    ) -> bool:
        cluster_payload = action.payload.get("cluster", {})
        cluster_id = (
            str(cluster_payload.get("cluster_id", ""))
            if isinstance(cluster_payload, Mapping)
            else ""
        )
        cluster = self.state.bottleneck_clusters.get(cluster_id)
        if (
            cluster is None
            or cluster.materialization_action_id != action.action_id
            or cluster.bridge_task_id is None
        ):
            return False
        return (
            cluster.cluster_id in result.result_refs
            and cluster.bridge_task_id in self.state.bottleneck_bridge_tasks
            and all(
                self.state.bottleneck_aliases.get(member_id)
                == cluster.canonical_obligation_id
                for member_id in cluster.member_obligation_ids
            )
        )

    def _handle_materialize_falsification_task(
        self, action: ControlActionRecord
    ) -> ControlActionResult:
        task_id = str(action.payload["task_id"])
        task = self.state.falsification_tasks[task_id]
        executable = (
            self.state.executable_tasks.get(task.executable_task_id)
            if task.executable_task_id is not None
            else None
        )
        if task.experiment_spec is None or task.computation_plan is None:
            if executable is not None and (
                executable.assigned_agent_id is not None
                or executable.wake_conditions
                or executable.terminal_reason is not None
            ):
                task.status = "deferred"
                task.action_id = action.action_id
                self._emit(
                    "falsification_task_routed",
                    {
                        "action_id": action.action_id,
                        "task_id": task.task_id,
                        "executable_task_id": executable.task_id,
                        "status": executable.status.value,
                    },
                )
                return ControlActionResult(
                    result_refs=[task.task_id, executable.task_id],
                    postcondition_met=True,
                )
            return ControlActionResult(
                detail=task.deferred_reason
                or "falsification request has no typed finite computation plan"
            )
        if task.experiment_spec.method == ComputationMethod.SANDBOXED_PYTHON:
            return ControlActionResult(
                detail="sandboxed Python is forbidden in the falsification fast lane"
            )
        task.status = "admitted"
        task.action_id = action.action_id
        self._emit(
            "falsification_task_materialized",
            {
                "action_id": action.action_id,
                "task_id": task.task_id,
                "experiment_id": task.experiment_spec.experiment_id,
                "target_obligation_id": task.target_obligation_id,
            },
        )
        return ControlActionResult(
            result_refs=[
                task.task_id,
                task.computation_plan.plan_id,
                *([executable.task_id] if executable is not None else []),
            ],
            postcondition_met=True,
        )

    def _falsification_task_postcondition(
        self,
        action: ControlActionRecord,
        result: ControlActionResult,
    ) -> bool:
        task = self.state.falsification_tasks.get(
            str(action.payload.get("task_id", ""))
        )
        executable = (
            self.state.executable_tasks.get(task.executable_task_id)
            if task is not None and task.executable_task_id is not None
            else None
        )
        if task is not None and task.experiment_spec is None and executable is not None:
            return (
                task.status == "deferred"
                and task.action_id == action.action_id
                and task.task_id in result.result_refs
                and executable.task_id in result.result_refs
                and (
                    executable.assigned_agent_id is not None
                    or bool(executable.wake_conditions)
                    or executable.terminal_reason is not None
                )
            )
        return (
            task is not None
            and task.status == "admitted"
            and task.action_id == action.action_id
            and task.experiment_spec is not None
            and task.computation_plan is not None
            and task.task_id in result.result_refs
            and task.computation_plan.plan_id in result.result_refs
            and executable is not None
            and executable.task_id in result.result_refs
        )

    def _handle_schedule_route_update(
        self,
        action: ControlActionRecord,
    ) -> ControlActionResult:
        route_id = str(action.payload["target_route_id"])
        message_ids = list(dict.fromkeys(action.payload.get("message_ids", [])))
        priority = str(action.payload.get("priority", ""))
        if priority not in {"critical", "high"}:
            return ControlActionResult(
                postcondition_met=False,
                detail="route updates are reserved for critical or high messages",
            )
        if not message_ids:
            return ControlActionResult(
                postcondition_met=False,
                detail="route update has no message to process",
            )
        for message_id in message_ids:
            self.message_broker.schedule_route_update(
                str(message_id),
                route_id,
                action_id=action.action_id,
            )
        task_id = (
            "route_update_"
            + stable_hash(
                {
                    "problem_hash": self.proof_graph.problem_hash,
                    "route_id": route_id,
                    "message_ids": message_ids,
                    "action_id": action.action_id,
                }
            )[:16]
        )
        task = RouteUpdateTask(
            task_id=task_id,
            target_route_id=route_id,
            message_ids=[str(item) for item in message_ids],
            priority=priority,
            scheduled_round=int(action.payload.get("scheduled_round", 0)),
            action_id=action.action_id,
        )
        executable = self.executable_task_controller.create_route_update_task(
            target_obligation_ids=list(action.target_obligation_ids),
            route_ids=[route_id],
            created_round=task.scheduled_round,
            explicit_prompt_ref=("route_update_messages:" + ",".join(task.message_ids)),
        )
        task.executable_task_id = executable.task_id
        self.state.route_update_tasks[task.task_id] = task
        self._emit(
            "route_update_scheduled",
            task.model_dump(mode="json"),
        )
        return ControlActionResult(
            result_refs=[task.task_id, executable.task_id, *task.message_ids],
            postcondition_met=True,
        )

    def _route_update_postcondition(
        self,
        action: ControlActionRecord,
        result: ControlActionResult,
    ) -> bool:
        task = next(
            (
                item
                for item in self.state.route_update_tasks.values()
                if item.action_id == action.action_id
            ),
            None,
        )
        if task is None or task.task_id not in result.result_refs:
            return False
        return all(
            (
                delivery := self.message_broker.delivery_record(
                    message_id,
                    task.target_route_id,
                )
            )
            is not None
            and delivery.get("route_update_action_id") == action.action_id
            and delivery.get("delivery_state") == "scheduled"
            for message_id in task.message_ids
        )

    def _handle_defer_inspiration_review(
        self,
        action: ControlActionRecord,
    ) -> ControlActionResult:
        deferral_id = str(action.payload["deferral_id"])
        record = self.state.inspiration_review_deferrals[deferral_id]
        record.review_status = "deferred"
        record.reviewed = False
        record.reason = str(action.payload.get("reason", record.reason))
        record.assigned_reviewer_agent_id = None
        record.defer_action_id = action.action_id
        self._emit(
            "inspiration_review_deferred",
            record.model_dump(mode="json"),
        )
        return ControlActionResult(
            result_refs=[record.deferral_id],
            postcondition_met=True,
        )

    def _inspiration_review_deferred_postcondition(
        self,
        action: ControlActionRecord,
        result: ControlActionResult,
    ) -> bool:
        record = self.state.inspiration_review_deferrals.get(
            str(action.payload.get("deferral_id", ""))
        )
        return (
            record is not None
            and record.review_status == "deferred"
            and not record.reviewed
            and record.defer_action_id == action.action_id
            and record.deferral_id in result.result_refs
        )

    def _handle_reassign_inspiration_review(
        self,
        action: ControlActionRecord,
    ) -> ControlActionResult:
        deferral_id = str(action.payload["deferral_id"])
        reviewer_agent_id = str(action.payload["reviewer_agent_id"]).strip()
        if not reviewer_agent_id:
            return ControlActionResult(
                postcondition_met=False,
                detail="inspiration review reassignment has no reviewer",
            )
        record = self.state.inspiration_review_deferrals[deferral_id]
        record.review_status = "reassigned"
        record.reviewed = False
        record.assigned_reviewer_agent_id = reviewer_agent_id
        record.reassign_action_id = action.action_id
        self._emit(
            "inspiration_review_reassigned",
            record.model_dump(mode="json"),
        )
        return ControlActionResult(
            result_refs=[record.deferral_id, reviewer_agent_id],
            postcondition_met=True,
        )

    def _inspiration_review_reassigned_postcondition(
        self,
        action: ControlActionRecord,
        result: ControlActionResult,
    ) -> bool:
        record = self.state.inspiration_review_deferrals.get(
            str(action.payload.get("deferral_id", ""))
        )
        reviewer_agent_id = str(action.payload.get("reviewer_agent_id", ""))
        return (
            record is not None
            and record.review_status == "reassigned"
            and not record.reviewed
            and record.assigned_reviewer_agent_id == reviewer_agent_id
            and record.reassign_action_id == action.action_id
            and record.deferral_id in result.result_refs
        )

    async def _handle_execute_meta_pivot(
        self,
        action: ControlActionRecord,
    ) -> ControlActionResult:
        pivot = self.state.meta_pivot_state
        if (
            pivot is None
            or pivot.pivot_id != str(action.payload.get("pivot_id", ""))
            or pivot.action_id != action.action_id
        ):
            return ControlActionResult(
                postcondition_met=False,
                detail="meta pivot action does not match the active pivot state",
            )
        if self._meta_pivot_executor is None:
            return ControlActionResult(
                postcondition_met=False,
                detail="meta pivot has no execution authority",
            )
        pivot.status = MetaPivotStatus.EXECUTING
        pivot.failure_reason = ""
        self._emit(
            "meta_pivot_executing",
            pivot.model_dump(mode="json"),
        )
        self.persist()
        attempts = await self._run_meta_pivot_mechanisms(pivot)
        outcome = self._summarize_meta_pivot_attempts(pivot, attempts)
        self.state.meta_pivot_outcomes[pivot.pivot_id] = outcome
        self._materialize_meta_pivot_tasks(
            pivot,
            outcome,
            current_round=self._meta_pivot_execution_round,
        )
        pivot.created_route_ids = list(outcome.new_route_ids)
        pivot.result_fact_ids = list(outcome.new_fact_ids)
        pivot.result_obligation_ids = list(outcome.new_obligation_ids)
        pivot.revised_strategy_ids = list(outcome.revised_strategy_ids)
        pivot.new_task_ids = list(outcome.new_task_ids)
        pivot.new_counterexample_ids = list(outcome.new_counterexample_ids)
        pivot.changed_route_ids = list(outcome.changed_route_ids)
        result_refs = list(
            dict.fromkeys(
                [
                    pivot.pivot_id,
                    *outcome.new_route_ids,
                    *outcome.revised_strategy_ids,
                    *outcome.new_obligation_ids,
                    *outcome.new_task_ids,
                    *outcome.new_fact_ids,
                    *outcome.new_counterexample_ids,
                    *outcome.changed_route_ids,
                    *outcome.wake_condition_ids,
                ]
            )
        )
        action.result_refs = result_refs

        if outcome.effect == MetaPivotEffect.EFFECTIVE:
            pivot.status = MetaPivotStatus.EXECUTED
            pivot.executed_round = self._meta_pivot_execution_round
            pivot.failure_reason = ""
            self._emit(
                "meta_pivot_executed",
                {
                    **pivot.model_dump(mode="json"),
                    "outcome": outcome.model_dump(mode="json"),
                },
            )
            self.persist()
            return ControlActionResult(
                result_refs=result_refs,
                postcondition_met=True,
            )

        if outcome.effect == MetaPivotEffect.DEFERRED:
            pivot.status = MetaPivotStatus.ADMITTED
            pivot.executed_round = None
            pivot.failure_reason = ""
            self.action_dispatcher.defer(
                action.action_id,
                reason=outcome.reason,
            )
            self._emit(
                "meta_pivot_deferred",
                {
                    **pivot.model_dump(mode="json"),
                    "outcome": outcome.model_dump(mode="json"),
                },
            )
            self.persist()
            return ControlActionResult(
                result_refs=result_refs,
                postcondition_met=True,
                detail=outcome.reason,
            )

        pivot.status = MetaPivotStatus.FAILED
        pivot.executed_round = None
        pivot.failure_reason = outcome.reason
        self._emit(
            "meta_pivot_failed",
            {
                **pivot.model_dump(mode="json"),
                "outcome": outcome.model_dump(mode="json"),
            },
        )
        self.persist()
        return ControlActionResult(
            result_refs=result_refs,
            postcondition_met=False,
            detail=outcome.reason,
        )

    async def _run_meta_pivot_mechanisms(
        self,
        pivot: MetaPivotState,
    ) -> list[Mapping[str, Any]]:
        executor = self._meta_pivot_executor
        if executor is None:
            return [
                {
                    "mechanism": "unavailable",
                    "effect": MetaPivotEffect.FAILED.value,
                    "reason": "meta pivot has no execution authority",
                }
            ]
        mechanisms = list(pivot.requested_mechanisms) or ["meta_replan"]
        try:
            signature = inspect.signature(executor)
            signature.bind(pivot, mechanisms[0])
            accepts_mechanism = True
        except (TypeError, ValueError):
            accepts_mechanism = False

        if not accepts_mechanism:
            try:
                raw_result = executor(pivot)
                if inspect.isawaitable(raw_result):
                    raw_result = await raw_result
            except Exception as exc:
                return [
                    {
                        "mechanism": mechanisms[0],
                        "effect": MetaPivotEffect.FAILED.value,
                        "reason": f"{type(exc).__name__}: {exc}",
                    }
                ]
            if not isinstance(raw_result, Mapping):
                return [
                    {
                        "mechanism": mechanisms[0],
                        "effect": MetaPivotEffect.FAILED.value,
                        "reason": "meta pivot executor returned a non-mapping result",
                    }
                ]
            raw_attempts = raw_result.get("attempts")
            if isinstance(raw_attempts, Sequence) and not isinstance(
                raw_attempts, (str, bytes)
            ):
                attempts: list[Mapping[str, Any]] = []
                for index, item in enumerate(raw_attempts):
                    mechanism = (
                        mechanisms[index]
                        if index < len(mechanisms)
                        else f"attempt_{index + 1}"
                    )
                    if not isinstance(item, Mapping):
                        attempts.append(
                            {
                                "mechanism": mechanism,
                                "effect": MetaPivotEffect.FAILED.value,
                                "reason": "meta pivot attempt was not a mapping",
                            }
                        )
                        continue
                    attempts.append({"mechanism": mechanism, **dict(item)})
                return attempts
            return [{"mechanism": mechanisms[0], **dict(raw_result)}]

        attempts = []
        for mechanism in mechanisms:
            try:
                raw_result = executor(pivot, mechanism)
                if inspect.isawaitable(raw_result):
                    raw_result = await raw_result
                if not isinstance(raw_result, Mapping):
                    raise TypeError(
                        "meta pivot mechanism returned a non-mapping result"
                    )
                attempt = {"mechanism": mechanism, **dict(raw_result)}
            except Exception as exc:
                attempt = {
                    "mechanism": mechanism,
                    "effect": MetaPivotEffect.FAILED.value,
                    "reason": f"{type(exc).__name__}: {exc}",
                }
            attempts.append(attempt)
            if (
                str(attempt.get("effect", "")).casefold()
                == MetaPivotEffect.EFFECTIVE.value
                and self._meta_pivot_material_refs(attempt)
            ):
                break
        return attempts

    def _summarize_meta_pivot_attempts(
        self,
        pivot: MetaPivotState,
        attempts: Sequence[Mapping[str, Any]],
    ) -> MetaPivotOutcome:
        attempted_mechanisms: list[str] = []
        completed_mechanisms: list[str] = []
        unavailable_mechanisms: dict[str, str] = {}
        collected: dict[str, list[str]] = {
            "new_route_ids": [],
            "revised_strategy_ids": [],
            "new_obligation_ids": [],
            "new_task_ids": [],
            "new_fact_ids": [],
            "new_counterexample_ids": [],
            "changed_route_ids": [],
            "wake_condition_ids": [],
        }
        reasons: list[str] = []
        deferred = False
        explicit_failures = 0

        for index, attempt in enumerate(attempts):
            mechanism = str(
                attempt.get(
                    "mechanism",
                    pivot.requested_mechanisms[index]
                    if index < len(pivot.requested_mechanisms)
                    else f"attempt_{index + 1}",
                )
            )
            if mechanism not in attempted_mechanisms:
                attempted_mechanisms.append(mechanism)
            reason = str(attempt.get("reason", "")).strip()
            if reason:
                reasons.append(f"{mechanism}: {reason}")
            effect = str(attempt.get("effect", "")).casefold()
            refs = self._meta_pivot_material_refs(attempt)

            for field_name, values in refs.items():
                collected[field_name].extend(values)
            collected["wake_condition_ids"].extend(
                self._meta_pivot_id_values(attempt, "wake_condition_ids")
            )

            if effect == MetaPivotEffect.DEFERRED.value:
                deferred = True
                unavailable_mechanisms[mechanism] = reason or "mechanism deferred"
                continue
            if effect == MetaPivotEffect.FAILED.value:
                explicit_failures += 1
                unavailable_mechanisms[mechanism] = reason or "mechanism failed"
                continue
            if effect in {"unavailable", MetaPivotEffect.EMPTY.value}:
                unavailable_mechanisms[mechanism] = (
                    reason or "mechanism produced no material state"
                )
                continue
            if any(refs.values()):
                completed_mechanisms.append(mechanism)
                break
            unavailable_mechanisms[mechanism] = (
                reason or "mechanism produced no material state"
            )

        collected = {
            key: list(dict.fromkeys(values)) for key, values in collected.items()
        }
        if completed_mechanisms:
            effect = MetaPivotEffect.EFFECTIVE
            reason = reasons[-1] if reasons else "meta pivot changed proof state"
        elif deferred and collected["wake_condition_ids"]:
            effect = MetaPivotEffect.DEFERRED
            reason = reasons[-1] if reasons else "meta pivot is waiting to wake"
        elif explicit_failures and explicit_failures == len(attempted_mechanisms):
            effect = MetaPivotEffect.FAILED
            reason = reasons[-1] if reasons else "all meta pivot mechanisms failed"
        else:
            effect = MetaPivotEffect.EMPTY
            reason = (
                "; ".join(reasons)
                if reasons
                else "all meta pivot mechanisms produced no material state"
            )
        return MetaPivotOutcome(
            pivot_id=pivot.pivot_id,
            effect=effect,
            attempted_mechanisms=attempted_mechanisms,
            completed_mechanisms=completed_mechanisms,
            unavailable_mechanisms=unavailable_mechanisms,
            new_route_ids=collected["new_route_ids"],
            revised_strategy_ids=collected["revised_strategy_ids"],
            new_obligation_ids=collected["new_obligation_ids"],
            new_task_ids=collected["new_task_ids"],
            new_fact_ids=collected["new_fact_ids"],
            new_counterexample_ids=collected["new_counterexample_ids"],
            changed_route_ids=collected["changed_route_ids"],
            wake_condition_ids=collected["wake_condition_ids"],
            reason=reason,
        )

    def _materialize_meta_pivot_tasks(
        self,
        pivot: MetaPivotState,
        outcome: MetaPivotOutcome,
        *,
        current_round: int,
    ) -> None:
        for task_id in outcome.new_task_ids:
            if task_id in self.state.executable_tasks:
                continue
            wake_conditions = (
                [
                    WakeCondition(
                        condition_id=condition_id,
                        kind=WakeConditionKind.USER_INTERVENTION,
                        earliest_round=current_round + 1,
                    )
                    for condition_id in outcome.wake_condition_ids
                ]
                if outcome.effect == MetaPivotEffect.DEFERRED
                else []
            )
            status = (
                TaskStatus.DEFERRED
                if outcome.effect == MetaPivotEffect.DEFERRED
                else TaskStatus.READY
            )
            self.state.executable_tasks[task_id] = ExecutableTaskRecord(
                task_id=task_id,
                task_kind="meta_pivot_step",
                status=status,
                registered_handler="meta_pivot_executor",
                explicit_prompt_ref=f"meta_pivot:{pivot.pivot_id}",
                wake_conditions=wake_conditions,
                created_round=current_round,
                last_transition_round=current_round,
                expires_round=current_round + 4,
                transition_history=[
                    {
                        "from": None,
                        "to": status.value,
                        "round": current_round,
                        "reason": "meta_pivot_materialized_task",
                    }
                ],
            )

    @classmethod
    def _meta_pivot_material_refs(
        cls,
        result: Mapping[str, Any],
    ) -> dict[str, list[str]]:
        return {
            "new_route_ids": cls._meta_pivot_id_values(
                result, "new_route_ids", "created_route_ids"
            ),
            "revised_strategy_ids": cls._meta_pivot_id_values(
                result, "revised_strategy_ids"
            ),
            "new_obligation_ids": cls._meta_pivot_id_values(
                result, "new_obligation_ids", "result_obligation_ids"
            ),
            "new_task_ids": cls._meta_pivot_id_values(result, "new_task_ids"),
            "new_fact_ids": cls._meta_pivot_id_values(
                result, "new_fact_ids", "result_fact_ids"
            ),
            "new_counterexample_ids": cls._meta_pivot_id_values(
                result, "new_counterexample_ids"
            ),
            "changed_route_ids": cls._meta_pivot_id_values(result, "changed_route_ids"),
        }

    @staticmethod
    def _meta_pivot_id_values(
        result: Mapping[str, Any],
        *field_names: str,
    ) -> list[str]:
        values: list[str] = []
        for field_name in field_names:
            raw_values = result.get(field_name, [])
            if isinstance(raw_values, (str, bytes)):
                raw_values = [raw_values]
            if not isinstance(raw_values, Sequence):
                continue
            values.extend(
                str(value).strip() for value in raw_values if str(value).strip()
            )
        return list(dict.fromkeys(values))

    def _meta_pivot_postcondition(
        self,
        action: ControlActionRecord,
        result: ControlActionResult,
    ) -> bool:
        pivot = self.state.meta_pivot_state
        outcome = (
            self.state.meta_pivot_outcomes.get(pivot.pivot_id)
            if pivot is not None
            else None
        )
        if outcome is not None:
            material_refs = {
                *outcome.new_route_ids,
                *outcome.revised_strategy_ids,
                *outcome.new_obligation_ids,
                *outcome.new_task_ids,
                *outcome.new_fact_ids,
                *outcome.new_counterexample_ids,
                *outcome.changed_route_ids,
            }
            return (
                outcome.effect == MetaPivotEffect.EFFECTIVE
                and bool(material_refs)
                and pivot is not None
                and pivot.status == MetaPivotStatus.EXECUTED
                and pivot.action_id == action.action_id
                and pivot.pivot_id in result.result_refs
                and material_refs.issubset(result.result_refs)
            )
        return (
            pivot is not None
            and pivot.status == MetaPivotStatus.EXECUTED
            and pivot.action_id == action.action_id
            and pivot.pivot_id in result.result_refs
            and bool(
                pivot.created_route_ids
                or pivot.result_fact_ids
                or pivot.result_obligation_ids
                or pivot.revised_strategy_ids
                or pivot.new_task_ids
                or pivot.new_counterexample_ids
                or pivot.changed_route_ids
            )
            and set(pivot.created_route_ids).issubset(result.result_refs)
            and set(pivot.result_fact_ids).issubset(result.result_refs)
            and set(pivot.result_obligation_ids).issubset(result.result_refs)
        )

    def _handle_activate_induction_measure(
        self, action: ControlActionRecord
    ) -> ControlActionResult:
        if not bool(action.payload.get("approved")):
            return ControlActionResult(
                detail="induction measure was not approved by an independent review"
            )
        proposal_id = str(action.payload["proposal_id"])
        proposal = self.state.induction_measures[proposal_id]
        reviewer_agent_id = str(action.payload.get("reviewer_agent_id", "")).strip()
        review_evidence_ids = list(
            dict.fromkeys(action.payload.get("review_evidence_ids", []))
        )
        if not reviewer_agent_id or not review_evidence_ids:
            return ControlActionResult(
                detail="induction activation lacks independent review evidence"
            )
        if not self.induction.validate_well_foundedness(proposal):
            return ControlActionResult(
                detail="induction measure failed deterministic well-foundedness checks"
            )
        if set(action.target_obligation_ids) != set(proposal.target_obligation_ids):
            return ControlActionResult(
                detail="induction activation target does not match the proposal"
            )
        for target_id in proposal.target_obligation_ids:
            target = self.proof_graph.get_obligation(target_id)
            if target.status not in {"open", "tentative", "blocked"}:
                return ControlActionResult(
                    detail="induction activation target is no longer open"
                )
        blueprint_node_id = (
            "induction_blueprint_"
            + stable_hash(
                {
                    "problem_hash": self.proof_graph.problem_hash,
                    "proposal_id": proposal.proposal_id,
                    "route_id": proposal.route_id,
                    "target_obligation_ids": proposal.target_obligation_ids,
                }
            )[:16]
        )
        node = InductionBlueprintNode(
            blueprint_node_id=blueprint_node_id,
            proposal_id=proposal.proposal_id,
            route_id=proposal.route_id,
            target_obligation_ids=list(proposal.target_obligation_ids),
            measure_name=proposal.measure_name,
            well_founded_domain=proposal.well_founded_domain,
            base_cases=list(proposal.base_cases),
            induction_step_relation=proposal.induction_step_relation,
            strict_decrease_argument=proposal.strict_decrease_argument,
            prohibited_circularity=(
                "Do not invoke the target at the same or a larger measure."
            ),
            reviewer_agent_id=reviewer_agent_id,
            review_evidence_ids=review_evidence_ids,
        )
        self.state.induction_blueprints[node.blueprint_node_id] = node
        proposal.status = "accepted"
        proposal.reviewer_agent_id = reviewer_agent_id
        proposal.review_evidence_ids = review_evidence_ids
        proposal.rejection_reason = ""
        proposal.activation_action_id = action.action_id
        proposal.blueprint_node_id = node.blueprint_node_id
        self._emit(
            "induction_measure_activated",
            {
                "action_id": action.action_id,
                "proposal_id": proposal.proposal_id,
                "blueprint_node_id": node.blueprint_node_id,
                "route_id": node.route_id,
                "target_obligation_ids": node.target_obligation_ids,
            },
        )
        return ControlActionResult(
            result_refs=[proposal.proposal_id, node.blueprint_node_id],
            postcondition_met=True,
        )

    def _induction_activation_postcondition(
        self,
        action: ControlActionRecord,
        result: ControlActionResult,
    ) -> bool:
        proposal_id = str(action.payload.get("proposal_id", ""))
        proposal = self.state.induction_measures.get(proposal_id)
        if (
            proposal is None
            or proposal.status != "accepted"
            or proposal.activation_action_id != action.action_id
            or proposal.blueprint_node_id is None
        ):
            return False
        node = self.state.induction_blueprints.get(proposal.blueprint_node_id)
        return (
            node is not None
            and node.status == "active"
            and node.proposal_id == proposal.proposal_id
            and node.route_id == proposal.route_id
            and set(node.target_obligation_ids) == set(proposal.target_obligation_ids)
            and node.blueprint_node_id in result.result_refs
        )

    def _handle_weaken_target(self, action: ControlActionRecord) -> ControlActionResult:
        proposal_id = str(action.payload["proposal_id"])
        proposal = self.state.minimal_bridge_proposals[proposal_id]
        binding_id = action.payload.get("binding_id")
        route_ids = list(action.route_ids)
        obligation_id = (
            "obl_weaker_target_"
            + stable_hash(
                {
                    "problem_hash": self.proof_graph.problem_hash,
                    "proposal_id": proposal.proposal_id,
                    "statement": normalize_text(proposal.candidate_statement),
                }
            )[:16]
        )
        candidate = ProofObligation(
            obligation_id=obligation_id,
            problem_hash=self.proof_graph.problem_hash,
            route_ids=route_ids,
            kind=ObligationKind.SUBGOAL,
            statement=proposal.candidate_statement,
            normalized_statement=normalize_text(proposal.candidate_statement),
            priority=0.85,
            centrality=0.75,
        )
        source = (
            self.proof_graph.get_obligation(proposal.overstrong_subject_id)
            if self._control_obligation_exists(proposal.overstrong_subject_id)
            else None
        )
        quality = self._assess_obligation_quality(
            candidate,
            source_kind="generated_bridge",
            source_statement=source.normalized_statement
            if source is not None
            else None,
        )
        if not quality.accepted:
            proposal.status = "rejected"
            return ControlActionResult(
                postcondition_met=False,
                detail=(
                    "weaker target failed semantic quality: "
                    + ", ".join(quality.rejection_reasons)
                ),
            )
        materialized = self.proof_graph.add_obligation(candidate)
        result_refs = [materialized.obligation_id]
        if binding_id is not None:
            binding = self.state.route_target_bindings[str(binding_id)]
            updated = binding.model_copy(
                update={
                    "direct_target_obligation_id": materialized.obligation_id,
                    "ancestor_obligation_ids": [],
                    "bridge_obligation_ids": [],
                    "relation_to_direct_target": GoalRelation.SUFFICIENT,
                    "relation_to_main_goal": GoalRelation.NECESSARY_ONLY,
                    "scope_relation_to_direct_target": ScopeRelation.SAME,
                    "blueprint_path_complete": False,
                }
            )
            bind_action = self.action_dispatcher.propose(
                ControlActionType.BIND_ROUTE_TARGET,
                source_record_ids=[proposal.proposal_id],
                route_ids=[updated.route_id] if updated.route_id is not None else [],
                target_obligation_ids=[
                    updated.direct_target_obligation_id,
                    updated.main_goal_obligation_id,
                ],
                payload=updated.model_dump(mode="json"),
                current_round=action.created_round,
            )
            bound = self.action_dispatcher.execute_sync(
                bind_action.action_id,
                current_round=action.created_round,
            )
            if bound.status != ControlActionStatus.EXECUTED:
                return ControlActionResult(
                    postcondition_met=False,
                    detail="weaker target could not be bound to the route",
                )
            result_refs.extend(bound.result_refs)
        proposal.status = "reviewed"
        proposal.action_id = action.action_id
        proposal.materialized_obligation_id = materialized.obligation_id
        return ControlActionResult(
            result_refs=result_refs,
            postcondition_met=True,
        )

    def _weaken_target_postcondition(
        self,
        action: ControlActionRecord,
        result: ControlActionResult,
    ) -> bool:
        proposal = self.state.minimal_bridge_proposals.get(
            str(action.payload.get("proposal_id", ""))
        )
        if (
            proposal is None
            or proposal.status != "reviewed"
            or proposal.materialized_obligation_id is None
            or proposal.materialized_obligation_id not in result.result_refs
        ):
            return False
        try:
            obligation = self.proof_graph.get_obligation(
                proposal.materialized_obligation_id
            )
        except KeyError:
            return False
        quality = self.state.obligation_semantic_quality.get(obligation.obligation_id)
        return (
            obligation.kind != ObligationKind.MAIN_GOAL
            and quality is not None
            and quality.accepted
        )

    def _handle_create_minimal_bridge(
        self, action: ControlActionRecord
    ) -> ControlActionResult:
        proposal_id = str(action.payload["proposal_id"])
        proposal = self.state.minimal_bridge_proposals[proposal_id]
        dependency_ids = [
            obligation_id
            for obligation_id in proposal.required_bridge_obligation_ids
            if self._control_obligation_exists(obligation_id)
            and obligation_id != proposal.target_obligation_id
        ]
        obligation_id = (
            "obl_minimal_bridge_"
            + stable_hash(
                {
                    "problem_hash": self.proof_graph.problem_hash,
                    "proposal_id": proposal.proposal_id,
                    "statement": normalize_text(proposal.candidate_statement),
                    "target": proposal.target_obligation_id,
                    "dependencies": dependency_ids,
                }
            )[:16]
        )
        candidate = ProofObligation(
            obligation_id=obligation_id,
            problem_hash=self.proof_graph.problem_hash,
            route_ids=list(action.route_ids),
            kind=ObligationKind.SUBGOAL,
            statement=proposal.candidate_statement,
            normalized_statement=normalize_text(proposal.candidate_statement),
            dependency_ids=dependency_ids,
            priority=0.9,
            centrality=0.85,
        )
        target = (
            self.proof_graph.get_obligation(proposal.target_obligation_id)
            if self._control_obligation_exists(proposal.target_obligation_id)
            else None
        )
        quality = self._assess_obligation_quality(
            candidate,
            source_kind="generated_bridge",
            source_statement=target.normalized_statement
            if target is not None
            else None,
        )
        if not quality.accepted:
            proposal.status = "rejected"
            return ControlActionResult(
                postcondition_met=False,
                detail=(
                    "minimal bridge failed semantic quality: "
                    + ", ".join(quality.rejection_reasons)
                ),
            )
        materialized = self.proof_graph.add_obligation(candidate)
        self._ensure_dependency_edge(
            proposal.target_obligation_id,
            materialized.obligation_id,
        )
        result_refs = [materialized.obligation_id]
        binding_id = action.payload.get("binding_id")
        if binding_id is not None:
            binding = self.state.route_target_bindings[str(binding_id)]
            updated = binding.model_copy(
                update={
                    "ancestor_obligation_ids": [
                        materialized.obligation_id,
                        binding.main_goal_obligation_id,
                    ],
                    "bridge_obligation_ids": list(
                        dict.fromkeys(
                            [
                                *binding.bridge_obligation_ids,
                                materialized.obligation_id,
                            ]
                        )
                    ),
                    "blueprint_path_complete": True,
                }
            )
            bind_action = self.action_dispatcher.propose(
                ControlActionType.BIND_ROUTE_TARGET,
                source_record_ids=[proposal.proposal_id],
                route_ids=[updated.route_id] if updated.route_id is not None else [],
                target_obligation_ids=[
                    updated.direct_target_obligation_id,
                    updated.main_goal_obligation_id,
                ],
                payload=updated.model_dump(mode="json"),
                current_round=action.created_round,
            )
            bound = self.action_dispatcher.execute_sync(
                bind_action.action_id,
                current_round=action.created_round,
            )
            if bound.status != ControlActionStatus.EXECUTED:
                return ControlActionResult(
                    postcondition_met=False,
                    detail="minimal bridge could not update the route target binding",
                )
            result_refs.extend(bound.result_refs)
        proposal.status = "accepted"
        proposal.action_id = action.action_id
        proposal.materialized_obligation_id = materialized.obligation_id
        self._emit(
            "minimal_bridge_materialized",
            {
                "action_id": action.action_id,
                "proposal_id": proposal.proposal_id,
                "obligation_id": materialized.obligation_id,
                "target_obligation_id": proposal.target_obligation_id,
            },
        )
        return ControlActionResult(
            result_refs=result_refs,
            postcondition_met=True,
        )

    def _minimal_bridge_postcondition(
        self,
        action: ControlActionRecord,
        result: ControlActionResult,
    ) -> bool:
        proposal = self.state.minimal_bridge_proposals.get(
            str(action.payload.get("proposal_id", ""))
        )
        if (
            proposal is None
            or proposal.status != "accepted"
            or proposal.materialized_obligation_id is None
            or proposal.materialized_obligation_id not in result.result_refs
        ):
            return False
        try:
            obligation = self.proof_graph.get_obligation(
                proposal.materialized_obligation_id
            )
        except KeyError:
            return False
        quality = self.state.obligation_semantic_quality.get(obligation.obligation_id)
        return (
            obligation.kind != ObligationKind.MAIN_GOAL
            and obligation.status in {"open", "tentative", "blocked"}
            and quality is not None
            and quality.accepted
        )

    def _handle_rewrite_blueprint(
        self, action: ControlActionRecord
    ) -> ControlActionResult:
        request_id = str(action.payload["blueprint_rewrite_request_id"])
        request = self.state.blueprint_rewrites[request_id]
        binding_id = action.payload.get("binding_id")
        binding = (
            self.state.route_target_bindings[str(binding_id)]
            if binding_id is not None
            else None
        )
        strategy_id = str(action.payload.get("strategy_id", ""))
        archive_entry = self.state.original_strategy_archive.get(strategy_id)
        if archive_entry is None:
            request.status = "failed"
            request.failure_reason = (
                "blueprint rewrite requires an archived original StrategyCard"
            )
            return ControlActionResult(
                postcondition_met=False,
                detail=request.failure_reason,
            )
        if (
            binding is not None
            and binding.direct_target_obligation_id == binding.main_goal_obligation_id
        ):
            request.status = "failed"
            request.failure_reason = "self-implication rewrite is not permitted"
            self._emit(
                "rewrite_semantic_rejected",
                {
                    "request_id": request.request_id,
                    "strategy_id": strategy_id,
                    "reason": request.failure_reason,
                },
            )
            return ControlActionResult(
                postcondition_met=False,
                detail=request.failure_reason,
            )
        original_strategy = archive_entry.strategy
        main_goal = (
            self.proof_graph.get_obligation(binding.main_goal_obligation_id)
            if binding is not None
            else self._main_goal()
        )
        if main_goal is None:
            request.status = "failed"
            request.failure_reason = "rewrite has no frozen main goal"
            return ControlActionResult(
                postcondition_met=False,
                detail=request.failure_reason,
            )
        added_statements: list[str] = []
        for value in [
            *request.proposed_weaker_targets,
            *request.proposed_bridge_obligation_ids,
        ]:
            if self._control_obligation_exists(value):
                added_statements.append(
                    self.proof_graph.get_obligation(value).normalized_statement
                )
            elif value.strip():
                added_statements.append(value.strip())
        if binding is not None:
            direct = self.proof_graph.get_obligation(
                binding.direct_target_obligation_id
            )
            bridge_statement = (
                f"If {direct.normalized_statement}, then "
                f"{main_goal.normalized_statement}."
            )
            if obligation_identity_text(direct.normalized_statement) == (
                obligation_identity_text(main_goal.normalized_statement)
            ):
                request.status = "failed"
                request.failure_reason = "self-implication rewrite is not permitted"
                return ControlActionResult(
                    postcondition_met=False,
                    detail=request.failure_reason,
                )
            added_statements.append(bridge_statement)
        added_statements = list(dict.fromkeys(added_statements))
        revised_strategy = original_strategy.model_copy(
            update={
                "strategy_id": (
                    "strategy_revision_"
                    + stable_hash(
                        {
                            "request_id": request.request_id,
                            "parent": original_strategy.strategy_id,
                            "statements": added_statements,
                        }
                    )[:16]
                ),
                "core_idea": (
                    original_strategy.core_idea
                    + " Preserve that mechanism while proving the explicit "
                    "intermediate implication."
                ),
                "expected_lemmas": list(
                    dict.fromkeys(
                        [*original_strategy.expected_lemmas, *added_statements]
                    )
                ),
                "bottleneck": (
                    added_statements[-1]
                    if added_statements
                    else original_strategy.bottleneck
                ),
                "parent_strategy_ids": list(
                    dict.fromkeys(
                        [
                            *original_strategy.parent_strategy_ids,
                            original_strategy.strategy_id,
                        ]
                    )
                ),
            }
        )
        semantic_result = self.rewrite_semantic_gate.revise_from_claims(
            rewrite_request_id=request.request_id,
            original_strategy=original_strategy,
            candidate_strategy=revised_strategy,
            main_goal=main_goal,
            retained_claim_statements=original_strategy.expected_lemmas,
            added_claim_statements=added_statements,
        )
        self.state.rewrite_semantic_assessments[request.request_id] = (
            semantic_result.semantic_assessment
        )
        self.state.strategy_lineage[semantic_result.lineage.strategy_id] = (
            semantic_result.lineage
        )
        if semantic_result.semantic_assessment.verdict != RewriteSemanticVerdict.VALID:
            request.status = "failed"
            request.failure_reason = "; ".join(
                semantic_result.semantic_assessment.reasons
            )
            self.state.strategy_lineage[strategy_id].status = "needs_rewrite"
            self._emit(
                "rewrite_semantic_rejected",
                {
                    "request_id": request.request_id,
                    "strategy_id": strategy_id,
                    "verdict": semantic_result.semantic_assessment.verdict.value,
                    "reasons": semantic_result.semantic_assessment.reasons,
                },
            )
            return ControlActionResult(
                postcondition_met=False,
                detail=request.failure_reason,
            )
        self.state.revised_strategy_results[request.request_id] = semantic_result
        self.strategy_archive.register_child(
            semantic_result.revised_strategy,
            parent_strategy_id=original_strategy.strategy_id,
            reason=StrategyRevisionReason.ADMISSION_REWRITE,
        )
        self._emit(
            "revised_strategy_created",
            {
                "request_id": request.request_id,
                "strategy_id": semantic_result.revised_strategy.strategy_id,
                "parent_strategy_id": original_strategy.strategy_id,
            },
        )
        result_refs = [
            request.request_id,
            semantic_result.revised_strategy.strategy_id,
        ]
        historical_targets = [
            target_id
            for target_id in (
                [binding.direct_target_obligation_id] if binding is not None else []
            )
            if target_id
        ]
        request.historical_target_obligation_ids = list(
            dict.fromkeys(
                [*request.historical_target_obligation_ids, *historical_targets]
            )
        )

        if request.current_overstrong_targets and request.proposed_weaker_targets:
            candidate = next(
                (
                    value
                    for value in request.proposed_weaker_targets
                    if not self._control_obligation_exists(value)
                ),
                None,
            )
            if candidate:
                weaker = MinimalBridgeProposal(
                    proposal_id="weaker_target_"
                    + stable_hash((request.request_id, candidate))[:16],
                    overstrong_subject_id=request.current_overstrong_targets[0],
                    target_obligation_id=(
                        binding.main_goal_obligation_id
                        if binding is not None
                        else action.target_obligation_ids[-1]
                    ),
                    candidate_statement=candidate,
                    relation_to_original="strictly_weaker",
                )
                self.state.minimal_bridge_proposals[weaker.proposal_id] = weaker
                weaken_action = self.action_dispatcher.propose(
                    ControlActionType.WEAKEN_TARGET,
                    source_record_ids=[weaker.proposal_id, request.request_id],
                    route_ids=list(action.route_ids),
                    target_obligation_ids=[weaker.target_obligation_id],
                    payload={
                        "proposal_id": weaker.proposal_id,
                        "binding_id": binding_id,
                    },
                    current_round=action.created_round,
                )
                weakened = self.action_dispatcher.execute_sync(
                    weaken_action.action_id,
                    current_round=action.created_round,
                )
                if weakened.status != ControlActionStatus.EXECUTED:
                    return ControlActionResult(
                        detail="blueprint rewrite could not materialize weaker target"
                    )
                result_refs.extend(weakened.result_refs)
                if binding_id is not None:
                    binding = self.state.route_target_bindings[str(binding_id)]

        bridge_ids = [
            obligation_id
            for obligation_id in request.proposed_bridge_obligation_ids
            if self._control_obligation_exists(obligation_id)
        ]
        if not bridge_ids:
            if binding is not None:
                direct = self.proof_graph.get_obligation(
                    binding.direct_target_obligation_id
                )
                main = self.proof_graph.get_obligation(binding.main_goal_obligation_id)
                dependencies = (
                    [direct.obligation_id]
                    if direct.obligation_id != main.obligation_id
                    else []
                )
                bridge_statement = (
                    f"If {direct.normalized_statement}, then "
                    f"{main.normalized_statement}."
                )
                target_id = main.obligation_id
            else:
                if not action.target_obligation_ids:
                    request.status = "failed"
                    request.failure_reason = (
                        "blueprint rewrite has no auditable target obligation"
                    )
                    return ControlActionResult(
                        postcondition_met=False,
                        detail=request.failure_reason,
                    )
                target_id = action.target_obligation_ids[-1]
                target = self.proof_graph.get_obligation(target_id)
                dependencies = []
                bridge_statement = (
                    "Establish a reviewed intermediate implication sufficient for "
                    f"{target.normalized_statement}."
                )
            proposal = MinimalBridgeProposal(
                proposal_id="minimal_bridge_"
                + stable_hash(
                    {
                        "request_id": request.request_id,
                        "target_id": target_id,
                        "dependencies": dependencies,
                    }
                )[:16],
                overstrong_subject_id=str(action.payload.get("strategy_id", "")),
                target_obligation_id=target_id,
                candidate_statement=bridge_statement,
                relation_to_original="strictly_weaker",
                implication_outline=[*dependencies, target_id],
                required_bridge_obligation_ids=dependencies,
            )
            bridge_action = self.materialize_minimal_bridge(
                proposal,
                route_id=action.route_ids[0] if action.route_ids else None,
                binding_id=str(binding_id) if binding_id is not None else None,
                current_round=action.created_round,
            )
            if bridge_action.status != ControlActionStatus.EXECUTED:
                return ControlActionResult(
                    postcondition_met=False,
                    detail="blueprint rewrite could not materialize a minimal bridge",
                )
            bridge_ids = [
                result_ref
                for result_ref in bridge_action.result_refs
                if self._control_obligation_exists(result_ref)
            ]
            result_refs.extend(bridge_action.result_refs)
            request.proposed_bridge_obligation_ids = list(
                dict.fromkeys([*request.proposed_bridge_obligation_ids, *bridge_ids])
            )

        route = None
        if self._control_route_exists(request.route_id):
            route = self.route_registry.get(request.route_id)
        self.blueprint_rewriter.apply_reviewed_rewrite(
            request,
            approved=True,
            route=route,
            review_evidence={
                "control_action_id": action.action_id,
                "source_record_ids": action.source_record_ids,
            },
        )
        request.execution_action_id = action.action_id
        request.result_obligation_ids = list(
            dict.fromkeys([*request.result_obligation_ids, *bridge_ids])
        )
        request.failure_reason = ""
        self._emit(
            "blueprint_rewrite_executed",
            {
                "action_id": action.action_id,
                "request_id": request.request_id,
                "result_obligation_ids": request.result_obligation_ids,
            },
        )
        return ControlActionResult(
            result_refs=list(dict.fromkeys(result_refs)),
            postcondition_met=True,
        )

    def _blueprint_rewrite_postcondition(
        self,
        action: ControlActionRecord,
        result: ControlActionResult,
    ) -> bool:
        request = self.state.blueprint_rewrites.get(
            str(action.payload.get("blueprint_rewrite_request_id", ""))
        )
        if (
            request is None
            or request.status != "executed"
            or request.execution_action_id != action.action_id
            or request.request_id not in result.result_refs
        ):
            return False
        semantic = self.state.rewrite_semantic_assessments.get(request.request_id)
        revised = self.state.revised_strategy_results.get(request.request_id)
        if (
            semantic is None
            or semantic.verdict != RewriteSemanticVerdict.VALID
            or revised is None
            or revised.revised_strategy.strategy_id not in result.result_refs
            or revised.lineage.parent_strategy_id is None
            or not revised.first_executable_obligation_id
        ):
            return False
        materialized = [
            obligation_id
            for obligation_id in request.result_obligation_ids
            if self._control_obligation_exists(obligation_id)
        ]
        binding_id = action.payload.get("binding_id")
        binding_complete = (
            binding_id is not None
            and str(binding_id) in self.state.route_target_bindings
            and self.state.route_target_bindings[
                str(binding_id)
            ].blueprint_path_complete
        )
        route_revised = (
            self._control_route_exists(request.route_id)
            and self.route_registry.get(request.route_id).requires_revision
        )
        return bool(materialized or binding_complete or route_revised)

    def _ensure_route_admission_rewrite(
        self,
        strategy: StrategyCard,
        *,
        link: ClaimGoalLink,
        binding: RouteTargetBinding | None,
        contract: GoalAlignmentContractResult | None,
        record: RouteAdmissionRecord,
    ) -> None:
        if record.rewrite_request_id is None:
            raise RuntimeError("REWRITE verdict requires a rewrite request ID")
        request = self.state.blueprint_rewrites.get(record.rewrite_request_id)
        if request is None:
            route_id = (
                binding.route_id
                if binding is not None and binding.route_id is not None
                else f"strategy:{strategy.strategy_id}"
            )
            request = BlueprintRewriteRequest(
                request_id=record.rewrite_request_id,
                route_id=route_id,
                failure_record_id=(
                    contract.contract_id if contract is not None else link.link_id
                ),
                preserved_fact_ids=[
                    item.message_id for item in self.message_broker.admitted_facts()
                ],
                preserved_step_ids=[],
                invalidated_plan_elements=[link.target_obligation_id],
                current_overstrong_targets=(
                    [link.subject_id]
                    if link.scope_relation == ScopeRelation.CLAIM_STRONGER
                    else []
                ),
                proposed_weaker_targets=(
                    [binding.direct_target_obligation_id] if binding is not None else []
                ),
                proposed_bridge_obligation_ids=(
                    binding.bridge_obligation_ids
                    if binding is not None
                    else link.required_bridge_obligation_ids
                ),
                representation_change_required=False,
            )
            self.state.blueprint_rewrites[request.request_id] = request
            self.blueprint_rewriter.requests[request.request_id] = request
            self._emit(
                "blueprint_rewrite_requested",
                request.model_dump(mode="json"),
            )
        action = self.dispatch_blueprint_rewrite(
            request.request_id,
            strategy_id=strategy.strategy_id,
            binding_id=binding.binding_id if binding is not None else None,
            current_round=0,
        )
        self._emit(
            "control_action_materialized",
            {
                "action_id": action.action_id,
                "action_type": action.action_type.value,
                "source_record_id": request.request_id,
            },
        )

    def _route_target_binding_for_strategy(
        self, strategy_id: str
    ) -> RouteTargetBinding | None:
        stored = next(
            (
                binding
                for binding in self.state.route_target_bindings.values()
                if binding.strategy_id == strategy_id
            ),
            None,
        )
        if stored is not None:
            return stored
        action = next(
            (
                item
                for item in self.state.control_actions.values()
                if item.action_type == ControlActionType.BIND_ROUTE_TARGET
                and item.payload.get("strategy_id") == strategy_id
            ),
            None,
        )
        return (
            RouteTargetBinding.model_validate(action.payload)
            if action is not None
            else None
        )

    def _alignment_contract_for_subject(
        self, subject_id: str
    ) -> GoalAlignmentContractResult | None:
        return next(
            (
                contract
                for contract in reversed(
                    list(self.state.goal_alignment_contracts.values())
                )
                if contract.subject_id == subject_id
            ),
            None,
        )

    def _risk_target_obligation_ids(self, risk: InferenceRiskRecord) -> list[str]:
        if risk.conclusion_id is not None and self._control_obligation_exists(
            risk.conclusion_id
        ):
            conclusion = self.proof_graph.get_obligation(risk.conclusion_id)
            if self._eligible_mathematical_obligation(conclusion):
                return [risk.conclusion_id]
        if risk.route_id is not None:
            try:
                route = self.route_registry.get(risk.route_id)
            except KeyError:
                route = None
            if route is not None:
                binding = self._route_target_binding_for_strategy(route.strategy_id)
                if binding is not None:
                    target = self.proof_graph.get_obligation(
                        binding.direct_target_obligation_id
                    )
                    if self._eligible_mathematical_obligation(target):
                        return [binding.direct_target_obligation_id]
        return self._main_goal_ids()

    def _verified_risk_bridges(self, risk: InferenceRiskRecord) -> bool:
        if not risk.required_bridge_obligation_ids:
            return False
        for obligation_id in risk.required_bridge_obligation_ids:
            try:
                obligation = self.proof_graph.get_obligation(obligation_id)
            except KeyError:
                return False
            if obligation.status != "closed" or not obligation.evidence_message_ids:
                return False
        return True

    def _counterexample_hunter_agent_id(self) -> str | None:
        return next(
            (
                agent.id
                for agent in self.config.agents
                if agent.enabled and "counterexample_hunter" in agent.roles
            ),
            None,
        )

    def evaluate_route_wakes(
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
    ) -> list[str]:
        woken = self.route_wake_controller.evaluate(
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
        if woken:
            self._emit(
                "proof_control_routes_woken",
                {
                    "round_index": current_round,
                    "route_ids": [route.route_id for route in woken],
                },
            )
            self.persist()
        return [route.route_id for route in woken]

    def plan_resume(
        self,
        *,
        checkpoint_state: Mapping[str, Any],
        config_hash: str,
        goal_hash: str,
        hard_stopped: bool,
        pending_action_ids: Sequence[str],
        terminal_stagnation_signature: str | None,
        prior_state_hash: str | None,
        prior_config_hash: str | None,
        prior_goal_hash: str | None,
        prior_terminal_stagnation_signature: str | None,
        intervention: str | None,
    ) -> ResumeDecision:
        decision = self.resume_planner.decide(
            checkpoint_state=checkpoint_state,
            config_hash=config_hash,
            goal_hash=goal_hash,
            hard_stopped=hard_stopped,
            pending_action_ids=pending_action_ids,
            executable_tasks=list(self.state.executable_tasks.values()),
            terminal_stagnation_signature=terminal_stagnation_signature,
            prior_state_hash=prior_state_hash,
            prior_config_hash=prior_config_hash,
            prior_goal_hash=prior_goal_hash,
            prior_terminal_stagnation_signature=(prior_terminal_stagnation_signature),
            intervention=intervention,
        )
        self._emit(
            "proof_control_resume_decided",
            decision.model_dump(mode="json"),
        )
        self.persist()
        return decision

    def prepare_routes_for_hard_stop(
        self,
        *,
        progress_signature: str,
        current_round: int,
    ) -> dict[str, list[str]]:
        result: dict[str, list[str]] = {
            "waiting_route_ids": [],
            "frozen_route_ids": [],
            "ready_task_ids": [],
        }
        live_statuses = {
            TaskStatus.CREATED,
            TaskStatus.NEEDS_REWRITE,
            TaskStatus.ASSIGNED,
            TaskStatus.READY,
            TaskStatus.RUNNING,
            TaskStatus.DEFERRED,
            TaskStatus.BLOCKED,
        }
        active_routes = self.route_registry.active_routes(current_round)
        route_tasks = {
            route.route_id: [
                task
                for task in self.state.executable_tasks.values()
                if route.route_id in task.route_ids and task.status in live_statuses
            ]
            for route in active_routes
        }
        result["ready_task_ids"] = sorted(
            {
                task.task_id
                for tasks in route_tasks.values()
                for task in tasks
                if task.status
                in {
                    TaskStatus.CREATED,
                    TaskStatus.ASSIGNED,
                    TaskStatus.READY,
                    TaskStatus.RUNNING,
                }
                and bool(
                    task.registered_handler
                    or task.assigned_agent_id
                    or task.status == TaskStatus.RUNNING
                )
            }
        )
        if result["ready_task_ids"]:
            return result
        for route in active_routes:
            tasks = route_tasks[route.route_id]
            automatic_wait_tasks = [
                task
                for task in tasks
                if any(
                    not condition.satisfied
                    and condition.kind != WakeConditionKind.USER_INTERVENTION
                    for condition in task.wake_conditions
                )
            ]
            if automatic_wait_tasks:
                for task in automatic_wait_tasks:
                    self.route_wake_controller.wait_for_task(
                        route.route_id,
                        task.task_id,
                        current_round=current_round,
                    )
                result["waiting_route_ids"].append(route.route_id)
                continue
            wake_ids = [
                condition.condition_id
                for task in tasks
                for condition in task.wake_conditions
                if not condition.satisfied
            ]
            record = RouteFreezeRecord(
                route_id=route.route_id,
                blocker_task_ids=[task.task_id for task in tasks],
                wake_condition_ids=sorted(set(wake_ids)),
                requires_user_intervention=True,
                reason=(
                    "No automatic repair remains after the certified progress "
                    f"plateau {progress_signature}."
                ),
                created_round=current_round,
            )
            self.route_wake_controller.freeze(record)
            result["frozen_route_ids"].append(route.route_id)
        for key in result:
            result[key] = sorted(set(result[key]))
        self._emit(
            "proof_control_hard_stop_routes_classified",
            {
                "round_index": current_round,
                "progress_signature": progress_signature,
                **result,
            },
        )
        self.persist()
        return result

    def reopen_frozen_routes(
        self,
        *,
        intervention: str,
        current_round: int,
    ) -> list[str]:
        if not intervention.strip():
            raise ValueError("reopening a frozen route requires an intervention")
        reopened: list[str] = []
        for route in self.route_registry.routes:
            if route.status not in {
                RouteStatus.FROZEN,
                RouteStatus.FROZEN_STALLED,
            }:
                continue
            self.route_registry.reactivate(
                route.route_id,
                revision_summary=(
                    f"explicit resume intervention {intervention} at round "
                    f"{current_round}"
                ),
            )
            reopened.append(route.route_id)
        if reopened:
            self._emit(
                "proof_control_frozen_routes_reopened",
                {
                    "intervention": intervention,
                    "round_index": current_round,
                    "route_ids": reopened,
                },
            )
            self.persist()
        return reopened

    def _mark_task_routes_waiting(
        self,
        task: ExecutableTaskRecord,
        *,
        current_round: int,
    ) -> None:
        if not self.active:
            return
        if task.status not in {
            TaskStatus.DEFERRED,
            TaskStatus.NEEDS_REWRITE,
            TaskStatus.BLOCKED,
        }:
            return
        if not any(not condition.satisfied for condition in task.wake_conditions):
            return
        for route_id in task.route_ids:
            try:
                route = self.route_registry.get(route_id)
                if route.status in {
                    RouteStatus.FROZEN,
                    RouteStatus.TERMINAL,
                    RouteStatus.FROZEN_STALLED,
                    RouteStatus.REFUTED,
                    RouteStatus.MERGED,
                    RouteStatus.ABANDONED,
                    RouteStatus.COMPLETED,
                }:
                    continue
                self.route_wake_controller.wait_for_task(
                    route_id,
                    task.task_id,
                    current_round=current_round,
                )
            except KeyError:
                continue

    @staticmethod
    def _property_strengthening_risk_types() -> set[InferenceRiskType]:
        return {
            InferenceRiskType.PARTIAL_PROPERTY_TO_TOTAL_PROPERTY,
            InferenceRiskType.NONEMPTY_INTERSECTION_TO_SUBSET_CONTAINMENT,
            InferenceRiskType.EXISTS_COMPONENT_TO_ALL_COMPONENTS,
            InferenceRiskType.SOME_WITNESS_TO_ALL_WITNESSES,
            InferenceRiskType.COVERAGE_TO_EXHAUSTIVENESS,
            InferenceRiskType.AT_LEAST_ONE_TO_ONLY_FROM_SET,
        }

    def _control_source_exists(self, source_id: str) -> bool:
        if (
            self.state.meta_pivot_state is not None
            and self.state.meta_pivot_state.pivot_id == source_id
        ):
            return True
        state_mappings = (
            self.state.control_actions,
            self.state.goal_links,
            self.state.route_target_bindings,
            self.state.goal_alignment_contracts,
            self.state.claim_verification_ledger,
            self.state.claim_referee_records,
            self.state.dependency_normalization_tasks,
            self.state.premise_closure_records,
            self.state.countermodel_tasks,
            self.state.falsification_tasks,
            self.state.typed_falsification_contracts,
            self.state.executable_tasks,
            self.state.negative_patterns,
            self.state.assumption_domains,
            self.state.obligation_domains,
            self.state.scope_signatures,
            self.state.proof_roles,
            self.state.inference_risks,
            self.state.minimal_bridge_proposals,
            self.state.abstract_structures,
            self.state.realizer_candidates,
            self.state.realizer_repair_tasks,
            self.state.induction_measures,
            self.state.induction_blueprints,
            self.state.failure_records,
            self.state.blueprint_rewrites,
            self.state.bottleneck_clusters,
            self.state.bottleneck_bridge_tasks,
            self.state.critical_assumptions,
            self.state.assumption_families,
            self.state.assumption_challenger_tasks,
            self.state.utility_contracts,
            self.state.usage_receipts,
            self.state.near_misses,
            self.state.process_diagnostics,
            self.state.route_update_tasks,
            self.state.inspiration_review_deferrals,
            self.state.route_admissions,
            self.state.meta_pivot_outcomes,
        )
        if any(source_id in values for values in state_mappings):
            return True
        if any(
            source_id
            in {
                message.message_id,
                message.content_hash,
            }
            for message in self.message_broker.messages
        ):
            return True
        if any(
            source_id
            in {
                obligation.obligation_id,
                obligation.content_hash,
            }
            for obligation in self.proof_graph.obligations
        ):
            return True
        return any(
            source_id in {route.route_id, route.strategy_id}
            for route in self.route_registry.routes
        )

    def _control_route_exists(self, route_id: str) -> bool:
        try:
            self.route_registry.get(route_id)
        except KeyError:
            return False
        return True

    def _control_obligation_exists(self, obligation_id: str) -> bool:
        try:
            self.proof_graph.get_obligation(obligation_id)
        except KeyError:
            return False
        return True

    @staticmethod
    def _history_auc(history: Sequence[float]) -> float:
        if not history:
            return 0.0
        if len(history) == 1:
            return float(history[0])
        return sum(
            (float(left) + float(right)) / 2.0
            for left, right in zip(history, history[1:])
        )

    def _emit(self, event_type: str, payload: Mapping[str, Any]) -> None:
        event = {"event_type": event_type, "payload": dict(payload)}
        self.state.events.append(event)
        self.store.append_event(event_type, dict(payload))
        if self.activity is not None:
            self.activity.info(
                event_type,
                title=event_type.replace("_", " ").title(),
                detail=str(payload.get("reason", "")),
                stage="proof_control",
                metrics=dict(payload),
            )
