# MathProofMesh 架构说明

## 1. 设计目标

系统需要同时满足四个目标：

1. **搜索有效性**：并行 Agent 必须探索不同数学机制，而不是生成同义改写；
2. **通信保真性**：中间结果在压缩和转交时不能丢失假设、依赖、适用范围和失败信息；
3. **验证可靠性**：生成者不能自证，最终答案不能只靠多数票；
4. **成本可控性**：通信、详细审稿和修订应按预期价值触发。

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

### 6.1 路径统计

系统根据：

- 完成状态；
- 证明步数和关键步；
- 已提取引理；
- 未解决缺口；
- 自评和独立验证；
- 策略新颖性；
- 停滞轮数；
- 已消耗 token；
- 结构是否有效；

构造 `PathStats`。

当前实现中的局部进展近似包含：

\[
p=\operatorname{clip}
\bigl(0.06\,n_{step}+0.08\,n_{lemma}+0.35\,I_{complete}
-0.06\,n_{gap},0,1\bigr),
\]

并与验证分数结合。系数是透明、可替换的调度启发式。

### 6.2 验证动作

\[
Q_{verify}=0.55\,(I_{complete}\ \text{or}\ p)
+0.35u+0.10n,
\]

其中 `u` 为不确定性，`n` 为新颖性。完整但尚未独立验证的路径优先复核。

### 6.3 深挖动作

\[
Q_{depth}=0.45p+0.25n+0.20u
+0.10\min(1,g/3)-0.18\min(2,s),
\]

其中 `g` 是缺口数，`s` 是停滞轮数；结构失败另加惩罚。含义是：有进展、有独特性、缺口可定位的路径值得继续；长期停滞的路径不应无限吞噬预算。

### 6.4 拓宽动作

\[
Q_{wide}=0.35(1-c)+0.30u+0.20\min(1,\bar s)+0.15(1-p_{max}),
\]

其中 `c` 为当前路径覆盖率。现有路径少、整体不确定、停滞且最佳进展低时，生成新机制。

### 6.5 软预算压力

默认目标：

\[
(breadth,depth,verification,synthesis)=(0.30,0.35,0.25,0.10).
\]

若某阶段已超过目标，其动作分数会降低，但未使用的预算可以借用。系统保留至少三次调用用于“综合 + 结构终审 + 详细终审”，避免探索耗尽全部预算。

## 7. 报告聚合

聚合不采用简单多数通过。主要规则：

- 任一确定性反例或 Lean 拒绝可强制 FAIL；
- 问题哈希不一致强制 FAIL；
- 结构门 FAIL 阻断详细 PASS；
- FAIL 报告必须携带至少一个 issue；
- 多 Reviewer 结果有冲突时计算分歧并降低聚合置信度；
- 最终 PASS 还需达到 `verification_pass_threshold`；
- `first_error_step` 优先取最早、最明确的问题。

## 8. 安全与故障模型

### 8.1 API key

- 推荐只配置 `api_key_env`；
- 脱敏配置不写入 key；
- 原始供应商响应可能包含供应商元数据，应按敏感数据管理；
- 每 key 独立限流和并发；
- `.env` 被 `.gitignore` 排除。

### 8.2 工具

- 任意 Python 禁止；
- SymPy 表达式 AST 白名单；
- Lean 默认关闭；
- 外部工具设置超时；
- 生产环境应在无网络、低权限容器执行形式化工具。

### 8.3 Prompt injection

数学题本身仍可能包含伪指令。系统的 `COMMON_SYSTEM` 把题面视为不可变数学数据，并要求只输出 Schema；但模型层注入不能仅靠提示词完全消除。公开服务应限制输入来源、工具能力和文件访问。

### 8.4 故障恢复

每阶段写 checkpoint。当前版本保存了恢复所需状态，但 CLI 尚未提供“从任意 checkpoint 自动续跑”的完整命令；这属于可扩展点。已有运行即使失败，也会保留报告、原始响应和部分结果。
