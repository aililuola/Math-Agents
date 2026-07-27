# MathProofMesh v0.8.5 Common-Mode 与恢复闭环修复映射

## 范围与不变量

本修复只补足通用控制闭环，不改变数学对象正文或内容哈希，不加入题目专用规则。
保持 proof-control `off` / `shadow` / `active` 行为、旧 checkpoint/YAML 兼容，
并保留 Broker、Route Team、Proof Graph、Typed Memory、Inspiration、SSE 与
checkpoint/resume。

明确不修改：

- `max_output_tokens`、segment 长度或 Deep Exploration tier 配置；
- Agent 数量、调用预算默认值或 SSE；
- `main` 分支；
- 完整 MCTS、完整 Lean 或 Orchestrator 大拆分。

## 缺陷到真实源码映射

| 缺陷 | 生产代码 | 回归测试 | 修复后置条件 |
|---|---|---|---|
| 中文/英文表述不能稳定形成同一依赖族 | `proof_control/common_mode.py`：`_normalize_assumption`、`_semantic_tags`、`_same_assumption_family` | `tests/test_common_mode_multilingual_runtime.py` | Unicode/CJK 与公式均参与确定性特征；传输包装不参与语义相似度；原文不改写 |
| 已冻结路线稀释 Common-Mode 分母 | `proof_control/common_mode.py`：`build`、`_build_families` | `tests/test_common_mode_multilingual_runtime.py` | 只以仍可调度路线计算覆盖率与依赖割集；两条剩余路线的共同割集可触发挑战 |
| Challenger 只留下 sidecar 记录 | `proof_control/controller.py`、`proof_control/tasks.py`、`orchestrator.py` | `tests/test_common_mode_execution_closure.py` | active 模式产生 READY executable task，由 Dispatcher exactly-once 执行并独立复核 |
| 非自动化 falsification 被伪标为 ASSIGNED | `proof_control/tasks.py`、`proof_control/controller.py` | `tests/test_executable_countermodel_falsification_tasks.py`、`tests/test_falsification_task_materialization.py` | 无 handler 的任务只能进入带明确 wake condition 的 DEFERRED/NEEDS_REWRITE，不能伪装成已分配 |
| 达到输出上限后换目标又启动完整深探 | `deep_exploration.py`、`orchestrator.py` | `tests/test_deep_exploration_policy.py`、`tests/test_post_failure_bottleneck.py` | post-failure lineage 只能获得一次现有有界 repair；改写目标不重置额度 |
| 可保守修复的结构化输出导致整轮失败 | `agents.py` | `tests/test_structured_payload_normalization.py` | 只做枚举别名、server-owned 字段清理与确定性策略标记；其他错误仍严格失败 |
| Meta Pivot 只有描述、没有物化状态 | `proof_control/controller.py` | `tests/test_meta_pivot_state_machine.py`、`tests/test_meta_pivot_effectiveness.py` | 只有真实新路线/任务/义务/状态变化满足后置条件，否则显式 FAILED |

## 多语言策略

不把机器翻译替换为权威题面。权威输入、冻结目标和 `goal_hash` 始终使用原始/经用户
确认的题面。控制层使用不写回数学对象的确定性语义 sidecar：NFKC 解析视图、
Latin token、CJK n-gram、数学符号和 typed dependency。这样既能跨中英文及不同
中文措辞发现共享跳跃，也不会因翻译漂移改变命题。

## 验收

1. 先运行新增测试并确认其在旧实现上失败。
2. 每一阶段修复后运行相关 Pytest。
3. 最终运行全量 Pytest、Ruff、format check、compileall、proof-control/topology
   benchmark、离线 E2E 与 checkpoint/resume。
4. 不调用真实 Provider，不提交 `.env`、runs、缓存、dist 或 Provider 输出。
