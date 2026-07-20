from __future__ import annotations

from typing import Iterable

from ..proof_graph.store import ProofGraphStore
from ..schemas import (
    NoveltySignature,
    ProofObligation,
    ReverseGoalPlan,
    stable_hash,
)


class ReverseGoalAnalyzer:
    """Turn a target into sufficient intermediate claims and its smallest gap."""

    def analyze(
        self,
        obligation: ProofObligation,
        *,
        fact_statements: Iterable[str] = (),
    ) -> ReverseGoalPlan:
        facts = list(fact_statements)
        sufficient = [
            f"Establish a criterion that implies: {obligation.statement}",
            f"Verify every hypothesis of that criterion under: {', '.join(obligation.assumptions) or 'the original assumptions'}",
        ]
        supported = [
            item for item in sufficient if any(token in item for token in facts)
        ]
        gaps = [item for item in sufficient if item not in supported]
        bridge_requests = [
            f"Prove bridge lemma for obligation {obligation.obligation_id}: {gap}"
            for gap in gaps[:2]
        ]
        signature = NoveltySignature(
            mechanism_tags=["reverse_goal_analysis", "sufficient_condition"],
            core_objects=["goal", "sufficient_intermediate_claim"],
            key_transformations=["goal_to_sufficient_condition"],
            proof_principles=["backward_chaining"],
            targeted_obligation_ids=[obligation.obligation_id],
        )
        digest = stable_hash((obligation.content_hash, signature.normalized_hash))
        return ReverseGoalPlan(
            plan_id=f"reverse_goal_{digest[:12]}",
            target_obligation_id=obligation.obligation_id,
            goal=obligation.statement,
            sufficient_intermediate_claims=sufficient,
            fact_supported_claims=supported,
            minimal_gaps=gaps,
            bridge_requests=bridge_requests,
            novelty_signature=signature,
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
                normalized_statement=gap.casefold().strip(),
                assumptions=target.assumptions,
                quantifiers=target.quantifiers,
                dependency_ids=[],
                status="open",
                priority=min(1.0, target.priority + 0.1),
                centrality=target.centrality,
            )
            created.append(graph.add_obligation(obligation))
        return created
