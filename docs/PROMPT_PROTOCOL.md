# 提示词演化与 Agent 间消息协议

## 1. 原则

MathProofMesh 的提示词不是同一个“请再想一遍”模板反复追加历史，而是随证明状态变化而演化。每一阶段只接收完成其职责所需的信息，并输出固定 Schema。

共有约束 `COMMON_SYSTEM` 包括：

- 原题不可改变；
- 只输出可审计的数学断言和证明步骤；
- 不输出隐藏草稿；
- confidence 不是证据；
- 引用必须给出精确陈述、条件和位置，否则标为未验证；
- 已证明、猜测、失败方向和缺口必须区分；
- 只返回一个符合 JSON Schema 的对象。

完整模板见 [`src/mathproofmesh/prompts.py`](../src/mathproofmesh/prompts.py)。

## 2. 消息生命周期

```text
P0 ProblemContract
  ↓
P1 TriageResult
  ↓
P2 StrategySet / StrategyCard
  ↓
P3 ProofAttempt (legacy isolated mode)
  ↓ when continuation is enabled
P3C ProofDelta → checkpoint verification → committed ProofCheckpoint
  ↓
P4 ClaimBatch → ClaimCard memory
  ↓
P5 Structural VerificationReport
  ↓ pass
P6 Detailed VerificationReport + optional ToolRequest/ToolResult
  ↓
P7 MetaReview
  ↓
P8 Targeted deepening / new strategy
  ↓
P9 FinalProof
  ↓
P10 Final structural + detailed VerificationReport
  ↓ failure
P11 Revised FinalProof
```

每个对象都不是自由文本“摘要”，而是类型化消息。

## 3. P0：不可变题目契约

### 输入

用户原始题面。

### 系统处理

- 去除首尾空白得到规范化文本；
- 保留原文；
- 生成 SHA-256 哈希；
- 指定交付物和硬约束；
- 列出允许工具。

### 传递规则

后续每个证明对象都携带 `problem_hash`。任何 Agent 不得改写题目后再让系统求一个较弱命题。

## 4. P1：Triage

### 提示目标

只判断：题型、难度、风险、可能工具、建议路径数/轮数、direct/decomposition/hybrid 模式。

### 为什么不让它立刻求解

若 Planner 同时负责判断和完整求解，后续策略容易围绕它的首个思路锚定。Triage 只提供元层信息，给策略生成保留空间。

### 输出

`TriageResult`：

```json
{
  "problem_kind": "proof",
  "difficulty": "olympiad",
  "key_risks": ["..."],
  "likely_tools": ["..."],
  "suggested_paths": 4,
  "suggested_rounds": 3,
  "proof_mode": "hybrid",
  "rationale": "...",
  "confidence": 0.8
}
```

## 5. P2：策略生成

### 新增上下文

- `ProblemContract`；
- `TriageResult`；
- 已尝试策略标题；
- Regulator/Meta-review 的策略级反馈。

### 删除的上下文

- 不发送其他 Agent 的完整草稿；
- 不发送未经验证的中间 Claim。

### 提示重点

要求策略在“决定性数学机制”上不同，而不是换符号。每条策略必须给：

- core idea；
- independence basis；
- expected lemmas；
- bottleneck；
- key original step；
- falsification test；
- estimated success/cost；
- tags。

### 演化

首轮 `prior_strategy_titles=[]`。拓宽时加入旧标题和策略级失败反馈，防止重复已经失败的机制。

## 6. P3：隔离探索

### 首轮输入

- 原题；
- 仅一个 StrategyCard；
- 与该策略相关的已验证 Claim（通常首轮为空）；
- 无其他候选答案；
- 剩余全局预算。

### 后续深挖输入

在首轮基础上增加：

- 同一路径上一版 `ProofAttempt` 的紧凑结构；
- Meta-review/Verifier 的首错和定向反馈；
- 新获得的相关已验证 Claim。

### 提示重点

- 完整证明优先，但禁止强行闭合；
- 有价值的部分引理、精确障碍和已证死路也算进展；
- 非平凡决定性步骤标记 `is_key_step=true`；
- 每步依赖使用 step ID 或已验证 Claim ID；
- 明示 falsification checks、dead ends、unresolved gaps。

### 输出

`ProofAttempt`，包含状态 `complete/partial/failed`、答案、步骤、候选引理、死路、缺口和自评。

### 防止错误传播

Explorer 自己提出的引理不会自动成为 verified；自评只影响调度。

