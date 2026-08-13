# 第 3 个问题修复记录：分离 Attempt、Route 与 Claim 生命周期

## 1. 修复状态

| 项目 | 结果 |
| --- | --- |
| 问题编号 | 003 |
| 修复分支 | `fix/003-claim-attempt-separation` |
| 基准分支 | `java` |
| 基准 commit | `31e1cc4ef28f4327c048c7a5ba36d631543275dd` |
| 提交信息 | `fix(claims): preserve verified local claims from failed attempts` |
| Core 专项测试 | PASS，13 tests |
| Desktop 生产链专项测试 | PASS，7 tests |
| 20 轮恢复测试 | PASS |
| 第 1 个问题回归 | PASS，15 tests |
| 第 2 个问题回归 | PASS，29 tests |
| 全模块非 Docker `clean verify` | PASS，2055 tests |
| Docker/Testcontainers `clean verify` | PASS，2081 tests |
| 5 个 PostgreSQL IT | PASS，21 tests |
| 修改范围 | 只处理 Attempt、Route 与 Claim 的独立审理、投影和恢复 |

本记录所在提交的最终 hash 由 `git show HEAD` 给出。提交 hash 不能写入它自身而保持同一
commit，因此不在正文中硬编码自引用 hash。

## 2. 原问题与边界

修复前，生产链把 Route/Attempt 的整体结果当作 Claim 级权威：

1. Route 整体失败时，已经成立的局部 lemma 和有效反例会与失败路线一起丢失；
2. Attempt-level PASS 可以通过 `markAttemptVerified(...)` 批量验证未被逐项审理的 Claim；
3. 失败 Attempt 的 proof steps 可能被整体当作 committed steps；
4. Claim、Route theorem 和 counterexample 缺少彼此独立的类型、状态和恢复投影。

本次只拆分上述生命周期。没有修改 Root Goal、Problem Semantic View、永久 Negative
Knowledge 的种类、语义键、可信边界或统一 Gate，也没有修改 Proof Graph 合并、Pivot、
Broker、并发、预算、Provider、Temporal、PostgreSQL 或 Python Sidecar 性能阈值。

## 3. 修复后的生产链

每个非空 `ProofAttempt` 现在都经过同一条生产路径：

```text
DesktopSolveCoordinator
  -> harvestAttemptArtifacts
  -> AttemptArtifactHarvester
  -> AttemptArtifactLedger
  -> claim_salvage_review (one bounded independent batch)
  -> ClaimReviewBatch validation
  -> LemmaMemory claim-scoped decision
  -> ClaimLifecycleController promotion gate
  -> TypedMemory FACT + ProofGraph Claim Node
  -> route theorem integration OR route failure recording
  -> DesktopSolveCheckpoint v7
```

Route 是否成功只决定 Route theorem 是否有资格进入，不再替代 Claim verdict。失败 Route
仍会记录 failure、rewrite request、near miss 和 temporary failure negative，但会同时保存
salvaged、rejected 与 uncertain artifact IDs。

### 3.1 Artifact 类型和单调状态

新增三种 artifact：

- `LOCAL_LEMMA`
- `ROUTE_THEOREM`
- `COUNTEREXAMPLE`

状态机为：

```text
HARVESTED -> REVIEW_PENDING -> VERIFIED_LOCAL -> PROMOTED_FACT
                                      |         -> APPLIED_COUNTEREXAMPLE
                                      -> REJECTED
                                      -> UNCERTAIN
```

终态不能降级或重新打开；重新证明必须创建新的 Claim revision，本问题不实现 revision。
Ledger 保存 attempt、route、delta、Claim、作者、证据、精确 target、review、投影 message 和
history，并以稳定 hash 验证 checkpoint round trip。

### 3.2 Claim Review 权威边界

新增 `ClaimReviewDecision` 和 `ClaimReviewBatch`：

- reviewer 必须不同于 artifact author；
- 一个 Attempt 最多接受一个有界 batch；
- 每个 candidate 最多一个 decision，duplicate/extra decision 拒绝整个 batch；
- missing decision 变为 `UNCERTAIN`；
- PASS 还必须满足 confidence、problem integrity、scope、quantifier 和 evidence type；
- counterexample 额外要求 `witnessChecked=true`。

