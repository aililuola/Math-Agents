from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass
from typing import Iterable, Mapping

from .agents import CallLedger
from .config import SystemConfig
from .schemas import (
    ActionKind,
    BudgetAction,
    BudgetDecision,
    FailureLevel,
    PathStats,
    ProofAttempt,
    StrategyCard,
    VerificationReport,
    VerificationStage,
    VerificationVerdict,
)
from .topology import jaccard_similarity, strategy_text


_FAILURE_ORDER = {
    FailureLevel.NONE: 0,
    FailureLevel.EXECUTION: 1,
    FailureLevel.PLAN: 2,
    FailureLevel.STRATEGY: 3,
}


@dataclass(frozen=True, slots=True)
class _AttemptEvidence:
    verdict: VerificationVerdict | None
    failure_level: FailureLevel
    confidence: float
    verification_score: float
    uncertainty: float
    structurally_valid: bool | None


class AdaptiveBudgetManager:
    """Evidence-aware breadth/depth/verification controller."""

    def __init__(self, config: SystemConfig) -> None:
        self.config = config

    def build_path_stats(
        self,
        strategies: list[StrategyCard],
        attempts: list[ProofAttempt],
        reports: list[VerificationReport],
        aggregate_reports: Mapping[str, VerificationReport] | None = None,
        graph_signals: Mapping[str, Mapping[str, object]] | None = None,
    ) -> list[PathStats]:
        attempts_by_strategy: dict[str, list[ProofAttempt]] = defaultdict(list)
        for attempt in attempts:
            attempts_by_strategy[attempt.strategy_id].append(attempt)

        reports_by_target: dict[str, list[VerificationReport]] = defaultdict(list)
        for report in reports:
            reports_by_target[report.target_id].append(report)
        aggregate_reports = aggregate_reports or self._infer_aggregates(reports)

        stats: list[PathStats] = []
        for strategy in strategies:
            path_attempts = sorted(
                attempts_by_strategy.get(strategy.strategy_id, []),
                key=lambda item: item.round_index,
            )
            latest = path_attempts[-1] if path_attempts else None
            if latest is None:
                stats.append(
                    PathStats(
                        strategy_id=strategy.strategy_id,
                        novelty=self._novelty(strategy, strategies),
                    )
                )
                continue

            snapshots = [
                self._evidence_for_attempt(
                    attempt.attempt_id,
                    reports_by_target,
                    aggregate_reports,
                )
                for attempt in path_attempts
            ]
            raw_progress = [
                self._raw_attempt_progress(attempt) for attempt in path_attempts
            ]
            backed_progress = [
                self._evidence_backed_progress(raw, evidence)
                for raw, evidence in zip(raw_progress, snapshots)
            ]

            latest_evidence = snapshots[-1]
            progress = backed_progress[-1]
            gap_reduction = 0.0
            marginal_progress = 0.0
            if len(path_attempts) >= 2:
                previous = path_attempts[-2]
                gap_reduction = self._gap_reduction(
                    len(previous.unresolved_gaps),
                    len(latest.unresolved_gaps),
                )
                verification_delta = (
                    latest_evidence.verification_score
                    - snapshots[-2].verification_score
                )
                marginal_progress = self._clamp_signed(
                    0.55 * (progress - backed_progress[-2])
                    + 0.30 * gap_reduction
                    + 0.15 * verification_delta
                )
                if (
                    latest_evidence.verdict != VerificationVerdict.PASS
                    and gap_reduction <= 0.0
                    and verification_delta
                    < self.config.scheduler.meaningful_progress_threshold
                ):
                    marginal_progress = min(marginal_progress, 0.0)

            stagnation = self._stagnation_rounds(
                path_attempts,
                snapshots,
                backed_progress,
            )
            consecutive_failures = self._consecutive_failures(snapshots)

            stats.append(
                PathStats(
                    strategy_id=strategy.strategy_id,
                    attempt_id=latest.attempt_id,
                    complete=latest.status.value == "complete",
                    progress=progress,
                    marginal_progress=marginal_progress,
                    gap_reduction=gap_reduction,
                    novelty=self._novelty(strategy, strategies),
                    uncertainty=latest_evidence.uncertainty,
                    verification_score=latest_evidence.verification_score,
                    latest_verdict=latest_evidence.verdict,
                    failure_level=latest_evidence.failure_level,
                    failure_confidence=latest_evidence.confidence
                    if latest_evidence.verdict == VerificationVerdict.FAIL
                    else 0.0,
                    consecutive_failures=consecutive_failures,
                    failed_repair_attempts=max(0, consecutive_failures - 1),
                    unresolved_gap_count=len(latest.unresolved_gaps),
                    stagnation_rounds=stagnation,
                    last_round_index=latest.round_index,
                    tokens_spent=sum(
                        attempt.usage.total_tokens for attempt in path_attempts
                    ),
                    structurally_valid=latest_evidence.structurally_valid,
                )
            )
        graph_signals = graph_signals or {}
        graph_fields = {
            "proof_debt",
            "proof_debt_reduction",
            "verified_fact_gain",
            "shared_obligation_count",
            "high_centrality_obligation_count",
            "contradiction_count",
            "counterexample_count",
            "message_utility",
            "route_redundancy",
            "bridge_opportunity",
            "negative_memory_hits",
            "inspiration_trigger_count",
            "novelty_score",
            "representation_diversity",
            "analogy_opportunity",
            "construction_opportunity",
            "surprise_budget_remaining",
        }
        for stat in stats:
            payload = graph_signals.get(stat.strategy_id, {})
            for field_name in graph_fields:
                if field_name in payload:
                    setattr(stat, field_name, payload[field_name])
        return stats

    @staticmethod
    def _infer_aggregates(
        reports: Iterable[VerificationReport],
    ) -> dict[str, VerificationReport]:
        aggregates: dict[str, VerificationReport] = {}
        for report in reports:
            if (
                report.agent_id == "system-aggregate"
                and report.target_type == "attempt"
            ):
                aggregates[report.target_id] = report
        return aggregates

    def _evidence_for_attempt(
        self,
        attempt_id: str,
        reports_by_target: Mapping[str, list[VerificationReport]],
        aggregate_reports: Mapping[str, VerificationReport],
    ) -> _AttemptEvidence:
        target_reports = reports_by_target.get(attempt_id, [])
        aggregate = aggregate_reports.get(attempt_id)
        if aggregate is None:
            aggregate_candidates = [
                report
                for report in target_reports
                if report.agent_id == "system-aggregate"
                and report.stage
                in {VerificationStage.DETAILED, VerificationStage.FINAL}
            ]
            aggregate = aggregate_candidates[-1] if aggregate_candidates else None

        structural_reports = [
            report
            for report in target_reports
            if report.stage == VerificationStage.STRUCTURAL
        ]
        structurally_valid: bool | None = None
        if structural_reports:
            if any(
                report.verdict == VerificationVerdict.FAIL
                or not report.problem_integrity_ok
                for report in structural_reports
            ):
                structurally_valid = False
            elif all(
                report.verdict == VerificationVerdict.PASS
                for report in structural_reports
            ):
                structurally_valid = True

        verdict = aggregate.verdict if aggregate is not None else None
        confidence = aggregate.confidence if aggregate is not None else 0.0
        failure_level = (
            aggregate.failure_level
            if aggregate is not None
            else self._strongest_failure_level(target_reports)
        )
        verification_score = self._verification_score(verdict, confidence)
        uncertainty = self._verdict_uncertainty(verdict, confidence)
        return _AttemptEvidence(
            verdict=verdict,
            failure_level=failure_level,
            confidence=confidence,
            verification_score=verification_score,
            uncertainty=uncertainty,
            structurally_valid=structurally_valid,
        )

    @staticmethod
    def _strongest_failure_level(
        reports: Iterable[VerificationReport],
    ) -> FailureLevel:
        levels = [
            report.failure_level
            for report in reports
            if report.verdict == VerificationVerdict.FAIL
        ]
        return max(
            levels, key=lambda level: _FAILURE_ORDER[level], default=FailureLevel.NONE
        )

    @staticmethod
    def _verification_score(
        verdict: VerificationVerdict | None,
        confidence: float,
    ) -> float:
        if verdict == VerificationVerdict.PASS:
            return confidence
        if verdict == VerificationVerdict.UNCERTAIN:
            return 0.45 * confidence
        if verdict == VerificationVerdict.SKIPPED:
            return 0.25 * confidence
        return 0.0

    @staticmethod
    def _verdict_uncertainty(
        verdict: VerificationVerdict | None,
        confidence: float,
    ) -> float:
        if verdict is None or verdict == VerificationVerdict.SKIPPED:
            return 1.0
        if verdict == VerificationVerdict.UNCERTAIN:
            return max(0.65, 1.0 - 0.5 * confidence)
        return max(0.0, min(1.0, 1.0 - confidence))

    @staticmethod
    def _raw_attempt_progress(attempt: ProofAttempt) -> float:
        step_progress = min(0.55, 0.055 * len(attempt.proof_steps))
        lemma_progress = min(0.20, 0.065 * len(attempt.proposed_lemmas))
        complete_bonus = 0.30 if attempt.status.value == "complete" else 0.0
        gap_penalty = min(0.35, 0.06 * len(attempt.unresolved_gaps))
        dead_end_penalty = min(0.15, 0.04 * len(attempt.dead_ends))
        return max(
            0.0,
            min(
                1.0,
                step_progress
                + lemma_progress
                + complete_bonus
                - gap_penalty
                - dead_end_penalty,
            ),
        )

    def _evidence_backed_progress(
        self,
        raw_progress: float,
        evidence: _AttemptEvidence,
    ) -> float:
        cfg = self.config.scheduler
        if evidence.verdict is None:
            progress = raw_progress * cfg.unverified_progress_discount
        elif evidence.verdict == VerificationVerdict.PASS:
            progress = raw_progress * (0.75 + 0.25 * evidence.confidence)
        elif evidence.verdict == VerificationVerdict.UNCERTAIN:
            progress = raw_progress * cfg.uncertain_progress_discount
        elif evidence.verdict == VerificationVerdict.FAIL:
            progress = (
                raw_progress
                * cfg.failed_progress_discount
                * max(0.05, 1.0 - evidence.confidence)
            )
        else:
            progress = raw_progress * 0.25

        if evidence.structurally_valid is False:
            progress = min(progress, cfg.structural_failure_progress_cap)
        if evidence.verdict == VerificationVerdict.FAIL:
            caps = {
                FailureLevel.EXECUTION: cfg.execution_failure_progress_cap,
                FailureLevel.PLAN: cfg.plan_failure_progress_cap,
                FailureLevel.STRATEGY: cfg.strategy_failure_progress_cap,
                FailureLevel.NONE: cfg.execution_failure_progress_cap,
            }
            progress = min(progress, caps[evidence.failure_level])
        return max(0.0, min(1.0, progress))

    @staticmethod
    def _gap_reduction(previous: int, current: int) -> float:
        if previous <= 0:
            return 0.0 if current <= 0 else -1.0
        return max(-1.0, min(1.0, (previous - current) / previous))

    def _stagnation_rounds(
        self,
        attempts: list[ProofAttempt],
        snapshots: list[_AttemptEvidence],
        progress: list[float],
    ) -> int:
        if len(attempts) < 2:
            return 0
        threshold = self.config.scheduler.meaningful_progress_threshold
        stagnant = 0
        for index in range(len(attempts) - 1, 0, -1):
            previous = attempts[index - 1]
            current = attempts[index]
            gap_reduction = self._gap_reduction(
                len(previous.unresolved_gaps),
                len(current.unresolved_gaps),
            )
            verification_gain = (
                snapshots[index].verification_score
                - snapshots[index - 1].verification_score
            )
            progress_gain = progress[index] - progress[index - 1]
            became_verified = (
                snapshots[index].verdict == VerificationVerdict.PASS
                and snapshots[index - 1].verdict != VerificationVerdict.PASS
            )
            if (
                became_verified
                or progress_gain >= threshold
                or verification_gain >= threshold
                or gap_reduction > 0.0
            ):
                break
            stagnant += 1
        return stagnant

    @staticmethod
    def _consecutive_failures(snapshots: list[_AttemptEvidence]) -> int:
        failures = 0
        for evidence in reversed(snapshots):
            if evidence.verdict != VerificationVerdict.FAIL:
                break
            failures += 1
        return failures

    @staticmethod
    def _clamp_signed(value: float) -> float:
        return max(-1.0, min(1.0, value))

    @staticmethod
    def _novelty(strategy: StrategyCard, all_strategies: list[StrategyCard]) -> float:
        others = [s for s in all_strategies if s.strategy_id != strategy.strategy_id]
        if not others:
            return 1.0
        max_similarity = max(
            jaccard_similarity(strategy_text(strategy), strategy_text(other))
            for other in others
        )
        return 1.0 - max_similarity

    def decide(
        self,
        stats: list[PathStats],
        *,
        current_path_count: int,
        remaining_calls: int,
        current_round: int = 0,
        final_verified: bool = False,
        max_actions: int | None = None,
        bucket_pressure: dict[str, float] | None = None,
    ) -> BudgetDecision:
        max_actions = max_actions or self.config.scheduler.max_actions_per_round
        coverage = min(
            1.0,
            current_path_count / max(1, self.config.budget.max_paths),
        )
        if final_verified:
            stop = BudgetAction(
                action=ActionKind.STOP,
                score=1.0,
                reason="final proof passed independent verification",
                rank=1,
                selected=True,
            )
            return BudgetDecision(
                actions=[stop],
                candidates=[stop],
                global_uncertainty=0.0,
                coverage=1.0,
                rationale="Stop condition reached.",
            )
        if remaining_calls <= 0:
            stop = BudgetAction(
                action=ActionKind.STOP,
                score=1.0,
                reason="global call budget exhausted",
                rank=1,
                selected=True,
            )
            return BudgetDecision(
                actions=[stop],
                candidates=[stop],
                global_uncertainty=1.0,
                coverage=coverage,
                rationale="No calls remain.",
            )

        explored = [stat for stat in stats if stat.attempt_id is not None]
        evaluated = [stat for stat in explored if stat.latest_verdict is not None]
        failed = [
            stat
            for stat in evaluated
            if stat.latest_verdict == VerificationVerdict.FAIL
        ]
        failure_rate = len(failed) / max(1, len(evaluated))
        all_evaluated_paths_failed = (
            bool(explored)
            and len(evaluated) == len(explored)
            and len(failed) == len(explored)
        )
        global_uncertainty = (
            sum(stat.uncertainty for stat in explored) / len(explored)
            if explored
            else 1.0
        )

        bucket_pressure = bucket_pressure or {}
        breadth_multiplier = max(
            0.35,
            1.25 - 0.35 * bucket_pressure.get("breadth", 0.0),
        )
        depth_multiplier = max(
            0.35,
            1.25 - 0.35 * bucket_pressure.get("depth", 0.0),
        )
        verification_multiplier = max(
            0.45,
            1.30 - 0.30 * bucket_pressure.get("verification", 0.0),
        )

        candidates: list[BudgetAction] = []
        hierarchical = self.config.topology.mode == "hierarchical_sparse"
        graph_mode = self.config.topology.proof_graph.mode
        graph_active = (
            hierarchical
            and self.config.topology.proof_graph.enabled
            and graph_mode == "active"
        )
        inspiration_mode = self.config.topology.inspiration.mode
        inspiration_active = (
            hierarchical
            and self.config.topology.inspiration.enabled
            and inspiration_mode == "active"
        )
        for stat in stats:
            if stat.attempt_id is None:
                continue
            verify_score = (
                0.50 * (1.0 if stat.complete else stat.progress)
                + 0.35 * stat.uncertainty
                + 0.15 * stat.novelty
            )
            verify_eligible = (
                stat.verification_score < self.config.budget.verification_pass_threshold
            )
            verify_blocked: str | None = None
            if (
                stat.latest_verdict == VerificationVerdict.FAIL
                and stat.failure_confidence
                >= self.config.budget.verification_pass_threshold
            ):
                verify_eligible = False
                verify_blocked = (
                    "latest independent audit already rejected this path with "
                    "confidence above the configured verification threshold"
                )
            candidates.append(
                BudgetAction(
                    action=ActionKind.VERIFY,
                    strategy_id=stat.strategy_id,
                    target_id=stat.attempt_id,
                    score=max(0.0, verify_score * verification_multiplier),
                    reason="candidate has material evidence but insufficient independent support",
                    eligible=verify_eligible,
                    blocked_reason=verify_blocked,
                )
            )

            depth_eligible, depth_blocked, repairability = self._depth_eligibility(
                stat,
                current_round=current_round,
            )
            depth_score = (
                0.42 * stat.progress
                + 0.20 * stat.novelty
                + 0.20 * max(0.0, stat.marginal_progress)
                + 0.12 * repairability
                + 0.06 * min(1.0, stat.unresolved_gap_count / 3.0) * repairability
            )
            if stat.latest_verdict in {None, VerificationVerdict.UNCERTAIN}:
                depth_score += 0.08 * stat.uncertainty
            depth_score -= 0.16 * min(3, stat.stagnation_rounds)
            if stat.structurally_valid is False:
                depth_score -= self.config.scheduler.structural_failure_penalty
            depth_score -= self._failure_penalty(stat)
            if graph_active:
                scheduler = self.config.scheduler
                depth_score += (
                    scheduler.proof_debt_reduction_weight
                    * max(0.0, stat.proof_debt_reduction)
                    + scheduler.verified_fact_gain_weight
                    * min(1.0, stat.verified_fact_gain / 2)
                    + scheduler.message_utility_weight * stat.message_utility
                    - scheduler.route_redundancy_penalty * stat.route_redundancy
                    - scheduler.contradiction_priority_weight
                    * min(1.0, stat.contradiction_count)
                )
            candidates.append(
                BudgetAction(
                    action=ActionKind.DEEPEN,
                    strategy_id=stat.strategy_id,
                    target_id=stat.attempt_id,
                    score=max(0.0, depth_score * depth_multiplier),
                    reason=self._depth_reason(stat, repairability),
                    eligible=depth_eligible,
                    blocked_reason=depth_blocked,
                )
            )

            if hierarchical and graph_mode in {"shadow", "active"}:
                shadow = not graph_active
                if stat.counterexample_count:
                    candidates.append(
                        BudgetAction(
                            action=ActionKind.SEARCH_COUNTEREXAMPLE,
                            strategy_id=stat.strategy_id,
                            target_id=stat.attempt_id,
                            score=(
                                self.config.scheduler.counterexample_priority_weight
                                * min(1.0, stat.counterexample_count)
                                + stat.high_centrality_obligation_count * 0.08
                            ),
                            reason="an exact or candidate counterexample has priority over positive votes",
                            eligible=not shadow,
                            blocked_reason=(
                                "proof graph shadow mode records this recommendation only"
                                if shadow
                                else None
                            ),
                        )
                    )
                if stat.contradiction_count:
                    candidates.append(
                        BudgetAction(
                            action=ActionKind.RESOLVE_CONFLICT,
                            strategy_id=stat.strategy_id,
                            target_id=stat.attempt_id,
                            score=(
                                self.config.scheduler.contradiction_priority_weight
                                * min(1.0, stat.contradiction_count)
                                + stat.high_centrality_obligation_count * 0.08
                            ),
                            reason="an unresolved scoped contradiction blocks synthesis",
                            eligible=not shadow,
                            blocked_reason=(
                                "proof graph shadow mode records this recommendation only"
                                if shadow
                                else None
                            ),
                        )
                    )
                if stat.shared_obligation_count or stat.bridge_opportunity:
                    candidates.append(
                        BudgetAction(
                            action=ActionKind.BRIDGE,
                            strategy_id=stat.strategy_id,
                            target_id=stat.attempt_id,
                            score=(
                                self.config.scheduler.bridge_priority_weight
                                * stat.bridge_opportunity
                                + self.config.scheduler.shared_bottleneck_weight
                                * min(1.0, stat.shared_obligation_count / 2)
                            ),
                            reason="several routes can share one independently verified bridge lemma",
                            eligible=not shadow,
                            blocked_reason=(
                                "proof graph shadow mode records this recommendation only"
                                if shadow
                                else None
                            ),
                        )
                    )
                if (
                    stat.route_redundancy
                    >= self.config.topology.broker.duplicate_strategy_threshold
                ):
                    candidates.append(
                        BudgetAction(
                            action=ActionKind.MERGE_ROUTE,
                            strategy_id=stat.strategy_id,
                            target_id=stat.attempt_id,
                            score=self.config.scheduler.route_redundancy_penalty
                            * stat.route_redundancy,
                            reason="route mechanism, obligations, and facts are redundant",
                            eligible=not shadow,
                            blocked_reason=(
                                "proof graph shadow mode records this recommendation only"
                                if shadow
                                else None
                            ),
                        )
                    )

            if hierarchical and inspiration_mode in {"shadow", "active"}:
                shadow = not inspiration_active
                obligation_relevance = min(
                    1.0,
                    (
                        stat.high_centrality_obligation_count
                        + stat.shared_obligation_count
                    )
                    / 3.0,
                )
                expected_debt_gain = min(1.0, stat.proof_debt / (1.0 + stat.proof_debt))
                historical_success = min(1.0, stat.verified_fact_gain / 2.0)
                fast_falsification = 1.0 / 4.0
                inspiration_base = max(
                    0.0,
                    0.30 * stat.novelty_score
                    + 0.20 * obligation_relevance
                    + 0.20 * expected_debt_gain
                    + 0.10 * fast_falsification
                    + 0.10 * historical_success
                    - 0.10 * stat.route_redundancy,
                )
                inspiration_candidates = [
                    (
                        ActionKind.TRIGGER_INSPIRATION,
                        inspiration_base
                        + stat.novelty_score
                        * self.config.scheduler.inspiration_novelty_weight,
                        stat.inspiration_trigger_count > 0,
                    ),
                    (
                        ActionKind.SWITCH_REPRESENTATION,
                        stat.representation_diversity
                        * self.config.scheduler.representation_switch_weight,
                        stat.representation_diversity > 0,
                    ),
                    (
                        ActionKind.SEARCH_ANALOGY,
                        stat.analogy_opportunity
                        * self.config.scheduler.analogy_relevance_weight,
                        stat.analogy_opportunity > 0,
                    ),
                    (
                        ActionKind.INVENT_CONSTRUCTION,
                        stat.construction_opportunity
                        * self.config.scheduler.construction_relevance_weight,
                        stat.construction_opportunity > 0,
                    ),
                    (
                        ActionKind.GENERATE_INVARIANT,
                        inspiration_base
                        + self.config.scheduler.construction_relevance_weight
                        * expected_debt_gain,
                        stat.proof_debt > 0
                        and (
                            stat.stagnation_rounds > 0
                            or stat.inspiration_trigger_count > 0
                        ),
                    ),
                    (
                        ActionKind.REVERSE_GOAL,
                        inspiration_base
                        + self.config.scheduler.bridge_priority_weight
                        * obligation_relevance,
                        stat.shared_obligation_count > 0
                        or stat.high_centrality_obligation_count > 0,
                    ),
                    (
                        ActionKind.META_REPLAN,
                        self.config.scheduler.meta_replan_weight
                        * min(1.0, stat.stagnation_rounds / 2),
                        stat.stagnation_rounds > 0,
                    ),
                    (
                        ActionKind.SURPRISE_WIDEN,
                        inspiration_base
                        + self.config.scheduler.surprise_exploration_weight
                        * stat.novelty_score,
                        stat.surprise_budget_remaining > 0
                        and current_path_count < self.config.budget.max_paths,
                    ),
                ]
                for action, score, relevant in inspiration_candidates:
                    if not relevant:
                        continue
                    candidates.append(
                        BudgetAction(
                            action=action,
                            strategy_id=stat.strategy_id,
                            target_id=stat.attempt_id,
                            score=score,
                            reason="Inspiration Engine recommends a mechanism-changing action from observable graph signals",
                            eligible=not shadow,
                            blocked_reason=(
                                "inspiration shadow mode records this recommendation only"
                                if shadow
                                else None
                            ),
                        )
                    )

        best_verified = max((stat.verification_score for stat in stats), default=0.0)
        any_complete = any(stat.complete for stat in stats)
        synthesis_eligible = (
            any_complete and best_verified >= self.config.budget.synthesis_threshold
        )
        candidates.append(
            BudgetAction(
                action=ActionKind.SYNTHESIZE,
                score=0.6 + 0.35 * best_verified + 0.05 * coverage,
                reason="at least one sufficiently supported complete candidate is available",
                eligible=synthesis_eligible,
                blocked_reason=None
                if synthesis_eligible
                else "no complete candidate meets the configured synthesis threshold",
            )
        )

        mean_stagnation = (
            sum(stat.stagnation_rounds for stat in explored) / len(explored)
            if explored
            else 1.0
        )
        max_progress = max((stat.progress for stat in explored), default=0.0)
        remaining_capacity = max(0, self.config.budget.max_paths - current_path_count)
        planned_paths = min(
            self.config.scheduler.widen_paths_per_action,
            remaining_capacity,
        )
        widen_eligible = planned_paths > 0
        widen_score = (
            0.30 * (1.0 - coverage)
            + 0.20 * global_uncertainty
            + 0.20 * min(1.0, mean_stagnation)
            + 0.15 * (1.0 - max_progress)
            + 0.15 * failure_rate
        )
        widen = BudgetAction(
            action=ActionKind.WIDEN,
            score=max(0.0, widen_score * breadth_multiplier),
            reason="existing mechanisms leave capacity uncovered, failed, or stagnant",
            eligible=widen_eligible,
            planned_paths=planned_paths,
            blocked_reason=None
            if widen_eligible
            else "maximum path capacity has already been reached",
        )
        candidates.append(widen)

        candidates.sort(key=lambda action: action.score, reverse=True)
        for rank, candidate in enumerate(candidates, start=1):
            candidate.rank = rank

        eligible = [candidate for candidate in candidates if candidate.eligible]
        selected = eligible[: min(max_actions, remaining_calls)]
        forced_widen = False
        if (
            self.config.scheduler.force_widen_when_all_failed
            and all_evaluated_paths_failed
            and widen.eligible
        ):
            widen.forced = True
            forced_widen = True
            if widen not in selected:
                if len(selected) >= min(max_actions, remaining_calls):
                    selected[-1] = widen
                else:
                    selected.append(widen)

        selected_ids = {id(candidate) for candidate in selected}
        for candidate in candidates:
            candidate.selected = id(candidate) in selected_ids
            if (
                candidate.eligible
                and not candidate.selected
                and candidate.blocked_reason is None
            ):
                candidate.blocked_reason = (
                    f"ranked #{candidate.rank} below the configured "
                    f"max_actions_per_round={max_actions} cutoff"
                )
        for candidate in selected:
            candidate.blocked_reason = None

        if not selected:
            stop = BudgetAction(
                action=ActionKind.STOP,
                score=1.0,
                reason="no action is eligible under the current failure and capacity policy",
                rank=1,
                selected=True,
            )
            selected = [stop]
            candidates.insert(0, stop)

        return BudgetDecision(
            actions=selected,
            candidates=candidates,
            global_uncertainty=max(0.0, min(1.0, global_uncertainty)),
            coverage=coverage,
            failure_rate=max(0.0, min(1.0, failure_rate)),
            all_evaluated_paths_failed=all_evaluated_paths_failed,
            forced_widen=forced_widen,
            rationale=(
                "Ranks use evidence-backed progress, verification history, failure level, "
                "repairability, novelty, stagnation, path-capacity coverage, and soft-budget pressure."
            ),
        )

    def _depth_eligibility(
        self,
        stat: PathStats,
        *,
        current_round: int,
    ) -> tuple[bool, str | None, float]:
        if (
            stat.complete
            and stat.verification_score >= self.config.budget.synthesis_threshold
        ):
            return False, "candidate is already complete and synthesis-ready", 0.0
        if stat.latest_verdict != VerificationVerdict.FAIL:
            return True, None, 0.75

        cfg = self.config.scheduler
        limits = {
            FailureLevel.EXECUTION: cfg.max_execution_repairs_per_path,
            FailureLevel.PLAN: cfg.max_plan_repairs_per_path,
            FailureLevel.STRATEGY: 1 if cfg.allow_strategy_failure_repair else 0,
            FailureLevel.NONE: cfg.max_unknown_failure_repairs_per_path,
        }
        limit = limits[stat.failure_level]
        repairability = {
            FailureLevel.EXECUTION: 1.0,
            FailureLevel.PLAN: 0.45,
            FailureLevel.STRATEGY: 0.0,
            FailureLevel.NONE: 0.55,
        }[stat.failure_level]
        if limit <= 0:
            return (
                False,
                f"{stat.failure_level.value} failure is configured to switch mechanisms rather than deepen",
                repairability,
            )
        if stat.failed_repair_attempts < limit:
            return True, None, repairability

        cooldown_until = stat.last_round_index + cfg.failed_path_cooldown_rounds
        if current_round <= cooldown_until:
            return (
                False,
                "failed repair limit reached; route is cooling down until "
                f"after adaptive round {cooldown_until}",
                repairability,
            )
        return True, None, max(0.15, 0.5 * repairability)

    def _failure_penalty(self, stat: PathStats) -> float:
        if stat.latest_verdict != VerificationVerdict.FAIL:
            return 0.0
        cfg = self.config.scheduler
        base = {
            FailureLevel.EXECUTION: cfg.execution_failure_penalty,
            FailureLevel.PLAN: cfg.plan_failure_penalty,
            FailureLevel.STRATEGY: cfg.strategy_failure_penalty,
            FailureLevel.NONE: cfg.execution_failure_penalty,
        }[stat.failure_level]
        repeated = cfg.repeated_failure_penalty * max(0, stat.consecutive_failures - 1)
        return (base + repeated) * max(0.25, stat.failure_confidence)

    @staticmethod
    def _depth_reason(stat: PathStats, repairability: float) -> str:
        if stat.latest_verdict == VerificationVerdict.FAIL:
            return (
                f"{stat.failure_level.value} failure has repairability {repairability:.2f}; "
                "deepen only if the configured repair allowance and cooldown permit"
            )
        return "path has evidence-backed marginal progress or a locally repairable gap"


