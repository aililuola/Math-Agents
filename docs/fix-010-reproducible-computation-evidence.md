# Issue 010: 可复核数学计算与原生实验基础设施

## 1. 状态与边界

- 状态：`CLOSED`
- 分支：`fix/010-reproducible-computation-evidence`
- 基准：`ceed9ac4328277886a6b578f3769e56c95c3a24d`
- Commit A：`bc9879ce2e79ae48cb8c2b64ebbe2b3d95036a97`
  - `fix(computation): unify typed capabilities and evidence verification`
- Commit B：`f33d1b85a08594a2f17647efc3b0918f2855a6e6`
  - `fix(computation): add native exact experiments and crash-safe replay`
- Checkpoint schema：`17 -> 18`

本次只处理计算请求的类型化编译、执行、独立验证、证据持久化、受限权威投影和崩溃恢复。没有开始 Issue 011，没有修改全局 Execution / Math / Usage / Report 对账、Provider、Token、预算、并发或停止策略。

## 2. 修复前行为失败证据

以下六项是在基准代码上运行黑盒场景得到的行为失败，不是“新 API 不存在”导致的编译失败：

| 场景 | 修复前实际结果 | 缺陷 |
| --- | --- | --- |
| Capability catalog 漂移 | `NATIVE_HANDLER_SUPPORTED=1`, `TOOL_REQUEST_CONTRACT_ACCEPTED=0`, `CAPABILITY_CATALOG_DRIFT=1` | Handler 与 Contract 清单不一致 |
| 同一错误实现重跑 | `GENERATOR_RESULTS_MATCH=1`, `INVALID_CERTIFICATE_ACCEPTED=1`, `AUDIT_VALID=true` | 同一 Producer 的稳定错误被误当作独立验证 |
| 缺少 durable artifact | `EXECUTED_RESULTS=1`, Result/Certificate/Receipt refs 均为 `0` | 计算完成但没有可寻址证据包 |
| 硬中断后重复执行 | `LOGICAL_REQUESTS=1`, `HANDLER_EXECUTIONS=2`, expected `1` | 无 durable execution frontier |
| 恢复后 quota 归零 | restore 前 `2`，restore 后 `0`，expected `2` | 配额仅存在于进程内 |
| 精确线性代数依赖外部后端 | request `1`, native execution `0`, backend unavailable `1` | 基础精确计算缺少 Java 原生能力 |

## 3. 生产修改

### 3.1 Contracts

主要文件：

- `ComputationMethod.java`、`ToolRequest.java`、`ContractEnumValues.java`
  - 增加精确线性代数、有限集合映射、超图横截等稳定 JSON 方法值。
  - Contract 允许值委托统一枚举来源，消除自由字符串清单漂移。
- `ComputationCertificateEnvelope.java`、`ComputationCertificateType.java`
  - 保存 request、execution、scope、domain、result、certificate hash 以及 cases/witness/producer 信息。
- `ComputationVerificationReceipt.java`、`ComputationVerificationStatus.java`、`ComputationVerifiedAuthority.java`
  - 把验证结果和权威上限变为服务端生成、内容寻址的类型化收据。
- `ComputationDecisionPlan.java`、`ComputationDecisionBranch.java`、`ComputationDecisionAction.java`
  - 将 confirm/refute/inconclusive 后续动作从模型自由文本改为可验证分支。
- `ComputationOutcomeApplicationReceipt.java`
  - 记录受控投影是否以及如何执行，支持 exactly-once 恢复。

### 3.2 Core capability 与证据链

主要文件：

- `ComputationCapabilityRegistry.java` 及 Descriptor/Fingerprint/Snapshot/Registered 类型
  - Registry 成为 Contract、Prompt catalog、Handler、Producer、Verifier、schema 和 authority ceiling 的统一来源。
  - 未注册能力 fail closed；Java Native 优先于 Sidecar 和 Sandbox。
