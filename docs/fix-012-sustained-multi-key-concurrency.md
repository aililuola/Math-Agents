# Issue 012: Sustained Multi-Key Concurrency

## 1. Scope and Git state

- Branch: `fix/012-sustained-multi-key-concurrency`
- Baseline: `c0efa070359b48bf9f5546a9c815c1244f17c7a2`
- Baseline contains the completed Issue 011 implementation.
- Commit A: `da1427e` (`fix(concurrency): add frozen epochs and credential leases`)
- Commit B: `687be5d` (`fix(concurrency): parallelize research stages with deterministic merge`)
- Documentation commit: `docs(concurrency): record issue 012 verification` (this commit)
- Desktop checkpoint schema: `19 -> 20`
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
| Agent selection | `AgentPool.select`, `selectReviewer`, `failoverCandidates`; coordinator role/team assignment | Compatibility selection remains available, but every active coordinator provider call crosses `callStage -> acquireStageLease` before dispatch. Concurrent research execution uses `tryAcquireLease` before submitting a virtual thread. |
| Provider invocation | `StructuredAgentRunner.call`, `callCheckpointed`, `callWithFailover`, plus new `callLeased` and `callCheckpointedLeased` | Active coordinator calls use the leased variants. `AgentLease.call` binds the reserved `AgentRuntime` to the actual call and releases it through `AutoCloseable`, including failures. Existing unleased methods remain for compatibility and non-concurrent callers. |
| Initial exploration | `DesktopSolveCoordinator.exploreUnstartedRoutes` | Independent routes use a virtual-thread `ExecutorCompletionService`; a completion immediately frees capacity instead of waiting by submission index. |
| Route integration/review | `integrateCommittedRoutes`, `reviewRoutesConcurrently` | Independent route reviews run concurrently; failures settle per route; route/checkpoint and authority projections are then processed in stable route order. |
| Claim Court | `reviewRouteClaimsConcurrently`, `reviewClaimWave`, `conductClaimCourt` | Conflict-free cases execute concurrently across cases. The existing within-case Falsification/Audit/Repair/Blind Adjudication order and role separation remain intact. |
| Scheduler | `runScheduler`, `schedulePendingProofTasksBatch`, `applyCompatibleSchedulerActions`, `compatibleSchedulerActions` | The scheduler no longer stops after the first compatible action. It compiles up to the existing `maxActionsPerRound`, rejects same-target conflicts, and later explores/integrates the resulting independent routes as a batch. |
| Focused work | frozen work kinds `FOCUSED_PROVER`, `FOCUSED_FALSIFIER`, `FOCUSED_REPROVER`, `DEPENDENCY_AUDITOR` | Four admitted roles can occupy the four research slots against one frozen authority snapshot. No budget or focused-target selection rule was changed. |
| Synthesis/review | `synthesizeAndVerify` and coordination-stage classification | Review, verification, audit, adjudication, referee, and synthesis calls use the reserved coordination class; final authority remains with the existing final gate. |
| Desktop executor | `DesktopResearchEpochExecutor` | Uses Java virtual threads plus `ExecutorCompletionService`, acquires a lease before submit, continuously refills slots, reaches an all-settled barrier, and prepares one stable merge plan. |
| Other executors | `DesktopRunManager`, Docker/Python I/O pools | These are run-management or computation-I/O executors, not Provider research schedulers, and were not reinterpreted as mathematical concurrency. |
| Future waits | completion queues in desktop batches and `DesktopResearchEpochExecutor` | Waits are completion ordered, never `Future` index ordered. Results are re-sorted by stable mathematical identity before merge. |
| Temporal children | `MathProofMeshSolveWorkflowImpl` | Child route workflows are created with stable IDs and started using `Async.function`; `Promise.allOf` supplies the barrier; results are sorted by `routeId` before parent-state mutation. |
| Durable desktop frontier | `DesktopSolveCheckpoint`, `DesktopSolveCoordinator.persist/restore` | Schema 20 carries epoch, task, result, lease, and telemetry snapshots. Restore reconciles old leases and uncertain tasks without blindly replaying Provider calls. |
| Mathematical mutations | route integration, Claim Court projection, proof graph/memory/broker/pivot/final gates | Concurrent workers cannot import protected authority services from the Core concurrency package. Results pass through existing Issues 001-011 gates and stable commit ordering. |

`pool.select(...)` references still visible in the private legacy backend are not reachable from the
active `executeLive` path. The active path is explicitly covered by the production coordinator and
lease-binding tests; the compatibility API was retained to avoid breaking old persisted fixtures.

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
| Telemetry | The state exposed call totals but not Provider call intervals, per-key busy time, lease count, queue wait, barrier wait, or ready-work utilization. |
| Straggler handling | Submission-order waits and selection before reservation could leave ready work behind a slow call while another credential remained idle. |

