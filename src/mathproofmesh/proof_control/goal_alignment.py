from __future__ import annotations

from collections.abc import Iterable, Mapping, Sequence
from typing import TYPE_CHECKING, Literal

from ..config import GoalAlignmentControlConfig, RouteAdmissionControlConfig
from ..proof_identity import obligation_identity_text
from ..schemas import (
    ClaimCard,
    ClaimStatus,
    GraphEdgeType,
    MemoryTier,
    MessageEnvelope,
    ProofObligation,
    StrategyCard,
)
from .models import (
    AlignmentExceptionCode,
    ClaimGoalLink,
    GateVerdict,
    GoalRelation,
    GoalAlignmentContractResult,
    MinimalBridgeProposal,
    PremiseClosureRecord,
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


class GoalAlignmentContract:
    """Apply configured alignment thresholds as fail-closed verdict rules."""

    def __init__(
        self,
        goal_config: GoalAlignmentControlConfig | None = None,
        route_config: RouteAdmissionControlConfig | None = None,
        *,
        strict_fail_closed: bool = True,
    ) -> None:
        self.goal_config = goal_config or GoalAlignmentControlConfig()
        self.route_config = route_config or RouteAdmissionControlConfig()
        self.strict_fail_closed = strict_fail_closed

    def validate(
        self,
        link: ClaimGoalLink,
        *,
        exception_code: AlignmentExceptionCode | None = None,
        exception_evidence_ids: Sequence[str] = (),
        countermodel_action_id: str | None = None,
    ) -> GoalAlignmentContractResult:
        threshold = max(
            self.goal_config.min_alignment_confidence,
            self.route_config.min_goal_alignment,
        )
        passed_min_confidence = link.alignment_confidence >= threshold
        has_required_outline = not self.goal_config.require_implication_outline or bool(
            link.implication_outline
        )
        unknown_relation_resolved = (
            link.relation != GoalRelation.UNKNOWN
            or link.countermodel_status
            in {
                "found",
                "none_found_bounded",
                "inapplicable",
                "deferred",
            }
        )
        evidence_ids = sorted(set(exception_evidence_ids))
        exception_valid = self._valid_exception(
            link,
            exception_code=exception_code,
            evidence_ids=evidence_ids,
        )
        if (
            exception_valid
            and exception_code == AlignmentExceptionCode.DIRECT_PREMISE_CLOSURE
        ):
            unknown_relation_resolved = True
        reasons: list[str] = []
        if not passed_min_confidence and not exception_valid:
            reasons.append("alignment confidence is below the configured threshold")
        if not has_required_outline and not exception_valid:
            reasons.append("required implication outline is missing")
        if link.relation == GoalRelation.UNKNOWN and not exception_valid:
            reasons.append("unknown relation requires explicit resolution")
            if (
                self.goal_config.run_countermodel_on_unknown_relation
                and countermodel_action_id is None
                and link.countermodel_status == "not_requested"
            ):
                reasons.append("unknown relation has no countermodel action")
        if link.relation == GoalRelation.UNRELATED:
            reasons.append("subject is unrelated to the target obligation")
        if link.relation == GoalRelation.HEURISTIC_ONLY:
            reasons.append("heuristic-only relation cannot satisfy the target")
        if link.relation == GoalRelation.NECESSARY_ONLY:
            reasons.append("necessary-only relation is not a sufficient closure path")
        if exception_code is not None and not exception_valid:
            reasons.append("alignment exception lacks matching verified evidence")

        pass_relation = link.relation in {
            GoalRelation.EQUIVALENT,
            GoalRelation.SUFFICIENT,
        }
        can_pass = (
            (pass_relation or exception_valid)
            and (passed_min_confidence or exception_valid)
            and (has_required_outline or exception_valid)
            and unknown_relation_resolved
            and not reasons
        )
        if can_pass:
            verdict = GateVerdict.PASS
        elif self.route_config.mode == "shadow":
            verdict = GateVerdict.SHADOW_BLOCK
        elif link.relation in {GoalRelation.UNRELATED, GoalRelation.HEURISTIC_ONLY}:
            verdict = GateVerdict.BLOCK
        elif self.strict_fail_closed or reasons:
            verdict = GateVerdict.REWRITE
        else:
            verdict = GateVerdict.REWRITE
        return GoalAlignmentContractResult(
            subject_id=link.subject_id,
            passed_min_confidence=passed_min_confidence,
            has_required_outline=has_required_outline,
            unknown_relation_resolved=unknown_relation_resolved,
            countermodel_action_id=countermodel_action_id,
            exception_code=exception_code if exception_valid else None,
            exception_evidence_ids=evidence_ids if exception_valid else [],
            final_verdict=verdict,
            reasons=reasons,
        )

    @staticmethod
    def _valid_exception(
        link: ClaimGoalLink,
        *,
        exception_code: AlignmentExceptionCode | None,
        evidence_ids: Sequence[str],
    ) -> bool:
        if exception_code is None or not evidence_ids:
            return False
        if exception_code == AlignmentExceptionCode.EXACT_EQUIVALENCE:
            return (
                link.relation == GoalRelation.EQUIVALENT
                and link.scope_relation == ScopeRelation.SAME
            )
        if exception_code == AlignmentExceptionCode.VERIFIED_GRAPH_EDGE:
            return link.relation in {
                GoalRelation.EQUIVALENT,
                GoalRelation.SUFFICIENT,
            }
        if exception_code == AlignmentExceptionCode.DIRECT_PREMISE_CLOSURE:
            return link.relation in {
                GoalRelation.EQUIVALENT,
                GoalRelation.SUFFICIENT,
                GoalRelation.UNKNOWN,
            }
        return False


class PremiseClosureAnalyzer:
    """Recognize only exact premise, witness, definition, or verified-fact matches."""

    def scan(
        self,
        target: ProofObligation,
        *,
        given_assumptions: Sequence[str] = (),
        verified_facts: Sequence[MessageEnvelope] = (),
        verified_claims: Sequence[ClaimCard] = (),
        direct_witnesses: Mapping[str, str] | None = None,
        definition_unfoldings: Mapping[str, str] | None = None,
    ) -> PremiseClosureRecord:
        target_text = _exact_identity(target.normalized_statement)
        for index, assumption in enumerate(given_assumptions):
            if _exact_identity(assumption) == target_text:
                return PremiseClosureRecord(
                    target_obligation_id=target.obligation_id,
                    closure_type="given_assumption",
                    supporting_ids=[f"assumption:{index}"],
                    verified=True,
                )
        for statement, supporting_id in sorted(
            (direct_witnesses or {}).items(), key=lambda item: item[1]
        ):
            if _exact_identity(statement) == target_text:
                return PremiseClosureRecord(
                    target_obligation_id=target.obligation_id,
                    closure_type="direct_witness",
                    supporting_ids=[supporting_id],
                    verified=True,
                )
        for statement, supporting_id in sorted(
            (definition_unfoldings or {}).items(), key=lambda item: item[1]
        ):
            if _exact_identity(statement) == target_text:
                return PremiseClosureRecord(
                    target_obligation_id=target.obligation_id,
                    closure_type="definition_unfolding",
                    supporting_ids=[supporting_id],
                    verified=True,
                )
        fact_ids = [
            fact.message_id
            for fact in verified_facts
            if fact.memory_tier == MemoryTier.FACT
            and fact.verification_status == ClaimStatus.VERIFIED
            and target_text
            in {
                _exact_identity(fact.normalized_statement),
                _exact_identity(fact.conclusion),
            }
        ]
        claim_ids = [
            claim.claim_id
            for claim in verified_claims
            if claim.status == ClaimStatus.VERIFIED
            and target_text
            in {
                _exact_identity(claim.statement),
                _exact_identity(claim.conclusion),
            }
        ]
        supporting_ids = sorted({*fact_ids, *claim_ids})
        if supporting_ids:
            return PremiseClosureRecord(
                target_obligation_id=target.obligation_id,
                closure_type="verified_fact_instance",
                supporting_ids=supporting_ids,
                verified=True,
            )
        return PremiseClosureRecord(
            target_obligation_id=target.obligation_id,
            closure_type="none",
            verified=False,
        )


def _exact_identity(value: str) -> str:
    return obligation_identity_text(value).strip(" .;:")


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
