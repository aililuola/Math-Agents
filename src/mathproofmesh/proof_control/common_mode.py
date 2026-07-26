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
from .domains import classify_assumption_domain
from .models import (
    AssumptionChallengerTask,
    AssumptionDomain,
    AssumptionDomainRecord,
    AssumptionFamily,
    CriticalAssumption,
)
from .semantic_quality import ObligationSemanticGate


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
        self.families: dict[str, AssumptionFamily] = {}
        self.domain_records: dict[str, AssumptionDomainRecord] = {}
        self.semantic_gate = ObligationSemanticGate()

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
            require_truth_apt: bool = False,
        ) -> None:
            normalized = self._normalize_assumption(statement)
            if not normalized or not route_id:
                return
            domain_record = classify_assumption_domain(statement)
            self.domain_records[
                f"{source_subject_id}:{stable_hash(normalized)[:12]}"
            ] = domain_record
            if domain_record.domain != AssumptionDomain.MATHEMATICAL:
                return
            if (
                require_truth_apt
                and not self.semantic_gate.assess_statement(
                    statement,
                    source_kind="mathematical",
                ).truth_apt
            ):
                return
            record = raw.setdefault(
                normalized,
                {
                    "sources": set(),
                    "routes": {},
                    "verified": False,
                    "semantic_tags": self._semantic_tags(normalized),
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
                        require_truth_apt=True,
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
                domain=AssumptionDomain.MATHEMATICAL,
                semantic_tags=sorted(record["semantic_tags"]),
            )
            assumptions[assumption_id] = assumption
            for route_id, weight in necessity_by_route.items():
                usage.setdefault(route_id, {})[assumption_id] = weight
        self.assumptions = assumptions
        self.usage = usage
        self.families = self._build_families(
            assumptions,
            active_route_ids=active_route_ids,
        )
        for family in self.families.values():
            for assumption_id in family.member_assumption_ids:
                assumption = assumptions[assumption_id]
                assumption.family_id = family.family_id
                assumption.common_mode_risk = family.common_mode_risk
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

    def semantic_family_key(
        self,
        statement: str,
        *,
        scope_signature_id: str | None,
        mechanism_chain: Sequence[str] = (),
        proof_graph_neighborhood: Sequence[str] = (),
    ) -> str:
        identity = {
            "semantic_tags": sorted(
                self._semantic_tags(self._normalize_assumption(statement))
            ),
            "scope_signature_id": scope_signature_id,
            "mechanism_chain": [
                normalize_text(item).casefold() for item in mechanism_chain
            ],
            "proof_graph_neighborhood": sorted(set(proof_graph_neighborhood)),
        }
        return f"assumption_family_key_{stable_hash(identity)[:20]}"

    def risk_families(self) -> list[AssumptionFamily]:
        return sorted(
            (
                family
                for family in self.families.values()
                if len(family.route_ids) >= self.config.min_routes
                and family.common_mode_risk >= self.config.risk_threshold
                and any(
                    self.assumptions[assumption_id].verification_status
                    != ClaimStatus.VERIFIED
                    for assumption_id in family.member_assumption_ids
                )
            ),
            key=lambda item: (
                -item.common_mode_risk,
                -len(item.route_ids),
                item.family_id,
            ),
        )

    @staticmethod
    def challenger_for_family(
        family: AssumptionFamily,
    ) -> AssumptionChallengerTask:
        return AssumptionChallengerTask(
            task_id=f"challenger_{stable_hash(family.family_id)[:12]}",
            family_id=family.family_id,
            assumption_ids=list(family.member_assumption_ids),
            target_statement=family.canonical_statement,
            route_ids=list(family.route_ids),
            required_actions=[
                "seek an exact counterexample",
                "show that the assumption is not necessary",
                "find a weaker sufficient condition",
                "construct a route independent of the assumption family",
            ],
            premise_eligible=False,
        )

    def _build_families(
        self,
        assumptions: dict[str, CriticalAssumption],
        *,
        active_route_ids: set[str],
    ) -> dict[str, AssumptionFamily]:
        groups: list[list[CriticalAssumption]] = []
        for assumption in sorted(
            assumptions.values(), key=lambda item: item.assumption_id
        ):
            tags = set(assumption.semantic_tags)
            group = next(
                (
                    candidate
                    for candidate in groups
                    if self._tag_similarity(
                        tags,
                        {tag for member in candidate for tag in member.semantic_tags},
                    )
                    >= 0.6
                ),
                None,
            )
            if group is None:
                groups.append([assumption])
            else:
                group.append(assumption)

        denominator = max(1.0, float(len(active_route_ids)))
        families: dict[str, AssumptionFamily] = {}
        for group in groups:
            member_ids = sorted(item.assumption_id for item in group)
            route_weights: dict[str, float] = {}
            for assumption in group:
                for route_id, weight in assumption.necessity_by_route.items():
                    route_weights[route_id] = max(
                        route_weights.get(route_id, 0.0),
                        float(weight),
                    )
            semantic_tags = sorted(
                {tag for item in group for tag in item.semantic_tags}
            )
            family_id = (
                "assumption_family_"
                + stable_hash(
                    {
                        "members": member_ids,
                        "semantic_tags": semantic_tags,
                    }
                )[:16]
            )
            canonical = min(
                group,
                key=lambda item: (
                    len(item.normalized_statement),
                    item.normalized_statement,
                ),
            )
            family = AssumptionFamily(
                family_id=family_id,
                canonical_statement=canonical.normalized_statement,
                member_assumption_ids=member_ids,
                route_ids=sorted(route_weights),
                semantic_tags=semantic_tags,
                common_mode_risk=min(
                    1.0,
                    sum(
                        weight
                        for route_id, weight in route_weights.items()
                        if route_id in active_route_ids
                    )
                    / denominator,
                ),
                normalization_confidence=(0.95 if len(group) == 1 else 0.85),
            )
            families[family.family_id] = family
        return families

    @staticmethod
    def _tag_similarity(left: set[str], right: set[str]) -> float:
        union = left | right
        return len(left & right) / len(union) if union else 0.0

    @staticmethod
    def _semantic_tags(statement: str) -> set[str]:
        aliases = {
            "admits": "admission",
            "each": "universal",
            "every": "universal",
            "has": "admission",
            "preserve": "preservation",
            "preserves": "preservation",
            "preserved": "preservation",
            "invariant": "preservation",
            "invariance": "preservation",
            "one": "unique",
            "transform": "transformation",
            "transforms": "transformation",
        }
        stop = {
            "a",
            "an",
            "and",
            "are",
            "is",
            "of",
            "remains",
            "the",
            "under",
        }
        return {
            aliases.get(token, token)
            for token in re.findall(r"[a-z][a-z0-9_]*", statement.casefold())
            if token not in stop
        }

    @classmethod
    def _normalize_assumption(cls, statement: str) -> str:
        value = normalize_text(statement).casefold().strip(" .;:")
        previous = ""
        while value != previous:
            previous = value
            value = cls._STRIP_PREFIX.sub("", value).strip(" .;:")
        return value
