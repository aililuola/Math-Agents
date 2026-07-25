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
from ..proof_identity import normalize_text
from ..schemas import (
    ClaimCard,
    ClaimStatus,
    MemoryTier,
    MessageEnvelope,
    MessageType,
    ObligationKind,
    ProofAttempt,
    ProofDelta,
    ProofObligation,
    ProofStep,
    StrategyCard,
    VerificationReport,
    VerificationVerdict,
)
from ..store import ArtifactStore
from .bottleneck import BottleneckCompressor
from .common_mode import CriticalAssumptionMatrix
from .failure_control import BlueprintRewriter, FailureClassifier
from .gates import (
    ContinueDeepeningGate,
    RouteAdmissionGate,
    SynthesisReadinessGate,
)
from .goal_alignment import GoalAlignmentAnalyzer
from .induction import InductionMeasureSelector
from .inference_risk import InferenceRiskScanner
from .message_utility import MessageUtilityController
from .models import (
    ClaimGoalLink,
    GateVerdict,
    GoalRelation,
    InferenceRiskRecord,
    MessageExpectedEffect,
    ProofRole,
    RealizerFailureType,
    RouteAdmissionRecord,
    ScopeRelation,
    ScopeSignature,
    SynthesisReadinessRecord,
)
from .near_miss import NearMissLedger
from .proof_roles import ProofRoleClassifier, core_proof_debt
from .realizer import AbstractRealizerController
from .scope_guard import ScopeGuard
from .state import ProofControlState


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

        self.scope_guard = ScopeGuard(self.control_config.scope_guard)
        self.goal_alignment = GoalAlignmentAnalyzer(proof_graph, self.scope_guard)
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
        if existing is not None:
            return existing
        target = self._main_goal()
        if target is None:
            self._emit(
                "goal_alignment_blocked",
                {
                    "subject_id": strategy.strategy_id,
                    "reason": "main goal obligation is not registered",
                },
            )
            return None
        link = self.goal_alignment.assess_strategy(strategy, target)
        if (
            link.relation == GoalRelation.UNKNOWN
            and strategy.expected_lemmas
            and strategy.falsification_test.strip()
        ):
            link = link.model_copy(
                update={"alignment_confidence": max(link.alignment_confidence, 0.70)}
            )
        return self._register_goal_link(strategy, link)

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

    def update_after_round(
        self,
        *,
        strategies: Sequence[StrategyCard],
        current_round: int,
    ) -> None:
        route_ids = [item.route_id for item in self.route_registry.routes]
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
                self.state.bottleneck_clusters[cluster.cluster_id] = cluster
                existing_members.add(key)
                self._emit(
                    "bottleneck_cluster_created",
                    cluster.model_dump(mode="json"),
                )
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
            self.proof_graph.obligations,
        )
        self.state.critical_assumptions.clear()
        self.state.critical_assumptions.update(assumptions)
        for assumption in self.common_mode.risks()[
            : self.control_config.common_mode.max_challengers_per_round
        ]:
            self._emit(
                "common_mode_assumption_detected",
                assumption.model_dump(mode="json"),
            )
            if assumption.challenger_task_id is None:
                task = self.common_mode.challenger_task(assumption)
                self._emit("assumption_challenger_created", task)

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
        core_ids = self.proof_graph.core_dependency_closure()
        obligations = {
            item.obligation_id: item for item in self.proof_graph.obligations
        }
        for strategy in strategies:
            link = self.register_strategy(strategy)
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
                record = self.route_admission_gate.evaluate(
                    strategy,
                    goal_link=link,
                    target_obligations=obligations,
                    core_obligation_ids=core_ids,
                    existing_mechanism_signatures=existing_signatures,
                    critical_assumptions=list(self.state.critical_assumptions.values()),
                    expected_core_obligation_reduction=(
                        link.target_obligation_id in core_ids
                        and bool(strategy.expected_lemmas)
                    ),
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

    def export_state(self) -> dict[str, Any]:
        return self.state.export_state()

    def persist(self) -> None:
        payload = self.export_state()
        self.store.write_json("structured", "proof_control", payload)
        self.store.write_json("reports", "proof_control_summary", self.summary())

    def summary(self) -> dict[str, Any]:
        return {
            "schema_version": self.state.schema_version,
            "mode": self.control_config.mode,
            "goal_links": len(self.state.goal_links),
            "overstrong_targets": sum(
                item.scope_relation == ScopeRelation.CLAIM_STRONGER
                for item in self.state.goal_links.values()
            ),
            "open_inference_risks": sum(
                item.status == "open" for item in self.state.inference_risks.values()
            ),
            "failure_classes": self._failure_distribution(),
            "blueprint_rewrites": len(self.state.blueprint_rewrites),
            "bottleneck_clusters": len(self.state.bottleneck_clusters),
            "common_mode_assumptions": sum(
                item.verification_status != ClaimStatus.VERIFIED
                and item.common_mode_risk
                >= self.control_config.common_mode.risk_threshold
                for item in self.state.critical_assumptions.values()
            ),
            "message_contracts": len(self.state.utility_contracts),
            "verified_message_uses": sum(
                item.verified_use for item in self.state.usage_receipts.values()
            ),
            "near_misses": len(self.state.near_misses),
            "route_admission_records": len(self.state.route_admissions),
            "continue_gate_blocks": sum(
                item.verdict == GateVerdict.BLOCK
                for item in self.state.continue_gate_records
            ),
            "synthesis_readiness_blocks": sum(
                item.verdict == GateVerdict.BLOCK
                for item in self.state.synthesis_readiness_records
            ),
            "core_debt_history": self.state.core_debt_history,
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
        texts: Sequence[str],
    ) -> None:
        if route_id is None or not self.control_config.induction.enabled:
            return
        triggers = self.induction.detect_trigger(*texts)
        if not triggers:
            return
        existing_sources = {
            tuple(item.trigger_features)
            for item in self.state.induction_measures.values()
            if item.route_id == route_id
        }
        for proposal in self.induction.propose_candidates(
            route_id=route_id,
            target_obligation_ids=self._main_goal_ids(),
            trigger_features=triggers,
            hints=[source_id, *texts[:4]],
        ):
            if tuple(proposal.trigger_features) in existing_sources:
                continue
            self.state.induction_measures[proposal.proposal_id] = proposal
            existing_sources.add(tuple(proposal.trigger_features))
            self._emit(
                "induction_measure_proposed",
                proposal.model_dump(mode="json"),
            )

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
                item.subject_id == message.message_id and item.status == "open"
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
                if item.normalized_statement == message.normalized_statement
                and item.assumptions == message.assumptions
            ),
            None,
        )
        return match or self._main_goal()

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
