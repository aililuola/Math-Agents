# Issue 011: Run State Reconciliation

## 1. Scope and Git state

- Branch: `fix/011-run-state-reconciliation`
- Baseline: `0e245ed65a28f174b0b447840522e2505827f439`
- Baseline contains the completed Issue 010 implementation.
- Commit A: `e6522f2` (`fix(run-state): separate execution math usage and campaign status`)
- Commit B: `a674a54` (`fix(run-state): reconcile durable state and atomic report projections`)
- Commit C: `ddf3e37` (`fix(run-state): close authority usage and crash consistency gaps`)
- Desktop checkpoint schema: `18 -> 19`
- PostgreSQL migration: `V5__run_state_reconciliation.sql`
- Issue 012 was not started. Scheduling, API-key concurrency, budget, token limits, and stop-policy logic were not changed.

Issue 011 is limited to durable execution evidence, mathematical progress, provider usage,
campaign recoverability, report projections, deterministic reconciliation, and restore behavior.
It does not grant or revoke mathematical authority.

## 2. Production entry-point audit

The following real production paths previously selected, inferred, or wrote run state. They now
use `RunStateSnapshot` as the authority and treat result, metadata, report, activity, and legacy
database fields as projections.

| State surface | Production entry points | Reconciled behavior |
| --- | --- | --- |
| Desktop execution | `DesktopRunManager.start`, `resume`, `cancel`, `executeSolve`, `executeResume` | Each execution attempt is represented independently; failure does not erase mathematical or usage evidence. |
| Desktop result/failure | `DesktopRunManager.publishResult`, `publishFailure`, `updateLifecycle` | Authority is committed/reconciled before result and metadata projections. |
| Desktop repository | `RunRepository.writeMetadataProjection`, `writeResult`, `reconcileFailure`, `reconcileCancellation`, `summary`, `detail` | `structured/run_state.json` is read first; legacy files are migrated rather than heuristically merged on every read. |
| Live backend failure | `DesktopLiveRunExecutionBackend.execute` and resume path | Failure reconciliation reads the latest checkpoint and durable usage instead of returning empty routes, claims, steps, and usage. |
| Semantic checkpoint | `DesktopSolveCoordinator.persistUnchecked`, `restore`; `DesktopSolveCheckpoint.runStateAnchor` | Checkpoints hold only an authority anchor. They do not become a second Run State authority. |
| API solve/resume/status | `RunApiService.solve`, `resume`, `status`, `applyConfiguredResult`, `restoreStoredRun` | The in-memory map is a cache. Cache misses restore from the file authority; terminal resume makes no provider call. |
| API view | `RunStateApiProjection` and `RunApiModels.RunView` | Execution, math, usage, campaign, report, reconciliation, and terminal reason are projected separately. |
| Result/report/metadata | `RunResultProjectionService`, `RunReportProjectionService`, `DesktopMetadataProjectionService`, `ReportFunctions` | Projection failure changes only projection state, never mathematical authority. |
| File authority | `FileRunStateStore.load`, `compareAndSet`, `transitions` | File lock, optimistic version, temp write, file fsync, atomic move, directory fsync when supported, and journal fallback. |
| PostgreSQL authority | `JdbcRunStateStore.load`, `compareAndSet`, `transitions` | State, transition, legacy run projection, and outbox are committed in one transaction under lease/fencing/version checks. |
| Legacy restore | `LegacyRunStateMigrator.migrate` | Evidence precedence is checkpoint and durable artifacts first, then result/metadata/report/activity projections. No provider or computation call is made. |

Audited write surfaces include `desktop_run.json`, `run_result.json`, `run_report.md`,
`activity.jsonl`, `desktop-solve-state.json`, API `RunView`, legacy `run.status/current_stage`,
`run_state_snapshot`, and `run_state_transition`.

## 3. Pre-fix black-box evidence

These were behavioral baseline failures, not merely compilation failures caused by missing new
types. The tests exercise the old public/production behavior with legacy files and backends.

