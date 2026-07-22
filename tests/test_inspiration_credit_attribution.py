from __future__ import annotations

from mathproofmesh.communication.route_registry import RouteRegistry
from mathproofmesh.inspiration.engine import InspirationEngine
from mathproofmesh.inspiration.outcomes import InspirationOutcomeLedger
from mathproofmesh.inspiration.trigger_policy import (
    SCHEDULABLE_MECHANISMS,
    InspirationSnapshot,
    enabled_schedulable_mechanisms,
)
from mathproofmesh.memory import TypedMemory
from mathproofmesh.proof_graph.store import ProofGraphStore
from mathproofmesh.schemas import (
    ClaimStatus,
    ComposedInspiration,
    EvidenceType,
    InspirationMechanism,
    InspirationProposal,
    InspirationReview,
    InspirationTrigger,
    InspirationTriggerType,
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
        exact_statement="Prove the target statement.",
        normalized_statement="prove the target statement",
    )
    graph = ProofGraphStore(config, problem_hash=problem.integrity_hash)
    graph.add_obligation(
        ProofObligation(
            obligation_id="goal",
            problem_hash=problem.integrity_hash,
            route_ids=["route-a"],
            kind=ObligationKind.LEMMA,
            statement="Close the target lemma.",
            normalized_statement="close the target lemma.",
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
        problem_hash=problem.integrity_hash,
        domain="number_theory",
        active_route_ids=["route-a"],
        open_obligation_ids=["goal"],
        obligation_kinds={"goal": ObligationKind.LEMMA.value},
        proof_debt_by_route={"route-a": graph.proof_debt("route-a")},
        remaining_calls=40,
        current_path_count=1,
        max_paths=8,
    )
    trigger = InspirationTrigger(
        trigger_id="trigger-stalled",
        trigger_type=InspirationTriggerType.STAGNATION,
        round_index=3,
        affected_route_ids=["route-a"],
        evidence_refs=["goal"],
        reason="the current proof route stalled",
    )
    engine.triggers[trigger.trigger_id] = trigger
    return config, problem, graph, engine, snapshot, trigger


def _proposal(
    identifier: str,
    *,
    mechanism: InspirationMechanism = InspirationMechanism.REPRESENTATION_SWITCH,
    target_routes: list[str] | None = None,
) -> InspirationProposal:
    return InspirationProposal(
        proposal_id=identifier,
        task_id=f"task-{identifier}",
        trigger_id="trigger-stalled",
        mechanism=mechanism,
        source_agent_id=f"agent-{identifier}",
        target_route_ids=["route-a"] if target_routes is None else target_routes,
        statement=f"Use {identifier} to close the target lemma.",
        rationale_summary="the proposal attacks the explicit stalled obligation",
        generated_obligations=["goal"],
        novelty_signature=NoveltySignature(
            mechanism_tags=[mechanism.value],
            targeted_obligation_ids=["goal"],
        ),
        novelty_score=0.9,
        expected_information_gain=0.8,
        estimated_cost=1,
    )


def _review(proposal_id: str, recommendation: str) -> InspirationReview:
    return InspirationReview(
        proposal_id=proposal_id,
        reviewer_agent_id=f"referee-{proposal_id}",
        semantically_distinct=True,
        relevant_to_open_obligation=True,
        internally_coherent=True,
        recommendation=recommendation,
        confidence=0.9,
    )


def _fact(
    problem: ProblemContract,
    identifier: str,
    statement: str = "Close the target lemma.",
) -> MessageEnvelope:
    return MessageEnvelope(
        message_id=identifier,
        problem_hash=problem.integrity_hash,
        source_agent_id="fact-author",
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
        round_created=4,
    )


def _register(engine, proposal, snapshot) -> None:
    engine.proposals[proposal.proposal_id] = proposal
    engine._register_outcome(proposal, snapshot)


def test_surprise_debt_uses_the_same_frozen_route_set_before_and_after(
    tmp_path,
) -> None:
    _config, problem, graph, engine, snapshot, _trigger = _runtime(tmp_path)
    proposal = _proposal(
        "surprise",
        mechanism=InspirationMechanism.SURPRISE_EXPLORATION,
        target_routes=[],
    )
    _register(engine, proposal, snapshot)
    outcome = engine.outcome_ledger.outcomes[proposal.proposal_id]
    before = graph.proof_debt("route-a")

    fact = _fact(problem, "fact-unrelated-to-debt")
    engine.typed_memory.add_fact(fact, referee_agent_id="fact-referee")
    engine.mark_verified(proposal.proposal_id, fact.message_id)
    outcome = engine.outcome_ledger.outcomes[proposal.proposal_id]

    assert outcome.credit_route_ids == ["route-a"]
    assert outcome.proof_debt_before == before
    assert outcome.proof_debt_after == before
    assert outcome.proof_debt_delta == 0


def test_existing_route_and_obligation_only_proposals_receive_explicit_credit(
    tmp_path,
) -> None:
    _config, problem, graph, engine, snapshot, _trigger = _runtime(tmp_path)
    attached = _proposal("attached")
    computation = _proposal(
        "computation", mechanism=InspirationMechanism.INVARIANT_HYPOTHESIS
    )
    bridge = _proposal("bridge", mechanism=InspirationMechanism.BRIDGE_LEMMA)
    for proposal in (attached, computation, bridge):
        _register(engine, proposal, snapshot)
    reviews = [
        _review(attached.proposal_id, "attach_to_existing_route"),
        _review(computation.proposal_id, "request_computation"),
        _review(bridge.proposal_id, "request_bridge_verification"),
    ]
    engine.reviews = {item.proposal_id: item for item in reviews}
    materializations = engine.materialize(reviews, snapshot)

    assert {item.action for item in materializations} == {
        "attached",
        "computation_requested",
        "bridge_requested",
    }
    fact = _fact(problem, "fact-shared-gain")
    engine.typed_memory.add_fact(fact, referee_agent_id="fact-referee")
    graph.ingest_message(fact)
    credited = engine.attribute_verified_fact(
        fact.message_id,
        source_route_id="route-a",
        closed_obligation_ids=["goal"],
    )

    assert credited == ["attached", "bridge", "computation"]
    assert all(
        engine.outcome_ledger.outcomes[item].verified_fact_gain == 1
        for item in credited
    )
    assert all(
        fact.message_id in engine.credit_targets[item].message_ids for item in credited
    )

    assert engine.mark_final_citations(route_ids=["route-a"]) == ["attached"]
    cited = engine.mark_final_citations(message_ids=[fact.message_id])
    assert cited == credited
    assert all(
        engine.outcome_ledger.outcomes[item].cited_by_final_proof for item in credited
    )


def test_credit_targets_and_fixed_scopes_survive_checkpoint_restore(tmp_path) -> None:
    _config, _problem, _graph, engine, snapshot, _trigger = _runtime(tmp_path)
    proposal = _proposal("checkpointed")
    _register(engine, proposal, snapshot)
    review = _review(proposal.proposal_id, "attach_to_existing_route")
    engine.reviews[proposal.proposal_id] = review
    engine.materialize([review], snapshot)

    state = engine.export_state()
    state.pop("credit_targets")
    state["outcomes"][proposal.proposal_id].pop("credit_route_ids")
    state["outcomes"][proposal.proposal_id].pop("credit_obligation_ids")
    state["materializations"][proposal.proposal_id].pop("message_ids")
    _config2, _problem2, _graph2, restored, _snapshot2, _trigger2 = _runtime(
        tmp_path / "restored"
    )
    restored.restore_state(state)

    assert restored.credit_targets == engine.credit_targets
    assert restored.outcome_ledger.outcomes[proposal.proposal_id].credit_route_ids == [
        "route-a"
    ]
    assert restored.outcome_ledger.outcomes[
        proposal.proposal_id
    ].credit_obligation_ids == ["goal"]


def test_composed_fact_credits_every_explicit_source_proposal(tmp_path) -> None:
    _config, problem, graph, engine, snapshot, _trigger = _runtime(tmp_path)
    left = _proposal("left")
    right = _proposal("right", mechanism=InspirationMechanism.AUXILIARY_CONSTRUCTION)
    for proposal in (left, right):
        _register(engine, proposal, snapshot)
    composition = ComposedInspiration(
        composition_id="composition-1",
        source_proposal_ids=[left.proposal_id, right.proposal_id],
        target_obligation_ids=["goal"],
        compatibility_conditions=["both proposals preserve the target assumptions"],
        combined_mechanism=["representation_switch", "auxiliary_construction"],
        first_executable_step="Prove the combined bridge.",
        new_obligations=["Prove the combined bridge."],
        fast_failure_tests=["check that the auxiliary object is well-defined"],
        novelty_signature=NoveltySignature(
            mechanism_tags=["composed_bridge"],
            targeted_obligation_ids=["goal"],
        ),
    )
    combined = _proposal(
        "combined", mechanism=InspirationMechanism.INSPIRATION_COMPOSITION
    ).model_copy(update={"composition": composition})
    _register(engine, combined, snapshot)
    review = _review(combined.proposal_id, "attach_to_existing_route")
    engine.reviews[combined.proposal_id] = review
    decision = engine.materialize([review], snapshot)[0]

    assert decision.obligation_ids
    assert (
        engine.credit_targets[left.proposal_id].materialization_action
        == "composition_source"
    )
    fact = _fact(problem, "fact-composed", "Prove the combined bridge.")
    engine.typed_memory.add_fact(fact, referee_agent_id="fact-referee")
    graph.ingest_message(fact)
    credited = engine.attribute_verified_fact(
        fact.message_id,
        source_route_id="route-a",
        closed_obligation_ids=decision.obligation_ids,
    )

    assert credited == ["combined", "left", "right"]


def test_ucb_profiles_include_only_enabled_schedulable_mechanisms(tmp_path) -> None:
    config = make_v07_config(tmp_path / "runs")
    config.topology.inspiration.analogy_agent = False
    ledger = InspirationOutcomeLedger(config.topology.inspiration)
    trigger = InspirationTrigger(
        trigger_id="trigger-stalled",
        trigger_type=InspirationTriggerType.STAGNATION,
        round_index=3,
        affected_route_ids=["route-a"],
        reason="the route stalled",
    )
    snapshot = InspirationSnapshot(round_index=3, remaining_calls=20)

    profiles = ledger.selection_profiles([trigger], snapshot)
    mechanisms = {key.rsplit(":", 1)[-1] for key in profiles}
    expected = {
        item.value
        for item in enabled_schedulable_mechanisms(config.topology.inspiration)
    }

    assert set(SCHEDULABLE_MECHANISMS) > set(
        enabled_schedulable_mechanisms(config.topology.inspiration)
    )
    assert mechanisms == expected
    assert InspirationMechanism.STRUCTURAL_ANALOGY.value not in mechanisms
    assert InspirationMechanism.INSPIRATION_COMPOSITION.value not in mechanisms
