from __future__ import annotations

from mathproofmesh.config import (
    GoalAlignmentControlConfig,
    RouteAdmissionControlConfig,
)
from mathproofmesh.proof_control.controller import ProofControlLayer
from mathproofmesh.proof_control.goal_alignment import GoalAlignmentContract
from mathproofmesh.proof_control.models import (
    AlignmentExceptionCode,
    ClaimGoalLink,
    ControlActionStatus,
    ControlActionType,
    GateVerdict,
    GoalRelation,
    ScopeRelation,
)
from mathproofmesh.schemas import ObligationKind, ProofObligation

from v07_helpers import (
    PROBLEM_HASH,
    make_broker_runtime,
    make_proof_control_config,
    make_strategy,
)


def _link(
    *,
    relation: GoalRelation = GoalRelation.SUFFICIENT,
    confidence: float = 0.95,
    outline: list[str] | None = None,
) -> ClaimGoalLink:
    return ClaimGoalLink(
        link_id="goal-link-a",
        subject_id="strategy-a",
        subject_kind="strategy",
        target_obligation_id="goal-a",
        relation=relation,
        scope_relation=ScopeRelation.SAME,
        implication_outline=["strategy-a", "goal-a"] if outline is None else outline,
        alignment_confidence=confidence,
    )


def _contract() -> GoalAlignmentContract:
    return GoalAlignmentContract(
        GoalAlignmentControlConfig(
            min_alignment_confidence=0.8,
            require_implication_outline=True,
            run_countermodel_on_unknown_relation=True,
        ),
        RouteAdmissionControlConfig(
            mode="active",
            min_goal_alignment=0.75,
        ),
        strict_fail_closed=True,
    )


def test_low_alignment_score_cannot_pass() -> None:
    result = _contract().validate(_link(confidence=0.79))

    assert not result.passed_min_confidence
    assert result.final_verdict != GateVerdict.PASS


def test_missing_implication_outline_cannot_pass() -> None:
    result = _contract().validate(_link(outline=[]))

    assert not result.has_required_outline
    assert result.final_verdict != GateVerdict.PASS


def test_alignment_exception_requires_enum_and_verified_evidence() -> None:
    weak = _link(
        relation=GoalRelation.EQUIVALENT,
        confidence=0.2,
        outline=[],
    )

    without_evidence = _contract().validate(
        weak,
        exception_code=AlignmentExceptionCode.EXACT_EQUIVALENCE,
    )
    with_evidence = _contract().validate(
        weak,
        exception_code=AlignmentExceptionCode.EXACT_EQUIVALENCE,
        exception_evidence_ids=["exact-equivalence-check-a"],
    )

    assert without_evidence.final_verdict != GateVerdict.PASS
    assert without_evidence.exception_code is None
    assert with_evidence.final_verdict == GateVerdict.PASS
    assert with_evidence.exception_code == AlignmentExceptionCode.EXACT_EQUIVALENCE


def test_unknown_relation_creates_countermodel_action(tmp_path) -> None:
    config = make_proof_control_config(tmp_path / "runs", mode="active")
    store, registry, memory, graph, broker = make_broker_runtime(
        config, tmp_path / "runtime"
    )
    graph.add_obligation(
        ProofObligation(
            obligation_id="goal-a",
            problem_hash=PROBLEM_HASH,
            route_ids=[],
            kind=ObligationKind.MAIN_GOAL,
            statement="Derive the target conclusion.",
            normalized_statement="derive the target conclusion",
            priority=1.0,
            centrality=1.0,
        )
    )
    control = ProofControlLayer(
        config,
        store,
        None,
        graph,
        memory,
        broker,
        registry,
    )

    link = control.register_strategy(make_strategy(9, tag="unmatched-mechanism"))

    assert link is not None
    actions = [
        action
        for action in control.state.control_actions.values()
        if action.action_type == ControlActionType.CREATE_COUNTERMODEL_TASK
    ]
    assert len(actions) == 1
    assert actions[0].status == ControlActionStatus.EXECUTED
    assert link.countermodel_status == "pending"
    contract = next(iter(control.state.goal_alignment_contracts.values()))
    assert contract.countermodel_action_id == actions[0].action_id
    assert contract.final_verdict != GateVerdict.PASS
