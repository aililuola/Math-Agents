from __future__ import annotations

import re
from collections.abc import Iterable

from ..config import InspirationConfig
from ..proof_graph.store import ProofGraphStore
from ..schemas import (
    BidirectionalFrontierState,
    FrontierBridge,
    FrontierClaim,
    MessageEnvelope,
    NoveltySignature,
    ProofObligation,
    ReverseGoalPlan,
    stable_hash,
)
from ..topology import jaccard_similarity


def _normalized(value: str) -> str:
    return " ".join(value.casefold().split())


def _symbolic_anchors(value: str) -> set[str]:
    math_fragments = " ".join(re.findall(r"\$([^$]+)\$", value))
    math_fragments = re.sub(r"\\[A-Za-z]+", " ", math_fragments)
    anchors = {
        token.casefold()
        for token in re.findall(r"\b[A-Za-z][A-Za-z0-9_]*\b", math_fragments)
    }
    anchors.update(
        token.casefold()
        for token in re.findall(r"\b[A-Za-z]\b", value)
        if token.casefold() not in {"a", "i"}
    )
    return anchors


class ReverseGoalAnalyzer:
    """Maintain frontiers without inventing implication edges from similarity."""

    def __init__(self, config: InspirationConfig | None = None) -> None:
        self.max_forward = config.frontier_max_forward_claims if config else 8
        self.max_backward = config.frontier_max_backward_claims if config else 8
        self.max_bridges = config.frontier_max_bridge_candidates if config else 3
        self.min_candidate_overlap = (
            config.frontier_min_candidate_overlap if config else 0.35
        )

    def analyze(
        self,
        obligation: ProofObligation,
        *,
        facts: Iterable[MessageEnvelope] = (),
        fact_statements: Iterable[str] = (),
        round_index: int = 0,
        proposed_backward_claims: Iterable[str] = (),
    ) -> ReverseGoalPlan:
        fact_values = list(facts)
        legacy = list(fact_statements)
        forward = self._forward_frontier(obligation, fact_values, legacy)
        backward = self._backward_frontier(obligation, proposed_backward_claims)
        bridges, supported = self._meet_frontiers(forward, backward)
        if not bridges:
            source = forward[0]
            target = backward[0]
            bridges = [self._bridge(source, target, 0.0, use_candidate=False)]
        gaps = [item.missing_implication for item in bridges]
        requests = [
            (
                f"Prove bridge lemma for obligation {obligation.obligation_id}: "
                f"{item.missing_implication}"
            )
            for item in bridges
        ]
        sufficient = [item.statement for item in backward]
        signature = NoveltySignature(
            mechanism_tags=[
                "reverse_goal_analysis",
                "bidirectional_frontier",
                "bridge_lemma",
            ],
            core_objects=["forward_frontier", "backward_frontier", "bridge_gap"],
            key_transformations=["meet_forward_and_backward_frontiers"],
            proof_principles=["forward_chaining", "backward_chaining"],
            targeted_obligation_ids=[obligation.obligation_id],
        )
        digest = stable_hash(
            (
                obligation.content_hash,
                [item.statement for item in forward],
                [item.statement for item in backward],
                [item.missing_implication for item in bridges],
            )
        )
        return ReverseGoalPlan(
            plan_id=f"reverse_goal_{digest[:12]}",
            target_obligation_id=obligation.obligation_id,
            goal=obligation.statement,
            sufficient_intermediate_claims=sufficient,
            fact_supported_claims=supported,
            minimal_gaps=gaps,
            bridge_requests=requests,
            forward_frontier=forward,
            backward_frontier=backward,
            frontier_bridges=bridges,
            novelty_signature=signature,
        )

    def enrich_agent_plan(
        self,
        plan: ReverseGoalPlan,
        obligation: ProofObligation,
        *,
        facts: Iterable[MessageEnvelope],
        round_index: int,
    ) -> ReverseGoalPlan:
        """Rebuild evidence-bearing frontiers from admitted Facts, not model claims."""

        rebuilt = self.analyze(
            obligation,
            facts=facts,
            round_index=round_index,
            proposed_backward_claims=plan.sufficient_intermediate_claims,
        )
        return rebuilt.model_copy(
            update={
                "plan_id": plan.plan_id,
                "goal": plan.goal,
            }
        )

    def state(
        self, plan: ReverseGoalPlan, *, round_index: int
    ) -> BidirectionalFrontierState:
        return BidirectionalFrontierState(
            target_obligation_id=plan.target_obligation_id,
            forward_frontier=plan.forward_frontier,
            backward_frontier=plan.backward_frontier,
            bridge_candidates=plan.frontier_bridges,
            round_index=round_index,
        )

    def materialize(
        self, plan: ReverseGoalPlan, graph: ProofGraphStore
    ) -> list[ProofObligation]:
        target = graph.get_obligation(plan.target_obligation_id)
        created: list[ProofObligation] = []
        for gap, request in zip(plan.minimal_gaps, plan.bridge_requests, strict=False):
            digest = stable_hash((plan.plan_id, gap))
            obligation = ProofObligation(
                obligation_id=f"obl_reverse_{digest[:12]}",
                problem_hash=target.problem_hash,
                route_ids=target.route_ids,
                kind="lemma",
                statement=request,
                normalized_statement=_normalized(gap),
                assumptions=target.assumptions,
                quantifiers=target.quantifiers,
                dependency_ids=[],
                status="open",
                priority=min(1.0, target.priority + 0.1),
                centrality=target.centrality,
            )
            created.append(graph.add_obligation(obligation))
        return created

    def _forward_frontier(
        self,
        obligation: ProofObligation,
        facts: list[MessageEnvelope],
        legacy_statements: list[str],
    ) -> list[FrontierClaim]:
        claims: list[FrontierClaim] = []
        seen: set[str] = set()
        for fact in facts[: self.max_forward]:
            statement = fact.conclusion or fact.statement
            normalized = _normalized(statement)
            if not normalized or normalized in seen:
                continue
            seen.add(normalized)
            claims.append(
                FrontierClaim(
                    frontier_id=f"frontier_fact_{fact.message_id}",
                    direction="forward",
                    statement=statement,
                    source_ref=fact.message_id,
                    assumptions=list(fact.assumptions),
                    quantifiers=list(fact.quantifiers),
                    scope_limitations=list(fact.scope_limitations),
                    supported=True,
                )
            )
        for index, statement in enumerate(legacy_statements):
            normalized = _normalized(statement)
            if not normalized or normalized in seen or len(claims) >= self.max_forward:
                continue
            seen.add(normalized)
            digest = stable_hash(
                (obligation.obligation_id, "legacy_fact", index, statement)
            )
            claims.append(
                FrontierClaim(
                    frontier_id=f"frontier_legacy_{digest[:12]}",
                    direction="forward",
                    statement=statement,
                    supported=True,
                )
            )
        statement = (
            " and ".join(obligation.assumptions)
            if obligation.assumptions
            else "the original hypotheses"
        )
        normalized_scope = _normalized(statement)
        if len(claims) < self.max_forward and normalized_scope not in seen:
            claims.append(
                FrontierClaim(
                    frontier_id=f"frontier_scope_{obligation.obligation_id}",
                    direction="forward",
                    statement=statement,
                    assumptions=list(obligation.assumptions),
                    quantifiers=list(obligation.quantifiers),
                    supported=True,
                )
            )
        if not claims:
            claims.append(
                FrontierClaim(
                    frontier_id=f"frontier_scope_{obligation.obligation_id}",
                    direction="forward",
                    statement=statement,
                    assumptions=list(obligation.assumptions),
                    quantifiers=list(obligation.quantifiers),
                    supported=True,
                )
            )
        return claims[: self.max_forward]

    def _backward_frontier(
        self,
        obligation: ProofObligation,
        proposed: Iterable[str],
    ) -> list[FrontierClaim]:
        statements = list(
            dict.fromkeys(
                [
                    *proposed,
                    f"a sufficient criterion implying {obligation.statement}",
                    (
                        "every hypothesis of that criterion follows from "
                        + (
                            ", ".join(obligation.assumptions)
                            or "the original hypotheses"
                        )
                    ),
                    obligation.statement,
                ]
            )
        )[: self.max_backward]
        return [
            FrontierClaim(
                frontier_id="frontier_goal_"
                + stable_hash((obligation.obligation_id, index, statement))[:12],
                direction="backward",
                statement=statement,
                source_ref=obligation.obligation_id,
                assumptions=list(obligation.assumptions),
                quantifiers=list(obligation.quantifiers),
                supported=False,
            )
            for index, statement in enumerate(statements)
        ]

    def _meet_frontiers(
        self,
        forward: list[FrontierClaim],
        backward: list[FrontierClaim],
    ) -> tuple[list[FrontierBridge], list[str]]:
        supported: list[str] = []
        bridges: list[FrontierBridge] = []
        scope = next(
            (
                source
                for source in forward
                if source.frontier_id.startswith("frontier_scope_")
            ),
            None,
        )
        for target in backward:
            exact = next(
                (
                    source
                    for source in forward
                    if _normalized(source.statement) == _normalized(target.statement)
                    and self._scope_compatible_for_closure(source, target)
                ),
                None,
            )
            if exact is not None:
                supported.append(target.statement)
                continue
            candidates = sorted(
                (
                    (jaccard_similarity(source.statement, target.statement), source)
                    for source in forward
                    if not source.frontier_id.startswith("frontier_scope_")
                    and self._semantic_candidate_compatible(source, target)
                ),
                key=lambda item: (-item[0], item[1].frontier_id),
            )
            if candidates and candidates[0][0] >= self.min_candidate_overlap:
                score, source = candidates[0]
                bridges.append(self._bridge(source, target, score, use_candidate=True))
            else:
                fallback = scope or (candidates[0][1] if candidates else forward[0])
                score = candidates[0][0] if candidates else 0.0
                bridges.append(
                    self._bridge(fallback, target, score, use_candidate=False)
                )
            if len(bridges) >= self.max_bridges:
                break
        return bridges, supported

    @staticmethod
    def _semantic_candidate_compatible(
        source: FrontierClaim,
        target: FrontierClaim,
    ) -> bool:
        source_assumptions = {
            _normalized(value) for value in source.assumptions if value.strip()
        }
        target_assumptions = {
            _normalized(value) for value in target.assumptions if value.strip()
        }
        if not source_assumptions.issubset(target_assumptions):
            return False
        source_limitations = {
            _normalized(value) for value in source.scope_limitations if value.strip()
        }
        if not source_limitations.issubset(target_assumptions):
            return False
        source_anchors = _symbolic_anchors(source.statement)
        target_anchors = _symbolic_anchors(target.statement)
        return (
            not source_anchors
            or not target_anchors
            or bool(source_anchors & target_anchors)
        )

    @staticmethod
    def _scope_compatible_for_closure(
        source: FrontierClaim,
        target: FrontierClaim,
    ) -> bool:
        if not ReverseGoalAnalyzer._semantic_candidate_compatible(source, target):
            return False
        source_quantifiers = [
            (
                item.order,
                item.kind,
                _normalized(item.variable_id),
                _normalized(item.domain),
                tuple(_normalized(value) for value in item.restrictions),
            )
            for item in source.quantifiers
        ]
        target_quantifiers = [
            (
                item.order,
                item.kind,
                _normalized(item.variable_id),
                _normalized(item.domain),
                tuple(_normalized(value) for value in item.restrictions),
            )
            for item in target.quantifiers
        ]
        return source_quantifiers == target_quantifiers

    @staticmethod
    def _bridge(
        source: FrontierClaim,
        target: FrontierClaim,
        score: float,
        *,
        use_candidate: bool,
    ) -> FrontierBridge:
        required_conditions = [
            "retain the target obligation's original assumptions and quantifier domains",
            "prove every additional premise rather than importing it from lexical overlap",
        ]
        if use_candidate:
            required_conditions.extend(
                [
                    "provide an explicit variable and object mapping for the admitted Fact",
                    "prove that the admitted Fact is applicable in the target scope",
                ]
            )
            missing = (
                f"Prove ({target.statement}) from the scoped assumptions. "
                f"The admitted Fact ({source.statement}) is only a candidate ingredient; "
                "independently establish its variable mapping, applicability, and every "
                "additional premise before using it."
            )
            relationship = "candidate_ingredient"
        else:
            missing = (
                f"Prove ({target.statement}) from the scoped assumptions. "
                "No admitted frontier Fact is assumed to imply this target; derive all "
                "missing intermediate claims explicitly."
            )
            relationship = "scope_only"
        identifier = (
            "frontier_bridge_"
            + stable_hash((source.frontier_id, target.frontier_id, missing))[:12]
        )
        return FrontierBridge(
            bridge_id=identifier,
            forward_frontier_id=source.frontier_id,
            backward_frontier_id=target.frontier_id,
            missing_implication=missing,
            compatibility_conditions=[
                "the forward claim and backward target use the same scoped assumptions",
                "all quantified variables retain their original domains",
                "lexical similarity is not evidence of logical implication",
            ],
            lexical_overlap=score,
            semantic_relationship=relationship,
            source_sufficiency_assumed=False,
            required_supporting_conditions=required_conditions,
        )


__all__ = ["ReverseGoalAnalyzer"]