| Test | Baseline behavior observed |
| --- | --- |
| `DesktopFailedExecutionZerosDurableUsageBlackBoxTest` | A failed backend projected committed provider usage as zero. |
| `DesktopFailedExecutionErasesPartialMathBlackBoxTest` | Verified local claims and partial mathematical progress disappeared from the failure result. |
| `DesktopResultMetadataSplitBrainBlackBoxTest` | `run_result.json` could say failed while `desktop_run.json` still said running. |
| `DesktopDeadProcessRunningProjectionBlackBoxTest` | Dead-process metadata and result/checkpoint evidence had no canonical state to reconcile them. |
| `DesktopFailedExecutionRecoverabilityBlackBoxTest` | A failed execution with a non-terminal checkpoint had no explicit `RECOVERABLE` campaign state. |
| `DesktopActivityTailCannotOverrideRunStateBlackBoxTest` | A trailing running activity event could remain the effective UI interpretation after failure. |
| `ServerRunStateRestartLossBlackBoxTest` | A fresh `RunApiService` instance could not load status from the prior process and returned not found. |
| `DesktopOriginalFailureVectorReconciliationTest` | The 215-call failure vector lost usage/math/recoverability under the legacy single-status projection. |

The independent follow-up audit was also reproduced before Commit C. The six new Core cases all
failed: the 23-call aggregate was reduced to 20, incomparable aggregate evidence was accepted as
`RECORDED`, final proof/review evidence and the proof-graph hash disappeared, and verified/refuted
claim counts fell to zero. Of the first six Server cases, five failed behaviorally: a backend could
choose the authority frontier, manufacture `VERIFIED`, reduce usage from 7 calls to 3,
`status=completed` implied mathematical verification, and two refuted claims were reported as
zero. The initial file-crash fixture had one setup error because its bare test mapper lacked the
Java time module; after correcting the fixture, the production crash window was exercised through
the real `FileRunStateStore` failure injector.

## 4. State model

The new core package `io.github.aililuola.mathproofmesh.runstate` separates five dimensions:

- `RunExecutionStatus`: `QUEUED`, `RUNNING`, `SUCCEEDED`, `FAILED`, `INTERRUPTED`, `CANCELLED`.
- `RunMathematicalStatus`: `NOT_STARTED`, `PARTIAL_UNVERIFIED`, `CANDIDATE_UNVERIFIED`, `VERIFIED`, `AUTHORITY_CONFLICT`.
- `RunUsageStatus`: `NOT_RECORDED`, `PARTIAL_RECORDED`, `RECORDED`, `CONFLICT`.
- `RunCampaignStatus`: `QUEUED`, `ACTIVE`, `RECOVERABLE`, `TERMINAL`, `ARCHIVED`.
- `RunReportStatus`: `ABSENT`, `PARTIAL`, `FINAL`, `STALE`, `PROJECTION_FAILED`.

`RunAuthoritySnapshot` owns execution, math, usage, campaign, terminal reason, progress, checkpoint,
proof-graph identity, sequence, attempt, and version. `RunProjectionSnapshot` owns result, desktop
metadata, report, activity sequence, projection errors, and their hashes. `RunStateSnapshot`
binds both layers, reconciliation status, typed conflicts, stable hash, and timestamp.

`RunStateReconciler` deterministically derives the five dimensions. In particular,
`FAILED`, `INTERRUPTED`, or `CANCELLED` execution with a non-terminal semantic checkpoint remains
`RECOVERABLE`; `SUCCEEDED` without final mathematical verification is also not silently terminal.
Math and usage are monotonic unless a typed authority conflict is raised.

`RunUsageReconciler` uses exact request identity and artifact provenance. It accepts an aggregate
only when it is a coordinate-wise monotonic extension of every earlier aggregate; incomparable
totals become `CONFLICT_QUARANTINED` rather than being selected by source priority. Request-level
evidence remains deduplicated by provider request ID.
`RunExecutionAttemptLedger` preserves separate attempts, while `RunStateTransitionLedger` records
stable, exactly-once state transitions.

`RunMathematicalProgressReconciler` now merges concrete verified/refuted Claim identities,
preserves final-proof and final-review hashes, retains an existing proof-graph hash when evidence
is absent, and raises typed authority conflicts when the same frontier supplies incompatible
mathematical evidence. The transition policy validates these concrete invariants in addition to
the summary math-status rank. Claim lifecycle recovery reads the real `entries[*].state` field,
with `status` retained only as a legacy compatibility fallback.

## 5. Durable stores and projections

