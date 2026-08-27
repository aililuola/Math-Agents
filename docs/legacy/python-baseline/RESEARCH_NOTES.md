# 面向高难度数学推理的多智能体研究与代码实践综述

**检索截止日期：2026 年 7 月 19 日。** 本文关注的不是通用 Agent 编排，而是以下更窄的问题：多个大模型实例如何在高难度证明中保持方向多样性、积累可复用中间结论、以低通信开销传递准确语义，并通过独立检查降低错误闭环。

## 1. 结论先行

公开研究没有给出一个对所有数学任务都最优的固定 Agent 数量、通信图或广度—深度比例。较一致的证据却指向以下组合：

1. **先独立探索，后交换信息**，否则 Agent 容易在早期趋同；
2. **交换结构化结论而非整段思维记录**，尤其要保存假设、结论、依赖、适用范围和反例风险；
3. **验证应分层**：先做便宜的题意与结构门控，再对通过者做昂贵逐步验证；
4. **额外审稿应按风险或分歧触发**，而不是所有候选都全连接互审；
5. **Meta-Reviewer 应汇总审稿意见**，避免 Reviewer 之间产生高开销且可能放大错误的循环；
6. **长时程推理需要“经验证的引理记忆”**，而不是不断把全部历史塞回上下文；
7. **多数票只能作为弱信号**，不能替代数学证据或首错定位；
8. **预算应按任务难度和边际进展动态分配**，固定 `best-of-N` 或固定辩论轮数会浪费计算。

MathProofMesh 因此采用“隔离并行探索 + 稀疏引理传输 + 分层验证 + Meta-review + 触发式加深/拓宽”的混合架构。

## 2. 直接相关系统

### 2.1 QED：研究级开放问题的计划—证明—验证—调节

