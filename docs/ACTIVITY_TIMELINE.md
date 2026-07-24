# 实时 Activity 时间线

每次新运行首先出现稳定的 `goal-preflight` 节点。清晰题目会在本地完成并标记 `api_call=false`；疑似歧义题目会连接一个 `goal_normalization` Planner 节点，并在需要改变数学含义时停留为“等待确认规范化目标”。用户确认只更新同一个预检节点，不会按轮询或 Token 数重复生成时间线记录。

## 0.7 拓扑与灵感事件

分层模式增加路线注册/成员分配、typed message 发布/投递/拒绝/过期、Fact/Insight/Negative 变化、义务开关、Bridge/Conflict、路线合并/冷却和 graph freeze 事件。`detailed` 显示 source/target route、消息类型、memory tier、拒绝原因、proof debt 变化和消息成本；`compact` 只显示高层动作。

Inspiration Engine 记录 `inspiration_triggered`、表示切换开始/候选、类比搜索/映射、构造开始/提案、不变量候选、`meta_strategy_replan`、Surprise Budget 预留/路线创建，以及提案拒绝、materialize 和验证事件。Detailed payload 可包含 trigger、mechanism、novelty、目标义务、估计调用、审稿建议、派生路线和 debt 前后值，但永远不包含模型私有思考链。

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
✓ 04:35  已提交证明检查点 C3
! 05:10  原 API 重试耗尽，切换备用 Agent
◐ 05:11  从 C3 继续当前子目标
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

恢复运行使用：

```bash
curl -N -X POST http://127.0.0.1:8000/resume/stream \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $MATHPROOFMESH_SERVER_TOKEN" \
  -d '{"run_id":"problem-001"}'
```

`ActivityStream` 会读取已有 `activity.jsonl`，继续原序号和累计耗时，并在最终 JSON/Markdown 时间线中保留恢复前后的全部事件。

## 7. 与 DeepSeek 响应流的区别

项目中存在两个彼此独立的 SSE 通道：

1. `AgentConfig.streaming: true`：**DeepSeek API → MathProofMesh 后端**。后端逐块接收模型响应，在本地聚合完整 JSON、usage 和非敏感传输元数据；不会把未完成片段或 `reasoning_content` 直接展示给用户。
2. `/solve/stream` 与 `/resume/stream`：**MathProofMesh 后端 → 浏览器前端**。它们只推送 Activity 阶段事件，例如“正在探索”“提交检查点”“切换备用 Agent”“综合完成”。

因此，开启 DeepSeek 流式读取并不等于显示模型逐 token 思考。它主要改善长响应的传输方式、连接可观测性和中断检测；用户看到的仍是经过整理的 Activity 时间线。
