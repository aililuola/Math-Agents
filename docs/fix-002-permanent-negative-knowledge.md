# 第 2 个问题修复记录：永久 Negative Knowledge

## 1. 修复状态

| 项目 | 结果 |
| --- | --- |
| 问题编号 | 002 |
| 修复分支 | `fix/002-permanent-negative-knowledge` |
| 基准分支 | `java` |
| 基准 commit | `e5d5275e1ec5489165d443974c1722abc4f395ff` |
| 提交信息 | `fix(memory): make verified negative knowledge permanent` |
| Core 专项测试 | PASS |
| Desktop 生产链专项测试 | PASS |
| 第 1 个问题回归 | PASS |
| Core + Desktop 模块回归 | PASS |
| `verify-all.ps1 -Offline` | PASS |
| 修改范围 | 只处理类型化、永久、可恢复且统一执行的 Negative Knowledge |

## 2. 原问题与修复边界

修复前，`TypedMemory` 中的 negative message 只能表达普通负面记忆，生命周期主要来自
`MessageEnvelope.ttlRounds`。系统不能可靠区分以下三种事实：

1. `TEMPORARY_HYPOTHESIS_REJECTION`：证据不足导致的临时拒绝；
2. `VERIFIED_COUNTEREXAMPLE`：可信独立计算和可重放证据否定了精确命题；
3. `DETERMINISTIC_GUARDRAIL`：确定性代码规则禁止某个命题或推理模式作为正向证明依据。

Strategy、Revision、Proof Graph、Inspiration 和 Fact Promotion 也没有共享同一个生产准入
Gate。因此，即使某个错误已被确定性规则或可信反例确认，它仍可能从旁路重新进入 active
state，或者在 checkpoint 恢复后重新参与调度。

本次没有修改第 1 个问题中的 `ExactGoalContractChecker`、`RootGoalContract`、
`ProblemSemanticViewService` 或其测试。也没有修改 Provider、Token、Temporal、Broker、
并发、预算、Claim 生命周期、Proof Graph 合并算法、Pivot、PostgreSQL 或 Python Sidecar
性能阈值。

## 3. 生产写入入口审计

修复前后逐一审计了以下生产写入入口：

| 入口 | 修复后的统一控制点 |
| --- | --- |
| `TypedMemory.addNegative(...)` | 只能注册 temporary rejection |
| 可信确定性种子 | `addDeterministicGuardrail(...)` + `DeterministicNegativeSeed` |
| 可信反例 | `applyVerifiedCounterexample(...)` + `VerifiedCounterexampleAuthority` |
| Initial Strategy | Blueprint 编译后、archive/route/graph mutation 之前批量 Gate |
| Route Widening | `NegativeKnowledgeSurface.ROUTE_WIDENING`；`DesktopPermanentNegativeKnowledgeProductionTest#blockedNegativeCandidateCannotEnterThroughRealWidenRoutes` 直接调用真实 `widenRoutes()` |
| Route Revision | revision 与 blueprint 全部预检后再原子修改状态 |
| Proof Obligation / Claim Node | `NegativeAwareProofGraphWriter` |
| Inspiration Materialization | 所有 draft 先预检，再一次性提交 |
| Fact Promotion | `MemoryPromotionPolicy` 调用统一 Gate 并传入 current round |
| Checkpoint Restore | Registry 恢复后重审 facts、routes、strategies、obligations 和 tasks |

`MAIN_GOAL` 只允许通过显式 `IMMUTABLE_ROOT_GOAL` 路径写入。
`FALSIFICATION_ONLY` 可以针对已知错误继续生成计算问题或反驳节点，但不能转为正向证明依赖。

## 4. 类型化 Registry

新增的核心类型包括：

- `NegativeKnowledgeKind`
- `NegativeKnowledgeTargetType`
- `NegativeMatchStrength`
- `NegativeKnowledgeSurface`
- `NegativeCandidateIntent`
- `NegativeKnowledgeDecisionCode`
- `NegativeKnowledgeCandidate`
- `NegativeKnowledgeRecord`
- `NegativeKnowledgeSnapshot`
- `NegativeKnowledgeAuditEvent`
- `NegativeKnowledgeDecision`
- `NegativeKnowledgeRegistry`
- `NegativeKnowledgeAdmissionGate`

