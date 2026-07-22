# 可复现验证记录

**验证日期：2026 年 7 月 22 日。** 本记录严格区分源码静态质量、确定性 Mock/HTTP Mock 行为、Docker 沙箱探针和真实供应商联调。0.7 源码、Mock、拓扑 benchmark 与 Docker 固定镜像隔离探针已执行；整个 0.7 自动化过程没有读取 API key，也没有发起真实供应商请求。

## 1. 一键复验

安装开发依赖后运行：

```bash
bash scripts/validate.sh
```

脚本依次执行：

```text
compileall(src + tests)
→ Ruff lint
→ Ruff format check
→ pytest
→ deterministic continuation demo
```

演示不访问外部 API。它完整经过题目冻结、策略生成、隔离探索、`ProofDelta`、独立检查点验证、`ProofCheckpoint` 提交、Claim 传递、候选验证、Meta-review、综合和最终审计。

## 2. 最终源码验证结果

| 检查 | 结果 |
|---|---:|
| `python -m compileall -q src tests` | PASS |
| `ruff check .` | PASS |
| `ruff format --check .` | PASS |
| `python -m pytest -q` | **239 passed**（安装 `.[dev,server]`，包含 `z3-solver`） |
| deterministic demo | `verified`，23 calls，30,540 tokens |
| continuation deterministic demo | `verified`，25 calls，32,920 tokens |
| demo 证明检查点 | 3 条路径，共 6 个 checkpoint（3 个 genesis + 3 个已验证完成段） |
| DeepSeek 非流式 HTTP Mock | PASS |
| DeepSeek SSE HTTP Mock | PASS |
| SSE `[DONE]` 缺失守卫 | PASS |
| 请求 usage 但尾部 usage 摘要缺失守卫 | PASS |
| `reasoning_content` 增量脱敏 | PASS |
| YAML 配置解析 | PASS |
| 源码秘密扫描 | 源码、配置和文档中真实 key 与通用长 `sk-*` 模式均为 0 匹配 |

## 2A. 0.7 分层拓扑与灵感验收

新增测试覆盖 Typed Message/receipt、问题哈希和量词作用域、Broker 去重/TTL/限流、初始隔离、Route Team 独立性、Fact/Insight/Negative 门控、Proof Graph 生命周期、Bridge/Conflict、机制级重复路线、Blind Final Review、checkpoint/resume exactly-once、调度器 graph/inspiration 信号，以及 Validation Escalation、Capability 和形式化微证书安全降级。

P0 Inspiration Engine 的每个要求均有确定性覆盖：触发策略、Representation Switchboard、只读已验证本地库的 Analogy Agent、Auxiliary Construction Inventor、Invariant/Monovariant Hypothesis、Reverse Goal Analyzer、Persistent Meta-Strategist、Surprise Budget、Novelty Signature、独立 Inspiration Referee、shadow 不改变状态、active 创建新路线和 resume 幂等 materialization。

离线拓扑 benchmark：

```text
6 contract cases
11 required variants/ablations
20/20 local component contracts passed
0 provider calls
```

结果保存在 `benchmarks/topology/mock_benchmark_results.json`。其中调用数、token 和费用明确是 Mock 估算，不是供应商遥测；该 benchmark 用于回归拓扑语义，不能单独证明真实 IMO 正确率提升。

演示定理为前 `n` 个正奇数之和等于 `n^2`。选择简单定理是为了使状态机、故障恢复和审计链测试可重复，不能据此声称系统已经解决研究级开放问题。

## 3. 断线、接力与进程恢复验证

### 3.1 已完成运行的幂等恢复

先完成一次 continuation demo，再对同一 `run_id` 调用 `resume`：

```text
首次：verified，22 calls，25,969 tokens，6 checkpoints
恢复：verified，22 calls，25,969 tokens，6 checkpoints
```

最终审计已经通过时，恢复不会重复调用模型，也不会重复计费本地已记录的 token。

### 3.2 未完成检查点后的继续推理

测试构造了两段证明：第 1 段只完成连续平方差恒等式，第 2 段完成望远镜求和。首次运行将总调用上限设为 4，并关闭阶段级快照，以模拟更苛刻的中断：

```text
中断前：budget_exhausted，4 calls
最新恢复点：segment 1，proof_complete=false
阶段快照：不存在
```

随后把总调用上限提高至 40，并对同一 `run_id` 执行 `resume`：

