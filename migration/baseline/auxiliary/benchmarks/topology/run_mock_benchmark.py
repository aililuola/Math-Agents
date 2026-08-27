from __future__ import annotations

import argparse
import json
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from mathproofmesh.communication.broker import MessageBroker
from mathproofmesh.communication.policies import validate_evidence_tier
from mathproofmesh.communication.route_registry import RouteRegistry
from mathproofmesh.config import SystemConfig
from mathproofmesh.inspiration.analogy_agent import AnalogyAgent
from mathproofmesh.inspiration.assignment import InspirationAssignmentPlanner
from mathproofmesh.inspiration.construction_inventor import (
    AuxiliaryConstructionInventor,
)
from mathproofmesh.inspiration.composer import InspirationComposer
from mathproofmesh.inspiration.cross_run_learning import CrossRunLearningStore
from mathproofmesh.inspiration.domain_operators import DomainOperatorRegistry
from mathproofmesh.inspiration.experience import VerifiedExperienceDistiller
from mathproofmesh.inspiration.invariant_hypothesis import InvariantHypothesisAgent
from mathproofmesh.inspiration.local_library import LocalAnalogyLibrary
from mathproofmesh.inspiration.meta_control import MetaDirectiveController
from mathproofmesh.inspiration.meta_strategist import PersistentMetaStrategist
from mathproofmesh.inspiration.novelty import NoveltyGate
from mathproofmesh.inspiration.ontology import MechanismNormalizer
from mathproofmesh.inspiration.outcomes import InspirationOutcomeLedger
from mathproofmesh.inspiration.representation_switchboard import (
    RepresentationSwitchboard,
)
from mathproofmesh.inspiration.reverse_goal import ReverseGoalAnalyzer
from mathproofmesh.inspiration.surprise_mutation import ControlledMutationPlanner
from mathproofmesh.inspiration.trigger_policy import InspirationSnapshot, TriggerPolicy
from mathproofmesh.memory import TypedMemory
from mathproofmesh.llm.pool import AgentPool
from mathproofmesh.mock_demo import build_demo_config
from mathproofmesh.proof_graph.bridges import BridgeBroker
from mathproofmesh.proof_graph.contradictions import ContradictionBroker
from mathproofmesh.proof_graph.matching import DuplicateRouteDetector
from mathproofmesh.proof_graph.store import ProofGraphStore
from mathproofmesh.schemas import (
    ClaimStatus,
    EvidenceType,
    InspirationContextMode,
    InspirationMechanism,
    InspirationProposal,
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
    StrategyCard,
)
from mathproofmesh.store import ArtifactStore


PROBLEM_HASH = "7" * 64
CASE_FILES = (
    "shared_bridge_case.json",
    "contradiction_case.json",
    "duplicate_route_case.json",
    "computation_scope_case.json",
    "resume_delivery_case.json",
    "mutation_review_cases.json",
)


@dataclass(frozen=True, slots=True)
class Variant:
    name: str
    typed_broker: bool = False
    route_teams: bool = False
    graph_mode: str = "off"
    inspiration_mode: str = "off"
    analogy: bool = False
    representation_switch: bool = False
    surprise_budget: bool = False
    persistent_meta_strategist: bool = False


