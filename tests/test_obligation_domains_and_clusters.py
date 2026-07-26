from __future__ import annotations

from mathproofmesh.proof_control.controller import ProofControlLayer
from mathproofmesh.proof_control.models import (
    ControlActionStatus,
    ControlActionType,
    ObligationDomain,
    ObligationDomainRecord,
)
from mathproofmesh.proof_control.proof_roles import core_proof_debt
from mathproofmesh.schemas import ObligationKind, ProofObligation, StrategyCard

from v07_helpers import (
    PROBLEM_HASH,
    make_broker_runtime,
    make_proof_control_config,
    make_strategy,
)


def _obligation(
    obligation_id: str,
    statement: str,
    *,
    route_ids: list[str],
    kind: ObligationKind = ObligationKind.SUBGOAL,
    dependency_ids: list[str] | None = None,
) -> ProofObligation:
    return ProofObligation(
        obligation_id=obligation_id,
        problem_hash=PROBLEM_HASH,
        route_ids=route_ids,
        kind=kind,
        statement=statement,
        normalized_statement=statement.casefold().rstrip("."),
        dependency_ids=dependency_ids or [],
        priority=1.0,
        centrality=1.0,
    )


def test_process_obligation_excluded_from_core_debt(tmp_path) -> None:
    config = make_proof_control_config(tmp_path / "runs", mode="active")
    _store, _registry, _memory, graph, _broker = make_broker_runtime(
        config, tmp_path / "runtime"
    )
    process = graph.add_obligation(
        _obligation(
            "process-obligation",
            "Follow the checkpoint policy before continuing.",
            route_ids=["route-a"],
        )
    )
    graph.add_obligation(
        _obligation(
            "main-goal",
            "Derive the final conclusion.",
            route_ids=["route-a"],
            kind=ObligationKind.MAIN_GOAL,
            dependency_ids=[process.obligation_id],
        )
    )
    domains = {
        process.obligation_id: ObligationDomainRecord(
            obligation_id=process.obligation_id,
            domain=ObligationDomain.PROCESS,
            inferred_from="statement",
            confidence=1.0,
        )
    }

    debt = core_proof_debt(
        graph,
        "route-a",
        config=config.topology.proof_control.core_debt,
        obligation_domains=domains,
    )

    expected_main_only = config.topology.proof_control.core_debt.main_goal_weight * 2.0
    assert debt == expected_main_only


def test_verification_obligation_not_used_as_route_target(tmp_path) -> None:
    config = make_proof_control_config(tmp_path / "runs", mode="active")
    store, registry, memory, graph, broker = make_broker_runtime(
        config, tmp_path / "runtime", route_count=0
    )
    verification = graph.add_obligation(
        _obligation(
            "verification-task",
            "Ask an independent reviewer to verify the proof.",
            route_ids=[],
        )
    )
    main = graph.add_obligation(
        _obligation(
            "main-goal",
            "Derive the final conclusion.",
            route_ids=[],
            kind=ObligationKind.MAIN_GOAL,
        )
    )
    strategy = StrategyCard(
        strategy_id="strategy-verification-decoy",
        title="Mathematical route",
        core_idea="Derive the final conclusion from a local relation.",
        independence_basis="A direct mathematical reduction.",
        expected_lemmas=[verification.normalized_statement],
        bottleneck=verification.normalized_statement,
        falsification_test="Test one finite boundary case.",
        estimated_success=0.6,
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

    control.register_obligation(verification)
    control.register_strategy(strategy)

    binding = next(iter(control.state.route_target_bindings.values()))
    assert (
        control.state.obligation_domains[verification.obligation_id].domain
        == ObligationDomain.VERIFICATION
    )
    assert binding.direct_target_obligation_id == main.obligation_id


def test_semantic_obligations_materialize_cluster(tmp_path) -> None:
    config = make_proof_control_config(tmp_path / "runs", mode="active")
    store, registry, memory, graph, broker = make_broker_runtime(
        config, tmp_path / "runtime", route_count=0
    )
    strategies = [
        make_strategy(61, tag="cluster-route-a"),
        make_strategy(62, tag="cluster-route-b"),
    ]
    routes = [
        registry.register_route(strategies[0], route_id="route-a"),
        registry.register_route(strategies[1], route_id="route-b"),
    ]
    first = graph.add_obligation(
        _obligation(
            "bottleneck-a",
            "Every local object satisfies the shared relation.",
            route_ids=[routes[0].route_id],
        )
    )
    second = graph.add_obligation(
        _obligation(
            "bottleneck-b",
            "Every local object satisfies the shared relation.",
            route_ids=[routes[1].route_id],
        )
    )
    graph.add_obligation(
        _obligation(
            "main-cluster",
            "Every admissible object satisfies the final relation.",
            route_ids=[item.route_id for item in routes],
            kind=ObligationKind.MAIN_GOAL,
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

    control.update_after_round(
        strategies=strategies,
        current_round=config.topology.proof_control.bottleneck.compression_interval_rounds,
    )

    assert len(control.state.bottleneck_clusters) == 1
    cluster = next(iter(control.state.bottleneck_clusters.values()))
    assert set(cluster.member_obligation_ids) == {
        first.obligation_id,
        second.obligation_id,
    }
    assert (
        graph.get_obligation(first.obligation_id).obligation_id == first.obligation_id
    )
    assert (
        graph.get_obligation(second.obligation_id).obligation_id == second.obligation_id
    )
    assert control.state.bottleneck_aliases[first.obligation_id] == (
        cluster.canonical_obligation_id
    )
    assert control.state.bottleneck_aliases[second.obligation_id] == (
        cluster.canonical_obligation_id
    )
    assert cluster.bridge_task_id in control.state.bottleneck_bridge_tasks
    bridge_task = control.state.bottleneck_bridge_tasks[cluster.bridge_task_id]
    assert bridge_task.target_obligation_id == cluster.canonical_obligation_id
    actions = [
        item
        for item in control.state.control_actions.values()
        if item.action_type == ControlActionType.MATERIALIZE_BOTTLENECK_CLUSTER
    ]
    assert len(actions) == 1
    assert actions[0].status == ControlActionStatus.EXECUTED