Provider stage `claim_salvage_review` 只接收冻结原题、当前 Attempt 和有界 candidate 集合。
模型给出的 Route verdict 或 `route_theorem` tag 不能创建 Claim 级权威。

### 3.3 Local Claim、Route Theorem 与 Counterexample

独立验证通过的 local Claim 会投影为：

- `LemmaMemory`: `VERIFIED`
- `ClaimLifecycleController`: `EXTERNALLY_ADMITTED_FACT`
- `TypedMemory`: `FACT`
- `ProofGraph`: Claim Node
- `AttemptArtifactLedger`: `PROMOTED_FACT`

来自失败/部分 Route 的 local Claim 不调用 obligation closure，不关闭 `MAIN_GOAL`，也不使用
文本相似度关闭任何目标。它通过全 Route 可见的 verified facts 提供给后续 Route。

Route theorem 只能由内部代码创建，并同时要求：

- `route.status == verified`
- `attempt.status == COMPLETE`
- validation passed
- fact promotion allowed
- 独立 Claim review PASS

Counterexample 还必须携带唯一且存在的
`counterexample-target:<exact-obligation-id>`。通过审理后只 refute 精确目标，不遍历相似文本
目标；写入 Negative Knowledge 的永久 authority 规则没有改变。

### 3.4 Claim 生命周期兼容行为

`LemmaMemory.markAttemptVerified(...)` 保留为 deprecated 兼容入口，但 Attempt PASS 不再把
所有 Claim 标成 VERIFIED。Attempt FAIL 也不会覆盖已经独立验证的 Claim。

`ClaimLifecycleController` 保存 artifact kind、source Attempt status 和 source Route status。
不完整 Attempt 的 local lemma 可以逐项晋升；不完整 Attempt 或未验证 Route 的 route theorem
不能晋升。晚到的较弱状态只记录 history，不会让权威状态回退。

## 4. Checkpoint v6 -> v7

`DesktopSolveCheckpoint.CURRENT_SCHEMA_VERSION` 从 6 升到 7，新增：

- `AttemptArtifactSnapshot attemptArtifacts`
- `ClaimLifecycleSnapshot claimLifecycle`
- 每条 Route 的 artifact/review/salvage/reject/uncertain 投影字段

恢复顺序已按生产代码验证：

```text
Root Goal
Typed/Negative Memory
LemmaMemory
ProofGraph
AttemptArtifactLedger
ClaimLifecycle
Routes
projection reconciliation
```

v6 JSON 缺失的新字段在反序列化时默认 empty。进入生产恢复链后，Coordinator 对旧版已验证
Fact 执行一次确定性兼容迁移：只有同一个 Claim ID 同时存在于 `LemmaMemory VERIFIED`、
`TypedMemory FACT` 和 `ProofGraph` verified Claim Node，且 problem hash、statement、assumptions、
conclusion、tier、status 与 content hash 精确一致时，才重建最小
`EXTERNALLY_ADMITTED_FACT` lifecycle 投影。迁移不调用 Claim Review、不再次晋升 Fact，也不伪造
`AttemptArtifactLedger` 记录；不满足三方精确匹配的旧数据不会获得权威。

随后恢复 Route 投影。原 v5 -> v6 Negative Knowledge 迁移未修改。专项测试断言 v6 checkpoint
恢复前后 Negative Registry hash 相同，Root Goal 文本仍为冻结 exact statement；保存为 v7 并
再次恢复后，旧 Fact 的可见性、数量和 lifecycle 权威仍保持一致。

## 5. 修改文件与目的

### 5.1 Contracts

- `ClaimReviewDecision.java`：定义 Claim 级 verdict、authority dimensions、witness 与反馈。
- `ClaimReviewBatch.java`：定义一个 Attempt 的有界独立审理批次，拒绝 duplicate decision。

### 5.2 Core 生产代码

