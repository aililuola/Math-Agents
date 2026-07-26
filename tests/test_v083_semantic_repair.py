from __future__ import annotations

from mathproofmesh.config import RouteAdmissionControlConfig
from mathproofmesh.proof_control.gates import RouteAdmissionGate
from mathproofmesh.proof_control.models import (
    ClaimGoalLink,
    GoalRelation,
    ScopeRelation,
    TaskStatus,
    WakeConditionKind,
)
from mathproofmesh.proof_control.tasks import ExecutableTaskController, WakeScheduler

from v082_helpers import make_control_runtime, make_domain_strategy


def _link(scope: ScopeRelation) -> ClaimGoalLink:
    return ClaimGoalLink(
        link_id=f"link-{scope.value}",
        subject_id="strategy-x",
        subject_kind="strategy",
        target_obligation_id="goal-g",
        relation=GoalRelation.SUFFICIENT,
        scope_relation=scope,
        alignment_confidence=0.9,
        assessment_source="deterministic",
    )


def test_lemma_weaker_scores_above_claim_stronger() -> None:
    same = RouteAdmissionGate._alignment_score(_link(ScopeRelation.SAME))
    weaker = RouteAdmissionGate._alignment_score(_link(ScopeRelation.CLAIM_WEAKER))
    stronger = RouteAdmissionGate._alignment_score(_link(ScopeRelation.CLAIM_STRONGER))
    incomparable = RouteAdmissionGate._alignment_score(
        _link(ScopeRelation.INCOMPARABLE)
    )
    assert weaker == same
    assert weaker > stronger > incomparable


def test_normalization_backlog_lists_unparseable_statements(tmp_path) -> None:
    *_runtime, control, _main_goal = make_control_runtime(tmp_path)
    strategy = make_domain_strategy(
        strategy_id="strategy-backlog",
        expected_lemmas=[
            "素数因子的分布结构",
            "Every admissible object has a canonical decomposition.",
        ],
    )
    control.admit_routes([strategy])

    backlog = control.normalization_backlog([strategy])

    assert any("素数因子" in item["statement"] for item in backlog)
    assert all(item["needs"] for item in backlog)


def test_apply_normalized_statements_rewrites_only_matches() -> None:
    strategy = make_domain_strategy(
        strategy_id="strategy-apply",
        expected_lemmas=["素数因子的分布结构", "Keep this lemma unchanged."],
    )
    repaired = ExecutableTaskController  # silence unused-import style checks
    del repaired
    from mathproofmesh.proof_control.controller import ProofControlLayer

    updated = ProofControlLayer.apply_normalized_statements(
        [strategy],
        {"素数因子的分布结构": "对任意正整数 n，a_n 的素因子集合是有限集。"},
    )

    assert updated[0].expected_lemmas[0].startswith("对任意正整数")
    assert updated[0].expected_lemmas[1] == "Keep this lemma unchanged."
    assert updated[0].strategy_id == strategy.strategy_id


def test_semantic_repair_task_has_no_expiry_and_terminal_reason() -> None:
    controller = ExecutableTaskController({})
    task = controller.create_semantic_repair_task(
        target_obligation_ids=["node-1", "node-2"],
        strategy_ids=["strategy-a"],
        created_round=0,
    )
    assert task.status == TaskStatus.READY
    assert task.expires_round is None
    again = controller.create_semantic_repair_task(
        target_obligation_ids=["node-1", "node-2"],
        strategy_ids=["strategy-a"],
        created_round=3,
    )
    assert again.task_id == task.task_id

    controller.mark_running(task.task_id, current_round=0)
    done = controller.complete_work(
        task.task_id,
        current_round=0,
        result_refs=["normalized:2"],
        reason="batched_normalization_completed",
    )
    assert done.terminal_reason


def test_semantic_repair_budget_contract_default_config() -> None:
    cfg = RouteAdmissionControlConfig()
    total_calls = 48
    cap = min(
        cfg.max_semantic_repair_calls,
        int(cfg.semantic_repair_budget_fraction * total_calls),
    )
    assert cfg.semantic_repair_enabled
    assert 1 <= cap <= total_calls * 0.1
    assert cfg.blueprint_review.max_review_calls_per_round <= cap
    assert cfg.blueprint_review.max_nodes_per_batch == 12
    assert cfg.blueprint_review.max_repair_rounds == 2


def test_review_task_is_idempotent_and_never_expires_for_large_batch() -> None:
    controller = ExecutableTaskController({})
    obligation_ids = [f"node-{index}" for index in range(54)]

    task = controller.create_admission_review_task(
        task_kind="batch_repair",
        target_obligation_ids=obligation_ids,
        strategy_ids=["strategy-a"],
        created_round=0,
        assigned_agent_id="reviewer-a",
        prompt_ref="prompt:normalize",
    )
    again = controller.create_admission_review_task(
        task_kind="batch_repair",
        target_obligation_ids=list(reversed(obligation_ids)),
        strategy_ids=["strategy-a"],
        created_round=9,
        assigned_agent_id="reviewer-b",
        prompt_ref="prompt:normalize",
    )

    assert task.task_id == again.task_id
    assert task.status == TaskStatus.READY
    assert task.expires_round is None


def test_round_advanced_wakes_quota_deferred_review_task() -> None:
    controller = ExecutableTaskController({})
    task = controller.create_admission_review_task(
        task_kind="edge_review",
        target_obligation_ids=["node-1"],
        strategy_ids=["strategy-a"],
        created_round=0,
        prompt_ref="prompt:edge-review",
    )
    controller.defer(
        task.task_id,
        current_round=0,
        reason="per_round_review_quota_exhausted",
        wake_kind=WakeConditionKind.ROUND_ADVANCED,
    )

    assert WakeScheduler(controller.tasks).evaluate(current_round=0) == []
    assert WakeScheduler(controller.tasks).evaluate(current_round=1) == [task]
    assert task.status == TaskStatus.READY


def test_claim_weaker_materializes_open_bridge_obligation(tmp_path) -> None:
    *_runtime, control, main_goal = make_control_runtime(tmp_path)
    strategy = make_domain_strategy(strategy_id="strategy-weaker")
    link = ClaimGoalLink(
        link_id="link-weaker-bridge",
        subject_id=strategy.strategy_id,
        subject_kind="strategy",
        target_obligation_id=main_goal.obligation_id,
        relation=GoalRelation.SUFFICIENT,
        scope_relation=ScopeRelation.CLAIM_WEAKER,
        alignment_confidence=0.9,
        assessment_source="deterministic",
    )

    registered = control._register_goal_link(strategy, link)

    assert registered.required_bridge_obligation_ids
    bridge = control.proof_graph.get_obligation(
        registered.required_bridge_obligation_ids[0]
    )
    assert bridge.status == "open"
    assert "then" in bridge.statement.casefold()
    assert bridge.obligation_id in registered.remaining_obligation_ids_if_proved