class SoftBudgetAllocator:
    """Soft phase targets plus dynamic action-cost and finish-reserve admission."""

    BUCKETS = ("breadth", "depth", "verification", "synthesis")

    def __init__(self, config: SystemConfig, ledger: CallLedger) -> None:
        self.config = config
        self.ledger = ledger
        maximum = config.budget.max_total_calls
        shares = {
            "breadth": config.budget.breadth_share,
            "depth": config.budget.depth_share,
            "verification": config.budget.verification_share,
            "synthesis": config.budget.synthesis_share,
        }
        self.targets = {
            bucket: max(1, int(round(maximum * share)))
            for bucket, share in shares.items()
        }
        revision_cycles = min(
            config.scheduler.reserve_revision_cycles,
            config.budget.max_revisions,
        )
        requested_reserve = self.estimate_action_calls(ActionKind.SYNTHESIZE)
        requested_reserve += revision_cycles * self.estimate_revision_cycle_calls()
        self.minimum_finish_reserve = min(maximum, requested_reserve)

    def pressure_snapshot(self) -> dict[str, float]:
        return {
            bucket: self.ledger.bucket_calls.get(bucket, 0)
            / max(1, self.targets[bucket])
            for bucket in self.BUCKETS
        }

    def can_spend(
        self,
        bucket: str,
        calls: int,
        *,
        protect_finish: bool,
        has_candidate: bool,
    ) -> bool:
        return (
            self.spend_block_reason(
                bucket,
                calls,
                protect_finish=protect_finish,
                has_candidate=has_candidate,
            )
            is None
        )

    def spend_block_reason(
        self,
        bucket: str,
        calls: int,
        *,
        protect_finish: bool,
        has_candidate: bool,
        remaining_calls: int | None = None,
        bucket_calls: Mapping[str, int] | None = None,
    ) -> str | None:
        if calls <= 0:
            return None
        remaining = (
            self.ledger.remaining_calls if remaining_calls is None else remaining_calls
        )
        used_by_bucket = (
            self.ledger.bucket_calls if bucket_calls is None else bucket_calls
        )
        reserve = (
            self.minimum_finish_reserve
            if protect_finish and has_candidate and bucket != "synthesis"
            else 0
        )
        if remaining - calls < reserve:
            return (
                f"estimated cost {calls} would leave {remaining - calls} calls, below "
                f"the configured finalization reserve {reserve}"
            )
        if bucket in self.BUCKETS:
            used = used_by_bucket.get(bucket, 0)
            target = self.targets[bucket]
            if used + calls > target:
                missing_verification = used_by_bucket.get("verification", 0) == 0
                missing_synthesis = used_by_bucket.get("synthesis", 0) == 0
                protected = int(missing_verification) + int(missing_synthesis)
                if remaining - calls < reserve + protected:
                    return (
                        f"{bucket} soft target would be exceeded before untouched later "
                        "phases receive their minimum headroom"
                    )
        return None

    def admit_decision(
        self,
        decision: BudgetDecision,
        *,
        current_path_count: int,
        has_candidate: bool,
        max_actions: int,
    ) -> BudgetDecision:
        decision.finish_reserve_calls = self.minimum_finish_reserve
        if not decision.candidates:
            return decision

        projected_remaining = self.ledger.remaining_calls
        projected_bucket_calls = dict(self.ledger.bucket_calls)
        projected_paths = current_path_count
        admitted: list[BudgetAction] = []
        terminal: list[BudgetAction] = []

        priority = sorted(
            decision.candidates,
            key=lambda action: (
                0 if action.forced else 1 if action.selected else 2,
                action.rank or 10**6,
            ),
        )
        for candidate in decision.candidates:
            candidate.selected = False
            if (
                candidate.eligible
                and candidate.blocked_reason
                and "ranked #" in candidate.blocked_reason
            ):
                candidate.blocked_reason = None

        for candidate in priority:
            if not candidate.eligible:
                continue
            if candidate.action in {ActionKind.STOP, ActionKind.SYNTHESIZE}:
                if not admitted and candidate in decision.actions:
                    candidate.selected = True
                    terminal = [candidate]
                continue
            if len(admitted) >= max_actions:
                if candidate.blocked_reason is None:
                    candidate.blocked_reason = (
                        f"ranked #{candidate.rank} below the configured "
                        f"max_actions_per_round={max_actions} cutoff"
                    )
                continue

            if candidate.action == ActionKind.WIDEN:
                candidate.planned_paths = min(
                    candidate.planned_paths
                    or self.config.scheduler.widen_paths_per_action,
                    max(0, self.config.budget.max_paths - projected_paths),
                )
                admitted_count = self._largest_affordable_widen_batch(
                    candidate,
                    projected_paths=projected_paths,
                    projected_remaining=projected_remaining,
                    projected_bucket_calls=projected_bucket_calls,
                    has_candidate=has_candidate,
                )
                if admitted_count <= 0:
                    candidate.selected = False
                    continue
                candidate.planned_paths = admitted_count
            candidate.estimated_calls = self.estimate_action_calls(
                candidate.action,
                current_path_count=projected_paths,
                widen_path_count=candidate.planned_paths or None,
            )
            bucket = self.bucket_for_action(candidate.action)
            blocked = self.spend_block_reason(
                bucket,
                candidate.estimated_calls,
                protect_finish=True,
                has_candidate=has_candidate,
                remaining_calls=projected_remaining,
                bucket_calls=projected_bucket_calls,
            )
            if blocked is not None:
                candidate.blocked_reason = blocked
                candidate.selected = False
                continue

            candidate.selected = True
            candidate.blocked_reason = None
            admitted.append(candidate)
            projected_remaining -= candidate.estimated_calls
            projected_bucket_calls[bucket] = (
                projected_bucket_calls.get(bucket, 0) + candidate.estimated_calls
            )
            if candidate.action == ActionKind.WIDEN:
                projected_paths += candidate.planned_paths
            elif candidate.action == ActionKind.SURPRISE_WIDEN:
                projected_paths += 1

        if admitted:
            decision.actions = admitted
        elif terminal:
            decision.actions = terminal
        else:
            stop = BudgetAction(
                action=ActionKind.STOP,
                score=1.0,
                reason="all ranked actions were blocked by capacity, cooldown, or reserve policy",
                selected=True,
            )
            decision.actions = [stop]
        return decision

    def _largest_affordable_widen_batch(
        self,
        candidate: BudgetAction,
        *,
        projected_paths: int,
        projected_remaining: int,
        projected_bucket_calls: Mapping[str, int],
        has_candidate: bool,
    ) -> int:
        maximum = min(
            candidate.planned_paths,
            self.config.budget.max_paths - projected_paths,
        )
        last_reason = "maximum path capacity has already been reached"
        for count in range(maximum, 0, -1):
            calls = self.estimate_action_calls(
                ActionKind.WIDEN,
                current_path_count=projected_paths,
                widen_path_count=count,
            )
            blocked = self.spend_block_reason(
                "breadth",
                calls,
                protect_finish=True,
                has_candidate=has_candidate,
                remaining_calls=projected_remaining,
                bucket_calls=projected_bucket_calls,
            )
            if blocked is None:
                candidate.estimated_calls = calls
                return count
            last_reason = blocked
        candidate.blocked_reason = last_reason
        candidate.estimated_calls = self.estimate_action_calls(
            ActionKind.WIDEN,
            current_path_count=projected_paths,
            widen_path_count=max(1, maximum),
        )
        return 0

    def should_protect_finish(
        self,
        aggregate_reports: Iterable[VerificationReport],
    ) -> bool:
        has_supported = any(
            report.verdict == VerificationVerdict.PASS
            and report.confidence >= self.config.budget.synthesis_threshold
            for report in aggregate_reports
        )
        threshold = max(
            self.minimum_finish_reserve
            + self.config.scheduler.finish_transition_buffer_calls,
            self.targets["synthesis"],
        )
        return has_supported and self.ledger.remaining_calls <= threshold

    def estimate_action_calls(
        self,
        action: ActionKind,
        *,
        current_path_count: int = 0,
        widen_path_count: int | None = None,
    ) -> int:
        verification_calls = self._verification_calls()
        followup_calls = 0
        if self.config.scheduler.include_post_action_verification_in_cost:
            followup_calls += verification_calls
        if self.config.scheduler.include_meta_review_in_cost:
            followup_calls += 1

        if action == ActionKind.WIDEN:
            capacity = max(0, self.config.budget.max_paths - current_path_count)
            paths = min(
                widen_path_count or self.config.scheduler.widen_paths_per_action,
                capacity,
            )
            if paths <= 0:
                return 0
            return 1 + paths * self._calls_per_explored_path() + followup_calls
        if action == ActionKind.DEEPEN:
            return self._calls_per_explored_path() + followup_calls
        if action == ActionKind.VERIFY:
            return verification_calls
        if action == ActionKind.SYNTHESIZE:
            return 1 + verification_calls
        if action == ActionKind.REVISE:
            return 1 + verification_calls
        if action == ActionKind.BRIDGE:
            return 1 + verification_calls
        if action == ActionKind.RESOLVE_CONFLICT:
            return 2 + verification_calls
        if action == ActionKind.SEARCH_COUNTEREXAMPLE:
            return 2 + verification_calls
        if action in {ActionKind.MERGE_ROUTE, ActionKind.COOLDOWN_ROUTE}:
            return 0
        if action in {
            ActionKind.SWITCH_REPRESENTATION,
            ActionKind.SEARCH_ANALOGY,
            ActionKind.INVENT_CONSTRUCTION,
            ActionKind.GENERATE_INVARIANT,
            ActionKind.REVERSE_GOAL,
            ActionKind.META_REPLAN,
            ActionKind.TRIGGER_INSPIRATION,
            ActionKind.SURPRISE_WIDEN,
        }:
            return 3
        return 0

    def _calls_per_explored_path(self) -> int:
        if not self.config.continuation.enabled:
            explore_calls = 1
        else:
            segments = self.config.continuation.segments_per_explore_call
            explore_calls = segments
            if self.config.continuation.verify_each_delta:
                explore_calls += (
                    segments * self.config.continuation.delta_verifier_replicas
                )
        claim_extraction_calls = 1
        return explore_calls + claim_extraction_calls

    def _verification_calls(self) -> int:
        # One structural gate plus the largest configured detailed-review panel.
        # A structural failure may use a second structural reviewer, but that is no
        # larger than this bound when high_risk_verifier_replicas >= 1.
        return (
            1
            + self.config.budget.high_risk_verifier_replicas
            + self.config.scheduler.verification_call_safety_margin
        )

    @staticmethod
    def bucket_for_action(action: ActionKind) -> str:
        return {
            ActionKind.WIDEN: "breadth",
            ActionKind.DEEPEN: "depth",
            ActionKind.VERIFY: "verification",
            ActionKind.SYNTHESIZE: "synthesis",
            ActionKind.REVISE: "synthesis",
            ActionKind.STOP: "other",
            ActionKind.BRIDGE: "depth",
            ActionKind.RESOLVE_CONFLICT: "verification",
            ActionKind.SEARCH_COUNTEREXAMPLE: "verification",
            ActionKind.MERGE_ROUTE: "other",
            ActionKind.COOLDOWN_ROUTE: "other",
            ActionKind.SWITCH_REPRESENTATION: "breadth",
            ActionKind.TRIGGER_INSPIRATION: "breadth",
            ActionKind.SEARCH_ANALOGY: "breadth",
            ActionKind.INVENT_CONSTRUCTION: "breadth",
            ActionKind.GENERATE_INVARIANT: "breadth",
            ActionKind.REVERSE_GOAL: "depth",
            ActionKind.META_REPLAN: "breadth",
            ActionKind.SURPRISE_WIDEN: "breadth",
        }.get(action, "other")

    def estimate_revision_cycle_calls(self) -> int:
        return self.estimate_action_calls(ActionKind.REVISE)
