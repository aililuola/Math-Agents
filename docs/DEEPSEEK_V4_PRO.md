# DeepSeek V4 Pro 五 Agent 配置

## 1. 固定模型参数

本配置使用 DeepSeek 官方 OpenAI-compatible 接口：

```text
Base URL: https://api.deepseek.com
Model: deepseek-v4-pro
Thinking: enabled
Reasoning effort: max
```

请求体中的关键字段为：

```json
{
  "model": "deepseek-v4-pro",
  "thinking": {"type": "enabled"},
  "reasoning_effort": "max",
  "max_tokens": 24000,
  "stream": true,
  "stream_options": {"include_usage": true}
}
```


### 1.1 DeepSeek SSE 流式读取

`streaming: true` 只改变 DeepSeek HTTP 响应的读取方式。非流式模式要等整个回答生成完成后一次性返回；流式模式则在生成过程中连续返回 SSE（Server-Sent Events）片段：

```text
DeepSeek API
  ├─ data: {reasoning_content 增量}
  ├─ data: {content 增量}
  ├─ data: {usage，choices=[]}
  └─ data: [DONE]
          ↓
MathProofMesh 在本地聚合完整结构化答案
```

适配器会：

1. 忽略空行和 `: keep-alive` 注释；
2. 逐块拼接最终 `content`；
3. 仅对 `reasoning_content` 增量计算字符数和 SHA-256，不保存原文；
4. 读取 `stream_options.include_usage=true` 产生的最后一个 usage-only 块；
5. 若连接在 `data: [DONE]` 前中断，则判定响应不完整并交由既有重试机制处理；
6. 最终仍返回与非流式路径相同的 `LLMResponse`，因此编排器和 Schema 校验逻辑无需改变。

`AgentConfig.streaming` 的默认值是 `false`，所以一般配置与第三方适配器仍保持原来的非流式行为；本项目提供的两份 DeepSeek V4 Pro 配置已对五个 Agent 显式设置为 `true`。

需要区分两个 SSE 通道：

- **供应商 SSE**：DeepSeek → MathProofMesh，负责增量接收模型响应；
- **Activity SSE**：MathProofMesh `/solve/stream` → 浏览器，负责推送阶段时间线。

供应商 SSE 不会自动把模型逐 token 内容显示给用户。数学证明必须等完整 JSON 聚合并通过 Schema 校验后，才会进入 Agent 通信和 Activity 结果摘要。

思考模式下，DeepSeek V4 不依赖 `temperature`、`top_p` 等采样参数，因此专用适配器不会发送 `temperature`。结构化阶段还会发送：

```json
{"response_format": {"type": "json_object"}}
```

参考：

- DeepSeek Chat Completion：<https://api-docs.deepseek.com/api/create-chat-completion/>
- Thinking Mode：<https://api-docs.deepseek.com/guides/thinking_mode>
- Models & Pricing：<https://api-docs.deepseek.com/quick_start/pricing>
- Rate Limits and Isolation：<https://api-docs.deepseek.com/quick_start/rate_limit>

## 2. 五个 key 的职责

| Agent | 环境变量 | 主要职责 |
|---|---|---|
| `ds-planner` | `DEEPSEEK_AGENT_1_KEY` | 题目分诊、策略生成、Meta-review、必要的最终复核 |
| `ds-explorer-a` | `DEEPSEEK_AGENT_2_KEY` | 数论、代数、组合、极值与不变量路线 |
| `ds-explorer-b` | `DEEPSEEK_AGENT_3_KEY` | 分析、不等式、几何、概率与解析路线 |
| `ds-explorer-c-verifier` | `DEEPSEEK_AGENT_4_KEY` | 逻辑、图论、构造路线、结构审计与反例检查 |
| `ds-synthesizer-verifier` | `DEEPSEEK_AGENT_5_KEY` | 证明综合、严谨写作，以及非自身产物的复核 |

角色不是五个固定流水线节点。系统会依据作者排除、当前负载、可信度和阶段需要选择 Agent。例如，Agent 4 生成某条探索路线后，不会审查自己的该条路线；最终证明由 Agent 5 综合时，Agent 5 会被最终结构和详细审查排除。