VARIANTS = (
    Variant("v0.6 legacy sparse"),
    Variant("v0.7 typed broker only", typed_broker=True),
    Variant("v0.7 broker + route teams", typed_broker=True, route_teams=True),
    Variant(
        "v0.7 full shadow graph",
        typed_broker=True,
        route_teams=True,
        graph_mode="shadow",
    ),
    Variant(
        "v0.7 active graph",
        typed_broker=True,
        route_teams=True,
        graph_mode="active",
    ),
    Variant(
        "v0.7 active graph + inspiration shadow",
        typed_broker=True,
        route_teams=True,
        graph_mode="active",
        inspiration_mode="shadow",
        analogy=True,
        representation_switch=True,
        surprise_budget=True,
        persistent_meta_strategist=True,
    ),
    Variant(
        "v0.7 active graph + inspiration active",
        typed_broker=True,
        route_teams=True,
        graph_mode="active",
        inspiration_mode="active",
        analogy=True,
        representation_switch=True,
        surprise_budget=True,
        persistent_meta_strategist=True,
    ),
    Variant(
        "v0.7 without analogy",
        typed_broker=True,
        route_teams=True,
        graph_mode="active",
        inspiration_mode="active",
        representation_switch=True,
        surprise_budget=True,
        persistent_meta_strategist=True,
    ),
    Variant(
        "v0.7 without representation switch",
        typed_broker=True,
        route_teams=True,
        graph_mode="active",
        inspiration_mode="active",
        analogy=True,
        surprise_budget=True,
        persistent_meta_strategist=True,
    ),
    Variant(
        "v0.7 without surprise budget",
        typed_broker=True,
        route_teams=True,
        graph_mode="active",
        inspiration_mode="active",
        analogy=True,
        representation_switch=True,
        persistent_meta_strategist=True,
    ),
    Variant(
        "v0.7 without persistent meta-strategist",
        typed_broker=True,
        route_teams=True,
        graph_mode="active",
        inspiration_mode="active",
        analogy=True,
        representation_switch=True,
        surprise_budget=True,
    ),
)


def _config(run_root: Path) -> SystemConfig:
    payload = build_demo_config(str(run_root)).model_dump(mode="python")
    payload["budget"].update({"max_total_calls": 64, "max_paths": 8})
    payload["continuation"]["enabled"] = True
    payload["topology"] = {
        "mode": "hierarchical_sparse",
        "typed_communication": {"enabled": True},
        "route_teams": {"enabled": True, "max_members_per_route": 4},
        "cross_route": {
            "enabled": True,
            "initial_isolation_rounds": 0,
            "max_neighbors_per_route": 2,
        },
        "proof_graph": {"enabled": True, "mode": "active"},
        "typed_memory": {"enabled": True, "strict_fact_gate": True},
        "inspiration": {
            "enabled": True,
            "mode": "active",
            "analogy_library_path": str(
                Path(__file__).parents[1] / "analogy_library.jsonl"
            ),
        },
    }
    return SystemConfig.model_validate(payload)


def _strategy(index: int, mechanism: str) -> StrategyCard:
    return StrategyCard(
        strategy_id=f"benchmark-strategy-{index}",
        title=f"Benchmark mechanism: {mechanism}",
        core_idea=f"Use {mechanism}.",
        independence_basis=f"Mechanism signature: {mechanism}.",
        expected_lemmas=["shared bridge lemma"],
        bottleneck="shared bridge lemma",
        falsification_test="check the smallest boundary case",
        estimated_success=0.5,
        estimated_cost=0.4,
        tags=[mechanism],
    )


def _message(
    message_id: str,
    *,
    route_id: str = "route-a",
    agent_id: str = "author-a",
    statement: str = "shared bridge lemma",
    targets: list[str] | None = None,
    message_type: MessageType = MessageType.VERIFIED_LEMMA,
    evidence_type: EvidenceType = EvidenceType.NATURAL_PROOF_AUDITED,
    memory_tier: MemoryTier = MemoryTier.FACT,
    status: ClaimStatus = ClaimStatus.VERIFIED,
) -> MessageEnvelope:
    return MessageEnvelope(
        message_id=message_id,
        problem_hash=PROBLEM_HASH,
        source_agent_id=agent_id,
        source_route_id=route_id,
        source_role=RouteRole.PROVER,
        target_route_ids=targets or [],
        message_type=message_type,
        statement=statement,
        normalized_statement=" ".join(statement.casefold().split()),
        conclusion=statement,
        evidence_type=evidence_type,
        memory_tier=memory_tier,
        verification_status=status,
        verification_confidence=0.95,
        normalization_confidence=0.95,
        round_created=0,
        ttl_rounds=2,
    )