```text
恢复后：verified，累计 13 calls
恢复来源：原 segment-1 committed checkpoint
新状态：segment 2，proof_complete=true
```

这同时验证了：

- 没有阶段快照时从 `ProblemContract` 和独立持久化的 Strategy/LemmaMemory 恢复；
- 未完成路线从自己的 `latest.json` 继续，而不是重新生成第 1 段；
- 预算、usage 和 Activity 序号跨进程累计；
- 已提交父检查点保留在不可变链中。

### 3.3 跨 API-key/Agent 接力

集成测试令首选 Explorer 在 `ProofDelta` 请求中抛出网络断连，备用 Explorer 返回合法增量。结果为：

```text
primary key 正常调用级重试耗尽
→ backup key 接收同一 checkpoint + current goal
→ 独立 Reviewer 验证
→ checkpoint source_agent_id=backup
→ failover_chain=[primary, backup]
```

还验证了：

- 401/403 在原 key 上不重复请求，但可以切换另一个 key；
- 连续失败的 key 进入短暂冷却，后续调度优先健康 Agent；
- checkpoint Reviewer 的首选和故障转移候选均排除当前 Delta 作者；
- Synthesizer 连接失败时可切换备用 Agent，最终审计继续排除实际完成综合的 Agent；
- 所有备用 Agent 均失败时，原 committed checkpoint 不被覆盖。

## 4. 检查点与信息完整性测试

自动化测试覆盖：

- `ProblemContract`、Claim 和 `ProofCheckpoint` 内容哈希；
- checkpoint 父节点必须等于当前 `latest.json`；
- segment 必须严格加一；
- problem/path/strategy 身份不可改变；
- proof step ID、Claim ID 不可重复；
- 新步骤只能依赖已提交对象或同一 Delta 中更早的步骤；
- Claim 自依赖、前向依赖和未验证依赖会被本地守卫拒绝；
- Reviewer FAIL/UNCERTAIN 时 Delta 只能进入 rejected 目录；
- prompt 使用内容寻址归档，重复阶段不会覆盖旧提示词；
- 比阶段快照更新的 `lemma_memory.json` 会在恢复时被重新加载；
- 被截断的 SSE、半截 JSON 和私有 `reasoning_content` 不会成为恢复点。

## 5. 调度器回归验证

新增回归测试覆盖以下通用情形：

- 路线覆盖率按 `current_paths / max_paths` 计算，而不是按初始路线数计算；
- 所有已审查路线均失败、仍有容量且预算允许时，本轮至少保留一个 `widen`；
- 达到 `max_paths` 或最终修订储备不足时，不越界拓宽；
- Structural FAIL 会进入路径统计并降低有效进展；
- 高置信度策略级失败不会因证明步骤较多而持续获得 `deepen`；
- 执行级、计划级与未知失败只获得配置允许的修补次数；
- 重复失败后进入可配置冷却；
- 动作成本根据新增路线数、continuation 段数、Reviewer、Claim 提取、验证和 Meta-review 动态计算；
- 最终预算储备根据 `reserve_revision_cycles` 与 `max_revisions` 计算；
- 调度产物记录每个候选动作的排名、分数、预计成本、未选原因和预算阻断原因。

当前包含上述历史回归在内的完整自动化结果为 `239 passed`。分段确定性完整演示结果仍为 `verified`，25 calls，32,920 tokens。所有路线数、动作数、修补次数和调用预算均来自配置，不绑定某一道题。

## 6. 终审边界与修订回归验证

新增测试明确区分形式要求与数学条件：

- 标准命名定理不需要提供书目链接或页码，但必须写出准确调用形式，并从已有步骤显式验证全部假设；
- 缺失定理适用条件时，最终结构门返回执行级 FAIL，不能直接形成 `verified`；
- 预算允许时，缺口进入一次定向修订；修订稿必须重新执行结构审计和详细审计；
- 修订前的 PASS 不会复用，修订动作本身也不会自动升级最终状态；
- 策略级错误不通过继续润色同一证明修复；
- 确定性工具找到反例时仍覆盖模型 PASS。

集成回归模拟了“最终证明遗漏一个实际为真的定理假设”的情形。首次结构审计阻断详细审计；修订器补出显式推导；随后新的结构审计和详细审计均通过，最终状态才成为 `verified`。

## 7. 历史 0.6 安装产物验证

