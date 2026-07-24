from __future__ import annotations

from collections.abc import Sequence

from ..config import InspirationConfig
from ..llm.pool import AgentPool, AgentRuntime
from ..schemas import (
    InspirationAssignmentPlan,
    InspirationContextMode,
    InspirationProposalAssignment,
    InspirationTask,
)


class InspirationAssignmentPlanner:
    """Build a bounded, distinct proposer population from the live agent pool."""

    def __init__(self, config: InspirationConfig) -> None:
        self.config = config

    def plan(
        self,
        task: InspirationTask,
        *,
        proposer_role: str,
        pool: AgentPool,
        round_index: int,
        specialty_hints: Sequence[str] = (),
        allow_generalists: bool = True,
        requested_proposals: int | None = None,
    ) -> InspirationAssignmentPlan:
        requested = min(
            requested_proposals
            if requested_proposals is not None
            else self.config.active_proposals_per_task,
            task.max_proposals,
            self.config.max_proposals_per_task,
        )
        available = [agent for agent in pool.agents if not agent.in_cooldown]
        specialists = [
            agent
            for agent in available
            if proposer_role in agent.config.roles or "general" in agent.config.roles
        ]
        generalist_roles = set(self.config.proposer_generalist_roles)
        generalists = (
            [
                agent
                for agent in available
                if any(role in generalist_roles for role in agent.config.roles)
            ]
            if allow_generalists
            else []
        )
        specialist_ids = {agent.id for agent in specialists}
        eligible_by_id = {agent.id: agent for agent in (*specialists, *generalists)}
        eligible = sorted(
            eligible_by_id.values(),
            key=lambda agent: self._rank_key(
                agent,
                proposer_role=proposer_role,
                pool=pool,
                specialist=agent.id in specialist_ids,
                specialty_hints=specialty_hints,
            ),
        )
        if requested <= 0:
            return InspirationAssignmentPlan(
                task_id=task.task_id,
                mechanism=task.mechanism,
                round_index=round_index,
                requested_proposals=0,
                eligible_agent_ids=[agent.id for agent in eligible],
                deferred_reason="task requested no active proposals",
            )
        if not eligible:
            return InspirationAssignmentPlan(
                task_id=task.task_id,
                mechanism=task.mechanism,
                round_index=round_index,
                requested_proposals=requested,
                deferred_reason=(
                    f"no available specialist or configured generalist for "
                    f"{proposer_role}"
                ),
            )

        if len(eligible) == 1:
            assignment_count = min(
                requested,
                self.config.max_single_agent_proposals_per_task,
            )
            selected = [eligible[0]] * assignment_count
        else:
            assignment_count = min(requested, len(eligible))
            selected = eligible[:assignment_count]

        cold_count = (
            min(
                self.config.cold_context_proposals_per_task,
                assignment_count - 1,
            )
            if assignment_count > 1
            else 0
        )
        cold_start = assignment_count - cold_count
        assignments = [
            InspirationProposalAssignment(
                task_id=task.task_id,
                mechanism=task.mechanism,
                proposal_slot=slot,
                proposer_agent_id=agent.id,
                proposer_role=proposer_role,
                context_mode=(
                    InspirationContextMode.COLD
                    if slot >= cold_start
                    else InspirationContextMode.WARM
                ),
                specialist_match=agent.id in specialist_ids,
            )
            for slot, agent in enumerate(selected)
        ]
        return InspirationAssignmentPlan(
            task_id=task.task_id,
            mechanism=task.mechanism,
            round_index=round_index,
            requested_proposals=requested,
            eligible_agent_ids=[agent.id for agent in eligible],
            assignments=assignments,
        )

    @staticmethod
    def _rank_key(
        agent: AgentRuntime,
        *,
        proposer_role: str,
        pool: AgentPool,
        specialist: bool,
        specialty_hints: Sequence[str],
    ) -> tuple[float, float, float, float, int, int, str]:
        return (
            -float(specialist),
            -pool.capability_score(agent, proposer_role),
            -agent.specialty_score(specialty_hints),
            -agent.trust_score,
            agent.active_calls,
            agent.calls,
            agent.id,
        )