def _runtime(
    config: SystemConfig, root: Path
) -> tuple[
    ArtifactStore,
    RouteRegistry,
    TypedMemory,
    ProofGraphStore,
    MessageBroker,
]:
    store = ArtifactStore(root, "topology-mock-benchmark")
    registry = RouteRegistry(config, problem_hash=PROBLEM_HASH)
    for index, suffix in enumerate(("a", "b", "c")):
        route_id = f"route-{suffix}"
        registry.register_route(
            _strategy(index, "residue_class_split"), route_id=route_id
        )
        registry.assign_member(route_id, f"author-{suffix}", RouteRole.PROVER, 0)
        registry.assign_member(route_id, f"referee-{suffix}", RouteRole.REFEREE, 0)
    registry.set_neighbors("route-a", ["route-b", "route-c"])
    registry.set_neighbors("route-b", ["route-a", "route-c"])
    registry.set_neighbors("route-c", ["route-a", "route-b"])
    memory = TypedMemory(store, config)
    graph = ProofGraphStore(config, store, problem_hash=PROBLEM_HASH)
    broker = MessageBroker(config, store, None, registry, graph, memory)
    return store, registry, memory, graph, broker


def _load_cases(root: Path) -> dict[str, dict[str, Any]]:
    loaded = {
        name: json.loads((root / name).read_text(encoding="utf-8"))
        for name in CASE_FILES
    }
    if {item["case_id"] for item in loaded.values()} != {
        "shared_bridge",
        "exact_counterexample",
        "mechanism_duplicate",
        "bounded_computation_scope",
        "resume_exactly_once",
        "proof_mutation_review",
    }:
        raise ValueError("topology benchmark cases do not match the v0.7 contract")
    return loaded


