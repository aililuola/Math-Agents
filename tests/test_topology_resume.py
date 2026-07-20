from __future__ import annotations

from mathproofmesh.communication.route_registry import RouteRegistry
from mathproofmesh.inspiration.engine import InspirationEngine
from mathproofmesh.inspiration.trigger_policy import InspirationSnapshot
from mathproofmesh.memory import TypedMemory
from mathproofmesh.proof_graph.store import ProofGraphStore
from mathproofmesh.schemas import (
    InspirationMechanism,
    InspirationReview,
    InspirationTask,
    ObligationKind,
    ProblemContract,
    ProofObligation,
)

from v07_helpers import make_strategy, make_v07_config


async def test_inspiration_resume_does_not_materialize_proposal_twice(tmp_path) -> None:
    config = make_v07_config(tmp_path / "runs")
    problem = ProblemContract(
        exact_statement="Prove the target identity.",
        normalized_statement="prove the target identity",
    )
    graph = ProofGraphStore(config, problem_hash=problem.integrity_hash)
    graph.add_obligation(
        ProofObligation(
            obligation_id="open-goal",
            problem_hash=problem.integrity_hash,
            route_ids=["route-a"],
            kind=ObligationKind.MAIN_GOAL,
            statement="target identity",
            normalized_statement="target identity",
        )
    )
    registry = RouteRegistry(config, problem_hash=problem.integrity_hash)
    registry.register_route(make_strategy(0), route_id="route-a")
    memory = TypedMemory(None, config)
    engine = InspirationEngine(
        config,
        problem=problem,
        proof_graph=graph,
        typed_memory=memory,
        route_registry=registry,
        project_root=tmp_path,
    )
    snapshot = InspirationSnapshot(
        round_index=2,
        active_route_ids=["route-a"],
        stagnation_rounds_by_route={"route-a": 2},
        remaining_calls=40,
        finalization_reserve_calls=8,
        current_path_count=1,
        max_paths=8,
        open_obligation_ids=["open-goal"],
    )
    engine.detect_triggers(snapshot)
    task = InspirationTask(
        trigger_id=next(iter(engine.triggers)),
        mechanism=InspirationMechanism.AUXILIARY_CONSTRUCTION,
        target_route_ids=["route-a"],
        target_obligation_ids=["open-goal"],
        reason="stagnation",
    )
    proposal = (await engine.generate([task]))[0]
    review = InspirationReview(
        proposal_id=proposal.proposal_id,
        reviewer_agent_id="independent-referee",
        semantically_distinct=True,
        relevant_to_open_obligation=True,
        internally_coherent=True,
        recommendation="attach_to_existing_route",
        confidence=0.9,
    )
    first = engine.materialize([review], snapshot)
    state = engine.export_state()

    restored = InspirationEngine(
        config,
        problem=problem,
        proof_graph=graph,
        typed_memory=memory,
        route_registry=registry,
        project_root=tmp_path,
    )
    restored.restore_state(state)
    assert first
    assert restored.materialize([review], snapshot) == []


async def test_inspiration_shadow_records_decision_without_state_mutation(
    tmp_path,
) -> None:
    config = make_v07_config(tmp_path / "runs", inspiration_mode="shadow")
    problem = ProblemContract(
        exact_statement="Prove the target identity.",
        normalized_statement="prove the target identity",
    )
    graph = ProofGraphStore(config, problem_hash=problem.integrity_hash)
    graph.add_obligation(
        ProofObligation(
            obligation_id="open-goal",
            problem_hash=problem.integrity_hash,
            route_ids=["route-a"],
            kind=ObligationKind.MAIN_GOAL,
            statement="target identity",
            normalized_statement="target identity",
        )
    )
    registry = RouteRegistry(config, problem_hash=problem.integrity_hash)
    registry.register_route(make_strategy(0), route_id="route-a")
    memory = TypedMemory(None, config)
    engine = InspirationEngine(
        config,
        problem=problem,
        proof_graph=graph,
        typed_memory=memory,
        route_registry=registry,
        project_root=tmp_path,
    )
    snapshot = InspirationSnapshot(
        round_index=2,
        active_route_ids=["route-a"],
        remaining_calls=40,
        current_path_count=1,
        max_paths=8,
        open_obligation_ids=["open-goal"],
    )
    trigger = engine.detect_triggers(
        snapshot.model_copy(update={"manual_trigger": True})
    )[0]
    task = InspirationTask(
        trigger_id=trigger.trigger_id,
        mechanism=InspirationMechanism.AUXILIARY_CONSTRUCTION,
        target_route_ids=["route-a"],
        target_obligation_ids=["open-goal"],
        reason="manual shadow audit",
    )
    proposal = (await engine.generate([task]))[0]
    review = InspirationReview(
        proposal_id=proposal.proposal_id,
        reviewer_agent_id="independent-referee",
        semantically_distinct=True,
        relevant_to_open_obligation=True,
        internally_coherent=True,
        recommendation="attach_to_existing_route",
        confidence=0.9,
    )
    before_graph = graph.export_state()
    before_routes = registry.export_state()
    decision = engine.materialize([review], snapshot)[0]
    assert decision.action == "shadow_only"
    assert memory.facts == memory.insights == memory.negatives == []
    assert graph.export_state() == before_graph
    assert registry.export_state() == before_routes


async def test_active_surprise_can_create_one_novel_route_within_budget(
    tmp_path,
) -> None:
    config = make_v07_config(tmp_path / "runs")
    problem = ProblemContract(
        exact_statement="Prove the target identity.",
        normalized_statement="prove the target identity",
    )
    graph = ProofGraphStore(config, problem_hash=problem.integrity_hash)
    graph.add_obligation(
        ProofObligation(
            obligation_id="open-goal",
            problem_hash=problem.integrity_hash,
            route_ids=["route-a"],
            kind=ObligationKind.MAIN_GOAL,
            statement="target identity",
            normalized_statement="target identity",
        )
    )
    registry = RouteRegistry(config, problem_hash=problem.integrity_hash)
    registry.register_route(make_strategy(0), route_id="route-a")
    memory = TypedMemory(None, config)
    engine = InspirationEngine(
        config,
        problem=problem,
        proof_graph=graph,
        typed_memory=memory,
        route_registry=registry,
        project_root=tmp_path,
    )
    snapshot = InspirationSnapshot(
        round_index=2,
        active_route_ids=["route-a"],
        remaining_calls=40,
        current_path_count=1,
        max_paths=8,
        open_obligation_ids=["open-goal"],
    )
    trigger = engine.detect_triggers(
        snapshot.model_copy(update={"manual_trigger": True})
    )[0]
    task = InspirationTask(
        trigger_id=trigger.trigger_id,
        mechanism=InspirationMechanism.SURPRISE_EXPLORATION,
        target_obligation_ids=["open-goal"],
        reason="manual surprise audit",
    )
    proposal = (await engine.generate([task]))[0]
    review = InspirationReview(
        proposal_id=proposal.proposal_id,
        reviewer_agent_id="independent-referee",
        semantically_distinct=True,
        relevant_to_open_obligation=True,
        internally_coherent=True,
        recommendation="create_new_route",
        confidence=0.9,
    )
    decision = engine.materialize([review], snapshot)[0]
    assert decision.action == "route_created"
    assert decision.route_id is not None
    assert len(registry.routes) == 2
    assert memory.insights
