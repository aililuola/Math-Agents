from __future__ import annotations

from collections import Counter
from typing import Any

from ..config import InspirationConfig
from ..schemas import (
    InspirationMechanism,
    MetaStrategyDecision,
    new_id,
)
from .trigger_policy import InspirationSnapshot


class PersistentMetaStrategist:
    """Persistent policy over observable progress signals, never a fact author."""

    def __init__(self, config: InspirationConfig) -> None:
        self.config = config
        self.history: list[MetaStrategyDecision] = []
        self.cooldowns: dict[InspirationMechanism, int] = {}

    def decide(self, snapshot: InspirationSnapshot) -> MetaStrategyDecision:
        metrics: dict[str, float | int | str | bool] = {
            "verified_fact_gain_recent": snapshot.verified_fact_gain_recent,
            "proof_debt_reduction_recent": snapshot.proof_debt_reduction_recent,
            "route_redundancy": snapshot.route_redundancy,
            "failed_route_count": len(snapshot.failed_route_ids),
            "active_route_count": len(snapshot.active_route_ids),
            "shared_bottleneck_count": len(snapshot.shared_bottleneck_ids),
            "unresolved_conflict_count": len(snapshot.unresolved_conflict_ids),
            "remaining_calls": snapshot.remaining_calls,
            "finalization_reserve_calls": snapshot.finalization_reserve_calls,
        }
        repeated_errors = Counter(snapshot.first_error_fingerprints)
        mechanism: InspirationMechanism | None = None
        action = "continue_current_mechanism"
        reason = "verified progress remains above the intervention threshold"
        if snapshot.final_repair_failed:
            action = "rewrite_plan"
            mechanism = InspirationMechanism.META_REPLAN
            reason = "final repair failed; blind plan rewrite is required"
        elif snapshot.shared_bottleneck_ids:
            action = "rewrite_plan"
            mechanism = InspirationMechanism.REVERSE_GOAL_ANALYSIS
            reason = "shared high-value obligation should be isolated as a bridge"
        elif snapshot.route_redundancy >= self.config.route_redundancy_trigger:
            action = "switch_representation"
            mechanism = InspirationMechanism.REPRESENTATION_SWITCH
            reason = "route mechanisms are redundant despite separate wording"
        elif (
            repeated_errors
            and max(repeated_errors.values()) >= self.config.repeated_error_threshold
        ):
            action = "invent_auxiliary_construction"
            mechanism = InspirationMechanism.AUXILIARY_CONSTRUCTION
            reason = "repeated first-error fingerprint indicates a missing object"
        elif (
            snapshot.proof_debt_by_route
            and snapshot.proof_debt_reduction_recent
            < self.config.proof_debt_min_reduction
        ):
            action = "rewrite_plan"
            mechanism = InspirationMechanism.META_REPLAN
            reason = "proof debt is not decreasing"
        elif snapshot.active_route_ids and set(snapshot.active_route_ids) <= set(
            snapshot.failed_route_ids
        ):
            action = "surprise_exploration"
            mechanism = InspirationMechanism.SURPRISE_EXPLORATION
            reason = "all current mechanisms failed independent review"

        if mechanism is not None and self._is_cooled(mechanism, snapshot.round_index):
            alternatives = [
                InspirationMechanism.REPRESENTATION_SWITCH,
                InspirationMechanism.STRUCTURAL_ANALOGY,
                InspirationMechanism.AUXILIARY_CONSTRUCTION,
                InspirationMechanism.META_REPLAN,
            ]
            replacement = next(
                (
                    item
                    for item in alternatives
                    if item != mechanism
                    and not self._is_cooled(item, snapshot.round_index)
                ),
                None,
            )
            if replacement is None:
                action = "continue_current_mechanism"
                reason = "all mechanism-changing actions are cooling down"
                mechanism = None
            else:
                mechanism = replacement
                action = {
                    InspirationMechanism.REPRESENTATION_SWITCH: "switch_representation",
                    InspirationMechanism.STRUCTURAL_ANALOGY: "search_analogy",
                    InspirationMechanism.AUXILIARY_CONSTRUCTION: "invent_auxiliary_construction",
                    InspirationMechanism.META_REPLAN: "rewrite_plan",
                }[replacement]
                reason += "; chose a non-cooled alternative mechanism"

        decision = MetaStrategyDecision(
            decision_id=new_id("meta"),
            round_index=snapshot.round_index,
            action=action,  # type: ignore[arg-type]
            affected_route_ids=snapshot.active_route_ids,
            selected_mechanism=mechanism,
            observable_metrics=metrics,
            reason=reason,
            estimated_calls=0 if mechanism is None else 1,
        )
        self.history.append(decision)
        return decision

    def cool(
        self,
        mechanism: InspirationMechanism,
        *,
        current_round: int,
        rounds: int,
    ) -> None:
        self.cooldowns[mechanism] = current_round + rounds

    def _is_cooled(self, mechanism: InspirationMechanism, current_round: int) -> bool:
        return self.cooldowns.get(mechanism, -1) > current_round

    def export_state(self) -> dict[str, Any]:
        return {
            "history": [item.model_dump(mode="json") for item in self.history],
            "cooldowns": {
                mechanism.value: round_index
                for mechanism, round_index in self.cooldowns.items()
            },
        }

    @classmethod
    def from_state(
        cls, state: dict[str, Any], *, config: InspirationConfig
    ) -> "PersistentMetaStrategist":
        strategist = cls(config)
        strategist.history = [
            MetaStrategyDecision.model_validate(item)
            for item in state.get("history", [])
        ]
        strategist.cooldowns = {
            InspirationMechanism(key): int(value)
            for key, value in dict(state.get("cooldowns", {})).items()
        }
        return strategist