- `ComputationProducer.java`、`ComputationCertificateVerifier.java`、`IndependentComputationCertificateVerifier.java`
  - Producer 与 Verifier 分离为不同实现对象。
  - 验证器按证书结构重新检查 witness、有限域覆盖、图、线性代数、集合映射和超图证书。
  - 同一 Producer 重跑只保留为审计信号，不能授予数学权威。
- `ComputationArtifactStore.java`、`InMemoryComputationArtifactStore.java` 及 Artifact Record/Bundle/Snapshot
  - Result、Certificate、Verification Receipt、Outcome Receipt 均不可变且内容寻址。
- `ComputationEvidenceGate.java`、`ComputationOutcomeProjector.java`
  - 权威不超过 capability ceiling。
  - `NOT_REFUTED`、有界观察和未验证反例不能晋升为 Fact 或永久反例。
  - 投影只调用既有受控入口，不直接标记 Claim VERIFIED、写 Fact、写永久负知识或关闭主目标。
- `ComputationExecutionService.java` 及 Execution Ledger/Record/Snapshot/State
  - 实现 `ADMITTED -> RESULT_DURABLE -> VERIFICATION_DURABLE -> AUTHORITY_APPLIED` durable frontier。
  - 每个阶段可恢复、可确定性 roll-forward；Producer、Verifier 和权威投影均 exactly once。
- `ComputationCacheKey.java`、`CanonicalComputationCacheEntry.java`
  - Cache key 绑定 capability、producer/verifier version、schema 和 runtime fingerprint。
  - Cache hit 仍产生新的 claim-bound Verification/Outcome receipt，但不重新执行 Producer。
- `ComputationLedger.java`
  - 兼容接口从 durable execution ledger 派生 quota，不再用恢复后归零的进程内计数作为真源。
- `ComputationBroker.java`
  - 旧入口委托统一 execution service，返回真实 Result/Certificate/Receipt artifact refs。

### 3.3 Java 原生精确能力

- `ExactLinearAlgebraFunctions.java`
  - 使用精确有理数执行 rank、determinant、nullspace、线性方程等有限矩阵计算。
  - 非方阵 determinant fail closed，不能以零行列式伪造有效证书。
- `FiniteSetMapFunctions.java`
  - 精确验证有限映射的单射、满射、双射和目标像。
- `HypergraphTransversalFunctions.java`
  - 精确验证 hitting set、minimal hitting set 和有限横截搜索。
- 原有 graph、modular、recurrence、integer search、sequence、geometry、number theory 能力继续工作。
- 新基础设施通过架构测试确认不依赖 `GreedyGcd*` 或题目特定判断。

### 3.4 Desktop 与 checkpoint

- `ArtifactStoreComputationArtifactStore.java`
  - 将计算证据接到 Desktop 真实 Artifact Store。
- `DesktopSolveCoordinator.java`
  - 仅在 computation 区域接入统一 service、保存和恢复五类 snapshot。
- `DesktopSolveCheckpoint.java`
  - schema 升级到 18，新增 capability、execution、artifact、verification、outcome receipt snapshots。
  - 保留 v17 旧 trace/audit 兼容读取。
- `DesktopLiveRunExecutionBackend.java`
  - 生产执行入口使用同一 durable computation path。

## 4. v17 到 v18 迁移

真实 v17 JSON 经 deserialize、restore、保存为 v18、再次 restore：

```text
V17_TO_V18_COMPUTATION_MIGRATION_DIAGNOSTIC
LEGACY_COMPLETE_TRACES=1
LEGACY_AUDIT_ONLY_TRACES=1
LEGACY_AUTHORITY_LOSSES=0
LEGACY_UNSAFE_PROMOTIONS=0
MIGRATION_BACKEND_INVOCATIONS=0
POST_V18_RESTORE_LOSSES=0
POST_V18_RESTORE_DUPLICATES=0
RESULT=PASS
```

