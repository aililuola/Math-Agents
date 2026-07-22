from __future__ import annotations

import math
from collections import defaultdict
from typing import Any, Iterable

from ..config import InspirationConfig
from ..schemas import (
    InspirationMechanism,
    InspirationOutcome,
    InspirationProposal,
    InspirationTrigger,
    ObligationKind,
)
from .trigger_policy import InspirationSnapshot, enabled_schedulable_mechanisms


class InspirationOutcomeLedger:
    """Persistent causal bookkeeping used only to schedule future exploration."""

    def __init__(self, config: InspirationConfig) -> None:
        self.config = config
        self.outcomes: dict[str, InspirationOutcome] = {}
        self.historical_outcomes: dict[str, InspirationOutcome] = {}

    def register(
        self,
        proposal: InspirationProposal,
        *,
        snapshot: InspirationSnapshot,
        trigger: InspirationTrigger,
        obligation_kinds: Iterable[ObligationKind],
        proof_debt_before: float,
        credit_route_ids: Iterable[str] = (),
        credit_obligation_ids: Iterable[str] = (),
    ) -> InspirationOutcome:
        existing = self.outcomes.get(proposal.proposal_id)
        if existing is not None:
            return existing
        outcome = InspirationOutcome(
            proposal_id=proposal.proposal_id,
            problem_hash=snapshot.problem_hash,
            task_id=proposal.task_id,
            mechanism=proposal.mechanism,
            domain=snapshot.domain,
            trigger_type=trigger.trigger_type,
            obligation_kinds=list(dict.fromkeys(obligation_kinds)),
            round_created=snapshot.round_index,
            proof_debt_before=max(0.0, proof_debt_before),
            credit_route_ids=list(dict.fromkeys(credit_route_ids)),
            credit_obligation_ids=list(dict.fromkeys(credit_obligation_ids)),
        )
        self.outcomes[proposal.proposal_id] = outcome
        return outcome

    def record_usage(
        self,
        proposal_id: str,
        *,
        phase: str,
        calls: int = 1,
        tokens: int = 0,
    ) -> None:
        outcome = self.outcomes.get(proposal_id)
        if outcome is None:
            return
        update: dict[str, Any] = {"tokens": outcome.tokens + max(0, tokens)}
        if phase == "proposer":
            update["proposer_calls"] = outcome.proposer_calls + max(0, calls)
        elif phase in {"referee", "skeptic"}:
            update["review_calls"] = outcome.review_calls + max(0, calls)
        elif phase == "route":
            update["route_calls"] = outcome.route_calls + max(0, calls)
        self.outcomes[proposal_id] = outcome.model_copy(update=update)
        self._recompute(proposal_id)

    def record_materialization(
        self,
        proposal_id: str,
        *,
        action: str,
        refuted: bool,
    ) -> None:
        outcome = self.outcomes.get(proposal_id)
        if outcome is None:
            return
        self.outcomes[proposal_id] = outcome.model_copy(
            update={
                "materialized": action not in {"shadow_only", "rejected"},
                "materialization_action": action,
                "refuted": refuted,
            }
        )
        self._recompute(proposal_id)

    def record_verified_gain(
        self,
        proposal_id: str,
        *,
        round_index: int,
        proof_debt_after: float,
        obligations_closed: Iterable[str],
    ) -> InspirationOutcome | None:
        outcome = self.outcomes.get(proposal_id)
        if outcome is None:
            return None
        closed = list(dict.fromkeys([*outcome.obligations_closed, *obligations_closed]))
        first_gain = outcome.rounds_to_first_gain
        if first_gain is None:
            first_gain = max(0, round_index - outcome.round_created)
        after = max(0.0, proof_debt_after)
        self.outcomes[proposal_id] = outcome.model_copy(
            update={
                "verified_fact_gain": outcome.verified_fact_gain + 1,
                "proof_debt_after": after,
                "proof_debt_delta": after - outcome.proof_debt_before,
                "obligations_closed": closed,
                "rounds_to_first_gain": first_gain,
            }
        )
        self._recompute(proposal_id)
        return self.outcomes[proposal_id]

    def mark_final_citation(self, proposal_id: str) -> None:
        outcome = self.outcomes.get(proposal_id)
        if outcome is None or outcome.cited_by_final_proof:
            return
        self.outcomes[proposal_id] = outcome.model_copy(
            update={"cited_by_final_proof": True}
        )
        self._recompute(proposal_id)

    def selection_profiles(
        self,
        triggers: Iterable[InspirationTrigger],
        snapshot: InspirationSnapshot,
    ) -> dict[str, dict[str, float | int | bool]]:
        if not self.config.adaptive_mechanism_selection:
            return {}
        schedulable = enabled_schedulable_mechanisms(self.config)
        if not schedulable:
            return {}
        result: dict[str, dict[str, float | int | bool]] = {}
        for trigger in triggers:
            matching = [
                item
                for item in [
                    *self.historical_outcomes.values(),
                    *self.outcomes.values(),
                ]
                if item.domain in {snapshot.domain, "unknown"}
                and item.trigger_type == trigger.trigger_type
                and item.mechanism in schedulable
                and self._obligation_context_matches(item, snapshot)
            ]
            grouped: dict[InspirationMechanism, list[InspirationOutcome]] = defaultdict(
                list
            )
            for item in matching:
                grouped[item.mechanism].append(item)
            total = len(matching)
            minimum_observed = min(
                (len(grouped.get(mechanism, [])) for mechanism in schedulable),
                default=0,
            )
            interval = (
                max(1, round(1.0 / self.config.adaptive_min_exploration_rate))
                if self.config.adaptive_min_exploration_rate > 0
                else 0
            )
            scheduled_exploration = bool(interval and total % interval == 0)
            for mechanism in schedulable:
                records = grouped.get(mechanism, [])
                observations = len(records)
                mean_reward = (
                    sum(item.reward for item in records) / observations
                    if observations
                    else 0.0
                )
                bonus = self.config.adaptive_ucb_weight * math.sqrt(
                    math.log(total + 2.0) / (observations + 1.0)
                )
                key = self.profile_key(trigger.trigger_type.value, mechanism.value)
                result[key] = {
                    "observations": observations,
                    "mean_reward": mean_reward,
                    "ucb_score": mean_reward + bonus,
                    "force_exploration": (
                        observations < self.config.adaptive_min_observations
                        or (scheduled_exploration and observations == minimum_observed)
                    ),
                }
        return result

    @staticmethod
    def profile_key(trigger_type: str, mechanism: str) -> str:
        return f"{trigger_type}:{mechanism}"

    def export_state(self) -> dict[str, Any]:
        return {
            key: value.model_dump(mode="json") for key, value in self.outcomes.items()
        }

    def restore_state(self, state: dict[str, Any]) -> None:
        self.outcomes = {
            str(key): InspirationOutcome.model_validate(value)
            for key, value in state.items()
        }

    def load_historical(self, outcomes: Iterable[InspirationOutcome]) -> None:
        for outcome in outcomes:
            key = f"{outcome.problem_hash}:{outcome.proposal_id}"
            self.historical_outcomes[key] = outcome

    def _recompute(self, proposal_id: str) -> None:
        outcome = self.outcomes[proposal_id]
        calls = outcome.proposer_calls + outcome.review_calls + outcome.route_calls
        reward = (
            self.config.adaptive_reward_fact_weight * outcome.verified_fact_gain
            + self.config.adaptive_reward_debt_weight
            * max(0.0, -outcome.proof_debt_delta)
            + self.config.adaptive_reward_obligation_weight
            * len(outcome.obligations_closed)
            + self.config.adaptive_reward_final_citation_weight
            * int(outcome.cited_by_final_proof)
            - self.config.adaptive_reward_call_cost * calls
            - self.config.adaptive_reward_token_cost_per_100k
            * (outcome.tokens / 100_000.0)
            - self.config.adaptive_reward_refutation_cost * int(outcome.refuted)
        )
        self.outcomes[proposal_id] = outcome.model_copy(update={"reward": reward})

    @staticmethod
    def _obligation_context_matches(
        outcome: InspirationOutcome, snapshot: InspirationSnapshot
    ) -> bool:
        requested = {
            snapshot.obligation_kinds[item]
            for item in snapshot.open_obligation_ids
            if item in snapshot.obligation_kinds
        }
        observed = {item.value for item in outcome.obligation_kinds}
        return not requested or not observed or bool(requested & observed)


__all__ = ["InspirationOutcomeLedger"]
