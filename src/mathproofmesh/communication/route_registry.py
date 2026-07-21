from __future__ import annotations

import re
from typing import Any, Iterable

from ..config import SystemConfig
from ..schemas import (
    RouteDescriptor,
    RouteMember,
    RouteRole,
    RouteStatus,
    StrategyCard,
    stable_hash,
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
        self._strategy_index = {
            route.strategy_id: route.route_id for route in self._routes.values()
        }
        self._strategy_aliases: dict[str, str] = {}
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
        existing_route_id = self._strategy_index.get(strategy.strategy_id)
        if existing_route_id is not None:
            return self.get(existing_route_id)
        # An explicit route_id is authoritative during checkpoint migration and
        # deterministic component fixtures. Ordinary production registration
        # omits it and therefore receives semantic de-duplication.
        duplicate = self.find_semantic_duplicate(strategy) if route_id is None else None
        if duplicate is not None:
            self._strategy_aliases[strategy.strategy_id] = duplicate.route_id
            duplicate.mechanism_signature = list(
                dict.fromkeys(
                    [
                        *duplicate.mechanism_signature,
                        *strategy.tags,
                        strategy.title,
                        *strategy.expected_lemmas,
                    ]
                )
            )
            return duplicate
        identifier = route_id or (
            "route_" + stable_hash((self.problem_hash, strategy.strategy_id))[:20]
        )
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
            strategy_signature=self.strategy_signature(strategy),
            shared_assumptions=list(dict.fromkeys(strategy.prerequisites)),
            inspiration_proposal_id=strategy.inspiration_proposal_id,
        )
        self._routes[identifier] = descriptor
        self._strategy_index[strategy.strategy_id] = identifier
        self.recompute_neighbors()
        return descriptor

    @staticmethod
    def _tokens(text: str) -> set[str]:
        return set(re.findall(r"[a-z0-9_]+|[\u4e00-\u9fff]", text.casefold()))

    @classmethod
    def strategy_signature(cls, strategy: StrategyCard) -> str:
        return stable_hash(
            {
                "title": " ".join(sorted(cls._tokens(strategy.title))),
                "core": " ".join(sorted(cls._tokens(strategy.core_idea))),
                "tags": sorted(set(item.casefold() for item in strategy.tags)),
                "prerequisites": sorted(
                    set(item.casefold() for item in strategy.prerequisites)
                ),
            }
        )

    def find_semantic_duplicate(self, strategy: StrategyCard) -> RouteDescriptor | None:
        signature = self.strategy_signature(strategy)
        exact = next(
            (
                route
                for route in self._routes.values()
                if route.strategy_signature and route.strategy_signature == signature
            ),
            None,
        )
        if exact is not None:
            return exact
        threshold = (
            self.config.topology.broker.duplicate_strategy_threshold
            if self.config is not None
            else 0.90
        )
        candidate_tokens = self._tokens(
            " ".join(
                [
                    strategy.title,
                    strategy.core_idea,
                    strategy.falsification_test,
                    *strategy.tags,
                    *strategy.prerequisites,
                ]
            )
        )
        if not candidate_tokens:
            return None
        best: tuple[float, RouteDescriptor] | None = None
        for route in self._routes.values():
            if route.status == RouteStatus.MERGED:
                continue
            route_tokens = self._tokens(" ".join(route.mechanism_signature))
            score = len(candidate_tokens & route_tokens) / max(
                1, len(candidate_tokens | route_tokens)
            )
            if best is None or score > best[0]:
                best = (score, route)
        return best[1] if best is not None and best[0] >= threshold else None

    def route_for_strategy(self, strategy_id: str) -> RouteDescriptor | None:
        route_id = self._strategy_index.get(strategy_id) or self._strategy_aliases.get(
            strategy_id
        )
        return self._routes.get(route_id) if route_id is not None else None

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

    def recompute_neighbors(self) -> None:
        active = [
            route
            for route in self._routes.values()
            if route.status == RouteStatus.ACTIVE
        ]
        limit = (
            self.config.topology.cross_route.max_neighbors_per_route
            if self.config is not None
            else max(0, len(active) - 1)
        )
        for route in self._routes.values():
            if route.status != RouteStatus.ACTIVE:
                route.neighbor_route_ids = []
                continue
            source = self._tokens(" ".join(route.mechanism_signature))
            scored: list[tuple[float, str]] = []
            for candidate in active:
                if candidate.route_id == route.route_id:
                    continue
                target = self._tokens(" ".join(candidate.mechanism_signature))
                similarity = len(source & target) / max(1, len(source | target))
                # Sparse exchange benefits from some overlap, while deterministic
                # route_id tie-breaking preserves reproducibility.
                scored.append((similarity, candidate.route_id))
            scored.sort(key=lambda item: (-item[0], item[1]))
            route.neighbor_route_ids = [item[1] for item in scored[:limit]]

    def neighbors(self, route_id: str, round_index: int | None = None) -> list[str]:
        route = self.get(route_id)
        active = {
            item.route_id
            for item in self.active_routes(round_index or 0)
            if item.route_id != route_id
        }
        return [item for item in route.neighbor_route_ids if item in active]

    def mark_cooling(
        self,
        route_id: str,
        until_round: int,
        reason: str,
        *,
        requires_revision: bool = False,
    ) -> None:
        route = self.get(route_id)
        route.status = RouteStatus.COOLING
        route.cooldown_until_round = until_round
        route.requires_revision = requires_revision
        route.revision_summary = None
        self.cooling_reasons[route_id] = reason
        self.recompute_neighbors()

    def reactivate(self, route_id: str, *, revision_summary: str) -> None:
        route = self.get(route_id)
        if route.requires_revision and not revision_summary.strip():
            raise ValueError(
                "a counterexample-dependent route requires an explicit revision"
            )
        route.status = RouteStatus.ACTIVE
        route.cooldown_until_round = None
        route.requires_revision = False
        route.revision_summary = revision_summary.strip()
        self.recompute_neighbors()

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
        for strategy_id, route_id in list(self._strategy_aliases.items()):
            if route_id == source_route_id:
                self._strategy_aliases[strategy_id] = target_route_id
        self.recompute_neighbors()

    def active_routes(self, round_index: int) -> list[RouteDescriptor]:
        for route in self._routes.values():
            if (
                route.status == RouteStatus.COOLING
                and route.cooldown_until_round is not None
                and route.cooldown_until_round <= round_index
                and not route.requires_revision
            ):
                route.status = RouteStatus.ACTIVE
                route.cooldown_until_round = None
        self.recompute_neighbors()
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
            "strategy_aliases": dict(self._strategy_aliases),
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
        registry._strategy_aliases = {
            str(key): str(value)
            for key, value in dict(state.get("strategy_aliases", {})).items()
        }
        return registry
