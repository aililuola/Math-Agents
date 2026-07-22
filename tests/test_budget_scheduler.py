from __future__ import annotations

from pathlib import Path

import pytest

from mathproofmesh.agents import CallLedger
from mathproofmesh.budget import AdaptiveBudgetManager, SoftBudgetAllocator
from mathproofmesh.config import SchedulerConfig, load_config
from mathproofmesh.llm.pool import AgentPool
from mathproofmesh.schemas import (
    ActionKind,
    AttemptStatus,
    FailureLevel,
    PathStats,
    ProofAttempt,
    ProofStep,
    Severity,
    StrategyCard,
    VerificationIssue,
    VerificationReport,
    VerificationStage,
    VerificationVerdict,
)


def _strategy(index: int) -> StrategyCard:
    return StrategyCard(
        strategy_id=f"strategy-{index}",
        title=f"Mechanism {index}",
        core_idea=f"Use independent mechanism {index}",
        independence_basis=f"Distinct invariant family {index}",
        expected_lemmas=[f"lemma-{index}"],
        bottleneck=f"bottleneck-{index}",
        falsification_test=f"counterexample-test-{index}",
        estimated_success=0.35,
        tags=[f"tag-{index}"],
    )


def _attempt(
    strategy: StrategyCard,
    *,
    attempt_index: int = 0,
    round_index: int = 0,
    steps: int = 10,
    gaps: int = 4,
    status: AttemptStatus = AttemptStatus.PARTIAL,
) -> ProofAttempt:
    return ProofAttempt(
        attempt_id=f"attempt-{strategy.strategy_id}-{attempt_index}",
        problem_hash="problem-hash",
        strategy_id=strategy.strategy_id,
        agent_id=f"explorer-{strategy.strategy_id}",
        round_index=round_index,
        status=status,
        final_answer="answer" if status == AttemptStatus.COMPLETE else None,
        proof_steps=[
            ProofStep(
                step_id=f"{strategy.strategy_id}-step-{attempt_index}-{step}",
                statement=f"statement {step}",
                justification=f"justification {step}",
            )
            for step in range(steps)
        ],
        unresolved_gaps=[f"gap-{attempt_index}-{gap}" for gap in range(gaps)],
        self_confidence=0.8,
    )


def _failed_report(
    attempt: ProofAttempt,
    *,
    stage: VerificationStage,
    failure_level: FailureLevel,
    confidence: float = 0.95,
    agent_id: str = "reviewer",
) -> VerificationReport:
    return VerificationReport(
        target_id=attempt.attempt_id,
        target_type="attempt",
        agent_id=agent_id,
        stage=stage,
        verdict=VerificationVerdict.FAIL,
        issues=[
            VerificationIssue(
                phase=stage.value,
                severity=Severity.ERROR,
                step_id=attempt.proof_steps[0].step_id if attempt.proof_steps else None,
                description="The route's key implication is not established.",
            )
        ],
        failure_level=failure_level,
        confidence=confidence,
        concise_feedback="Independent verification rejected the route.",
    )


def _passed_report(attempt: ProofAttempt) -> VerificationReport:
    return VerificationReport(
        target_id=attempt.attempt_id,
        target_type="attempt",
        agent_id="system-aggregate",
        stage=VerificationStage.DETAILED,
        verdict=VerificationVerdict.PASS,
        failure_level=FailureLevel.NONE,
        confidence=0.95,
        concise_feedback="The candidate passed independent verification.",
    )


def _failed_state(failure_levels: list[FailureLevel]):
    strategies = [_strategy(index) for index in range(len(failure_levels))]
    attempts = [_attempt(strategy) for strategy in strategies]
    reports: list[VerificationReport] = []
    aggregates: dict[str, VerificationReport] = {}
    for attempt, failure_level in zip(attempts, failure_levels):
        structural = _failed_report(
            attempt,
            stage=VerificationStage.STRUCTURAL,
            failure_level=failure_level,
            agent_id=f"structural-{attempt.strategy_id}",
        )
        aggregate = _failed_report(
            attempt,
            stage=VerificationStage.DETAILED,
            failure_level=failure_level,
            agent_id="system-aggregate",
        )
        reports.extend([structural, aggregate])
        aggregates[attempt.attempt_id] = aggregate
    return strategies, attempts, reports, aggregates


