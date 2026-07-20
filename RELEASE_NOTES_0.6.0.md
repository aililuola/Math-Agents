# MathProofMesh 0.6.0

发布日期：2026-07-20

## 重点

0.6.0 在 0.5.1 稳定工作流上加入 reasoning-first computation：Explorer 仍以抽象数学推理为主，但可以把精确定义、决策相关的数值检查交给受控工具，而不是在推理文本中展开大量枚举。

## 新增

- `ComputationHint`、`ExperimentSpec`、`ExperimentProgram`、`ExperimentResult`、`ComputationDecision` 和证据等级 Schema。
- `InitialExplorationTurn` / `ContinuationTurn` 计算往返协议。
- 定向反驳快速通道，以及广泛搜索的停滞和 Meta-Reviewer 门控。
- SymPy、模剩余类、Z3 有限整数域、NetworkX 图证书、精确递推、精确坐标几何和数值反例 Handler。
- 工具版本、精度、种子和镜像 digest 感知的内容缓存。
- `runs/<run_id>/experiments/<request_hash>/` 完整实验审计目录。
- 关键实验的终审重放和哈希验证。
- 默认关闭的 Docker Python 沙箱及静态源码安全检查。
- DeepSeek 冒烟/正式配置启用强类型工具；模型生成 Python 继续关闭。
- 离线枚举密集 token 代理基准。

## 正确性约束

- `not_refuted` 永不升级为 `verified`。
- 有限范围通过不会直接提升 Reviewer PASS。
- 独立复核的反例会覆盖仍使用该命题的模型 PASS。
- 工具异常只产生 `inconclusive`，不判定数学命题为假。
- 实验请求和结果不能直接提交 ProofCheckpoint 或进入 Claim 库。
- 每个分段默认最多一次计算往返，并回到同一 Explorer 和同一父检查点。

## 兼容性

- 通用配置的 `computation.enabled` 默认是 `false`，继续使用旧的 `ProofAttempt` / `ProofDelta` 响应协议。
- DeepSeek 两个预设默认开启新协议。
- `mathproofmesh.tools` 保留兼容导出，旧的 `ToolBroker`、`parse_expression` 和 `UnsafeExpressionError` 导入不需要修改。
- 0.6.0 新增 `networkx` 和 `z3-solver` 依赖；从旧分支切换后需重新执行一次 editable install。

## 验证

发布前必须通过 pytest、Ruff、格式检查、compileall、离线计算基准、源码/构建产物密钥扫描、Wheel/sdist 构建和隔离安装演示。真实 DeepSeek 调用不会在没有凭据时自动执行。
