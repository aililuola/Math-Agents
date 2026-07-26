from __future__ import annotations

from collections.abc import Collection, Mapping, Sequence
from typing import TYPE_CHECKING, Any

from ..config import (
    ContinueGateControlConfig,
    RouteAdmissionControlConfig,
    SynthesisReadinessControlConfig,
)
from ..proof_graph.store import ProofGraphStore
from ..proof_identity import normalize_text
from ..schemas import (
    ClaimStatus,
    ObligationKind,
    ProofObligation,
    StrategyCard,
    stable_hash,
)
from .models import (
    ClaimGoalLink,
    ContinueGateRecord,
    CriticalAssumption,
    GateVerdict,
    GoalAlignmentContractResult,
    GoalRelation,
    InferenceRiskRecord,
    InferenceRiskType,
    ObligationDomain,
    ObligationDomainRecord,
    ObligationSemanticQuality,
    RouteAdmissionRecord,
    RouteTargetBinding,
    ScopeRelation,
    SynthesisReadinessRecord,
)

if TYPE_CHECKING:
    from ..proof_graph.contradictions import ContradictionRecord


class RouteAdmissionGate:
    """Evaluate strategy viability without mutating routes or mathematical state."""

    def __init__(self, config: RouteAdmissionControlConfig | None = None) -> None:
        self.config = config or RouteAdmissionControlConfig()

    def evaluate(
        self,
        strategy: StrategyCard,
        *,
        goal_link: ClaimGoalLink,
        target_obligations: Mapping[str, ProofObligation] | Sequence[ProofObligation],
        core_obligation_ids: Collection[str] = (),
        existing_mechanism_signatures: Sequence[Sequence[str]] = (),
        critical_assumptions: Sequence[CriticalAssumption] = (),
        expected_core_obligation_reduction: bool | None = None,
        rewrite_request_id: str | None = None,
        target_binding: RouteTargetBinding | None = None,
        alignment_contract: GoalAlignmentContractResult | None = None,
    ) -> RouteAdmissionRecord:
        targets = (
            dict(target_obligations)
            if isinstance(target_obligations, Mapping)
            else {item.obligation_id: item for item in target_obligations}
        )
        direct_target_id = (
            target_binding.direct_target_obligation_id
            if target_binding is not None
            else goal_link.target_obligation_id
        )
        direct_relation = (
            target_binding.relation_to_direct_target
            if target_binding is not None
            else goal_link.relation
        )
        direct_scope = (
            target_binding.scope_relation_to_direct_target
            if target_binding is not None
            else goal_link.scope_relation
        )
        target_ids = [direct_target_id]
        hard_reasons: list[str] = []
        rewrite_reasons: list[str] = []

        if direct_target_id not in targets:
            hard_reasons.append("target obligation is not registered")
        if direct_relation == GoalRelation.UNRELATED:
            hard_reasons.append("strategy is unrelated to the target obligation")
        if (
            self.config.reject_heuristic_only_as_main_route
            and direct_relation == GoalRelation.HEURISTIC_ONLY
        ):
            hard_reasons.append("heuristic-only evidence cannot define a main route")
        if (
            self.config.reject_necessary_only_as_main_route
            and direct_relation == GoalRelation.NECESSARY_ONLY
        ):
            has_bridge = bool(
                goal_link.required_bridge_obligation_ids
                or (target_binding is not None and target_binding.bridge_obligation_ids)
            )
            reason = "a necessary condition is being used as a sufficient route target"
            if has_bridge:
                rewrite_reasons.append(reason)
            else:
                hard_reasons.append(reason + " without an explicit bridge")
        intermediate_weaker = (
            target_binding is not None
            and direct_target_id != target_binding.main_goal_obligation_id
            and direct_relation in {GoalRelation.EQUIVALENT, GoalRelation.SUFFICIENT}
            and target_binding.blueprint_path_complete
        )
        if direct_scope in {
            ScopeRelation.CLAIM_STRONGER,
            ScopeRelation.CLAIM_WEAKER,
            ScopeRelation.INCOMPARABLE,
        } and not (direct_scope == ScopeRelation.CLAIM_WEAKER and intermediate_weaker):
            rewrite_reasons.append(
                f"strategy scope is {direct_scope.value} relative to the direct target"
            )
        if goal_link.alignment_confidence < self.config.min_goal_alignment:
            rewrite_reasons.append(
                "goal alignment confidence is below admission threshold"
            )
        if (
            self.config.require_falsification_test
            and not strategy.falsification_test.strip()
        ):
            rewrite_reasons.append("strategy has no concrete falsification test")
        if self.config.require_mechanism_novelty and self._duplicates_mechanism(
            strategy, existing_mechanism_signatures
        ):
            rewrite_reasons.append("strategy mechanism duplicates an admitted route")
        risky_dependencies = self._risky_common_dependencies(
            strategy, critical_assumptions
        )
        if risky_dependencies:
            hard_reasons.append(
                "strategy fully depends on unresolved common-mode assumption(s): "
                + ", ".join(risky_dependencies)
            )

        if expected_core_obligation_reduction is None:
            expected_core_obligation_reduction = (
                direct_target_id in set(core_obligation_ids)
                and direct_relation
                in {GoalRelation.EQUIVALENT, GoalRelation.SUFFICIENT}
                and (
                    direct_scope
                    in {
                        ScopeRelation.SAME,
                        ScopeRelation.CLAIM_STRONGER,
                    }
                    or intermediate_weaker
                )
                and (target_binding is None or target_binding.blueprint_path_complete)
            )
        if not expected_core_obligation_reduction:
            rewrite_reasons.append(
                "strategy does not predict reduction of a core obligation"
            )
        if (
            alignment_contract is not None
            and alignment_contract.final_verdict != GateVerdict.PASS
        ):
            contract_reasons = alignment_contract.reasons or [
                "goal-alignment contract did not pass"
            ]
            if alignment_contract.final_verdict == GateVerdict.BLOCK:
                hard_reasons.extend(contract_reasons)
            else:
                rewrite_reasons.extend(contract_reasons)

        alignment_score = self._alignment_score(goal_link)
        if not self.config.enabled or self.config.mode == "off":
            verdict = GateVerdict.PASS
            reasons = ["route admission control is disabled"]
        elif hard_reasons or rewrite_reasons:
            reasons = [*hard_reasons, *rewrite_reasons]
            if self.config.mode == "shadow":
                verdict = GateVerdict.SHADOW_BLOCK
            elif hard_reasons:
                verdict = GateVerdict.BLOCK
            else:
                verdict = GateVerdict.REWRITE
        else:
            verdict = GateVerdict.PASS
            reasons = ["strategy satisfies route admission requirements"]
        if verdict == GateVerdict.REWRITE and rewrite_request_id is None:
            rewrite_request_id = (
                "blueprint_rewrite_"
                + stable_hash(
                    {
                        "strategy_id": strategy.strategy_id,
                        "goal_link_id": goal_link.link_id,
                        "target_ids": target_ids,
                        "reasons": sorted(set(rewrite_reasons)),
                    }
                )[:16]
            )
        return RouteAdmissionRecord(
            strategy_id=strategy.strategy_id,
            verdict=verdict,
            alignment_score=alignment_score,
            target_obligation_ids=target_ids,
            reasons=reasons,
            rewrite_request_id=(
                rewrite_request_id if verdict == GateVerdict.REWRITE else None
            ),
            target_binding_id=(
                target_binding.binding_id if target_binding is not None else None
            ),
            alignment_contract_id=(
                alignment_contract.contract_id
                if alignment_contract is not None
                else None
            ),
        )

    async def review_ambiguous(
        self,
        *,
        runner: Any,
        prompt: Any,
        role: str = "structural_verifier",
    ) -> RouteAdmissionRecord | None:
        result = await runner.call(role, prompt)
        artifact = getattr(result, "artifact", result)
        return artifact if isinstance(artifact, RouteAdmissionRecord) else None

    @staticmethod
    def blocks_runtime(record: RouteAdmissionRecord) -> bool:
        return record.verdict in {GateVerdict.BLOCK, GateVerdict.REWRITE}

    @staticmethod
    def _alignment_score(link: ClaimGoalLink) -> float:
        relation_score = {
            GoalRelation.EQUIVALENT: 1.0,
            GoalRelation.SUFFICIENT: 0.85,
            GoalRelation.UNKNOWN: 0.50,
            GoalRelation.NECESSARY_ONLY: 0.25,
            GoalRelation.HEURISTIC_ONLY: 0.10,
            GoalRelation.UNRELATED: 0.0,
        }[link.relation]
        scope_penalty = {
            ScopeRelation.SAME: 0.0,
            ScopeRelation.CLAIM_STRONGER: 0.15,
            ScopeRelation.CLAIM_WEAKER: 0.35,
            ScopeRelation.INCOMPARABLE: 0.50,
            ScopeRelation.UNKNOWN: 0.20,
        }[link.scope_relation]
        confidence_factor = 0.5 + 0.5 * link.alignment_confidence
        return max(0.0, min(1.0, relation_score * confidence_factor - scope_penalty))

    @staticmethod
    def _duplicates_mechanism(
        strategy: StrategyCard,
        existing_signatures: Sequence[Sequence[str]],
    ) -> bool:
        signature = {
            normalize_text(item).casefold()
            for item in [*strategy.tags, strategy.key_original_step or ""]
            if normalize_text(item)
        }
        if not signature:
            signature = {normalize_text(strategy.core_idea).casefold()}
        return any(
            signature
            == {
                normalize_text(item).casefold()
                for item in existing
                if normalize_text(item)
            }
            for existing in existing_signatures
        )

    @staticmethod
    def _risky_common_dependencies(
        strategy: StrategyCard,
        assumptions: Sequence[CriticalAssumption],
    ) -> list[str]:
        prerequisites = {
            normalize_text(item).casefold() for item in strategy.prerequisites
        }
        return sorted(
            item.assumption_id
            for item in assumptions
            if item.verification_status != ClaimStatus.VERIFIED
            and item.common_mode_risk >= 0.70
            and normalize_text(item.normalized_statement).casefold() in prerequisites
        )


