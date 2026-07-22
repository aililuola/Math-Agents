# 证明检查点、断线接力与进程恢复

## 0.7 拓扑恢复语义

分层模式的阶段 checkpoint 除旧 `ProofCheckpoint` 外，还保存 `route_registry`、`message_broker`、`message_receipts`、`typed_memory`、`proof_graph`、`bridge_broker`、`contradiction_broker`、`inspiration_engine` 和 domain-role capability 状态。旧 checkpoint 缺少这些字段时初始化为空，并记录 `checkpoint_migrated_to_v0_7`，不会丢弃原有已验证证明段。

若旧 checkpoint 恰好停在 Triage 完成、Strategy 尚未生成的窗口，恢复流程会在重新生成 Strategy 后、任何证明调用前，幂等补齐 Route、Prover 成员和稀疏邻居，并立即保存 `resume_routes_ensured` checkpoint。实际选中的 Prover 也会同步到 RouteRegistry。`hierarchical_sparse` 缺 Route、MessageBroker 或 TypedMemory 时必须 fail closed，不允许回退到 legacy `proof_continuation` 或把旧 LemmaMemory Claim 当作路线收件箱。

消息恢复使用 `(message_id, target_route_id)` 稳定 delivery key。已经进入 Agent prompt 但尚未回执的投递恢复为 pending receipt，`prompt_consumed=true`，不会再次放入 prompt。Inspiration proposal、review 和 materialization 也有稳定 ID；同一 proposal 恢复后不能重复创建路线或重复花费 Surprise Budget。

Graph freeze、Meta-Strategist cooldown、最后可观测 Inspiration snapshot 和受保护预算都随 checkpoint 恢复。恢复的是可审计外部状态，不是模型私有解码状态。

`InspirationCreditTarget` 及 outcome 中已固化的 `credit_route_ids` / `credit_obligation_ids` 也随 checkpoint 恢复。旧 checkpoint 若缺少这些字段，会根据既有 Proposal、Trigger 和 Materialization 幂等回填；恢复后不会改用新的路线集合重算历史 proof debt，也不会重复给同一 Fact 记账。

MathProofMesh v0.5.0 将一次长证明从“单个超长模型调用”改造成若干可验证的数学增量。系统不能恢复供应商服务器在断线瞬间的隐藏神经网络状态，但可以恢复最近一个**已经独立验证并持久化**的证明状态。因此，用户看到的行为是“证明已通过第 7 步，断线后继续第 8 步”，而不是重新证明全部前置内容。

## 1. 恢复语义

系统区分四种状态：

1. **供应商私有推理状态**：不保存、不能恢复，也不跨 Agent 传输；
2. **候选 `ProofDelta`**：一个调用新增的少量证明步骤和 Claim，尚不能作为事实；
3. **已验证 `ProofCheckpoint`**：本地完整性守卫和独立 Reviewer 均通过，可作为恢复点；
4. **阶段快照**：策略、Attempt、验证报告、Meta-review、预算和 Agent 使用量，用于进程级恢复。

恢复优先级为：

```text
latest committed ProofCheckpoint
        > verified structured stage state
        > unverified candidate delta
        > partial SSE / private reasoning
```

后两项不会进入事实库。被截断的 SSE、半截 JSON、未通过 Reviewer 的增量只能作为失败证据保存，不能推进 `latest.json`。

## 2. 正常推进流程

```text
当前检查点 C_k
      │
      ▼
Explorer 只生成 1–3 个新步骤组成 ProofDelta D_{k+1}
      │
      ▼
本地完整性守卫
  - problem_hash
  - path_id / strategy_id
  - parent_checkpoint_id
  - segment_index
  - dependency closure
  - ID 唯一性
      │ PASS
      ▼
独立 checkpoint verifier（明确排除当前 Delta 作者）
  - 逐步数学核查
  - 子目标状态核查
  - 完整证明条件核查
      │ PASS 且置信度达阈值
      ▼
原子提交 C_{k+1}
  - 写入不可变段文件
  - 再更新 latest.json
  - Claim 才升级为 verified
```

