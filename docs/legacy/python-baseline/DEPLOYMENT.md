# 部署、扩展与真实 API 联调

## 1. 推荐角色配置

### 4 个 key 的最低实用配置

```text
key 1: planner + meta_reviewer
key 2: explorer + summarizer
key 3: structural_verifier + detailed_verifier
key 4: synthesizer + final_verifier
```

缺点是单个 Explorer 难以真正并行扩展，且 Final Verifier 可能与其他阶段复用同一模型。

### 6 个 key 的推荐配置

```text
planner
explorer A
explorer B
verifier A
verifier B
synthesizer
```

与 `config.example.yaml` 对应。多个策略仍可轮转复用 Explorer key，但每个 key 的并发上限独立。

### 5 个 DeepSeek V4 Pro key 的专用配置

仓库中的 `config.deepseek-v4-pro.yaml` 使用五个独立环境变量，并将角色分为 Planner、三个可探索 Agent 和 Synthesizer/Verifier。所有请求固定为 `deepseek-v4-pro`、思考模式开启、`reasoning_effort=max`。由于五个 Agent 属于同一提供商，配置关闭了无意义的“跨提供商优先”加分，但仍强制作者排除、独立 `user_id`、首轮隔离和条件复核。详见 [DEEPSEEK_V4_PRO.md](DEEPSEEK_V4_PRO.md)。

### 8–12 个 key 的高难度配置

- 1–2 Planner/Meta；
- 4–6 Explorer，按代数、数论、组合、几何、分析、逻辑专长分组；
- 2–3 Reviewer，尽量跨提供商；
- 1 Synthesizer；
- 1 专用 Final Verifier。

增加 key 前应先增加策略多样性，而不是让所有 Agent 使用同一模型和同一提示词生成近重复答案。

## 2. 真实供应商联调步骤

1. 先运行 Mock demo，确认本地依赖和文件权限；
2. 只启用一个真实 Agent，使用小题测试 JSON Schema 兼容性；
3. 核对供应商是否支持 `/chat/completions`、Anthropic `/messages` 或 Gemini `generateContent`；
4. 检查模型的最大输出参数是否兼容；
5. 设置低 `max_total_calls` 做一次全链；
6. 检查 `raw/` 中的 usage 字段是否正确映射；
7. 配置 token 单价；
8. 再增加其他 key 和并行度。

DeepSeek 配置可先运行：

```bash
mathproofmesh probe --config config.deepseek-v4-pro.yaml
mathproofmesh probe --config config.deepseek-v4-pro.yaml --completion
```

第一条只请求模型列表；第二条会为每个 Agent 发送一个小型补全请求。

当前 OpenAI-compatible 适配器使用 Chat Completions 风格：

```text
POST <base_url>/chat/completions
Authorization: Bearer <key>
```

某些新 API 只支持 Responses API，或把 `max_tokens` 改成其他字段。这类网关需要新增适配器或在 `openai_compatible.py` 中按供应商调整，不能假设所有“兼容”服务完全一致。

## 3. 成本设置

每个 Agent 可配置：

```yaml
pricing:
  input_per_million: 5.0
  output_per_million: 20.0
```

系统根据返回 usage 估算成本，并可设置：

```yaml
budget:
  max_total_tokens: 1000000
  max_cost_usd: 50.0
```

若供应商不返回 token usage，这两个限制不能精确生效；调用次数上限仍有效。生产环境可扩展本地 tokenizer 估算器。

## 4. 并发与限流

- `runtime.max_parallel_calls`：全局并发；
- `agent.max_concurrency`：单 key 并发；
- `agent.requests_per_minute`：单 key 滑动窗口；
- `runtime.request_retries`：同一 key 对超时、网络、远端协议错误、408/409/429/5xx 的额外重试次数；401/403 跳过同 key 重试并可切换备用 key；
- `runtime.parse_retries`：合法响应但 JSON 不合 Schema 时的修复次数。

不要通过复制同一个 key 为多个 Agent ID 来绕过供应商限额。系统无法判断两个环境变量是否实际指向同一 key，部署者应保证隔离。

## 5. HTTP 运行

环境变量：

```bash
export MATHPROOFMESH_CONFIG=/absolute/path/config.yaml
export MATHPROOFMESH_MAX_CONCURRENT_RUNS=1
export MATHPROOFMESH_SERVER_TOKEN='long-random-secret'
uvicorn mathproofmesh.server:create_app --factory --host 127.0.0.1 --port 8000
```

`/health` 不触发模型调用；`/solve` 在设置 token 时要求 Bearer 鉴权。`/solve/stream` 使用 Server-Sent Events 实时发送 `activity` 事件，并在末尾发送 `result`；它使用相同的 Bearer 鉴权和并发信号量。

