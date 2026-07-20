from __future__ import annotations

from typing import Any

from ..config import InspirationConfig
from ..schemas import InspirationProposal, SurpriseBudgetState


class SurpriseBudgetExplorer:
    """A protected novelty budget that cannot consume finalization reserve."""

    def __init__(
        self,
        config: InspirationConfig,
        *,
        max_total_calls: int,
        finalization_reserve_calls: int,
        state: SurpriseBudgetState | None = None,
    ) -> None:
        self.config = config
        exploratory_pool = max(0, max_total_calls - finalization_reserve_calls)
        calculated = round(exploratory_pool * config.surprise_budget_fraction)
        total = min(
            config.surprise_budget_max_calls,
            max(config.surprise_budget_min_calls, calculated),
            exploratory_pool,
        )
        self.state = state or SurpriseBudgetState(
            total_calls=total,
            finalization_reserve_calls=finalization_reserve_calls,
        )

    def can_explore(
        self,
        *,
        current_round: int,
        remaining_calls: int,
        current_path_count: int,
        max_paths: int,
        estimated_calls: int = 1,
    ) -> tuple[bool, str]:
        if not self.config.surprise_exploration:
            return False, "surprise exploration is disabled"
        if (
            self.state.cooldown_until_round is not None
            and current_round < self.state.cooldown_until_round
        ):
            return False, "surprise exploration is cooling down"
        if current_path_count >= max_paths:
            return False, "max_paths has been reached"
        if estimated_calls > self.state.remaining_calls:
            return False, "surprise budget is exhausted"
        if self.config.protect_finalization_reserve and (
            remaining_calls - estimated_calls < self.state.finalization_reserve_calls
        ):
            return False, "finalization reserve is protected"
        return True, "admitted"

    def admit(
        self,
        proposal: InspirationProposal,
        *,
        current_round: int,
        remaining_calls: int,
        current_path_count: int,
        max_paths: int,
    ) -> tuple[bool, str]:
        if proposal.novelty_score < self.config.novelty_threshold:
            self.reject(current_round=current_round)
            return False, "proposal is below the mechanism novelty threshold"
        allowed, reason = self.can_explore(
            current_round=current_round,
            remaining_calls=remaining_calls,
            current_path_count=current_path_count,
            max_paths=max_paths,
            estimated_calls=max(1, proposal.estimated_cost),
        )
        if not allowed:
            return False, reason
        self.state.used_calls += max(1, proposal.estimated_cost)
        self.state.rejection_streak = 0
        return True, "admitted"

    def reject(self, *, current_round: int) -> None:
        self.state.rejection_streak += 1
        if (
            self.state.rejection_streak
            >= self.config.max_consecutive_surprise_rejections
        ):
            self.state.cooldown_until_round = (
                current_round + self.config.surprise_cooldown_rounds
            )
            self.state.rejection_streak = 0

    def export_state(self) -> dict[str, Any]:
        return self.state.model_dump(mode="json")

    @classmethod
    def from_state(
        cls,
        state: dict[str, Any],
        *,
        config: InspirationConfig,
        max_total_calls: int,
        finalization_reserve_calls: int,
    ) -> "SurpriseBudgetExplorer":
        return cls(
            config,
            max_total_calls=max_total_calls,
            finalization_reserve_calls=finalization_reserve_calls,
            state=SurpriseBudgetState.model_validate(state),
        )