- `AttemptArtifactKind.java`：区分 local lemma、route theorem 和 counterexample。
- `AttemptArtifactStatus.java`：定义单调 artifact 状态机。
- `AttemptArtifactRecord.java`：保存完整来源、证据、review、投影和版本信息。
- `AttemptArtifactSnapshot.java`：保存 Ledger 和每 Attempt 的唯一 review identity。
- `AttemptArtifactLedger.java`：执行单调 transition、batch 校验、snapshot/restore 和稳定 hash。
- `AttemptArtifactHarvester.java`：确定性分类 proposed lemmas、精确 counterexample target 和内部 route theorem。
- `ClaimLifecycleSnapshot.java`：持久化 Claim lifecycle authority projection。
- `ClaimLifecycleController.java`：增加 artifact/source 状态，允许 incomplete local Claim，阻止不合格 route theorem，支持 snapshot/load，并提供受三方持久投影校验约束的 v6 Fact 权威重建入口。
- `LemmaMemory.java`：增加 claim-scoped decision；停止由 Attempt PASS 批量授予 Claim 权威。
- `TypedMemory.java`：让已验证的失败路线局部 Fact 对后续 Route 可见。
- `MemoryPromotionPolicy.java`：允许经独立 Claim review 和现有 Fact Gate 的 counterexample evidence 投影为 Fact。

### 5.3 Server 与 Desktop 生产代码

- `PromptCatalog.java`：新增隔离的 `claim_salvage_review` provider stage。
- `DesktopSolveCoordinator.java`：接入 harvest/review/integration/failure 生产链，精确应用反例，恢复 artifact/lifecycle 投影，并从精确匹配的 v6 Lemma/Fact/Graph 投影重建最小 Claim lifecycle。
- `DesktopSolveCheckpoint.java`：schema v7、artifact/lifecycle snapshots 和 Route salvage fields。

### 5.4 Core 测试

- `AttemptArtifactFixtures.java`：构造稳定的 artifact 与 review fixtures。
- `AttemptArtifactHarvesterTest.java`：验证分类、模型 tag 无权创建 route theorem 和精确 target。
- `AttemptArtifactLedgerSnapshotTest.java`：验证单调状态、唯一 batch、authority 边界和 JSON round trip。
- `ClaimLifecycleIncompleteAttemptTest.java`：验证 incomplete local Claim 与 route theorem 的不同晋升规则。
- `ClaimReviewBatchValidationTest.java`：验证 duplicate/extra/missing decision 和 author/reviewer 隔离。
- `CounterexampleArtifactTargetingTest.java`：验证缺失、多重和未知 target 均为 uncertain。
- `RouteTheoremNonSalvageTest.java`：验证失败/partial Route 不能产生 route theorem。
- `ClaimSalvageNegativeKnowledgeBoundaryTest.java`：验证 Claim salvage 继续经过已有统一 Negative Gate。
- `LemmaMemoryClaimScopedVerificationTest.java`：黑盒证明 Attempt PASS 不再批量验证 Claim。
- `LemmaMemoryParityTest.java`：更新兼容入口的正确语义断言。
- `Phase17LemmaMemoryHardeningTest.java`：覆盖 late attempt verdict 不降低已验证 Claim。

### 5.5 Desktop 测试

- `DesktopClaimSalvageTestHarness.java`：Fake Provider、内存依赖和真实 Coordinator 驱动器。
- `DesktopFailedRouteClaimSalvageProductionTest.java`：失败 Route 的正确 local Claim 进入真实生产投影。
- `DesktopClaimReviewPromptIsolationTest.java`：Claim reviewer 与 author 隔离，Prompt 不授予 Route 权威。
- `DesktopClaimSalvageNoMainGoalClosureTest.java`：salvaged local Claim 不关闭主目标。
- `ClaimSalvageProjectionAtomicityTest.java`：blocked/invalid artifact 不产生部分投影。
- `DesktopClaimSalvageMultiRoundRestoreTest.java`：20 轮、真实 checkpoint JSON、v6 恢复与全诊断。
- `DesktopV6VerifiedClaimContinuityTest.java`：验证真实 v6 缺失新 snapshots 时，旧 VERIFIED Claim/FACT/Graph Node 无 Review、无重复晋升地恢复权威，并在 v7 二次恢复后继续可见。
- `DesktopLiveRunExecutionBackendTest.java`：Fake backend 支持新 stage，并覆盖重复 canonical Fact 的生产去重。
- `DesktopPermanentNegativeKnowledgeProductionTest.java`：仅把 checkpoint schema 期望值从 6 更新为 7。

