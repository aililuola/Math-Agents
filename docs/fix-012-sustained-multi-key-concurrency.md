# Issue 012: Sustained Multi-Key Concurrency

## 1. Scope and Git state

- Branch: `fix/012-sustained-multi-key-concurrency`
- Baseline: `c0efa070359b48bf9f5546a9c815c1244f17c7a2`
- Baseline contains the completed Issue 011 implementation.
- Commit A: `da1427e` (`fix(concurrency): add frozen epochs and credential leases`)
- Commit B: `687be5d` (`fix(concurrency): parallelize research stages with deterministic merge`)
- Documentation commit: `2173a31` (`docs(concurrency): record issue 012 verification`)
- Final production-chain patch: `fix(concurrency): wire frozen epochs into authoritative desktop stages`
- Final crash-atomic patch: `fix(concurrency): make epoch authority commits crash-atomic`
- Legacy commit-protocol closure baseline: `0f2b186ea800fbc9a5f31a633c780b773cd3fcbf`
- Legacy commit-protocol closure: `fix(concurrency): preserve legacy epoch commit protocol across upgrades`
- Prepared-Epoch protocol closure baseline: `0d2d67a5ebb3789de41137d1323eed33aebc95ff`
- Prepared-Epoch protocol closure: `fix(concurrency): upgrade replayed legacy epochs to receipt protocol`
- Desktop checkpoint schema: `19 -> 20 -> 21`
- PostgreSQL migration: `V6__research_concurrency_epochs.sql`

Issue 012 is limited to deciding which already-admitted work can overlap, reserving credentials,
freezing the read frontier, collecting durable task results, merging them deterministically, and
restoring the concurrency frontier. It does not change mathematical authority or decide how much
budget a mechanism deserves.

Issue 013 was not started. `BudgetConfig`, `AdaptiveBudgetManager`, provider pricing, stage token
limits, stopping thresholds, and zero-gain policy are unchanged relative to the baseline.

## 2. Production entry-point audit

The audit covered every production selection, provider-call, executor, wait, route loop, scheduler,
Claim Court, Temporal child, mutation, checkpoint, and restore surface named in the Issue 012
instructions.

| Surface | Production entry points | Final behavior |
| --- | --- | --- |
| Live desktop entry | `DesktopLiveRunExecutionBackend.executeLive` | Constructs `AgentPool`, `StructuredAgentRunner`, and `DesktopSolveCoordinator`; this is the active live path. `executeLegacyLive` remains private and explicitly compatibility-only for old fixtures. |
| Agent selection | `AgentPool.acquireLease`, `tryAcquireLease`; `DesktopSolveCoordinator.callStage` | Modern stage calls atomically select and reserve an eligible credential inside `AgentPool`. A `requiredAgentId` is supplied only by explicit fixed-role paths; ordinary concurrent work no longer performs `select -> fixed lease`. |
| Provider invocation | `StructuredAgentRunner.call`, `callCheckpointed`, `callWithFailover`, plus new `callLeased` and `callCheckpointedLeased` | Active coordinator calls use the leased variants. `AgentLease.call` binds the reserved `AgentRuntime` to the actual call and releases it through `AutoCloseable`, including failures. Existing unleased methods remain for compatibility and non-concurrent callers. |
| Initial exploration | `DesktopSolveCoordinator.exploreUnstartedRoutes`, `executeAuthoritativeEpoch` | Independent route drafts are compiled against one frozen authority anchor, executed as `ROUTE_EXPLORATION` work, settled durably, and committed in stable route order. |
| Route integration/review | `integrateCommittedRoutes`, `reviewRoutesConcurrently`, `executeAuthoritativeEpoch` | Independent route reviews execute as `ROUTE_REVIEW` work against one frozen epoch. No Route worker mutates authority; stable single-writer projection starts only after all results settle. |
| Claim Court | `reviewRouteClaimsConcurrently`, `reviewClaimWave`, `executeClaimCourtCaseAgainstFrozenSnapshot`, `commitClaimCourtResultsInStableOrder` | Each worker owns local Court/revision/execution/negative ledgers and returns a typed draft. The global Claim Lifecycle, Typed Memory, Proof Graph, attempts, tasks, and checkpoints are mutated only by the stable single writer after the barrier. |
| Scheduler | `runScheduler`, `schedulePendingProofTasksBatch`, `applyCompatibleSchedulerActions`, `compatibleSchedulerActions` | The scheduler no longer stops after the first compatible action. It compiles up to the existing `maxActionsPerRound`, rejects same-target conflicts, and later explores/integrates the resulting independent routes as a batch. |
| Focused work | `executeFocusedAttackMatrix`, frozen kinds `FOCUSED_PROVER`, `FOCUSED_FALSIFIER`, `FOCUSED_REPROVER`, `DEPENDENCY_AUDITOR` | The actual Coordinator path emits four typed work items against one snapshot and one stable merge receipt. No budget or focused-target selection rule was changed. |
| Synthesis/review | `synthesizeAndVerify` and coordination-stage classification | Review, verification, audit, adjudication, referee, and synthesis calls use the reserved coordination class; final authority remains with the existing final gate. |
| Desktop executor | `DesktopResearchEpochExecutor` | Uses Java virtual threads plus `ExecutorCompletionService`, supports call-site-managed leases for multi-stage workers, continuously refills slots, reaches an all-settled barrier, and prepares one stable merge plan. Production process-termination `Error` values cross the managed completion boundary; the legacy direct-worker API preserves its historical wrapped-failure contract. |
| Other executors | `DesktopRunManager`, Docker/Python I/O pools | These are run-management or computation-I/O executors, not Provider research schedulers, and were not reinterpreted as mathematical concurrency. |
| Future waits | completion queues in desktop batches and `DesktopResearchEpochExecutor` | Waits are completion ordered, never `Future` index ordered. Results are re-sorted by stable mathematical identity before merge. |
| Temporal children | `MathProofMeshSolveWorkflowImpl` | Child route workflows are created with stable IDs and started using `Async.function`; `Promise.allOf` supplies the barrier; results are sorted by `routeId` before parent-state mutation. |
| Durable desktop frontier | `DesktopSolveCheckpoint`, `DesktopSolveCoordinator.persist/restore` | Schema 21 carries epoch, task, result, authority-mutation receipt, lease, and telemetry snapshots. Restore reconciles old leases and uncertain tasks without blindly replaying Provider calls. |
| Mathematical mutations | route integration, Claim Court projection, proof graph/memory/broker/pivot/final gates | Workers only produce immutable result artifacts. Existing Issues 001-011 gates execute on the stable single-writer thread; failed Claim projections restore only their own mutation snapshot and cannot roll back successful siblings. |

