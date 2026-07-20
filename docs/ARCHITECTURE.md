# MathProofMesh 架构说明

## 0.7 分层稀疏扩展

0.7 保留下文的 v0.6 编排器、`SparseTopologyRouter`、软预算和推理优先计算主干，并在 `topology.mode: hierarchical_sparse` 时增加四个平面：控制平面、路线局部团队、全局 Proof Obligation Graph 黑板和盲终审平面。跨路线只允许 `MessageBroker` 投递通过作用域、证据和独立审稿门控的 `MessageEnvelope`，不共享原始 transcript 或 `reasoning_content`。

新增包边界为：

- `communication/`：消息、回执、路线注册、稀疏 Broker 与政策；
- `proof_graph/`：义务图、桥梁、冲突与机制级重复路线检测；
- `teams/`：按需启用的 Prover/Skeptic/Tool/Referee；
- `inspiration/`：表示切换、类比、构造、不变量、逆向目标、持续元策略、Surprise Budget、Novelty 与独立 Referee；
- `verification/`：升级验证、能力画像、Proof Mutation 和形式化微证书接口。

Proof Graph 与 Inspiration Engine 都支持 `off | shadow | active`。Shadow 只记录候选信号，不改变旧调度；Active 才能正式分配预算、附着义务或创建路线。完整拓扑见 [COMMUNICATION_TOPOLOGY.md](COMMUNICATION_TOPOLOGY.md)，灵感流水线见 [INSPIRATION_ENGINE.md](INSPIRATION_ENGINE.md)。

## 1. 设计目标

系统需要同时满足四个目标：

1. **搜索有效性**：并行 Agent 必须探索不同数学机制，而不是生成同义改写；
2. **通信保真性**：中间结果在压缩和转交时不能丢失假设、依赖、适用范围和失败信息；
3. **验证可靠性**：生成者不能自证，最终答案不能只靠多数票；
4. **成本可控性**：通信、详细审稿和修订应按预期价值触发；
5. **可恢复性**：网络或进程中断不得迫使所有已验证数学进展从头重算。

这四个目标存在张力。更多并行路径增加覆盖，但会增加验证成本；更长上下文保留信息，却提高费用并导致注意力稀释；更多 Reviewer 降低单点错误，却可能产生相关误判。因此系统采用显式状态、软预算和分层门控，而不是固定 DAG。

## 2. 运行时组件

### 2.1 `ProblemContract`

字段包括：

- 原始题面和规范化题面；
- 题型、交付物、定义、硬约束、允许工具；
- 输出语言；
- 原始题面的 SHA-256 `integrity_hash`。

所有 `ProofAttempt` 和 `FinalProof` 必须携带同一哈希。模型即使声称 PASS，只要哈希不一致，本地守卫会强制 FAIL。

### 2.2 `AgentConfig` / `AgentRuntime`

每个 API key 对应一个 `AgentConfig`：

- `id`、provider、model、`api_key_env`；
- roles 和 specialties；
- 独立并发上限与 RPM；
- 温度、最大输出、超时、信任先验；
- 输入/输出单价。

`AgentRuntime` 维护：

- per-key semaphore；
- 滑动窗口限流器；
- 调用次数、token、费用、延迟、失败数；
- 动态 trust score。

全局 semaphore 控制整个运行的峰值并发。即使一个 key 被分配多个角色，也不会绕过其独立并发限制。

### 2.3 `StructuredAgentRunner`

所有 Agent 调用经过统一入口：

1. 保存实际 prompt；
2. 检查全局调用/token/费用预算；
3. 调供应商 API；
4. 内容寻址保存原始响应；
5. 提取首个平衡 JSON 对象；
6. 用对应 Pydantic 模型验证；
7. 若格式失败，在有限次数内只做 JSON 修复；
8. 记录阶段、Agent、证据引用和用量。

格式修复提示词明确禁止改变数学内容，只修字段和类型。若仍失败，抛出结构化输出错误，由 Orchestrator 决定降级或换 Agent。

### 2.4 `SparseTopologyRouter`

负责：

- 最大最小距离式策略多样性选择；
- 按角色、专长、负载、信任和跨提供商偏好分配 Agent；
- 选择独立 Reviewer；
- 根据风险决定 Reviewer 副本数；
- 从引理库选取语义相关且来源稀疏的 Claim；
- 计算审稿分歧；
- 导出通信图。

