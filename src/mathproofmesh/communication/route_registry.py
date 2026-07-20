from __future__ import annotations

from typing import Any, Iterable

from ..config import SystemConfig
from ..schemas import (
    RouteDescriptor,
    RouteMember,
    RouteRole,
    RouteStatus,
    StrategyCard,
    new_id,
)


class RouteRegistry:
    """Authoritative route identity, role membership, and sparse-neighbor registry."""

    def __init__(
        self,
        config: SystemConfig | None = None,
        *,
        problem_hash: str = "",
        routes: Iterable[RouteDescriptor] = (),
    ) -> None:
        self.config = config
        self.problem_hash = problem_hash
        self._routes = {route.route_id: route for route in routes}
        self.cooling_reasons: dict[str, str] = {}

    @property
    def routes(self) -> list[RouteDescriptor]:
        return list(self._routes.values())

    def get(self, route_id: str) -> RouteDescriptor:
        try:
            return self._routes[route_id]
        except KeyError as exc:
            raise KeyError(f"unknown route: {route_id}") from exc

    def register_route(
        self,
        strategy: StrategyCard,
        *,
        route_id: str | None = None,
    ) -> RouteDescriptor:
        identifier = route_id or new_id("route")
        if identifier in self._routes:
            existing = self._routes[identifier]
            if existing.strategy_id != strategy.strategy_id:
                raise ValueError("route ID already belongs to another strategy")
            return existing
        mechanism = list(
            dict.fromkeys([*strategy.tags, strategy.title, *strategy.expected_lemmas])
        )
        descriptor = RouteDescriptor(
            route_id=identifier,
            strategy_id=strategy.strategy_id,
            mechanism_signature=mechanism,
        )
        self._routes[identifier] = descriptor
        return descriptor

    def assign_member(
        self,
        route_id: str,
        agent_id: str,
        role: RouteRole,
        round_index: int,
    ) -> None:
        route = self.get(route_id)
        for member in route.members:
            if member.agent_id == agent_id and member.role == role:
                return
        maximum = (
            self.config.topology.route_teams.max_members_per_route
            if self.config is not None
            else 8
        )
        unique_agents = {member.agent_id for member in route.members}
        if agent_id not in unique_agents and len(unique_agents) >= maximum:
            raise ValueError(f"route {route_id} has reached its member limit")
        route.members.append(
            RouteMember(agent_id=agent_id, role=role, assigned_round=round_index)
        )

    def owns_agent(
        self, route_id: str, agent_id: str, role: RouteRole | None = None
    ) -> bool:
        route = self._routes.get(route_id)
        if route is None:
            return False
        return any(
            member.agent_id == agent_id and (role is None or member.role == role)
            for member in route.members
        )

    def set_neighbors(self, route_id: str, neighbors: list[str]) -> None:
        route = self.get(route_id)
        unique = [
            item
            for item in dict.fromkeys(neighbors)
            if item != route_id and item in self._routes
        ]
        limit = (
            self.config.topology.cross_route.max_neighbors_per_route
            if self.config is not None
            else len(unique)
        )
        route.neighbor_route_ids = unique[:limit]

    def neighbors(self, route_id: str, round_index: int | None = None) -> list[str]:
        route = self.get(route_id)
        active = {
            item.route_id
            for item in self.active_routes(round_index or 0)
            if item.route_id != route_id
        }
        return [item for item in route.neighbor_route_ids if item in active]

    def mark_cooling(self, route_id: str, until_round: int, reason: str) -> None:
        route = self.get(route_id)
        route.status = RouteStatus.COOLING
        route.cooldown_until_round = until_round
        self.cooling_reasons[route_id] = reason

    def merge_routes(self, source_route_id: str, target_route_id: str) -> None:
        if source_route_id == target_route_id:
            raise ValueError("a route cannot be merged into itself")
        source = self.get(source_route_id)
        target = self.get(target_route_id)
        source.status = RouteStatus.MERGED
        source.merged_into_route_id = target_route_id
        target.mechanism_signature = list(
            dict.fromkeys(target.mechanism_signature + source.mechanism_signature)
        )

    def active_routes(self, round_index: int) -> list[RouteDescriptor]:
        for route in self._routes.values():
            if (
                route.status == RouteStatus.COOLING
                and route.cooldown_until_round is not None
                and route.cooldown_until_round <= round_index
            ):
                route.status = RouteStatus.ACTIVE
                route.cooldown_until_round = None
        return [
            route
            for route in self._routes.values()
            if route.status == RouteStatus.ACTIVE
        ]

    def export_state(self) -> dict[str, Any]:
        return {
            "problem_hash": self.problem_hash,
            "routes": [route.model_dump(mode="json") for route in self.routes],
            "cooling_reasons": dict(self.cooling_reasons),
        }

    @classmethod
    def from_state(
        cls,
        state: dict[str, Any],
        config: SystemConfig | None = None,
    ) -> "RouteRegistry":
        registry = cls(
            config,
            problem_hash=str(state.get("problem_hash", "")),
            routes=[
                RouteDescriptor.model_validate(item) for item in state.get("routes", [])
            ],
        )
        registry.cooling_reasons = {
            str(key): str(value)
            for key, value in dict(state.get("cooling_reasons", {})).items()
        }
        return registry