### 5.1 File and desktop

`FileRunStateStore` writes one canonical `structured/run_state_commit.json` envelope containing
the complete `RunStateSnapshot` and transition frontier. `run_state.json` and
`run_state_transitions.json` are repairable legacy projections, not separate authorities. A crash
after either projection write deterministically rolls them forward from the committed envelope;
an uncommitted projection is rolled back on load. `RunRepository.summary/detail` prefer this
authority. If it is missing, `LegacyRunStateMigrator`
constructs a single canonical state from v18/legacy evidence and writes projections from it.

Projection-only updates use an independent monotonic `projectionVersion` and compare both the
expected state hash and projection version. The file and JDBC stores therefore reject two writers
that start from the same authority version instead of allowing the later projection to overwrite
the earlier one.

Desktop schema 19 adds only `RunStateAnchor(authoritySequence, authorityHash,
executionAttemptId)` to the semantic checkpoint. A v18 checkpoint receives an empty anchor and is
reconciled without changing old migrations or mathematical state.

### 5.2 API and reports

`RunApiService` persists the immutable solve request and canonical state. A process restart can
restore `status` and a recoverable `resume`; terminal `resume` performs no provider call. The API
returns all five state dimensions, reconciliation status, terminal reason, recoverability,
authority hash/sequence, provider calls, and logical steps.

Result, desktop metadata, and report writers operate as projections. A projection error records
`PROJECTION_FAILED` while retaining a successful/verified authority state. Stale projections are
repairable from the authority snapshot.

### 5.3 PostgreSQL V5

Flyway V5 creates `run_state_snapshot` and `run_state_transition` plus the transition sequence
index. `JdbcRunStateStore` commits canonical state, transition, legacy run fields, and outbox in a
single transaction and applies existing lease/fencing/optimistic-version rules. The Docker-backed
test ran against PostgreSQL 18.4 and successfully applied all five migrations.

## 6. Modified files and purpose

Commit A adds the core Run State domain under
`mathproofmesh-core/src/main/java/io/github/aililuola/mathproofmesh/runstate/`: status enums,
authority/projection snapshots, evidence bundles, hashes, reconciliation, usage reconciliation,
attempt ledger, transition ledger/policy, store interface, failure points, and anchors. Its core
tests cover dimension separation, derivation, monotonicity, conflicts, duplicates, snapshots, and
problem-neutral dependencies.

Commit B changes or adds the following production groups:

- Core `runstate`: serialization-stable attempt/transition hashes, constant-time hash comparison,
  recoverability rules, and branch coverage.
- Desktop: `DesktopRunManager`, `DesktopLiveRunExecutionBackend`, `RunRepository`,
  `LegacyRunStateEvidence`, `LegacyRunStateMigrator`, `DesktopSolveCheckpoint`, and only the Run
  State anchor/emission area of `DesktopSolveCoordinator`.
- Server API: `RunExecutionBackend`, `RunApiModels`, `RunApiService`, `RunStateApiProjection`,
  `RunStateReconciliationService`, `RunReportProjectionService`, and `ReportFunctions`.
- Server persistence/projection: `FileRunStateStore`, `JdbcRunStateStore`,
  `AtomicRunProjectionWriter`, `RunResultProjectionService`, `DesktopMetadataProjectionService`,
  `RunProjectionReceipt`, and Flyway V5.
- Tests: 14 Core Issue 011 assertions, 10 Server Issue 011 tests, 17 Desktop Issue 011 production
  and black-box tests, plus coverage-only branch tests. Existing PostgreSQL migration assertions
  were updated for V5 and its two tables.

Code-only diff for Commits A and B: `102 files changed, 5142 insertions(+), 135 deletions(-)`.
Commit C adds the narrowly scoped audit closure: `42 files changed, 1935 insertions(+), 120
deletions(-)`.
No target directories, logs, databases, checkpoints, caches, or generated verification reports
are included.

## 7. Issue 011 specialized tests

Final explicit commands and results:

| Suite | Tests | Failures | Errors | Skipped | Result |
| --- | ---: | ---: | ---: | ---: | --- |
| Core exact command | 14 | 0 | 0 | 0 | PASS |
| Server exact command | 10 | 0 | 0 | 0 | PASS |
| Desktop exact command | 17 | 0 | 0 | 0 | PASS |
| Commit C Core gap suite | 9 | 0 | 0 | 0 | PASS |
| Commit C Server gap suite | 13 | 0 | 0 | 0 | PASS |
| Commit C Desktop/protected suite | 2 | 0 | 0 | 0 | PASS |