## 6. 修复前黑盒行为失败

测试先于生产修复添加，并在基准代码上得到两个独立行为失败，不只是“新 API 不存在”：

1. `LemmaMemoryClaimScopedVerificationTest`

   调用旧 `markAttemptVerified(... PASS ...)` 后，两个未被逐项审理的 Claim 都变成
   `VERIFIED`；测试期望它们保持 `PROPOSED`。

2. `DesktopFailedRouteClaimSalvageProductionTest`

   真实 `DesktopSolveCoordinator` 处理整体失败 Route 后，独立正确的
   `salvageable-local` 没有出现在 `LemmaMemory.verified()`，也没有形成 TypedMemory Fact。

3. `DesktopV6VerifiedClaimContinuityTest`

   在加入兼容迁移前，真实 v6 JSON 中的 Lemma VERIFIED、TypedMemory FACT 和 ProofGraph Claim
   Node 虽然都能恢复，但访问新 lifecycle 投影时报
   `IllegalArgumentException: unknown claim: legacy-local-claim`。这是旧 Fact 权威连续性缺失的
   行为失败，不是“新 API 不存在”造成的编译失败。

这些失败分别直接复现“Attempt-level PASS 覆盖 Claim review”、“失败 Route 丢弃已成立局部
Claim”和“v6 旧 Fact 缺少 v7 lifecycle 权威投影”。修复没有删除或弱化旧断言。

## 7. 专项测试结果

### 7.1 Core

```powershell
.\mvnw.cmd -pl mathproofmesh-core -am `
  "-Dtest=AttemptArtifactHarvesterTest,ClaimLifecycleIncompleteAttemptTest,LemmaMemoryClaimScopedVerificationTest,ClaimReviewBatchValidationTest,CounterexampleArtifactTargetingTest,RouteTheoremNonSalvageTest,AttemptArtifactLedgerSnapshotTest,ClaimSalvageNegativeKnowledgeBoundaryTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" `
  test
```

结果：`Tests run: 13, Failures: 0, Errors: 0, Skipped: 0`。

### 7.2 Desktop

```powershell
.\mvnw.cmd -pl mathproofmesh-desktop -am `
  "-Dtest=DesktopFailedRouteClaimSalvageProductionTest,DesktopClaimReviewPromptIsolationTest,DesktopClaimSalvageNoMainGoalClosureTest,ClaimSalvageProjectionAtomicityTest,DesktopClaimSalvageMultiRoundRestoreTest,DesktopV6VerifiedClaimContinuityTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" `
  test
```

结果：`Tests run: 7, Failures: 0, Errors: 0, Skipped: 0`。专项不调用 DeepSeek、网络、
Docker、PostgreSQL 或 Python Sidecar。

## 8. 20 轮生产链诊断