### 4.1 生命周期

- temporary record 在 `currentRound <= firstSeenRound + ttlRounds` 时 active；
- 超过该轮次后 inactive；
- permanent record 的 `expiresAfterRound` 必须为 `null`；
- 没有使用 `Integer.MAX_VALUE` 模拟永久；
- `MessageEnvelope.ttlRounds` 仍然只是消息/临时拒绝的寿命，不代表数学真值寿命。

### 4.2 语义键与匹配强度

语义键绑定：

- `problemHash`
- target type
- 数学规范化后的 statement
- normalized assumptions
- ordered quantifiers
- variable bindings
- scope limitations

实现复用 `CanonicalJson`、稳定 hash 和现有数学文本规范化工具，并执行 NFKC 与
`Locale.ROOT` 规范化。不同 problem hash、量词、假设或作用域不会被当成 exact match。

只有 `EXACT` 和由可信代码/可信验证流程写入的 `TRUSTED_ALIAS` 会硬阻断。
`POSSIBLE_EQUIVALENT` 只返回 quarantine，不会由字符串相似度直接宣布数学等价。

匹配边界专项测试的实际统计为：

```text
NEGATIVE KNOWLEDGE MATCH BOUNDARY DIAGNOSTIC
TRUSTED_ALIAS_PERMANENT_BLOCKS=7
POSSIBLE_EQUIVALENT_QUARANTINES=1
POSSIBLE_EQUIVALENT_PERMANENT_BLOCKS=0
RESULT=PASS
```

其中 7 次 trusted-alias hard block 分别覆盖 7 个正向生产 surface；高相似但未经可信流程
确认等价的候选只 quarantine，不会扩散成永久数学否定。

### 4.3 可信边界与单调合并

公开 API 不允许普通调用者直接传入任意 `NegativeKnowledgeKind`：

- 普通 `addNegative(...)` 只能产生 temporary；
- deterministic guardrail 必须来自 `DeterministicNegativeSeed`；
- verified counterexample 必须来自 `VerifiedCounterexampleAuthority`。

可信反例同时验证 replay result、`replayValid`、`REFUTED` authority、experiment artifact、
raw source reference 和精确 target。模型自行填写 `COUNTEREXAMPLE` 和置信度 1.0 不会获得
永久权威。

同一 semantic key 的记录单调合并：kinds、evidence IDs、trusted aliases 取并集，version
递增。永久记录不会被后续 temporary duplicate 降级，也不会重新获得过期时间。

## 5. Snapshot 与 v5 -> v6 迁移

`TypedMemorySnapshot` 新增可缺省的 `negativeKnowledge`，缺失时从 legacy negatives 迁移。
`DesktopSolveCheckpoint.CURRENT_SCHEMA_VERSION` 从 5 升为 6。

迁移规则及测试结果：

| Legacy evidence | v6 结果 |
| --- | --- |
| `deterministic-preflight` + `deterministic://greedy-gcd-guardrails/...` | permanent deterministic guardrail |
| 独立计算 replay + counterexample + experiment artifact + confidence 1.0 + raw ref | permanent verified counterexample |
| 其他 legacy negative | temporary hypothesis rejection |

恢复时 Registry hash 在序列化前后完全一致；冲突的历史 artifact 保留，但 active route、
strategy、open obligation、pending task 和 fact candidate 会被重审、阻断或 retired。

## 6. Greedy GCD 确定性种子

旧的独立 `GreedyGcdStrategyGuardrails.violates(...)` 权威判断已删除，迁移为
`GreedyGcdNegativeKnowledgeSeeds` 的四条可信种子：

1. `finite-prime-support` (`CLAIM`)
2. `universal-prefix-prime` (`CLAIM`)
3. `cross-modulus-containment` (`INFERENCE_PATTERN`)
4. `finite-sample-periodicity` (`INFERENCE_PATTERN`)

有限素数错误包含规定的中英文 trusted aliases。Prompt 中的 forbidden shortcuts 仍可作为
提示，但实际权威判断只经过 Registry 和统一 Gate。

## 7. 修改文件

### 7.1 Core 生产代码

