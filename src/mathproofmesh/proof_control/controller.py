from __future__ import annotations

from collections.abc import Collection, Iterable, Mapping, Sequence
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
from .domains import classify_obligation_domain
from .failure_control import BlueprintRewriter, FailureClassifier
from .falsification import (
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
    ClaimGoalLink,
    ControlActionRecord,
    ControlActionResult,
    ControlActionStatus,
    ControlActionType,
    CountermodelTaskRecord,
    FalsificationTaskRecord,
    GateVerdict,
    GoalAlignmentContractResult,
    GoalRelation,
    InductionBlueprintNode,
    InferenceRiskRecord,
    InferenceRiskType,
    MessageExpectedEffect,
    MinimalBridgeProposal,
    NegativePatternRecord,
    ObligationDomain,
    ObligationDomainRecord,
    ProofRole,
    RealizerFailureType,
    RouteAdmissionRecord,
    RouteTargetBinding,
    ScopeRelation,
    ScopeSignature,
    SynthesisReadinessRecord,
)
from .near_miss import NearMissLedger
from .proof_roles import ProofRoleClassifier, core_proof_debt
from .realizer import AbstractRealizerController
from .scope_guard import ScopeGuard
from .state import ProofControlState
from .route_target import choose_nearest_target_obligation


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
        self.bottlenecks = BottleneckCompressor(self.control_config.bottleneck)
        self.common_mode = CriticalAssumptionMatrix(self.control_config.common_mode)
        self.falsification_tasks = FalsificationTaskMaterializer(config)
        self.message_utility = MessageUtilityController(
            self.control_config.message_utility,
            proof_graph=proof_graph,
            contracts=self.state.utility_contracts,
            receipts=self.state.usage_receipts,
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

        self.message_broker.set_proof_control_message_gate(self._message_gate)
        self.proof_graph.set_proof_control_pre_close_policy(self._pre_close_policy)

    @property
    def active(self) -> bool:
        return self.control_config.enabled and self.control_config.mode == "active"

    @property
    def shadow(self) -> bool:
        return self.control_config.enabled and self.control_config.mode == "shadow"

    def register_strategy(self, strategy: StrategyCard) -> ClaimGoalLink | None:
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
        try:
            binding = choose_nearest_target_obligation(
                strategy,
                self.proof_graph,
                self.state.goal_links,
                self.state.obligation_domains,
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
            binding.direct_target_obligation_id == binding.main_goal_obligation_id
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
        link = existing or self.goal_alignment.assess_strategy(strategy, target)
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

    def register_obligation(self, obligation: ProofObligation) -> ClaimGoalLink | None:
        domain = self._ensure_obligation_domain(obligation)
        if domain.domain != ObligationDomain.MATHEMATICAL:
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

    def register_attempt(self, attempt: ProofAttempt) -> None:
        route_id = self._route_id_for_strategy(attempt.strategy_id)
        for claim in attempt.proposed_lemmas:
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

    def register_delta(self, delta: ProofDelta) -> None:
        route_id = self._route_id_for_strategy(delta.strategy_id)
        for claim in delta.new_claims:
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
            for risk in self.state.inference_risks.values():
                if risk.subject_id in subject_ids and risk.status == "open":
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
                if self.state.obligation_domains[item.obligation_id].domain
                == ObligationDomain.MATHEMATICAL
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
        self.persist()

    def route_signals(self, route_id: str) -> dict[str, object]:
        history = self.state.core_debt_history.get(route_id, [])
        debt = history[-1] if history else self._update_core_debt(route_id)
        reduction = max(0.0, history[-2] - history[-1]) if len(history) >= 2 else 0.0
        core_items = self.proof_graph.obligations_in_core_closure(route_id=route_id)
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
            if self._ensure_obligation_domain(item).domain
            == ObligationDomain.MATHEMATICAL
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
        }
        obligations = {
            item.obligation_id: item
            for item in self.proof_graph.obligations
            if self.state.obligation_domains[item.obligation_id].domain
            == ObligationDomain.MATHEMATICAL
        }
        for strategy in strategies:
            link = self.register_strategy(strategy)
            obligations = {
                item.obligation_id: item
                for item in self.proof_graph.obligations
                if self.state.obligation_domains[item.obligation_id].domain
                == ObligationDomain.MATHEMATICAL
            }
            if link is None:
                record = RouteAdmissionRecord(
                    strategy_id=strategy.strategy_id,
                    verdict=(
                        GateVerdict.BLOCK if self.active else GateVerdict.SHADOW_BLOCK
                    ),
                    alignment_score=0.0,
                    target_obligation_ids=[],
                    reasons=["main goal obligation is unavailable"],
                )
            else:
                binding = self._route_target_binding_for_strategy(strategy.strategy_id)
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
            if record.verdict in {GateVerdict.BLOCK, GateVerdict.REWRITE}:
                self._emit(
                    "route_admission_blocked",
                    record.model_dump(mode="json"),
                )
            elif record.verdict == GateVerdict.SHADOW_BLOCK:
                self._emit(
                    "route_admission_shadow_blocked",
                    record.model_dump(mode="json"),
                )
            if record.verdict in {GateVerdict.PASS, GateVerdict.SHADOW_BLOCK}:
                admitted.append(strategy)
                existing_signatures.append(strategy.tags)
        self.persist()
        return admitted, records

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
            broker_admitted_fact_ids=admitted_fact_ids,
            obligation_domains=self.state.obligation_domains,
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
        existing = self.state.falsification_tasks.get(task.task_id)
        if existing is not None:
            task = existing
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
        if task.experiment_spec is None:
            task.status = "deferred"
            return self.action_dispatcher.defer(
                action.action_id,
                reason=task.deferred_reason,
            )
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

    def export_state(self) -> dict[str, Any]:
        return self.state.export_state()

    def persist(self) -> None:
        payload = self.export_state()
        self.store.write_json("structured", "proof_control", payload)
        self.store.write_json("reports", "proof_control_summary", self.summary())

    def summary(self) -> dict[str, Any]:
        links = list(self.state.goal_links.values())
        aligned_relations = {GoalRelation.EQUIVALENT, GoalRelation.SUFFICIENT}
        invalid_scopes = {ScopeRelation.CLAIM_WEAKER, ScopeRelation.INCOMPARABLE}
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
        if link.scope_relation in {
            ScopeRelation.CLAIM_WEAKER,
            ScopeRelation.INCOMPARABLE,
        }:
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
            preserved_constraints=delta.active_assumptions,
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
        record = self.near_misses.extract_deterministic(
            report,
            route_id=route_id,
            target_obligation_id=(
                self._main_goal_ids()[0] if self._main_goal_ids() else None
            ),
            abstract_idea=(
                steps[0].justification
                if steps
                else "preserve the route mechanism before the first failed step"
            ),
            concrete_candidate=(
                attempt.final_answer
                if attempt is not None and attempt.final_answer
                else delta.candidate_final_answer
                if delta is not None and delta.candidate_final_answer
                else steps[-1].statement
                if steps
                else report.target_id
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
            suggested_repair_operators=["replace_realizer_preserve_structure"],
        )
        if record is not None:
            added = self.near_misses.add(record)
            self.state.near_misses[added.near_miss_id] = added
            self._emit("near_miss_recorded", added.model_dump(mode="json"))
        return record

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
                reasons.append(
                    "cross-route message has no proof-obligation utility target"
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
                if self._ensure_obligation_domain(item).domain
                == ObligationDomain.MATHEMATICAL
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
        if existing is not None:
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
        materialized = self.proof_graph.add_obligation(obligation)
        parent_id = action.payload.get("parent_main_goal_id")
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
        task = CountermodelTaskRecord(
            task_id=task_id,
            source_record_id=source_record_id,
            source_goal_link_id=str(link_id) if link_id is not None else None,
            target_obligation_id=target_obligation_id,
            route_ids=list(action.route_ids),
            status="pending",
        )
        self.state.countermodel_tasks[task_id] = task
        if link is not None:
            link.countermodel_status = "pending"
        risk = self.state.inference_risks.get(source_record_id)
        if risk is not None:
            risk.countermodel_task_id = task_id
        return ControlActionResult(
            result_refs=[task_id],
            postcondition_met=True,
        )

    def _countermodel_task_postcondition(
        self,
        _action: ControlActionRecord,
        result: ControlActionResult,
    ) -> bool:
        return bool(result.result_refs) and all(
            result_ref in self.state.countermodel_tasks
            and self.state.countermodel_tasks[result_ref].status
            in {"pending", "deferred", "inapplicable", "completed"}
            for result_ref in result.result_refs
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
            domain = self.state.obligation_domains.get(member_id)
            if domain is not None and domain.domain != ObligationDomain.MATHEMATICAL:
                return ControlActionResult(
                    detail="non-mathematical obligations cannot enter a bottleneck cluster"
                )
            self.proof_graph.get_obligation(member_id)
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
        if task.experiment_spec is None or task.computation_plan is None:
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
            result_refs=[task.task_id, task.computation_plan.plan_id],
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
        return (
            task is not None
            and task.status == "admitted"
            and task.action_id == action.action_id
            and task.experiment_spec is not None
            and task.computation_plan is not None
            and task.task_id in result.result_refs
            and task.computation_plan.plan_id in result.result_refs
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
        materialized = self.proof_graph.add_obligation(
            ProofObligation(
                obligation_id=obligation_id,
                problem_hash=self.proof_graph.problem_hash,
                route_ids=route_ids,
                kind=ObligationKind.SUBGOAL,
                statement=proposal.candidate_statement,
                normalized_statement=normalize_text(proposal.candidate_statement),
                priority=0.85,
                centrality=0.75,
            )
        )
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
                    detail="weaker target could not be bound to the route"
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
        return obligation.kind != ObligationKind.MAIN_GOAL

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
        materialized = self.proof_graph.add_obligation(
            ProofObligation(
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
        )
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
                    detail="minimal bridge could not update the route target binding"
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
        return obligation.kind != ObligationKind.MAIN_GOAL and obligation.status in {
            "open",
            "tentative",
            "blocked",
        }

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
        result_refs = [request.request_id]
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
                    "Establish an explicit implication from "
                    f"{direct.normalized_statement} to {main.normalized_statement}."
                )
                target_id = main.obligation_id
            else:
                if not action.target_obligation_ids:
                    request.status = "failed"
                    request.failure_reason = (
                        "blueprint rewrite has no auditable target obligation"
                    )
                    return ControlActionResult(detail=request.failure_reason)
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
                    detail="blueprint rewrite could not materialize a minimal bridge"
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
            if (
                self._ensure_obligation_domain(conclusion).domain
                == ObligationDomain.MATHEMATICAL
            ):
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
                    if (
                        self._ensure_obligation_domain(target).domain
                        == ObligationDomain.MATHEMATICAL
                    ):
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
        state_mappings = (
            self.state.control_actions,
            self.state.goal_links,
            self.state.route_target_bindings,
            self.state.goal_alignment_contracts,
            self.state.claim_verification_ledger,
            self.state.premise_closure_records,
            self.state.countermodel_tasks,
            self.state.falsification_tasks,
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
            self.state.route_admissions,
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
