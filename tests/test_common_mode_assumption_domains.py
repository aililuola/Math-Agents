from __future__ import annotations

from mathproofmesh.proof_control.common_mode import CriticalAssumptionMatrix
from mathproofmesh.proof_control.controller import ProofControlLayer
from mathproofmesh.proof_control.models import (
    AssumptionDomain,
    ControlActionStatus,
    ControlActionType,
)
from mathproofmesh.schemas import (
    CriticalClaim,
    ObligationKind,
    ProofObligation,
    RouteDescriptor,
)

from v07_helpers import (
    PROBLEM_HASH,
    make_broker_runtime,
    make_proof_control_config,
    make_strategy,
)


def _routes_and_strategies():
    left = make_strategy(51, tag="order-route-a").model_copy(
        update={
            "prerequisites": ["The transformation preserves order."],
            "critical_claims": [],
        }
    )
    right = make_strategy(52, tag="order-route-b").model_copy(
        update={
            "prerequisites": [
                "Order remains invariant under the transformation.",
                "Output JSON and retain the goal hash.",
            ],
            "critical_claims": [],
        }
    )
    routes = [
        RouteDescriptor(
            route_id="route-a",
            strategy_id=left.strategy_id,
            mechanism_signature=["transform", "order"],
        ),
        RouteDescriptor(
            route_id="route-b",
            strategy_id=right.strategy_id,
            mechanism_signature=["order", "invariance"],
        ),
    ]
    return routes, [left, right]


def test_protocol_constraints_excluded_from_common_mode() -> None:
    routes, strategies = _routes_and_strategies()
    matrix = CriticalAssumptionMatrix()

    assumptions = matrix.build(routes, strategies)

    assert assumptions
    assert all(
        item.domain == AssumptionDomain.MATHEMATICAL for item in assumptions.values()
    )
    assert all(
        "output json" not in item.normalized_statement for item in assumptions.values()
    )
    assert any(
        record.domain == AssumptionDomain.PROTOCOL
        for record in matrix.domain_records.values()
    )


def test_semantically_related_assumptions_form_family() -> None:
    routes, strategies = _routes_and_strategies()
    matrix = CriticalAssumptionMatrix()

    matrix.build(routes, strategies)

    families = list(matrix.families.values())
    assert len(families) == 1
    assert families[0].route_ids == ["route-a", "route-b"]
    assert len(families[0].member_assumption_ids) == 2
    assert {"order", "preservation", "transformation"} <= set(families[0].semantic_tags)


def test_common_mode_family_creates_challenger(tmp_path) -> None:
    config = make_proof_control_config(tmp_path / "runs", mode="active")
    config.topology.proof_control.common_mode.min_routes = 2
    store, registry, memory, graph, broker = make_broker_runtime(
        config, tmp_path / "runtime", route_count=0
    )
    routes, strategies = _routes_and_strategies()
    for route, strategy in zip(routes, strategies):
        registry.register_route(strategy, route_id=route.route_id)
    graph.add_obligation(
        ProofObligation(
            obligation_id="main-common-mode",
            problem_hash=PROBLEM_HASH,
            route_ids=[item.route_id for item in routes],
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

    control.update_after_round(strategies=strategies, current_round=1)

    assert len(control.state.assumption_families) == 1
    assert len(control.state.assumption_challenger_tasks) == 1
    actions = [
        item
        for item in control.state.control_actions.values()
        if item.action_type == ControlActionType.CREATE_ASSUMPTION_CHALLENGER
    ]
    assert len(actions) == 1
    assert actions[0].status == ControlActionStatus.EXECUTED
    task = next(iter(control.state.assumption_challenger_tasks.values()))
    assert task.premise_eligible is False
    assert "seek an exact counterexample" in task.required_actions


def test_multiple_agent_agreement_does_not_promote_assumption() -> None:
    strategy = make_strategy(53, tag="single-route").model_copy(
        update={
            "prerequisites": ["The map preserves order."],
            "critical_claims": [],
        }
    )
    route = RouteDescriptor(
        route_id="route-single",
        strategy_id=strategy.strategy_id,
        mechanism_signature=["order"],
    )
    matrix = CriticalAssumptionMatrix()

    assumptions = matrix.build([route], [strategy])

    assumption = next(iter(assumptions.values()))
    assert assumption.verification_status.value == "proposed"
    assert matrix.risks() == []


def test_non_propositional_bottleneck_is_not_a_common_mode_assumption() -> None:
    strategy = make_strategy(54, tag="diagram-route").model_copy(
        update={
            "prerequisites": [
                "Every downstream artifact must retain the frozen goal hash."
            ],
            "critical_claims": [
                CriticalClaim(
                    claim_id="critical-diagram",
                    statement="formalizing the diagram without a picture",
                    necessity="required",
                    status="needs_check",
                    falsification_test="inspect the construction",
                )
            ],
        }
    )
    route = RouteDescriptor(
        route_id="route-diagram",
        strategy_id=strategy.strategy_id,
        mechanism_signature=["diagram"],
    )
    matrix = CriticalAssumptionMatrix()

    assumptions = matrix.build([route], [strategy])

    assert assumptions == {}
    domains = {record.domain for record in matrix.domain_records.values()}
    assert AssumptionDomain.PROTOCOL in domains