## 3. 环境变量注入

不得把真实 key 写入 `.env.example`、YAML、源码、运行报告或 Git 历史。

Linux/macOS：

```bash
export DEEPSEEK_AGENT_1_KEY="..."
export DEEPSEEK_AGENT_2_KEY="..."
export DEEPSEEK_AGENT_3_KEY="..."
export DEEPSEEK_AGENT_4_KEY="..."
export DEEPSEEK_AGENT_5_KEY="..."
```

Windows PowerShell：

```powershell
$env:DEEPSEEK_AGENT_1_KEY="..."
$env:DEEPSEEK_AGENT_2_KEY="..."
$env:DEEPSEEK_AGENT_3_KEY="..."
$env:DEEPSEEK_AGENT_4_KEY="..."
$env:DEEPSEEK_AGENT_5_KEY="..."
```

生产部署应使用容器 Secret、系统密钥环或云平台 Secret Manager，而不是明文 `.env`。若必须使用 `.env`，应确认它被 `.gitignore` 排除并限制文件权限。

## 4. Agent 隔离

五个 Agent 即使使用同一个模型，也通过以下机制降低相关错误和上下文污染：

1. 每个 Agent 使用不同 API key 环境变量；
2. 每个 Agent 使用不同 `user_id`；
3. 初始探索提示词相互隔离；
4. Reviewer 永远排除当前证明的作者；
5. Reviewer 之间不互相聊天；
6. 跨路径只传输已验证的 `ClaimCard`，不广播完整思维链；
7. Synthesizer 与 Final Verifier 分离；
8. 相同结论若依赖同一错误前提，不会因“多数同意”自动通过。

需要注意：如果五个 key 实际属于同一 DeepSeek 账户，供应商并发配额仍可能按账户统一计算；不同 `user_id` 用于内容、缓存与调度隔离，不等于五个独立账户。

## 5. 实时运行时间线

DeepSeek 处于长推理模式时，单次请求可能持续较长时间。v0.5.1 在 CLI 中默认开启 `compact` Activity 面板，按时间顺序显示题目分析、路线生成、并行探索、Claim 提取、两级验证、Meta-review、综合和终审；每个长调用还会按 `activity_heartbeat_seconds` 发出低频“仍在处理”状态。

```bash
mathproofmesh solve problem.txt \
  --config config.deepseek-v4-pro.yaml \
  --activity compact
```

更详细或关闭：

```bash
mathproofmesh solve problem.txt --config config.deepseek-v4-pro.yaml --activity detailed
mathproofmesh solve problem.txt --config config.deepseek-v4-pro.yaml --activity off
```

该时间线来自编排器状态与 Schema 验证后的结果摘要，不读取、不显示、也不转发 DeepSeek 的原始 `reasoning_content`。运行结束后可在 `activity.jsonl`、`reports/activity_timeline.json` 和 `reports/activity_timeline.md` 中查看完整时间顺序。HTTP 部署还可通过 `/solve/stream` 以 SSE 实时推送同一事件流。

## 6. reasoning_content 处理

DeepSeek 在思考模式下可能返回 `reasoning_content`。MathProofMesh 的处理规则是：

- 不将其作为下一 Agent 的输入；
- 不写入 `raw/`、`events.jsonl` 或最终报告；
- 只保存 `present`、字符数和 SHA-256，以便确认思考模式是否生效；
- Agent 间传递的是经过 Schema 校验的最终结构化对象；
- 本地工具输出单独作为证据保存。

这样既避免把冗长、未经验证的内部推理扩散到整个网络，也防止下游 Agent 被上游措辞锚定。

## 7. 证明检查点、断线接力与恢复

两份 DeepSeek 配置都启用：

```yaml
continuation:
  enabled: true
  checkpoint_policy: verified_subgoal
  verify_each_delta: true
  resume_on_disconnect: true
  allow_cross_agent_failover: true
  max_failover_agents: 2
  process_resume_enabled: true
```

