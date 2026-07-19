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

五个 DeepSeek 配置默认启用上游 SSE 流式读取。DeepSeek 会把一次长回答拆成若干
`data: {...}` 增量事件，并以 `data: [DONE]` 结束。适配器逐块读取后，在本地重新
聚合为与非流式模式相同的 `LLMResponse`，因此编排器、Schema 校验、预算统计和最终
证明输出不需要区分两种传输方式。最后一个 usage-only 事件用于取得完整 token 用量。

配置模型仍保留 `streaming: false` 作为全局兼容默认值；只有在 Agent 条目显式设置
`streaming: true` 时才会发送 SSE 请求。因此普通 OpenAI-compatible、Anthropic、
Gemini 适配器以及未开启该字段的旧配置均不受影响。

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

DeepSeek 处于长推理模式时，单次请求可能持续较长时间。v0.3.0 在 CLI 中默认开启 `compact` Activity 面板，按时间顺序显示题目分析、路线生成、并行探索、Claim 提取、两级验证、Meta-review、综合和终审；每个长调用还会按 `activity_heartbeat_seconds` 发出低频“仍在处理”状态。

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

这里存在两个用途不同、可以同时开启的 SSE 通道：

```text
DeepSeek API --SSE token chunks--> MathProofMesh adapter
MathProofMesh --SSE activity events--> browser / CLI frontend
```

前一个是本节新增的“模型响应流式读取”，解决后端必须等待整份 HTTP 响应才能开始
处理的问题；后一个是 v0.3.0 已有的“Activity 时间线推送”，负责把阶段进展展示给
用户。模型响应流不会把 token 或私有思考链直接显示到 Activity 面板，Activity 仍只
显示安全的阶段摘要与无内容心跳。

流式读取通常能更早收到首个数据块，并能确认长连接仍在持续产生数据，但它不会减少
模型实际推理量、token 费用或保证总完成时间缩短。系统仍会等最终结构化内容聚合完毕
后再做 JSON Schema 校验和 Agent 间传递。

## 6. reasoning_content 处理

DeepSeek 在思考模式下可能返回 `reasoning_content`。MathProofMesh 的处理规则是：

- 不将其作为下一 Agent 的输入；
- 不写入 `raw/`、`events.jsonl` 或最终报告；
- 只保存 `present`、字符数和 SHA-256，以便确认思考模式是否生效；
- Agent 间传递的是经过 Schema 校验的最终结构化对象；
- 本地工具输出单独作为证据保存。

流式模式下，`reasoning_content` 不会先拼成完整字符串再保存。适配器只对每个增量片段
累加字符数并更新 SHA-256，随后立即丢弃该片段；最终运行产物只包含 `present`、字符数、
哈希以及无敏感内容的流统计信息。

这样既避免把冗长、未经验证的内部推理扩散到整个网络，也防止下游 Agent 被上游措辞锚定。

## 7. 连通性检查

仅验证 key 与模型可见性，不产生补全费用：

```bash
mathproofmesh probe --config config.deepseek-v4-pro.yaml
```

进一步验证 `thinking=enabled`、`reasoning_effort=max` 和 JSON 输出，需要发送一次很小的真实请求：

```bash
mathproofmesh probe --config config.deepseek-v4-pro.yaml --completion
```

命令只打印 Agent ID、模型、状态和 token 数，不打印 key。

## 8. 低成本冒烟测试

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
- usage 与费用估计是否写入报告。

通过后再使用 `config.deepseek-v4-pro.yaml`。

## 9. 正式运行

```bash
mathproofmesh solve problem.txt \
  --config config.deepseek-v4-pro.yaml \
  --run-id olympiad-problem-001
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

## 10. 工具调用兼容性

MathProofMesh 当前不把 DeepSeek 的 provider-side tool calls 作为 Agent 间协议。模型先输出结构化 `tool_requests`，系统在本地安全工具层执行，再以新的自包含请求将结果交给同一独立 Reviewer 解释。因此：

- 不需要在供应商对话历史中回放 tool-call assistant 消息；
- 不会因遗漏 `reasoning_content` 的 tool-call 回放规则而触发长对话错误；
- 所有确定性工具证据都有独立 artifact 引用；
- 工具找到反例时，本地守卫可以覆盖模型的错误 PASS。

未来若直接启用 DeepSeek provider-side tool calls，必须完整实现其 `reasoning_content` 回放要求，不能直接沿用当前的无状态请求路径。

## 11. 密钥安全

密钥一旦粘贴到聊天、工单、日志或截图中，就应视为已经暴露。完成本地联调后，建议在 DeepSeek 控制台撤销旧 key、生成新 key，并只把新 key 注入部署环境。仓库交付物中不应出现任何 `sk-...` 明文。