### 2.5 `LemmaMemory`

`ClaimCard` 包含：

- `statement`、`assumptions`、`conclusion`；
- 完整 `proof_steps`；
- 其他 Claim 依赖；
- `source_attempt_id`、`source_agent_id`；
- 原始证据引用；
- 范围限制、反例风险、自评和验证置信度；
- 内容哈希；
- proposed / verified / rejected / uncertain 状态。

状态规则：

- Summarizer 或 Explorer 生成的 Claim 一律以 `proposed` 入库；
- 只有验证报告可以升级；
- 依赖未验证、依赖缺失或依赖成环时自动降级为 `uncertain`；
- `external:*` 依赖可以保留，但外部定理的精确陈述和适用性仍由 Reviewer 检查；
- 去重时合并证据和范围，但不静默提升状态。

### 2.6 `ToolBroker`

默认工具：

- `sympy_simplify`；
- `sympy_equivalent`；
- `polynomial_factor`；
- `numeric_counterexample`；
- 可选 `lean_check`。

表达式先经过 AST 白名单；属性访问、下标、lambda、字符串、dunder 名称和非白名单函数都被拒绝。不存在任意 Python 工具。

数值测试的语义是单向的：

- 找到反例可以否定对应全称断言或暴露形式化错误；
- 没找到反例不构成证明。

### 2.7 `ProofDelta` / `ProofCheckpoint`

启用 continuation 后，Explorer 不再一次生成整篇长证明，而是从最近检查点只产生少量新增步骤：

- `ProofDelta`：候选增量，包含父检查点、步骤、Claim、当前/剩余子目标、风险和完成标记；
- `ProofCheckpoint`：经本地完整性检查与独立 Reviewer 通过后形成的持久状态；
- `runtime_ledger.json`：持久化调用预算和累计 Agent 使用量；
- `latest.json`：每条证明路径的原子恢复指针。

检查点形成有向父子链：

```text
C0(genesis) → C1 → C2 → C3
                         ├─ 被拒绝的 D4-a（不推进）
                         └─ 通过的 D4-b → C4
```

`ArtifactStore` 强制父检查点必须等于当前 latest，段号只能加一，且问题/路径/策略身份不变。这阻止旧进程、竞态调用或错误 Agent 覆盖更新后的证明状态。

## 3. 状态机

### Stage 0：冻结题目

创建 `ProblemContract`，保存配置的脱敏版本。

### Stage 1：Triage

Planner 只判断题型、难度、风险、工具和证明模式，不立即求解。简单题可以用较少路径；研究题允许更多轮次。

### Stage 2：策略生成与多样性筛选

Planner 生成 `StrategySet`。每个策略必须说明：

- 决定性数学机制；
- 与其他策略独立的依据；
- 预期引理；
- 瓶颈；
- 可证伪测试；
- 成功率与成本估计。

Router 先选可行性最高者，再贪心最大化与已选策略的最小语义距离：

\[
s^*=\arg\max_s\left[0.7\min_{q\in S_{\rm selected}}
(1-J(s,q))+0.3\,F(s)\right],
\]

其中 `J` 为 Jaccard 特征相似度，`F` 综合估计成功率和成本。

### Stage 3：隔离并行探索

每个 Explorer 只获得：

- 原题契约；
- 自己的策略卡；
- 与该路径相关的**已验证**引理；
- 该路径上一轮草稿和定向反馈（首轮为空）；
- 剩余调用预算。

首轮不发送其他候选答案，从协议上保护方向多样性。

### Stage 3.5：分段续推与检查点提交

当 `continuation.enabled=true` 时，每条路径执行以下循环：

1. 读取路径自己的最新 `committed` 检查点；不存在时创建 genesis；
2. 生成最多若干个新 `ProofStep` 和 `ClaimCard`；
3. 本地检查 problem hash、父节点、段号、依赖闭包和 ID 唯一性；
4. 由与作者不同的 Detailed Verifier 审查新增步骤；
5. 只有全部报告 PASS 且达到 `checkpoint_pass_threshold`，才提交新检查点；
6. 断线时丢弃未完成段，从旧检查点重试；原 key 重试耗尽后可切换备用 Agent；
7. 进程重启后，CLI `resume` 同时恢复阶段快照、独立持久化的 Triage/Strategy/LemmaMemory、runtime ledger 和各路径 latest 指针；第一个阶段快照尚未生成时也可从 ProblemContract 重新进入。

