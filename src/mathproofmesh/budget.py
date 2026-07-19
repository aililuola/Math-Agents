from __future__ import annotations

from collections import defaultdict
from typing import Iterable

from .config import SystemConfig
from .agents import CallLedger
from .schemas import (
    ActionKind,
    BudgetAction,
    BudgetDecision,
    PathStats,
    ProofAttempt,
    StrategyCard,
    VerificationReport,
    VerificationVerdict,
)
from .topology import jaccard_similarity, strategy_text


class AdaptiveBudgetManager:
    """A lightweight breadth/depth/verification controller inspired by adaptive branching search."""

    def __init__(self, config: SystemConfig) -> None:
        self.config = config

    def build_path_stats(
        self,
        strategies: list[StrategyCard],
        attempts: list[ProofAttempt],
        reports: list[VerificationReport],
    ) -> list[PathStats]:
        attempts_by_strategy: dict[str, list[ProofAttempt]] = defaultdict(list)
        for attempt in attempts:
            attempts_by_strategy[attempt.strategy_id].append(attempt)
        reports_by_target: dict[str, list[VerificationReport]] = defaultdict(list)
        for report in reports:
            reports_by_target[report.target_id].append(report)

        stats: list[PathStats] = []
        for strategy in strategies:
            path_attempts = sorted(
                attempts_by_strategy.get(strategy.strategy_id, []),
                key=lambda x: x.round_index,
            )
            latest = path_attempts[-1] if path_attempts else None
            if latest is None:
                stats.append(PathStats(strategy_id=strategy.strategy_id, novelty=self._novelty(strategy, strategies)))
                continue

            step_progress = min(0.6, 0.06 * len(latest.proof_steps))
            lemma_progress = min(0.25, 0.08 * len(latest.proposed_lemmas))
            complete_bonus = 0.35 if latest.status.value == "complete" else 0.0
            gap_penalty = min(0.35, 0.06 * len(latest.unresolved_gaps))
            progress = max(0.0, min(1.0, step_progress + lemma_progress + complete_bonus - gap_penalty))

            target_reports = reports_by_target.get(latest.attempt_id, [])
            verification_score = 0.0
            structurally_valid: bool | None = None
            if target_reports:
                stage_scores: list[float] = []
                for report in target_reports:
                    if report.stage.value == "structural":
                        structurally_valid = report.verdict == VerificationVerdict.PASS
                    verdict_value = {
                        VerificationVerdict.PASS: 1.0,
                        VerificationVerdict.UNCERTAIN: 0.45,
                        VerificationVerdict.FAIL: 0.0,
                        VerificationVerdict.SKIPPED: 0.25,
                    }[report.verdict]
                    stage_scores.append(verdict_value * report.confidence)
                verification_score = min(stage_scores) if stage_scores else 0.0
                uncertainty = 1.0 - max(stage_scores)
            else:
                uncertainty = max(0.15, 1.0 - latest.self_confidence)

            stagnation = 0
            if len(path_attempts) >= 2:
                previous = path_attempts[-2]
                previous_progress = min(
                    1.0,
                    0.06 * len(previous.proof_steps)
                    + 0.08 * len(previous.proposed_lemmas)
                    + (0.35 if previous.status.value == "complete" else 0.0)
                    - min(0.35, 0.06 * len(previous.unresolved_gaps)),
                )
                if progress <= previous_progress + 0.03:
                    stagnation = 1 + sum(
                        1
                        for a, b in zip(path_attempts[:-1], path_attempts[1:])
                        if len(b.proof_steps) <= len(a.proof_steps)
                    )

            stats.append(
                PathStats(
                    strategy_id=strategy.strategy_id,
                    attempt_id=latest.attempt_id,
                    complete=latest.status.value == "complete",
                    progress=progress,
                    novelty=self._novelty(strategy, strategies),
                    uncertainty=max(0.0, min(1.0, uncertainty)),
                    verification_score=max(0.0, min(1.0, verification_score)),
                    unresolved_gap_count=len(latest.unresolved_gaps),
                    stagnation_rounds=stagnation,
                    tokens_spent=sum(a.usage.total_tokens for a in path_attempts),
                    structurally_valid=structurally_valid,
                )
            )
        return stats

    @staticmethod
    def _novelty(strategy: StrategyCard, all_strategies: list[StrategyCard]) -> float:
        others = [s for s in all_strategies if s.strategy_id != strategy.strategy_id]
        if not others:
            return 1.0
        max_similarity = max(
            jaccard_similarity(strategy_text(strategy), strategy_text(other)) for other in others
        )
        return 1.0 - max_similarity

    def decide(
        self,
        stats: list[PathStats],
        *,
        current_path_count: int,
        remaining_calls: int,
        final_verified: bool = False,
        max_actions: int = 2,
        bucket_pressure: dict[str, float] | None = None,
    ) -> BudgetDecision:
        if final_verified:
            return BudgetDecision(
                actions=[BudgetAction(action=ActionKind.STOP, score=1.0, reason="final proof passed independent verification")],
                global_uncertainty=0.0,
                coverage=1.0,
                rationale="Stop condition reached.",
            )
        if remaining_calls <= 0:
            return BudgetDecision(
                actions=[BudgetAction(action=ActionKind.STOP, score=1.0, reason="global call budget exhausted")],
                global_uncertainty=1.0,
                coverage=min(1.0, current_path_count / max(1, self.config.budget.max_paths)),
                rationale="No calls remain.",
            )

        coverage = min(1.0, current_path_count / max(1, self.config.budget.initial_paths))
        global_uncertainty = (
            sum(s.uncertainty for s in stats) / len(stats) if stats else 1.0
        )
        actions: list[BudgetAction] = []
        bucket_pressure = bucket_pressure or {}
        breadth_multiplier = max(0.35, 1.25 - 0.35 * bucket_pressure.get("breadth", 0.0))
        depth_multiplier = max(0.35, 1.25 - 0.35 * bucket_pressure.get("depth", 0.0))
        verification_multiplier = max(0.45, 1.30 - 0.30 * bucket_pressure.get("verification", 0.0))

        for stat in stats:
            if stat.attempt_id is None:
                continue
            # Verification is high-value for complete, influential candidates that remain uncertain.
            verify_score = (
                0.55 * (1.0 if stat.complete else stat.progress)
                + 0.35 * stat.uncertainty
                + 0.10 * stat.novelty
            )
            if stat.verification_score < self.config.budget.verification_pass_threshold:
                actions.append(
                    BudgetAction(
                        action=ActionKind.VERIFY,
                        strategy_id=stat.strategy_id,
                        target_id=stat.attempt_id,
                        score=verify_score * verification_multiplier,
                        reason="candidate has material progress but insufficient independent verification",
                    )
                )

            # Deepening is preferred for promising paths with repairable gaps and nonzero marginal progress.
            if not stat.complete or stat.verification_score < self.config.budget.synthesis_threshold:
                depth_score = (
                    0.45 * stat.progress
                    + 0.25 * stat.novelty
                    + 0.20 * stat.uncertainty
                    + 0.10 * min(1.0, stat.unresolved_gap_count / 3.0)
                    - 0.18 * min(2, stat.stagnation_rounds)
                )
                if stat.structurally_valid is False:
                    depth_score -= 0.2
                actions.append(
                    BudgetAction(
                        action=ActionKind.DEEPEN,
                        strategy_id=stat.strategy_id,
                        target_id=stat.attempt_id,
                        score=depth_score * depth_multiplier,
                        reason="promising path needs targeted repair or deeper derivation",
                    )
                )

        best_verified = max((s.verification_score for s in stats), default=0.0)
        any_complete = any(s.complete for s in stats)
        if any_complete and best_verified >= self.config.budget.synthesis_threshold:
            actions.append(
                BudgetAction(
                    action=ActionKind.SYNTHESIZE,
                    score=0.6 + 0.35 * best_verified + 0.05 * coverage,
                    reason="at least one sufficiently supported complete candidate is available",
                )
            )

        mean_stagnation = (
            sum(s.stagnation_rounds for s in stats) / len(stats) if stats else 1.0
        )
        max_progress = max((s.progress for s in stats), default=0.0)
        if current_path_count < self.config.budget.max_paths:
            widen_score = (
                0.35 * (1.0 - coverage)
                + 0.30 * global_uncertainty
                + 0.20 * min(1.0, mean_stagnation)
                + 0.15 * (1.0 - max_progress)
            )
            actions.append(
                BudgetAction(
                    action=ActionKind.WIDEN,
                    score=widen_score * breadth_multiplier,
                    reason="existing paths leave uncovered mechanisms or show stagnation",
                )
            )

        # Remove actions that would consume more calls than remain; synthesis/final verification needs headroom.
        actions.sort(key=lambda a: a.score, reverse=True)
        selected: list[BudgetAction] = []
        seen_targets: set[tuple[ActionKind, str | None]] = set()
        for action in actions:
            key = (action.action, action.strategy_id)
            if key in seen_targets:
                continue
            # Do not spend the last call merely widening when a candidate already exists.
            if remaining_calls == 1 and action.action == ActionKind.WIDEN and any_complete:
                continue
            selected.append(action)
            seen_targets.add(key)
            if len(selected) >= min(max_actions, remaining_calls):
                break

        if not selected:
            selected = [
                BudgetAction(
                    action=ActionKind.STOP,
                    score=1.0,
                    reason="no action has positive expected value under remaining budget",
                )
            ]

        return BudgetDecision(
            actions=selected,
            global_uncertainty=max(0.0, min(1.0, global_uncertainty)),
            coverage=max(0.0, min(1.0, coverage)),
            rationale=(
                "Scores combine verified progress, uncertainty, strategy novelty, unresolved gaps, "
                "stagnation, and remaining budget; breadth and depth are selected adaptively."
            ),
        )


