# 第 4 个问题修复记录：持久化长推理中的重要中间发现

## 1. 修复状态

| 项目 | 结果 |
| --- | --- |
| 问题编号 | 004 |
| 修复分支 | `fix/004-durable-research-checkpoints` |
| 基准分支 | `java` |
| 基准 commit | `1467925d7c55e6b274163574e7fa320a92036037` |
| 提交信息 | `fix(research): persist interim findings across truncated calls` |
| 第 4 项专项测试 | PASS，36 tests |
| 20 轮恢复测试 | PASS，60/60 material findings persisted |
| 第 1、2、3 项回归 | PASS |
| 四模块全回归 | PASS |
| 完整 `verify-all.ps1 -Offline` | PASS，含 Docker/PostgreSQL IT |
| 修改范围 | 仅 durable public research checkpoint、恢复与生产上下文 |

本记录所在提交的最终 hash 由 `git show HEAD` 给出。提交 hash 不能写入其自身并保持同一个
commit，因此正文不硬编码自引用 hash。

## 2. 修复前的行为缺陷

修复前先增加两个不依赖新 API 的生产链黑盒测试，并在基准实现上得到独立行为失败：

1. `DesktopReasoningBudgetFindingLossBlackBoxTest`

   Provider reasoning trace 已出现 `same-support minimal representative`，但输出预算耗尽后，
   active state 与下一条生产 Prompt 都看不到该 finding。

2. `DesktopFinalJsonCompressionLossBlackBoxTest`

   Provider reasoning trace 已出现 `a1=15 triangle hitting-set structure`，但最终 structured JSON
   压缩或遗漏该内容后，下一条生产 Prompt 看不到该 finding。

因此修复前证据是“真实生产状态丢失中间发现”，不是“新增类型尚不存在”造成的编译失败。

## 3. 修复后的生产链

仅精确允许的 research stages 使用 checkpointed runner，普通 `call(...)` 语义不变：

```text
provider response stored
-> providerCallId 精确绑定 reasoning trace
-> 提取完整 public marker frames
-> 校验 frame/quote/offset/hash/limits
-> ResearchCheckpointLedger 原子提交
-> 应用显式 finding dispositions
-> Desktop checkpoint v8 持久化
-> 应用原 stage result
-> active findings 注入后续同 Route 生产 Prompt
```

预算耗尽时仍只执行既有的一次 artifact recovery，但在恢复前先按 `provider_call_id` 读取精确
trace、提交其中完整 marker，并持久化 ledger。恢复 Prompt 带入 active findings、已完成 frames、
trace SHA-256、有限 excerpt 与 finding accounting rule。

若原 trace 没有完整 marker，恢复模型只能提交带 `source_quote`、`quote_start`、`quote_end` 和
`quote_sha256` 的 finding；服务端对原始 trace 做精确切片和常量时间 hash 校验。校验失败的
sidecar 被剥离，不能进入 Ledger。完整原始 reasoning 不进入 progress、Prompt sidecar 或 Ledger。

## 4. Public Contract 与权威边界

新增 public、non-authoritative contract：

- `ResearchFindingKind`
- `ResearchFindingDraft`
- `ResearchCheckpointFrame`
- `ResearchFindingDispositionAction`
- `ResearchFindingDisposition`
- `ResearchFindingUpdateBatch`
- `CheckpointedResearchEnvelope`

Finding kind 精确为：

```text
CANDIDATE_LEMMA
COUNTEREXAMPLE_CANDIDATE
EXACT_EXAMPLE
DISCARDED_HYPOTHESIS
SHARP_OBSTRUCTION
REPRESENTATION_INSIGHT
CONSTRUCTION_CANDIDATE
NEXT_MICRO_OBLIGATION
```

这里没有 `VERIFIED`、`FACT` 或 `PROVED`。Research finding 本身不能直接写入 LemmaMemory
VERIFIED、TypedMemory FACT、ProofGraph closure 或 Permanent Negative Registry。

显式更新动作仅为：

```text
KEEP_ACTIVE
DEFER
PROMOTE_TO_PROPOSED_LEMMA
PROMOTE_TO_COUNTEREXAMPLE_CANDIDATE
REJECT_WITH_REASON
SUPERSEDE_WITH
```

晋升仍经过第 3 项既有 Claim Review 与 exact counterexample target 路径，并带
`source-research-finding:<finding-id>`。没有被最终 JSON 提及的 finding 不会被静默删除。

## 5. Parser、Ledger 与原子性

Marker 格式为：

```text
<MPM_PUBLIC_RESEARCH_CHECKPOINT_V1>
{ strict ResearchCheckpointFrame JSON }
</MPM_PUBLIC_RESEARCH_CHECKPOINT_V1>
```