下列记录属于 v0.6.0 基线产物。0.7.0 本次提交只发布源码，不提交 Wheel/sdist；`dist/` 继续由 `.gitignore` 排除。

构建命令：

```bash
python -m hatchling build
```

结果：

| 产物 | 结果 |
|---|---:|
| `dist/mathproofmesh-0.6.0.tar.gz` | PASS |
| `dist/mathproofmesh-0.6.0-py3-none-any.whl` | PASS |
| wheel 安装到隔离 target | PASS |
| 从已安装 wheel 执行 continuation demo | `verified`，25 calls |
| wheel/sdist 通用长 `sk-*` 凭据模式扫描 | 0 匹配 |

## 8. GitHub CI

仓库包含 `.github/workflows/ci.yml`，对功能分支 push 和 Pull Request 执行：

```text
Python 3.11
→ pip install -e ".[dev,server]"
→ bash scripts/validate.sh
```

该工作流只运行确定性 Mock 测试，不读取 DeepSeek secrets，也不会产生真实模型费用。远程 CI 状态应以相应 GitHub commit/PR 页面为准。

## 9. 0.6.0 推理优先计算验证

新增自动化测试覆盖：

- 模糊搜索拒绝、定向反驳快速通道、停滞/Meta 审批和软硬配额；
- `not_refuted`、有限证据、反例、穷尽证书和形式证书的非对称语义；
- 反例由 Handler 和 Broker 双重重放，并覆盖仍使用被反驳命题的模型 PASS；
- 请求/结果往返不增加证明分段或提交中间检查点；
- 模、Z3、图证书、递推、精确几何和数值反例 Handler；
- 工具异常降级为无结论；
- 缓存键、实验 Ledger、进程恢复和最终关键证据重放；
- Python AST 禁止项与 Docker 无网络、只读、非 root、资源上限和只读临时挂载参数；
- 离线枚举密集代理基准的正确率和可见文本 token 降幅。

离线代理基准覆盖 1,000,602 个声明案例，3/3 预期结果一致；结构化请求/结果约 2,197 tokens，相对逐例文本代理约 8,004,816 tokens，估算减少 99.97%。这不是供应商隐藏思维 token 的实测值。

发布时的最终测试数量、构建产物和哈希由 `BUILD_INFO.json` 记录。真实 DeepSeek V4 Pro 调用仍标记为 `NOT RUN`，除非用户主动提供凭据并执行 probe/smoke。

## 10. 尚未实机验证的部分

本次构建没有使用聊天中提供的真实 DeepSeek key。尚需在用户本机验证：

- 五个 key 的当前有效性、余额、账户级并发限制和模型可见性；
- 真实 DeepSeek V4 Pro 对长 `ProofDelta` JSON Schema 的遵循率；
- 真实 SSE 长连接在本地网络、代理和防火墙下的稳定性；
- 真实 429/5xx 的 `Retry-After` 行为和供应商计费；
- 多 key 大规模并行下的实际成本、延迟和错误相关性。
- Docker Desktop/WSL2 已安装，固定 digest 的 `python:3.11-slim` 已在无网络、只读、非 root、cap-drop、进程/内存/CPU 限制下完成 `sandbox-ok` 实机探针。真实模型生成程序的数学正确性仍必须经过现有证据门和独立重放。

真实联调应先执行 `mathproofmesh probe`，再使用 `config.deepseek-v4-pro.smoke.yaml`，最后切换正式配置。详见 [DEEPSEEK_V4_PRO.md](DEEPSEEK_V4_PRO.md)、[CHECKPOINT_RESUME.md](CHECKPOINT_RESUME.md) 与 [DEPLOYMENT.md](DEPLOYMENT.md)。

## 11. v0.7 最终 P0 事实门与灵感拒绝回归

本轮只验证最终 P0 bugfix，不调用真实 DeepSeek API，也不改变调度、预算、Prompt 策略或供应商参数。确定性测试覆盖：