The Server suite used the local Docker Desktop/Testcontainers path, not a mock database.
The specialized tests make no real DeepSeek or external network call.

## 8. Original failure vector

```text
ORIGINAL FAILURE VECTOR DIAGNOSTIC
PROVIDER_CALLS=215
INPUT_TOKENS=1464085
OUTPUT_TOKENS=1984941
TOTAL_TOKENS=3449026
EXECUTION_STATUS=FAILED
MATH_STATUS=PARTIAL_UNVERIFIED
USAGE_STATUS=RECORDED
CAMPAIGN_STATUS=RECOVERABLE
REPORT_STATUS=PARTIAL
USAGE_ZEROING_EVENTS=0
PARTIAL_MATH_STATE_LOSSES=0
RECOVERABILITY_LOSSES=0
RESULT=PASS
```

## 9. Twenty-round restore diagnostic

All counts and hashes below were emitted by `DesktopRunStateMultiRoundRestoreTest` from the real
file authority and transition ledger. Round 10 destroys the store instance and reloads it.

```text
RUN STATE RECONCILIATION DIAGNOSTIC
ROUNDS=20
RESTORE_ROUND=10
EXECUTION_ATTEMPTS=3
EXECUTION_FAILURES=1
EXECUTION_INTERRUPTS=1
EXECUTION_SUCCESSES=1
PARTIAL_UNVERIFIED_STATES=12
CANDIDATE_UNVERIFIED_STATES=4
VERIFIED_STATES=4
MATH_STATUS_REGRESSIONS=0
RECOVERABLE_STATES=2
TERMINAL_STATES=1
FALSE_TERMINAL_STATES=0
FALSE_RECOVERABLE_STATES=0
PROVIDER_CALLS_EXPECTED=20
PROVIDER_CALLS_RECONCILED=20
USAGE_ZEROING_EVENTS=0
USAGE_DOUBLE_COUNTS=0
POST_RESTORE_USAGE_RESETS=0
REPORT_PROJECTION_FAILURES=1
PRE_RESTORE_STATE_HASH=094674fa35b839c52d9928ff100d15661fbdd9d7ae576b3e5df1086b5179594e
POST_RESTORE_STATE_HASH=094674fa35b839c52d9928ff100d15661fbdd9d7ae576b3e5df1086b5179594e
PRE_RESTORE_USAGE_HASH=d65072f4eac5d46e892d43f71bc4a6a16eaa644c10876f18aa0fd9e87f2784f9
POST_RESTORE_USAGE_HASH=d65072f4eac5d46e892d43f71bc4a6a16eaa644c10876f18aa0fd9e87f2784f9
PRE_RESTORE_TRANSITION_HASH=07394c8d685939e79471d743c39659a026cb80f6f5dfa7b19ba46df196be68bd
POST_RESTORE_TRANSITION_HASH=07394c8d685939e79471d743c39659a026cb80f6f5dfa7b19ba46df196be68bd
POST_RESTART_RUN_STATE_LOSSES=0
POST_RESTART_DUPLICATE_TRANSITIONS=0
RESULT=PASS
```

Separate production tests prove report failure does not change authority, stale report repair is
deterministic, activity tails cannot override canonical state, hard-crash recovery loses neither
usage nor math progress, and terminal resume makes zero provider calls.

## 10. Regression and release gates

The complete Core/Server/Desktop reactor passed. The final offline release gate executed every
current test, including all explicit Issue 001-010 regression classes and Issue 011 tests:

| Module/suite | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| Contracts unit | 65 | 0 | 0 | 0 |
| Core unit | 1350 | 0 | 0 | 0 |
| Server unit | 895 | 0 | 0 | 3 |
| Server integration | 26 | 0 | 0 | 0 |
| Desktop unit | 280 | 0 | 0 | 1 |
| Compatibility unit | 149 | 0 | 0 | 0 |
| Total | 2765 | 0 | 0 | 4 conditional |

