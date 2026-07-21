from __future__ import annotations

from mathproofmesh.communication.route_registry import RouteRegistry
from mathproofmesh.llm.pool import AgentPool
from mathproofmesh.mock_demo import demo_responders
from mathproofmesh.schemas import (
    ClaimStatus,
    EvidenceType,
    MemoryTier,
    MessageType,
    RouteRole,
)
from mathproofmesh.teams.role_runner import RoleRunner
from mathproofmesh.teams.route_team import RouteTeam

from v07_helpers import PROBLEM_HASH, make_message, make_strategy, make_v07_config


def test_high_risk_computation_uses_skeptic_tool_and_independent_referee(
    tmp_path,
) -> None:
    config = make_v07_config(tmp_path / "runs")
    registry = RouteRegistry(config, problem_hash=PROBLEM_HASH)
    registry.register_route(make_strategy(0), route_id="route-a")
    pool = AgentPool(config, mock_responders=demo_responders(config))
    team = RouteTeam(config, RoleRunner(pool, registry))
    artifact = make_message(
        message_id="numeric",
        route_id="route-a",
        agent_id="explorer-a",
        message_type=MessageType.COMPUTATION_CERTIFICATE,
        evidence_type=EvidenceType.BOUNDED_EXPERIMENT,
        memory_tier=MemoryTier.INSIGHT,
        status=ClaimStatus.UNCERTAIN,
        confidence=0.4,
    )
    plan = team.plan(
        "route-a",
        "explorer-a",
        artifact,
        round_index=1,
        entering_global_fact_gate=True,
    )
    assert plan.skeptic is not None and plan.skeptic.agent_id != "explorer-a"
    assert plan.tool_specialist is not None
    assert plan.referee.agent_id not in {None, "explorer-a"}
    assert (
        len(
            {
                plan.prover.agent_id,
                plan.skeptic.agent_id,
                plan.tool_specialist.agent_id,
                plan.referee.agent_id,
            }
        )
        == 4
    )
    assert registry.owns_agent("route-a", plan.referee.agent_id, RouteRole.REFEREE)


def test_low_risk_route_does_not_spend_a_skeptic_call(tmp_path) -> None:
    config = make_v07_config(tmp_path / "runs")
    registry = RouteRegistry(config, problem_hash=PROBLEM_HASH)
    registry.register_route(make_strategy(0), route_id="route-a")
    pool = AgentPool(config, mock_responders=demo_responders(config))
    team = RouteTeam(config, RoleRunner(pool, registry))
    plan = team.plan(
        "route-a",
        "explorer-a",
        {"statement": "a direct algebraic identity", "self_confidence": 0.95},
        round_index=0,
    )
    assert plan.skeptic is None
    assert plan.tool_specialist is None
    assert plan.referee.agent_id != "explorer-a"