完整且 `replayValid=true` 的旧 trace 迁移为 durable result + accepted legacy verification；不完整或 replay 无效的旧记录只迁移为 audit-only。迁移没有调用模型、Docker、Python 或任何 Handler。

## 5. 专项测试

### 5.1 修复后结果

| 模块 | Issue 010 专项测试 | 结果 |
| --- | ---: | --- |
| Contracts/Core | 32 | 0 failures, 0 errors |
| Server | 7 | 0 failures, 0 errors |
| Desktop | 18 | 0 failures, 0 errors |

关键黑盒结果：

```text
CAPABILITY_CATALOG_DRIFT=0
HANDLER_CONTRACT_DRIFT=0
GENERATOR_RESULTS_MATCH=0
INVALID_CERTIFICATE_ACCEPTED=0
AUDIT_VALID=false
RESULT_ARTIFACT_REFS=1
CERTIFICATE_ARTIFACT_REFS=1
VERIFICATION_RECEIPT_REFS=1
EXPERIMENTS_BEFORE_RESTORE=2
EXPERIMENTS_AFTER_RESTORE=2
EXACT_LINEAR_ALGEBRA_REQUESTS=1
NATIVE_EXECUTIONS=1
BACKEND_UNAVAILABLE=0
```

### 5.2 原子性

```text
COMPUTATION ATOMICITY DIAGNOSTIC
FAILURE_POINTS=7
DETERMINISTIC_ROLL_FORWARDS=7
PARTIAL_EXECUTION_RECORDS=0
DUPLICATE_ARTIFACTS=0
RESULT=PASS
```

### 5.3 真实硬中断恢复

测试使用继承 `Error` 的终止信号，绕过普通异常补偿路径，并用新实例恢复 durable state：

```text
COMPUTATION HARD-CRASH RECOVERY DIAGNOSTIC
HARD_CRASH_POINTS=6
RESTORE_FAILURES=0
DUPLICATE_PRODUCER_EXECUTIONS=0
DUPLICATE_VERIFIER_EXECUTIONS=0
DUPLICATE_AUTHORITY_PROJECTIONS=0
GHOST_RESULTS=0
GHOST_CERTIFICATES=0
GHOST_RECEIPTS=0
PARTIAL_FACT_WRITES=0
PARTIAL_COUNTEREXAMPLE_WRITES=0
TASK_LEASE_LEAKS=0
RESULT=PASS
```

## 6. 20 轮恢复诊断

所有数字来自真实 Execution Ledger、Artifact Store、Verification Ledger、Outcome Receipt Ledger 和 checkpoint snapshot：