确定性边界：完整行 marker、完整 begin/end、每 frame 不超过 16 KiB、每 frame 最多 8 个
findings、每 call 最多 16 个 frames、同一 call 的 frame sequence 不重复。损坏或半截 frame
被隔离，不能遮蔽后续完整 frame。

服务端 finding ID 绑定：

```text
problemHash + routeId + stage + providerCallId
+ frameSequence + kind + math-normalized statement
```

`ResearchCheckpointLedger` 保存 checkpoint、finding、状态和 audit，不保存 raw reasoning。
snapshot 使用 canonical JSON stable hash；restore 校验 map key 与 record ID 一致。Finding update
先计算全部 `PendingTransition`，全部合法后才一次提交；Desktop 再在恢复出的临时 Ledger 上完成
整批 capture，成功后才替换当前 Ledger，因此非法后置 update 不会留下半提交状态。

## 6. Reasoning Trace 与 Budget Exhaustion

`ReasoningTraceBinding`、`ReasoningTraceCall` 和 `ReasoningTraceStore` 增加精确
`provider_call_id` 绑定与查询，不使用 latest stage/agent 近似匹配。

`ReasoningBudgetExhaustedError.progress` 新增：

```text
provider_call_id
response_artifact_ref
reasoning_trace_call_id
reasoning_trace_task_id
reasoning_trace_sha256
reasoning_trace_characters
```

progress 不包含完整 reasoning text。测试证明完整 frame 在 budget 控制流抛出前已交给 sink；
marker-free fallback 只接受与原 trace 精确 quote/offset/hash 一致的 finding。

## 7. Malformed JSON 与最终遗漏

Checkpointed stage 先独立捕获并提交 trace frames 和可完整解析的 envelope checkpoint，再解析或
repair 原 stage 的嵌套 `result`。因此 nested result malformed 时，已经提交的 findings 不会随 JSON
repair 丢失。最终 structured artifact 没有重复某个 active finding，也不会将其从 Ledger 删除。

`CheckpointedStructuredRepairRetentionTest`、`DesktopFinalArtifactOmissionRetentionTest` 和
`DesktopFinalJsonCompressionLossBlackBoxTest` 覆盖这三条边界。

## 8. Checkpoint v7 -> v8

`DesktopSolveCheckpoint.CURRENT_SCHEMA_VERSION` 从 7 升为 8，新增：

- `ResearchCheckpointSnapshot researchCheckpoints`
- Route 的 `latestResearchCheckpointId`
- Route 的 `activeResearchFindingIds`
- Route 的 `lastCheckpointedProviderCallId`
- Route 的 `checkpointRecoveryCount`
- Route 的 `pendingFindingReconciliation`

v7 JSON 缺失这些字段时默认 empty/zero/false，不重新分析旧 reasoning trace。恢复后 Root Goal 与
Negative Registry hash 不变。新增结构化文件：

```text
structured/research-checkpoints.json
structured/research-finding-audit.json
```

没有新增 raw-chain-of-thought artifact，也没有修改 v5 -> v6 或 v6 -> v7 迁移语义。

## 9. 修改文件及目的

### 9.1 Contracts

- `CheckpointedResearchEnvelope.java`：承载 checkpoint sidecar、finding updates 和原严格 result。
- `ResearchCheckpointFrame.java`：定义有界 public frame。
- `ResearchFindingDraft.java`：定义 non-authoritative finding 与可选 exact quote 证据。
- `ResearchFindingKind.java`：定义 8 种非权威研究发现。
- `ResearchFindingDisposition*.java`：定义显式、可审计的 finding 状态更新。
- `ResearchFindingUpdateBatch.java`：拒绝重复 finding update。

### 9.2 Core

- `ResearchCheckpointFrameParser.java`：严格 marker、大小、数量、序列、quote 与 hash 校验。
- `ResearchCheckpointTraceSpan.java`：保存校验后的 offsets/hashes，不保存 raw trace。
- `ResearchCheckpointLedger.java`：稳定 ID、原子 transition、snapshot/restore 与 ledger hash。
- `ResearchCheckpointRecord.java`、`ResearchFindingRecord.java`：持久化来源、状态和版本。
- `ResearchCheckpointSnapshot.java`：v8 可恢复快照。
- `ResearchFindingAuditEvent.java`：保存 append/update/defer 审计。
- `ResearchFindingStatus.java`：定义 CAPTURED/ACTIVE/DEFERRED/PROMOTED/REJECTED/SUPERSEDED。

### 9.3 Server

- `StructuredAgentRunner.java`：增加精确 allowlist 的 `callCheckpointed(...)`、先提交后 repair、
  budget progress refs 和 fallback quote 校验；未改 billing、retry、failover 或 provider selection。