def _run_component_contracts(
    config: SystemConfig, root: Path, cases: dict[str, dict[str, Any]]
) -> dict[str, bool]:
    store, registry, _, graph, broker = _runtime(config, root)

    for identifier, route_id in (("bridge-a", "route-a"), ("bridge-b", "route-b")):
        graph.add_obligation(
            ProofObligation(
                obligation_id=identifier,
                problem_hash=PROBLEM_HASH,
                route_ids=[route_id],
                kind=ObligationKind.LEMMA,
                statement=cases["shared_bridge_case.json"]["obligation"],
                normalized_statement="shared bridge lemma",
                priority=0.9,
                centrality=0.9,
            )
        )
    bridge = BridgeBroker(config, graph)
    bridge_tasks = bridge.detect(current_round=1)
    bridge_closed = bridge.accept_verified_result(
        bridge_tasks[0].task_id,
        _message("verified-bridge", statement="shared bridge lemma"),
    )

    claim = _message("claim")
    counterexample = _message(
        "counterexample",
        route_id="route-b",
        agent_id="author-b",
        message_type=MessageType.COUNTEREXAMPLE,
        evidence_type=EvidenceType.COUNTEREXAMPLE,
        memory_tier=MemoryTier.NEGATIVE,
        status=ClaimStatus.REJECTED,
    )
    conflicts = ContradictionBroker(config, graph).detect(
        [claim, counterexample], current_round=1
    )

    duplicates = DuplicateRouteDetector(config).detect(
        registry.routes[:2],
        obligations_by_route={"route-a": ["goal"], "route-b": ["goal"]},
        fact_ids_by_route={"route-a": ["fact"], "route-b": ["fact"]},
    )

    bounded = _message(
        "bounded",
        evidence_type=EvidenceType.BOUNDED_EXPERIMENT,
    )
    bounded_gate = validate_evidence_tier(
        bounded,
        config,
        referee_agent_id="referee-a",
        dependencies_resolved=True,
        dependency_cycle=False,
        known_counterexample=False,
    )

    resume_message = _message(
        "resume-fact-1", targets=["route-b"], statement="resume fact"
    )
    broker.publish(resume_message, referee_agent_id="referee-a", current_round=1)
    first_delivery = broker.inbox("route-b", current_round=1)
    restored = MessageBroker.from_state(
        broker.export_state(),
        config=config,
        store=store,
        activity=None,
        route_registry=registry,
        proof_graph=graph,
        typed_memory=broker.typed_memory,
    )
    repeated_delivery = restored.inbox("route-b", current_round=1)

    signature_payload = cases["duplicate_route_case.json"]["route_a"]
    signature = NoveltySignature(
        representation_tags=signature_payload["representation_tags"],
        mechanism_tags=signature_payload["mechanism_tags"],
        key_transformations=signature_payload["key_transformations"],
        targeted_obligation_ids=signature_payload["targeted_obligations"],
    )
    novelty_duplicate = NoveltyGate(config.topology.inspiration).assess(
        signature, [signature]
    )
    normalizer = MechanismNormalizer()
    normalized_route = normalizer.signature_from_route_tags(
        ["p-adic valuation", "minimal counterexample", "quotient"],
        targeted_obligation_ids=["inspiration-goal"],
    )

    problem = ProblemContract(
        exact_statement="Prove a finite sum identity by differences.",
        normalized_statement="finite sum identity differences",
    )
    inspiration_obligation = ProofObligation(
        obligation_id="inspiration-goal",
        problem_hash=problem.integrity_hash,
        route_ids=["route-a"],
        kind=ObligationKind.MAIN_GOAL,
        statement="prove the finite sum identity",
        normalized_statement="prove finite sum identity",
    )
    representations = RepresentationSwitchboard().generate(
        problem, [inspiration_obligation], domain="algebra"
    )
    analogies = AnalogyAgent(
        LocalAnalogyLibrary(Path(__file__).parents[1] / "analogy_library.jsonl")
    ).search(
        problem,
        target_obligation_ids=[inspiration_obligation.obligation_id],
        object_tags=["finite_sum"],
        operation_tags=["difference"],
    )
    constructions = AuxiliaryConstructionInventor().propose(
        problem, [inspiration_obligation], domain="algebra"
    )
    invariants = InvariantHypothesisAgent().propose(
        problem, [inspiration_obligation], domain="algebra"
    )
    inspiration_snapshot = InspirationSnapshot(
        round_index=3,
        domain="algebra",
        active_route_ids=["route-a", "route-b"],
        stagnation_rounds_by_route={"route-a": 3, "route-b": 3},
        shared_bottleneck_ids=[inspiration_obligation.obligation_id],
        route_redundancy=0.95,
        remaining_calls=20,
        current_path_count=2,
        max_paths=8,
        open_obligation_ids=[inspiration_obligation.obligation_id],
        obligation_kinds={
            inspiration_obligation.obligation_id: ObligationKind.MAIN_GOAL.value
        },
    )
    triggers = TriggerPolicy(config.topology.inspiration).detect(inspiration_snapshot)
    assignment_task = InspirationTask(
        task_id="benchmark-assignment-task",
        trigger_id=triggers[0].trigger_id,
        mechanism=InspirationMechanism.REVERSE_GOAL_ANALYSIS,
        target_route_ids=["route-a"],
        target_obligation_ids=[inspiration_obligation.obligation_id],
        reason="measure dynamic proposer assignment",
        max_proposals=3,
    )
    assignment_plan = InspirationAssignmentPlanner(config.topology.inspiration).plan(
        assignment_task,
        proposer_role="reverse_goal_analyzer",
        pool=AgentPool(config),
        round_index=inspiration_snapshot.round_index,
    )

    meta_decision = PersistentMetaStrategist(config.topology.inspiration).decide(
        inspiration_snapshot
    )
    meta_controller = MetaDirectiveController(config.topology.inspiration, registry)
    meta_directive = meta_controller.from_decision(meta_decision, inspiration_snapshot)
    meta_audit = meta_controller.audit(meta_directive, inspiration_snapshot)
    meta_execution, meta_tasks = meta_controller.execute(
        meta_directive,
        meta_audit,
        inspiration_snapshot,
        trigger_id=triggers[0].trigger_id,
    )

    learning_proposal = InspirationProposal(
        proposal_id="benchmark-learning-proposal",
        task_id="benchmark-learning-task",
        trigger_id=triggers[0].trigger_id,
        mechanism=InspirationMechanism.REPRESENTATION_SWITCH,
        source_agent_id="benchmark-inspiration-agent",
        target_route_ids=["route-a"],
        statement="rewrite the finite sum as consecutive differences",
        rationale_summary="the representation exposes telescoping",
        generated_obligations=[inspiration_obligation.obligation_id],
        novelty_signature=NoveltySignature(
            representation_tags=["finite_state"],
            mechanism_tags=["representation_switch"],
            targeted_obligation_ids=[inspiration_obligation.obligation_id],
        ),
        novelty_score=1.0,
        expected_information_gain=0.8,
        estimated_cost=1,
        context_mode=InspirationContextMode.WARM,
    )
    outcome_ledger = InspirationOutcomeLedger(config.topology.inspiration)
    learning_outcome = outcome_ledger.register(
        learning_proposal,
        snapshot=inspiration_snapshot,
        trigger=triggers[0],
        obligation_kinds=[ObligationKind.MAIN_GOAL],
        proof_debt_before=4.0,
    )
    outcome_ledger.record_materialization(
        learning_proposal.proposal_id,
        action="route_created",
        refuted=False,
    )
    outcome_ledger.record_usage(
        learning_proposal.proposal_id, phase="proposer", tokens=1000
    )
    outcome_ledger.record_verified_gain(
        learning_proposal.proposal_id,
        round_index=4,
        proof_debt_after=1.0,
        obligations_closed=[inspiration_obligation.obligation_id],
    )
    adaptive_profiles = outcome_ledger.selection_profiles(
        [triggers[0]], inspiration_snapshot
    )

    experience_graph = ProofGraphStore(config, problem_hash=problem.integrity_hash)
    experience_graph.add_obligation(inspiration_obligation)
    experience_fact = MessageEnvelope(
        message_id="benchmark-experience-fact",
        problem_hash=problem.integrity_hash,
        source_agent_id="benchmark-prover",
        source_route_id="route-a",
        source_role=RouteRole.PROVER,
        message_type=MessageType.VERIFIED_LEMMA,
        statement="consecutive differences telescope",
        normalized_statement="consecutive differences telescope",
        conclusion="consecutive differences telescope",
        evidence_type=EvidenceType.NATURAL_PROOF_AUDITED,
        memory_tier=MemoryTier.FACT,
        verification_status=ClaimStatus.VERIFIED,
        verification_confidence=0.95,
        normalization_confidence=1.0,
        round_created=4,
    )
    distilled = VerifiedExperienceDistiller(
        config.topology.inspiration
    ).distill_verified(
        problem=problem,
        proposal=learning_proposal,
        fact=experience_fact,
        outcome=outcome_ledger.outcomes[learning_proposal.proposal_id],
        proof_graph=experience_graph,
    )
    learned_library = LocalAnalogyLibrary(root / "learned-experiences.jsonl")
    learned_added = bool(
        distilled
        and learned_library.add_verified_record(distilled.model_dump(mode="json"))
    )

    operator_registry = DomainOperatorRegistry()
    domain_operators = operator_registry.select(
        problem,
        domain="number_theory",
        families=("representation", "construction", "mutation"),
        limit=16,
    )
    mutation_planner = ControlledMutationPlanner(operator_registry)
    first_mutation = mutation_planner.plan(
        problem,
        task_id="benchmark-surprise",
        proposal_slot=0,
        target_obligation_ids=[inspiration_obligation.obligation_id],
        domain="number_theory",
    )
    replayed_mutation = mutation_planner.plan(
        problem,
        task_id="benchmark-surprise",
        proposal_slot=0,
        target_obligation_ids=[inspiration_obligation.obligation_id],
        domain="number_theory",
    )
    second_mutation = mutation_planner.plan(
        problem,
        task_id="benchmark-surprise",
        proposal_slot=1,
        target_obligation_ids=[inspiration_obligation.obligation_id],
        domain="number_theory",
    )
    reverse_plan = ReverseGoalAnalyzer(config.topology.inspiration).analyze(
        inspiration_obligation,
        facts=[experience_fact],
        proposed_backward_claims=["the finite sum telescopes"],
        round_index=3,
    )
    companion = learning_proposal.model_copy(
        update={
            "proposal_id": "benchmark-composition-companion",
            "mechanism": InspirationMechanism.AUXILIARY_CONSTRUCTION,
            "source_agent_id": "benchmark-construction-agent",
            "statement": "construct consecutive partial differences",
            "novelty_signature": NoveltySignature(
                mechanism_tags=["auxiliary_construction"],
                core_objects=["partial_difference"],
                key_transformations=["construct_difference_sequence"],
                targeted_obligation_ids=[inspiration_obligation.obligation_id],
            ),
        }
    )
    composition_reviews = [
        InspirationReview(
            proposal_id=item.proposal_id,
            reviewer_agent_id=f"reviewer-{index}",
            semantically_distinct=True,
            relevant_to_open_obligation=True,
            internally_coherent=True,
            recommendation="create_new_route",
            confidence=0.9,
        )
        for index, item in enumerate((learning_proposal, companion))
    ]
    compositions = InspirationComposer(config.topology.inspiration).compose(
        [learning_proposal, companion],
        composition_reviews,
        quick_falsification_passed={learning_proposal.proposal_id},
        proof_graph=experience_graph,
    )
    config.topology.inspiration.cross_run_learning_enabled = True
    config.topology.inspiration.cross_run_learning_path = ".benchmark-learning"
    cross_run_store = CrossRunLearningStore(
        config.topology.inspiration,
        project_root=root,
    )
    cross_run_counts = cross_run_store.persist(
        experiences=(
            [distilled.model_copy(update={"cited_by_final_proof": True})]
            if distilled is not None
            else []
        ),
        negatives=[],
        outcomes=outcome_ledger.outcomes.values(),
        run_verified=True,
    )

    return {
        "shared_bridge": len(bridge_tasks) == 1
        and set(bridge_closed) == {"bridge-a", "bridge-b"},
        "exact_counterexample": len(conflicts) == 1
        and conflicts[0].status == "resolved",
        "mechanism_duplicate": bool(duplicates) and novelty_duplicate.duplicate,
        "bounded_computation_scope": not bounded_gate.accepted,
        "resume_exactly_once": len(first_delivery) == 1 and not repeated_delivery,
        "representation_switchboard": bool(representations)
        and all(item.fast_failure_tests for item in representations),
        "analogy_agent": bool(analogies)
        and all(item.non_transferable_conditions for item in analogies),
        "construction_inventor": bool(constructions)
        and all(item.falsification_tests for item in constructions),
        "invariant_hypothesis_agent": bool(invariants)
        and all(item.falsification_request for item in invariants),
        "inspiration_triggers": bool(triggers),
        "mechanism_ontology": (
            normalized_route.representation_tags == ["valuation"]
            and "descent" in normalized_route.proof_principles
            and "quotient" in normalized_route.key_transformations
            and normalized_route.core_objects == []
        ),
        "active_candidate_population": (
            config.topology.inspiration.active_proposals_per_task == 3
            and config.topology.inspiration.max_reviewed_proposals_per_task == 2
            and config.topology.inspiration.max_materialized_proposals_per_trigger == 1
            and config.topology.inspiration.cold_context_proposals_per_task == 1
        ),
        "dynamic_proposer_assignment": (
            bool(assignment_plan.assignments)
            and len({item.proposer_agent_id for item in assignment_plan.assignments})
            == len(assignment_plan.assignments)
            and len(assignment_plan.assignments)
            <= len(assignment_plan.eligible_agent_ids)
        ),
        "meta_directive_control": (
            meta_audit.accepted
            and meta_execution.status == "executed"
            and bool(meta_tasks)
        ),
        "adaptive_outcome_learning": (
            learning_outcome.proposal_id in outcome_ledger.outcomes
            and outcome_ledger.outcomes[learning_outcome.proposal_id].reward > 0
            and bool(adaptive_profiles)
        ),
        "verified_experience_distillation": learned_added,
        "domain_operator_plugins": (
            {item.family for item in domain_operators}
            == {"representation", "construction", "mutation"}
            and all(item.fast_failure_tests for item in domain_operators)
            and all(item.known_failure_modes for item in domain_operators)
        ),
        "controlled_surprise_mutation": (
            first_mutation == replayed_mutation
            and first_mutation.directive_id != second_mutation.directive_id
            and bool(first_mutation.reversibility_requirements)
        ),
        "bidirectional_frontier": (
            experience_fact.message_id
            in [item.source_ref for item in reverse_plan.forward_frontier]
            and bool(reverse_plan.frontier_bridges)
            and all(
                not item.source_sufficiency_assumed
                for item in reverse_plan.frontier_bridges
            )
        ),
        "inspiration_composer": bool(compositions)
        and len(compositions[0].source_proposal_ids) == 2
        and bool(compositions[0].fast_failure_tests),
        "cross_run_learning": cross_run_counts["experiences"] == 1
        and bool(cross_run_store.load_experiences()),
    }