### P3C：检查点续推提示

启用 `continuation` 后，首轮和后续深挖改用 `continue_proof()`：

**输入：**

- 不可变 ProblemContract；
- 单一路径 StrategyCard；
- 最新 `committed` ProofCheckpoint；
- 相关 verified Claim；
- 当前子目标、剩余子目标和定向反馈；
- 权威的 parent checkpoint、path、round 和 segment ID。

**输出：** `ProofDelta`，且一次最多产生配置允许的少量步骤和 Claim。提示词明确要求：

- 不重证已提交步骤，除非报告显式冲突；
- 只依赖已提交步骤、verified Claim、明确外部定理或本 Delta 更早步骤；
- `verified_subgoal` 策略下必须填写 `completed_subgoal`；
- 只有完整解决原题时才能设置 `proof_complete=true`；
- 只输出可审计增量，不输出隐藏草稿。

随后 `verify_delta()` 将父检查点、候选 Delta 和 verified Claim 一并交给独立 Reviewer。作者 ID 会从首选 Reviewer 和 Reviewer 故障转移候选中同时排除。Reviewer 必须核查身份、依赖、每个新推导及子目标状态；PASS 之前 Delta 不会成为恢复点。

断线或跨 key 接力时，新 Agent 收到完全相同的检查点包。系统不传递半截 SSE 或原作者的私有 `reasoning_content`。

## 7. P4：Claim 提取

### 输入

- 原题；
- 一个完整 ProofAttempt；
- 现有 Claim 的**紧凑去重索引**。

### 为什么不是直接转发 ProofAttempt

长草稿包含重复尝试、失败分支和局部记号。后续 Agent 需要的是可复用结论，但压缩必须保留数学语义。

### 提示重点

- 只提取证明真正存在的实质性结论；
- 目标、猜测和想要得到的结论不能冒充引理；
- Claim.dependencies 只能是已经提交的 Claim、同一 Delta 中更早出现的 Claim ID 或 `external:*`；自依赖和前向循环在本地守卫阶段直接拒绝；
- Claim 内部步骤依赖写在 `ProofStep.dependencies`；
- 保留范围限制、失败条件、反例风险和证据引用；
- 没有可复用结论时返回空列表。

### 输出

`ClaimBatch`。Orchestrator 会强制把所有 Claim 状态重置为 `proposed`，即使模型输出 verified。

## 8. P5：结构验证

### 输入

- 原题契约；
- 目标 ProofAttempt/FinalProof；
- 可选计划。

### 审查顺序

1. 逐项对照原题的量词、假设、定义、领域和结论；
2. 检查所有请求部分是否完成；
3. 验证依赖图、循环和孤立结论；
4. 查是否使用未验证 Claim；
5. 查引用结构；
6. 查关键步骤是否被隐藏在“显然”后。

### 输出

`VerificationReport(stage="structural")`，带：

- `problem_integrity_ok`；
- PASS/FAIL/UNCERTAIN；
- issues；
- first error；
- failure level；
- concise feedback。

### 演化规则

结构 PASS 才加入详细验证提示词。结构 FAIL 时，后续提示词不是“请重算全部”，而是把错误分类为 execution/plan/strategy。

## 9. P6：详细验证与工具反馈

### 首次详细验证输入

- 原题；
- 完整目标；
- 结构报告；
- 与目标相关的已验证 Claim 及其依赖闭包；
- 空工具结果。

### 审查要求

逐步检查 statement、dependencies、justification、计算、边界和穷尽性，并主动找反例。Reviewer 必须寻找**第一个**不成立或未充分证明的步骤，因为后续正确结论不能修复早期断裂。

### 工具回合

Reviewer 可以输出 `ToolRequest`。ToolBroker 执行后，同一 Reviewer 收到：

- 原题和目标；
- 原结构报告；
- 相同已验证 Claim；
- `ToolResult`。

它需要解释工具结果是否匹配原数学断言。若工具已经找到反例，本地守卫不允许 Reviewer 再输出 PASS。

### 为什么由同一 Reviewer 解释工具

它保留了自己请求该检查时的形式化意图；另换 Reviewer 可能无法判断工具表达式是否准确映射原步骤。额外独立 Reviewer 仍可由分歧规则触发。

## 10. P7：Meta-review

### 输入

- 原题；
- 最多若干个排名最高候选的结构化紧凑包；
- 对这些候选的聚合验证报告。

### 不输入

- Reviewer 之间的对话，因为不存在；
- 全部原始生成 transcript；
- 与候选无关的 Claim。