- `memory/MemoryPromotionPolicy.java`：Fact Promotion 改用统一 Gate。
- `memory/TypedMemory.java`：持有 Registry，区分临时、确定性和可信反例写入，并在恢复时迁移/重审。
- `memory/TypedMemorySnapshot.java`：持久化 `NegativeKnowledgeSnapshot`，保持旧构造器兼容。
- `proofgraph/ProofGraphStore.java`：所有普通 obligation/claim 写入委托给统一 writer，并支持恢复重审。
- `proofgraph/NegativeAwareProofGraphWriter.java`：统一 Proof Graph Gate、root bypass 和 falsification-only。
- `memory/DeterministicNegativeSeed.java`
- `memory/GreedyGcdNegativeKnowledgeSeeds.java`
- `memory/NegativeCandidateIntent.java`
- `memory/NegativeKnowledgeAdmissionGate.java`
- `memory/NegativeKnowledgeAuditEvent.java`
- `memory/NegativeKnowledgeBlockedException.java`
- `memory/NegativeKnowledgeCandidate.java`
- `memory/NegativeKnowledgeDecision.java`
- `memory/NegativeKnowledgeDecisionCode.java`
- `memory/NegativeKnowledgeKind.java`
- `memory/NegativeKnowledgeRecord.java`
- `memory/NegativeKnowledgeRegistry.java`
- `memory/NegativeKnowledgeSemanticKey.java`
- `memory/NegativeKnowledgeSnapshot.java`
- `memory/NegativeKnowledgeSurface.java`
- `memory/NegativeKnowledgeTargetType.java`
- `memory/NegativeMatchStrength.java`
- `memory/VerifiedCounterexampleAuthority.java`

### 7.2 Desktop 生产代码

- `DesktopSolveCheckpoint.java`：schema version 5 -> 6。
- `DesktopSolveCoordinator.java`：在所有列出的生产入口安装同一 Registry/Gate，恢复时统一重审。
- 删除 `GreedyGcdStrategyGuardrails.java`：消除与 Registry 并存的独立权威旁路。

### 7.3 Core 测试

- `NegativeKnowledgeLifetimePolicyTest.java`
- `NegativeKnowledgeMonotonicMergeTest.java`
- `NegativeKnowledgeTrustBoundaryTest.java`
- `NegativeKnowledgeScopeIsolationTest.java`
- `NegativeKnowledgeAdmissionGateTest.java`
- `NegativeKnowledgeLegacySnapshotMigrationTest.java`
- `PermanentNegativeKnowledgeMultiRoundTest.java`
- `NegativeKnowledgeRegistryRobustnessTest.java`
- `NegativeKnowledgeFixtures.java`
- `proofgraph/NegativeAwareProofGraphWriterTest.java`

### 7.4 Desktop 测试

- `DesktopPermanentNegativeKnowledgeProductionTest.java`
- `NegativeKnowledgeAtomicMutationTest.java`
- `NegativeKnowledgeNoBypassArchitectureTest.java`
- `GreedyGcdNegativeKnowledgeSeedsTest.java`
- `DesktopNegativeKnowledgeTestHarness.java`
- `GreedyGcdStrategyGuardrailsTest.java`：改为验证统一 Registry 种子，不再验证已删除的旁路。

## 8. 测试优先与修复前架构缺失证据

生产实现加入前先编写新测试。首次 test compile 明确失败，主要错误为：

```text
cannot find symbol: class NegativeKnowledgeKind
cannot find symbol: class NegativeKnowledgeRegistry
cannot find symbol: class NegativeKnowledgeTargetType
cannot find symbol: class NegativeKnowledgeSnapshot
cannot find symbol: method negativeKnowledgeRegistry()
```

该结果是“修复前架构缺失证据”：它直接证明基准代码没有类型化 kind、永久 Registry、
Snapshot 字段和统一 Gate API。它不等同于一份可独立运行的“修复前行为回归失败”，也不据此
声称已经在基准 commit 上量出了某个具体 active-state leak。

架构缺失证据直接对应：

- `NEGATIVE_KIND_DISTINCTION_MISSING`
- `LEGACY_SNAPSHOT_HAS_NO_PERMANENT_REGISTRY`
- 所有生产入口共享的 admission gate 尚不存在

测试不是通过删除或弱化旧断言获得绿色结果。