```text
REPRODUCIBLE COMPUTATION DIAGNOSTIC
ROUNDS=20
RESTORE_ROUND=10
LOGICAL_REQUESTS=80
ADMITTED_REQUESTS=80
NATIVE_JAVA_REQUESTS=80
EXTERNAL_TYPED_REQUESTS=0
SANDBOX_REQUESTS=0
DOCKER_REQUIRED_REQUESTS=0
RESULT_ARTIFACTS=80
CERTIFICATE_ARTIFACTS=80
VERIFICATION_RECEIPTS=80
OUTCOME_APPLICATION_RECEIPTS=80
EXACT_COUNTEREXAMPLES=20
FINITE_DOMAIN_CERTIFICATES=40
BOUNDED_OBSERVATIONS=20
NOT_REFUTED_FACT_PROMOTIONS=0
BOUNDED_SCOPE_ESCALATIONS=0
UNVERIFIED_COUNTEREXAMPLE_PROMOTIONS=0
DUPLICATE_PRODUCER_EXECUTIONS=0
DUPLICATE_VERIFIER_EXECUTIONS=0
DUPLICATE_AUTHORITY_PROJECTIONS=0
DUPLICATE_RESULT_ARTIFACTS=0
CACHE_HITS=20
CACHE_AUTHORITY_REBIND_BYPASSES=0
POST_RESTORE_EXECUTION_LOSSES=0
POST_RESTORE_RESULT_LOSSES=0
POST_RESTORE_VERIFICATION_LOSSES=0
POST_RESTORE_QUOTA_RESETS=0
POST_RESTORE_DUPLICATE_EXECUTIONS=0
RESULT_ARTIFACT_HASH_MISMATCHES=0
CERTIFICATE_HASH_MISMATCHES=0
VERIFICATION_RECEIPT_HASH_MISMATCHES=0
COMPUTATION_EVIDENCE_DISABLED_EVENTS=0
BACKEND_UNAVAILABLE_STATE_CORRUPTIONS=0
ROOT_HASH_CHANGES=0
NEGATIVE_REGISTRY_HASH_CHANGES=0
CLAIM_LIFECYCLE_HASH_CHANGES=0
RESEARCH_CHECKPOINT_HASH_CHANGES=0
CANONICALIZATION_HASH_CHANGES=0
CONVERGENCE_HASH_CHANGES=0
SEMANTIC_PIVOT_HASH_CHANGES=0
STRATEGY_PORTFOLIO_HASH_CHANGES=0
CLAIM_COURT_HASH_CHANGES=0
BROKER_HASH_CHANGES=0
DIRECT_CLAIM_VERIFICATIONS=0
DIRECT_FACT_PROMOTIONS=0
DIRECT_NEGATIVE_REGISTRATIONS=0
MAIN_GOAL_CLOSURES=0
RESULT=PASS
```

恢复前后稳定 hash：

```text
EXECUTION_HASH_BEFORE=0e90a7783594343d396b56b6fbd307603124ef1f14118328f64bb13b90037432
EXECUTION_HASH_AFTER =0e90a7783594343d396b56b6fbd307603124ef1f14118328f64bb13b90037432
ARTIFACT_HASH_BEFORE=45868b7a20a149b70f9ddec9496d173df12444ddede156ecb7bd769c2c1e2822
ARTIFACT_HASH_AFTER =45868b7a20a149b70f9ddec9496d173df12444ddede156ecb7bd769c2c1e2822
VERIFICATION_HASH_BEFORE=da2a7c62ca9d94611db834985239182725c38a450a1816e26d135d85e45b6692
VERIFICATION_HASH_AFTER =da2a7c62ca9d94611db834985239182725c38a450a1816e26d135d85e45b6692
```

## 7. 前九项回归与受保护文件

- Issue 001 到 Issue 009 显式专项：`555 tests, 0 failures, 0 errors, 0 skipped`。
- 19 个受保护生产文件相对 `ceed9ac` 的 `git diff --name-only` 输出为空。
- Root Goal、Negative Registry、Claim Lifecycle、Research Checkpoint、Proof Graph、Semantic Pivot、Strategy Portfolio、Claim Court 和 Mathematical Artifact Broker 的权威 hash 在 20 轮中均无变化。
- 计算结果没有旁路写 Claim VERIFIED、Fact、永久 Negative Knowledge 或 MAIN_GOAL closure。

## 8. 完整验证

最终代码在最后一次 determinant 边界修复后重新运行：

```powershell
.\scripts\verify-all.ps1 -Offline
```

结果：`FULL VERIFICATION: PASS`，耗时约 418 秒。

| 模块 | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| Contracts | 65 | 0 | 0 | 0 |
| Core | 1247 | 0 | 0 | 0 |
| Server unit | 871 | 0 | 0 | 3 |
| Server PostgreSQL IT | 26 | 0 | 0 | 0 |
| Desktop | 245 | 0 | 0 | 1 |
| Compatibility | 149 | 0 | 0 | 0 |
| 合计 | 2603 | 0 | 0 | 4 |

Docker PostgreSQL 集成门实际运行并通过，包括 `JdbcMessageRepositoryIT`、`MemoryProofGraphPostgresIT`、`PersistencePostgresIT`、`Phase17CheckpointOutboxPerformanceIT` 和 `ProviderCallPostgresIT`。