- 论文：[QED: An Open-Source Multi-Agent System for Generating Mathematical Proofs on Open Problems](https://arxiv.org/abs/2604.24021)
- 代码：[proofQED/QED](https://github.com/proofQED/QED)
- 关键实现：[code/decomposition_prover.py](https://github.com/proofQED/QED/blob/main/code/decomposition_prover.py)

QED 将失败模式直接映射到系统模块。公开实现中的主链为：

```text
Decomposer → Single Prover → Structural Verifier → Detailed Verifier
      ↑                                             ↓
      └──────────────── Regulator ──────────────────┘
```

其结构审计覆盖题目完整性、证明完备性、引用、子目标图等；只有结构阶段通过后才进入逐步数学检查。Regulator 将失败分成三级：

- `REVISE_PROOF`：策略和计划基本正确，只修执行；
- `REVISE_PLAN`：依赖计划有结构缺口；
- `REWRITE`：根本机制错误，重新分解。

代码还维护跨轮次 `plan_history.md`，并规定 Regulator 是该文件的唯一写入者，Decomposer 在生成新计划前读取它。这是一个很重要的通信纪律：**失败经验由专门角色归纳后再传递，而不是把所有旧对话直接广播给新一轮 Agent。**

QED 的论文报告在五个专家提供的开放问题上产生了三个经出题专家确认的正确、原创且非平凡证明。这个结果说明系统设计有潜力，但样本规模小、问题领域集中，不能把它解释为一般正确性保证。

**MathProofMesh 的吸收：**不可变题目契约、结构/详细两级验证、执行/计划/策略三级失败、原始证据归档、最终独立验证。

**MathProofMesh 的差异：**QED 的公开主链更接近单计划深挖；本系统在前端增加多个机制独立的并行路径，并用动态预算在广度与深度间切换。

### 2.2 Intern-S1-MO：Reasoner—Summarizer—Verifier 与引理记忆

- 论文：[Long-horizon Reasoning Agent for Olympiad-Level Mathematical Problem Solving](https://arxiv.org/abs/2512.10739)

Intern-S1-MO 的核心不是让上下文无限增长，而是循环执行：

```text
Reasoner 产生探索轨迹
  → Summarizer 提炼候选引理
  → Verifier 检查引理
  → 通过的引理写入持久记忆
  → 下一轮 Reasoner 读取题目与引理库
```

最终候选答案再进入过程验证与修订循环。论文同时展示了大预算扩展版本：对极难竞赛题进行更宽的并行搜索、更多引理验证和更多最终修订轮次。这表明广度和深度都可以扩展，但应集中在困难题，而不是对每题固定使用最大预算。

**MathProofMesh 的吸收：**长推理压缩为可验证 Claim、只把验证通过的引理作为事实传给下一轮、最终过程审计。

**进一步强化：**本系统给 Claim 增加内容哈希、来源 Agent/Attempt、证据引用、依赖 ID、范围限制和反例风险；缺失依赖、非验证依赖和循环依赖会自动降级，防止“摘要把不确定内容洗成定理”。

### 2.3 MARS：Author—Independent Reviewers—Meta-Reviewer

- 论文：[MARS: toward more efficient multi-agent collaboration for LLM reasoning](https://arxiv.org/abs/2509.20502)
- 代码：[xwang97/MARS](https://github.com/xwang97/MARS)
- 提示词：[prompt_templates.py](https://github.com/xwang97/MARS/blob/main/prompt_templates.py)
- 运行链：[pipelines.py](https://github.com/xwang97/MARS/blob/main/pipelines.py)

MARS 用审稿流程替代全连接圆桌辩论：Author 给出草稿；Reviewers 独立审查；Meta-Reviewer 汇总并给作者一个反馈包。论文报告在其基准上与多智能体辩论达到相近准确率，同时令 token 和时间约减半。

代码实践中值得保留的细节：

- Reviewer 被要求**先忽略作者答案并独立求解**，再进行比较，降低锚定；
- 每个 Reviewer 保有自己的历史，不与其他 Reviewer 直接通信；
- Meta-Reviewer 被明确要求不能只数票，要评价证据；
- 作者收到的是 Meta-Reviewer 的聚合建议，不是所有未经清洗的评论；
- 下一轮 Reviewer 会知道这是“修订后复审”还是“通过后的稳健性复查”。

**MathProofMesh 的吸收：**独立审稿、Meta-review 汇聚、定向修订、Reviewer 间零直接通信。

**进一步强化：**Reviewer 输出由严格 Schema 约束，并要求首个错误步骤、失败层级、工具请求和证据；模型可靠度不能覆盖确定性反例。

### 2.4 DynaDebate：路径生成、过程争议与触发式验证

- 论文：[DynaDebate: Breaking Homogeneity in Multi-Agent Debate with Dynamic Path Generation](https://arxiv.org/abs/2601.05746)

DynaDebate 针对“多个 Agent 从一开始就走同一条路”的同质化问题，提出：

1. 由 Path Generation Agent 生成差异化解题路径并动态分配；
2. 争论焦点从最终答案迁移到逐步推理过程；
3. 仅在出现分歧时激活可调用外部工具的 Verification Agent。

**MathProofMesh 的吸收：**策略必须按决定性数学机制区分；初始探索互相隔离；额外 Reviewer 和工具验证按失败、分歧或高风险触发。

### 2.5 AgentPrune：把通信看成可剪枝的时空图

- 论文：[Cut the Crap: An Economical Communication Pipeline for LLM-based Multi-Agent Systems](https://openreview.net/forum?id=LkzuPorQ5L)
- 代码：[yanweiyue/AgentPrune](https://github.com/yanweiyue/AgentPrune)
- 图实现：[AgentPrune/graph/graph.py](https://github.com/yanweiyue/AgentPrune/blob/main/AgentPrune/graph/graph.py)

AgentPrune 显式区分：

- **spatial edges**：同一轮不同 Agent 间的消息边；
- **temporal edges**：跨轮次记忆边；
- 边由 mask 和可学习 logit 控制，并通过一次性剪枝降低冗余消息。

论文报告在其六个基准上减少 28.1%–72.8% token，并给出一个成本从 43.7 美元降至 5.6 美元的实验例子。这些数字高度依赖任务、模型和价格，不应直接外推到数学证明系统。

**MathProofMesh 的吸收：**显式通信图、空间/时间信息分离、避免全连接广播。

**实现选择：**本系统不训练边 logit，而采用可解释的规则剪枝：`neighbor_k` 语义近邻、验证状态、相关度、依赖闭包、字符预算和条件交叉审稿。这样无需额外训练数据，也更容易审计为什么某条消息被传递。

## 3. 为什么不采用全连接多轮辩论

经典 Multi-Agent Debate 往往让 `N` 个 Agent 在每一轮读取其他 `N-1` 个答案。若平均消息长度为 `L`，轮数为 `R`，仅传输量就近似为

\[
M_{\mathrm{dense}}=R N(N-1),\qquad
C_{\mathrm{dense}}=\Theta(RN^2L).
\]

除了二次通信量，还存在三类数学风险：

1. **早期错误趋同**：错误但表达自信的方向被反复复制；
2. **多数错觉**：多个同源模型的同一错误被误认为独立证据；
3. **上下文污染**：审稿人同时看到过多候选，难以区分来源和依赖。

近期关于多智能体辩论价值的理论与实证分析指出，多数投票常解释了相当一部分收益，单纯增加辩论轮次并不自动提高期望正确性；更有效的是有针对性的纠错、独立重算和证据检查。因此本系统把“讨论”改造成**有角色边界的审稿流水线**。

## 4. 逐步验证与过程监督

数学证明的最终答案正确，不意味着过程正确。过程监督和 step-level verifier 的研究表明，定位中间错误比只给最终对错更适合训练或筛选长推理；但 ProcessBench、PRM 评测等也显示现有过程奖励模型仍会漏检或误判。因此：

- MathProofMesh 要求 `first_error_step`；
- 结构检查与数学检查分开；
- 可确定计算交给工具；
- Reviewer PASS 仍需聚合阈值；
- 自然语言验证通过不宣称形式化证明。

相关资源：

- [Let’s Verify Step by Step / PRM800K](https://arxiv.org/abs/2305.20050)
- [Math-Shepherd](https://arxiv.org/abs/2312.08935)
- [ProcessBench](https://github.com/QwenLM/ProcessBench)

## 5. 广度—深度预算：证据与工程选择

### 5.1 没有固定普适比例

难题可能需要：

- 多条短路径快速排除（偏广度）；
- 一条已露出关键结构的路径长时间补细节（偏深度）；
- 对一个看似完整的证明投入大量验证（偏验证）；
- 多个局部引理已经成熟后尽快综合（偏综合）。

测试时计算分配、MCTS 和自适应 best-of-N 研究共同说明：最优计算量依赖题目难度和当前搜索状态。MathProofMesh 的默认 30/35/25/10 只是工程初值，实际动作由边际价值评分决定。

### 5.2 本系统的触发原则

- **拓宽**：路径覆盖不足、整体不确定性高、多个路径停滞、最佳进展低；
- **深挖**：已有实质进展、方向较独特、缺口可修、没有长期停滞；
- **验证**：候选较完整但支持不足，或关键步骤风险高；
- **综合**：至少一个完整候选达到支持阈值；
- **停止**：终审通过、预算耗尽或所有动作预期价值过低。

更多公式见 [ARCHITECTURE.md](ARCHITECTURE.md)。

## 6. 最终设计取舍矩阵

| 问题 | 采用方案 | 主要依据 | 没有采用的替代方案 |
|---|---|---|---|
| 初始方向是否共享 | 不共享候选，只共享原题和策略卡 | DynaDebate、同质化分析 | 第一轮就全连接 |
| 长历史如何传递 | 验证过的 ClaimCard + 原始证据引用 | Intern-S1-MO | 全部 transcript 回灌 |
| 审稿拓扑 | 独立 Reviewer → Meta-Reviewer | MARS | Reviewer 互聊 |
| 验证顺序 | 结构门 → 详细门 | QED | 所有草稿直接重证 |
| 额外审稿 | 风险/失败/分歧触发 | DynaDebate、成本分析 | 固定所有 Agent 互审 |
| 通信剪枝 | 规则化语义近邻与上下文预算 | AgentPrune | 学习边权；全连接 |
| 错误修复 | execution/plan/strategy 三级 | QED | 每次只“再想一遍” |
| 决策依据 | 证据、首错、依赖、工具；票数仅弱信号 | debate limitations | 简单多数票 |
| 工具执行 | 白名单数学工具；任意 Python 禁止 | 安全与可审计性 | 让模型执行任意代码 |

## 7. 研究边界

1. QED、Intern-S1-MO、MARS、DynaDebate 和 AgentPrune 的任务、模型和预算不同，不能把各自指标直接横向相加。
2. 多 Agent 的收益取决于模型异质性。若所有 key 指向同一模型、同一温度和相似提示词，表面上的 Agent 数量不等于真正独立的证据来源。
3. 自然语言验证器可能发生相关错误；跨提供商审稿只降低相关性，不消除错误。
4. 形式化工具能够提供更强保证，但自然语言到形式化命题的映射本身仍需审查。
5. 对开放研究问题，系统应输出“已证明、条件成立、未解决、反例、引用待核验”等不同状态，而不是强制产生完整证明。
