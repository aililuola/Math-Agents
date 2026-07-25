from __future__ import annotations

import re
from collections.abc import Sequence
from typing import Any

from ..config import CommonModeControlConfig
from ..proof_identity import normalize_text
from ..schemas import (
    ClaimStatus,
    MemoryTier,
    MessageEnvelope,
    ObligationKind,
    ProofObligation,
    RouteDescriptor,
    RouteStatus,
    StrategyCard,
    stable_hash,
)
from .models import CriticalAssumption


class CriticalAssumptionMatrix:
    """Measure route dependence without treating agreement as verification."""

    _STRIP_PREFIX = re.compile(
        r"^(?:we\s+)?(?:assume|suppose|using|use|hypothesis)\s*(?:that)?\s*[:,-]?\s*",
        re.IGNORECASE,
    )

    def __init__(self, config: CommonModeControlConfig | None = None) -> None:
        self.config = config or CommonModeControlConfig()
        self.usage: dict[str, dict[str, float]] = {}
        self.assumptions: dict[str, CriticalAssumption] = {}

    def build(
        self,
        routes: Sequence[RouteDescriptor],
        strategies: Sequence[StrategyCard],
        messages: Sequence[MessageEnvelope] = (),
        obligations: Sequence[ProofObligation] = (),
    ) -> dict[str, CriticalAssumption]:
        active_route_ids = {
            route.route_id
            for route in routes
            if route.status
            not in {
                RouteStatus.REFUTED,
                RouteStatus.MERGED,
                RouteStatus.ABANDONED,
            }
        }
        strategy_routes = {route.strategy_id: route.route_id for route in routes}
        raw: dict[str, dict[str, Any]] = {}

        def add(
            statement: str,
            *,
            route_id: str,
            source_subject_id: str,
            weight: float,
            verified: bool = False,
        ) -> None:
            normalized = self._normalize_assumption(statement)
            if not normalized or not route_id:
                return
            record = raw.setdefault(
                normalized,
                {
                    "sources": set(),
                    "routes": {},
                    "verified": False,
                },
            )
            record["sources"].add(source_subject_id)
            record["routes"][route_id] = max(
                weight, record["routes"].get(route_id, 0.0)
            )
            record["verified"] = bool(record["verified"] or verified)

        if self.config.include_strategy_prerequisites:
            for strategy in strategies:
                route_id = strategy_routes.get(strategy.strategy_id, "")
                for prerequisite in strategy.prerequisites:
                    add(
                        prerequisite,
                        route_id=route_id,
                        source_subject_id=strategy.strategy_id,
                        weight=0.9,
                    )
        if self.config.include_critical_claims:
            for strategy in strategies:
                route_id = strategy_routes.get(strategy.strategy_id, "")
                for claim in strategy.critical_claims:
                    add(
                        claim.statement,
                        route_id=route_id,
                        source_subject_id=claim.claim_id,
                        weight=1.0 if claim.necessity == "required" else 0.5,
                        verified=claim.status == "verified",
                    )
        for message in messages:
            for assumption in message.assumptions:
                add(
                    assumption,
                    route_id=message.source_route_id,
                    source_subject_id=message.message_id,
                    weight=0.75,
                )
            if (
                message.memory_tier == MemoryTier.FACT
                and message.verification_status == ClaimStatus.VERIFIED
            ):
                add(
                    message.normalized_statement,
                    route_id=message.source_route_id,
                    source_subject_id=message.message_id,
                    weight=0.0,
                    verified=True,
                )
        if self.config.include_unverified_dependencies:
            for obligation in obligations:
                for route_id in obligation.route_ids:
                    for assumption in obligation.assumptions:
                        add(
                            assumption,
                            route_id=route_id,
                            source_subject_id=obligation.obligation_id,
                            weight=0.8,
                            verified=obligation.kind == ObligationKind.MAIN_GOAL,
                        )

        denominator = max(1.0, float(len(active_route_ids)))
        assumptions: dict[str, CriticalAssumption] = {}
        usage: dict[str, dict[str, float]] = {
            route_id: {} for route_id in sorted(active_route_ids)
        }
        for normalized, record in sorted(raw.items()):
            assumption_id = f"assumption_{stable_hash(normalized)[:12]}"
            necessity_by_route = {
                route_id: float(weight)
                for route_id, weight in sorted(record["routes"].items())
            }
            risk = min(
                1.0,
                sum(
                    weight
                    for route_id, weight in necessity_by_route.items()
                    if route_id in active_route_ids
                )
                / denominator,
            )
            assumption = CriticalAssumption(
                assumption_id=assumption_id,
                normalized_statement=normalized,
                source_subject_ids=sorted(record["sources"]),
                route_ids=sorted(necessity_by_route),
                verification_status=(
                    ClaimStatus.VERIFIED if record["verified"] else ClaimStatus.PROPOSED
                ),
                necessity_by_route=necessity_by_route,
                common_mode_risk=risk,
            )
            assumptions[assumption_id] = assumption
            for route_id, weight in necessity_by_route.items():
                usage.setdefault(route_id, {})[assumption_id] = weight
        self.assumptions = assumptions
        self.usage = usage
        return dict(assumptions)

    def risks(
        self,
        assumptions: Sequence[CriticalAssumption] | None = None,
    ) -> list[CriticalAssumption]:
        values = assumptions or list(self.assumptions.values())
        return sorted(
            (
                item
                for item in values
                if item.verification_status != ClaimStatus.VERIFIED
                and len(item.route_ids) >= self.config.min_routes
                and item.common_mode_risk >= self.config.risk_threshold
            ),
            key=lambda item: (
                -item.common_mode_risk,
                -len(item.route_ids),
                item.assumption_id,
            ),
        )

    def challenger_task(self, assumption: CriticalAssumption) -> dict[str, Any]:
        task_id = f"challenger_{stable_hash(assumption.assumption_id)[:12]}"
        assumption.challenger_task_id = task_id
        return {
            "task_id": task_id,
            "assumption_id": assumption.assumption_id,
            "target_statement": assumption.normalized_statement,
            "route_ids": list(assumption.route_ids),
            "roles": [
                "counterexample_hunter",
                "meta_strategist",
                "reverse_goal_analyzer",
            ],
            "required_actions": [
                "seek an exact counterexample",
                "find a route that does not depend on the assumption",
                "find a weaker sufficient condition",
                "determine whether the assumption is actually necessary",
            ],
            "premise_eligible": False,
        }

    @classmethod
    def _normalize_assumption(cls, statement: str) -> str:
        value = normalize_text(statement).casefold().strip(" .;:")
        previous = ""
        while value != previous:
            previous = value
            value = cls._STRIP_PREFIX.sub("", value).strip(" .;:")
        return value
