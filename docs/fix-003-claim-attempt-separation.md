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
| Desktop 生产链专项测试 | PASS，6 tests |
| 20 轮恢复测试 | PASS |
| 第 1 个问题回归 | PASS，15 tests |
| 第 2 个问题回归 | PASS，29 tests |
| 全模块非 Docker `clean verify` | PASS，2054 tests |
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

v6 缺失的新字段默认 empty；恢复后重建 Route 投影。原 v5 -> v6 Negative Knowledge 迁移
未修改。专项测试断言 v6 checkpoint 恢复前后 Negative Registry hash 相同，Root Goal 文本
仍为冻结 exact statement。

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
- `ClaimLifecycleController.java`：增加 artifact/source 状态，允许 incomplete local Claim，阻止不合格 route theorem，并支持 snapshot/load。
- `LemmaMemory.java`：增加 claim-scoped decision；停止由 Attempt PASS 批量授予 Claim 权威。
- `TypedMemory.java`：让已验证的失败路线局部 Fact 对后续 Route 可见。
- `MemoryPromotionPolicy.java`：允许经独立 Claim review 和现有 Fact Gate 的 counterexample evidence 投影为 Fact。

### 5.3 Server 与 Desktop 生产代码

- `PromptCatalog.java`：新增隔离的 `claim_salvage_review` provider stage。
- `DesktopSolveCoordinator.java`：接入 harvest/review/integration/failure 生产链，精确应用反例，并恢复 artifact/lifecycle 投影。
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

这些失败分别直接复现“Attempt-level PASS 覆盖 Claim review”和“失败 Route 丢弃已成立局部
Claim”。修复没有删除或弱化旧断言。

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
  "-Dtest=DesktopFailedRouteClaimSalvageProductionTest,DesktopClaimReviewPromptIsolationTest,DesktopClaimSalvageNoMainGoalClosureTest,ClaimSalvageProjectionAtomicityTest,DesktopClaimSalvageMultiRoundRestoreTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" `
  test
```

结果：`Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`。专项不调用 DeepSeek、网络、
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
| desktop | 69 | 0 | 0 | 1 |
| compatibility | 149 | 0 | 0 | 0 |
| 合计 | 2054 | 0 | 0 | 4 |

所有模块编译、Enforcer、duplicate-class gate 和 SpotBugs 通过，SpotBugs findings 为 0。
Core JaCoCo branch coverage 为 `75.102744%`，高于既有 `75%` 门槛；没有放宽覆盖率或
Python Sidecar 性能门。

`verify-all.ps1 -Offline` 的正常执行已经到达既有 PostgreSQL Testcontainers IT，但当前机器
没有可用 Docker，因此以下 5 个 IT 在容器环境探测阶段失败：

- `JdbcMessageRepositoryIT`
- `MemoryProofGraphPostgresIT`
- `PersistencePostgresIT`
- `Phase17CheckpointOutboxPerformanceIT`
- `ProviderCallPostgresIT`

因此不把 `verify-all` 记作 PASS，也没有启动 Docker、绕过测试或降低门槛。跳过环境依赖 IT
后的完整六模块验证全部通过。覆盖率报告的全部数值门通过，仅两个需要 PostgreSQL IT report
的 critical scenarios 因上述环境条件显示 missing。

冻结原始源码在仓库的标准父子目录拓扑中单独检查通过：

```text
SOURCE IMMUTABILITY: PASS
files=401
manifest_sha256=9f3def2bec8cea99d0a18b51fbb5fa8ce53f44a24ce869f6495cac809b7e3770
```

工作 clone 位于 `.publish/Math-Agents`，直接从该 clone 运行脚本时其父目录没有那 401 个原始
源文件，因此会报告布局不满足；这不是源码 hash 变化。标准拓扑中的检查结果如上。

## 11. 验收结论

- 每个非空 Attempt 都会 harvest 和逐 Claim 独立审理；
- Attempt PASS 不再批量验证 Claim，Attempt FAIL 不降低已验证 Claim；
- 失败 Route 的正确 local Claim 可进入 LemmaMemory、TypedMemory、ClaimLifecycle 和 ProofGraph；
- false/unsupported Claim、模型 route-theorem tag 和不精确 counterexample 不获得权威；
- salvaged local Claim 不关闭主目标或其他 obligation；
- counterexample 只 refute 精确 target；
- route theorem 仍要求完整、验证通过的成功 Route；
- 第 10 轮恢复前后三类 hash 完全一致；
- v6 -> v7 缺省新 snapshots，不改变 Negative Registry；
- 第 1、2 个问题的受保护实现零差异，所有专项回归通过；
- 没有修改其余 10 个问题。