### 3.2 Architecture-absence evidence

The first test-first build also failed because the baseline had no `FrozenResearchSnapshot`,
`ResearchEpochLedger`, `ResearchTaskLedger`, `ResearchResultLedger`, `AgentLease`, deterministic
merge plan, or concurrency telemetry API. That establishes the missing architecture, not by itself
the complete runtime defect. The direct baseline source traces and the credential-selection race
above provide the behavioral evidence; the fixed production tests provide the closure evidence.

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

`DesktopResearchEpochExecutor` performs the following bounded loop:

1. Sort and record the work plan.
2. Put non-settled work in `ResearchReadyQueue`.
3. Poll only a conflict-compatible item.
4. Acquire a credential lease before submitting a virtual thread.
5. Consume the next completed task from `ExecutorCompletionService`.
6. Release its lease and immediately refill the free slot.
7. Continue until every task is durably settled.
8. Enter the `ALL_SETTLED` barrier.
9. Build one `ResearchMergePlan` in stable ordinal/route/Claim/obligation/work-item order.
10. Commit through the single-writer `ResearchEpochCommitter` after rechecking the authority anchor.

No first-success or first-N result can mutate the authority frontier. Failures become typed durable
or quarantined results and remain visible to the merge decision. Completion timestamps, Provider
latency, and `Future` return order are excluded from merge identity.

The one-slow/seven-normal straggler test verifies that later ready work starts while the slow task
is still active. The slow task completes after at least four ordinary tasks, all eight tasks start,
and the measured maximum remains four; there is no `Future`-index head-of-line wait.

## 8. Stage-wide production behavior

- **Initial exploration:** independent routes overlap through the completion queue.
- **Route review:** independent route review chains overlap; failures are collected, then projected
  in stable route order.
- **Claim Court:** different cases at the same available stage overlap. Each individual case still
  follows the pre-existing Falsification, Audit, Repair, and Blind Adjudication state machine.
- **Focused attack matrix:** Prover, Falsifier, independent Re-prover, and dependency/quantifier
  Auditor are represented as distinct conflict-checked work kinds and use at most the configured
  focused-role slots.
- **Scheduler actions:** up to the existing `maxActionsPerRound` compatible actions are selected;
  same-route/global-widen conflicts are excluded. Budget ordering and scoring are unchanged.
- **Synthesis/final review:** coordination capacity is reserved for independent review and
  adjudication work. The existing final gate remains the only mathematical authority.
- **Temporal:** route children start asynchronously, share stable logical identities, wait at one
  all-child barrier, and merge in sorted route order.

`DesktopSustainedConcurrencyBlackBoxTest` exercises Exploration, Route Review, Claim Audit, and
Focused Work with four real fake-provider call intervals each; every stage observes maximum
Provider concurrency four. `DesktopClaimCourtBatchProductionTest` additionally drives the real
coordinator Claim Court path rather than only the generic executor.

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

Desktop schema 20 adds:

- `ResearchEpochSnapshot`
- `ResearchTaskSnapshot`
- `ResearchResultSnapshot`
- `AgentLeaseSnapshot`
- `ConcurrencyTelemetrySnapshot`

The v19 migration supplies empty snapshots and rebuilds only the concurrency projection. It makes
no model call and does not copy or reinterpret mathematical authority. Existing durable attempts
remain authoritative; uncertain running calls are quarantined rather than automatically replayed.

Restore rules are covered for planned, running/uncertain, result-durable, merge-prepared, and
committed frontiers. A durable result is reused exactly once. An in-flight call without conclusive
response evidence becomes `QUARANTINED_UNCERTAIN_CALL`. Old-attempt leases release capacity, but a
lease timeout alone never authorizes a Provider replay.

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
and restored old leases do not consume capacity. The hard-crash fixture uses a simulated
process-termination `Error`, restores fresh ledgers/coordinator state, and verifies zero duplicate
Provider calls, duplicate task results, duplicate merges, lease leaks, and ghost running tasks.

Authority application remains single-writer. `ResearchEpochCommitter` recomputes the frozen anchor
before mutation; an anchor change rejects the batch. Existing Issues 001-011 gates still decide
whether individual mathematical effects are admissible.

## 12. Twenty-round diagnostic