因此，跨 Agent 通信单位从“长 transcript”变为：

\[
\text{ProblemContract}+\text{StrategyCard}+\text{ProofCheckpoint}+\text{VerifiedClaims}+\text{CurrentGoal}.
\]

这既缩短上下文，也明确哪些结论可以安全复用。

### Stage 4：Claim 提取

Summarizer 把长草稿压缩成 Claim。它接收的是精简去重索引，而不是全部历史 Claim 的完整证明，从而减少重复上下文。

### Stage 5：结构验证门

Structural Verifier 检查：

- 原题是否被改变；
- 是否完成所有要求；
- 依赖 ID 是否缺失或循环；
- 是否把 proposed/uncertain Claim 当成定理；
- 引用是否包含精确陈述和适用条件；
- 非平凡关键步骤是否显式标记。

结构失败时默认不进行昂贵详细验证。失败或不确定可触发一个额外独立 Structural Reviewer，而不是广播给所有 Reviewer。

### Stage 6：详细验证

Detailed Verifier 对每一步：

1. 重述准确断言；
2. 列出依赖；
3. 检查 justification 是否推出 statement；
4. 查代数、符号、方向、等号条件、边界和穷尽性；
5. 主动找小例、极端例、反例和维数错误；
6. 定位首个无效步骤；
7. 需要时提出白名单工具请求。

若 Reviewer 之间分歧超过阈值、有人 FAIL/UNCERTAIN，或置信度低于阈值，才添加额外 Reviewer。

### Stage 7：Meta-review

Meta-Reviewer 不是另一个辩手。它接收压缩后的候选和独立验证报告，执行：

- 去重相同意见；
- 明示冲突；
- 按完备性、关键步骤、可修复性和验证进展排序；
- 将失败分为 execution、plan、strategy；
- 生成单一聚合反馈包。

### Stage 8：自适应动作

可能的动作：`WIDEN`、`DEEPEN`、`VERIFY`、`SYNTHESIZE`、`REVISE`、`STOP`。

### Stage 9：综合与终审

Synthesizer 收到：

- 最佳路径完整包；
- 其他被选路径的紧凑包；
- 与这些路径相关、含依赖闭包的已验证 Claim；
- Meta-review。

它必须选择一条一致的证明路线，不能平均不兼容证明。完成后由与 Synthesizer 隔离的 Structural 和 Detailed Reviewer 终审。可修复错误进入有限修订循环；策略级失败不通过润色强行修复。

## 4. 通信复杂度

### 4.1 全连接辩论

`N` 个 Agent、`R` 轮、每条平均消息长度 `L`：

\[
E_{\rm dense}=RN(N-1),\qquad
C_{\rm dense}=\Theta(RN^2L).
\]

若每轮还把累计历史发送给每个 Agent，实际 token 可能随轮数增长得更快。

### 4.2 稀疏审稿网

设：

- `P` 为探索路径数；
- `V≤P` 为进入验证的候选数；
- `r` 为每个候选的基础详细 Reviewer 数；
- `δ∈[0,1]` 表示触发额外 Reviewer 的候选比例；
- `K` 为 Meta-review 接收的候选数；
- `S` 为综合路径数。

忽略工具跟进时，消息边近似：

\[
E_{\rm mesh}
\approx P                    \quad\text{（策略分配）}
+P                            \quad\text{（Claim 提取）}
+V(1+r+\delta)                \quad\text{（结构/详细/条件复核）}
+K                            \quad\text{（Meta-review）}
+S+2                          \quad\text{（综合与终审）}.
\]

当 `r,K,S` 是小常数时，对路径数近似线性。它不是严格运行时上界，因为工具跟进、修订和多轮加深会增加边，但仍避免每轮 `N^2` 广播。

## 5. 上下文压缩与保真

### 5.1 不允许的压缩

- 截断公式中间字符；
- 删除假设后只留结论；
- 把“未证猜想”改写成“引理”；
- 丢掉反例和死路；
- 合并来源后失去 provenance；
- 用自评 confidence 代替验证状态。