```text
CLAIM SALVAGE LIFECYCLE DIAGNOSTIC
ROUNDS=20
RESTORE_ROUND=10
FAILED_ROUTE_ATTEMPTS=20
HARVESTED_LOCAL_CLAIMS=60
VERIFIED_LOCAL_CLAIMS=20
REJECTED_LOCAL_CLAIMS=20
UNCERTAIN_LOCAL_CLAIMS=20
HARVESTED_COUNTEREXAMPLES=5
VERIFIED_COUNTEREXAMPLES=5
EXACT_OBLIGATIONS_REFUTED=5
UNRELATED_OBLIGATIONS_REFUTED=0
ROUTE_THEOREM_PROMOTIONS=0
MAIN_GOAL_CLOSURES=0
LOCAL_FACT_PROMOTIONS=20
INVALID_FACT_PROMOTIONS=0
FAILED_ROUTE_CLAIM_LOSSES=0
DUPLICATE_FACTS=0
CLAIM_STATUS_REGRESSIONS=0
LATER_ROUTE_FACT_VISIBILITY_FAILURES=0
POST_RESTORE_ARTIFACT_LOSSES=0
POST_RESTORE_FACT_LOSSES=0
POST_RESTORE_STATUS_CHANGES=0
DUPLICATE_REVIEW_CALLS_AFTER_RESTORE=0
ROOT_HASH_CHANGES=0
ROOT_GOAL_REPLACEMENTS=0
NEGATIVE_REGISTRY_HASH_CHANGES=0
PERMANENT_NEGATIVE_COUNT_CHANGES=0
NEGATIVE_GATE_BYPASSES=0
ARTIFACT_LEDGER_HASH_BEFORE_RESTORE=230e23c382a1662053adb62e09b2d7461a72cadc65527315657da229af15889f
ARTIFACT_LEDGER_HASH_AFTER_RESTORE=230e23c382a1662053adb62e09b2d7461a72cadc65527315657da229af15889f
CLAIM_LIFECYCLE_HASH_BEFORE_RESTORE=ffbc92e214e1402a4436096d0141926646489693d5c174c9ac15c975c76d5385
CLAIM_LIFECYCLE_HASH_AFTER_RESTORE=ffbc92e214e1402a4436096d0141926646489693d5c174c9ac15c975c76d5385
NEGATIVE_REGISTRY_HASH_BEFORE_RESTORE=ddb45f57846c85feee887f7ad514d756a3478b63d4093f63c083c397dc9e7223
NEGATIVE_REGISTRY_HASH_AFTER_RESTORE=ddb45f57846c85feee887f7ad514d756a3478b63d4093f63c083c397dc9e7223
RESULT=PASS
```

所有数字均由断言所检查的实际状态计算，不是固定打印。

### 8.1 v6 旧 VERIFIED Claim 连续性诊断

```text
V6 VERIFIED CLAIM CONTINUITY DIAGNOSTIC
LEGACY_VERIFIED_FACTS_BEFORE=1
LEGACY_VERIFIED_FACTS_AFTER=1
LEGACY_FACT_VISIBILITY_FAILURES=0
LEGACY_FACT_DUPLICATES=0
LEGACY_FACT_REVIEW_CALLS=0
LEGACY_FACT_REPROMOTIONS=0
LEGACY_PROOFGRAPH_NODE_LOSSES=0
LEGACY_LEMMA_STATUS_CHANGES=0
SECOND_RESTORE_FACT_LOSSES=0
SECOND_RESTORE_DUPLICATES=0
ROOT_HASH_CHANGES=0
NEGATIVE_REGISTRY_HASH_CHANGES=0
RESULT=PASS
```

该测试经过真实 `DesktopSolveCoordinator` checkpoint JSON 恢复链，并创建后续 Route，捕获
`InitialExplorationTurn` 生产 Prompt 中的 `verified_facts`。第一次恢复把 v6 旧 Fact 重建为
`EXTERNALLY_ADMITTED_FACT`；再保存为 v7 并第二次恢复后，不发生复审、重复晋升、Fact 丢失或
重复。`AttemptArtifactSnapshot` 对该旧 Fact 仍为空，迁移没有伪造 Attempt 历史。

## 9. 前两个问题回归

第 1 个问题：

- Core `14/14` PASS；Desktop 生产 Prompt 链 `1/1` PASS；
- 20 轮 root hash changes、root replacements、sidecar leaks 和 post-resume leaks 均为 0；
- 五条生产目标路径仍使用 immutable Root Goal。

第 2 个问题：

- Core `18/18` PASS；Desktop `11/11` PASS；
- 30 轮 150 次重入仍为零 active-state leak；
- Route widening `10/10` blocked，零 route/archive/obligation leak；
- `POSSIBLE_EQUIVALENT` 仍只 quarantine，permanent blocks 为 0；
- Negative Registry 恢复 hash 与 permanent count 均未改变。

受保护实现相对基准 commit 的 `git diff --name-only` 为空：

- `ExactGoalContractChecker.java`
- `RootGoalContract.java`
- `ProblemSemanticViewService.java`
- `SemanticProfileService.java`
- `NegativeKnowledgeRegistry.java`
- `NegativeKnowledgeAdmissionGate.java`
- `NegativeKnowledgeSemanticKey.java`
- `GreedyGcdNegativeKnowledgeSeeds.java`

`DesktopSolveCoordinator` 的修改只位于 Claim salvage、failure metadata、checkpoint save/restore
和 projection reconciliation 区域；freeze、triage、semantic sidecar 和 root-goal restore 逻辑未改。

