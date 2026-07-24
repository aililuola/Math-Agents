from __future__ import annotations

from mathproofmesh.communication.route_registry import RouteRegistry
from mathproofmesh.inspiration.composer import InspirationComposer
from mathproofmesh.inspiration.domain_operators import DomainOperatorRegistry
from mathproofmesh.inspiration.engine import InspirationEngine
from mathproofmesh.inspiration.reverse_goal import ReverseGoalAnalyzer
from mathproofmesh.inspiration.surprise_mutation import ControlledMutationPlanner
from mathproofmesh.inspiration.trigger_policy import InspirationSnapshot
from mathproofmesh.memory import TypedMemory
from mathproofmesh.proof_graph.store import ProofGraphStore
from mathproofmesh.schemas import (
    ClaimStatus,
    EvidenceType,
    InspirationMechanism,
    InspirationProposal,
    InspirationReview,
    InspirationTask,
    InspirationTrigger,
    InspirationTriggerType,
    MemoryTier,
    MessageEnvelope,
    MessageType,
    NoveltySignature,
    ObligationKind,
    ProblemContract,
    ProofObligation,
    QuantifierSpec,
    RepresentationCandidate,
    RouteRole,
)

from v07_helpers import make_strategy, make_v07_config


def _runtime(tmp_path):
    config = make_v07_config(tmp_path / "runs")
    problem = ProblemContract(
        exact_statement="For every prime p, prove the integer divisibility claim.",
        normalized_statement="prime integer divisibility claim",
    )
    graph = ProofGraphStore(config, problem_hash=problem.integrity_hash)
    graph.add_obligation(
        ProofObligation(
            obligation_id="goal",
            problem_hash=problem.integrity_hash,
            route_ids=["route-a"],
            kind=ObligationKind.LEMMA,
            statement="Prove the target divisibility bridge.",
            normalized_statement="prove target divisibility bridge",
        )
    )
    registry = RouteRegistry(config, problem_hash=problem.integrity_hash)
    registry.register_route(make_strategy(0, tag="modular"), route_id="route-a")
    engine = InspirationEngine(
        config,
        problem=problem,
        proof_graph=graph,
        typed_memory=TypedMemory(None, config),
        route_registry=registry,
        project_root=tmp_path,
    )
    snapshot = InspirationSnapshot(
        round_index=2,
        problem_hash=problem.integrity_hash,
        domain="number_theory",
        active_route_ids=["route-a"],
        open_obligation_ids=["goal"],
        obligation_kinds={"goal": "lemma"},
        proof_debt_by_route={"route-a": 1.0},
        remaining_calls=40,
        current_path_count=1,
        max_paths=8,
    )
    return config, problem, graph, registry, engine, snapshot