### 5.2 使用的压缩

1. **Claim 抽取**：保留完整结构字段；
2. **去重索引**：仅用于 Summarizer 识别已有 Claim，包含 ID、hash、statement、conclusion、status、source；
3. **语义近邻**：按当前策略/反馈与 Claim 文本相似度排序；
4. **来源稀疏**：最多从 `neighbor_k` 个来源路径传入；
5. **依赖闭包**：发送 derived Claim 时尽量先发送其依赖 Claim；
6. **整包字符预算**：超预算时舍弃整个低优先级包，不截断单个 JSON 字段；
7. **原始证据引用**：需要时可从 `artifact://` 回溯。

`max_context_chars` 是软上限。最佳候选或首个关键 Claim 即使超长也会完整保留，因为截断数学对象比超预算更危险。

## 6. 广度—深度评分

### 6.1 路径统计与验证证据

系统按策略聚合历次 `ProofAttempt`，并同时接收结构审查报告和最终聚合报告。`PathStats` 记录：

- 证据支持后的进展和边际进展；
- 关键缺口是否减少；
- 最新验证结论、失败层级与置信度；
- 结构是否有效；
- 连续失败数和失败后的修补次数；
- 停滞轮数、新颖性、token 消耗和最后活跃轮次。

原始证明步数和候选引理数只形成一个局部草稿分数。若独立审查给出 `UNCERTAIN` 或 `FAIL`，该分数会按配置折扣；结构失败、计划失败和策略失败还分别受到进展上限约束。因此，一篇很长但核心断言错误的证明不会仅凭步数压过一个尚未探索的新机制。

### 6.2 失败层级与深挖资格

调度器区分：

- `execution`：机制可能正确，但局部推导或边界条件有误；
- `plan`：引理顺序或依赖计划存在系统性缺口；
- `strategy`：核心机制无法推出目标结论；
- `none/unknown`：证据不足以可靠分类。

各类型允许的修补次数由 `scheduler.max_*_repairs_per_path` 控制。默认允许执行级和计划级路线各进行一次定向修补，不自动深挖策略级失败。重复失败后进入可配置冷却期，避免连续数轮把预算押在同一断点上；冷却结束后是否重新考虑仍由边际进展和当前替代路线决定。

### 6.3 验证、深挖与拓宽评分

验证动作偏好“已有材料但独立证据不足”的候选；对于已经以高置信度 FAIL 的同一版本，不会重复进行无信息增益的复核。

深挖分数使用证据支持后的进展、新颖性、正边际进展和失败可修复性，并扣除结构失败、失败层级和连续停滞惩罚。不确定性和缺口只有在路线仍可修复时才提供有限加分，不能再使高置信度错误路线因“问题很多”而获得更高优先级。

拓宽覆盖率定义为：

\[
c=\min\!\left(1,\frac{\text{current paths}}{\text{max paths}}\right),
\]

而不是 `current paths / initial paths`。其评分综合剩余容量、整体不确定性、平均停滞、最佳证据进展和失败率。若所有已探索路线的最新版本都经过审查且均为 FAIL、尚未达到 `max_paths`，并且最终储备之后仍有足够预算，`force_widen_when_all_failed=true` 会保证本轮入选动作中至少有一个 `widen`。

### 6.4 可配置动作数与动态调用成本

每轮最多执行的动作数、一次拓宽生成的路线数以及各类修补上限均来自 `SchedulerConfig`，不存在针对某一道题写死的“两个动作”“三条初始路线”或“六条最大路线”。

动作成本由运行配置动态计算。例如，拓宽成本包含：

\[
C_{wide}=C_{plan}+k\,C_{path}+C_{postverify}+C_{meta},
\]

其中 `k` 是本次实际新增路线数；`C_path` 会根据是否启用分段续推、每次探索段数、Delta Reviewer 数和 Claim 提取而变化。验证成本按结构门加最大详细审查面板估计，并可通过 `verification_call_safety_margin` 增加安全余量。若完整批次不可负担，调度器会尝试缩小本次拓宽数量，而不是直接放弃所有新方向。

### 6.5 最终修订储备

默认软目标仍为：

\[
(breadth,depth,verification,synthesis)=(0.30,0.35,0.25,0.10).
\]

这些份额是可借用的软目标。除此之外，调度器保护：