## 10. 全模块与质量验证

执行：

```powershell
.\mvnw.cmd -B -ntp -o -DskipITs clean verify
```

结果：BUILD SUCCESS。

| 模块 | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| contracts | 44 | 0 | 0 | 0 |
| core | 958 | 0 | 0 | 0 |
| server | 834 | 0 | 0 | 3 |
| desktop | 70 | 0 | 0 | 1 |
| compatibility | 149 | 0 | 0 | 0 |
| 合计 | 2055 | 0 | 0 | 4 |

所有模块编译、Enforcer、duplicate-class gate 和 SpotBugs 通过，SpotBugs findings 为 0。
Core JaCoCo branch coverage 为 `75.089487%`，高于既有 `75%` 门槛；没有放宽覆盖率或
Python Sidecar 性能门。

随后使用 Docker Desktop 4.83.0 / Engine 29.6.2 执行：

```powershell
.\scripts\verify-all.ps1 -Offline
```

Maven `clean verify`、全部 Testcontainers IT、覆盖率、安全和许可门均通过。包含 IT 的报告
合计为 `2081` tests、0 failures、0 errors、4 conditional skips；server 模块为 `860` tests。
完整运行的 Core JaCoCo branch coverage 为 `75.102744%`，仍高于未调整的 `75%` 门槛。
本问题要求核验的 5 个 PostgreSQL 测试结果为：

| PostgreSQL IT | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| `JdbcMessageRepositoryIT` | 4 | 0 | 0 | 0 |
| `MemoryProofGraphPostgresIT` | 4 | 0 | 0 | 0 |
| `PersistencePostgresIT` | 9 | 0 | 0 | 0 |
| `Phase17CheckpointOutboxPerformanceIT` | 1 | 0 | 0 | 0 |
| `ProviderCallPostgresIT` | 3 | 0 | 0 | 0 |
| 合计 | 21 | 0 | 0 | 0 |

工作 clone 位于 `.publish/Math-Agents`，而 immutable-source 脚本按设计把目标仓库的父目录
当作冻结原始源码目录。该 clone 的父目录 `.publish` 不包含 401 个原始文件，因此同一次脚本
在所有构建、IT 和质量门通过后，最后以 `Current source file count is 0; expected 401` 退出，
没有打印字面上的 `FULL VERIFICATION: PASS`。这不是测试失败，也不是 Docker 失败。

同一个 immutable-source 检查已在仓库规定的标准父子目录拓扑中独立执行并通过：

冻结原始源码在仓库的标准父子目录拓扑中单独检查通过：

```text
SOURCE IMMUTABILITY: PASS
files=401
manifest_sha256=9f3def2bec8cea99d0a18b51fbb5fa8ce53f44a24ce869f6495cac809b7e3770
```

因此，`verify-all.ps1 -Offline` 所包含的所有实际代码、PostgreSQL、覆盖率、安全、许可和源码
不可变门均已验证通过；唯一未出现的是由工作 clone 非标准父目录布局造成的单进程最终 PASS
字符串。没有绕过测试、修改脚本、提交运行报告或降低 PostgreSQL、覆盖率和性能门。

## 11. 验收结论

- 每个非空 Attempt 都会 harvest 和逐 Claim 独立审理；
- Attempt PASS 不再批量验证 Claim，Attempt FAIL 不降低已验证 Claim；
- 失败 Route 的正确 local Claim 可进入 LemmaMemory、TypedMemory、ClaimLifecycle 和 ProofGraph；
- false/unsupported Claim、模型 route-theorem tag 和不精确 counterexample 不获得权威；
- salvaged local Claim 不关闭主目标或其他 obligation；
- counterexample 只 refute 精确 target；
- route theorem 仍要求完整、验证通过的成功 Route；
- 第 10 轮恢复前后三类 hash 完全一致；
- v6 -> v7 反序列化缺省新 snapshots；精确匹配的旧 VERIFIED Fact 无复审、无重复晋升地重建最小 lifecycle，且不改变 Negative Registry；
- 第 1、2 个问题的受保护实现零差异，所有专项回归通过；
- 没有修改其余 10 个问题。
