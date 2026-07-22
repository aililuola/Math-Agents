from __future__ import annotations

from collections import Counter
import re
from typing import Any

from .schemas import RunResult
from .store import ArtifactStore


def _mermaid_id(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9_]", "_", value)


def _broker_admitted_fact_payloads(
    message_broker: dict[str, Any],
    typed_memory: dict[str, Any],
) -> list[dict[str, Any]]:
    """Intersect Broker-authorized IDs with the persisted live TypedMemory facts."""

    broker_messages = dict(message_broker.get("messages", {}))
    typed_messages = dict(typed_memory.get("messages", {}))
    tiers = dict(typed_memory.get("tiers", {}))
    admitted_ids = set(message_broker.get("admitted_fact_ids", []))
    admitted: list[dict[str, Any]] = []
    for message_id, message in broker_messages.items():
        if message_id not in admitted_ids:
            continue
        typed_message = typed_messages.get(message_id)
        if not isinstance(message, dict) or not isinstance(typed_message, dict):
            continue
        if message.get("memory_tier") != "fact":
            continue
        if message.get("verification_status") != "verified":
            continue
        if tiers.get(message_id) != "fact":
            continue
        if typed_message.get("content_hash") != message.get("content_hash"):
            continue
        admitted.append(message)
    return admitted


def _report_component_state(
    store: ArtifactStore,
    checkpoint_state: dict[str, Any],
    name: str,
) -> dict[str, Any]:
    value = checkpoint_state.get(name)
    if isinstance(value, dict):
        return value
    if store.has_named_json("structured", name):
        try:
            persisted = store.read_named_json("structured", name)
        except (OSError, ValueError):
            return {}
        if isinstance(persisted, dict):
            return persisted
    return {}


