from __future__ import annotations

import json

from mathproofmesh.communication.route_registry import RouteRegistry
from mathproofmesh.inspiration.context import build_inspiration_prompt_context
from mathproofmesh.inspiration.engine import InspirationEngine
from mathproofmesh.inspiration.trigger_policy import InspirationSnapshot
from mathproofmesh.memory import TypedMemory
from mathproofmesh.proof_graph.store import ProofGraphStore
from mathproofmesh.schemas import (
    ClaimStatus,
    EvidenceType,
    InspirationContextMode,
    InspirationMechanism,
    InspirationProposal,
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


class _AdmittedFactBroker:
    def __init__(self, facts: list[MessageEnvelope]) -> None:
        self._facts = facts

    def admitted_facts(self) -> list[MessageEnvelope]:
        return list(self._facts)

    def is_globally_admitted_fact(self, message_id: str) -> bool:
        return any(item.message_id == message_id for item in self._facts)


def _fact(problem_hash: str, identifier: str, statement: str) -> MessageEnvelope:
    return MessageEnvelope(
        message_id=identifier,
        problem_hash=problem_hash,
        source_agent_id="author-a",
        source_route_id="route-a",
        source_role=RouteRole.PROVER,
        message_type=MessageType.VERIFIED_LEMMA,
        statement=statement,
        normalized_statement=statement.casefold(),
        conclusion=statement,
        evidence_type=EvidenceType.NATURAL_PROOF_AUDITED,
        memory_tier=MemoryTier.FACT,
        verification_status=ClaimStatus.VERIFIED,
        verification_confidence=0.95,
        normalization_confidence=0.95,
        round_created=1,
    )


def _negative(problem_hash: str) -> MessageEnvelope:
    statement = "The direct parity split fails at the target bridge."
    return MessageEnvelope(
        message_id="negative-1",
        problem_hash=problem_hash,
        source_agent_id="skeptic-a",
        source_route_id="route-a",
        source_role=RouteRole.SKEPTIC,
        message_type=MessageType.COUNTEREXAMPLE,
        statement=statement,
        normalized_statement=statement.casefold(),
        conclusion=statement,
        evidence_type=EvidenceType.COUNTEREXAMPLE,
        memory_tier=MemoryTier.NEGATIVE,
        verification_status=ClaimStatus.REJECTED,
        verification_confidence=1.0,
        normalization_confidence=1.0,
        round_created=1,
    )


def _engine(tmp_path):
    config = make_v07_config(tmp_path / "runs")
    problem = ProblemContract(
        exact_statement="Prove the target bridge by a structural mechanism.",
        normalized_statement="prove target bridge structural mechanism",
    )
    graph = ProofGraphStore(config, problem_hash=problem.integrity_hash)
    graph.add_obligation(
        ProofObligation(
            obligation_id="goal-1",
            problem_hash=problem.integrity_hash,
            route_ids=["route-a"],
            kind=ObligationKind.LEMMA,
            statement="Close the target bridge.",
            normalized_statement="close target bridge",
        )
    )
    registry = RouteRegistry(config, problem_hash=problem.integrity_hash)
    registry.register_route(
        make_strategy(0, tag="modular arithmetic"), route_id="route-a"
    )
    memory = TypedMemory(None, config)
    memory.add_negative(_negative(problem.integrity_hash))
    admitted = _fact(
        problem.integrity_hash, "fact-admitted", "A target bridge identity"
    )
    engine = InspirationEngine(
        config,
        problem=problem,
        proof_graph=graph,
        typed_memory=memory,
        route_registry=registry,
        broker=_AdmittedFactBroker([admitted]),
        project_root=tmp_path,
    )
    snapshot = InspirationSnapshot(
        round_index=3,
        active_route_ids=["route-a"],
        route_signatures=[
            engine.mechanism_normalizer.signature_from_route_tags(
                ["modular arithmetic"], targeted_obligation_ids=["goal-1"]
            )
        ],
        stagnation_rounds_by_route={"route-a": 2},
        proof_debt_by_route={"route-a": 0.8},
        open_obligation_ids=["goal-1"],
        remaining_calls=40,
        current_path_count=1,
        max_paths=8,
    )
    return engine, snapshot


def test_warm_and_cold_contexts_are_bounded_and_distinct(tmp_path) -> None:
    engine, snapshot = _engine(tmp_path)
    task = InspirationTask(
        task_id="task-1",
        trigger_id="trigger-1",
        mechanism=InspirationMechanism.AUXILIARY_CONSTRUCTION,
        target_route_ids=["route-a"],
        target_obligation_ids=["goal-1"],
        reason="the target bridge is stalled",
    )

    warm = build_inspiration_prompt_context(
        engine,
        task,
        snapshot=snapshot,
        context_mode=InspirationContextMode.WARM,
        proposal_slot=0,
    )
    cold = build_inspiration_prompt_context(
        engine,
        task,
        snapshot=snapshot,
        context_mode=InspirationContextMode.COLD,
        proposal_slot=2,
    )

    assert [item["message_id"] for item in warm["verified_facts"]] == ["fact-admitted"]
    assert [item["item_id"] for item in warm["negative_memory"]] == ["negative-1"]
    assert warm["proof_graph"]
    assert cold["verified_facts"] == []
    assert cold["negative_memory"] == []
    assert cold["proof_graph"] == {}
    assert cold["route_novelty_signatures"] == []
    assert cold["target_obligation_ids"] == ["goal-1"]
    assert cold["generation_contract"]["forbidden_existing_mechanisms"]
    assert (
        warm["generation_contract"]["diversity_axis"]
        != cold["generation_contract"]["diversity_axis"]
    )
    max_chars = min(
        engine.inspiration_config.inspiration_context_max_chars,
        engine.config.topology.max_context_chars,
    )
    assert len(json.dumps(warm, ensure_ascii=False, separators=(",", ":"))) <= max_chars
    assert len(json.dumps(cold, ensure_ascii=False, separators=(",", ":"))) <= max_chars


def test_meta_context_receives_metrics_but_not_proof_transcripts(tmp_path) -> None:
    engine, snapshot = _engine(tmp_path)
    task = InspirationTask(
        task_id="task-meta",
        trigger_id="trigger-meta",
        mechanism=InspirationMechanism.META_REPLAN,
        target_route_ids=["route-a"],
        target_obligation_ids=["goal-1"],
        reason="portfolio progress has plateaued",
    )

    context = build_inspiration_prompt_context(
        engine,
        task,
        snapshot=snapshot,
        context_mode=InspirationContextMode.WARM,
        proposal_slot=0,
    )

    assert context["search_metrics"]["proof_debt"] == {"route-a": 0.8}
    assert context["verified_facts"] == []
    assert context["negative_memory"] == []
    assert context["proof_graph"] == {}


def test_inspiration_context_honors_the_hard_character_budget(tmp_path) -> None:
    engine, snapshot = _engine(tmp_path)
    engine.inspiration_config.inspiration_context_max_chars = 1000
    task = InspirationTask(
        task_id="task-budget",
        trigger_id="trigger-budget",
        mechanism=InspirationMechanism.AUXILIARY_CONSTRUCTION,
        target_route_ids=["route-a"],
        target_obligation_ids=["goal-1"],
        reason="the target bridge is stalled",
    )

    context = build_inspiration_prompt_context(
        engine,
        task,
        snapshot=snapshot,
        context_mode=InspirationContextMode.WARM,
        proposal_slot=0,
    )

    assert context["context_truncated"]
    assert len(json.dumps(context, ensure_ascii=False, separators=(",", ":"))) <= 1000


def _proposal(
    identifier: str,
    *,
    mode: InspirationContextMode,
    representation: str,
) -> InspirationProposal:
    return InspirationProposal(
        proposal_id=identifier,
        task_id="task-population",
        trigger_id="trigger-population",
        mechanism=InspirationMechanism.REPRESENTATION_SWITCH,
        source_agent_id=f"author-{identifier}",
        target_route_ids=["route-a"],
        statement=f"Use {representation}.",
        rationale_summary="Test a structurally different representation.",
        generated_obligations=["goal-1"],
        novelty_signature=NoveltySignature(
            representation_tags=[representation],
            mechanism_tags=["representation switch"],
            targeted_obligation_ids=["goal-1"],
        ),
        novelty_score=1.0,
        expected_information_gain=0.8,
        estimated_cost=1,
        proposal_slot=int(identifier[-1]),
        context_mode=mode,
    )


def test_candidate_population_deduplicates_before_bounded_review(tmp_path) -> None:
    engine, _snapshot = _engine(tmp_path)
    proposals = [
        _proposal(
            "proposal-0", mode=InspirationContextMode.WARM, representation="modular"
        ),
        _proposal(
            "proposal-1", mode=InspirationContextMode.WARM, representation="modular"
        ),
        _proposal(
            "proposal-2", mode=InspirationContextMode.COLD, representation="graph"
        ),
    ]

    selected = engine.select_proposals_for_review(proposals, existing_signatures=[])

    assert len(selected) == 2
    assert {item.context_mode for item in selected} == {
        InspirationContextMode.WARM,
        InspirationContextMode.COLD,
    }
    assert (
        sum(item.selected_for_review for item in engine.candidate_decisions.values())
        == 2
    )
    assert any(
        not item.selected_for_review and "duplicate" in item.reason
        for item in engine.candidate_decisions.values()
    )
