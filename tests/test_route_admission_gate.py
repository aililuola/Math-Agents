from __future__ import annotations

from mathproofmesh.config import RouteAdmissionControlConfig
from mathproofmesh.proof_control.gates import RouteAdmissionGate
from mathproofmesh.proof_control.models import (
    ClaimGoalLink,
    GateVerdict,
    GoalRelation,
    ScopeRelation,
)
from mathproofmesh.schemas import ObligationKind, ProofObligation

from v07_helpers import PROBLEM_HASH, make_strategy


def _target() -> ProofObligation:
    return ProofObligation(
        obligation_id="main-goal",
        problem_hash=PROBLEM_HASH,
        route_ids=[],
        kind=ObligationKind.MAIN_GOAL,
        statement="Prove G.",
        normalized_statement="prove g.",
    )


def _link(
    strategy_id: str,
    *,
    relation: GoalRelation = GoalRelation.SUFFICIENT,
    scope: ScopeRelation = ScopeRelation.SAME,
) -> ClaimGoalLink:
    return ClaimGoalLink(
        subject_id=strategy_id,
        subject_kind="strategy",
        target_obligation_id="main-goal",
        relation=relation,
        scope_relation=scope,
        alignment_confidence=1.0,
        minimality_score=0.9,
    )


def test_active_admission_rewrites_overstrong_or_necessary_only_route() -> None:
    target = _target()
    strategy = make_strategy(1, tag="strong-mechanism")
    gate = RouteAdmissionGate(
        RouteAdmissionControlConfig(mode="active", min_goal_alignment=0.0)
    )

    overstrong = gate.evaluate(
        strategy,
        goal_link=_link(
            strategy.strategy_id,
            scope=ScopeRelation.CLAIM_STRONGER,
        ),
        target_obligations=[target],
        core_obligation_ids=[target.obligation_id],
    )
    necessary = gate.evaluate(
        strategy,
        goal_link=_link(
            strategy.strategy_id,
            relation=GoalRelation.NECESSARY_ONLY,
        ),
        target_obligations=[target],
        core_obligation_ids=[target.obligation_id],
    )

    assert overstrong.verdict == GateVerdict.REWRITE
    assert necessary.verdict == GateVerdict.REWRITE
    assert any("necessary condition" in item for item in necessary.reasons)


def test_minimal_sufficient_novel_route_passes() -> None:
    target = _target()
    strategy = make_strategy(2, tag="minimal-mechanism")
    record = RouteAdmissionGate(
        RouteAdmissionControlConfig(mode="active", min_goal_alignment=0.65)
    ).evaluate(
        strategy,
        goal_link=_link(strategy.strategy_id),
        target_obligations=[target],
        core_obligation_ids=[target.obligation_id],
        existing_mechanism_signatures=[["different-mechanism"]],
    )

    assert record.verdict == GateVerdict.PASS
    assert record.alignment_score >= 0.65


def test_shadow_admission_records_but_does_not_block_runtime() -> None:
    target = _target()
    strategy = make_strategy(3)
    record = RouteAdmissionGate(RouteAdmissionControlConfig(mode="shadow")).evaluate(
        strategy,
        goal_link=_link(
            strategy.strategy_id,
            relation=GoalRelation.UNRELATED,
        ),
        target_obligations=[target],
        core_obligation_ids=[target.obligation_id],
    )

    assert record.verdict == GateVerdict.SHADOW_BLOCK
    assert RouteAdmissionGate.blocks_runtime(record) is False