- `CheckpointedPromptBundle.java`、`CheckpointedStructuredCallResult.java`：保留原 response type 并返回
  已捕获 sidecar。
- `ResearchCheckpointedPromptFactory.java`：为精确 research stages 构造 checkpoint contract。
- `ResearchCheckpointCapture.java`、`ResearchCheckpointSink.java`：提供 runner 到生产持久层的提交边界。
- `ResearchCheckpointFallbackEvidence.java`：仅在恢复调用生命周期内持有原 trace 校验证据。
- `ReasoningTraceBinding.java`、`ReasoningTraceCall.java`、`ReasoningTraceStore.java`：精确
  provider-call trace binding/read。
- `AbstractHttpProvider.java`、`MockClient.java`：把 provider call identity 写入 trace；Mock 覆盖测试路径。

### 9.4 Desktop

- `DesktopSolveCheckpoint.java`：schema v8、research snapshot 与 Route projection。
- `DesktopSolveCoordinator.java`：接入 checkpointed call、原子提交、预算恢复、后续 research context、
  finding reconciliation、snapshot/restore 与结构化 artifacts。
- `DesktopPermanentNegativeKnowledgeProductionTest.java`、`DesktopV6VerifiedClaimContinuityTest.java`：
  仅把 checkpoint schema 期望值从 7 更新为 8，没有弱化前两项断言。

### 9.5 测试

- Contracts：`ResearchCheckpointContractsTest`。
- Core：parser、ledger、disposition、authority、snapshot、trace span 和 boundary validation 共 7 类。
- Server：stage allowlist、trace binding、budget progress/capture、JSON repair、marker-free quote 共 6 类。
- Desktop：两个修复前黑盒、生产 retention、budget recovery、final omission、atomicity、Greedy GCD、
  20 轮 restore 和 v7 migration 共 9 类，另有一个共享 Fake Provider harness。

## 10. 第 4 项专项测试

最终专项集合结果：

```text
CONTRACTS_TESTS=4
CORE_TESTS=16
SERVER_TESTS=7
DESKTOP_TESTS=9
TOTAL_TESTS=36
TOTAL_FAILURES=0
TOTAL_ERRORS=0
RESULT=PASS
```

全部专项使用 Fake Provider、内存依赖和临时目录，不调用真实 DeepSeek、外部网络、Docker、
PostgreSQL 或 Python Sidecar。

## 11. 20 轮诊断

```text
DURABLE RESEARCH CHECKPOINT DIAGNOSTIC
MATERIAL_FINDINGS_EMITTED=60
MATERIAL_FINDINGS_PERSISTED=60
ACTIVE_FINDING_LOSSES=0
BUDGET_RECOVERIES=5
JSON_REPAIR_RECOVERIES=5
FINAL_ARTIFACT_OMISSIONS_DETECTED=5
UNACCOUNTED_FINDINGS=0
DUPLICATE_FINDINGS=0
POST_RESTORE_FINDING_LOSSES=0
DUPLICATE_PROVIDER_CALLS_AFTER_RESTORE=0
RAW_REASONING_TEXT_IN_LEDGER=0
DIRECT_FACT_PROMOTIONS=0
DIRECT_CLAIM_VERIFICATIONS=0
DIRECT_NEGATIVE_REGISTRATIONS=0
MAIN_GOAL_CLOSURES=0
ROOT_HASH_CHANGES=0
NEGATIVE_REGISTRY_HASH_CHANGES=0
ATTEMPT_ARTIFACT_LEDGER_HASH_CHANGES=0
CLAIM_LIFECYCLE_HASH_CHANGES=0
RESTORE_LEDGER_HASH_BEFORE=bc3d2088526c102ed853b40ce3d4c959d80db32cfd088efb7a80f6b4c0af79cb
RESTORE_LEDGER_HASH_AFTER=bc3d2088526c102ed853b40ce3d4c959d80db32cfd088efb7a80f6b4c0af79cb
RESULT=PASS
```

上述数字均由断言检查的真实 Ledger、Prompt、checkpoint 和既有权威存储状态计算，不是固定打印。

## 12. 关键专项结论

- Budget exhaustion：5 次恢复，完整 frame 在异常抛出前提交；marker-free fallback 通过 exact quote。
- Malformed JSON：5 次 repair，已提交 findings 全部保留。
- Final artifact omission：5 次遗漏均被检测，active findings 未丢失。
- Greedy GCD：`same-support minimal representative` 保留在 Ledger，并进入下一生产 Prompt。
- Restore：第 10 轮 JSON checkpoint 恢复前后 Ledger hash 完全一致，无重复 provider call。
- Authority：Root、Negative Registry、AttemptArtifact Ledger、Claim Lifecycle hash 均 0 changes。
- Boundary：0 direct Fact promotions、0 direct Claim verifications、0 permanent negative registrations、
  0 main-goal closures、0 raw reasoning text in Ledger。

