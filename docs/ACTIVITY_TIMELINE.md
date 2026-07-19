# 实时 Activity 时间线

MathProofMesh 可以在求解过程中持续显示类似“Activity”的时间线，让用户按时间顺序看到系统正在做什么，而不必等待全部 Agent 结束后才得到结果。

## 1. 默认显示内容

默认的 `compact` 模式只显示高层、可审计进展：

```text
Activity · 07:42
◐ 00:00  启动多 Agent 数学求解
✓ 00:21  题目分析完成
✓ 00:48  证明路线已生成并分配
◐ 01:03  并行探索不同证明方向
◐ 02:14  沿指定路线独立推演  ds-explorer-a
✓ 03:37  首轮并行探索完成
✓ 04:11  引理归纳完成
◐ 04:12  验证候选证明
✓ 05:46  首轮候选验证完成
✓ 06:18  综合复核完成
◐ 06:19  综合候选路线形成最终证明
✓ 07:42  多 Agent 求解结束
```

时间线会显示：

- 当前阶段及已经完成的阶段；
- 当前工作的 Agent 和任务类别；
- 路线数、完整/部分/失败候选数；
- Claim 数和验证结论统计；
- 自适应调度决定，例如继续深挖、拓宽或进入综合；
- 最终审计、定向修订、调用数和 token 汇总；
- 对耗时 API 调用的低频“仍在处理”心跳。

## 2. 不显示的内容

该时间线**不是原始思考链显示器**。它不会输出或保存 DeepSeek 的 `reasoning_content`，也不会把某个 Agent 的私有草稿式推理广播给其他 Agent。时间线只来自编排器的状态变化和通过 Schema 校验后的结构化结果摘要。

这一区分可以避免：

- 将未经验证的中间猜测误当作证明事实；
- 将一个 Agent 的错误思路锚定到其他 Agent；
- 在控制台、浏览器或日志中泄露 API key 或供应商私有推理字段；
- 因重复传输长文本显著增加 token 成本。

## 3. CLI 用法

默认读取 YAML 中的 `runtime.activity_mode`：

```bash
mathproofmesh solve problem.txt --config config.deepseek-v4-pro.yaml
```

显式选择显示级别：

```bash
# 推荐：阶段摘要 + Agent 当前任务
mathproofmesh solve problem.txt --config config.deepseek-v4-pro.yaml --activity compact

# 包含结构化输出修复、心跳等更细事件
mathproofmesh solve problem.txt --config config.deepseek-v4-pro.yaml --activity detailed

# 完全关闭终端实时显示；持久化行为仍由 activity_persist 控制
mathproofmesh solve problem.txt --config config.deepseek-v4-pro.yaml --activity off
```

`--json` 默认关闭终端 Activity，以保持标准输出为纯 JSON；需要同时观察进度时可显式添加 `--activity compact`，时间线写入 `stderr`，结果 JSON 写入 `stdout`。

## 4. 配置

```yaml
runtime:
  activity_mode: compact             # off | compact | detailed
  activity_max_visible: 18           # 终端面板最多保留多少个当前/最近任务
  activity_persist: true             # 保存 JSONL、JSON 和 Markdown 时间线
  activity_include_agent_calls: true # 显示 Agent 当前任务，不显示提示词或思考链
  activity_heartbeat_seconds: 20      # 长调用每隔多少秒更新一次；0 表示关闭
```

## 5. 持久化文件

每次运行都会生成：

```text
runs/<run_id>/
├── activity.jsonl
└── reports/
    ├── activity_timeline.json
    └── activity_timeline.md
```

`activity.jsonl` 是追加式实时事件流；即使运行中途异常，也通常能保留此前进展。最终 JSON/Markdown 文件在正常结束、预算耗尽或捕获到运行异常时生成。

每条事件包含：

```json
{
  "sequence": 12,
  "elapsed_ms": 74128,
  "status": "running",
  "importance": "normal",
  "stage": "independent_exploration",
  "task_id": "activity_...",
  "title": "沿指定路线独立推演",
  "detail": "ds-explorer-a 正在处理结构化任务",
  "agent_id": "ds-explorer-a",
  "metrics": {
    "role": "explorer"
  }
}
```

标题、详情和 Agent 标识都会经过长度限制与常见凭据模式脱敏。

## 6. 浏览器或前端接入

HTTP 服务提供 Server-Sent Events：

```bash
curl -N -X POST http://127.0.0.1:8000/solve/stream \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $MATHPROOFMESH_SERVER_TOKEN" \
  -d '{"problem":"Prove ...", "run_id":"problem-001"}'
```

事件顺序为：

```text
connected
activity
activity
...
result
```

前端只需按 `task_id` 更新同一行：`running` 事件显示旋转图标，`completed` 显示完成标记，`warning`/`failed` 显示相应状态。这样可以构造与截图相近的可折叠时间线界面，而无需访问模型私有思考内容。

### 两条 SSE 通道的区别

DeepSeek Agent 配置中的 `streaming: true` 与本节的 `/solve/stream` 是两条方向不同的流：

```text
DeepSeek API --模型响应分块--> MathProofMesh
MathProofMesh --Activity 事件--> 浏览器前端
```

前者用于让后端逐块接收 DeepSeek 的长响应；后者用于把安全的阶段进度展示给用户。
二者可以同时启用。模型响应流不会把 token 或 `reasoning_content` 原样转发到前端，
Activity 流仍只包含阶段状态、结构化结果摘要和无内容心跳。