## 9. 30 轮 Core 诊断

```text
PERMANENT NEGATIVE KNOWLEDGE DIAGNOSTIC
ROUNDS=30
RESTORE_ROUND=15
SEEDED_DETERMINISTIC_GUARDRAILS=4
VERIFIED_COUNTEREXAMPLES=1
TEMPORARY_NEGATIVES=1
TEMPORARY_EXPIRED_AT_ROUND=3
REENTRY_ATTEMPTS=150
STRATEGY_BLOCKS=30
OBLIGATION_BLOCKS=30
REVISION_BLOCKS=30
INSPIRATION_BLOCKS=30
FACT_PROMOTION_BLOCKS=30
ACTIVE_ROUTE_LEAKS=0
PROOF_GRAPH_LEAKS=0
REVISION_LINEAGE_LEAKS=0
INSPIRATION_MEMORY_LEAKS=0
PENDING_TASK_LEAKS=0
FACT_MEMORY_LEAKS=0
POST_RESTORE_LEAKS=0
PERMANENT_DOWNGRADES=0
CROSS_PROBLEM_FALSE_BLOCKS=0
SCOPE_FALSE_BLOCKS=0
FALSIFICATION_ONLY_ALLOWS=30
UNRELATED_ADMISSIONS=5
PRE_RESTORE_REGISTRY_HASH=23c560c8346b9d6af534241f53fc47501fa145ae75594842a4ffca8eb4ceec1b
POST_RESTORE_REGISTRY_HASH=23c560c8346b9d6af534241f53fc47501fa145ae75594842a4ffca8eb4ceec1b
RESULT=PASS
```

所有数字均来自测试运行状态和断言，不是固定打印。

## 10. 30 轮 Desktop 生产链诊断

```text
DESKTOP PERMANENT NEGATIVE KNOWLEDGE PRODUCTION DIAGNOSTIC
ROUNDS=30
RESTORE_ROUND=15
REENTRY_ATTEMPTS=150
STRATEGY_BLOCKS=30
OBLIGATION_BLOCKS=30
REVISION_BLOCKS=30
INSPIRATION_BLOCKS=30
FACT_PROMOTION_BLOCKS=30
ACTIVE_ROUTE_LEAKS=0
PROOF_GRAPH_LEAKS=0
REVISION_LINEAGE_LEAKS=0
INSPIRATION_MEMORY_LEAKS=0
PENDING_TASK_LEAKS=0
FACT_MEMORY_LEAKS=0
POST_RESTORE_LEAKS=0
PERMANENT_DOWNGRADES=0
PRE_RESTORE_REGISTRY_HASH=59b9d58595463e86aca3f288033ec434d0b1003629c2c6834e8d1f3c581db08d
POST_RESTORE_REGISTRY_HASH=59b9d58595463e86aca3f288033ec434d0b1003629c2c6834e8d1f3c581db08d
RESULT=PASS
```

该测试经过真实 `DesktopSolveCoordinator`、`TypedMemory`、Proof Graph、Strategy Archive、
Inspiration materialization、pending proof task 和实际 checkpoint JSON round trip，不调用
DeepSeek、外部网络、Docker、PostgreSQL 或 Python Sidecar。

### 10.1 Route Widening 小回归

30 轮主诊断保持原定义的 5 个入口、每轮 5 次、合计 150 次重入。Route Widening 另用一个
10 轮小测试直接调用真实 `DesktopSolveCoordinator.widenRoutes()`，避免改变主诊断口径：

```text
ROUTE WIDENING NEGATIVE KNOWLEDGE DIAGNOSTIC
ROUNDS=10
ROUTE_WIDENING_BLOCK_TESTS=10
WIDENING_BLOCKS=10
ROUTE_LEAKS=0
ADMITTED_STRATEGY_LEAKS=0
LINEAGE_LEAKS=0
OBLIGATION_LEAKS=0
RESULT=PASS
```

测试场景是：候选已经处于 widening 的待选 admitted queue，随后统一 Gate 使用轮换 trusted
alias 在创建 route 前拒绝。计数比较的是进入和退出真实 `widenRoutes()` 之间的生产状态；
除审计和候选游标外，route、admitted strategy、archive lineage 与 proof obligation 均未发生
新增或替换。架构测试还精确断言 Gate 位于 `addRoute(candidate, ...)` 之前。