def _fact(problem: ProblemContract, identifier: str, statement: str) -> MessageEnvelope:
    return MessageEnvelope(
        message_id=identifier,
        problem_hash=problem.integrity_hash,
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


def _proposal(identifier: str, mechanism: InspirationMechanism, tag: str):
    return InspirationProposal(
        proposal_id=identifier,
        task_id=f"task-{identifier}",
        trigger_id="trigger-stalled",
        mechanism=mechanism,
        source_agent_id=f"agent-{identifier}",
        target_route_ids=["route-a"],
        statement=f"Apply {tag} to the target.",
        rationale_summary=f"{tag} exposes the missing bridge.",
        generated_obligations=["goal"],
        novelty_signature=NoveltySignature(
            representation_tags=[tag]
            if mechanism == InspirationMechanism.REPRESENTATION_SWITCH
            else [],
            mechanism_tags=[tag],
            key_transformations=[f"transform-{tag}"],
            targeted_obligation_ids=["goal"],
        ),
        novelty_score=0.95,
        expected_information_gain=0.8,
        estimated_cost=1,
    )


def _review(proposal_id: str) -> InspirationReview:
    return InspirationReview(
        proposal_id=proposal_id,
        reviewer_agent_id=f"referee-{proposal_id}",
        semantically_distinct=True,
        relevant_to_open_obligation=True,
        internally_coherent=True,
        recommendation="create_new_route",
        confidence=0.9,
    )


def test_domain_operator_plugins_supply_auditable_contracts() -> None:
    registry = DomainOperatorRegistry()
    problem = ProblemContract(
        exact_statement="Let p be prime and prove an integer divisibility statement.",
        normalized_statement="prime integer divisibility",
    )

    operators = registry.select(
        problem,
        domain="number_theory",
        families=("representation", "construction", "mutation"),
        limit=20,
    )

    assert {item.family for item in operators} == {
        "representation",
        "construction",
        "mutation",
    }
    assert all(item.preconditions for item in operators)
    assert all(item.generated_obligations for item in operators)
    assert all(item.reversibility_requirements for item in operators)
    assert all(item.fast_failure_tests for item in operators)
    assert all(item.known_failure_modes for item in operators)


def test_controlled_surprise_mutations_are_replayable_and_slot_diverse() -> None:
    problem = ProblemContract(
        exact_statement="Let p be prime and prove an integer divisibility statement.",
        normalized_statement="prime integer divisibility",
    )
    planner = ControlledMutationPlanner()
    arguments = {
        "task_id": "surprise-task",
        "target_obligation_ids": ["goal"],
        "domain": "number_theory",
    }

    first = planner.plan(problem, proposal_slot=0, **arguments)
    replay = planner.plan(problem, proposal_slot=0, **arguments)
    second = planner.plan(problem, proposal_slot=1, **arguments)

    assert first == replay
    assert first.directive_id != second.directive_id
    assert first.seed != second.seed
    assert first.fast_failure_tests and first.known_failure_modes
    assert first.reversibility_requirements


def test_operator_and_mutation_admission_are_checkpointed(tmp_path) -> None:
    _config, _problem, _graph, _registry, engine, snapshot = _runtime(tmp_path)
    task = InspirationTask(
        task_id="surprise-task",
        trigger_id="trigger-surprise",
        mechanism=InspirationMechanism.SURPRISE_EXPLORATION,
        target_route_ids=["route-a"],
        target_obligation_ids=["goal"],
        reason="escape a repeated local failure",
    )

    catalog = engine.domain_operator_catalog(task, snapshot)
    directive = engine.surprise_mutation_directive(
        task,
        snapshot,
        proposal_slot=0,
    )
    state = engine.export_state()

    assert catalog
    assert state["domain_operator_selections"][task.task_id]
    assert directive is not None
    assert (
        state["mutation_directives"][directive.directive_id]["seed"] == directive.seed
    )


def test_active_artifact_cannot_invent_an_unadmitted_domain_operator(tmp_path) -> None:
    _config, problem, _graph, _registry, engine, snapshot = _runtime(tmp_path)
    task = InspirationTask(
        task_id="representation-task",
        trigger_id="trigger-representation",
        mechanism=InspirationMechanism.REPRESENTATION_SWITCH,
        target_route_ids=["route-a"],
        target_obligation_ids=["goal"],
        reason="change the representation",
    )
    catalog = engine.domain_operator_catalog(task, snapshot)
    artifact = RepresentationCandidate(
        source_problem_hash=problem.integrity_hash,
        representation_name="invented black-box encoding",
        rewritten_problem_view="Encode the divisibility target by an invented map.",
        object_mapping={"integer": "encoded state"},
        preserved_invariants=["divisibility target"],
        expected_advantage="shortens the target",
        failure_risks=["the encoding may lose information"],
        fast_failure_tests=["check the smallest prime"],
        operator_id="not_in_the_admitted_catalog",
        novelty_signature=NoveltySignature(
            representation_tags=["invented_encoding"],
            mechanism_tags=["invented_encoding"],
            targeted_obligation_ids=["goal"],
        ),
    )

    proposal = engine.register_agent_artifact(
        task,
        artifact,
        source_agent_id="representation-agent",
        proposal_slot=1,
        state=snapshot,
    )

    assert proposal is not None and proposal.representation is not None
    selected = proposal.representation
    assert selected.operator_id == catalog[1]["operator_id"]
    assert selected.operator_id != artifact.operator_id
    assert selected.operator_preconditions
    assert selected.generated_obligations
    assert selected.reversibility_requirements
    assert selected.known_failure_modes


def test_reverse_goal_meets_only_admitted_forward_facts(tmp_path) -> None:
    config, problem, graph, _registry, engine, _snapshot = _runtime(tmp_path)
    target = graph.get_obligation("goal")
    admitted = _fact(problem, "fact-admitted", "p divides the transformed term")
    analyzer = ReverseGoalAnalyzer(config.topology.inspiration)

    plan = analyzer.analyze(
        target,
        facts=[admitted],
        proposed_backward_claims=["the transformed term is divisible by p"],
        round_index=2,
    )

    assert plan.forward_frontier[0].source_ref == "fact-admitted"
    assert any(
        item.frontier_id.startswith("frontier_scope_") for item in plan.forward_frontier
    )
    assert plan.frontier_bridges
    candidate = plan.frontier_bridges[0]
    assert candidate.semantic_relationship == "candidate_ingredient"
    assert candidate.source_sufficiency_assumed is False
    assert candidate.required_supporting_conditions
    assert ") implies (" not in candidate.missing_implication
    assert "candidate ingredient" in candidate.missing_implication
    created = analyzer.materialize(plan, graph)
    assert created
    assert all(item.kind == ObligationKind.LEMMA for item in created)

    engine.typed_memory.add_fact(
        _fact(problem, "typed-only", "unbrokered claim"),
        referee_agent_id="referee-b",
    )
    assert engine._admitted_inspiration_facts() == []


def test_reverse_goal_does_not_turn_lexical_overlap_into_false_implication(
    tmp_path,
) -> None:
    config, problem, _graph, _registry, _engine, _snapshot = _runtime(tmp_path)
    target = ProofObligation(
        obligation_id="coprimality-goal",
        problem_hash=problem.integrity_hash,
        route_ids=["route-a"],
        kind=ObligationKind.LEMMA,
        statement=(
            "For N=(2P)^2+1 and every prime p dividing P, prove N is congruent "
            "to 1 modulo p."
        ),
        normalized_statement="constructed n congruent one modulo p",
    )
    admitted = _fact(
        problem,
        "quadratic-residue-fact",
        "If q is an odd prime dividing a^2+1, then q is congruent to 1 modulo 4.",
    )
    analyzer = ReverseGoalAnalyzer(config.topology.inspiration)

    plan = analyzer.analyze(
        target,
        facts=[admitted],
        proposed_backward_claims=[target.statement],
        round_index=2,
    )

    bridge = plan.frontier_bridges[0]
    assert bridge.semantic_relationship == "scope_only"
    assert bridge.source_sufficiency_assumed is False
    assert bridge.forward_frontier_id.startswith("frontier_scope_")
    assert admitted.statement not in bridge.missing_implication
    assert ") implies (" not in bridge.missing_implication
    assert "derive all missing intermediate claims explicitly" in (
        bridge.missing_implication
    )


def test_reverse_goal_exact_text_cannot_bypass_scope_compatibility(tmp_path) -> None:
    config, problem, _graph, _registry, _engine, _snapshot = _runtime(tmp_path)
    statement = "Every object in the current domain has property P."
    target = ProofObligation(
        obligation_id="scoped-goal",
        problem_hash=problem.integrity_hash,
        route_ids=["route-a"],
        kind=ObligationKind.LEMMA,
        statement=statement,
        normalized_statement=statement.casefold(),
        assumptions=["x belongs to the target domain"],
        quantifiers=[
            QuantifierSpec(
                order=0,
                kind="forall",
                variable_id="x",
                display_name="x",
                domain="target domain",
            )
        ],
    )
    admitted = _fact(problem, "wrong-scope-fact", statement).model_copy(
        update={
            "assumptions": ["x belongs to a restricted source domain"],
            "quantifiers": [
                QuantifierSpec(
                    order=0,
                    kind="exists",
                    variable_id="x",
                    display_name="x",
                    domain="source domain",
                )
            ],
        }
    )

    plan = ReverseGoalAnalyzer(config.topology.inspiration).analyze(
        target,
        facts=[admitted],
        proposed_backward_claims=[statement],
        round_index=2,
    )

    assert statement not in plan.fact_supported_claims
    assert plan.frontier_bridges[0].semantic_relationship == "scope_only"
    assert plan.frontier_bridges[0].source_sufficiency_assumed is False


def test_composer_queues_a_separately_reviewed_next_round_proposal(tmp_path) -> None:
    _config, _problem, _graph, _registry, engine, snapshot = _runtime(tmp_path)
    engine.triggers["trigger-stalled"] = InspirationTrigger(
        trigger_id="trigger-stalled",
        trigger_type=InspirationTriggerType.STAGNATION,
        round_index=2,
        affected_route_ids=["route-a"],
        reason="the route stalled",
    )
    left = _proposal(
        "proposal-left", InspirationMechanism.REPRESENTATION_SWITCH, "finite_state"
    )
    right = _proposal(
        "proposal-right", InspirationMechanism.AUXILIARY_CONSTRUCTION, "potential"
    )
    engine.proposals = {left.proposal_id: left, right.proposal_id: right}
    engine.record_quick_falsification(left.proposal_id, passed=True, reason="passed")

    compositions = engine.queue_compositions(
        [left, right],
        [_review(left.proposal_id), _review(right.proposal_id)],
        snapshot,
    )

    assert len(compositions) == 1
    composition = compositions[0]
    assert set(composition.source_proposal_ids) == {
        left.proposal_id,
        right.proposal_id,
    }
    assert engine.pending_composed_proposals
    assert not engine.materializations
    assert not engine.typed_memory.facts

    tasks = engine.select_tasks([], snapshot)
    assert [item.mechanism for item in tasks] == [
        InspirationMechanism.INSPIRATION_COMPOSITION
    ]
    proposal = engine.pending_composition_for_task(tasks[0].task_id)
    assert proposal is not None and proposal.composition == composition
    assert proposal.source_agent_id == "inspiration_composer"

    state = engine.export_state()
    _config2, _problem2, _graph2, _registry2, restored, _snapshot2 = _runtime(
        tmp_path / "restored"
    )
    restored.restore_state(state)
    assert restored.compositions == engine.compositions
    assert restored.pending_composed_proposals == engine.pending_composed_proposals
    assert restored.quick_falsification_passed == engine.quick_falsification_passed


def test_composer_rejects_unfalsified_or_noncomplementary_pairs(tmp_path) -> None:
    config, _problem, graph, _registry, _engine, _snapshot = _runtime(tmp_path)
    composer = InspirationComposer(config.topology.inspiration)
    left = _proposal(
        "proposal-left", InspirationMechanism.REPRESENTATION_SWITCH, "finite_state"
    )
    duplicate = _proposal(
        "proposal-copy", InspirationMechanism.REPRESENTATION_SWITCH, "finite_state"
    )

    assert (
        composer.compose(
            [left, duplicate],
            [_review(left.proposal_id), _review(duplicate.proposal_id)],
            quick_falsification_passed=set(),
            proof_graph=graph,
        )
        == []
    )


def test_composer_honors_zero_candidate_cap(tmp_path) -> None:
    config, _problem, graph, _registry, _engine, _snapshot = _runtime(tmp_path)
    config.topology.inspiration.composer_max_candidates_per_round = 0
    composer = InspirationComposer(config.topology.inspiration)
    left = _proposal(
        "proposal-left", InspirationMechanism.REPRESENTATION_SWITCH, "finite_state"
    )
    right = _proposal(
        "proposal-right", InspirationMechanism.AUXILIARY_CONSTRUCTION, "potential"
    )

    assert (
        composer.compose(
            [left, right],
            [_review(left.proposal_id), _review(right.proposal_id)],
            quick_falsification_passed={left.proposal_id},
            proof_graph=graph,
        )
        == []
    )


def test_composer_can_build_a_bounded_three_source_composition(tmp_path) -> None:
    config, _problem, graph, _registry, _engine, _snapshot = _runtime(tmp_path)
    config.topology.inspiration.max_reviewed_proposals_per_task = 3
    config.topology.inspiration.composer_max_sources = 3
    config.topology.inspiration.composer_max_candidates_per_round = 8
    composer = InspirationComposer(config.topology.inspiration)
    proposals = [
        _proposal(
            "proposal-representation",
            InspirationMechanism.REPRESENTATION_SWITCH,
            "finite_state",
        ),
        _proposal(
            "proposal-construction",
            InspirationMechanism.AUXILIARY_CONSTRUCTION,
            "potential",
        ),
        _proposal(
            "proposal-invariant",
            InspirationMechanism.INVARIANT_HYPOTHESIS,
            "valuation_invariant",
        ),
    ]

    compositions = composer.compose(
        proposals,
        [_review(item.proposal_id) for item in proposals],
        quick_falsification_passed={proposals[0].proposal_id},
        proof_graph=graph,
    )

    three_source = next(
        item for item in compositions if len(item.source_proposal_ids) == 3
    )
    assert set(three_source.source_proposal_ids) == {
        item.proposal_id for item in proposals
    }
    assert len(three_source.combined_mechanism) >= 3
    assert three_source.estimated_cost == 3
