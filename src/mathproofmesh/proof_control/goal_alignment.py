from __future__ import annotations

from collections.abc import Iterable
from typing import TYPE_CHECKING, Any, Literal

from ..proof_identity import obligation_identity_text
from ..schemas import (
    ClaimCard,
    GraphEdgeType,
    MessageEnvelope,
    ProofObligation,
    StrategyCard,
)
from .models import (
    ClaimGoalLink,
    GoalRelation,
    MinimalBridgeProposal,
    ScopeRelation,
    ScopeSignature,
)
from .scope_guard import ScopeGuard

if TYPE_CHECKING:
    from ..proof_graph.store import ProofGraphStore


class GoalAlignmentAnalyzer:
    """Create auditable links without treating lexical overlap as implication."""

    def __init__(
        self,
        graph: ProofGraphStore | None = None,
        scope_guard: ScopeGuard | None = None,
    ) -> None:
        self.graph = graph
        self.scope_guard = scope_guard or ScopeGuard()

    def assess_strategy(
        self,
        strategy: StrategyCard,
        target: ProofObligation,
        *,
        subject_scope: ScopeSignature | None = None,
        target_scope: ScopeSignature | None = None,
    ) -> ClaimGoalLink:
        scope = subject_scope or self.scope_guard._extract(
            subject_id=strategy.strategy_id,
            text="\n".join(
                [
                    strategy.core_idea,
                    strategy.bottleneck,
                    *strategy.expected_lemmas,
                ]
            ),
            domain_constraints=strategy.prerequisites,
            base_confidence=0.35,
        )
        return self._assess(
            subject_id=strategy.strategy_id,
            subject_kind="strategy",
            statement=strategy.bottleneck,
            target=target,
            subject_scope=scope,
            target_scope=target_scope,
            heuristic_only=bool(strategy.computation_hints)
            and not strategy.expected_lemmas,
        )

    def assess_claim(
        self,
        claim: ClaimCard,
        target: ProofObligation,
        *,
        subject_scope: ScopeSignature | None = None,
        target_scope: ScopeSignature | None = None,
    ) -> ClaimGoalLink:
        return self._assess(
            subject_id=claim.claim_id,
            subject_kind="claim",
            statement=claim.statement,
            target=target,
            subject_scope=subject_scope or self.scope_guard.extract_from_claim(claim),
            target_scope=target_scope,
        )

    def assess_message(
        self,
        message: MessageEnvelope,
        target: ProofObligation,
        *,
        subject_scope: ScopeSignature | None = None,
        target_scope: ScopeSignature | None = None,
    ) -> ClaimGoalLink:
        heuristic = message.evidence_type.value in {
            "numerical_heuristic",
            "bounded_experiment",
        }
        return self._assess(
            subject_id=message.message_id,
            subject_kind="message",
            statement=message.normalized_statement,
            target=target,
            subject_scope=subject_scope
            or self.scope_guard.extract_from_message(message),
            target_scope=target_scope,
            heuristic_only=heuristic,
        )

    def assess_obligation(
        self,
        obligation: ProofObligation,
        target: ProofObligation,
        *,
        subject_scope: ScopeSignature | None = None,
        target_scope: ScopeSignature | None = None,
    ) -> ClaimGoalLink:
        return self._assess(
            subject_id=obligation.obligation_id,
            subject_kind="obligation",
            statement=obligation.normalized_statement,
            target=target,
            subject_scope=subject_scope
            or self.scope_guard.extract_from_obligation(obligation),
            target_scope=target_scope,
        )

    def deterministic_relation(
        self,
        *,
        subject_id: str,
        subject_statement: str,
        target: ProofObligation,
        subject_scope: ScopeSignature,
        target_scope: ScopeSignature,
        heuristic_only: bool = False,
    ) -> tuple[GoalRelation, ScopeRelation, float]:
        scope_relation = self.scope_guard.compare(subject_scope, target_scope)
        same_statement = obligation_identity_text(
            subject_statement
        ) == obligation_identity_text(target.normalized_statement)
        if same_statement and scope_relation == ScopeRelation.SAME:
            return GoalRelation.EQUIVALENT, scope_relation, 1.0
        if heuristic_only:
            return GoalRelation.HEURISTIC_ONLY, scope_relation, 0.95
        graph_relation = self._graph_relation(subject_id, target.obligation_id)
        if graph_relation is not None:
            return graph_relation, scope_relation, 1.0
        if scope_relation in {
            ScopeRelation.INCOMPARABLE,
            ScopeRelation.CLAIM_WEAKER,
        }:
            return GoalRelation.UNKNOWN, scope_relation, 0.85
        return GoalRelation.UNKNOWN, scope_relation, 0.5

    async def model_review_ambiguous(
        self,
        runner: Any,
        prompt: Any,
        *,
        role: str = "structural_verifier",
    ) -> ClaimGoalLink | None:
        result = await runner.call(role, prompt)
        artifact = getattr(result, "artifact", result)
        return artifact if isinstance(artifact, ClaimGoalLink) else None

    def _assess(
        self,
        *,
        subject_id: str,
        subject_kind: Literal[
            "strategy", "claim", "message", "obligation", "proof_step"
        ],
        statement: str,
        target: ProofObligation,
        subject_scope: ScopeSignature,
        target_scope: ScopeSignature | None,
        heuristic_only: bool = False,
    ) -> ClaimGoalLink:
        target_signature = target_scope or self.scope_guard.extract_from_obligation(
            target
        )
        relation, scope_relation, confidence = self.deterministic_relation(
            subject_id=subject_id,
            subject_statement=statement,
            target=target,
            subject_scope=subject_scope,
            target_scope=target_signature,
            heuristic_only=heuristic_only,
        )
        remaining = self._remaining_open_ids(target, subject_id)
        bridge_ids = (
            list(target.dependency_ids)
            if relation in {GoalRelation.UNKNOWN, GoalRelation.NECESSARY_ONLY}
            else []
        )
        implication_outline = (
            [subject_id, target.obligation_id]
            if relation in {GoalRelation.EQUIVALENT, GoalRelation.SUFFICIENT}
            else []
        )
        score = {
            GoalRelation.EQUIVALENT: 1.0,
            GoalRelation.SUFFICIENT: 0.85,
            GoalRelation.NECESSARY_ONLY: 0.25,
            GoalRelation.HEURISTIC_ONLY: 0.10,
            GoalRelation.UNRELATED: 0.0,
            GoalRelation.UNKNOWN: 0.50,
        }[relation]
        if scope_relation == ScopeRelation.CLAIM_STRONGER:
            score = max(0.0, score - 0.25)
        return ClaimGoalLink(
            subject_id=subject_id,
            subject_kind=subject_kind,
            target_obligation_id=target.obligation_id,
            relation=relation,
            scope_relation=scope_relation,
            implication_outline=implication_outline,
            remaining_obligation_ids_if_proved=remaining,
            required_bridge_obligation_ids=bridge_ids,
            minimality_score=score,
            alignment_confidence=confidence,
        )

    def _graph_relation(self, subject_id: str, target_id: str) -> GoalRelation | None:
        if self.graph is None:
            return None
        for edge in self.graph.edges:
            if edge.edge_type == GraphEdgeType.EQUIVALENT_TO and {
                edge.source_id,
                edge.target_id,
            } == {subject_id, target_id}:
                return GoalRelation.EQUIVALENT
            if edge.edge_type != GraphEdgeType.IMPLIES:
                continue
            if edge.source_id == subject_id and edge.target_id == target_id:
                return GoalRelation.SUFFICIENT
            if edge.source_id == target_id and edge.target_id == subject_id:
                return GoalRelation.NECESSARY_ONLY
        return None

    def _remaining_open_ids(
        self, target: ProofObligation, subject_id: str
    ) -> list[str]:
        if self.graph is None:
            return [
                item
                for item in target.dependency_ids
                if item not in {subject_id, target.obligation_id}
            ]
        return sorted(
            item.obligation_id
            for item in self.graph.obligations
            if item.status not in {"closed", "refuted"}
            and item.obligation_id not in {subject_id, target.obligation_id}
            and (
                item.obligation_id in target.dependency_ids
                or target.obligation_id in item.dependency_ids
            )
        )