## 11. 测试命令与结果

### 11.1 Core 专项

```powershell
.\mvnw.cmd -pl mathproofmesh-core -am `
  "-Dtest=NegativeKnowledgeLifetimePolicyTest,NegativeKnowledgeMonotonicMergeTest,NegativeKnowledgeTrustBoundaryTest,NegativeKnowledgeScopeIsolationTest,NegativeKnowledgeAdmissionGateTest,NegativeKnowledgeLegacySnapshotMigrationTest,PermanentNegativeKnowledgeMultiRoundTest,TypedMemoryParityTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" `
  test
```

结果：PASS，30 轮诊断全部为零泄漏。

### 11.2 Desktop 专项

```powershell
.\mvnw.cmd -pl mathproofmesh-desktop -am `
  "-Dtest=DesktopPermanentNegativeKnowledgeProductionTest,NegativeKnowledgeAtomicMutationTest,NegativeKnowledgeNoBypassArchitectureTest,GreedyGcdNegativeKnowledgeSeedsTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" `
  test
```

结果：PASS，真实生产链 150 次重入全部被对应入口阻断。

### 11.3 第 1 个问题回归

```powershell
.\mvnw.cmd -pl mathproofmesh-core,mathproofmesh-desktop -am `
  "-Dtest=ExactGoalContractCheckerTest,ProblemSemanticViewDeterministicAuditTest,RootGoalContractMultiRoundTest,ProblemSemanticViewParityTest,DesktopSolveCoordinatorRootGoalPropagationTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" `
  test
```

结果：PASS。20 轮中 root hash changes、root goal replacements、rejected sidecar leaks 和
post-resume main-goal leaks 均为 0；第 1 个问题的生产链实现未被修改。

### 11.4 模块与完整验证

`core + desktop -am` 全回归结果：

| 模块 | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| contracts | 44 | 0 | 0 | 0 |
| core | 945 | 0 | 0 | 0 |
| server | 860 | 0 | 0 | 3 |
| desktop | 63 | 0 | 0 | 1 |

条件性 skipped tests 属于仓库原有环境门；所有执行的测试均通过。

完整验证：

```powershell
.\scripts\verify-all.ps1 -Offline
```

结果：`FULL VERIFICATION: PASS`。包括 Maven clean verify、集成测试、SpotBugs、依赖/许可证
检查、Phase 17 coverage 和原始源码不可变检查。由于当前 Git clone 位于 `.publish`，最终
执行使用临时目录还原脚本期望的“401 个冻结源文件 + Java target”父子拓扑；临时目录已删除，
未提交任何运行产物。

上述完整 PASS 对应实现提交 `4904985d033b87ef778d033ec55bc3c1a9fe2bee`。本次 widening
与匹配边界补丁只修改测试和本文档；补丁后重新执行 `core,desktop -am test` 以及
`core,desktop -am -DskipITs verify` 均为 PASS。另一次不带 `-DskipITs` 的 verify 尝试中，
当前机器的 Docker Desktop 不可用，5 个既有 PostgreSQL Testcontainers IT 在容器环境探测
阶段失败；没有启动 Docker、没有修改集成测试，也没有把该环境失败记作代码 PASS。

覆盖率门没有放宽：

| 指标 | 结果 |
| --- | ---: |
| Core line | 91.258419% |
| Core branch | 75.244831% |
| Core audited invariant branch | 85.452245% |
| Desktop line | 71.058164% |

## 12. 验收结论

- temporary、verified counterexample、deterministic guardrail 已类型化区分；
- 永久记录可序列化、恢复并保持 hash；
- permanent + temporary merge 不会降级；
- 不同 problem hash、假设、量词和作用域不会被错误 exact block；
- possible equivalent 只 quarantine；
- falsification-only 保持允许；
- Strategy、Widening、Revision、Proof Graph、Inspiration、Fact Promotion 和 Restore 统一使用 Gate；
- blocked revision 和 inspiration 在第一次 active-state mutation 前失败；
- 30 轮、150 次错误 alias 重入没有 active-state 泄漏；
- 第 1 个问题的 root goal contract 回归保持通过；
- 没有修改其余 11 个问题。
