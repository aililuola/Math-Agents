from __future__ import annotations

import pytest

from mathproofmesh.proof_control.controller import ProofControlLayer
from mathproofmesh.proof_control.models import (
    ControlActionStatus,
    ControlActionType,
)
from mathproofmesh.schemas import (
    ObligationKind,
    ProofDelta,
    ProofObligation,
    ProofStep,
)

from v07_helpers import (
    PROBLEM_HASH,
    make_broker_runtime,
    make_proof_control_config,
    make_strategy,
)


def _runtime(tmp_path):
    config = make_proof_control_config(tmp_path / "runs", mode="active")
    store, registry, memory, graph, broker = make_broker_runtime(
        config, tmp_path / "runtime"
    )
    strategy = make_strategy(31, tag="structural-descent")
    route = registry.register_route(strategy, route_id="route-induction")
    subgoal = graph.add_obligation(
        ProofObligation(
            obligation_id="subgoal-trigger",
            problem_hash=PROBLEM_HASH,
            route_ids=[route.route_id],
            kind=ObligationKind.SUBGOAL,
            statement="Establish the repeated-feature reduction.",
            normalized_statement="establish the repeated-feature reduction",
            priority=0.9,
            centrality=0.8,
        )
    )
    graph.add_obligation(
        ProofObligation(
            obligation_id="main-goal",
            problem_hash=PROBLEM_HASH,
            route_ids=[route.route_id],
            kind=ObligationKind.MAIN_GOAL,
            statement="Derive the final conclusion.",
            normalized_statement="derive the final conclusion",
            dependency_ids=[subgoal.obligation_id],
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
    return control, route, strategy, subgoal


def _register_trigger(control, strategy, subgoal) -> None:
    control.register_delta(
        ProofDelta(
            delta_id="delta-induction-trigger",
            problem_hash=PROBLEM_HASH,
            path_id="path-induction",
            strategy_id=strategy.strategy_id,
            parent_checkpoint_id="checkpoint-parent",
            agent_id="route-prover",
            round_index=2,
            segment_index=2,
            new_steps=[
                ProofStep(
                    step_id="step-induction-trigger",
                    statement=(
                        "Ordinary induction fails at the first occurrence "
                        "of the repeated feature."
                    ),
                    justification="The recursive predecessor has smaller support.",
                )
            ],
            remaining_subgoals=[subgoal.normalized_statement],
            current_goal=subgoal.normalized_statement,
        )
    )


def test_induction_candidate_binds_triggering_obligation(tmp_path) -> None:
    control, _route, strategy, subgoal = _runtime(tmp_path)

    _register_trigger(control, strategy, subgoal)

    proposals = list(control.state.induction_measures.values())
    assert proposals
    assert all(
        item.target_obligation_ids == [subgoal.obligation_id] for item in proposals
    )
    assert all("main-goal" not in item.target_obligation_ids for item in proposals)


def test_accepted_induction_enters_route_prompt(tmp_path) -> None:
    control, route, strategy, subgoal = _runtime(tmp_path)
    _register_trigger(control, strategy, subgoal)
    proposal = next(iter(control.state.induction_measures.values()))

    action = control.review_induction_measure(
        proposal.proposal_id,
        reviewer_agent_id="independent-referee",
        approved=True,
        review_evidence_ids=["review-induction-a"],
        current_round=3,
    )

    assert action.status == ControlActionStatus.EXECUTED
    assert action.action_type == ControlActionType.ACTIVATE_INDUCTION_MEASURE
    assert proposal.status == "accepted"
    hints = control.route_hints(route.route_id)
    active = hints["active_induction_schemes"]
    assert len(active) == 1
    assert active[0]["proposal_id"] == proposal.proposal_id
    assert active[0]["target_obligation_ids"] == [subgoal.obligation_id]
    assert active[0]["authority"] == "proof_plan_not_fact"


def test_induction_activation_requires_independent_review_evidence(tmp_path) -> None:
    control, _route, strategy, subgoal = _runtime(tmp_path)
    _register_trigger(control, strategy, subgoal)
    proposal = next(iter(control.state.induction_measures.values()))

    with pytest.raises(ValueError, match="review evidence"):
        control.review_induction_measure(
            proposal.proposal_id,
            reviewer_agent_id="independent-referee",
            approved=True,
            review_evidence_ids=[],
            current_round=3,
        )

    assert proposal.status == "candidate"


def test_non_well_founded_measure_is_rejected(tmp_path) -> None:
    control, _route, strategy, subgoal = _runtime(tmp_path)
    _register_trigger(control, strategy, subgoal)
    proposal = next(iter(control.state.induction_measures.values()))
    proposal.strict_decrease_argument = "Use an object of equal complexity."

    with pytest.raises(ValueError, match="well founded"):
        control.review_induction_measure(
            proposal.proposal_id,
            reviewer_agent_id="independent-referee",
            approved=True,
            review_evidence_ids=["review-induction-a"],
            current_round=3,
        )

    assert proposal.status == "candidate"


def test_induction_not_bound_to_main_goal_by_default(tmp_path) -> None:
    config = make_proof_control_config(tmp_path / "runs", mode="active")
    store, registry, memory, graph, broker = make_broker_runtime(
        config, tmp_path / "runtime"
    )
    strategy = make_strategy(32, tag="unbound-descent")
    registry.register_route(strategy, route_id="route-main-only")
    graph.add_obligation(
        ProofObligation(
            obligation_id="main-only",
            problem_hash=PROBLEM_HASH,
            route_ids=["route-main-only"],
            kind=ObligationKind.MAIN_GOAL,
            statement="Derive the final conclusion.",
            normalized_statement="derive the final conclusion",
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

    control.register_delta(
        ProofDelta(
            delta_id="delta-main-only-trigger",
            problem_hash=PROBLEM_HASH,
            path_id="path-main-only",
            strategy_id=strategy.strategy_id,
            parent_checkpoint_id="checkpoint-parent",
            agent_id="route-prover",
            round_index=2,
            segment_index=2,
            new_steps=[
                ProofStep(
                    step_id="step-main-only",
                    statement="Ordinary induction fails at the first occurrence.",
                    justification="A structural measure may be needed.",
                )
            ],
            remaining_subgoals=["Find a concrete local reduction first."],
            current_goal="Find a concrete local reduction first.",
        )
    )

    assert control.state.induction_measures == {}


def test_induction_activation_resume_is_exactly_once(tmp_path) -> None:
    control, _route, strategy, subgoal = _runtime(tmp_path)
    _register_trigger(control, strategy, subgoal)
    proposal = next(iter(control.state.induction_measures.values()))
    first = control.review_induction_measure(
        proposal.proposal_id,
        reviewer_agent_id="independent-referee",
        approved=True,
        review_evidence_ids=["review-induction-a"],
        current_round=3,
    )
    blueprint_ids = set(control.state.induction_blueprints)

    repeated = control.action_dispatcher.execute_sync(
        first.action_id,
        current_round=4,
    )

    assert repeated.action_id == first.action_id
    assert set(control.state.induction_blueprints) == blueprint_ids
    assert len(blueprint_ids) == 1