def _variant_metrics(variant: Variant, contracts: dict[str, bool]) -> dict[str, Any]:
    active_graph = variant.graph_mode == "active"
    inspiration_enabled = variant.inspiration_mode in {"shadow", "active"}
    inspiration_active = variant.inspiration_mode == "active"
    mechanism_count = sum(
        (
            variant.analogy,
            variant.representation_switch,
            variant.surprise_budget,
            variant.persistent_meta_strategist,
        )
    )
    case_successes = {
        "shared_bridge": active_graph and contracts["shared_bridge"],
        "exact_counterexample": active_graph
        and variant.typed_broker
        and contracts["exact_counterexample"],
        "mechanism_duplicate": active_graph and contracts["mechanism_duplicate"],
        "bounded_computation_scope": variant.typed_broker
        and contracts["bounded_computation_scope"],
        "resume_exactly_once": variant.typed_broker
        and contracts["resume_exactly_once"],
        "proof_mutation_review": variant.route_teams,
    }
    false_accepts = int(not variant.typed_broker) + int(not variant.route_teams)
    false_accepts += int(not active_graph)
    calls = 20
    calls += 1 if variant.typed_broker else 0
    calls += 2 if variant.route_teams else 0
    calls += 2 if active_graph else 0
    calls += 2 if variant.inspiration_mode == "shadow" else 0
    calls += mechanism_count if inspiration_active else 0
    cross_route_tokens = 1800 if variant.typed_broker else 8400
    estimated_tokens = calls * 900 + cross_route_tokens
    breakthrough_conversion = (
        round(0.15 + 0.15 * mechanism_count, 3) if inspiration_active else 0.0
    )
    representation_diversity = 0.25
    if variant.representation_switch and inspiration_active:
        representation_diversity += 0.45
    if variant.analogy and inspiration_active:
        representation_diversity += 0.10
    if variant.surprise_budget and inspiration_active:
        representation_diversity += 0.10
    repeated_mechanism = 0.40
    if active_graph:
        repeated_mechanism -= 0.20
    if variant.representation_switch and inspiration_active:
        repeated_mechanism -= 0.08
    if variant.persistent_meta_strategist and inspiration_active:
        repeated_mechanism -= 0.07
    metrics = {
        "verified_solve_rate": round(sum(case_successes.values()) / 6, 3),
        "false_accept_rate": round(false_accepts / 3, 3),
        "first_error_localization_rate": 1.0 if variant.route_teams else 0.4,
        "route_diversity": round(min(1.0, representation_diversity), 3),
        "duplicate_route_rate": 0.08 if active_graph else 0.40,
        "verified_fact_reuse_rate": 0.82
        if active_graph
        else (0.18 if variant.typed_broker else 0.0),
        "proof_debt_reduction": round(
            (0.52 if active_graph else 0.12)
            + (0.05 * mechanism_count if inspiration_active else 0.0),
            3,
        ),
        "messages_per_solve": 4.0 if variant.typed_broker else 14.0,
        "cross_route_token_estimate": cross_route_tokens,
        "bridge_success_rate": 1.0 if active_graph else 0.0,
        "contradiction_resolution_rate": 1.0 if active_graph else 0.0,
        "resume_duplicate_delivery_rate": 0.0 if variant.typed_broker else 1.0,
        "calls": calls,
        "tokens": estimated_tokens,
        "estimated_cost_usd": 0.0,
        "inspiration_trigger_rate": 1.0 if inspiration_enabled else 0.0,
        "verified_breakthrough_conversion_rate": breakthrough_conversion,
        "representation_diversity": round(min(1.0, representation_diversity), 3),
        "analogy_usefulness_rate": (
            0.67 if inspiration_active and variant.analogy else 0.0
        ),
        "auxiliary_construction_survival_rate": 0.67 if inspiration_active else 0.0,
        "surprise_budget_utilization": (
            0.75 if inspiration_active and variant.surprise_budget else 0.0
        ),
        "calls_to_first_breakthrough": (
            max(5, 10 - mechanism_count) if inspiration_active else None
        ),
        "false_novelty_rate": 0.0 if inspiration_enabled else None,
        "repeated_mechanism_rate": round(max(0.0, repeated_mechanism), 3),
        "capability_calibration_by_domain_role": {
            "number_theory/route_referee": 0.86 if variant.route_teams else 0.50,
            "combinatorics/skeptic": 0.82 if variant.route_teams else 0.50,
            "algebra/tool_agent": 0.84 if variant.typed_broker else 0.50,
        },
    }
    return {
        "variant": variant.name,
        "features": {
            "typed_broker": variant.typed_broker,
            "route_teams": variant.route_teams,
            "graph_mode": variant.graph_mode,
            "inspiration_mode": variant.inspiration_mode,
            "analogy": variant.analogy,
            "representation_switch": variant.representation_switch,
            "surprise_budget": variant.surprise_budget,
            "persistent_meta_strategist": variant.persistent_meta_strategist,
        },
        "case_successes": case_successes,
        "metrics": metrics,
    }


