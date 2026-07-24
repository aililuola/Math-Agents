from __future__ import annotations

import time

from mathproofmesh.config import AgentConfig
from mathproofmesh.inspiration.assignment import InspirationAssignmentPlanner
from mathproofmesh.llm.pool import AgentPool
from mathproofmesh.schemas import (
    InspirationContextMode,
    InspirationMechanism,
    InspirationTask,
)

from v07_helpers import make_v07_config


def _task(
    mechanism: InspirationMechanism = InspirationMechanism.REVERSE_GOAL_ANALYSIS,
) -> InspirationTask:
    return InspirationTask(
        task_id="task-assignment",
        trigger_id="trigger-assignment",
        mechanism=mechanism,
        target_route_ids=["route-a"],
        target_obligation_ids=["goal-a"],
        reason="the current local bridge is stalled",
        max_proposals=3,
    )


def _pool(
    tmp_path, roles: list[list[str]]
) -> tuple[InspirationAssignmentPlanner, AgentPool]:
    config = make_v07_config(tmp_path / "runs")
    config.agents = [
        AgentConfig(
            id=f"candidate-{index}",
            provider="mock",
            model="mock",
            roles=agent_roles,
        )
        for index, agent_roles in enumerate(roles)
    ]
    return InspirationAssignmentPlanner(config.topology.inspiration), AgentPool(config)


def test_assignment_uses_dynamic_pool_and_distinct_agents(tmp_path) -> None:
    planner, pool = _pool(
        tmp_path,
        [
            ["reverse_goal_analyzer"],
            ["explorer"],
            ["route_prover"],
            ["explorer", "route_prover"],
            ["route_referee"],
            ["explorer"],
        ],
    )

    plan = planner.plan(
        _task(),
        proposer_role="reverse_goal_analyzer",
        pool=pool,
        round_index=4,
    )

    assert len(plan.assignments) == 3
    assert len({item.proposer_agent_id for item in plan.assignments}) == 3
    assert plan.assignments[0].specialist_match
    assert set(plan.eligible_agent_ids) == {
        "candidate-0",
        "candidate-1",
        "candidate-2",
        "candidate-3",
        "candidate-5",
    }
    assert [item.context_mode for item in plan.assignments] == [
        InspirationContextMode.WARM,
        InspirationContextMode.WARM,
        InspirationContextMode.COLD,
    ]


def test_assignment_does_not_repeat_when_two_agents_are_available(tmp_path) -> None:
    planner, pool = _pool(
        tmp_path,
        [["reverse_goal_analyzer"], ["explorer"], ["route_referee"]],
    )

    plan = planner.plan(
        _task(),
        proposer_role="reverse_goal_analyzer",
        pool=pool,
        round_index=2,
    )

    assert len(plan.assignments) == 2
    assert len({item.proposer_agent_id for item in plan.assignments}) == 2
    assert [item.context_mode for item in plan.assignments] == [
        InspirationContextMode.WARM,
        InspirationContextMode.COLD,
    ]


def test_single_agent_fallback_is_bounded_to_warm_and_cold(tmp_path) -> None:
    planner, pool = _pool(
        tmp_path,
        [["reverse_goal_analyzer"], ["route_referee"]],
    )

    plan = planner.plan(
        _task(),
        proposer_role="reverse_goal_analyzer",
        pool=pool,
        round_index=2,
    )

    assert len(plan.assignments) == 2
    assert {item.proposer_agent_id for item in plan.assignments} == {"candidate-0"}
    assert [item.context_mode for item in plan.assignments] == [
        InspirationContextMode.WARM,
        InspirationContextMode.COLD,
    ]


def test_cooled_down_agent_causes_task_deferral(tmp_path) -> None:
    planner, pool = _pool(tmp_path, [["reverse_goal_analyzer"]])
    pool.agents[0].cooldown_until = time.monotonic() + 60

    plan = planner.plan(
        _task(),
        proposer_role="reverse_goal_analyzer",
        pool=pool,
        round_index=2,
    )

    assert plan.assignments == []
    assert plan.deferred_reason is not None
    assert "no available" in plan.deferred_reason


def test_meta_replan_does_not_consume_generalist_proposers(tmp_path) -> None:
    planner, pool = _pool(
        tmp_path,
        [["meta_strategist"], ["explorer"], ["route_prover"]],
    )

    plan = planner.plan(
        _task(InspirationMechanism.META_REPLAN),
        proposer_role="meta_strategist",
        pool=pool,
        round_index=2,
        allow_generalists=False,
        requested_proposals=1,
    )

    assert [item.proposer_agent_id for item in plan.assignments] == ["candidate-0"]