The active `executeLive` path is covered by production Coordinator tests. Compatibility selection
APIs remain available to old callers, but the four-call race test drives the real `callStage` path
and observes four distinct leases with no eligible credential idle behind a preselected key.

## 3. Pre-fix evidence

The baseline evidence is recorded in two categories so compilation failures are not presented as
if they alone proved runtime behavior.

### 3.1 Behavioral and source-trace failures

| Case | Baseline observation |
| --- | --- |
| Sustained desktop pipeline | Only initial route exploration submitted multiple virtual threads; integration and later provider stages were route-by-route. |
| Credential reservation race | Four concurrent selections chose the same high-trust agent before any call incremented `activeCalls`: `SELECTIONS=4`, `DISTINCT_AGENT_SELECTIONS=1`, `IDLE_CREDENTIALS=4`. |
| Route review | Four independent completed attempts entered the integration review loop serially; effective review overlap was one. |
| Scheduler batch | Four compatible actions were available, but the first successful action caused the legacy loop to stop. |
| Claim Court | Independent Court cases were advanced one route at a time; cross-case provider overlap was one. |
| Focused bottleneck | The four admissible roles were not represented as a bounded independent work batch. |
| Temporal route children | `child.explore(...)` was invoked synchronously in the parent loop, so the next child was not started before the prior child completed. |
| Completion order | There was no canonical result-envelope/merge-plan boundary proving that reversed completion order produced the same merge hash. |
| Crash replay | There was no task/result/lease frontier capable of distinguishing a durable result from an uncertain in-flight call. |
| Epoch authority crash | With the production-chain patch but before the final atomicity patch, a test killed the process after the first Claim Court projection. The persisted checkpoint still had `MERGE_PREPARED` while `crash-claim-0` was already `EXTERNALLY_ADMITTED_FACT`; the assertion requiring an empty authority projection failed. This is behavioral pre-fix evidence, not a missing-API compilation failure. |
| Telemetry | The state exposed call totals but not Provider call intervals, per-key busy time, lease count, queue wait, barrier wait, or ready-work utilization. |
| Straggler handling | Submission-order waits and selection before reservation could leave ready work behind a slow call while another credential remained idle. |

### 3.2 Architecture-absence evidence

The first test-first build also failed because the baseline had no `FrozenResearchSnapshot`,
`ResearchEpochLedger`, `ResearchTaskLedger`, `ResearchResultLedger`, `AgentLease`, deterministic
merge plan, or concurrency telemetry API. That establishes the missing architecture, not by itself
the complete runtime defect. The direct baseline source traces and the credential-selection race
above provide the behavioral evidence; the fixed production tests provide the closure evidence.

### 3.3 Legacy committed-Epoch behavioral evidence

The final compatibility audit first reproduced the behavior through JSON checkpoint and restore
boundaries before the production protocol API was added. Against `0f2b186`, a real schema-20
checkpoint containing a committed Epoch without receipts
restored once and was upgraded to schema 21, but a fresh Coordinator failed the second restore
with `QUARANTINED_PARTIAL_AUTHORITY_COMMIT: committed epoch lacks durable receipts`.

The same baseline Desktop restore path accepted four independently tampered modern committed
receipts: merge-plan hash, authority-before hash, authority-after hash, and accepted-result hashes.
Foreign-Epoch and dangling-merge cases were already rejected. A newly created schema-21 Epoch
without receipts was also already rejected; that passing boundary was retained to ensure the
legacy repair did not weaken the modern protocol.

### 3.4 Prepared-Epoch protocol-upgrade behavioral evidence

The final narrow audit reproduced a distinct lifecycle defect against `0d2d67a`. A real
schema-20 `MERGE_PREPARED` Epoch with durable Provider results and no receipts was restored and
committed by the current crash-atomic writer. The commit produced one authority-mutation receipt
and one merge receipt without replaying a Provider call, but the Epoch retained
`LEGACY_NO_RECEIPT`. After both receipts were removed, a fresh Coordinator accepted the modern
commit as legacy instead of quarantining it.

The test-first diagnostics were:

```text
POST_COMMIT_PROTOCOL_RECEIPT_V1=0
REPLAYED_MODERN_COMMITTED_EPOCHS_WITHOUT_RECEIPTS=0
MISSING_RECEIPT_QUARANTINES=0
LEGACY_FAIL_OPEN_ACCEPTS=1
```

The Core regression independently failed both state classification and commit-time upgrade:
`MERGE_PREPARED` schema-20 records migrated to `LEGACY_NO_RECEIPT`, and a modern commit preserved
that legacy marker. These are runtime protocol failures, not missing-API compilation evidence.

## 4. Concurrency configuration

`ConcurrencyConfig` is independent from `BudgetConfig` and contains:

- `enabled`
- `researchSlots`
- `coordinationSlots`
- `maxInFlightTasks`
- `maxFocusedParallelRoles`
- `reserveCoordinationCapacity`
- `allowCoordinationBorrowing`
- `telemetrySampleMillis`
- `leaseTimeoutSeconds`

