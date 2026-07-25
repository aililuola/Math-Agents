from __future__ import annotations

from mathproofmesh.proof_control.controller import ProofControlLayer
from mathproofmesh.proof_control.goal_alignment import PremiseClosureAnalyzer
from mathproofmesh.proof_control.models import (
    ControlActionStatus,
    ControlActionType,
)
from mathproofmesh.schemas import ObligationKind, ProofObligation

from v07_helpers import (
    PROBLEM_HASH,
    make_broker_runtime,
    make_proof_control_config,
    make_strategy,
)


def _target(*, assumptions: list[str] | None = None) -> ProofObligation:
    return ProofObligation(
        obligation_id="direct-target",
        problem_hash=PROBLEM_HASH,
        route_ids=[],
        kind=ObligationKind.MAIN_GOAL,
        statement="The target relation holds.",
        normalized_statement="the target relation holds",
        assumptions=assumptions or [],
        priority=1.0,
        centrality=1.0,
    )


def test_premise_closure_requires_an_exact_match() -> None:
    analyzer = PremiseClosureAnalyzer()

    exact = analyzer.scan(
        _target(),
        given_assumptions=["The target relation holds."],
    )
    merely_related = analyzer.scan(
        _target(),
        given_assumptions=["A related relation holds."],
    )

    assert exact.verified
    assert exact.closure_type == "given_assumption"
    assert not merely_related.verified
    assert merely_related.closure_type == "none"


def test_direct_premise_action_does_not_close_obligation_itself(tmp_path) -> None:
    config = make_proof_control_config(tmp_path / "runs", mode="active")
    store, registry, memory, graph, broker = make_broker_runtime(
        config, tmp_path / "runtime"
    )
    target = graph.add_obligation(_target(assumptions=["The target relation holds."]))
    control = ProofControlLayer(
        config,
        store,
        None,
        graph,
        memory,
        broker,
        registry,
    )

    control.register_strategy(make_strategy(8, tag="direct-premise-scan"))

    actions = [
        action
        for action in control.state.control_actions.values()
        if action.action_type == ControlActionType.CLOSE_BY_DIRECT_PREMISE
    ]
    assert len(actions) == 1
    assert actions[0].status == ControlActionStatus.EXECUTED
    assert graph.get_obligation(target.obligation_id).status == "open"
    assert graph.get_obligation(target.obligation_id).evidence_message_ids == []