def test_all_failed_paths_force_configurable_widen_and_use_max_capacity(
    demo_config,
) -> None:
    demo_config.budget.max_paths = 6
    demo_config.scheduler = SchedulerConfig(
        max_actions_per_round=2,
        widen_paths_per_action=2,
        force_widen_when_all_failed=True,
    )
    strategies, attempts, reports, aggregates = _failed_state(
        [FailureLevel.STRATEGY, FailureLevel.EXECUTION, FailureLevel.PLAN]
    )

    manager = AdaptiveBudgetManager(demo_config)
    stats = manager.build_path_stats(strategies, attempts, reports, aggregates)
    decision = manager.decide(
        stats,
        current_path_count=len(strategies),
        remaining_calls=25,
        current_round=1,
    )

    assert decision.coverage == pytest.approx(3 / 6)
    assert decision.all_evaluated_paths_failed is True
    assert decision.forced_widen is True
    assert any(action.action == ActionKind.WIDEN for action in decision.actions)
    widen = next(
        candidate
        for candidate in decision.candidates
        if candidate.action == ActionKind.WIDEN
    )
    assert widen.forced is True
    assert widen.selected is True
    assert widen.planned_paths == 2

    pool = AgentPool(demo_config)
    ledger = CallLedger(demo_config, pool)
    ledger.calls_started = demo_config.budget.max_total_calls - 25
    admitted = SoftBudgetAllocator(demo_config, ledger).admit_decision(
        decision,
        current_path_count=len(strategies),
        has_candidate=True,
        max_actions=demo_config.scheduler.max_actions_per_round,
    )
    assert any(action.action == ActionKind.WIDEN for action in admitted.actions)


def test_structural_and_strategy_failures_reduce_false_progress(demo_config) -> None:
    demo_config.budget.max_paths = 6
    strategies, attempts, reports, aggregates = _failed_state([FailureLevel.STRATEGY])

    manager = AdaptiveBudgetManager(demo_config)
    stats = manager.build_path_stats(strategies, attempts, reports, aggregates)

    assert len(stats) == 1
    stat = stats[0]
    assert stat.structurally_valid is False
    assert stat.latest_verdict == VerificationVerdict.FAIL
    assert stat.failure_level == FailureLevel.STRATEGY
    assert stat.progress <= demo_config.scheduler.strategy_failure_progress_cap

    decision = manager.decide(
        stats,
        current_path_count=1,
        remaining_calls=20,
        current_round=1,
    )
    deepen = next(
        candidate
        for candidate in decision.candidates
        if candidate.action == ActionKind.DEEPEN
    )
    assert deepen.eligible is False
    assert "switch mechanisms" in (deepen.blocked_reason or "")


def test_execution_failure_gets_one_repair_then_configurable_cooldown(
    demo_config,
) -> None:
    demo_config.scheduler = SchedulerConfig(
        max_execution_repairs_per_path=1,
        failed_path_cooldown_rounds=2,
    )
    manager = AdaptiveBudgetManager(demo_config)

    first_failure = PathStats(
        strategy_id="execution-route",
        attempt_id="attempt-1",
        latest_verdict=VerificationVerdict.FAIL,
        failure_level=FailureLevel.EXECUTION,
        failure_confidence=0.95,
        failed_repair_attempts=0,
        consecutive_failures=1,
        last_round_index=1,
        unresolved_gap_count=2,
    )
    first_decision = manager.decide(
        [first_failure],
        current_path_count=1,
        remaining_calls=20,
        current_round=2,
    )
    first_deepen = next(
        candidate
        for candidate in first_decision.candidates
        if candidate.action == ActionKind.DEEPEN
    )
    assert first_deepen.eligible is True

    repeated_failure = first_failure.model_copy(
        update={
            "attempt_id": "attempt-2",
            "failed_repair_attempts": 1,
            "consecutive_failures": 2,
            "last_round_index": 2,
        }
    )
    cooling_decision = manager.decide(
        [repeated_failure],
        current_path_count=1,
        remaining_calls=20,
        current_round=3,
    )
    cooling_deepen = next(
        candidate
        for candidate in cooling_decision.candidates
        if candidate.action == ActionKind.DEEPEN
    )
    assert cooling_deepen.eligible is False
    assert "cooling down" in (cooling_deepen.blocked_reason or "")

    revisit_decision = manager.decide(
        [repeated_failure],
        current_path_count=1,
        remaining_calls=20,
        current_round=5,
    )
    revisit_deepen = next(
        candidate
        for candidate in revisit_decision.candidates
        if candidate.action == ActionKind.DEEPEN
    )
    assert revisit_deepen.eligible is True