Explorer 每次只生成少量新步骤。只有本地完整性守卫和独立 Delta Reviewer 都通过后，系统才推进该路径的 `latest.json`。如果 SSE 中断、没有 `[DONE]`、缺少请求的 usage 摘要或网络超时，当前半截结果被丢弃，同一 key 先按 `request_retries` 重试；仍失败才切换备用 key。401/403 不在原 key 上重复请求，但可以由其他 key 接力；连续失败的 Agent 会进入短暂冷却。当前 Delta 的作者始终从独立 checkpoint Reviewer 及其备用候选中排除。

恢复命令：

```bash
mathproofmesh resume olympiad-problem-001 \
  --config config.deepseek-v4-pro.yaml
```

恢复的是最近已验证的外部数学状态，不是 DeepSeek 私有隐藏状态。调用数和 usage 按 `run_id` 累计恢复；若上次已经耗尽 `max_total_calls`，需在恢复配置中提高总上限。具体文件格式和失败矩阵见 [CHECKPOINT_RESUME.md](CHECKPOINT_RESUME.md)。

## 8. 连通性检查

仅验证 key 与模型可见性，不产生补全费用：

```bash
mathproofmesh probe --config config.deepseek-v4-pro.yaml
```

进一步验证 `thinking=enabled`、`reasoning_effort=max` 和 JSON 输出，需要发送一次很小的真实请求：

```bash
mathproofmesh probe --config config.deepseek-v4-pro.yaml --completion
```

命令只打印 Agent ID、模型、状态和 token 数，不打印 key。

## 9. 低成本冒烟测试

```bash
mathproofmesh solve examples/problem.txt \
  --config config.deepseek-v4-pro.smoke.yaml \
  --run-id deepseek-smoke
```

冒烟配置仍使用 V4 Pro 和 `max`，但限制为两条探索路径、单轮自适应调度、较小输出上限以及较低总费用上限。它用于检查：

- 五个 key 是否均可读取；
- JSON Schema 是否稳定遵循；
- 结构审计和详细审计是否能完成；
- `reasoning_content` 是否被剥离；
- usage 与费用估计是否写入报告；
- ProofDelta 是否通过独立检查并提交 checkpoint；
- 原 key 失败时备用 key 是否从同一 checkpoint 接力；
- `resume` 是否恢复累计预算和 Activity 时间线。

通过后再使用 `config.deepseek-v4-pro.yaml`。

## 10. 正式运行

```bash
mathproofmesh solve problem.txt \
  --config config.deepseek-v4-pro.yaml \
  --run-id olympiad-problem-001

# 进程中断后
mathproofmesh resume olympiad-problem-001 \
  --config config.deepseek-v4-pro.yaml
```

生产配置的默认上限为：

```text
42 calls
4 adaptive rounds
3 initial paths
6 maximum paths
2,000,000 total tokens
USD 5 conservative estimated cost cap
```

其中输入单价按缓存未命中的公开价格做保守估计。实际费用可能因缓存命中和供应商价格变化而不同，正式运行前应核对官方价格页。

## 11. 工具调用兼容性

MathProofMesh 当前不把 DeepSeek 的 provider-side tool calls 作为 Agent 间协议。模型先输出结构化 `tool_requests`，系统在本地安全工具层执行，再以新的自包含请求将结果交给同一独立 Reviewer 解释。因此：

- 不需要在供应商对话历史中回放 tool-call assistant 消息；
- 不会因遗漏 `reasoning_content` 的 tool-call 回放规则而触发长对话错误；
- 所有确定性工具证据都有独立 artifact 引用；
- 工具找到反例时，本地守卫可以覆盖模型的错误 PASS。

未来若直接启用 DeepSeek provider-side tool calls，必须完整实现其 `reasoning_content` 回放要求，不能直接沿用当前的无状态请求路径。

## 12. 密钥安全

密钥一旦粘贴到聊天、工单、日志或截图中，就应视为已经暴露。完成本地联调后，建议在 DeepSeek 控制台撤销旧 key、生成新 key，并只把新 key 注入部署环境。仓库交付物中不应出现任何 `sk-...` 明文。