The 20-round fixture uses five fake agents, four Research slots, one reserved Coordination slot,
80 Provider work items, a real schema-20 JSON checkpoint round trip at round 10, and the
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
EPOCH_HASH_BEFORE_RESTORE=1b1a062dea229c864d14b220f960fd7bc2c701565d379a038759fa57e4f3549b
EPOCH_HASH_AFTER_RESTORE=1b1a062dea229c864d14b220f960fd7bc2c701565d379a038759fa57e4f3549b
TASK_HASH_BEFORE_RESTORE=05545ac85f63cf44e3578f1491d1cf115e7ed1aea1262a42c42803d8e8595ba1
TASK_HASH_AFTER_RESTORE=05545ac85f63cf44e3578f1491d1cf115e7ed1aea1262a42c42803d8e8595ba1
RESULT_HASH_BEFORE_RESTORE=0ccd2ef9ebb2630735f092d04d8e9f959382235047201eb3ba7c7d16ddaeb570
RESULT_HASH_AFTER_RESTORE=0ccd2ef9ebb2630735f092d04d8e9f959382235047201eb3ba7c7d16ddaeb570
LEASE_HASH_BEFORE_RESTORE=6ba60e00b0c92728b77d7fbb13941da6aba228e55addb58a60f9b81f1a4d5c12
LEASE_HASH_AFTER_RESTORE=6ba60e00b0c92728b77d7fbb13941da6aba228e55addb58a60f9b81f1a4d5c12
TELEMETRY_HASH_BEFORE_RESTORE=b631ff1a9cb51fb5cf0caa575c8575c9edc967a28df64297da7a2bd6c73a11c2
TELEMETRY_HASH_AFTER_RESTORE=b631ff1a9cb51fb5cf0caa575c8575c9edc967a28df64297da7a2bd6c73a11c2
ROOT_HASH_CHANGES=0
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

Code-and-test diff by functional commit:

- Commit A: `88 files changed, 3066 insertions(+), 12 deletions(-)`.
- Commit B: `53 files changed, 3792 insertions(+), 122 deletions(-)`.
- Baseline through Commit B: `132 files changed, 6842 insertions(+), 118 deletions(-)`.

No target directory, logs, checkpoints, databases, caches, or generated verification reports are
included.

## 14. Issue 012 specialized tests

All tests use fake providers, deterministic delays/tickers, in-memory dependencies, Temporal's
test environment, or Docker-backed local PostgreSQL. They make no real DeepSeek or external
network call.

| Suite | Tests | Failures | Errors | Skipped | Result |
| --- | ---: | ---: | ---: | ---: | --- |
| Core expanded Issue 012 suite | 25 | 0 | 0 | 0 | PASS |
| Server/Provider/Temporal expanded Issue 012 suite | 22 | 0 | 0 | 0 | PASS |
| Desktop exact Issue 012 suite | 24 | 0 | 0 | 0 | PASS |

Coverage includes configuration, frozen identity, work conflicts, state transitions, monotonic
snapshots, atomic leases, fairness, role isolation, cooldown/failure isolation, actual global and
per-agent limits, lease-bound structured calls, ready-queue refill, all-settled behavior,
completion-order invariance, PostgreSQL contracts/fencing, Temporal children/replay, desktop stage
batches, atomicity, crash restore, v19 migration, protected authority, and 20 rounds.

## 15. Module and full verification

The module regression command completed with `2675` tests, zero failures, zero errors, and four
intentional skips across Contracts, Core, Server, and Desktop.

The final `./scripts/verify-all.ps1 -Offline` completed in `617.9 s` with:

| Module/suite | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| Contracts unit | 65 | 0 | 0 | 0 |
| Core unit | 1377 | 0 | 0 | 0 |
| Server unit | 917 | 0 | 0 | 3 |
| Desktop unit | 316 | 0 | 0 | 1 |
| Compatibility | 149 | 0 | 0 | 0 |
| PostgreSQL/Sandbox failsafe IT | 26 | 0 | 0 | 0 |
| **Total** | **2850** | **0** | **0** | **4** |

The Docker-backed integration run included `MathProofMeshApplicationIT`,
`JdbcMessageRepositoryIT`, `MemoryProofGraphPostgresIT`, `PersistencePostgresIT`,
`Phase17CheckpointOutboxPerformanceIT`, `ProviderCallPostgresIT`, and `SandboxSecurityIT`.
PostgreSQL applied Flyway V6 successfully. Temporal concurrency tests passed in the Server suite.

All unchanged release gates passed:

- Core branch coverage: `75.568696%` against the unchanged `75%` gate.
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
- Atomic credential leases and 4+1 capacity reservation: PASS.
- Sustained stage concurrency with completion-queue refill: PASS.
- All-settled deterministic batch merge: PASS.
- Completion-order invariance and straggler handling: PASS.
- Crash-safe task/result/lease restore: PASS.
- Desktop/Temporal deterministic parity: PASS.
- Schema 20 and Flyway V6 migration: PASS.
- Issues 001-011 regression and protected-file isolation: PASS.
- Full offline verification including Docker PostgreSQL: PASS.
- Issue 013 budget/token/stop-policy work: not started.

`ISSUE_012_STATUS=CLOSED`