### 提示重点

- 先独立评估，再综合报告；
- 去重重复评论；
- 暴露冲突；
- 禁止把多数票变成数学真理；
- 按完整性、关键步骤、可修复性、已验证进展排序；
- 选择 execution/plan/strategy 层级；
- 只有有足够支持的候选时 `can_synthesize=true`。

### 输出如何进入下一轮

MetaReview 不全文广播。系统抽取：

- selected target；
- first error；
- concise feedback；
- required actions；
- failure level。

这些组成下一轮 Explorer 的 `targeted_feedback`。

## 11. P8：深挖或拓宽

### 深挖提示

保留同一 StrategyCard，加入上一版 Attempt 和局部错误，要求修复而不是换题。

### 计划修订提示

策略机制保留，但要求调整依赖顺序、补引理或拆分关键步骤。

### 拓宽提示

加入旧策略标题和“不可再重复”的失败诊断，生成新的机制。

这对应提示词的三级演化：

```text
execution failure → same strategy + same plan + first-error repair
plan failure      → same strategy + revised dependency plan
strategy failure  → new strategy generation + failure memory
```

## 12. P9：综合

### 输入压缩策略

- 最佳候选完整保留；
- 其他候选用紧凑包；
- Claim 按相关性排序并保留依赖闭包；
- Meta-review；
- 原题。

### 提示重点

- 选择一致路线，不平均互不兼容的证明；
- 只有假设匹配时才能组合 Claim；
- 所有关键步骤显式展开；
- 不在数学答案中提 Agent、投票或评分；
- 证据不足时输出最强诚实部分结果，不伪造闭合。

## 13. P10：最终审计

FinalProof 先进入独立结构 Reviewer，再进入独立详细 Reviewer。Synthesizer 被排除在 Reviewer 候选之外；若可行，优先跨提供商审查。

最终 PASS 条件不仅是所有报告字面 PASS，还需要：

- 题目哈希一致；
- 无确定性反例；
- 结构门通过；
- 聚合置信度达到阈值；
- 没有未解决的严重 issue。

## 14. P11：最终修订

### 输入

- 原题；
- 当前 FinalProof；
- 独立终审报告；
- 相关已验证 Claim。

### 提示重点

- 先核对反馈，不盲从；
- execution 错误局部修复；
- plan 错误重排依赖；
- strategy 错误不能靠措辞润色；
- 保留正确材料，删除无效 Claim；
- 不可加新假设或弱化命题。

修订后重新走完整终审，而不是让原 Reviewer 口头确认。

## 15. 信息传输的准确性规则

### 15.1 每条可复用信息必须回答六个问题

1. 在什么假设下？
2. 精确结论是什么？
3. 依赖哪些步骤或 Claim？
4. 证明在哪里？
5. 适用范围和失败条件是什么？
6. 当前验证状态是什么？

### 15.2 原始响应与转发包分离

- `raw/` 保存模型原文，不修改；
- `structured/` 保存 Schema 验证对象；
- Agent 间默认传 `structured`；
- `EvidenceRef` 允许审稿人回溯 `raw`；
- 两者内容哈希可用于发现篡改或混淆。

### 15.3 严禁的状态跃迁

```text
proposed --(summary/self confidence)--> verified    # 禁止
uncertain --(majority vote)---------> verified      # 禁止
missing dependency -----------------> ignored       # 禁止
counterexample found ---------------> PASS          # 禁止
problem hash mismatch --------------> PASS          # 禁止
```

允许的主要跃迁：

```text
proposed --(independent PASS + valid dependencies)--> verified
proposed --(FAIL)-----------------------------------> rejected
verified --(dependency later invalidated)----------> uncertain
```

## 16. 自定义提示词时的检查清单

修改任一角色提示词时，应验证：

- 是否仍包含不可变题目哈希或契约；
- 是否把非必要的其他候选答案暴露给首轮 Explorer；
- 是否要求结构化字段而不是自由文本摘要；
- 是否明确区分 step dependency 和 claim dependency；
- 是否要求首错、边界、量词和等号条件；
- 是否允许 confidence 越权；
- 是否保留失败方向和反例；
- 是否会导致 Reviewer 相互锚定；
- 续推提示是否携带 latest checkpoint 和准确 parent ID；
- 是否可能把未验证 Delta 或半截输出当作恢复状态；
- 是否超过上下文预算；
- Schema 修改是否同步到 Mock、测试和报告。
