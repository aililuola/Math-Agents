from __future__ import annotations

from mathproofmesh.budget import AdaptiveBudgetManager
from mathproofmesh.schemas import ActionKind, PathStats, VerificationVerdict

from v07_helpers import make_v07_config


def _stats(**updates) -> PathStats:
    values = {
        "strategy_id": "strategy-a",
        "attempt_id": "attempt-a",
        "progress": 0.4,
        "marginal_progress": 0.05,
        "uncertainty": 0.5,
        "verification_score": 0.5,
        "latest_verdict": VerificationVerdict.UNCERTAIN,
        "proof_debt": 4.0,
        "proof_debt_reduction": 1.0,
        "verified_fact_gain": 1,
        "shared_obligation_count": 2,
        "high_centrality_obligation_count": 1,
        "bridge_opportunity": 1.0,
        "stagnation_rounds": 2,
        "inspiration_trigger_count": 1,
        "novelty_score": 0.9,
        "representation_diversity": 0.8,
        "construction_opportunity": 1.0,
        "surprise_budget_remaining": 2,
    }
    values.update(updates)
    return PathStats(**values)


def test_active_graph_and_inspiration_actions_are_schedulable(tmp_path) -> None:
    config = make_v07_config(tmp_path / "runs")
    decision = AdaptiveBudgetManager(config).decide(
        [_stats()], current_path_count=2, remaining_calls=30, current_round=2
    )
    candidates = {item.action: item for item in decision.candidates}
    assert candidates[ActionKind.BRIDGE].eligible
    assert candidates[ActionKind.TRIGGER_INSPIRATION].eligible
    assert candidates[ActionKind.SWITCH_REPRESENTATION].eligible
    assert candidates[ActionKind.SURPRISE_WIDEN].eligible


def test_shadow_modes_record_but_cannot_change_scheduling(tmp_path) -> None:
    config = make_v07_config(
        tmp_path / "runs", graph_mode="shadow", inspiration_mode="shadow"
    )
    decision = AdaptiveBudgetManager(config).decide(
        [_stats()], current_path_count=2, remaining_calls=30, current_round=2
    )
    candidates = {item.action: item for item in decision.candidates}
    assert not candidates[ActionKind.BRIDGE].eligible
    assert "shadow" in (candidates[ActionKind.BRIDGE].blocked_reason or "")
    assert not candidates[ActionKind.TRIGGER_INSPIRATION].eligible
    assert "shadow" in (candidates[ActionKind.TRIGGER_INSPIRATION].blocked_reason or "")