## 13. 前 3 项回归

显式复跑第 1、2、3 项全部指定测试，结果 `BUILD SUCCESS`：

- 第 1 项：Exact Goal Contract、deterministic audit、20 轮 Root Goal、parity、Desktop prompt propagation。
- 第 2 项：lifetime、monotonic merge、trust/scope、admission、legacy migration、30 轮、Desktop production、
  atomicity、no-bypass、Greedy GCD seeds 与 10 轮 widening。
- 第 3 项：artifact harvesting/lifecycle/review/targeting、failed-route salvage、no main-goal closure、
  atomic projection、20 轮 restore 及 `DesktopV6VerifiedClaimContinuityTest`。

关键既有诊断仍为 PASS：Root Goal 20 轮无替换；Negative Knowledge 150 次重入无 leak；Route
widening 10/10 blocked；Claim salvage 20 轮无 Fact/状态/恢复损失；v6 VERIFIED Fact 二次恢复无丢失、
无重复 Review、无重复晋升。

## 14. 全回归与完整门禁

四模块全回归：

```text
Contracts: 48 tests, 0 failures, 0 errors
Core:      974 tests, 0 failures, 0 errors
Server:    841 tests, 0 failures, 0 errors, 3 skipped
Desktop:    79 tests, 0 failures, 0 errors, 1 skipped
BUILD SUCCESS
```

完整标准布局执行：

```powershell
.\scripts\verify-all.ps1 -Offline
```

结果 `exit 0` / `FULL VERIFICATION: PASS`。该次实跑包括：

```text
UNIT_TESTS=2091
UNIT_FAILURES=0
UNIT_ERRORS=0
POSTGRESQL_INTEGRATION_TESTS=26
POSTGRESQL_IT_FAILURES=0
POSTGRESQL_IT_ERRORS=0
COVERAGE_RESULT=PASS
SECURITY_RESULT=PASS
LICENSE_RESULT=PASS
SPOTBUGS_RESULT=PASS
SOURCE_IMMUTABILITY=PASS
SOURCE_FILES=401
SOURCE_MANIFEST_SHA256=9f3def2bec8cea99d0a18b51fbb5fa8ce53f44a24ce869f6495cac809b7e3770
```

覆盖率没有放宽门槛：Contracts adjusted branch `86.390533%`，Core branch `75.323511%`，Server
branch `70.619146%`，均通过现有 gate。没有修改 Python Sidecar 性能阈值。

## 15. 保护文件与范围审计

相对基准 commit 对第 1、2、3 项列出的全部受保护生产文件执行：

```text
git diff --exit-code 1467925d... -- <protected files>
PROTECTED_FILES_NO_DIFF
```

没有修改 Exact Goal、RootGoalContract、Negative Knowledge、AttemptArtifact、Claim Review、
ClaimLifecycle、LemmaMemory 或 MemoryPromotionPolicy 的生产实现。`DesktopSolveCoordinator` 的改动只在
checkpointed research call、recovery context、research ledger persistence/restore 与 research context；
`DesktopSolveCheckpoint` 只做 v7 -> v8。

没有修改 BudgetConfig、AdaptiveBudgetManager、output/recovery token 配置、StageThinkingPolicy、
maxSegmentsPerPath、scheduler stop、DeepExploration tier、Provider 选择、billing、retry 或 failover。

## 16. 最终 Git 检查

提交前执行并要求通过：

```text
git diff --check
git status --short
git diff --stat
```

验证自动生成的 `migration/reports/phase-17-*.json` 已还原，不提交 target、日志、checkpoint、数据库、
临时验收布局或其他运行产物。

## 17. 验收结论

- [x] 中间 findings 在正常、budget exhaustion、truncated/malformed result 和 final omission 下持久化；
- [x] public checkpoint 有严格边界、稳定 ID、可恢复 Ledger 与原子 update；
- [x] raw reasoning 不进入 Ledger，marker-free recovery 必须通过精确 quote 校验；
- [x] findings 只作为 non-authoritative sidecar，不绕过第 1、2、3 项权威边界；
- [x] v7 -> v8 缺失字段安全默认 empty，不重放旧 trace；
- [x] 20 轮中 60/60 findings 保留，恢复前后 hash 一致；
- [x] 第 1、2、3 项回归、全模块回归与完整 Docker/PostgreSQL 门禁通过；
- [x] 没有删除、跳过或弱化旧测试与质量门；
- [x] 没有修改问题 005 至 013。
