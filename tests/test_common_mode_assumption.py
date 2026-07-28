from __future__ import annotations

from mathproofmesh.proof_control.common_mode import CriticalAssumptionMatrix
from mathproofmesh.schemas import (
    ClaimStatus,
    EvidenceType,
    MemoryTier,
    MessageType,
    ObligationKind,
    ProofObligation,
    RouteDescriptor,
)

from v07_helpers import PROBLEM_HASH, make_message, make_strategy


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


def test_chinese_particle_variants_share_the_same_assumption_family() -> None:
    assert CriticalAssumptionMatrix.statements_semantically_match(
        "相邻对象的距离有界",
        "相邻对象距离有界",
    )


def test_chinese_and_english_assumptions_share_a_conservative_family() -> None:
    routes = [
        RouteDescriptor(
            route_id="route-zh",
            strategy_id="strategy-zh",
            mechanism_signature=["direct"],
        ),
        RouteDescriptor(
            route_id="route-en",
            strategy_id="strategy-en",
            mechanism_signature=["induction"],
        ),
    ]
    chinese = make_strategy(101).model_copy(
        update={
            "strategy_id": "strategy-zh",
            "prerequisites": ["相邻对象的距离有界"],
        }
    )
    english = make_strategy(102).model_copy(
        update={
            "strategy_id": "strategy-en",
            "prerequisites": ["Adjacent objects have bounded distance"],
        }
    )

    matrix = CriticalAssumptionMatrix()
    matrix.build(routes, [chinese, english])
    shared = [
        family
        for family in matrix.risk_families()
        if family.route_ids == ["route-en", "route-zh"]
    ]

    assert len(shared) == 1
    assert CriticalAssumptionMatrix.statements_semantically_match(
        "相邻对象的距离有界",
        "Adjacent objects have bounded distance",
    )


def test_cross_language_matching_fails_closed_on_semantic_conflicts() -> None:
    assert not CriticalAssumptionMatrix.statements_semantically_match(
        "相邻对象的距离有界",
        "Adjacent objects have unbounded distance",
    )
    assert not CriticalAssumptionMatrix.statements_semantically_match(
        "每个对象都有一个表示",
        "There exists an object with a representation",
    )
    assert not CriticalAssumptionMatrix.statements_semantically_match(
        "映射在定义域上保持次序",
        "Every closed interval is compact",
    )


def test_transport_wrappers_do_not_make_unrelated_chinese_gaps_equivalent() -> None:
    assert not CriticalAssumptionMatrix.statements_semantically_match(
        "[LEMMA][STATUS:OPEN][SOURCE:route-a][PREMISE_ELIGIBLE:false] "
        "Unresolved gap: 映射在定义域上保持次序",
        "[LEMMA][STATUS:OPEN][SOURCE:route-b][PREMISE_ELIGIBLE:false] "
        "Unresolved gap: 每个闭区间都是紧致的",
    )


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


def test_main_goal_hypotheses_are_given_not_unverified_route_votes() -> None:
    routes, strategies = _routes_and_strategies()
    goal = ProofObligation(
        obligation_id="main-goal",
        problem_hash=PROBLEM_HASH,
        route_ids=[item.route_id for item in routes],
        kind=ObligationKind.MAIN_GOAL,
        statement="Prove G from H.",
        normalized_statement="prove g from h.",
        assumptions=["H"],
    )
    matrix = CriticalAssumptionMatrix()
    assumptions = matrix.build(routes, strategies, obligations=[goal])
    shared = next(
        item for item in assumptions.values() if item.normalized_statement == "h"
    )

    assert shared.verification_status == ClaimStatus.VERIFIED
    assert shared not in matrix.risks(list(assumptions.values()))
