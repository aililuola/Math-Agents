from __future__ import annotations

from dataclasses import dataclass
from typing import Iterable

from ..communication.route_registry import RouteRegistry
from ..llm.pool import AgentPool, AgentRuntime
from ..schemas import RouteRole


ROLE_FALLBACKS: dict[RouteRole, tuple[str, ...]] = {
    RouteRole.PROVER: ("route_prover", "explorer", "general"),
    RouteRole.SKEPTIC: ("route_skeptic", "detailed_verifier", "general"),
    RouteRole.REFEREE: (
        "route_referee",
        "structural_verifier",
        "detailed_verifier",
    ),
    RouteRole.TOOL_SPECIALIST: ("tool_specialist", "general"),
    RouteRole.BRIDGE_PROVER: ("bridge_prover", "explorer", "general"),
    RouteRole.CONFLICT_RESOLVER: (
        "conflict_resolver",
        "meta_reviewer",
        "general",
    ),
    RouteRole.COUNTEREXAMPLE_HUNTER: (
        "counterexample_hunter",
        "detailed_verifier",
        "general",
    ),
}


@dataclass(frozen=True, slots=True)
class RoleAssignment:
    route_id: str
    role: RouteRole
    agent_id: str | None
    selected_via: str | None
    local_only: bool = False
    reason: str = ""


class RoleRunner:
    """Select role-capable agents while keeping author/referee independence."""

    def __init__(self, pool: AgentPool, registry: RouteRegistry) -> None:
        self.pool = pool
        self.registry = registry

    def select(
        self,
        route_id: str,
        role: RouteRole,
        *,
        round_index: int,
        exclude: Iterable[str] = (),
        specialty_hints: Iterable[str] = (),
    ) -> RoleAssignment:
        excluded = set(exclude)
        for configured_role in ROLE_FALLBACKS[role]:
            try:
                agent = self.pool.select(
                    configured_role,
                    exclude=excluded,
                    specialty_hints=specialty_hints,
                )
            except RuntimeError:
                continue
            if agent.id in excluded:
                # AgentPool has a general last-resort reuse fallback. A referee
                # must not use it because author independence is a fact gate.
                continue
            try:
                self.registry.assign_member(route_id, agent.id, role, round_index)
            except ValueError:
                continue
            return RoleAssignment(
                route_id=route_id,
                role=role,
                agent_id=agent.id,
                selected_via=configured_role,
            )
        return RoleAssignment(
            route_id=route_id,
            role=role,
            agent_id=None,
            selected_via=None,
            local_only=True,
            reason="no independent agent is available for this role",
        )

    def runtime(self, assignment: RoleAssignment) -> AgentRuntime:
        if assignment.agent_id is None:
            raise RuntimeError(assignment.reason or "role is unassigned")
        return self.pool.get(assignment.agent_id)