class ContinueDeepeningGate:
    """Stop repeated deepening while leaving budget and lease ownership untouched."""

    def __init__(
        self,
        config: ContinueGateControlConfig | None = None,
        *,
        prior_records: Sequence[ContinueGateRecord] = (),
    ) -> None:
        self.config = config or ContinueGateControlConfig()
        self.records = list(prior_records)
        self._last_first_error: dict[str, str | None] = {}

    def evaluate(
        self,
        *,
        route_id: str,
        segment_index: int,
        core_obligation_closed: bool,
        core_debt_reduced: bool,
        verified_bridge_gain: bool,
        first_error_fingerprint: str | None = None,
        first_error_changed: bool | None = None,
    ) -> ContinueGateRecord:
        previous = next(
            (item for item in reversed(self.records) if item.route_id == route_id),
            None,
        )
        if first_error_changed is None:
            known_previous = route_id in self._last_first_error
            first_error_changed = (
                known_previous
                and self._last_first_error[route_id] != first_error_fingerprint
            )
        self._last_first_error[route_id] = first_error_fingerprint

        signals = (
            (self.config.allow_on_core_obligation_closed and core_obligation_closed),
            self.config.allow_on_core_debt_reduced and core_debt_reduced,
            self.config.allow_on_first_error_changed and first_error_changed,
            self.config.allow_on_verified_bridge_gain and verified_bridge_gain,
        )
        any_core_signal = any(signals)
        previous_stagnation = (
            previous.consecutive_no_core_progress if previous is not None else 0
        )
        if any_core_signal:
            consecutive = 0
        elif first_error_changed and previous_stagnation:
            consecutive = 1
        else:
            consecutive = previous_stagnation + 1

        should_block = (
            self.config.require_any_core_signal
            and consecutive >= self.config.no_core_progress_segments
            and not first_error_changed
        )
        if not self.config.enabled or self.config.mode == "off":
            verdict = GateVerdict.PASS
            reason = "continue-deepening control is disabled"
        elif should_block and self.config.mode == "shadow":
            verdict = GateVerdict.SHADOW_BLOCK
            reason = (
                "shadow: repeated segments made no core progress and retained "
                "the same first error"
            )
        elif should_block:
            verdict = GateVerdict.BLOCK
            reason = (
                "deepening blocked after repeated no-core-progress segments with "
                "the same first error; route rewrite or bounded repair is required"
            )
        else:
            verdict = GateVerdict.PASS
            reason = (
                "a core progress signal permits deepening"
                if any_core_signal
                else "stagnation threshold has not been reached"
            )
        record = ContinueGateRecord(
            route_id=route_id,
            segment_index=segment_index,
            verdict=verdict,
            core_obligation_closed=core_obligation_closed,
            core_debt_reduced=core_debt_reduced,
            first_error_changed=first_error_changed,
            verified_bridge_gain=verified_bridge_gain,
            consecutive_no_core_progress=consecutive,
            reason=reason,
        )
        self.records.append(record)
        return record

    @staticmethod
    def blocks_deepening(record: ContinueGateRecord) -> bool:
        return record.verdict == GateVerdict.BLOCK