The five Docker-backed PostgreSQL suites passed, including `PersistencePostgresIT`,
`MemoryProofGraphPostgresIT`, and the new Run State atomicity path. No integration test was
skipped because Docker was available.

Release gates:

- `FULL VERIFICATION: PASS`
- Core line coverage: `90.130135%`; Core branch coverage: `75.102041%` (gate unchanged).
- Contracts adjusted line: `91.628382%`; adjusted branch: `85.397898%`.
- Server line: `87.295534%`; Desktop line: `79.793294%`.
- SpotBugs/FindSecBugs: PASS, including constant-time authority hash comparisons.
- OWASP/dependency/security/secret scan: PASS.
- License gate: PASS.
- Source immutability: PASS.
- Python Sidecar performance gate: PASS; no threshold was changed.

The direct baseline diff and `RunStateProtectedAuthorityTest` both confirm zero changes to the 22
protected Issue 001-010 authority files. Therefore:

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
PROTECTED_FILES_NO_DIFF=PASS
```

## 11. Acceptance conclusion

```text
ISSUE 011 RUN STATE RECONCILIATION DIAGNOSTIC
================================================================
STATE_DIMENSION_SEPARATION=PASS
COMPLETED_WITHOUT_PROOF_VERIFIED=0
BACKEND_RUN_STATE_RECONCILER_BYPASSES=0
EXECUTION_STATUS_CONFLICTS=0
MATH_STATUS_REGRESSIONS=0
MATHEMATICAL_PROGRESS_REGRESSIONS=0
VERIFIED_CLAIM_COUNT_LOSSES=0
REFUTED_CLAIM_COUNT_LOSSES=0
REFUTED_CLAIM_FALSE_COUNTS=0
PROOF_GRAPH_HASH_LOSSES=0
USAGE_ZEROING_EVENTS=0
EARLY_FAILURE_PROVIDER_CALL_LOSSES=0
POST_CHECKPOINT_PROVIDER_CALL_LOSSES=0
POST_CHECKPOINT_TOKEN_LOSSES=0
CAMPAIGN_RECOVERABILITY_ERRORS=0
REPORT_AUTHORITY_ESCALATIONS=0
RUN_RESULT_METADATA_SPLIT_BRAINS=0
API_DESKTOP_STATE_MISMATCHES=0
ACTIVITY_TAIL_AUTHORITY_OVERRIDES=0
PROVIDER_CALLS_EXPECTED=215
PROVIDER_CALLS_RECONCILED=215
USAGE_DOUBLE_COUNTS=0
USAGE_CONFLICTS_SILENTLY_ACCEPTED=0
PARTIAL_MATH_PROGRESS_LOSSES=0
VERIFIED_LOCAL_CLAIM_LOSSES=0
PROOF_GRAPH_HASH_REGRESSIONS=0
POST_RESTART_STATUS_NOT_FOUND=0
POST_RESTART_DUPLICATE_PROVIDER_CALLS=0
POST_RESTORE_STATE_TRANSITION_REPLAYS=0
REPORT_PROJECTION_FAILURE_AUTHORITY_CHANGES=0
STALE_PROJECTIONS_AFTER_REPAIR=0
AUTHORITY_WITHOUT_TRANSITION=0
TRANSITION_WITHOUT_AUTHORITY=0
DUPLICATE_TRANSITIONS=0
STALE_PROJECTION_OVERWRITES=0
ORIGINAL_FAILURE_EXECUTION_STATUS=FAILED
ORIGINAL_FAILURE_MATH_STATUS=PARTIAL_UNVERIFIED
ORIGINAL_FAILURE_USAGE_STATUS=RECORDED
ORIGINAL_FAILURE_CAMPAIGN_STATUS=RECOVERABLE
ORIGINAL_FAILURE_PROVIDER_CALLS=215
ORIGINAL_FAILURE_TOTAL_TOKENS=3449026
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
COMPUTATION_HASH_CHANGES=0
PROTECTED_FILES_NO_DIFF=PASS
FULL_VERIFICATION=PASS
ISSUE_011_STATUS=CLOSED
================================================================
```

The hashes for Issues 001-010 are protected by baseline no-diff rather than being rewritten by
Run State. Issue 011 records their observable identities only. No production authority from the
first ten issues was modified, and Issue 012 has not begun.