def test_successful_candidate_does_not_trigger_failure_widen_guarantee(
    demo_config,
) -> None:
    demo_config.budget.max_paths = 6
    strategy = _strategy(0)
    attempt = _attempt(
        strategy,
        status=AttemptStatus.COMPLETE,
        gaps=0,
    )
    report = _passed_report(attempt)
    manager = AdaptiveBudgetManager(demo_config)
    stats = manager.build_path_stats(
        [strategy],
        [attempt],
        [report],
        {attempt.attempt_id: report},
    )
    decision = manager.decide(
        stats,
        current_path_count=1,
        remaining_calls=20,
        current_round=1,
    )

    assert decision.all_evaluated_paths_failed is False
    assert decision.forced_widen is False
    assert any(action.action == ActionKind.SYNTHESIZE for action in decision.actions)


def test_widen_is_blocked_at_configured_max_paths(demo_config) -> None:
    demo_config.budget.max_paths = 3
    stats = [
        PathStats(
            strategy_id=f"strategy-{index}",
            attempt_id=f"attempt-{index}",
            latest_verdict=VerificationVerdict.FAIL,
            failure_level=FailureLevel.STRATEGY,
            failure_confidence=0.95,
        )
        for index in range(3)
    ]
    decision = AdaptiveBudgetManager(demo_config).decide(
        stats,
        current_path_count=3,
        remaining_calls=20,
        current_round=1,
    )

    widen = next(
        candidate
        for candidate in decision.candidates
        if candidate.action == ActionKind.WIDEN
    )
    assert decision.coverage == 1.0
    assert widen.eligible is False
    assert widen.selected is False
    assert "maximum path capacity" in (widen.blocked_reason or "")


def test_action_cost_and_finish_reserve_derive_from_configuration(demo_config) -> None:
    demo_config.budget.max_total_calls = 80
    demo_config.budget.max_paths = 8
    demo_config.budget.max_revisions = 4
    demo_config.continuation.enabled = True
    demo_config.continuation.segments_per_explore_call = 2
    demo_config.continuation.verify_each_delta = True
    demo_config.continuation.delta_verifier_replicas = 1
    demo_config.scheduler = SchedulerConfig(
        widen_paths_per_action=3,
        reserve_revision_cycles=2,
        verification_call_safety_margin=1,
    )
    pool = AgentPool(demo_config)
    ledger = CallLedger(demo_config, pool)
    allocator = SoftBudgetAllocator(demo_config, ledger)

    one_path = allocator.estimate_action_calls(
        ActionKind.WIDEN,
        current_path_count=3,
        widen_path_count=1,
    )
    three_paths = allocator.estimate_action_calls(
        ActionKind.WIDEN,
        current_path_count=3,
        widen_path_count=3,
    )
    synthesis_calls = allocator.estimate_action_calls(ActionKind.SYNTHESIZE)
    revision_calls = allocator.estimate_revision_cycle_calls()

    assert three_paths > one_path
    assert allocator.minimum_finish_reserve == synthesis_calls + 2 * revision_calls
    assert allocator.minimum_finish_reserve > 3


def test_budget_diagnostics_record_rank_selection_and_blocking(demo_config) -> None:
    demo_config.budget.max_paths = 4
    demo_config.scheduler.max_actions_per_round = 1
    stats = [
        PathStats(
            strategy_id="route",
            attempt_id="attempt",
            progress=0.3,
            uncertainty=0.8,
            unresolved_gap_count=2,
        )
    ]
    decision = AdaptiveBudgetManager(demo_config).decide(
        stats,
        current_path_count=1,
        remaining_calls=20,
        current_round=1,
        max_actions=1,
    )

    assert decision.candidates
    assert all(candidate.rank is not None for candidate in decision.candidates)
    assert sum(candidate.selected for candidate in decision.candidates) == 1
    assert all(
        candidate.selected or candidate.blocked_reason is not None
        for candidate in decision.candidates
        if candidate.eligible
    )