def write_hierarchical_reports(
    store: ArtifactStore,
    *,
    route_registry: dict[str, Any],
    message_broker: dict[str, Any],
    proof_graph: dict[str, Any],
    typed_memory: dict[str, Any],
    bridge_broker: dict[str, Any],
    contradiction_broker: dict[str, Any],
    inspiration_engine: dict[str, Any],
    deep_exploration: dict[str, Any] | None = None,
    legacy_claims: list[dict[str, Any]] | None = None,
) -> None:
    """Write the stable v0.7 topology, graph, diagnostics, and metric artifacts."""
    routes = list(route_registry.get("routes", []))
    messages = dict(message_broker.get("messages", {}))
    deliveries = dict(message_broker.get("deliveries", {}))
    decisions = list(message_broker.get("decisions", []))
    receipts = dict(message_broker.get("receipts", {}))
    utility_records = dict(message_broker.get("utility_records", {}))
    obligations = dict(proof_graph.get("obligations", {}))
    edges = dict(proof_graph.get("edges", {}))
    tiers = dict(typed_memory.get("tiers", {}))
    deep_exploration = deep_exploration or {}
    legacy_claims = legacy_claims or []
    admitted_facts = _broker_admitted_fact_payloads(message_broker, typed_memory)
    typed_fact_candidates = [
        message_id for message_id, tier in tiers.items() if tier == "fact"
    ]

    store.write_json(
        "reports",
        "global_fact_inventory",
        {
            "broker_admitted_global_facts": admitted_facts,
            "typed_fact_candidate_ids": typed_fact_candidates,
            "legacy_claim_history": legacy_claims,
            "policy": (
                "Only the Broker-admitted and independently reviewed TypedMemory "
                "intersection is globally admissible in hierarchical_sparse."
            ),
        },
    )

    topology = {
        "routes": routes,
        "messages": messages,
        "deliveries": deliveries,
        "receipts": receipts,
    }
    store.write_json("reports", "communication_topology", topology)
    topology_mmd = ["flowchart LR"]
    for route in routes:
        route_id = str(route["route_id"])
        topology_mmd.append(f'  {_mermaid_id(route_id)}["{route_id}"]')
        for neighbor in route.get("neighbor_route_ids", []):
            topology_mmd.append(
                f"  {_mermaid_id(route_id)} --> {_mermaid_id(str(neighbor))}"
            )
    for delivery in deliveries.values():
        message = messages.get(str(delivery.get("message_id")), {})
        source = str(message.get("source_route_id", "unknown"))
        target = str(delivery.get("target_route_id", "unknown"))
        label = str(message.get("message_type", "message"))
        topology_mmd.append(
            f'  {_mermaid_id(source)} -. "{label}" .-> {_mermaid_id(target)}'
        )
    store.write_text(
        "reports", "communication_topology", "\n".join(topology_mmd), suffix=".mmd"
    )

    store.write_json("reports", "proof_graph", proof_graph)
    store.write_json("reports", "deep_exploration", deep_exploration)
    graph_mmd = ["flowchart TD"]
    for obligation_id, obligation in obligations.items():
        label = str(obligation.get("status", "open"))
        graph_mmd.append(
            f'  {_mermaid_id(str(obligation_id))}["{obligation_id}: {label}"]'
        )
    for edge in edges.values():
        graph_mmd.append(
            f"  {_mermaid_id(str(edge['source_id']))} -->|{edge['edge_type']}| "
            f"{_mermaid_id(str(edge['target_id']))}"
        )
    store.write_text("reports", "proof_graph", "\n".join(graph_mmd), suffix=".mmd")

    diagnostic_lines = ["# Typed Message Diagnostics", ""]
    if not decisions:
        diagnostic_lines.append("No typed messages have been evaluated.")
    for item in decisions:
        status = "accepted" if item.get("accepted") else "rejected"
        reason = item.get("rejection_reason") or "gate passed"
        diagnostic_lines.append(
            f"- `{item.get('message_id', 'unknown')}`: **{status}** - {reason}"
        )
    store.write_text(
        "reports", "message_diagnostics", "\n".join(diagnostic_lines), suffix=".md"
    )

    publication_attempts = len(decisions)
    published = len(messages)
    rejected = sum(not bool(item.get("accepted")) for item in decisions)
    duplicates = sum(bool(item.get("duplicate_of")) for item in decisions)
    acknowledged = sum(item.get("status") == "accepted" for item in receipts.values())
    consumed = sum(bool(item.get("prompt_consumed")) for item in deliveries.values())
    mathematically_used = len(utility_records)
    materialization_counts = Counter(
        str(item.get("action", "unknown"))
        for item in dict(inspiration_engine.get("materializations", {})).values()
    )
    proposals = dict(inspiration_engine.get("proposals", {}))
    candidate_decisions = dict(inspiration_engine.get("candidate_decisions", {}))
    call_reservations = dict(inspiration_engine.get("call_reservations", {}))
    credit_targets = dict(inspiration_engine.get("credit_targets", {}))
    inspiration_outcomes = dict(inspiration_engine.get("outcomes", {}))
    meta_directives = dict(inspiration_engine.get("meta_directives", {}))
    meta_executions = dict(inspiration_engine.get("meta_directive_executions", {}))
    frontier_states = dict(inspiration_engine.get("frontier_states", {}))
    compositions = dict(inspiration_engine.get("compositions", {}))
    cross_run_learning = dict(inspiration_engine.get("cross_run_learning", {}))
    context_mode_counts = Counter(
        str(item.get("context_mode", "local")) for item in proposals.values()
    )
    cross_route_chars = sum(
        len(str(messages.get(str(item.get("message_id")), {})))
        for item in deliveries.values()
    )
    metrics = {
        "route_count": len(routes),
        "active_route_count": sum(item.get("status") == "active" for item in routes),
        "merged_route_count": sum(item.get("status") == "merged" for item in routes),
        # Keep fact_count for metric compatibility, but make its semantics the
        # v0.7 global Fact gate rather than a raw TypedMemory tier count.
        "fact_count": len(admitted_facts),
        "broker_admitted_global_fact_count": len(admitted_facts),
        "typed_fact_candidate_count": len(typed_fact_candidates),
        "legacy_claim_history_count": len(legacy_claims),
        "legacy_verified_claim_history_count": sum(
            item.get("status") == "verified" for item in legacy_claims
        ),
        "insight_count": sum(value == "insight" for value in tiers.values()),
        "negative_count": sum(value == "negative" for value in tiers.values()),
        "open_obligation_count": sum(
            item.get("status") != "closed" for item in obligations.values()
        ),
        "closed_obligation_count": sum(
            item.get("status") == "closed" for item in obligations.values()
        ),
        "shared_bottleneck_count": sum(
            len(set(item.get("route_ids", []))) >= 2 and item.get("status") != "closed"
            for item in obligations.values()
        ),
        "contradiction_count": len(contradiction_broker.get("records", [])),
        "bridge_task_count": len(bridge_broker.get("tasks", [])),
        "message_publication_attempts": publication_attempts,
        "messages_published_unique": published,
        "delivery_records": len(deliveries),
        "messages_consumed": consumed,
        "messages_semantically_accepted": acknowledged,
        "messages_mathematically_used": mathematically_used,
        "messages_rejected": rejected,
        "duplicate_message_rate": duplicates / max(1, len(decisions)),
        "cross_route_token_estimate": (cross_route_chars + 3) // 4,
        "message_consumption_rate": consumed / max(1, len(deliveries)),
        "message_semantic_acceptance_rate": acknowledged / max(1, consumed),
        "message_mathematical_use_rate": mathematically_used / max(1, consumed),
        "graph_mode": proof_graph.get("mode", "off"),
        "inspiration_mode": inspiration_engine.get("mode", "off"),
        "inspiration_trigger_count": len(inspiration_engine.get("triggers", {})),
        "inspiration_proposal_count": len(proposals),
        "inspiration_proposal_context_modes": dict(context_mode_counts),
        "inspiration_candidates_selected_for_review": sum(
            bool(item.get("selected_for_review"))
            for item in candidate_decisions.values()
        ),
        "inspiration_candidates_filtered_before_review": sum(
            not bool(item.get("selected_for_review"))
            for item in candidate_decisions.values()
        ),
        "inspiration_call_budget_reserved": sum(
            int(item.get("reserved_calls", 0)) for item in call_reservations.values()
        ),
        "inspiration_call_budget_consumed": sum(
            int(item.get("consumed_calls", 0)) for item in call_reservations.values()
        ),
        "inspiration_call_budget_released": sum(
            int(item.get("released_calls", 0)) for item in call_reservations.values()
        ),
        "inspiration_call_budget_overrun": sum(
            int(item.get("overrun_calls", 0)) for item in call_reservations.values()
        ),
        "inspiration_verified_count": len(
            inspiration_engine.get("verified_proposals", {})
        ),
        "inspiration_outcome_count": len(inspiration_outcomes),
        "inspiration_outcome_reward_total": sum(
            float(item.get("reward", 0.0)) for item in inspiration_outcomes.values()
        ),
        "inspiration_outcome_final_citations": sum(
            bool(item.get("cited_by_final_proof"))
            for item in inspiration_outcomes.values()
        ),
        "inspiration_credit_target_count": len(credit_targets),
        "inspiration_credited_message_count": len(
            {
                str(message_id)
                for target in credit_targets.values()
                for message_id in target.get("message_ids", [])
            }
        ),
        "meta_directive_count": len(meta_directives),
        "meta_directive_executed_count": sum(
            item.get("status") == "executed" for item in meta_executions.values()
        ),
        "verified_experience_count": len(
            inspiration_engine.get("verified_experiences", {})
        ),
        "negative_analogy_record_count": len(
            inspiration_engine.get("negative_analogy_records", {})
        ),
        "bidirectional_frontier_count": len(frontier_states),
        "frontier_bridge_candidate_count": sum(
            len(item.get("bridge_candidates", [])) for item in frontier_states.values()
        ),
        "inspiration_composition_count": len(compositions),
        "pending_inspiration_composition_count": len(
            inspiration_engine.get("pending_composed_proposals", {})
        ),
        "quick_falsification_pass_count": len(
            inspiration_engine.get("quick_falsification_passed", [])
        ),
        "controlled_mutation_proposal_count": sum(
            item.get("mutation") is not None for item in proposals.values()
        ),
        "controlled_mutation_directive_count": len(
            inspiration_engine.get("mutation_directives", {})
        ),
        "domain_operator_catalog_selection_count": len(
            inspiration_engine.get("domain_operator_selections", {})
        ),
        "domain_operator_proposal_count": sum(
            bool(
                (item.get("representation") or {}).get("operator_id")
                or (item.get("construction") or {}).get("operator_id")
                or (item.get("mutation") or {}).get("operator_id")
            )
            for item in proposals.values()
        ),
        "cross_run_learning_enabled": bool(cross_run_learning.get("enabled")),
        "cross_run_loaded_experience_count": len(
            cross_run_learning.get("loaded_experience_ids", [])
        ),
        "cross_run_loaded_negative_count": len(
            cross_run_learning.get("loaded_negative_ids", [])
        ),
        "inspiration_materialization_actions": dict(materialization_counts),
        "surprise_budget": inspiration_engine.get("surprise_budget", {}),
        "deep_exploration_attempt_count": len(deep_exploration.get("attempts", {})),
        "deep_exploration_locked_signature_count": len(
            deep_exploration.get("locked_signatures", {})
        ),
        "deep_exploration_pivot_count": len(deep_exploration.get("pivots", {})),
        "parallel_distinct_deep_signatures_allowed": True,
    }
    store.write_json("reports", "hierarchical_metrics", metrics)


