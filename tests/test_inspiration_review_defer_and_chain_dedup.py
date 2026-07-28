from __future__ import annotations

from mathproofmesh.communication.route_registry import RouteRegistry
from mathproofmesh.inspiration.context import build_inspiration_prompt_context
from mathproofmesh.inspiration.engine import InspirationEngine
from mathproofmesh.inspiration.novelty import NoveltyGate
from mathproofmesh.inspiration.trigger_policy import InspirationSnapshot
from mathproofmesh.memory import TypedMemory
from mathproofmesh.proof_control.controller import ProofControlLayer
from mathproofmesh.proof_control.models import (
    ControlActionStatus,
    ControlActionType,
)
from mathproofmesh.proof_graph.store import ProofGraphStore
from mathproofmesh.schemas import (
    InspirationContextMode,
    InspirationMechanism,
    InspirationProposal,
    InspirationReview,
    InspirationTask,
    MechanismChainSignature,
    NoveltySignature,
    ObligationKind,
    ProblemContract,
    ProofObligation,
)

from v07_helpers import (
    make_broker_runtime,
    make_proof_control_config,
    make_strategy,
    make_v07_config,
)


def _control(tmp_path):
    config = make_proof_control_config(tmp_path / "runs", mode="active")
    store, registry, memory, graph, broker = make_broker_runtime(
        config, tmp_path / "runtime"
    )
    return ProofControlLayer(
        config,
        store,
        None,
        graph,
        memory,
        broker,
        registry,
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
            obligation_id="goal-chain",
            problem_hash=problem.integrity_hash,
            route_ids=["route-a"],
            kind=ObligationKind.LEMMA,
            statement="Close the target bridge.",
            normalized_statement="close target bridge",
        )
    )
    registry = RouteRegistry(config, problem_hash=problem.integrity_hash)
    registry.register_route(make_strategy(0), route_id="route-a")
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
        active_route_ids=["route-a"],
        route_signatures=[],
        open_obligation_ids=["goal-chain"],
        remaining_calls=20,
        current_path_count=1,
        max_paths=8,
    )
    return engine, snapshot


def _proposal() -> InspirationProposal:
    return InspirationProposal(
        proposal_id="proposal-deferred",
        trigger_id="trigger-deferred",
        mechanism=InspirationMechanism.REPRESENTATION_SWITCH,
        source_agent_id="author-a",
        statement="Use a reversible representation to expose the bridge.",
        rationale_summary="The current representation hides the missing implication.",
        target_route_ids=["route-a"],
        generated_obligations=["goal-chain"],
        novelty_signature=NoveltySignature(
            representation_tags=["finite_support"],
            mechanism_tags=["stabilization"],
            key_transformations=["fixed_modulus"],
            proof_principles=["periodicity"],
            targeted_obligation_ids=["goal-chain"],
        ),
        novelty_score=0.95,
        expected_information_gain=0.8,
        estimated_cost=1,
    )


def test_referee_budget_exhaustion_defers_proposal(tmp_path) -> None:
    control = _control(tmp_path)

    action = control.defer_inspiration_review(
        proposal_id="proposal-a",
        task_id="task-a",
        reason="ReasoningBudgetExhaustedError",
        current_round=2,
    )

    assert action.action_type == ControlActionType.DEFER_INSPIRATION_REVIEW
    assert action.status == ControlActionStatus.EXECUTED
    record = next(iter(control.state.inspiration_review_deferrals.values()))
    assert record.review_status == "deferred"
    assert record.reviewed is False


def test_deferred_inspiration_can_be_reassigned(tmp_path) -> None:
    control = _control(tmp_path)
    control.defer_inspiration_review(
        proposal_id="proposal-b",
        task_id="task-b",
        reason="provider cooldown",
        current_round=2,
    )

    action = control.reassign_inspiration_review(
        proposal_id="proposal-b",
        reviewer_agent_id="independent-referee",
        current_round=2,
    )

    assert action.action_type == ControlActionType.REASSIGN_INSPIRATION_REVIEW
    assert action.status == ControlActionStatus.EXECUTED
    record = next(iter(control.state.inspiration_review_deferrals.values()))
    assert record.review_status == "reassigned"
    assert record.assigned_reviewer_agent_id == "independent-referee"


def test_unreviewed_proposal_cannot_materialize_route(tmp_path) -> None:
    engine, snapshot = _engine(tmp_path)
    proposal = _proposal()
    engine.proposals[proposal.proposal_id] = proposal
    deferred_review = InspirationReview(
        proposal_id=proposal.proposal_id,
        reviewer_agent_id="",
        semantically_distinct=False,
        relevant_to_open_obligation=True,
        internally_coherent=False,
        recommendation="create_new_route",
        confidence=0.0,
        review_status="deferred",
        deferred_reason="network failure",
    )

    materializations = engine.materialize([deferred_review], snapshot)

    assert materializations == []
    assert engine.materialized_strategies == {}
    assert proposal.proposal_id not in engine.reviews


def test_basic_math_tool_not_forbidden_by_chain_dedup(tmp_path) -> None:
    engine, snapshot = _engine(tmp_path)
    snapshot.route_signatures = [
        NoveltySignature(mechanism_tags=["modular"]),
    ]
    task = InspirationTask(
        task_id="task-chain",
        trigger_id="trigger-chain",
        mechanism=InspirationMechanism.AUXILIARY_CONSTRUCTION,
        target_route_ids=["route-a"],
        target_obligation_ids=["goal-chain"],
        reason="the bridge is stalled",
    )

    context = build_inspiration_prompt_context(
        engine,
        task,
        snapshot=snapshot,
        context_mode=InspirationContextMode.COLD,
        proposal_slot=0,
    )

    contract = context["generation_contract"]
    assert contract["blocked_mechanism_chains"] == []
    assert "modular" not in str(contract["forbidden_existing_mechanisms"])


def test_duplicate_mechanism_chain_is_blocked(tmp_path) -> None:
    engine, _snapshot = _engine(tmp_path)
    chain = MechanismChainSignature(
        representation=["finite_support"],
        transformations=["stabilization", "fixed_modulus"],
        bridge_pattern=["state_reduction"],
        terminal_argument=["periodicity"],
    )
    left = chain.to_novelty_signature(targeted_obligation_ids=["goal-chain"])
    right = chain.to_novelty_signature(targeted_obligation_ids=["goal-chain"])

    assessment = NoveltyGate(engine.inspiration_config).assess(left, [right])

    assert chain.complete
    assert assessment.duplicate
    assert assessment.mechanism_chain_similarity == 1.0