The Core/Server implementation supports any enabled-agent count, provider, role set, per-agent
capacity, and global capacity. It contains no DeepSeek, API-key-environment, or problem-specific
constant. Validation enforces that configured slots fit both `runtime.maxParallelCalls` and the
sum of enabled per-agent capacity.

The existing desktop live profile deliberately configures its required deployment topology as:

```yaml
concurrency:
  enabled: true
  research_slots: 4
  coordination_slots: 1
  max_in_flight_tasks: 5
  max_focused_parallel_roles: 4
  reserve_coordination_capacity: true
  allow_coordination_borrowing: false
```

This profile did not raise `runtime.max_parallel_calls`, any agent's `max_concurrency`, or any call
or token budget.

## 5. Frozen epochs and work graph

`ResearchAuthorityAnchor` binds the problem/root goal and the frozen hashes for Negative
Knowledge, attempt artifacts, Claim Lifecycle, research checkpoints, Proof Graph,
canonicalization, convergence, semantic pivots, strategy portfolio, Claim Court, Broker,
computation, and Run State authority.

`FrozenResearchSnapshot` binds that anchor to a deterministic `ResearchEpochId` and snapshot hash.
All work in an epoch carries the same epoch ID and snapshot hash. `ResearchEpochLedger` tracks:

`PLANNED -> DISPATCHING -> ALL_SETTLED -> MERGE_PREPARED -> COMMITTED`

with `ABORTED` and `QUARANTINED` failure states available. A mismatched current authority hash
fails with `STALE_SNAPSHOT`; stale results are not silently applied to a newer frontier.

`ResearchWorkItem` carries a server-generated identity, kind, route/Claim/obligation/canonical
target, required role, lease class, exclusions, read set, conflict set, input artifact reference,
result schema, and stable ordinal. Conflict sets prevent concurrent work on the same route, Claim
case, pivot, exact obligation mutation, or strategy epoch. Java-native computation remains outside
Provider-key slot accounting.

Workers return immutable `ResearchWorkResultEnvelope` values containing only public structured
output and artifact/usage references. Hidden reasoning is not stored. Result hashes are content
addressed, and the result ledger is updated before the corresponding task reaches a durable
terminal status.

## 6. Agent and credential leases

`AgentPool.acquireLease` and `tryAcquireLease` perform candidate selection and reservation while
holding one pool lock. Capacity is charged to `reservedCalls` before a virtual thread is submitted,
so concurrent callers cannot all select the same apparently idle key.

Selection applies, in order, role/exclusion eligibility, available capacity, epoch busy time, run
lease count, specialty, trust, provider preference, and stable agent ID. This makes small trust
differences a tie-break rather than a reason to starve other credentials. Research leases cannot
consume the reserved coordination capacity when borrowing is disabled.

The lease request binds run, epoch, work item, lease class, role, author exclusion, provider
preference, and permit count. Issue 008 role separation is enforced at the lease boundary. A
try-with-resources close releases capacity for success, provider failure, cancellation, timeout,
or worker failure. Restore marks leases owned by an old execution attempt abandoned/expired and
does not infer that their tasks are safe to replay.

The real `GlobalParallelLimitTest` holds four Research calls and one Coordination call inside the
actual Provider responder at the same time. It observes both the live counter and ledger telemetry
at five, rejects a sixth lease, and therefore proves Provider-call overlap rather than merely
thread creation.

## 7. Continuous dispatch and deterministic merge

`DesktopSolveCoordinator.executeAuthoritativeEpoch` freezes the current production authority and
delegates the bounded work loop to `DesktopResearchEpochExecutor`:

1. Sort and record the work plan.
2. Put non-settled work in `ResearchReadyQueue`.
3. Poll only a conflict-compatible item.
4. Submit a virtual thread; managed workers acquire each Provider credential atomically at the
   actual `callStage` boundary, while single-call workers may reserve before submission.
5. Consume the next completed task from `ExecutorCompletionService`.
6. Release its lease and immediately refill the free slot.
7. Continue until every task is durably settled.
8. Enter the `ALL_SETTLED` barrier.
9. Build one `ResearchMergePlan` in stable ordinal/route/Claim/obligation/work-item order.
10. Commit through the single-writer `ResearchEpochCommitter` after rechecking the authority anchor.

No first-success or first-N result can mutate the authority frontier. Failures become typed durable
or quarantined results and remain visible to the merge decision. Completion timestamps, Provider
latency, provenance timestamps, and `Future` return order are excluded from mathematical merge
identity.

The one-slow/seven-normal straggler test verifies that later ready work starts while the slow task
is still active. The slow task completes after at least four ordinary tasks, all eight tasks start,
and the measured maximum remains four; there is no `Future`-index head-of-line wait.

## 8. Stage-wide production behavior

- **Initial exploration:** independent routes execute as frozen `ROUTE_EXPLORATION` work; their
  drafts are projected only after the all-settled barrier.
- **Route review:** independent review chains execute as frozen `ROUTE_REVIEW` work; failures are
  collected, then projected in stable route order.
- **Claim Court:** different cases execute concurrently from local worker ledgers. Each individual
  case still follows the Falsification, Audit, Repair, and Blind Adjudication state machine, but no
  worker writes global Claim, Memory, Graph, task, or checkpoint authority. Same semantic Court
  identities are grouped once and then projected to every Route target by the single writer.
- **Focused attack matrix:** Prover, Falsifier, independent Re-prover, and dependency/quantifier
  Auditor are represented as distinct conflict-checked work kinds and use at most the configured
  focused-role slots.
- **Scheduler actions:** up to the existing `maxActionsPerRound` compatible actions are selected;
  same-route/global-widen conflicts are excluded. Budget ordering and scoring are unchanged.
- **Synthesis/final review:** coordination capacity is reserved for independent review and
  adjudication work. The existing final gate remains the only mathematical authority.
- **Temporal:** route children start asynchronously, share stable logical identities, wait at one
  all-child barrier, and merge in sorted route order.