class MinimalSufficiencyAnalyzer:
    def compare_targets(
        self, left: ClaimGoalLink, right: ClaimGoalLink
    ) -> ClaimGoalLink | None:
        if left.target_obligation_id != right.target_obligation_id:
            return None
        eligible = {GoalRelation.EQUIVALENT, GoalRelation.SUFFICIENT}
        if left.relation not in eligible and right.relation not in eligible:
            return None
        if left.relation in eligible and right.relation not in eligible:
            return left
        if right.relation in eligible and left.relation not in eligible:
            return right
        return max(
            (left, right),
            key=lambda item: (
                item.minimality_score,
                item.alignment_confidence,
                item.link_id,
            ),
        )

    def dominated_strong_targets(
        self, links: Iterable[ClaimGoalLink]
    ) -> list[ClaimGoalLink]:
        grouped: dict[str, list[ClaimGoalLink]] = {}
        for link in links:
            grouped.setdefault(link.target_obligation_id, []).append(link)
        dominated: list[ClaimGoalLink] = []
        for group in grouped.values():
            sufficient = [
                item
                for item in group
                if item.relation in {GoalRelation.EQUIVALENT, GoalRelation.SUFFICIENT}
            ]
            if not sufficient:
                continue
            best_score = max(item.minimality_score for item in sufficient)
            dominated.extend(
                item
                for item in sufficient
                if item.scope_relation == ScopeRelation.CLAIM_STRONGER
                or item.minimality_score < best_score
            )
        return sorted(dominated, key=lambda item: item.link_id)

    def propose_weaker_bridge(
        self,
        overstrong: ClaimGoalLink,
        *,
        candidate_statement: str,
        implication_outline: list[str],
        required_bridge_obligation_ids: list[str] | None = None,
    ) -> MinimalBridgeProposal:
        return MinimalBridgeProposal(
            overstrong_subject_id=overstrong.subject_id,
            target_obligation_id=overstrong.target_obligation_id,
            candidate_statement=candidate_statement,
            relation_to_original="strictly_weaker",
            implication_outline=implication_outline,
            remaining_obligation_ids=overstrong.remaining_obligation_ids_if_proved,
            required_bridge_obligation_ids=required_bridge_obligation_ids or [],
        )