```bash
curl -N -X POST http://127.0.0.1:8000/solve/stream \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $MATHPROOFMESH_SERVER_TOKEN" \
  -d '{"problem":"Prove ...", "run_id":"problem-001"}'
```

Activity 事件只含阶段状态、Agent ID、耗时和结构化结果摘要，不含 prompt、API key 或模型原始思考链。生产反向代理应关闭该路径的响应缓冲，例如 Nginx 使用 `proxy_buffering off`；服务端也会发送 `X-Accel-Buffering: no`。

公开服务建议：

- Nginx/Caddy TLS；
- 请求体大小和速率限制；
- 运行目录配额；
- 每个租户独立配置与 key；
- 不让用户指定任意 `base_url`；
- 工具容器无网络、只读根文件系统、低权限用户；
- 对 `raw/` 和 prompt 目录加密或设置短期保留策略。

## 6. 添加新供应商

实现 `LLMClient`：

```python
class MyProviderClient(LLMClient):
    async def complete(
        self,
        messages,
        *,
        temperature,
        max_output_tokens,
        json_mode=False,
        schema_name=None,
        schema=None,
    ) -> LLMResponse:
        ...
```

然后：

1. 在 `ProviderName` 中加入字面量；
2. 在 `AgentPool._make_client` 中构造；
3. 映射输入/输出 token；
4. 保留 request ID 和原始元数据；
5. 添加 Mock HTTP 测试；
6. 更新配置和部署文档。

## 7. 添加数学工具

新增工具必须满足：

- 参数 Schema 明确；
- 无任意代码执行；
- 有超时、资源上限和输出上限；
- 结果带证据引用；
- 明确“通过/失败”的逻辑含义；
- 本地 guard 能覆盖模型对确定性结果的错误解释。

可扩展方向：

- SMT（Z3）约束检查；
- SageMath 独立容器；
- Lean/Coq/Isabelle；
- 数值区间算术；
- 图论小规模穷举；
- SAT/ILP 反例搜索。

## 8. 添加 Agent 角色

推荐通过新 Schema 和新 stage 添加，而不是给现有 Prompt 无限追加职责。例如：

- literature verifier：核对外部定理和引用；
- geometry formalizer：把图形条件转为坐标/合成几何对象；
- counterexample specialist：仅负责构造反例；
- formalization agent：将关键 Claim 转成 Lean；
- human-review gateway：把关键未决问题提交给专家。

新增角色后需决定：

- 它能读取哪些对象；
- 是否可以写 LemmaMemory；
- 谁验证它；
- 是否属于 breadth/depth/verification/synthesis 预算；
- 失败时进入哪个层级。

## 9. 可恢复执行

正式配置已启用证明步骤级检查点和进程恢复。启动任务时指定稳定 `run_id`：

```bash
mathproofmesh solve problem.txt \
  --config config.deepseek-v4-pro.yaml \
  --run-id hard-problem-001
```

进程中断后重新注入 API-key 环境变量，然后执行：

```bash
mathproofmesh resume hard-problem-001 \
  --config config.deepseek-v4-pro.yaml
```

恢复会加载 `ProblemContract`、阶段快照、单独持久化的 Triage/Strategy/LemmaMemory、Claim、验证报告、每条路径的 `latest.json`、runtime ledger、累计 usage 和已有 Activity 时间线。即使第一个阶段快照尚未生成，也能从冻结原题重新进入；未完成的 SSE/JSON 不会成为恢复点。原 key 的调用级重试耗尽后，可由备用 Agent 使用同一检查点继续，且 checkpoint Reviewer 不能是当前 Delta 作者。

预算上限按 `run_id` 累计；若上一轮是 `budget_exhausted`，恢复前应在配置中提高 `max_total_calls`。HTTP 部署还提供 `/resume` 与 `/resume/stream`。生产环境应把 `runs/` 放在持久卷，并保证同一 `run_id` 不被多个无协调进程并发写入。检查点父子链能拒绝旧父节点覆盖，但当前版本不实现分布式租约；需要多副本调度时，应在外层增加任务队列或数据库锁。详见 [CHECKPOINT_RESUME.md](CHECKPOINT_RESUME.md)。

## 10. 真实 API 尚未验证的部分

交付构建没有使用用户真实凭据发起付费 API 请求，因此以下内容只完成了实现、HTTP Mock 与本地端到端测试：

- 各供应商实时模型名是否存在；
- 第三方 OpenAI-compatible 网关的参数差异；
- 真实速率限制和 429 行为；
- 供应商 usage 字段完整性；
- 长输出 JSON 在特定模型上的遵循率；
- 多 key 大规模并发下的成本和延迟；
- 真实长响应中断后的供应商计费与 usage 尾块行为。

正式使用前应按第 2 节逐步联调，并从低预算开始。