class SoftBudgetAllocator:
    """
    Tracks the intended breadth/depth/verification/synthesis mix as soft targets.

    Shares are not hard walls: unused calls may be borrowed by a high-value phase, but
    the allocator protects a minimum finish reserve so exploration cannot consume every
    call before synthesis and independent final verification.
    """

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
        # At least one call per phase; rounding residue remains globally borrowable.
        self.targets = {bucket: max(1, int(round(maximum * share))) for bucket, share in shares.items()}
        self.minimum_finish_reserve = min(maximum, 3)  # synthesis + structural audit + detailed audit

    def pressure_snapshot(self) -> dict[str, float]:
        return {
            bucket: self.ledger.bucket_calls.get(bucket, 0) / max(1, self.targets[bucket])
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
        if calls <= 0:
            return True
        remaining = self.ledger.remaining_calls
        reserve = self.minimum_finish_reserve if protect_finish and has_candidate and bucket != "synthesis" else 0
        if remaining - calls < reserve:
            return False
        # A phase may exceed its target by borrowing, but not while another essential
        # later phase has never received any budget and global headroom is small.
        if bucket in self.BUCKETS:
            used = self.ledger.bucket_calls.get(bucket, 0)
            target = self.targets[bucket]
            if used + calls > target:
                missing_verification = self.ledger.bucket_calls.get("verification", 0) == 0
                missing_synthesis = self.ledger.bucket_calls.get("synthesis", 0) == 0
                protected = int(missing_verification) + int(missing_synthesis)
                if remaining - calls < reserve + protected:
                    return False
        return True

    def should_protect_finish(self, aggregate_reports: Iterable[VerificationReport]) -> bool:
        has_supported = any(
            report.verdict == VerificationVerdict.PASS
            and report.confidence >= self.config.budget.synthesis_threshold
            for report in aggregate_reports
        )
        return has_supported and self.ledger.remaining_calls <= max(
            self.minimum_finish_reserve + 2,
            self.targets["synthesis"] + 2,
        )

    @staticmethod
    def estimate_action_calls(action: ActionKind) -> int:
        return {
            ActionKind.WIDEN: 3,       # strategy + explorer + claim packet
            ActionKind.DEEPEN: 2,      # explorer + claim packet
            ActionKind.VERIFY: 2,      # structural + detailed; conditional replicas may cost more
            ActionKind.SYNTHESIZE: 3,  # synthesis + structural + detailed final audit
            ActionKind.REVISE: 3,      # revision + structural + detailed re-audit
            ActionKind.STOP: 0,
        }[action]

    @staticmethod
    def bucket_for_action(action: ActionKind) -> str:
        return {
            ActionKind.WIDEN: "breadth",
            ActionKind.DEEPEN: "depth",
            ActionKind.VERIFY: "verification",
            ActionKind.SYNTHESIZE: "synthesis",
            ActionKind.REVISE: "synthesis",
            ActionKind.STOP: "other",
        }[action]

    @staticmethod
    def estimate_revision_cycle_calls() -> int:
        return 3
