from __future__ import annotations

from mathproofmesh.communication.route_registry import RouteRegistry
from mathproofmesh.inspiration.engine import InspirationEngine
from mathproofmesh.inspiration.trigger_policy import InspirationSnapshot
from mathproofmesh.memory import TypedMemory
from mathproofmesh.proof_graph.store import ProofGraphStore
from mathproofmesh.schemas import (
    InspirationMechanism,
    InspirationTask,
    MetaStrategyDecision,
    ProblemContract,
    RouteStatus,
)

from v07_helpers import make_strategy, make_v07_config


def _engine(tmp_path):
    config = make_v07_config(tmp_path / "runs")
    problem = ProblemContract(
        exact_statement="Prove the target.",
        normalized_statement="prove target",
    )
    registry = RouteRegistry(config, problem_hash=problem.integrity_hash)
    registry.register_route(make_strategy(0), route_id="route-a")
    registry.register_route(make_strategy(1), route_id="route-b")
    engine = InspirationEngine(
        config,
        problem=problem,
        proof_graph=ProofGraphStore(config, problem_hash=problem.integrity_hash),
        typed_memory=TypedMemory(None, config),
        route_registry=registry,
        project_root=tmp_path,
    )
    return config, problem, registry, engine


def test_meta_strategy_becomes_audited_control_task_not_an_insight(tmp_path) -> None:
    _config, _problem, registry, engine = _engine(tmp_path)
    snapshot = InspirationSnapshot(
        round_index=4,
        active_route_ids=["route-a", "route-b"],
        route_redundancy=0.95,
        remaining_calls=30,
        finalization_reserve_calls=8,
        current_path_count=2,
        max_paths=8,
        open_obligation_ids=["goal"],
    )
    trigger = engine.detect_triggers(snapshot)[0]
    task = InspirationTask(
        task_id="meta-task",
        trigger_id=trigger.trigger_id,
        mechanism=InspirationMechanism.META_REPLAN,
        target_route_ids=["route-a", "route-b"],
        target_obligation_ids=["goal"],
        reason="routes repeat the same mechanism",
    )
    decision = MetaStrategyDecision(
        decision_id="meta-decision",
        round_index=999,
        action="switch_representation",
        affected_route_ids=["route-a", "unknown-route"],
        selected_mechanism=InspirationMechanism.REPRESENTATION_SWITCH,
        observable_metrics={"fabricated": 1},
        reason="the observed route portfolio is redundant",
        estimated_calls=1,
    )

    execution = engine.register_meta_decision(task, decision, state=snapshot)

    assert execution is not None
    assert execution.status == "executed"
    assert len(execution.generated_task_ids) == 1
    queued = engine.pending_directive_tasks[execution.generated_task_ids[0]]
    assert queued.mechanism == InspirationMechanism.REPRESENTATION_SWITCH
    assert queued.target_route_ids == ["route-a"]
    assert engine.proposals == {}
    assert engine.typed_memory.insights == []
    directive = next(iter(engine.meta_directives.values()))
    assert directive.observable_evidence["route_redundancy"] == 0.95
    assert "fabricated" not in directive.observable_evidence
    assert registry.get("route-a").status == RouteStatus.ACTIVE


def test_audited_cooldown_mutates_route_registry_and_resumes_exactly_once(
    tmp_path,
) -> None:
    config, problem, registry, engine = _engine(tmp_path)
    snapshot = InspirationSnapshot(
        round_index=5,
        active_route_ids=["route-a", "route-b"],
        stagnation_rounds_by_route={"route-a": 3},
        remaining_calls=30,
        finalization_reserve_calls=8,
    )
    task = InspirationTask(
        task_id="cooldown-task",
        trigger_id="manual-trigger",
        mechanism=InspirationMechanism.META_REPLAN,
        target_route_ids=["route-a"],
        reason="route-a is stalled",
    )
    decision = MetaStrategyDecision(
        decision_id="cooldown-decision",
        round_index=5,
        action="cooldown_route",
        affected_route_ids=["route-a"],
        observable_metrics={"stagnation_rounds": 3},
        reason="route-a has not reduced proof debt",
    )
    first = engine.register_meta_decision(task, decision, state=snapshot)
    assert first is not None and first.status == "executed"
    assert registry.get("route-a").status == RouteStatus.COOLING

    state = engine.export_state()
    restored = InspirationEngine(
        config,
        problem=problem,
        proof_graph=engine.proof_graph,
        typed_memory=engine.typed_memory,
        route_registry=registry,
        project_root=tmp_path,
    )
    restored.restore_state(state)
    again = restored.register_meta_decision(task, decision, state=snapshot)

    assert again == first
    assert len(restored.meta_directive_executions) == 1
    assert restored.typed_memory.insights == []


def test_shadow_meta_directive_never_mutates_route_control(tmp_path) -> None:
    config, problem, registry, _engine_active = _engine(tmp_path)
    config.topology.inspiration.mode = "shadow"
    engine = InspirationEngine(
        config,
        problem=problem,
        proof_graph=ProofGraphStore(config, problem_hash=problem.integrity_hash),
        typed_memory=TypedMemory(None, config),
        route_registry=registry,
        project_root=tmp_path,
    )
    snapshot = InspirationSnapshot(
        round_index=5,
        active_route_ids=["route-a", "route-b"],
        stagnation_rounds_by_route={"route-a": 3},
        remaining_calls=30,
    )
    task = InspirationTask(
        task_id="shadow-meta",
        trigger_id="manual-trigger",
        mechanism=InspirationMechanism.META_REPLAN,
        target_route_ids=["route-a"],
        reason="shadow observation",
    )
    execution = engine.register_meta_decision(
        task,
        MetaStrategyDecision(
            decision_id="shadow-cooldown",
            round_index=5,
            action="cooldown_route",
            affected_route_ids=["route-a"],
            observable_metrics={"stagnation_rounds": 3},
            reason="route appears stalled",
        ),
        state=snapshot,
    )

    assert execution is not None and execution.status == "noop"
    assert registry.get("route-a").status == RouteStatus.ACTIVE
    assert engine.pending_directive_tasks == {}
