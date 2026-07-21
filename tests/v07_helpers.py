from __future__ import annotations

from pathlib import Path
from typing import Any

from mathproofmesh.communication.broker import MessageBroker
from mathproofmesh.communication.route_registry import RouteRegistry
from mathproofmesh.config import SystemConfig
from mathproofmesh.memory import TypedMemory
from mathproofmesh.mock_demo import build_demo_config
from mathproofmesh.proof_graph.store import ProofGraphStore
from mathproofmesh.schemas import (
    ClaimStatus,
    EvidenceType,
    MemoryTier,
    MessageEnvelope,
    MessageType,
    QuantifierSpec,
    RouteRole,
    StrategyCard,
    VariableBinding,
)
from mathproofmesh.store import ArtifactStore


PROBLEM_HASH = "7" * 64


def make_v07_config(
    run_root: str | Path,
    *,
    graph_mode: str = "active",
    inspiration_mode: str = "active",
) -> SystemConfig:
    base = build_demo_config(str(run_root))
    payload = base.model_dump(mode="python")
    payload["budget"].update(
        {
            "max_total_calls": 64,
            "max_paths": 8,
            "max_revisions": 1,
        }
    )
    payload["continuation"]["enabled"] = True
    payload["topology"] = {
        "mode": "hierarchical_sparse",
        "neighbor_k": 2,
        "max_context_chars": 12000,
        "max_verified_claims_per_context": 12,
        "typed_communication": {"enabled": True},
        "route_teams": {"enabled": True, "max_members_per_route": 4},
        "cross_route": {
            "enabled": True,
            "initial_isolation_rounds": 1,
            "max_neighbors_per_route": 2,
            "max_messages_per_route_per_round": 4,
            "max_global_messages_per_round": 12,
        },
        "proof_graph": {"enabled": True, "mode": graph_mode},
        "typed_memory": {"enabled": True, "strict_fact_gate": True},
        "inspiration": {
            "enabled": True,
            "mode": inspiration_mode,
            "stagnation_rounds": 2,
            "novelty_threshold": 0.60,
            "analogy_library_enabled": False,
        },
        "validation_escalation": {"enabled": True},
        "agent_capability": {"enabled": True},
    }
    return SystemConfig.model_validate(payload)


def make_strategy(index: int, *, tag: str | None = None) -> StrategyCard:
    mechanism = tag or f"mechanism-{index}"
    return StrategyCard(
        strategy_id=f"strategy-{index}",
        title=f"Route {index}: {mechanism}",
        core_idea=f"Use {mechanism} to close the target.",
        independence_basis=f"The route is organized around {mechanism}.",
        expected_lemmas=[f"lemma-{index}"],
        bottleneck=f"justify {mechanism}",
        falsification_test=f"test a boundary case for {mechanism}",
        estimated_success=0.5,
        estimated_cost=0.4,
        tags=[mechanism],
    )


def make_message(
    *,
    message_id: str,
    route_id: str,
    agent_id: str,
    statement: str = "a reusable local identity",
    target_routes: list[str] | None = None,
    message_type: MessageType = MessageType.CLAIM_PROPOSAL,
    evidence_type: EvidenceType = EvidenceType.UNVERIFIED_IDEA,
    memory_tier: MemoryTier = MemoryTier.INSIGHT,
    status: ClaimStatus = ClaimStatus.PROPOSED,
    confidence: float = 0.0,
    normalization_confidence: float = 1.0,
    dependencies: list[str] | None = None,
    scope_limitations: list[str] | None = None,
    conclusion: str | None = None,
    quantifiers: list[QuantifierSpec] | None = None,
    variable_bindings: list[VariableBinding] | None = None,
    round_created: int = 0,
    ttl_rounds: int = 2,
    source_role: RouteRole = RouteRole.PROVER,
) -> MessageEnvelope:
    normalized = " ".join(statement.casefold().split())
    return MessageEnvelope(
        message_id=message_id,
        problem_hash=PROBLEM_HASH,
        source_agent_id=agent_id,
        source_route_id=route_id,
        source_role=source_role,
        target_route_ids=target_routes or [],
        message_type=message_type,
        statement=statement,
        normalized_statement=normalized,
        assumptions=[],
        conclusion=conclusion or statement,
        quantifiers=quantifiers or [],
        variable_bindings=variable_bindings or [],
        dependencies=dependencies or [],
        scope_limitations=scope_limitations or [],
        evidence_type=evidence_type,
        memory_tier=memory_tier,
        verification_status=status,
        verification_confidence=confidence,
        normalization_confidence=normalization_confidence,
        round_created=round_created,
        ttl_rounds=ttl_rounds,
    )


def make_fact(**overrides: Any) -> MessageEnvelope:
    values: dict[str, Any] = {
        "message_id": "fact-1",
        "route_id": "route-a",
        "agent_id": "author-a",
        "statement": "a reusable local identity",
        "message_type": MessageType.VERIFIED_LEMMA,
        "evidence_type": EvidenceType.NATURAL_PROOF_AUDITED,
        "memory_tier": MemoryTier.FACT,
        "status": ClaimStatus.VERIFIED,
        "confidence": 0.95,
        "normalization_confidence": 0.95,
    }
    values.update(overrides)
    return make_message(**values)


def make_broker_runtime(
    config: SystemConfig,
    tmp_path: Path,
    *,
    route_count: int = 3,
) -> tuple[
    ArtifactStore,
    RouteRegistry,
    TypedMemory,
    ProofGraphStore,
    MessageBroker,
]:
    store = ArtifactStore(tmp_path / "runs", "v07-broker")
    registry = RouteRegistry(config, problem_hash=PROBLEM_HASH)
    for index in range(route_count):
        route_id = f"route-{chr(ord('a') + index)}"
        route = registry.register_route(make_strategy(index), route_id=route_id)
        registry.assign_member(
            route.route_id,
            f"author-{chr(ord('a') + index)}",
            RouteRole.PROVER,
            0,
        )
        registry.assign_member(
            route.route_id,
            f"referee-{chr(ord('a') + index)}",
            RouteRole.REFEREE,
            0,
        )
    route_ids = [item.route_id for item in registry.routes]
    for route_id in route_ids:
        registry.set_neighbors(
            route_id, [item for item in route_ids if item != route_id]
        )
    memory = TypedMemory(store, config)
    graph = ProofGraphStore(config, store, problem_hash=PROBLEM_HASH)
    broker = MessageBroker(config, store, None, registry, graph, memory)
    return store, registry, memory, graph, broker