- 被拒 `InspirationProposal` 可进入 Blind Negative Packet，保留数学内容和拒绝理由，但不泄漏 Agent/Route 身份；
- Active hierarchical + Active Inspiration + rejected proposal 的完整 Mock 流程最终为 `verified`，并且没有 `run_failed`；
- hierarchical 全局事实统一经过 `build_admissible_fact_context()`，只接收 Broker admitted 且仍存在于 TypedMemory 的 Fact；
- ProofDelta/ProofAttempt verifier、工具复核、Synthesizer、Blind Judge 与 Final Revision 均不能看到被 Route Referee 拒绝的 legacy marker Claim；
- Typed Fact 为零时不允许 legacy Claim 补位，未满足的 typed dependency fail closed；
- 旧 checkpoint 中只有 LemmaMemory 的历史 Claim 会被隔离，不会自动升级为全局 Fact；
- `legacy_sparse` 继续使用原有 LemmaMemory 兼容路径；
- AST 回归测试禁止关键 hierarchical 全局路径重新直接调用 `memory.verified()`。

最终完整验证结果记录在 `BUILD_INFO.json`。Pytest、Ruff check、Ruff format check、`compileall` 和离线 topology Mock benchmark 全部通过；真实 provider 调用保持 `NOT RUN`。

## 12. 旧 checkpoint 路线重建与报告事实资格

新增动态回归覆盖 triage 已保存、Strategy 尚未生成、且不存在 RouteRegistry、MessageBroker、TypedMemory 的早期 checkpoint。恢复后系统必须先生成 Strategy，再幂等重建 Route、Prover 成员和稀疏邻居，保存一致的 hierarchical checkpoint，最后才进入证明调用。

回归测试明确断言：

- 每个恢复后的 Strategy 都能解析到 Route；
- 所有证明调用使用 `route_prove`，legacy `proof_continuation` 调用数为 0；
- 被隔离的 legacy marker 不出现在任何 Route Prover prompt；
- 实际选中的 Prover 已登记到 RouteRegistry；
- Route Team、MessageBroker 和 TypedMemory 继续正常执行；
- 缺失 Route 或 typed route context 时 fail closed，不允许静默绕回 LemmaMemory；
- 运行报告只把 Broker admitted、独立审稿且仍在 TypedMemory 的交集称为全局 Fact；旧 Claim 只列入迁移历史。

新增上下文策略回归还验证：

- 显式 `message_id` 和 `content_hash` 引用及其完整依赖闭包优先于 Jaccard 词法相似度；
- `ContextPurpose` 会改变 Fact 排序字段、字段投影和全局字符预算；
- Blind NegativeMemory 受 `max_negative_context` 与字符预算约束，但精确反例和显式冲突强制优先；
- 强制 Fact 或 Negative 证据缺失时，确定性 Blind Context Gate 覆盖模型 PASS；
- Blind packet 仅保留 artifact 文件内容 SHA-256、证书类型和 replay 状态，不包含原始路径、Agent 或 Route 名称。

离线 topology benchmark 的调用数、token、成功率和消融指标均为 component-contract Mock 数据，用于确定性回归，不是实际 IMO 题集的性能测量。

## 13. 高级灵感机制补全验证

本轮把此前尚未形成完整运行闭环的四类能力接入 Active Inspiration：

- 领域算子插件：数论、组合、不等式与几何算子携带前置条件、变换、派生义务、可逆性、快速失败检查和已知失败模式；
- 受控 Surprise Mutation：使用固定种子生成可复现的 `dualize`、`quotient`、`lift`、`encode_as_graph` 等变异指令，模型返回不得暗中替换已准入指令；
- 双向前沿：前向前沿只读取 Broker admitted Typed Facts，后向前沿从目标义务展开，仅在两者之间生成可审计的 Bridge Lemma；
- Inspiration Composer：只组合指向相同或相邻义务、签名互补且至少一项通过快速反驳检查的提案，组合结果单独接受 Referee/Skeptic 审查，并受每次触发的路线物化上限约束。

同时加入项目本地跨运行学习。只有最终 `verified` 且被最终证明引用的正面经验可以进入经验库；失败迁移和机制结果以结构化记录保存，不保存私有推理、API 输出或原始 prompt。学习目录固定在项目内 `.mathproofmesh/learning`，已由 `.gitignore` 排除，并通过路径越界拒绝、原子写入、损坏记录跳过和 checkpoint 恢复测试。

最终离线验收结果：

```text
pytest: 239 passed
ruff check: PASS
ruff format --check: PASS (173 files)
compileall: PASS
topology mock benchmark: PASS
component contracts: 20/20
provider calls: 0
```

这些结果验证的是确定性协议、门控、恢复和组件闭环，不宣称真实 IMO 正确率提升；真实 DeepSeek API 仍未调用。
