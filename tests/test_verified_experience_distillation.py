from __future__ import annotations

from mathproofmesh.communication.route_registry import RouteRegistry
from mathproofmesh.inspiration.engine import InspirationEngine
from mathproofmesh.inspiration.trigger_policy import InspirationSnapshot
from mathproofmesh.memory import TypedMemory
from mathproofmesh.proof_graph.store import ProofGraphStore
from mathproofmesh.schemas import (
    AnalogyMapping,
    ClaimStatus,
    EvidenceType,
    InspirationMechanism,
    InspirationReview,
    InspirationTask,
    MemoryTier,
    MessageEnvelope,
    MessageType,
    NoveltySignature,
    ObligationKind,
    ProblemContract,
    ProofObligation,
    RouteRole,
)

from v07_helpers import make_strategy, make_v07_config


def _runtime(tmp_path):
    config = make_v07_config(tmp_path / "runs")
    problem = ProblemContract(
        exact_statement="Prove the target identity.",
        normalized_statement="prove target identity",
    )
    graph = ProofGraphStore(config, problem_hash=problem.integrity_hash)
    graph.add_obligation(
        ProofObligation(
            obligation_id="goal",
            problem_hash=problem.integrity_hash,
            route_ids=["route-a"],
            kind=ObligationKind.LEMMA,
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
        round_index=3,
        domain="proof",
        active_route_ids=["route-a"],
        stagnation_rounds_by_route={"route-a": 3},
        proof_debt_by_route={"route-a": graph.proof_debt("route-a")},
        remaining_calls=30,
        current_path_count=1,
        max_paths=8,
        open_obligation_ids=["goal"],
        obligation_kinds={"goal": ObligationKind.LEMMA.value},
        manual_trigger=True,
    )
    trigger = engine.detect_triggers(snapshot)[0]
    return config, problem, graph, memory, engine, snapshot, trigger


async def test_only_fact_gated_proposal_enters_verified_experience_library(
    tmp_path,
) -> None:
    _config, problem, _graph, memory, engine, snapshot, trigger = _runtime(tmp_path)
    task = InspirationTask(
        task_id="construction-task",
        trigger_id=trigger.trigger_id,
        mechanism=InspirationMechanism.AUXILIARY_CONSTRUCTION,
        target_route_ids=["route-a"],
        target_obligation_ids=["goal"],
        reason="invent a missing object",
    )
    proposal = (await engine.generate([task]))[0]
    assert engine.verified_experiences == {}

    fact = MessageEnvelope(
        message_id="verified-fact",
        problem_hash=problem.integrity_hash,
        source_agent_id="route-prover",
        source_route_id="route-a",
        source_role=RouteRole.PROVER,
        message_type=MessageType.VERIFIED_LEMMA,
        statement="the auxiliary construction proves the target identity",
        normalized_statement="the auxiliary construction proves the target identity",
        conclusion="the auxiliary construction proves the target identity",
        evidence_type=EvidenceType.NATURAL_PROOF_AUDITED,
        memory_tier=MemoryTier.FACT,
        verification_status=ClaimStatus.VERIFIED,
        verification_confidence=0.95,
        normalization_confidence=1.0,
        round_created=3,
    )
    memory.add_fact(fact, referee_agent_id="independent-referee")
    engine.mark_verified(proposal.proposal_id, fact.message_id)

    assert len(engine.verified_experiences) == 1
    experience = next(iter(engine.verified_experiences.values()))
    assert experience.verified is True
    assert experience.transferable_lemmas == [fact.statement]
    assert experience.obligation_kinds == [ObligationKind.LEMMA.value]
    assert experience.non_transferable_conditions
    assert any(
        item.get("record_id") == experience.record_id
        for item in engine.analogy_library.records
    )
    assert engine.outcome_ledger.outcomes[proposal.proposal_id].verified_fact_gain == 1

    restored = InspirationEngine(
        engine.config,
        problem=problem,
        proof_graph=engine.proof_graph,
        typed_memory=memory,
        route_registry=engine.route_registry,
        project_root=tmp_path,
    )
    restored.restore_state(engine.export_state())
    assert experience.record_id in restored.verified_experiences
    assert proposal.proposal_id in restored.outcome_ledger.outcomes
    assert any(
        item.get("record_id") == experience.record_id
        for item in restored.analogy_library.records
    )


async def test_rejected_analogy_enters_negative_not_positive_library(tmp_path) -> None:
    _config, problem, _graph, _memory, engine, snapshot, trigger = _runtime(tmp_path)
    task = InspirationTask(
        task_id="analogy-task",
        trigger_id=trigger.trigger_id,
        mechanism=InspirationMechanism.STRUCTURAL_ANALOGY,
        target_route_ids=["route-a"],
        target_obligation_ids=["goal"],
        reason="test a local analogy",
    )
    mapping = AnalogyMapping(
        source_record_id="source-experience",
        source_problem_summary="a superficially similar identity",
        target_problem_hash=problem.integrity_hash,
        object_correspondence={"source object": "target object"},
        operation_correspondence={"source operation": "target operation"},
        transferable_lemmas=["candidate transferred lemma"],
        non_transferable_conditions=["the source assumes positivity"],
        transfer_risks=["the target has no positivity hypothesis"],
        novelty_signature=NoveltySignature(
            mechanism_tags=["structural_analogy"],
            core_objects=["target object"],
            targeted_obligation_ids=["goal"],
        ),
    )
    proposal = engine.register_agent_artifact(
        task,
        mapping,
        source_agent_id="analogy-author",
        state=snapshot,
    )
    assert proposal is not None
    review = InspirationReview(
        proposal_id=proposal.proposal_id,
        reviewer_agent_id="independent-referee",
        semantically_distinct=True,
        relevant_to_open_obligation=True,
        internally_coherent=False,
        hidden_assumptions=["positivity is missing in the target"],
        recommendation="reject",
        confidence=0.95,
    )
    reviewed = (
        await engine.review(
            [proposal], precomputed_reviews={proposal.proposal_id: review}
        )
    )[0]
    decision = engine.materialize([reviewed], snapshot)[0]

    assert decision.action == "rejected"
    assert engine.verified_experiences == {}
    assert len(engine.negative_analogy_records) == 1
    negative = next(iter(engine.negative_analogy_records.values()))
    assert negative.source_record_id == "source-experience"
    assert "positivity" in negative.failure_reason
    assert engine.outcome_ledger.outcomes[proposal.proposal_id].refuted is True
    engine.analogy_library.add_verified_record(
        {
            "record_id": "source-experience",
            "verified": True,
            "problem_summary": "target identity positivity",
            "proof_summary": "transfer by positivity",
        }
    )
    assert (
        engine.analogy_library.search(
            query_text="target identity positivity",
            problem_hash=problem.integrity_hash,
        )
        == []
    )

    restored = InspirationEngine(
        engine.config,
        problem=problem,
        proof_graph=engine.proof_graph,
        typed_memory=engine.typed_memory,
        route_registry=engine.route_registry,
        project_root=tmp_path,
    )
    restored.restore_state(engine.export_state())
    assert negative.record_id in restored.negative_analogy_records
    assert any(
        item.get("record_id") == negative.record_id
        for item in restored.analogy_library.negative_records
    )