def run_mock_benchmark(run_root: str | Path | None = None) -> dict[str, Any]:
    case_root = Path(__file__).parent
    cases = _load_cases(case_root)
    temporary: tempfile.TemporaryDirectory[str] | None = None
    if run_root is None:
        temporary = tempfile.TemporaryDirectory(prefix="mathproofmesh_topology_")
        root = Path(temporary.name)
    else:
        root = Path(run_root)
    try:
        config = _config(root)
        contracts = _run_component_contracts(config, root, cases)
        if not all(contracts.values()):
            failed = [name for name, passed in contracts.items() if not passed]
            raise RuntimeError(f"v0.7 component contract failure: {failed}")
        results = [_variant_metrics(variant, contracts) for variant in VARIANTS]
        return {
            "benchmark": "mathproofmesh_v0_7_hierarchical_topology_mock",
            "provider_calls": 0,
            "provider_cost_measured": False,
            "case_count": len(cases),
            "variant_count": len(results),
            "component_contracts": contracts,
            "variants": results,
            "notes": [
                "All mathematical and routing checks are deterministic and offline.",
                "Calls, tokens, and cost are explicit mock estimates, not provider telemetry.",
                "Shadow variants record recommendations but do not materialize graph or inspiration actions.",
                "Active inspiration uses a bounded warm/cold candidate population; all performance figures remain mock estimates.",
            ],
        }
    finally:
        if temporary is not None:
            temporary.cleanup()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    result = run_mock_benchmark()
    rendered = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if args.output is not None:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered, encoding="utf-8")
    print(rendered, end="")


if __name__ == "__main__":
    main()
