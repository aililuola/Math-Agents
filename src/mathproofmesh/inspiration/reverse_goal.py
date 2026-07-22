from __future__ import annotations

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


class ReverseGoalAnalyzer:
    """Maintain forward and backward frontiers and expose their smallest gap."""

    def __init__(self, config: InspirationConfig | None = None) -> None:
        self.max_forward = config.frontier_max_forward_claims if config else 8
        self.max_backward = config.frontier_max_backward_claims if config else 8
        self.max_bridges = config.frontier_max_bridge_candidates if config else 3

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
            assumption = forward[0]
            target = backward[0]
            bridges = [self._bridge(assumption, target, 0.0)]
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
        if not claims:
            statement = (
                " and ".join(obligation.assumptions)
                if obligation.assumptions
                else "the original hypotheses"
            )
            claims.append(
                FrontierClaim(
                    frontier_id=f"frontier_assumptions_{obligation.obligation_id}",
                    direction="forward",
                    statement=statement,
                    assumptions=list(obligation.assumptions),
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
        candidates: list[tuple[float, FrontierClaim, FrontierClaim]] = []
        for target in backward:
            exact = next(
                (
                    source
                    for source in forward
                    if _normalized(source.statement) == _normalized(target.statement)
                ),
                None,
            )
            if exact is not None:
                supported.append(target.statement)
                continue
            for source in forward:
                score = jaccard_similarity(source.statement, target.statement)
                candidates.append((score, source, target))
        candidates.sort(
            key=lambda item: (
                -item[0],
                item[1].frontier_id,
                item[2].frontier_id,
            )
        )
        bridges: list[FrontierBridge] = []
        used_targets: set[str] = set()
        for score, source, target in candidates:
            if target.frontier_id in used_targets:
                continue
            bridges.append(self._bridge(source, target, score))
            used_targets.add(target.frontier_id)
            if len(bridges) >= self.max_bridges:
                break
        return bridges, supported

    @staticmethod
    def _bridge(
        source: FrontierClaim,
        target: FrontierClaim,
        score: float,
    ) -> FrontierBridge:
        missing = f"({source.statement}) implies ({target.statement})"
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
            ],
            lexical_overlap=score,
        )


__all__ = ["ReverseGoalAnalyzer"]