\[
R_{finish}=C_{synthesis+audit}
+r\,C_{revision+reaudit},
\]

其中 `r=min(reserve_revision_cycles,max_revisions)`。因此正式配置可以保证在初次最终审计失败后仍至少有一个完整的修订与复核周期，而不是让搜索阶段耗尽最后一次调用。

### 6.6 可诊断决策

每轮 `BudgetDecision` 保存全部候选动作，而不只保存最终入选项。每个候选包含：

- 分数和排名；
- 路线与目标 Attempt；
- 是否满足失败修补策略；
- 是否被“全部失败后拓宽”规则强制保留；
- 动态预计调用数与计划新增路线数；
- 未入选、达到容量、冷却或最终储备不足等具体原因。

这些字段写入 `budget_decision_round_*.json`，并在 Activity `detailed` 模式中形成简短时间线，便于离线重放和调整通用配置。

## 7. 报告聚合

聚合不采用简单多数通过。主要规则：

- 任一确定性反例或 Lean 拒绝可强制 FAIL；
- 问题哈希不一致强制 FAIL；
- 结构门 FAIL 阻断详细 PASS；
- FAIL 报告必须携带至少一个 issue；
- 多 Reviewer 结果有冲突时计算分歧并降低聚合置信度；
- 最终 PASS 还需达到 `verification_pass_threshold`；
- `first_error_step` 优先取最早、最明确的问题。

## 8. 0.6.0 推理优先计算层

0.6.0 在 Explorer 与 ProofDelta 验证之间增加 `ComputationGate -> ToolBroker -> ExperimentResult` 子流程。它不是新的证明路线，也没有写入 Claim 库的权限。

- Planner 只产生不可执行的 `ComputationHint`。
- Explorer 必须提交完整 `ExperimentSpec`，Gate 才能决定 allow、defer 或 reject。
- 计算往返保持原 `parent_checkpoint_id` 和 `segment_index`。
- 强类型 Handler 与默认关闭的 Docker Python 沙箱位于 `src/mathproofmesh/computation/`。
- 实验 Ledger 独立于模型调用 Ledger，并按唯一请求统计路径配额和 CPU 时间。
- 终审重放关键实验；哈希、版本或结果不一致会阻断最终通过。

完整协议见 [COMPUTATION_POLICY.md](COMPUTATION_POLICY.md)。

## 9. 安全与故障模型

### 9.1 API key

- 推荐只配置 `api_key_env`；
- 脱敏配置不写入 key；
- 原始供应商响应可能包含供应商元数据，应按敏感数据管理；
- 每 key 独立限流和并发；
- `.env` 被 `.gitignore` 排除。

### 9.2 工具

- 宿主机直接执行任意 Python 禁止；模型生成程序仅可进入显式开启、固定镜像的 Docker 沙箱；
- SymPy 表达式 AST 白名单；
- Lean 默认关闭；
- 外部工具设置超时；
- 生产环境应在无网络、低权限容器执行形式化工具。

### 9.3 Prompt injection

数学题本身仍可能包含伪指令。系统的 `COMMON_SYSTEM` 把题面视为不可变数学数据，并要求只输出 Schema；但模型层注入不能仅靠提示词完全消除。公开服务应限制输入来源、工具能力和文件访问。

### 9.4 故障恢复

系统实现三层恢复：

1. **调用级重试**：同一 key 对超时、网络、远端协议错误、408/409/429 和 5xx 进行有限指数退避；401/403 不在同一 key 上重复请求；
2. **跨 key 接力**：重试耗尽后，备用 Agent 收到同一最新已验证检查点和子目标；401/403 可直接换 key，失败 Agent 会短暂冷却，证明作者被排除在自己的 checkpoint 审查链之外；
3. **进程级恢复**：`mathproofmesh resume <run_id>` 恢复阶段状态、独立结构化产物、证明路径、累计预算/token/费用和 Activity 时间线；没有阶段快照时从冻结原题重新进入。

未收到完整 SSE 结束标记或 usage 摘要的响应不会进入结构化状态。恢复不保存供应商私有 `reasoning_content`，也不声称恢复模型隐藏状态；它从最近已验证的外部数学状态发起一个新请求。详细协议见 [CHECKPOINT_RESUME.md](CHECKPOINT_RESUME.md)。