`DesktopAuthoritativePipelineUsesFrozenEpochTest` drives the real Exploration, Review, and Claim
Court path and observes seven work items, seven result artifacts, and three merge receipts with no
worker authority mutation. `DesktopFocusedAttackMatrixProductionTest` drives the four actual
focused work kinds. `DesktopClaimCourtBatchProductionTest` continues to cover real multi-case
Provider overlap.

## 9. Telemetry

`ConcurrencyTelemetryLedger` records `WORK_QUEUED`, `LEASE_ACQUIRED`,
`PROVIDER_CALL_STARTED`, `FIRST_TOKEN_RECEIVED`, `PROVIDER_CALL_COMPLETED`, `RESULT_DURABLE`,
`LEASE_RELEASED`, `BARRIER_ENTERED`, `BARRIER_RELEASED`, `MERGE_STARTED`, and
`MERGE_COMPLETED`.

`ConcurrencyMetrics` derives maximum and mean Provider concurrency, ready-window slot utilization,
single/zero-active fractions, per-agent busy time and lease counts, queue wait, barrier wait, and
straggler idle time from actual `PROVIDER_CALL_STARTED` to `PROVIDER_CALL_COMPLETED` intervals.
It does not count threads, Futures, or submitted work as active Provider calls. Deterministic tests
use an injected monotonic ticker.

The fake-provider gates verify four-way overlap for Research and a separate real 4+1 global test
verifies the reserved Coordination slot. Real network runs emit metrics but do not fail solely due
to nondeterministic Provider latency or rate limiting.

## 10. Checkpoint, restore, and persistence

Desktop schema 20 added:

- `ResearchEpochSnapshot`
- `ResearchTaskSnapshot`
- `ResearchResultSnapshot`
- `AgentLeaseSnapshot`
- `ConcurrencyTelemetrySnapshot`

Desktop schema 21 adds `ResearchAuthorityMutationSnapshot`, containing the server-generated
`ResearchAuthorityMutationReceipt` and its matching `ResearchMergeReceipt`. A receipt binds the
Epoch ID, merge-plan hash, authority hashes before and after the batch, accepted result hashes,
projected Claim IDs, Fact message IDs, and refuted obligation IDs. Its content hash and snapshot
hash are checked during deserialization.

Receipt requirements are now an Epoch property rather than an inference from the outer checkpoint
schema. `ResearchEpochRecord.authorityCommitProtocol` is either `LEGACY_NO_RECEIPT` or
`RECEIPT_V1`, and `authorityHashAfterCommit` durably binds a modern committed receipt to its Epoch
without incorrectly comparing an old Epoch with a later Campaign authority frontier. New Epochs
are always `RECEIPT_V1`. A v19/v20 Epoch without protocol metadata migrates to
`LEGACY_NO_RECEIPT` only when it was already `COMMITTED` and both receipts are absent. An
uncommitted legacy Epoch migrates to `RECEIPT_V1`, because its authority commit will run under the
current receipt protocol. `ResearchEpochLedger.commit` also forces `RECEIPT_V1` as a second
defense whenever the modern writer completes a commit. A committed legacy marker survives every
subsequent schema-21 save and restore. When old metadata already includes both receipts, migration
derives its durable after-hash once and preserves the modern protocol. No receipt is synthesized
and no Provider call is made during migration.

The v19 migration supplies empty snapshots and rebuilds only the concurrency projection. It makes
no model call and does not copy or reinterpret mathematical authority. Existing durable attempts
remain authoritative; uncertain running calls are quarantined rather than automatically replayed.

Restore rules are covered for planned, running/uncertain, result-durable, merge-prepared, and
committed frontiers. `ResearchEpochRecord` durably carries the frozen authority anchor, so a fresh
Coordinator can resume a `MERGE_PREPARED` epoch without rebuilding identity from mutated state. A
durable result is reused exactly once. An in-flight call without conclusive response evidence
becomes `QUARANTINED_UNCERTAIN_CALL`. Old-attempt leases release capacity, but a lease timeout alone
never authorizes a Provider replay.

Flyway V6 creates `research_epoch`, `research_work_item`, `agent_lease`, and
`concurrency_telemetry_event`, with run/epoch/task identities, status, result references, versions,
and fencing tokens. The schema stores agent IDs but no API key, credential value, or environment
secret. The Docker-backed PostgreSQL verification applied V1 through V6 and exercised lease
fencing and the new table contracts.

Temporal tests cover asynchronous child start, stable child IDs, replay identity, result
exactly-once behavior, completion-order invariance, and fencing rejection.

## 11. Atomicity and hard-crash behavior

`ResearchConcurrencyFailurePoint` names every durable boundary from epoch planning through task
recording, lease acquisition, response/result durability, all-settled, merge preparation,
authority mutation, epoch commit, and checkpoint atomic move.

The failure tests prove that no task/result/lease exists before its predecessor boundary, a
durable response/result is not called again, uncertain calls quarantine, merge order is stable,
and restored old leases do not consume capacity. Claim Court persists Provider-stage execution as
an operational frontier before a simulated process termination; this frontier is checkpointed but
excluded from the frozen mathematical-authority hash until stable projection. A fresh Coordinator
then reuses both durable result artifacts, commits one merge, and performs each Claim/Graph
mutation exactly once.

Authority application remains single-writer. `ResearchEpochCommitter` now accepts an explicit
rollback-capable `ResearchAuthorityMutationTransaction`, snapshots the full production projection
before the stable writer, validates the mutation receipt, and restores the batch on an ordinary
runtime failure. Existing Issues 001-011 gates still decide whether individual mathematical
effects are admissible.

During an authoritative Epoch commit, `activeEpochAuthorityCommit` suppresses nested formal
checkpoint writes from Claim Court, Exploration, Route Review, computation, Broker, and other
existing projection helpers. All accepted results, the mutation receipt, merge receipt, task
terminal states, and `Epoch=COMMITTED` are staged in memory and written once through
`research_epoch_committed`. The authoritative `desktop-solve-state.json` therefore exposes only
the whole pre-commit frontier or the whole committed frontier. Non-Epoch Claim Court calls retain
their historical per-case persistence behavior.

