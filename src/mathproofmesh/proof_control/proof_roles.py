from __future__ import annotations

from typing import TYPE_CHECKING, Any

from ..config import CoreDebtControlConfig
from ..schemas import (
    ClaimStatus,
    EvidenceType,
    MemoryTier,
    MessageEnvelope,
    MessageType,
    ObligationKind,
)
from .models import (
    ClaimGoalLink,
    CriticalAssumption,
    GoalRelation,
    InferenceRiskRecord,
    ObligationDomain,
    ObligationDomainRecord,
    ObligationSemanticQuality,
    ProofRole,
)

if TYPE_CHECKING:
    from ..proof_graph.store import ProofGraphStore


class ProofRoleClassifier:
    def classify(
        self,
        subject: Any,
        goal_link: ClaimGoalLink | None,
        graph: ProofGraphStore | None,
    ) -> ProofRole:
        if isinstance(subject, MessageEnvelope) and (
            subject.message_type == MessageType.COUNTEREXAMPLE
            or subject.evidence_type == EvidenceType.COUNTEREXAMPLE
        ):
            return ProofRole.COUNTEREXAMPLE
        if goal_link is not None:
            if goal_link.relation == GoalRelation.EQUIVALENT:
                return ProofRole.EQUIVALENT_REDUCTION
            if goal_link.relation == GoalRelation.NECESSARY_ONLY:
                return ProofRole.NECESSARY_CONDITION
            if goal_link.relation == GoalRelation.HEURISTIC_ONLY:
                return ProofRole.SEARCH_HEURISTIC
            if goal_link.relation == GoalRelation.SUFFICIENT:
                if self._targets_core(goal_link, graph):
                    return ProofRole.CORE_BRIDGE
                return ProofRole.SUFFICIENT_CONDITION
        if isinstance(subject, MessageEnvelope):
            if subject.memory_tier == MemoryTier.INSIGHT or subject.evidence_type in {
                EvidenceType.NUMERICAL_HEURISTIC,
                EvidenceType.BOUNDED_EXPERIMENT,
            }:
                return ProofRole.SEARCH_HEURISTIC
        text = " ".join(
            str(getattr(subject, field, ""))
            for field in ("statement", "conclusion", "title")
        ).casefold()
        if any(
            marker in text for marker in ("upper bound", "lower bound", "上界", "下界")
        ):
            return ProofRole.AUXILIARY_BOUND
        return ProofRole.TECHNICAL_LEMMA

    @staticmethod
    def _targets_core(link: ClaimGoalLink, graph: ProofGraphStore | None) -> bool:
        if graph is None:
            return True
        try:
            target = graph.get_obligation(link.target_obligation_id)
        except KeyError:
            return False
        if target.kind == ObligationKind.MAIN_GOAL:
            return True
        core_query = getattr(graph, "core_dependency_closure", None)
        return bool(core_query and target.obligation_id in core_query())


def core_proof_debt(
    graph: ProofGraphStore,
    route_id: str,
    *,
    config: CoreDebtControlConfig | None = None,
    proof_roles: dict[str, ProofRole] | None = None,
    inference_risks: dict[str, InferenceRiskRecord] | None = None,
    critical_assumptions: dict[str, CriticalAssumption] | None = None,
    obligation_domains: dict[str, ObligationDomainRecord] | None = None,
    obligation_semantic_quality: dict[str, ObligationSemanticQuality] | None = None,
) -> float:
    cfg = config or CoreDebtControlConfig()
    roles = proof_roles or {}
    domains = obligation_domains or {}
    semantic_quality = obligation_semantic_quality or {}
    core = [
        item
        for item in graph.obligations_in_core_closure(
            route_id=route_id,
            open_only=True,
        )
        if item.obligation_id not in domains
        or domains[item.obligation_id].domain == ObligationDomain.MATHEMATICAL
        if item.obligation_id not in semantic_quality
        or semantic_quality[item.obligation_id].eligible_for_core_debt
    ]
    core_ids = {item.obligation_id for item in core}
    debt = 0.0
    for obligation in core:
        if obligation.kind == ObligationKind.MAIN_GOAL:
            weight = cfg.main_goal_weight
        else:
            role = roles.get(obligation.obligation_id, ProofRole.CORE_BRIDGE)
            if role == ProofRole.CORE_BRIDGE:
                weight = cfg.core_bridge_weight
            elif role == ProofRole.AUXILIARY_BOUND:
                weight = cfg.auxiliary_weight
            elif role == ProofRole.NECESSARY_CONDITION:
                weight = cfg.necessary_only_weight
            else:
                weight = 1.0
        debt += weight * max(0.01, obligation.priority) * (1.0 + obligation.centrality)

    for risk in (inference_risks or {}).values():
        if (
            risk.status == "open"
            and (risk.route_id is None or risk.route_id == route_id)
            and (
                risk.subject_id in core_ids
                or risk.conclusion_id in core_ids
                or bool(set(risk.premise_ids) & core_ids)
            )
        ):
            debt += cfg.unresolved_scope_risk_weight * max(0.01, risk.confidence)

    for assumption in (critical_assumptions or {}).values():
        if (
            route_id in assumption.route_ids
            and assumption.verification_status != ClaimStatus.VERIFIED
        ):
            debt += cfg.common_mode_weight * assumption.common_mode_risk
    return debt
