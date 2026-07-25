from __future__ import annotations

from mathproofmesh.proof_control.common_mode import CriticalAssumptionMatrix
from mathproofmesh.schemas import (
    ClaimStatus,
    EvidenceType,
    MemoryTier,
    MessageType,
    RouteDescriptor,
)

from v07_helpers import make_message, make_strategy


def _routes_and_strategies() -> tuple[list[RouteDescriptor], list[object]]:
    variants = [
        "Assume H",
        "Suppose that H",
        "Hypothesis: H",
        "Using H",
        "H",
    ]
    routes: list[RouteDescriptor] = []
    strategies = []
    for index, assumption in enumerate(variants):
        strategy = make_strategy(index)
        strategy.prerequisites = [assumption]
        strategies.append(strategy)
        routes.append(
            RouteDescriptor(
                route_id=f"route-{index}",
                strategy_id=strategy.strategy_id,
                mechanism_signature=[f"mechanism-{index}"],
            )
        )
    return routes, strategies


def test_wording_variants_share_one_unverified_common_mode_assumption() -> None:
    routes, strategies = _routes_and_strategies()
    matrix = CriticalAssumptionMatrix()
    assumptions = matrix.build(routes, strategies)
    risks = matrix.risks(list(assumptions.values()))

    shared = next(item for item in risks if item.normalized_statement == "h")
    assert len(shared.route_ids) == 5
    assert shared.verification_status == ClaimStatus.PROPOSED
    assert shared.common_mode_risk >= 0.8

    task = matrix.challenger_task(shared)
    assert task["premise_eligible"] is False
    assert "counterexample_hunter" in task["roles"]
    assert shared.challenger_task_id == task["task_id"]


def test_route_votes_never_upgrade_evidence_status() -> None:
    routes, strategies = _routes_and_strategies()
    assumptions = CriticalAssumptionMatrix().build(routes, strategies)

    shared = next(
        item for item in assumptions.values() if item.normalized_statement == "h"
    )
    assert shared.verification_status != ClaimStatus.VERIFIED


def test_independently_verified_fact_clears_common_mode_risk() -> None:
    routes, strategies = _routes_and_strategies()
    fact = make_message(
        message_id="verified-h",
        route_id="route-0",
        agent_id="agent-0",
        statement="H",
        message_type=MessageType.VERIFIED_LEMMA,
        evidence_type=EvidenceType.NATURAL_PROOF_AUDITED,
        memory_tier=MemoryTier.FACT,
        status=ClaimStatus.VERIFIED,
        confidence=1.0,
    )
    matrix = CriticalAssumptionMatrix()
    assumptions = matrix.build(routes, strategies, [fact])
    shared = next(
        item for item in assumptions.values() if item.normalized_statement == "h"
    )

    assert shared.verification_status == ClaimStatus.VERIFIED
    assert shared not in matrix.risks(list(assumptions.values()))
