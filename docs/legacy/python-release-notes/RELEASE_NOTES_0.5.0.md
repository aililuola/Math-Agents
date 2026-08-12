# MathProofMesh v0.5.0

## 主要新增

### 1. 已验证证明步骤级断点续推

- 新增 `ProofDelta`、`ProofCheckpoint` 和 `CheckpointStatus`；
- 长证明按少量步骤/子目标分段生成；
- 本地守卫检查问题哈希、路径、父检查点、段号、依赖闭包和 ID 唯一性；
- 独立 Reviewer 通过且达到阈值后才原子提交新检查点；
- 检查点父子链单调，旧父节点不能覆盖当前 latest；
- 被拒绝的 Delta 可审计保存，但不进入引理库或恢复状态。

### 2. 同 key 重试与跨 key/Agent 接力

- API 超时、断连、远端协议错误、408/409/429/5xx 先由原 key 指数退避重试；
- 重试耗尽后把同一 `PromptBundle`、最新检查点和当前子目标交给备用 Agent；
- 支持限制备用 Agent 数量；401/403 跳过同 key 重试并允许换 key，明显的请求级非暂态 4xx 不扩散；
- 连续失败的 key 进入短暂指数冷却，后续路径优先健康 Agent；
- checkpoint Reviewer 及其备用候选强制排除当前 Delta 作者；
- Activity 时间线记录接力链，但不记录私有 `reasoning_content`。

### 3. 进程级 `resume`

- 新增 CLI：`mathproofmesh resume <run_id> --config ...`；
- 新增 HTTP：`POST /resume` 与 `POST /resume/stream`；
- 恢复阶段快照、独立持久化的 Triage/Strategy/LemmaMemory、ProofCheckpoint、Claim、验证报告、Meta-review、调用预算和 Agent 累计 usage；
- 第一个阶段快照尚未生成时，也能从已冻结的 `ProblemContract` 重新进入；
- `runtime_ledger.json` 在逻辑调用开始和 usage 更新后原子持久化，恢复预算按同一 run 累计而不是清零；
- Activity 时间线跨进程保持连续序号和累计耗时。

### 4. DeepSeek SSE 完整性强化

- 保留非流式默认行为；
- DeepSeek 配置继续使用 `stream=true` 和 `stream_options.include_usage=true`；
- 缺少 `[DONE]` 或请求的最终 usage 摘要均视为不完整传输；
- 半截 content/JSON 不会推进证明检查点；
- `reasoning_content` 仍只增量计算长度和哈希，不落盘、不跨 Agent。

### 5. 文档与可观测性

- 新增 `docs/CHECKPOINT_RESUME.md`；
- README、架构、提示词协议、部署、DeepSeek、Activity 和验证文档同步更新；
- 运行报告新增检查点数量、恢复来源、每条路径的最新 checkpoint 和 failover chain；
- prompt 改为内容寻址的不可变归档，重复阶段不再覆盖旧提示词；
- 新增 GitHub Actions Python 3.11 确定性 CI。

## 兼容性

- `config.example.yaml` 的 continuation 默认关闭，旧配置继续使用整篇 `ProofAttempt`；
- `config.deepseek-v4-pro.yaml` 与 smoke 配置显式开启检查点续推；
- `main` 不应直接覆盖，建议从 v0.4.0 功能分支创建新的 v0.5.0 分支。

## 验证

最终构建结果记录在 `BUILD_INFO.json` 和 `docs/VALIDATION.md`。真实 API key 未写入源码、配置、测试、wheel 或源码 ZIP。