质量门：

- Contracts raw coverage：line `92.440919%`，branch `79.009721%`。
- Contracts adjusted gate：line `92.051282%`，branch `86.043873%`。
- Core coverage：line `90.576733%`，branch `75.011880%`。
- Server line coverage：`87.736618%`。
- Desktop line coverage：`78.860531%`。
- SpotBugs / FindSecBugs：`0` findings。
- OWASP、secret scan、license、source immutability、Python Sidecar performance：全部 `PASS`，未放宽任何门槛。

定向补充测试：`IndependentVerifierBranchCoverageTest` 为 `6 tests, 0 failures, 0 errors`。

## 9. Diff 与提交卫生

功能代码相对基准：

```text
131 files changed, 9493 insertions(+), 424 deletions(-)
```

未提交 `target`、日志、checkpoint、数据库、缓存或 verify 生成报告；`migration/reports` 已恢复，不包含本次运行产物。功能提交完成后工作树为空，文档提交完成后再次检查。

## 10. 最终诊断

```text
ISSUE 010 REPRODUCIBLE COMPUTATION DIAGNOSTIC
================================================================
CAPABILITY_REGISTRY_RESULT=PASS
CAPABILITY_CATALOG_DRIFT=0
HANDLER_CONTRACT_DRIFT=0
PRODUCER_VERIFIER_SEPARATION=PASS
INVALID_STABLE_CERTIFICATES_ACCEPTED=0
NATIVE_JAVA_EXECUTIONS=80
SANDBOX_EXECUTIONS=0
DOCKER_REQUIRED_NATIVE_EXECUTIONS=0
RESULT_ARTIFACT_LOSSES=0
CERTIFICATE_ARTIFACT_LOSSES=0
VERIFICATION_RECEIPT_LOSSES=0
NOT_REFUTED_FACT_PROMOTIONS=0
BOUNDED_SCOPE_ESCALATIONS=0
UNVERIFIED_COUNTEREXAMPLE_PROMOTIONS=0
DUPLICATE_PRODUCER_EXECUTIONS=0
DUPLICATE_VERIFIER_EXECUTIONS=0
DUPLICATE_AUTHORITY_PROJECTIONS=0
POST_RESTORE_QUOTA_RESETS=0
CACHE_AUTHORITY_REBIND_BYPASSES=0
SANDBOX_AUTHORITY_ESCALATIONS=0
ISSUE_001_REGRESSION=PASS
ISSUE_002_REGRESSION=PASS
ISSUE_003_REGRESSION=PASS
ISSUE_004_REGRESSION=PASS
ISSUE_005_REGRESSION=PASS
ISSUE_006_REGRESSION=PASS
ISSUE_007_REGRESSION=PASS
ISSUE_008_REGRESSION=PASS
ISSUE_009_REGRESSION=PASS
PROTECTED_FILES_NO_DIFF=PASS
FULL_VERIFICATION=PASS
WORKTREE_CLEAN=true
ISSUE_010_STATUS=CLOSED
================================================================
```

## 11. Independent source-audit closure patch

The follow-up audit identified four remaining Issue 010 boundaries. They are now closed on
the same branch without changing Issues 001-009 or beginning Issue 011.

### 11.1 Explicit independent native verification

- Removed every authority-producing default verifier path. Unsupported methods fail closed.
- Added independent exact replay for modular exhaustion, bounded integer search, recurrence,
  greedy sequences, candidate periods, exact geometry, and number theory.
- Replaced recursive determinant expansion with independent elimination.
- Added positive replay coverage for all supported native branches plus forged certificate and
  counterexample mutation coverage.

```text
NATIVE_METHODS_WITHOUT_EXPLICIT_VERIFIER=0
NATIVE_METHODS_WITH_POSITIVE_DEFAULT_VERIFIER=0
FORGED_NATIVE_CERTIFICATES_ACCEPTED=0
FORGED_NATIVE_COUNTEREXAMPLES_ACCEPTED=0
```