Restore distinguishes four frontiers: an unchanged `MERGE_PREPARED` Epoch without receipts is
replayed once; a complete committed receipt is a no-op; a fully receipted prepared frontier can
roll forward without reprojecting mathematics; and an advanced or internally inconsistent
frontier without a complete receipt is quarantined as
`QUARANTINED_PARTIAL_AUTHORITY_COMMIT`. Dangling merge receipts and receipts bound to another
Epoch are also quarantined. Every committed Epoch now traverses the same
`ResearchEpochCommitStateMachine`; the former Desktop early-`continue` bypass was removed. The
state machine validates Epoch ID, merge-plan hash, authority-before and durable authority-after
hashes, accepted-result identity, exact accepted/rejected partition, and mutation/merge receipt
agreement. A legacy committed Epoch is read-only compatible only when both receipts are absent;
an inconsistent partial legacy receipt is quarantined. The compatibility
`executeFrozenResearchEpoch` path records a no-authority-change receipt and commits its Epoch
instead of leaving a permanent prepared frontier.

## 12. Twenty-round diagnostic

The 20-round fixture uses five fake agents, four Research slots, one reserved Coordination slot,
80 Provider work items, a real schema-21 JSON checkpoint round trip at round 10, and the
coordinator-owned epoch/task/result/lease/telemetry ledgers. Assertions, rather than console text,
decide the result.

```text
SUSTAINED MULTI-KEY CONCURRENCY DIAGNOSTIC
ROUNDS=20
RESTORE_ROUND=10
ENABLED_AGENTS=5
RESEARCH_SLOTS=4
COORDINATION_SLOTS=1
PROVIDER_WORK_ITEMS=80
MAX_ACTIVE_PROVIDER_CALLS=4
POST_RESTORE_TASK_LOSSES=0
EPOCH_HASH_BEFORE_RESTORE=a2cde3849f132a544f04f2cfb49255bd92065ca86ade0bac43db10d258731b40
EPOCH_HASH_AFTER_RESTORE=a2cde3849f132a544f04f2cfb49255bd92065ca86ade0bac43db10d258731b40
TASK_HASH_BEFORE_RESTORE=05545ac85f63cf44e3578f1491d1cf115e7ed1aea1262a42c42803d8e8595ba1
TASK_HASH_AFTER_RESTORE=05545ac85f63cf44e3578f1491d1cf115e7ed1aea1262a42c42803d8e8595ba1
RESULT_HASH_BEFORE_RESTORE=0ccd2ef9ebb2630735f092d04d8e9f959382235047201eb3ba7c7d16ddaeb570
RESULT_HASH_AFTER_RESTORE=0ccd2ef9ebb2630735f092d04d8e9f959382235047201eb3ba7c7d16ddaeb570
LEASE_HASH_BEFORE_RESTORE=8270a46efc355a488dfd1738856fc7bc7700f8f2cd7b81f72fe62864d3bc0e69
LEASE_HASH_AFTER_RESTORE=8270a46efc355a488dfd1738856fc7bc7700f8f2cd7b81f72fe62864d3bc0e69
TELEMETRY_HASH_BEFORE_RESTORE=1d7f53d7fbbdaa2d02a40806847f46d36fe4688e670069c3cf6c33738555fc1e
TELEMETRY_HASH_AFTER_RESTORE=1d7f53d7fbbdaa2d02a40806847f46d36fe4688e670069c3cf6c33738555fc1e
ROOT_HASH_CHANGES=0
RESULT=PASS
```

### 12.1 Legacy protocol and committed-receipt diagnostics

```text
LEGACY COMMITTED EPOCH SECOND-RESTORE DIAGNOSTIC
LEGACY_COMMITTED_EPOCHS=1
FIRST_RESTORE_FAILURES=0
SECOND_RESTORE_FAILURES=0
THIRD_RESTORE_FAILURES=0
LEGACY_EPOCH_PROTOCOL_LOSSES=0
LEGACY_RECEIPTS_SYNTHESIZED=0
PROVIDER_CALLS_DURING_MIGRATION=0
POST_SECOND_RESTORE_EPOCH_STATUS=COMMITTED
POST_SECOND_RESTORE_AUTHORITY_CHANGES=0
RESULT=PASS

COMMITTED EPOCH RECEIPT BINDING DIAGNOSTIC
FOREIGN_EPOCH_RECEIPT_ACCEPTS=0
MERGE_PLAN_MISMATCH_ACCEPTS=0
AUTHORITY_BEFORE_MISMATCH_ACCEPTS=0
AUTHORITY_AFTER_MISMATCH_ACCEPTS=0
ACCEPTED_RESULT_MISMATCH_ACCEPTS=0
DANGLING_MERGE_RECEIPT_ACCEPTS=0
RESULT=PASS

MODERN COMMITTED EPOCH MISSING-RECEIPT DIAGNOSTIC
MODERN_COMMITTED_EPOCHS_WITHOUT_RECEIPT=1
MODERN_MISSING_RECEIPT_QUARANTINES=1
RESULT=PASS

V20 PREPARED EPOCH MODERN-COMMIT PROTOCOL DIAGNOSTIC
LEGACY_PREPARED_EPOCHS=1
MODERN_REPLAYED_COMMITS=1
POST_COMMIT_PROTOCOL_RECEIPT_V1=1
POST_COMMIT_MUTATION_RECEIPTS=1
POST_COMMIT_MERGE_RECEIPTS=1
PROVIDER_CALL_REPLAYS=0
REPLAYED_MODERN_COMMITTED_EPOCHS_WITHOUT_RECEIPTS=1
MISSING_RECEIPT_QUARANTINES=1
LEGACY_FAIL_OPEN_ACCEPTS=0
RESULT=PASS
```

