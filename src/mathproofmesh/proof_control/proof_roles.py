from __future__ import annotations

from typing import TYPE_CHECKING, Any

from ..schemas import (
    EvidenceType,
    MemoryTier,
    MessageEnvelope,
    MessageType,
    ObligationKind,
)
from .models import ClaimGoalLink, GoalRelation, ProofRole

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


def core_proof_debt_placeholder(graph: ProofGraphStore, route_id: str) -> float:
    """Phase-2 compatibility shim; the weighted implementation lands in Phase 3."""

    return graph.proof_debt(route_id)