Before the patch, the seven named forged-native regression tests all failed because the old
positive default accepted their forged evidence. After the patch, all seven pass.

### 11.2 Exact computation target binding

`ComputationTargetBinding` is server-owned and binds the claim context hash, obligation semantic
hash, canonical target, scope, and polarity. Focus and text similarity cannot confer authority.
An unbound request receives an isolated `COMPUTATION_QUESTION`; its result cannot mutate another
mathematical obligation.

```text
WRONG_FOCUS_OBLIGATION_BINDINGS=0
SIMILARITY_ONLY_AUTHORITY_BINDINGS=0
WRONG_TARGET_REFUTATIONS=0
WRONG_TARGET_FINITE_CERTIFICATE_CLOSURES=0
WRONG_TARGET_FORMAL_CERTIFICATE_CLOSURES=0
WRONG_TARGET_CERTIFICATE_CLOSURES=0
```

### 11.3 Crash-safe authority projection

The durable state sequence is now:

```text
VERIFICATION_DURABLE
-> PROJECTION_READY
-> AUTHORITY_MUTATION_DURABLE
-> AUTHORITY_APPLIED
```

`ComputationAuthorityMutationReceipt` records the real Fact, counterexample, closed/refuted
obligation, and Claim Court evidence projections. Desktop mutation is checkpointed atomically;
restore deterministically rolls forward a durable mutation or performs a no-op for an already
applied receipt.

```text
COMPUTATION AUTHORITY PROJECTION HARD-CRASH DIAGNOSTIC
HARD_CRASH_POINTS=5
RESTORE_FAILURES=0
AUTHORITY_LEDGER_WITHOUT_MUTATION=0
MUTATION_WITHOUT_AUTHORITY_LEDGER=0
DUPLICATE_FACT_PROJECTIONS=0
DUPLICATE_COUNTEREXAMPLE_PROJECTIONS=0
PARTIAL_AUTHORITY_PROJECTIONS=0
ROOT_HASH_CHANGES=0
SECOND_RESTORE_CHANGES=0
RESULT=PASS

COMPUTATION AUTHORITY RESTORE RECONCILIATION DIAGNOSTIC
FRONTIERS_RECONCILED=3
AUTHORITY_LEDGER_WITHOUT_MUTATION=0
MUTATION_WITHOUT_AUTHORITY_LEDGER=0
PARTIAL_AUTHORITY_PROJECTIONS=0
SECOND_RESTORE_CHANGES=0
RESULT=PASS
```

### 11.4 Enforced resource envelope

The request compiler, native execution wrapper, and result validation now enforce CPU timeout,
memory, serialized input/output, matrix dimensions, exact-number bit length, finite-set size,
hypergraph size, certificate nodes, and result characters. All five required resource tests pass,
with additional boundary coverage for each independent limit and execution failure mode.

### 11.5 Final verification after the audit patch

```text
FULL VERIFICATION: PASS
Contracts=65, failures=0, errors=0, skipped=0
Core=1321, failures=0, errors=0, skipped=0
Server unit=871, failures=0, errors=0, skipped=3
Server PostgreSQL IT=26, failures=0, errors=0, skipped=0
Desktop=255, failures=0, errors=0, skipped=1
Compatibility=149, failures=0, errors=0, skipped=0
TOTAL=2687, failures=0, errors=0, skipped=4

CORE_LINE_COVERAGE=90.264414%
CORE_BRANCH_COVERAGE=75.107743%
SPOTBUGS_FINDBUGS_FINDINGS=0
POSTGRESQL_ITS=PASS
OWASP=PASS
SECRET_SCAN=PASS
LICENSE_GATE=PASS
SOURCE_IMMUTABILITY=PASS
PYTHON_SIDECAR_PERFORMANCE=PASS

ISSUE_010_STATUS=CLOSED
```

Issue 011 尚未开始。