`MAX_ACTIVE_PROVIDER_CALLS=4` is correct for this particular 20-round workload because it contains
Research work only. It must not be misreported as five. `GlobalParallelLimitTest` separately
launches four Research calls plus one Coordination call, observes actual maximum five in both the
Provider responder and telemetry ledger, and rejects a sixth lease.

The remaining focused assertions report:

```text
INITIAL_EXPLORATION_MAX_CONCURRENCY=4
ROUTE_REVIEW_MAX_CONCURRENCY=4
CLAIM_COURT_MAX_CONCURRENCY=4
FOCUSED_WORK_MAX_CONCURRENCY=4
GLOBAL_PARALLEL_LIMIT_BYPASSES=0
PER_AGENT_CAPACITY_BYPASSES=0
AGENT_LEASE_COLLISIONS=0
AUTHOR_REVIEWER_LEASE_VIOLATIONS=0
BLIND_ROLE_LEASE_VIOLATIONS=0
HEAD_OF_LINE_BLOCKS=0
SAME_EPOCH_CROSS_TASK_VISIBILITY=0
CONCURRENT_WORKER_AUTHORITY_MUTATIONS=0
COMPLETION_ORDER_MERGE_HASH_CHANGES=0
DUPLICATE_PROVIDER_CALLS=0
DUPLICATE_TASK_RESULTS=0
DUPLICATE_MERGES=0
LEASE_LEAKS=0
POST_RESTORE_TASK_LOSSES=0
POST_RESTORE_PROVIDER_CALL_REPLAYS=0
TEMPORAL_REPLAY_DUPLICATE_CHILDREN=0
DESKTOP_TEMPORAL_MERGE_HASH_MISMATCHES=0
```

### 12.2 Final production-chain diagnostics

The final audit patch adds five black-box tests against the real Coordinator path. The figures
below are computed from asserted state, not fixed status text.

```text
AUTHORITATIVE PIPELINE FROZEN EPOCH DIAGNOSTIC
PRODUCTION_WORK_ITEMS=7
PRODUCTION_RESULT_ARTIFACTS=7
PRODUCTION_MERGE_RECEIPTS=3
PRODUCTION_WORK_KINDS=[ROUTE_REVIEW, ROUTE_EXPLORATION, CLAIM_PROOF_AUDIT]
DIRECT_ROUTE_WORKER_AUTHORITY_MUTATIONS=0
RESULT=PASS

CLAIM COURT ROLLBACK ISOLATION DIAGNOSTIC
CONCURRENT_CASES=4
SUCCESSFUL_CASES=3
FAILED_PROJECTION_CASES=1
SUCCESSFUL_SIBLING_CLAIM_LOSSES=0
SUCCESSFUL_SIBLING_FACT_LOSSES=0
SUCCESSFUL_SIBLING_GRAPH_LOSSES=0
CROSS_CASE_GLOBAL_ROLLBACKS=0
RESULT=PASS

CLAIM COURT COMPLETION ORDER DETERMINISM DIAGNOSTIC
COMPLETION_ORDERS_EXECUTED=3
DISTINCT_COMPLETION_ORDERS=3
CLAIM_LIFECYCLE_HASH_CHANGES=0
TYPED_MEMORY_HASH_CHANGES=0
PROOF_GRAPH_HASH_CHANGES=0
ATTEMPT_ARTIFACT_HASH_CHANGES=0
EPOCH_COMMIT_HASH_CHANGES=0
RESULT=PASS

CALLSTAGE ATOMIC CREDENTIAL LEASE DIAGNOSTIC
CONCURRENT_STAGE_CALLS=4
DISTINCT_LEASED_AGENTS=4
MAX_ACTIVE_PROVIDER_CALLS=4
IDLE_ELIGIBLE_CREDENTIALS_WHILE_WAITING=0
FIXED_AGENT_SELECTION_RACE=0
RESULT=PASS

AUTHORITATIVE CONCURRENCY HARD CRASH DIAGNOSTIC
HARD_CRASHES_INJECTED=1
DURABLE_RESULT_ARTIFACTS_BEFORE_CRASH=2
PROVIDER_CALLS_BEFORE_CRASH=6
DUPLICATE_PROVIDER_CALLS=0
PIVOT_FREE_MERGE_COMMITS=1
DUPLICATE_MERGES=0
DUPLICATE_AUTHORITY_MUTATIONS=0
PARTIAL_CLAIM_WRITES=0
PARTIAL_GRAPH_WRITES=0
TASK_LEASE_LEAKS=0
PENDING_TASK_LEAKS=0
ROOT_HASH_CHANGES=0
NEGATIVE_REGISTRY_HASH_CHANGES=0
RESULT=PASS

FOCUSED ATTACK MATRIX PRODUCTION DIAGNOSTIC
FOCUSED_WORK_ITEMS=4
FOCUSED_RESULT_ARTIFACTS=4
FOCUSED_MERGE_RECEIPTS=1
FOCUSED_WORK_KINDS=[FOCUSED_PROVER, FOCUSED_FALSIFIER, DEPENDENCY_AUDITOR, FOCUSED_REPROVER]
FOCUSED_MAX_CONCURRENCY=4
DIRECT_WORKER_AUTHORITY_MUTATIONS=0
RESULT=PASS
```

The final crash-atomic test uses three real Claim Court cases (`VERIFIED`, `REFUTED`, and
`PROOF_INVALID_BUT_CLAIM_OPEN`) and creates a fresh Coordinator from the real persisted state at
each of four `Error`-based process-termination windows. Every number below is derived from the
checkpoint, Claim Lifecycle, Typed Memory, receipt ledger, Epoch ledger, and Provider-call store.

