from __future__ import annotations

from collections import Counter
import re
from typing import Any

from .schemas import RunResult
from .store import ArtifactStore


def _mermaid_id(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9_]", "_", value)


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
) -> None:
    """Write the stable v0.7 topology, graph, diagnostics, and metric artifacts."""
    routes = list(route_registry.get("routes", []))
    messages = dict(message_broker.get("messages", {}))
    deliveries = dict(message_broker.get("deliveries", {}))
    decisions = list(message_broker.get("decisions", []))
    receipts = dict(message_broker.get("receipts", {}))
    obligations = dict(proof_graph.get("obligations", {}))
    edges = dict(proof_graph.get("edges", {}))
    tiers = dict(typed_memory.get("tiers", {}))

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

    published = sum(bool(item.get("accepted")) for item in decisions)
    rejected = len(decisions) - published
    duplicates = sum(bool(item.get("duplicate_of")) for item in decisions)
    acknowledged = sum(item.get("status") == "accepted" for item in receipts.values())
    cross_route_chars = sum(
        len(str(messages.get(str(item.get("message_id")), {})))
        for item in deliveries.values()
    )
    metrics = {
        "route_count": len(routes),
        "active_route_count": sum(item.get("status") == "active" for item in routes),
        "merged_route_count": sum(item.get("status") == "merged" for item in routes),
        "fact_count": sum(value == "fact" for value in tiers.values()),
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
        "messages_published": published,
        "messages_delivered": sum(
            bool(item.get("prompt_consumed")) for item in deliveries.values()
        ),
        "messages_rejected": rejected,
        "duplicate_message_rate": duplicates / max(1, len(decisions)),
        "cross_route_token_estimate": (cross_route_chars + 3) // 4,
        "message_utilization_rate": acknowledged / max(1, len(deliveries)),
        "graph_mode": proof_graph.get("mode", "off"),
        "inspiration_mode": inspiration_engine.get("mode", "off"),
        "inspiration_trigger_count": len(inspiration_engine.get("triggers", {})),
        "inspiration_proposal_count": len(inspiration_engine.get("proposals", {})),
        "inspiration_verified_count": len(
            inspiration_engine.get("verified_proposals", {})
        ),
        "surprise_budget": inspiration_engine.get("surprise_budget", {}),
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
    lines = [
        f"# {store.run_id}：MathProofMesh 运行报告",
        "",
        "## 问题",
        "",
        result.problem.exact_statement,
        "",
        f"- 完整性哈希：`{result.problem.integrity_hash}`",
        f"- 运行状态：**{result.status.value}**",
        f"- API 调用数：{result.total_calls}",
        f"- Token：{result.total_usage.total_tokens}",
        f"- 估算费用：${result.total_usage.estimated_cost_usd:.4f}",
        f"- 验证报告：{dict(verdict_counts)}",
        f"- 已提交证明检查点：{len(result.proof_checkpoints)}",
        f"- 本次是否为恢复运行：{'是' if result.resumed else '否'}",
        f"- 恢复起点：`{result.resumed_from_checkpoint_id or '无'}`",
        "- 运行时间线：`activity_timeline.md`（仅含阶段状态与结构化摘要，不含模型原始思考链）",
        "",
        "## 最终结果",
        "",
    ]
    if result.final_proof is None:
        lines.extend(["未形成可提交的最终证明。", ""])
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

    lines.extend(["## 路径与引理", ""])
    for attempt in result.attempts:
        lines.append(
            f"- `{attempt.attempt_id}` / `{attempt.strategy_id}` / {attempt.agent_id}："
            f"{attempt.status.value}，自评 {attempt.self_confidence:.2f}，"
            f"步骤 {len(attempt.proof_steps)}，证明段 {attempt.segment_count}，"
            f"未解缺口 {len(attempt.unresolved_gaps)}，"
            f"最新检查点 `{attempt.latest_checkpoint_id or '无'}`"
        )
        if attempt.failover_chain:
            lines.append(
                f"  - API-key/Agent 接力链：{' → '.join(attempt.failover_chain)}"
            )
    lines.append("")
    verified_claims = [
        claim for claim in result.claims if claim.status.value == "verified"
    ]
    lines.append(f"已验证引理数：{len(verified_claims)}")
    for claim in verified_claims:
        lines.append(f"- `{claim.claim_id}`：{claim.statement}")
    lines.append("")

    latest_checkpoint = store.latest_stage_checkpoint()
    topology_state = latest_checkpoint[1] if latest_checkpoint is not None else {}
    registry_state = topology_state.get("route_registry") or {}
    typed_memory_state = topology_state.get("typed_memory") or {}
    proof_graph_state = topology_state.get("proof_graph") or {}
    broker_state = topology_state.get("message_broker") or {}
    bridge_state = topology_state.get("bridge_broker") or {}
    conflict_state = topology_state.get("contradiction_broker") or {}
    inspiration_state = topology_state.get("inspiration_engine") or {}
    if registry_state or proof_graph_state or inspiration_state:
        routes = list(registry_state.get("routes", []))
        tiers = dict(typed_memory_state.get("tiers", {}))
        obligations = list(dict(proof_graph_state.get("obligations", {})).values())
        decisions = list(broker_state.get("decisions", []))
        deliveries = list(dict(broker_state.get("deliveries", {})).values())
        published = sum(1 for item in decisions if item.get("accepted"))
        rejected = sum(1 for item in decisions if not item.get("accepted"))
        duplicates = sum(1 for item in decisions if item.get("duplicate_of"))
        lines.extend(
            [
                "## v0.7 分层稀疏拓扑指标",
                "",
                f"- 路线：{len(routes)}（active {sum(item.get('status') == 'active' for item in routes)}；merged {sum(item.get('status') == 'merged' for item in routes)}）",
                f"- Typed Memory：Fact {sum(value == 'fact' for value in tiers.values())}；Insight {sum(value == 'insight' for value in tiers.values())}；Negative {sum(value == 'negative' for value in tiers.values())}",
                f"- Proof Obligation：open {sum(item.get('status') != 'closed' for item in obligations)}；closed {sum(item.get('status') == 'closed' for item in obligations)}",
                f"- Bridge tasks：{len(bridge_state.get('tasks', []))}；Contradictions：{len(conflict_state.get('records', []))}",
                f"- Typed messages：published {published}；delivered {len(deliveries)}；rejected {rejected}；duplicate rate {(duplicates / max(1, len(decisions))):.3f}",
                f"- Inspiration：triggers {len(inspiration_state.get('triggers', {}))}；proposals {len(inspiration_state.get('proposals', {}))}；materialized {len(inspiration_state.get('materializations', {}))}",
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
            f"失败 {metric.failures}"
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