`ArtifactStore.commit_proof_checkpoint()` 强制检查父子链：新检查点必须以当前 `latest.json` 为父节点、段号严格加一，并保持问题、路径和策略身份不变。重复提交同一检查点是幂等的；试图覆盖或跳过最新父节点会失败。

## 3. 网络断连与跨 key 接力

单个结构化任务的故障恢复顺序为：

```text
同一 Agent / 同一 API key
  ├─ 初始请求
  ├─ request_retries 次指数退避重试
  └─ 仍失败
        │
        ▼
备用 Agent 1（同一个 PromptBundle 和检查点）
        │失败
        ▼
备用 Agent 2
```

可重试错误包括超时、网络断开、远端协议错误、HTTP 408/409/429 和 5xx。401/403 等权限错误不会在同一个无效 key 上重复请求，但允许将同一任务转交给另一个 key；明显属于请求或模型配置本身的非暂态 4xx 则停止无意义的跨 key 扩散。失败 key 会进入短暂指数冷却，后续路径优先选择健康 Agent。

备用 Agent 接收：

- 不可变原题；
- 原始策略卡；
- 最近 `committed` 检查点；
- 当前子目标和剩余子目标；
- 已验证 Claim；
- 定向审查反馈。

备用 Agent**不会**接收：

- 原 Agent 的原始 `reasoning_content`；
- 截断的 SSE 内容；
- 未验证的半截证明；
- API key 或供应商原始鉴权信息。

因此，接力恢复的是数学状态，而不是模型私有思考链。

## 4. 进程重启恢复

启动求解时建议指定稳定 `run_id`：

```bash
mathproofmesh solve problem.txt \
  --config config.deepseek-v4-pro.yaml \
  --run-id hard-problem-001
```

进程、机器或终端中断后：

```bash
mathproofmesh resume hard-problem-001 \
  --config config.deepseek-v4-pro.yaml
```

`resume` 会恢复；即使进程在第一个阶段快照之前退出，只要 `ProblemContract` 已写入，也会从冻结原题重新进入 Triage：

- `ProblemContract` 与完整性哈希；
- Triage 和 StrategySet；若阶段快照滞后，则优先读取单独持久化的 `triage`、`selected_strategies` 和 `lemma_memory`；
- Attempt、Claim、验证报告和 Meta-review；
- 每条路径的最新已验证 `ProofCheckpoint`；
- 调用数、预算桶、每个 Agent 的 token、费用、失败数和信任分；
- Activity 时间线的已有事件、序号和累计耗时；
- 最终证明和最终审计（若已写入最终阶段快照）。

恢复后，未完成路径从自己的 `latest.json` 继续；已完成路径不会被当作空白路线重新初始化。调用预算按同一 `run_id` 累计恢复：若上一次已经达到 `max_total_calls`，需要在恢复所用配置中提高该总上限；系统不会把历史消耗静默清零。

## 5. 配置

DeepSeek 正式配置默认启用：

```yaml
continuation:
  enabled: true
  checkpoint_policy: verified_subgoal
  max_new_steps_per_call: 3
  max_new_claims_per_call: 3
  max_output_tokens_per_segment: 12000
  segments_per_explore_call: 1
  max_segments_per_path: 12
  verify_each_delta: true
  delta_verifier_replicas: 1
  checkpoint_pass_threshold: 0.80
  resume_on_disconnect: true
  allow_cross_agent_failover: true
  max_failover_agents: 2
  process_resume_enabled: true
  retain_rejected_deltas: true
```

字段含义：