```text
AUTHORITATIVE EPOCH COMMIT CRASH DIAGNOSTIC
HARD_CRASH_POINTS=4
CLAIM_CASES=3
EXPECTED_AUTHORITY_MUTATIONS=3
PARTIAL_AUTHORITY_CHECKPOINTS=0
MERGE_PREPARED_WITH_ADVANCED_AUTHORITY=0
STALE_RESTORED_EPOCH_AUTHORITY_ERRORS=0
AUTHORITY_MUTATION_RECEIPTS=1
MERGE_RECEIPTS=1
COMMITTED_EPOCHS=1
DUPLICATE_PROVIDER_CALLS=0
DUPLICATE_AUTHORITY_MUTATIONS=0
DUPLICATE_FACTS=0
DUPLICATE_REFUTATIONS=0
DUPLICATE_CLAIM_PROJECTIONS=0
LOST_FACTS=0
LOST_REFUTATIONS=0
LOST_OPEN_CLAIMS=0
POST_SECOND_RESTORE_STATE_CHANGES=0
POST_SECOND_RESTORE_PROVIDER_CALLS=0
ROOT_HASH_CHANGES=0
NEGATIVE_REGISTRY_HASH_CHANGES=0
RESULT=PASS
```

## 13. Modified files and purpose

Commit A adds the generic concurrency domain under
`mathproofmesh-core/src/main/java/io/github/aililuola/mathproofmesh/concurrency/`: frozen authority
anchors, epochs, work/read/conflict models, task/result/lease/telemetry ledgers and snapshots,
ready/completion abstractions, deterministic merge planning, and the single-writer committer.

Commit A also adds `ConcurrencyConfig`, `AgentLease`, `AgentLeaseManager`, `LeasedAgent`, atomic
reservation and fairness metrics to `AgentPool`/`AgentRuntime`, lease-aware runner entry points,
desktop profile configuration, validation, and Core/Server tests. It does not introduce provider-
or problem-specific dependencies into the Core concurrency package.

Commit B adds `DesktopResearchEpochExecutor`, schema-20 checkpoint ownership and restore,
completion-queue route/review/Claim batches, compatible scheduler batching, lease-bound active
stage calls, async Temporal child workflows, deterministic Temporal/Desktop merge semantics,
Flyway V6, PostgreSQL/fencing tests, production and black-box Desktop tests, and the 20-round
restore diagnostic. `ConcurrencyDurabilityBoundaryTest` adds genuine branch coverage for invalid
transitions, restore boundaries, conflicts, and immutable snapshots; no coverage threshold was
lowered.

The final production-chain patch adds durable frozen authority anchors to epoch records, an
optional trusted `requiredAgentId` lease constraint, call-site-managed worker execution, and the
real `executeAuthoritativeEpoch` Coordinator bridge. It converts Exploration, Route Review, Claim
Court, and the focused matrix to immutable result artifacts plus stable single-writer projection.
Claim Court workers now use local ledgers, process-crash recovery reuses durable operational stage
results, and obsolete pre-epoch private exploration/Court bypasses were removed rather than
silenced. Five production black-box tests and three existing harness updates cover the bridge.

The final crash-atomic patch adds the Core authority transaction, receipt ledger/snapshot, restore
state machine, Desktop schema-21 projection, one-write Epoch commit context, and full production
rollback snapshot. It adds Core receipt/rollback/state-machine tests, a v20-to-v21 migration test,
the four-window production hard-crash test, and an architecture test that forbids formal
per-result persistence inside stable Epoch commit methods.

The legacy protocol closure adds `ResearchAuthorityCommitProtocol`, the immutable protocol and
after-authority binding on `ResearchEpochRecord`, and
`ResearchEpochCommitProtocolMigration`. The pure migration policy was deliberately extracted from
`DesktopSolveCoordinator`, keeping the Coordinator below SpotBugs' class-analysis limit without
disabling or filtering any finding. Desktop restore now sends legacy and modern committed Epochs
through the common state machine. Three new Desktop black-box suites cover schema-20 first,
second, and third restore, six receipt-binding corruptions, old pre-protocol schema-21 migration,
and the modern missing-receipt fail-closed boundary.

The prepared-Epoch closure makes protocol migration state-aware and makes the commit boundary
authoritative for protocol identity. `ResearchEpochCommitProtocolMigrationTest` covers both the
schema-20 state split and the commit-time second defense.
`DesktopV20PreparedEpochModernCommitProtocolTest` drives the real hard-crash checkpoint,
schema-20 JSON downgrade, durable-result replay, current Coordinator commit, schema-21 restart,
and receipt-deletion quarantine. It proves that checkpoint origin does not permanently label the
protocol used by a later commit.

Code-and-test diff by functional commit:

- Commit A: `88 files changed, 3066 insertions(+), 12 deletions(-)`.
- Commit B: `53 files changed, 3792 insertions(+), 122 deletions(-)`.
- Baseline through Commit B: `132 files changed, 6842 insertions(+), 118 deletions(-)`.
- Final crash-atomic patch: `20 files changed, 1884 insertions(+), 74 deletions(-)`.
- Prepared-Epoch protocol closure: `5 files changed, 287 insertions(+), 14 deletions(-)`.

No target directory, logs, checkpoints, databases, caches, or generated verification reports are
included.

## 14. Issue 012 specialized tests

All tests use fake providers, deterministic delays/tickers, in-memory dependencies, Temporal's
test environment, or Docker-backed local PostgreSQL. They make no real DeepSeek or external
network call.

| Suite | Tests | Failures | Errors | Skipped | Result |
| --- | ---: | ---: | ---: | ---: | --- |
| Core exact Issue 012 suite | 16 | 0 | 0 | 0 | PASS |
| Core authority receipt/rollback/recovery focus | 18 | 0 | 0 | 0 | PASS |
| Server/Provider/Temporal exact Issue 012 suite | 19 | 0 | 0 | 0 | PASS |
| Desktop exact Issue 012 suite, including final five tests | 29 | 0 | 0 | 0 | PASS |
| Final production and legacy Claim Court compatibility focus | 10 | 0 | 0 | 0 | PASS |
| Desktop authority hard-crash/migration/architecture focus | 7 | 0 | 0 | 0 | PASS |
| Legacy protocol and committed-receipt closure focus | 11 | 0 | 0 | 0 | PASS |
| Prepared-Epoch protocol-upgrade closure focus | 8 | 0 | 0 | 0 | PASS |

