# 可复现验证记录

**验证日期：2026 年 7 月 19 日。** 这一记录区分三种层级：源码静态质量、确定性 Mock/HTTP Mock 行为、真实供应商联调。前两类已经执行；第三类因当前构建容器无法解析外部 API 域名而未完成。

## 1. 一键复验

安装开发依赖后运行：

```bash
./scripts/validate.sh
```

脚本依次执行：

```text
compileall → Ruff → pytest → deterministic Mock demo
```

Mock demo 不发出任何外部 API 请求。它验证从题目冻结、策略生成、隔离探索、Claim 提取、结构/详细审稿、Meta-review、综合到最终审计的完整调用链，并检查运行报告和通信图等产物。

## 2. 本次交付的实际结果

| 检查 | 结果 |
|---|---|
| `python -m compileall -q src tests` | PASS |
| Ruff | PASS |
| `PYTHONPATH=src pytest -q` | **24 passed** |
| 源码模式 deterministic demo | `status=verified`，19 calls |
| 构建 `mathproofmesh-0.4.0-py3-none-any.whl` | PASS |
| 将 wheel 安装到隔离 target 目录 | PASS |
| 从已安装 wheel 执行 CLI demo | `status=verified`，19 calls |
| 示例配置解析与预算比例校验 | PASS |
| 五个用户提供 key 的精确秘密扫描 | 源码、Wheel 和交付目录均无匹配；测试中保留一个专用于脱敏测试的伪造 `sk-*` 字符串 |
| DeepSeek 官方域名真实请求 | 本轮构建未使用用户凭据；应在本机运行 `probe` 验证 |

演示定理为前 `n` 个正奇数之和等于 `n^2`。这里选择简单定理是为了对状态机和审计链做稳定、确定性的基础设施测试，不能把它解释为系统已经证明了研究级开放问题。

## 3. 测试覆盖

自动化测试包括：

- 题目契约、Claim 内容哈希与篡改检测；
- 模型自由文本中的平衡 JSON 对象提取；
- 非法工具表达式拒绝；
- SymPy 等价检查、因式分解与有界反例搜索；
- 确定性反例强制覆盖模型错误 PASS；
- Claim 缺失依赖、依赖闭包和循环风险；
- 策略差异化、稀疏来源限制和上下文软预算；
- Agent 选择的跨进程可复现性；
- DeepSeek V4 Pro 请求体：`thinking.type=enabled`、`reasoning_effort=max`、JSON mode、`user_id`；
- DeepSeek 非流式兼容行为，以及 SSE `stream=true`、`stream_options.include_usage=true`、
  usage-only 尾块、本地内容聚合和 `reasoning_content` 增量哈希脱敏；
- DeepSeek 模型列表解析与 `reasoning_content` 脱敏；
- Activity 事件及嵌套 metrics 的凭据脱敏、JSON 安全化、监听回调、JSONL 追加和 JSON/Markdown 导出；
- CLI compact 时间线渲染与长 API 调用的低频无内容心跳；
- 多 Agent 全链运行、最终验证状态和 Activity/审计产物。

## 4. 尚未实机验证的部分

本轮构建没有使用聊天中出现的真实凭据，也没有向 DeepSeek 发起计费请求。尚未验证：

- 当前具体模型名和账户权限；
- OpenAI-compatible 第三方网关的字段差异；
- Anthropic、Gemini 与兼容网关的实时限流/错误响应；
- 特定模型对长 JSON Schema 输出的遵循率；
- 大规模多 key 并发下的真实费用与时延。

这些边界不会被 Mock 测试掩盖。真实联调应先运行 `mathproofmesh probe`，再使用低预算冒烟配置，详见 [DEEPSEEK_V4_PRO.md](DEEPSEEK_V4_PRO.md) 与 [DEPLOYMENT.md](DEPLOYMENT.md)。