def _proof_steps_markdown(steps) -> str:
    lines: list[str] = []
    for index, step in enumerate(steps, start=1):
        marker = " **[关键步骤]**" if step.is_key_step else ""
        lines.append(f"{index}. **{step.step_id}**{marker}: {step.statement}")
        lines.append(f"   - 依据：{step.justification}")
        if step.dependencies:
            lines.append(f"   - 依赖：{', '.join(step.dependencies)}")
        if step.calculations:
            lines.append("   - 计算：" + "；".join(step.calculations))
    return "\n".join(lines)


def write_run_report(store: ArtifactStore, result: RunResult) -> str:
    verdict_counts = Counter(
        report.verdict.value for report in result.verification_reports
    )
    working_checkpoints = store.list_working_checkpoints()
    lines = [
        f"# {store.run_id}：MathProofMesh 运行报告",
        "",
        "## 问题",
        "",
        result.problem.exact_statement,
        "",
        f"- 完整性哈希：`{result.problem.integrity_hash}`",
        f"- 运行状态：**{result.status.value}**",
        f"- 数学状态：**{result.math_status.value}**",
        f"- 执行状态：**{result.execution_status.value}**",
        f"- API 调用数：{result.total_calls}",
        f"- Token：{result.total_usage.total_tokens}",
        f"- 估算费用：${result.total_usage.estimated_cost_usd:.4f}",
        f"- 验证报告：{dict(verdict_counts)}",
        f"- 已提交证明检查点：{len(result.proof_checkpoints)}",
        f"- 路线私有 Working checkpoint：{len(working_checkpoints)}",
        f"- 本次是否为恢复运行：{'是' if result.resumed else '否'}",
        f"- 恢复起点：`{result.resumed_from_checkpoint_id or '无'}`",
        "- 运行时间线：`activity_timeline.md`（仅含阶段状态与结构化摘要，不含模型原始思考链）",
        "",
        "## 最终结果",
        "",
    ]
    if result.final_proof is None or result.math_status.value != "verified":
        lines.extend(["未形成可提交的最终证明。", ""])
        if result.final_proof is not None:
            lines.extend(
                [
                    "已保留一份尚未通过最终独立审计的候选草稿；该草稿不作为答案或全局事实。",
                    "",
                ]
            )
        if result.research_progress_report is not None:
            progress = result.research_progress_report
            lines.extend(
                [
                    "### 研究进展报告",
                    "",
                    progress.summary,
                    "",
                    f"- 有效局部路线：{len(progress.valid_partial_attempt_ids)}",
                    f"- 已审查步骤：{len(progress.verified_step_ids)}",
                    f"- 已反驳路线：{len(progress.refuted_routes)}",
                    f"- 开放义务：{len(progress.open_obligations)}",
                    f"- 剩余缺口：{len(progress.remaining_gaps)}",
                    "",
                ]
            )
    else:
        lines.extend(
            [
                result.final_proof.answer,
                "",
                "### 可审计证明步骤",
                "",
                _proof_steps_markdown(result.final_proof.proof_steps),
                "",
            ]
        )
        if result.final_proof.caveats:
            lines.extend(["### 保留意见", ""])
            lines.extend(f"- {item}" for item in result.final_proof.caveats)
            lines.append("")

    if result.final_verification is not None:
        lines.extend(
            [
                "## 最终独立验证",
                "",
                f"- 结论：**{result.final_verification.verdict.value}**",
                f"- 置信度：{result.final_verification.confidence:.3f}",
                f"- 首个错误步骤：{result.final_verification.first_error_step or '无'}",
                f"- 反馈：{result.final_verification.concise_feedback}",
                "",
            ]
        )
        for issue in result.final_verification.issues:
            lines.append(
                f"- [{issue.severity.value}] {issue.step_id or issue.claim_id or '整体'}：{issue.description}"
            )
        lines.append("")

    latest_checkpoint = store.latest_stage_checkpoint()
    topology_state = latest_checkpoint[1] if latest_checkpoint is not None else {}
    registry_state = _report_component_state(store, topology_state, "route_registry")
    typed_memory_state = _report_component_state(store, topology_state, "typed_memory")
    proof_graph_state = _report_component_state(store, topology_state, "proof_graph")
    broker_state = _report_component_state(store, topology_state, "message_broker")
    bridge_state = _report_component_state(store, topology_state, "bridge_broker")
    conflict_state = _report_component_state(
        store, topology_state, "contradiction_broker"
    )
    inspiration_state = _report_component_state(
        store, topology_state, "inspiration_engine"
    )
    hierarchical_report = any(
        (
            registry_state,
            typed_memory_state,
            proof_graph_state,
            broker_state,
            inspiration_state,
        )
    )
    admitted_facts = _broker_admitted_fact_payloads(
        broker_state,
        typed_memory_state,
    )

    lines.extend(["## 路径与事实资格", ""])
    for attempt in result.attempts:
        lines.append(
            f"- `{attempt.attempt_id}` / `{attempt.strategy_id}` / {attempt.agent_id}："
            f"{attempt.status.value}，"
            f"步骤 {len(attempt.proof_steps)}，证明段 {attempt.segment_count}，"
            f"未解缺口 {len(attempt.unresolved_gaps)}，"
            f"最新检查点 `{attempt.latest_checkpoint_id or '无'}`"
        )
        if attempt.failover_chain:
            lines.append(
                f"  - API-key/Agent 接力链：{' → '.join(attempt.failover_chain)}"
            )
    lines.append("")
    if hierarchical_report:
        lines.append(f"Broker 准入的全局 Fact 数：{len(admitted_facts)}")
        for fact in admitted_facts:
            lines.append(
                f"- `{fact.get('message_id', 'unknown')}`：{fact.get('statement', '')}"
            )
        lines.extend(
            [
                "",
                f"Legacy ClaimMemory 历史记录数：{len(result.claims)}",
                "以下记录仅用于迁移与审计；即使旧状态为 verified，也未自动获得 v0.7 全局事实资格，不得进入 hierarchical 证明提示词。",
            ]
        )
        for claim in result.claims:
            lines.append(
                f"- `{claim.claim_id}` [{claim.status.value}]：{claim.statement}"
            )
    else:
        verified_claims = [
            claim for claim in result.claims if claim.status.value == "verified"
        ]
        lines.append(f"已验证引理数：{len(verified_claims)}")
        for claim in verified_claims:
            lines.append(f"- `{claim.claim_id}`：{claim.statement}")
    lines.append("")

    if registry_state or proof_graph_state or inspiration_state:
        routes = list(registry_state.get("routes", []))
        tiers = dict(typed_memory_state.get("tiers", {}))
        obligations = list(dict(proof_graph_state.get("obligations", {})).values())
        decisions = list(broker_state.get("decisions", []))
        deliveries = list(dict(broker_state.get("deliveries", {})).values())
        messages = dict(broker_state.get("messages", {}))
        receipts = dict(broker_state.get("receipts", {}))
        utility_records = dict(broker_state.get("utility_records", {}))
        published = len(messages)
        rejected = sum(1 for item in decisions if not item.get("accepted"))
        duplicates = sum(1 for item in decisions if item.get("duplicate_of"))
        consumed = sum(bool(item.get("prompt_consumed")) for item in deliveries)
        semantic_accepts = sum(
            item.get("status") == "accepted" for item in receipts.values()
        )
        materializations = dict(inspiration_state.get("materializations", {}))
        materialization_counts = Counter(
            str(item.get("action", "unknown")) for item in materializations.values()
        )
        lines.extend(
            [
                "## v0.7 分层稀疏拓扑指标",
                "",
                f"- 路线：{len(routes)}（active {sum(item.get('status') == 'active' for item in routes)}；merged {sum(item.get('status') == 'merged' for item in routes)}）",
                f"- 全局事实：Broker-admitted Fact {len(admitted_facts)}；Typed Fact candidates {sum(value == 'fact' for value in tiers.values())}；Legacy Claim history {len(result.claims)}",
                f"- Typed Memory：Insight {sum(value == 'insight' for value in tiers.values())}；Negative {sum(value == 'negative' for value in tiers.values())}",
                f"- Proof Obligation：open {sum(item.get('status') != 'closed' for item in obligations)}；closed {sum(item.get('status') == 'closed' for item in obligations)}",
                f"- Bridge tasks：{len(bridge_state.get('tasks', []))}；Contradictions：{len(conflict_state.get('records', []))}",
                f"- Typed messages：publication attempts {len(decisions)}；unique published {published}；delivery records {len(deliveries)}；consumed {consumed}；semantically accepted {semantic_accepts}；mathematically used {len(utility_records)}；rejected {rejected}；duplicate rate {(duplicates / max(1, len(decisions))):.3f}",
                f"- Inspiration：triggers {len(inspiration_state.get('triggers', {}))}；proposals {len(inspiration_state.get('proposals', {}))}；actions {dict(materialization_counts)}；verified {len(inspiration_state.get('verified_proposals', {}))}",
                "- 跨路线只传递门控后的结构化数学对象；上述计数不包含原始思考链。",
                "",
            ]
        )

    lines.extend(["## 检查点与恢复", ""])
    if not result.proof_checkpoints:
        lines.append("本次运行未启用或未形成证明步骤级检查点。")
    else:
        by_path: dict[str, list] = {}
        for checkpoint in result.proof_checkpoints:
            by_path.setdefault(checkpoint.path_id, []).append(checkpoint)
        for path_id, checkpoints in sorted(by_path.items()):
            latest = max(checkpoints, key=lambda item: item.segment_index)
            lines.append(
                f"- `{path_id}`：{len(checkpoints)} 个已提交检查点；"
                f"最新为 `{latest.checkpoint_id}`（第 {latest.segment_index} 段，"
                f"完成={latest.proof_complete}）"
            )
    lines.extend(
        [
            "",
            "断线时，未完成的 SSE/JSON 不会进入事实库；系统只从最近一个已验证并提交的检查点重试或切换备用 Agent。",
            "",
            "## 运行时间线",
            "",
            "- 实时事件：`activity.jsonl`",
            "- 可读时间线：`reports/activity_timeline.md`",
            "- 结构化时间线：`reports/activity_timeline.json`",
            "- 时间线只包含阶段状态、Agent 标识与结构化结果摘要，不包含模型原始私有思考链。",
            "",
        ]
    )

    lines.extend(["## Agent 使用情况", ""])
    for metric in result.agent_metrics:
        lines.append(
            f"- {metric.agent_id}：调用 {metric.calls}，token {metric.usage.total_tokens}，"
            f"估算费用 ${metric.usage.estimated_cost_usd:.4f}，信任分 {metric.trust_score:.3f}，"
            f"成功响应 {metric.successful_responses}，失败尝试 {metric.failed_attempts}，"
            f"失败分类 {metric.failure_categories}"
        )
    lines.append("")
    lines.extend(
        [
            "## 说明",
            "",
            "“verified”表示通过本系统配置的独立结构验证和逐步验证；它不是形式化证明助手的绝对保证。",
            "研究级开放问题仍应接受领域专家或 Lean/Rocq/Isabelle 等形式系统的进一步核验。",
            "",
        ]
    )
    return store.write_text("reports", "run_report", "\n".join(lines), suffix=".md")