Coverage includes configuration, frozen identity, work conflicts, state transitions, monotonic
snapshots, atomic leases, fairness, role isolation, cooldown/failure isolation, actual global and
per-agent limits, lease-bound structured calls, ready-queue refill, all-settled behavior,
completion-order invariance, PostgreSQL contracts/fencing, Temporal children/replay, desktop stage
batches, atomicity, four in-commit hard-crash windows, v19/v20 migration, protected authority, and
20 rounds.

## 15. Module and full verification

The module regression command completed with `2701` tests, zero failures, zero errors, and four
intentional skips across Contracts, Core, Server, and Desktop. The direct
Core/Server/Desktop aggregate was `2636` tests with zero failures and zero errors.

The final `./scripts/verify-all.ps1 -Offline` completed in `16 min 05 s` with:

| Module/suite | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| Contracts unit | 65 | 0 | 0 | 0 |
| Core unit | 1389 | 0 | 0 | 0 |
| Server unit | 917 | 0 | 0 | 3 |
| Desktop unit | 330 | 0 | 0 | 1 |
| Compatibility | 149 | 0 | 0 | 0 |
| PostgreSQL/Sandbox failsafe IT | 26 | 0 | 0 | 0 |
| **Total** | **2876** | **0** | **0** | **4** |

The Docker-backed integration run included `MathProofMeshApplicationIT`,
`JdbcMessageRepositoryIT`, `MemoryProofGraphPostgresIT`, `PersistencePostgresIT`,
`Phase17CheckpointOutboxPerformanceIT`, `ProviderCallPostgresIT`, and `SandboxSecurityIT`.
PostgreSQL applied Flyway V6 successfully. Temporal concurrency tests passed in the Server suite.

The first release-gate pass exposed five new, local SpotBugs findings: immutable receipt lists and
the two deliberate transaction exception rethrows. They were fixed with narrowly justified
annotations after immutable copying and rollback behavior were verified; no SpotBugs rule was
disabled. A sandboxed retry could not reach the Windows Docker named pipe. The final run used the
local Docker Engine `29.6.2` outside that sandbox and passed all PostgreSQL/Testcontainers gates.

The legacy closure's first clean release-gate attempt pushed `DesktopSolveCoordinator` just over
SpotBugs' class-analysis limit and produced one `SKIPPED_CLASS_TOO_BIG` plus cascading suppression
and unread-field findings. Moving the pure protocol migration into its own Core class restored
full analysis. The standalone Desktop SpotBugs check and the final clean release gate then both
reported zero findings; no rule, baseline, threshold, or suppression policy was weakened.

All unchanged release gates passed:

- Core branch coverage: `75.491888%` against the unchanged `75%` gate.
- Core SpotBugs: zero findings.
- Server/Desktop SpotBugs and FindSecBugs: PASS.
- OWASP dependency scan: PASS.
- Secret scan: PASS.
- License policy: PASS.
- Source immutability: PASS.
- Python Sidecar performance: PASS, with no threshold change.
- Overall result: `FULL VERIFICATION PASS`.

## 16. Issues 001-011 and protected authority

The complete module and release-gate runs execute the explicit regressions introduced by Issues
001 through 011. All passed without deleting, skipping, or weakening their assertions.

The baseline no-diff check passed for the protected authority files: Root Goal, Permanent Negative
Knowledge, Attempt/Claim/Claim Court, Research Checkpoint, canonical Proof Graph/convergence,
Semantic Pivot, Strategy Portfolio, Mathematical Artifact Broker, reproducible computation, and
Run State reconciliation.

```text
ISSUE_001_REGRESSION=PASS
ISSUE_002_REGRESSION=PASS
ISSUE_003_REGRESSION=PASS
ISSUE_004_REGRESSION=PASS
ISSUE_005_REGRESSION=PASS
ISSUE_006_REGRESSION=PASS
ISSUE_007_REGRESSION=PASS
ISSUE_008_REGRESSION=PASS
ISSUE_009_REGRESSION=PASS
ISSUE_010_REGRESSION=PASS
ISSUE_011_REGRESSION=PASS
PROTECTED_FILES_NO_DIFF=PASS
ISSUE_013_AUTHORITY_FILES_NO_DIFF=PASS
```

## 17. Acceptance conclusion

- Frozen Research Epochs: PASS.
- Authoritative Desktop Exploration/Review/Claim Court epoch integration: PASS.
- Claim Court local drafts, stable single-writer projection, and sibling rollback isolation: PASS.
- Atomic credential leases and 4+1 capacity reservation: PASS.
- Active `callStage` atomic credential selection with no fixed-selection race: PASS.
- Sustained stage concurrency with completion-queue refill: PASS.
- All-settled deterministic batch merge: PASS.
- Completion-order invariance and straggler handling: PASS.
- Crash-safe task/result/lease restore: PASS.
- Crash-atomic Epoch authority commit with four real `Error` windows, durable receipts, and zero
  Provider replay or duplicate authority: PASS.
- Desktop/Temporal deterministic parity: PASS.
- Schema 20 and schema 21 compatibility migrations plus Flyway V6: PASS.
- Per-Epoch legacy/modern commit protocol survives schema upgrade and repeated restore: PASS.
- A schema-20 prepared Epoch committed by the modern writer upgrades to `RECEIPT_V1`: PASS.
- Removing both receipts from that replayed modern commit fails closed: PASS.
- Modern committed receipt binding and missing-receipt fail-closed behavior: PASS.
- Issues 001-011 regression and protected-file isolation: PASS.
- Full offline verification including Docker PostgreSQL: PASS.
- Issue 013 budget/token/stop-policy work: not started.

`ISSUE_012_STATUS=CLOSED`
