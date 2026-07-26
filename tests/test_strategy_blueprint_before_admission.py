from __future__ import annotations

from mathproofmesh.proof_control.models import BlueprintNodeKind, GateVerdict
from mathproofmesh.proof_control.strategy_blueprint import (
    BlueprintSemanticGate,
    StrategyBlueprintCompiler,
)

from v082_helpers import make_control_runtime, make_domain_strategy


def test_blueprint_contains_non_main_subgoal_and_path(tmp_path) -> None:
    *_runtime, main_goal = make_control_runtime(tmp_path)
    strategy = make_domain_strategy()
    compiler = StrategyBlueprintCompiler()

    compilation = compiler.compile(
        strategy,
        problem_hash=main_goal.problem_hash,
        main_goal=main_goal,
        open_obligations=[],
        admitted_facts=[],
        negative_memory=[],
    )

    blueprint = compilation.blueprint
    nodes = {item.node_id: item for item in compilation.nodes}
    edges = compilation.edges
    assert any(
        node.kind != BlueprintNodeKind.TARGET
        and node.node_id != blueprint.main_goal_node_id
        for node in nodes.values()
    )
    assert blueprint.complete_path_to_main_goal
    assert blueprint.direct_target_node_ids
    assert blueprint.main_goal_node_id not in blueprint.direct_target_node_ids
    assert all(edge.implication_outline for edge in edges)
    assert any(node.executable_first_step for node in nodes.values())


def test_blueprint_preserves_strategy_mechanism(tmp_path) -> None:
    *_runtime, main_goal = make_control_runtime(tmp_path)
    strategy = make_domain_strategy()
    compilation = StrategyBlueprintCompiler().compile(
        strategy,
        problem_hash=main_goal.problem_hash,
        main_goal=main_goal,
    )

    assessment = BlueprintSemanticGate().validate(
        compilation.blueprint,
        nodes=compilation.nodes,
        edges=compilation.edges,
        strategy=strategy,
        main_goal=main_goal,
    )

    assert assessment.accepted
    assert compilation.blueprint.preserves_mechanism_signature


def test_blueprint_compiler_runs_before_route_admission(tmp_path) -> None:
    *_runtime, control, main_goal = make_control_runtime(tmp_path)
    strategy = make_domain_strategy()

    admitted, records = control.admit_routes([strategy])

    assert admitted == [strategy]
    assert records[0].verdict in {GateVerdict.PASS, GateVerdict.SHADOW_BLOCK}
    blueprint = control.state.strategy_blueprints[strategy.strategy_id]
    binding = next(
        item
        for item in control.state.route_target_bindings.values()
        if item.strategy_id == strategy.strategy_id
    )
    assert binding.direct_target_obligation_id != main_goal.obligation_id
    assert blueprint.status == "accepted"
    event_types = [item["event_type"] for item in control.state.events]
    assert event_types.index("strategy_blueprint_compiled") < event_types.index(
        "route_admission_evaluated"
    )


def test_failed_blueprint_does_not_enter_core_graph(tmp_path) -> None:
    *_runtime, control, _main_goal = make_control_runtime(tmp_path)
    strategy = make_domain_strategy(expected_lemmas=["Find a suitable invariant."])
    before = {item.obligation_id for item in control.proof_graph.obligations}

    admitted, _records = control.admit_routes([strategy])

    after = {item.obligation_id for item in control.proof_graph.obligations}
    assert admitted == []
    assert after == before
    assert strategy.strategy_id in control.state.original_strategy_archive
    assert control.state.strategy_blueprints[strategy.strategy_id].status in {
        "needs_review",
        "rejected",
    }
