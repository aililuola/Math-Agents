from __future__ import annotations

from collections import Counter

from .schemas import RunResult
from .store import ArtifactStore


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
    verdict_counts = Counter(report.verdict.value for report in result.verification_reports)
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
            f"步骤 {len(attempt.proof_steps)}，未解缺口 {len(attempt.unresolved_gaps)}"
        )
    lines.append("")
    verified_claims = [claim for claim in result.claims if claim.status.value == "verified"]
    lines.append(f"已验证引理数：{len(verified_claims)}")
    for claim in verified_claims:
        lines.append(f"- `{claim.claim_id}`：{claim.statement}")
    lines.append("")

    lines.extend(
        [
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
