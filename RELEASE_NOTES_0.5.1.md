# MathProofMesh 0.5.1

## 调度器正确性修复

本补丁修复“初始路线全部失败后仍连续深挖旧路线、没有生成新机制，并耗尽最终修订预算”的通用调度缺陷。它不针对某一道题绑定路线数或调用数；所有容量、修补和储备参数均可配置。

### 主要变化

- 覆盖率从 `current_paths / initial_paths` 修正为 `current_paths / max_paths`。
- 将结构审查和最终聚合报告同时接入路径统计，结构失败不再表现为 `structurally_valid=None`。
- 证明步数和候选引理只构成原始草稿进展；高置信度 FAIL、结构失败、计划失败和策略失败会折扣或限制其有效进展。
- 执行级、计划级、策略级和未知失败分别使用可配置修补上限；重复失败进入可配置冷却。
- 当所有已探索路线均被审查为 FAIL、仍有路线容量且预算允许时，强制在本轮保留一个 `widen`。
- `max_actions_per_round`、`widen_paths_per_action`、修补次数、冷却轮次和最终修订周期全部进入 `scheduler` 配置。
- 动作成本按实际拓宽批次、分段续推、Delta Reviewer、Claim 提取、候选验证和 Meta-review 动态估算；预算不足时可缩小拓宽批次。
- 最终储备包含综合与终审，以及配置数量的“修订 + 重新审计”完整周期。
- 调度 JSON 和 Activity 详细模式记录所有候选动作的分数、排名、预计成本、未选原因和预算阻断原因。
- 分段续推产生的新一轮 `ProofAttempt` 使用新的 Attempt ID，避免历史验证报告被同一 ID 覆盖。

## 兼容性

- 不改变 DeepSeek SSE、检查点恢复和跨 key 接力协议。
- 旧配置没有 `scheduler` 段时使用安全默认值。
- 通用配置默认仍可关闭 continuation；正式 DeepSeek 配置继续启用 V4 Pro、`reasoning_effort=max` 和 SSE。

## 验证

- `PYTHONPATH=src python -m pytest -q`：48 passed。
- `python -m ruff check src tests`：PASS。
- `python -m ruff format --check src tests`：PASS。
- `python -m compileall -q src tests`：PASS。
- 未调用真实 DeepSeek API，未提交 API key、运行目录或构建产物。