@pytest.mark.parametrize(
    "filename",
    [
        "config.deepseek-v4-pro.smoke.yaml",
        "config.deepseek-v4-pro.yaml",
        "config.deepseek-v4-pro.topology-active.yaml",
    ],
)
def test_deepseek_profiles_reserve_one_widen_and_finalization_cycle(
    filename: str,
) -> None:
    root = Path(__file__).resolve().parents[1]
    config = load_config(root / filename)

    assert config.budget.max_rounds >= 2
    assert config.budget.max_paths > config.budget.initial_paths

    segments = config.continuation.segments_per_explore_call
    explore_calls = segments
    if config.continuation.verify_each_delta:
        explore_calls += segments * config.continuation.delta_verifier_replicas
    calls_per_path = explore_calls + 1  # Claim extraction.
    verification_calls = (
        1
        + config.budget.high_risk_verifier_replicas
        + config.scheduler.verification_call_safety_margin
    )
    initially_verified = min(
        config.budget.initial_paths,
        config.budget.candidates_to_verify,
    )
    initial_calls = (
        2  # Triage and strategy generation.
        + config.budget.initial_paths * calls_per_path
        + initially_verified * verification_calls
        + 1  # Initial meta-review.
    )
    widen_paths = min(
        config.scheduler.widen_paths_per_action,
        config.budget.max_paths - config.budget.initial_paths,
    )
    widen_calls = 1 + widen_paths * calls_per_path
    if config.scheduler.include_post_action_verification_in_cost:
        widen_calls += verification_calls
    if config.scheduler.include_meta_review_in_cost:
        widen_calls += 1
    revision_cycles = min(
        config.scheduler.reserve_revision_cycles,
        config.budget.max_revisions,
    )
    finish_reserve = (1 + verification_calls) * (1 + revision_cycles)

    assert config.budget.max_total_calls - initial_calls >= (
        widen_calls + finish_reserve
    )


def test_smoke_and_formal_output_limits() -> None:
    root = Path(__file__).resolve().parents[1]
    smoke = load_config(root / "config.deepseek-v4-pro.smoke.yaml")
    formal = load_config(root / "config.deepseek-v4-pro.yaml")
    active = load_config(root / "config.deepseek-v4-pro.topology-active.yaml")

    assert all(agent.max_output_tokens == 50000 for agent in smoke.agents)
    assert smoke.continuation.max_output_tokens_per_segment == 50000
    assert all(agent.max_output_tokens == 100000 for agent in formal.agents)
    assert formal.continuation.max_output_tokens_per_segment == 100000
    assert all(agent.provider_max_output_tokens == 384000 for agent in active.agents)
    assert all(agent.max_output_tokens == 128000 for agent in active.agents)
    assert active.continuation.max_output_tokens_per_segment == 128000
    assert [item.output_tokens for item in active.deep_exploration_policy.tiers] == [
        32000,
        64000,
        96000,
        128000,
    ]
    assert [
        item.no_content_token_cutoff for item in active.deep_exploration_policy.tiers
    ] == [24000, 56000, 84000, 112000]
    assert active.deep_exploration_policy.allow_parallel_distinct_signatures is True
    assert active.budget.max_total_tokens == 10000000
    assert active.budget.max_total_calls == 150
    assert active.budget.max_rounds == 8
    assert active.budget.initial_paths == 6
    assert active.budget.max_paths == 12
    assert active.continuation.max_new_steps_per_call == 20
    assert active.continuation.max_segments_per_path == 20
    assert active.continuation.post_failure_bottleneck_enabled is True
    assert active.continuation.post_failure_bottleneck_max_output_tokens == 12000
    assert active.continuation.post_failure_bottleneck_once_per_checkpoint is True
    assert active.continuation.post_failure_trigger_inspiration is True
    assert active.runtime.stage_output_token_limits["post_failure_bottleneck"] == 12000
    assert active.computation.sandboxed_python_enabled is True
    assert smoke.continuation.segments_per_explore_call == 1
    assert formal.continuation.segments_per_explore_call == 1
    assert smoke.budget.max_revisions >= 1
    assert formal.budget.max_revisions >= 1
    assert smoke.scheduler.reserve_revision_cycles >= 1
    assert formal.scheduler.reserve_revision_cycles >= 1