- `checkpoint_policy=verified_subgoal`：只有明确完成一个连贯子目标的 Delta 才能推进检查点；
- `verified_delta`：允许更小但完整可验证的增量；
- `segments_per_explore_call`：一次编排动作最多提交多少个连续段，默认 1 可降低断线损失；
- `max_segments_per_path`：单一路线的持久化深度上限；
- `verify_each_delta`：关闭后仅保留本地完整性守卫，不建议用于高风险证明；
- `delta_verifier_replicas`：每个 Delta 的独立 Reviewer 数；
- `checkpoint_pass_threshold`：独立 Reviewer 的最低置信度；
- `max_failover_agents`：原 key 重试耗尽后允许尝试的备用 Agent 数；
- `retain_rejected_deltas`：保留被拒绝的 Delta，便于审计，但不进入恢复状态。

通用 `config.example.yaml` 默认 `enabled: false`，确保旧配置保持原来的整篇 Attempt 行为。DeepSeek V4 Pro 正式与 smoke 配置显式开启。

## 6. 持久化目录

```text
runs/<run_id>/
├── checkpoints/
│   ├── runtime_ledger.json
│   ├── *_latest.json
│   └── proof/
│       └── <path_id>/
│           ├── 0000_checkpoint_*.json
│           ├── 0001_checkpoint_*.json
│           ├── 0002_checkpoint_*.json
│           └── latest.json
├── deltas/
│   ├── candidate_delta_*.json
│   └── rejected_delta_*.json
├── activity.jsonl
└── structured/
```

`runtime_ledger.json` 在每次逻辑调用开始以及响应计量更新后原子写入。即使进程在模型调用中途终止，已消耗的逻辑调用预算也不会因恢复而被静默重置。

## 7. HTTP API

普通恢复：

```bash
curl -X POST http://127.0.0.1:8000/resume \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $MATHPROOFMESH_SERVER_TOKEN" \
  -d '{"run_id":"hard-problem-001"}'
```

带 Activity SSE：

```bash
curl -N -X POST http://127.0.0.1:8000/resume/stream \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $MATHPROOFMESH_SERVER_TOKEN" \
  -d '{"run_id":"hard-problem-001"}'
```

Activity 可能显示：

```text
✓ 已提交检查点 C3：证明通过第 7 步
! ds-explorer-a 的 API 重试耗尽
◐ ds-explorer-b 从 C3 接力当前子目标
✓ 新增步骤 8–9 通过独立验证，提交 C4
```

这些是编排状态和结构化数学结果，不是模型原始思考链。

## 8. 失败边界

| 情况 | 行为 |
|---|---|
| SSE 未收到 `[DONE]` | 当前调用失败；不解析半截结果；从最近检查点重试 |
| 请求了 usage 但没有最终 usage 摘要 | 视为不完整传输并重试 |
| 完整响应但 JSON 非法 | 同一 Agent 进行定向 Schema 修复 |
| Delta 依赖未提交步骤 | 本地守卫 FAIL，不推进检查点 |
| Reviewer FAIL/UNCERTAIN | Delta 保留为 rejected，不进入引理库 |
| 原 key 重试耗尽 | 由备用 Agent 从同一检查点继续；证明作者不能充当自己的 checkpoint Reviewer |
| 所有备用 key 失败 | 路线保持在原检查点，其余并行路线继续 |
| Python 进程退出 | `resume` 加载阶段快照、独立结构化产物、runtime ledger 和 proof latest pointer；没有阶段快照时从 ProblemContract 重新进入 |
| 检查点文件被篡改 | Pydantic/hash/父子链校验失败，拒绝加载或提交 |

## 9. 不能保证的事项

- 不能恢复 DeepSeek 断线瞬间的服务器端 KV cache、隐藏解码状态或下一个 token；
- 不能把自然语言 Reviewer 的 PASS 等同于形式化证明内核的证明；
- 不能保证多个同构模型的错误完全独立；
- 不能保证网络断开前已计费但没有 usage 尾块的 token 能被供应商精确回报；本地 runtime ledger 只能准确保存已经观测到的 usage 和逻辑调用数。

对于可形式化的关键定理，仍建议将最终结论交给 Lean、Rocq、Isabelle 或领域专家复核。