class SynthesisReadinessGate:
    """Audit final-proof prerequisites without synthesizing or closing anything."""

    _SCOPE_RISKS = {
        InferenceRiskType.EVENTUAL_TO_GLOBAL,
        InferenceRiskType.POINTWISE_TO_UNIFORM,
        InferenceRiskType.PROJECTION_TO_ORIGINAL,
        InferenceRiskType.LOCAL_TO_GLOBAL,
        InferenceRiskType.EXISTENCE_TO_UNIFORM_EXISTENCE,
        InferenceRiskType.PAIRWISE_TO_COMMON_WITNESS,
        InferenceRiskType.PARTIAL_PROPERTY_TO_TOTAL_PROPERTY,
        InferenceRiskType.NONEMPTY_INTERSECTION_TO_SUBSET_CONTAINMENT,
        InferenceRiskType.EXISTS_COMPONENT_TO_ALL_COMPONENTS,
        InferenceRiskType.SOME_WITNESS_TO_ALL_WITNESSES,
        InferenceRiskType.COVERAGE_TO_EXHAUSTIVENESS,
        InferenceRiskType.AT_LEAST_ONE_TO_ONLY_FROM_SET,
    }

    def __init__(self, config: SynthesisReadinessControlConfig | None = None) -> None:
        self.config = config or SynthesisReadinessControlConfig()

    def evaluate(
        self,
        graph: ProofGraphStore,
        *,
        inference_risks: Sequence[InferenceRiskRecord] = (),
        goal_links: Sequence[ClaimGoalLink] = (),
        conflicts: Sequence[ContradictionRecord] = (),
        critical_assumptions: Sequence[CriticalAssumption] = (),
        candidate_dependency_ids: Sequence[str] = (),
        candidate_fact_ids: Sequence[str] = (),
        broker_admitted_fact_ids: Collection[str] = (),
        obligation_domains: Mapping[str, ObligationDomainRecord] | None = None,
        obligation_semantic_quality: (
            Mapping[str, ObligationSemanticQuality] | None
        ) = None,
    ) -> SynthesisReadinessRecord:
        reasons: list[str] = []
        domains = obligation_domains or {}
        semantic_quality = obligation_semantic_quality or {}
        mathematical_ids = {
            item.obligation_id
            for item in graph.obligations
            if item.obligation_id not in domains
            or domains[item.obligation_id].domain == ObligationDomain.MATHEMATICAL
            if item.obligation_id not in semantic_quality
            or semantic_quality[item.obligation_id].eligible_for_core_debt
        }
        main_goal_ids = set(graph.main_goal_obligation_ids())
        open_core = [
            item
            for item in graph.core_open_obligations()
            if item.kind != ObligationKind.MAIN_GOAL
            and item.obligation_id in mathematical_ids
        ]
        if self.config.require_core_dependency_closure and open_core:
            reasons.append("core dependency closure contains open obligations")
        if self.config.require_core_dependency_closure and not main_goal_ids:
            reasons.append("proof graph has no registered main-goal obligation")

        open_scope_risks = [
            item
            for item in inference_risks
            if item.status == "open" and item.risk_type in self._SCOPE_RISKS
        ]
        if self.config.require_no_open_scope_risks and open_scope_risks:
            reasons.append("open scope risks remain")

        unresolved_conflicts = [
            item
            for item in conflicts
            if item.status == "open"
            and item.centrality >= self.config.high_centrality_threshold
        ]
        if (
            self.config.require_no_unresolved_high_centrality_conflicts
            and unresolved_conflicts
        ):
            reasons.append("high-centrality contradictions remain unresolved")

        invalid_links = [
            item
            for item in goal_links
            if item.target_obligation_id in mathematical_ids
            and (
                item.relation == GoalRelation.NECESSARY_ONLY
                or item.scope_relation
                in {ScopeRelation.CLAIM_WEAKER, ScopeRelation.INCOMPARABLE}
            )
        ]
        if self.config.require_no_necessary_only_bridge_as_sufficient and invalid_links:
            reasons.append("necessary-only or scope-invalid goal links remain")

        common_mode = [
            item
            for item in critical_assumptions
            if item.verification_status != ClaimStatus.VERIFIED
            and item.common_mode_risk >= self.config.high_centrality_threshold
        ]
        if common_mode:
            reasons.append("unresolved common-mode assumptions remain")

        open_auxiliary = [
            item
            for item in graph.obligations
            if item.obligation_id in mathematical_ids
            and item.obligation_id not in graph.core_dependency_closure()
            and item.status in {"open", "tentative", "blocked"}
        ]
        if len(open_auxiliary) > self.config.max_open_auxiliary_obligations:
            reasons.append("too many auxiliary obligations remain open")

        obligation_by_id = {item.obligation_id: item for item in graph.obligations}
        admitted = set(broker_admitted_fact_ids)
        unresolved_dependencies = [
            item_id
            for item_id in candidate_dependency_ids
            if (
                item_id in obligation_by_id
                and item_id in mathematical_ids
                and obligation_by_id[item_id].status != "closed"
            )
            or (item_id not in obligation_by_id and item_id not in admitted)
        ]
        if unresolved_dependencies:
            reasons.append(
                "final candidate has unresolved dependencies: "
                + ", ".join(sorted(set(unresolved_dependencies)))
            )
        unadmitted_facts = sorted(set(candidate_fact_ids) - admitted)
        if unadmitted_facts:
            reasons.append(
                "final candidate cites facts not admitted by the broker: "
                + ", ".join(unadmitted_facts)
            )

        if not self.config.enabled or self.config.mode == "off":
            verdict = GateVerdict.PASS
            record_reasons = ["synthesis readiness control is disabled"]
        elif reasons and self.config.mode == "shadow":
            verdict = GateVerdict.SHADOW_BLOCK
            record_reasons = reasons
        elif reasons:
            verdict = GateVerdict.BLOCK
            record_reasons = reasons
        else:
            verdict = GateVerdict.PASS
            record_reasons = ["synthesis prerequisites are satisfied"]
        return SynthesisReadinessRecord(
            verdict=verdict,
            open_core_obligation_ids=sorted(item.obligation_id for item in open_core),
            open_scope_risk_ids=sorted(item.risk_id for item in open_scope_risks),
            unresolved_conflict_ids=sorted(
                item.contradiction_id for item in unresolved_conflicts
            ),
            invalid_goal_link_ids=sorted(item.link_id for item in invalid_links),
            unresolved_common_mode_assumption_ids=sorted(
                item.assumption_id for item in common_mode
            ),
            reasons=record_reasons,
        )

    @staticmethod
    def blocks_synthesis(record: SynthesisReadinessRecord) -> bool:
        return record.verdict == GateVerdict.BLOCK
