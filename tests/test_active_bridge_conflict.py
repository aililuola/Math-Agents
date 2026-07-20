from __future__ import annotations

from mathproofmesh.agents import StructuredAgentRunner
from mathproofmesh.communication.broker import MessageBroker
from mathproofmesh.communication.route_registry import RouteRegistry
from mathproofmesh.llm.pool import AgentPool
from mathproofmesh.memory import LemmaMemory, TypedMemory
from mathproofmesh.mock_demo import demo_responders
from mathproofmesh.orchestrator import ProofMeshOrchestrator, SolveState
from mathproofmesh.proof_graph.bridges import BridgeBroker
from mathproofmesh.proof_graph.contradictions import ContradictionBroker
from mathproofmesh.proof_graph.store import ProofGraphStore
from mathproofmesh.prompts import PromptFactory
from mathproofmesh.schemas import (
    ActionKind,
    ClaimStatus,
    EvidenceType,
    MemoryTier,
    MessageEnvelope,
    MessageType,
    ObligationKind,
    ProblemContract,
    ProofObligation,
    RouteRole,
)
from mathproofmesh.store import ArtifactStore

from v07_helpers import make_strategy, make_v07_config


def _claim(
    problem_hash: str,
    *,
    message_id: str,
    route_id: str,
    agent_id: str,
    conclusion: str,
) -> MessageEnvelope:
    return MessageEnvelope(
        message_id=message_id,
        problem_hash=problem_hash,
        source_agent_id=agent_id,
        source_route_id=route_id,
        source_role=RouteRole.PROVER,
        message_type=MessageType.VERIFIED_LEMMA,
        statement="claim p",
        normalized_statement="claim p",
        conclusion=conclusion,
        evidence_type=EvidenceType.NATURAL_PROOF_AUDITED,
        memory_tier=MemoryTier.FACT,
        verification_status=ClaimStatus.VERIFIED,
        verification_confidence=0.99,
        normalization_confidence=1.0,
        round_created=1,
    )


async def test_active_bridge_and_conflict_tasks_run_through_typed_agents(
    tmp_path,
) -> None:
    config = make_v07_config(tmp_path / "runs", inspiration_mode="active")
    store = ArtifactStore(config.runtime.run_root, "active-bridge-conflict")
    problem = ProblemContract(
        exact_statement="Prove the scoped claim.",
        normalized_statement="Prove the scoped claim.",
        output_language="en",
    )
    strategies = [make_strategy(0), make_strategy(1)]
    registry = RouteRegistry(config, problem_hash=problem.integrity_hash)
    route_a = registry.register_route(strategies[0], route_id="route-a")
    route_b = registry.register_route(strategies[1], route_id="route-b")
    registry.assign_member(route_a.route_id, "explorer-a", RouteRole.PROVER, 0)
    registry.assign_member(route_b.route_id, "explorer-b", RouteRole.PROVER, 0)
    registry.set_neighbors(route_a.route_id, [route_b.route_id])
    registry.set_neighbors(route_b.route_id, [route_a.route_id])

    legacy_memory = LemmaMemory(store)
    typed_memory = TypedMemory(store, config, lemma_memory=legacy_memory)
    graph = ProofGraphStore(
        config,
        store,
        problem_hash=problem.integrity_hash,
    )
    broker = MessageBroker(
        config,
        store,
        None,
        registry,
        graph,
        typed_memory,
    )
    bridge = BridgeBroker(config, graph)
    conflicts = ContradictionBroker(config, graph)
    state = SolveState(
        triage=None,
        strategies=strategies,
        attempts=[],
        reports=[],
        aggregate_reports={},
        meta_reviews=[],
        checkpoints=[],
        route_registry=registry,
        message_broker=broker,
        proof_graph=graph,
        typed_memory=typed_memory,
        bridge_broker=bridge,
        contradiction_broker=conflicts,
        current_round=1,
        proof_debt_history={},
        route_team_reviews={},
    )

    for obligation_id, route_id in (("bridge-a", "route-a"), ("bridge-b", "route-b")):
        graph.add_obligation(
            ProofObligation(
                obligation_id=obligation_id,
                problem_hash=problem.integrity_hash,
                route_ids=[route_id],
                kind=ObligationKind.LEMMA,
                statement="common bridge lemma",
                normalized_statement="common bridge lemma",
                priority=0.9,
                centrality=0.9,
            )
        )
    assert bridge.detect(current_round=1)

    pool = AgentPool(config, mock_responders=demo_responders(config))
    runner = StructuredAgentRunner(config, pool, store)
    orchestrator = ProofMeshOrchestrator(config)
    prompts = PromptFactory("en")
    try:
        bridge_completed = await orchestrator._execute_cross_route_verification_task(
            state,
            ActionKind.BRIDGE,
            strategy_id=None,
            current_round=1,
            store=store,
            problem=problem,
            runner=runner,
            prompts=prompts,
        )
        assert bridge_completed is True
        assert bridge.completed_task_ids
        assert graph.get_obligation("bridge-a").status == "closed"
        assert graph.get_obligation("bridge-b").status == "closed"

        left = _claim(
            problem.integrity_hash,
            message_id="claim-left",
            route_id="route-a",
            agent_id="explorer-a",
            conclusion="p",
        )
        right = _claim(
            problem.integrity_hash,
            message_id="claim-right",
            route_id="route-b",
            agent_id="explorer-b",
            conclusion="not (p)",
        )
        graph.add_claim_node(left)
        graph.add_claim_node(right)
        records = conflicts.detect([left, right], current_round=1)
        assert len(records) == 1
        assert records[0].status == "open"

        conflict_completed = await orchestrator._execute_cross_route_verification_task(
            state,
            ActionKind.RESOLVE_CONFLICT,
            strategy_id=None,
            current_round=1,
            store=store,
            problem=problem,
            runner=runner,
            prompts=prompts,
        )
        assert conflict_completed is True
        assert conflicts.unresolved() == []
        resolution_id = records[0].resolution_message_id
        resolution = next(
            item for item in graph.claim_nodes if item.message_id == resolution_id
        )
        assert resolution.message_type == MessageType.CONTRADICTION_NOTICE
    finally:
        await pool.aclose()
