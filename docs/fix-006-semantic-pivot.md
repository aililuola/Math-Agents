# Issue 006: Semantic Pivot

## 1. Status and Git provenance

- Status: `CLOSED` after the final gates recorded below pass.
- Branch: `fix/006-semantic-pivot`
- Baseline branch: `java`
- Baseline commit: `5bb2460e7fe7dd2a33c72fb32eaf3f0dd0ab1237`
- Baseline checkpoint schema: `11`
- Resulting checkpoint schema: `12`
- Implementation commit: `14303bcbfd5f5e80395ccb5d47807505d4f9a643`.
- Claim materialization and checkpoint atomicity follow-up commit:
  `9e77ad374d2e4313354e330b8245fc2877f2fea2`.
- Process-crash-safe checkpoint follow-up commit:
  `c73803297396057c52d4b6ef193ed00240fee9d7`.
- Issue 005 prerequisite: `CLOSED` at the baseline.

The work remained on `fix/006-semantic-pivot`. It did not modify `main` or commit directly
to `java`.

## 2. Production entry-point audit

The pre-change production path had the following state-selection and mutation entry points:

| Surface | Production entry | Pre-change mutation |
|---|---|---|
| Route deepen | `deepenRoute` | `revisionStrategy` then `prepareRouteRevision` |
| Failed-route revision | `reviseFailedRoute` | `revisionStrategy` then `prepareRouteRevision` |
| Meta pivot intent | `PersistentMetaStrategist.decide` | decision converted by `MetaDirectiveController.fromDecision` |
| Meta proposal task | `MetaDirectiveController.execute` | representation/reverse/auxiliary inspiration task |
| Meta pivot lifecycle | `runInspiration` | non-empty material references could be treated as applied |
| Strategy root archive | `generateAndAdmitStrategies`, `materializeInspiration` | `StrategyArchive.archive` |
| Strategy child lineage | `prepareRouteRevision`, legacy restore | `StrategyArchive.registerChild` |
| Active strategy replacement | `prepareRouteRevision` | direct active-strategy replacement |
| Route obligation | `addRoute` | controlled writer |
| Blueprint obligations | `addBlueprintObligations` | controlled writer and canonicalization |
| Computation obligation | `bindComputationTargetObligation` | controlled writer |
| Inspiration obligation | `materializeInspiration` | preview then controlled writer |
| Restore/replay | `restore`, strategy restore, route projection restore | graph, archive, route, and task projections |

The concrete defects were:

1. `revisionStrategy` appended `Repair only the first invalid bridge` to old prose without
   changing the mathematical object, target, direction, assumptions, claims, or obligations.
2. A materialized meta proposal could be counted as a pivot without a typed semantic delta.
3. The Greedy GCD counterexample path did not replace the refuted prefix object and target.

## 3. Pre-fix behavioral evidence

The three black-box tests were written before production changes and run against the baseline:

```text
REVISION_ATTEMPTS=3
CORE_IDEA_APPEND_COUNT=3
STRUCTURAL_DELTA_COUNT=0
EXPECTED_PIVOT_REJECTIONS=3
ACTUAL_PIVOT_REJECTIONS=0

MATERIAL_PROPOSALS=1
SEMANTIC_DELTAS=0
PIVOTS_MARKED_APPLIED=1
EXPECTED_PIVOTS_MARKED_APPLIED=0

EXACT_COUNTEREXAMPLES=1
EXPECTED_OBJECT_REPLACEMENTS=1
ACTUAL_OBJECT_REPLACEMENTS=0
EXPECTED_TARGET_REFORMULATIONS=1
ACTUAL_TARGET_REFORMULATIONS=0
```

These are behavioral failures from the old production path, not compilation failures caused
by new types.

## 4. Implemented contract and lifecycle

The production flow is now:

```text
Pivot intent
-> SemanticPivotProposal from the proposer
-> server-owned PivotDelta compilation
-> deterministic structural and authority audit
-> isolated independent SemanticPivotReviewBatch
-> existing Negative Knowledge, canonicalization, focused-recovery, and capacity gates
-> staged SemanticPivotApplyPlan
-> atomic graph/archive/route/task mutation
-> SemanticPivotApplyReceipt
-> durable SemanticPivotLedger
-> gain evaluation
```

`SemanticPivotProposal`, `SemanticPivotReviewDecision`, and
`SemanticPivotReviewBatch` are bounded contracts. Provider-supplied pivot IDs and structural
hashes are rejected. `SemanticPivotCompiler` resolves obstruction IDs only through the
trusted server map and converts enum strings fail-closed.

`PivotDelta` explicitly describes transformation kinds, obstruction bindings, mathematical
object changes, direction changes, assumption changes, Claim usage changes, obligation
changes, and the proposed Strategy epoch. The server computes both `pivotId` and
`structuralDeltaHash`.

`SemanticPivotDeterministicAuditor` rejects empty or prose-only deltas, root/problem/route/
strategy mismatches, unknown or unlocated obstructions, unauthorized Claim changes,
unauthorized old-obligation changes, permanent-negative conflicts, focused-recovery binding
mismatches, and capacity/quota failures.

The independent reviewer must differ from the proposer, return exactly one decision for the
server pivot ID, satisfy every bounded authority dimension, and meet the confidence threshold.
The reviewer cannot change IDs, authority, graph state, Claim truth, or the root goal.

## 5. Local Repair and Semantic Pivot separation

`StrategyRevisionKind` separates `LOCAL_REPAIR` from `SEMANTIC_PIVOT`.

- Local Repair keeps the mathematical object, high-level target, direction, and `coreIdea`.
  It changes the focused bottleneck/bridge and produces a `LocalRepairApplyReceipt`.
- Local Repair never enters `SemanticPivotLedger` and never increments the semantic-pivot
  count.
- Semantic Pivot requires a non-empty typed `PivotDelta` and unequal ID-insensitive old/new
  structural signatures.
- The old Strategy remains archived; the new Strategy is a child epoch. The old Attempt and
  revision history remain auditable.

The former unbounded `oldCoreIdea + " Repair only ..."` chain is no longer used.

## 6. Authority boundaries and atomic apply

Pivot never directly:

- edits the immutable Root Goal or its hash;
- creates a verified Claim or promotes a Fact;
- registers permanent Negative Knowledge;
- closes or refutes an old obligation merely because it leaves active Strategy focus;
- changes an old Claim's mathematical truth state;
- bypasses selected-family Focused Recovery, capacity, quota, canonicalization, or the
  controlled proof-graph writer.

`DesktopSolveCoordinator.applySemanticPivotAtomically` snapshots every mutated owner before
the staged apply. Injected failures after staging, Strategy epoch creation, route switching,
canonicalization, and pending-task creation restore all owners before rethrowing. A retry uses
the durable server pivot identity and applies exactly once.

Atomicity diagnostics:

```text
PARTIAL_PIVOT_RECORDS=0
PARTIAL_STRATEGY_EPOCHS=0
PARTIAL_ROUTE_SWITCHES=0
PARTIAL_OBLIGATION_WRITES=0
PARTIAL_CANONICAL_WRITES=0
TASK_LEASE_LEAKS=0
PENDING_TASK_LEAKS=0
```

## 7. Greedy GCD semantic pivot

The trusted exact-counterexample path now performs the requested mathematical transition:

- retires prefix minimal-hitting-set stability only from active Strategy use;
- retains the already verified hitting-set equivalence as a Fact;
- replaces the active prefix object with a global inclusion-minimal support family;
- reformulates the active target as a global large-prime support reduction;
- adds the load-bearing large-prime reduction obligation through all existing gates.

Production diagnostics:

```text
OBJECT_REPLACEMENTS=1
TARGET_REFORMULATIONS=1
RETAINED_VERIFIED_CLAIMS=1
NEW_OBLIGATIONS=1
OLD_CORE_IDEA_APPEND_COUNT=0
```

## 8. Structural signatures and gain

The fixture used by the deterministic tests produced these stable hashes:

```text
OLD_STRUCTURAL_SIGNATURE_HASH=42085d0034afd078e617abf2599ff1515a60009b44fa9a0816916e66b7c9ccd8
NEW_STRUCTURAL_SIGNATURE_HASH=d2d9d7accaaa9640e3ea2220a248ee5ff50a3a63077614114ab0b9924f3e3c7f
```

Strategy ID, title, rationale, and prose are excluded as proof of a mathematical change.
Gain evaluation counts only verified Claims, exact refutations, closed canonical targets, or a
real global canonical-debt decrease. A materialized pivot with none of those remains
`MATERIALIZED_NO_GAIN`; it does not rewrite old mathematical truth.

## 9. Checkpoint and restore

`DesktopSolveCheckpoint.CURRENT_SCHEMA_VERSION` advanced from `11` to `12` and persists:

- `SemanticPivotSnapshot`;
- route active pivot and pivot history;
- active Strategy epoch;
- retired active-Claim and retired Strategy-focus obligation projections;
- active mathematical objects and direction signature.

The v11 -> v12 migration defaults the Pivot ledger to empty, preserves legacy revisions as
legacy revisions, preserves Strategy/route/task and all issue 001-005 authority state, performs
no Provider call, and does not infer old string revisions as pivots. A second v12 save/restore
retains the same projection.

Restore order places Root Goal and existing authority owners before Strategy archive,
Semantic Pivot ledger, route projections, and pending work. Applied pivots do not call the
Provider again; rejected pivots remain rejected; a staged reviewed pivot resumes at its apply
frontier.

The 20-round checkpoint at round 10 reported:

```text
PIVOT_LEDGER_HASH_BEFORE_RESTORE=d67c4944a8b1967316ef1a0c2513f26163f870ea8854a485572b5b7255fac19a
PIVOT_LEDGER_HASH_AFTER_RESTORE=d67c4944a8b1967316ef1a0c2513f26163f870ea8854a485572b5b7255fac19a
```

## 10. Twenty-round diagnostic

The test uses the real coordinator path. Rounds 0-4 reject text-only proposals. Rounds 5-9
reject root-hash, unknown-obstruction, verified-Claim retirement, old-obligation authority,
and permanent-negative violations. Round 10 serializes and restores a real checkpoint.
Rounds 10-14 apply valid pivots. Rounds 15-19 apply Local Repairs without counting them as
pivots.

```text
SEMANTIC PIVOT DIAGNOSTIC
ROUNDS=20
RESTORE_ROUND=10
PIVOT_CANDIDATES=15
TEXT_ONLY_PIVOT_CANDIDATES=5
TEXT_ONLY_PIVOT_REJECTIONS=5
AUTHORITY_VIOLATION_CANDIDATES=5
AUTHORITY_VIOLATION_REJECTIONS=5
VALID_PIVOT_CANDIDATES=5
VALID_PIVOTS_APPLIED=5
LOCAL_REPAIR_ATTEMPTS=5
LOCAL_REPAIRS_APPLIED=5
LOCAL_REPAIRS_COUNTED_AS_PIVOTS=0
OBJECT_CHANGE_EVENTS=5
TARGET_REFORMULATIONS=5
NEW_OBLIGATIONS_ADMITTED=5
RETAINED_VERIFIED_CLAIMS=5
EMPTY_DELTA_APPLIES=0
TEXT_ONLY_DELTA_APPLIES=0
CORE_IDEA_APPEND_LEAKS=0
OLD_STRATEGY_DELETIONS=0
OLD_ATTEMPT_HISTORY_LOSSES=0
OLD_TARGET_MATH_STATUS_CHANGES=0
OLD_TARGET_AUTOMATIC_CLOSURES=0
OLD_TARGET_AUTOMATIC_REFUTATIONS=0
PARTIAL_PIVOT_WRITES=0
DUPLICATE_PIVOT_APPLIES=0
DUPLICATE_NEW_OBLIGATIONS=0
DUPLICATE_PIVOT_TASKS=0
POST_RESTORE_PIVOT_LOSSES=0
POST_RESTORE_PIVOT_STATUS_CHANGES=0
POST_RESTORE_DUPLICATE_APPLIES=0
POST_RESTORE_DUPLICATE_PROVIDER_CALLS=0
GLOBAL_CANONICAL_DEBT_FALSE_DECREASES=0
FOCUSED_RECOVERY_BINDING_BYPASSES=0
CAPACITY_OR_QUOTA_BYPASSES=0
DIRECT_FACT_PROMOTIONS=0
DIRECT_CLAIM_VERIFICATIONS=0
DIRECT_NEGATIVE_REGISTRATIONS=0
MAIN_GOAL_CLOSURES=0
ROOT_HASH_CHANGES=0
NEGATIVE_REGISTRY_HASH_CHANGES=0
ATTEMPT_ARTIFACT_LEDGER_UNAUTHORIZED_CHANGES=0
CLAIM_LIFECYCLE_UNAUTHORIZED_CHANGES=0
RESEARCH_CHECKPOINT_LEDGER_HASH_CHANGES=0
CANONICALIZATION_REGISTRY_UNAUTHORIZED_CHANGES=0
CONVERGENCE_CONTROL_UNAUTHORIZED_CHANGES=0
RESULT=PASS
```

The Focused Recovery production boundary test independently produced:

```text
FOCUSED_RECOVERY_BINDING_BYPASSES=0
CAPACITY_OR_QUOTA_BYPASSES=0
```

## 11. Files and purpose

### Contracts

- `SemanticPivotProposal.java`: bounded, non-authoritative proposer draft.
- `SemanticPivotReviewDecision.java`: one independent review decision.
- `SemanticPivotReviewBatch.java`: isolated review batch and reviewer identity.

### Core proof control

- `Pivot*`, `MathematicalObjectChange`, and `StrategyRevisionKind`: typed delta,
  structural signature, authority, action, and status vocabulary.
- `SemanticPivotCompiler`, `SemanticPivotDeterministicAuditor`, and
  `SemanticPivotReviewValidator`: compilation and fail-closed gates.
- `SemanticPivotApplyPlan`, `SemanticPivotApplyReceipt`, `SemanticPivotController`,
  `SemanticPivotLedger`, `SemanticPivotRecord`, `SemanticPivotSnapshot`, and audit event:
  atomic, monotonic, restore-safe lifecycle.
- `LocalRepairPlan` and `LocalRepairApplyReceipt`: non-pivot local repair path.
- `MetaPivotController`, `ProofControlFacade`, `ProofControlModels`, and
  `StrategyArchive`: receipt-backed effect evaluation and Strategy epoch lineage.

### Server and desktop

- `PromptCatalog.java`: only the `semantic_pivot_proposal` and
  `semantic_pivot_review` stages were added.
- `DesktopSolveCheckpoint.java`: schema 12 Pivot state and v11 migration defaults.
- `DesktopSolveCoordinator.java`: production proposal/review/gate/apply/restore integration,
  Local Repair split, Greedy GCD transition, and rollback.
- `SemanticPivotFailurePoint.java`: test-only failure injection vocabulary used by the
  coordinator's package-private hook.
- Existing desktop compatibility tests were updated only for schema 12 and the new required
  fake Provider response.

### Tests

- Core tests cover structural identity, every typed delta, obstruction and authority
  boundaries, no-op rejection, independent review, apply-plan gates, ledger illegal
  transitions, atomic controller rollback, snapshots, Local Repair, and Strategy lineage.
- Server tests cover both contracts, both prompts, and proposer/reviewer isolation.
- Desktop tests cover the three pre-fix black boxes, real Provider flow, text-only rejection,
  all production gates, atomicity, exactly-once restore, retained Claim visibility, old-target
  and debt boundaries, Greedy GCD, 20 rounds, v11 migration, and the no-append architecture.

## 12. Verification results

Issue 006 specialized tests:

| Suite | Tests | Failures | Errors | Skipped | Result |
|---|---:|---:|---:|---:|---|
| Core Pivot and Checkpoint suite | 48 | 0 | 0 | 0 | PASS |
| Server Pivot suite | 4 | 0 | 0 | 0 | PASS |
| Desktop production suite | 23 | 0 | 0 | 0 | PASS |

Explicit issue 001-005 regression suites:

| Protected issue | Contracts | Core | Server | Desktop | Result |
|---|---:|---:|---:|---:|---|
| 001 Exact Root Goal | 0 | 14 | 0 | 1 | PASS |
| 002 Permanent Negative Knowledge | 0 | 22 | 0 | 11 | PASS |
| 003 Claim/Attempt separation | 0 | 13 | 0 | 7 | PASS |
| 004 Research Checkpoints | 4 | 15 | 3 | 9 | PASS |
| 005 Canonicalization and convergence | 1 | 64 | 1 | 30 | PASS |

The separately requested issue 004/005 production-chain mini regressions also passed. No old
assertion was removed or weakened.

Final module regression:

| Module | Tests | Failures | Errors | Skipped | Result |
|---|---:|---:|---:|---:|---|
| Contracts | 48 | 0 | 0 | 0 | PASS |
| Core | 1068 | 0 | 0 | 0 | PASS |
| Server unit | 847 | 0 | 0 | 3 | PASS |
| Server integration | 26 | 0 | 0 | 0 | PASS |
| Desktop | 136 | 0 | 0 | 1 | PASS |
| Compatibility | 149 | 0 | 0 | 0 | PASS |

The final `verify-all.ps1 -Offline` run completed through the project's expected source layout
and reported `FULL VERIFICATION: PASS`. Docker-backed PostgreSQL integration tests were not
skipped:

| PostgreSQL integration suite | Tests | Result |
|---|---:|---|
| `JdbcMessageRepositoryIT` | 4 | PASS |
| `MemoryProofGraphPostgresIT` | 4 | PASS |
| `PersistencePostgresIT` | 9 | PASS |
| `Phase17CheckpointOutboxPerformanceIT` | 1 | PASS |
| `ProviderCallPostgresIT` | 3 | PASS |

Final gates:

```text
MAVEN_CLEAN_VERIFY=PASS
POSTGRESQL_INTEGRATION_TESTS=PASS
DEPENDENCY_CHECK_PATH_NORMALIZATION=PASS
PHASE_17_COVERAGE=PASS
PHASE_17_SECURITY=PASS
SOURCE_IMMUTABILITY=PASS
SOURCE_FILES=401
SOURCE_MANIFEST_SHA256=9f3def2bec8cea99d0a18b51fbb5fa8ce53f44a24ce869f6495cac809b7e3770
LICENSE_COMPONENTS=111
MISSING_OR_UNREVIEWED_LICENSES=0
SPOTBUGS_OR_SECURITY_BUGS=0
FULL_VERIFICATION=PASS
```

The new fail-closed boundary tests keep core aggregate branch coverage above the unchanged
75% gate. The final clean verification measured `75.305335%` core branch coverage. No Python
Sidecar performance or coverage/security/license threshold was changed.

Implementation diff stat:

```text
initial_implementation=91 files/8778 insertions/43 deletions
claim_and_checkpoint_follow_up=27 files/1019 insertions/17 deletions
process_crash_follow_up=6 files/239 insertions/8 deletions
```

## 13. Protected files and scope

Relative to baseline `5bb2460e7fe7dd2a33c72fb32eaf3f0dd0ab1237`:

```text
PROTECTED_FILE_COUNT=37
PROTECTED_DIFF_COUNT=0
PROTECTED_FILES_NO_DIFF=PASS
```

No issue 001 Exact Root Goal, issue 002 Negative Knowledge, issue 003 Claim/Attempt,
issue 004 Research Checkpoint, or issue 005 canonicalization/convergence authority file was
modified. Existing APIs are called rather than reimplemented.

No Provider selection, API-key routing, concurrency, Temporal scheduling, token/budget,
billing, Python Sidecar performance threshold, or issue 007-013 implementation was changed.
Issue 007 has not started.

## 14. Final acceptance diagnostic

```text
ISSUE 006 SEMANTIC PIVOT DIAGNOSTIC
================================================================
REVISION_STRING_APPEND_RESULT=PASS
CORE_IDEA_APPEND_LEAKS=0
TEXT_ONLY_PIVOT_REJECTIONS=5
AUTHORITY_VIOLATION_REJECTIONS=5
VALID_PIVOTS_APPLIED=5
LOCAL_REPAIRS_APPLIED=5
LOCAL_REPAIRS_COUNTED_AS_PIVOTS=0
OBJECT_CHANGE_EVENTS=5
TARGET_REFORMULATIONS=5
NEW_OBLIGATIONS_ADMITTED=5
RETAINED_VERIFIED_CLAIMS=5
EMPTY_DELTA_APPLIES=0
TEXT_ONLY_DELTA_APPLIES=0
PARTIAL_PIVOT_WRITES=0
DUPLICATE_PIVOT_APPLIES=0
OLD_STRATEGY_DELETIONS=0
OLD_ATTEMPT_HISTORY_LOSSES=0
OLD_TARGET_AUTOMATIC_CLOSURES=0
OLD_TARGET_AUTOMATIC_REFUTATIONS=0
GLOBAL_CANONICAL_DEBT_FALSE_DECREASES=0
POST_RESTORE_PIVOT_LOSSES=0
POST_RESTORE_DUPLICATE_APPLIES=0
POST_RESTORE_DUPLICATE_PROVIDER_CALLS=0
ROOT_HASH_CHANGES=0
NEGATIVE_REGISTRY_HASH_CHANGES=0
ATTEMPT_ARTIFACT_LEDGER_UNAUTHORIZED_CHANGES=0
CLAIM_LIFECYCLE_UNAUTHORIZED_CHANGES=0
RESEARCH_CHECKPOINT_LEDGER_HASH_CHANGES=0
CANONICALIZATION_REGISTRY_UNAUTHORIZED_CHANGES=0
CONVERGENCE_CONTROL_UNAUTHORIZED_CHANGES=0
ISSUE_001_REGRESSION=PASS
ISSUE_002_REGRESSION=PASS
ISSUE_003_REGRESSION=PASS
ISSUE_004_REGRESSION=PASS
ISSUE_005_REGRESSION=PASS
PROTECTED_FILES_NO_DIFF=PASS
FULL_VERIFICATION=PASS
WORKTREE_CLEAN=true
ISSUE_006_STATUS=CLOSED
================================================================
```

## 15. Final proposed-Claim and checkpoint-branch follow-up

This follow-up closes two production branches that were not covered by the initial issue 006
implementation. It remains part of issue 006 and does not start issue 007.

### 15.1 Pre-fix behavioral evidence

The tests were added and executed before the production changes. They exposed behavior, not
merely missing Java types:

```text
PivotClaimStatementHashBindingTest
  expected CLAIM_STATEMENT_HASH_MISMATCH, but the deterministic audit returned no failure
  expected UNMATERIALIZABLE_PROPOSED_CLAIM, but the deterministic audit returned no failure

DesktopPivotProposedClaimMaterializationTest
  pivot status=APPLIED
  proposed Claim present after apply=false
```

The old path could therefore count an `ADD_AS_PROPOSED_CLAIM` entry in the structural
signature without creating any mathematical Claim state. It also had no rollback snapshot for
the global `ContinuationFunctions.CheckpointLedger` after `branchForStrategy` mutated it.

### 15.2 Authoritative statement-hash binding and materialization

`SemanticPivotProposal.ClaimUseChangeDraft` now accepts a complete optional
`ProposedClaimDraft`. `ADD_AS_PROPOSED_CLAIM` requires that payload; the old four-field JSON
shape remains valid for every non-add action.

The deterministic authority projection now carries `claimId -> authoritative statement hash`.
The server recomputes each proposed statement hash from
`CanonicalJson.stableHash(ProofIdentity.normalizeText(statement))` and rejects:

- an existing Claim referenced with a different hash;
- an add action without a complete materializable draft;
- an outer Claim ID or hash that differs from its nested draft;
- duplicate known or same-Pivot statement hashes;
- self-dependencies or dependencies absent from both the authoritative state and this Pivot.

The stable failure codes are `CLAIM_STATEMENT_HASH_MISMATCH` and
`UNMATERIALIZABLE_PROPOSED_CLAIM`.

An admitted draft is now atomically materialized through the existing issue 003 owners as:

```text
ClaimCard(status=PROPOSED)
-> LemmaMemory
-> ClaimLifecycleController(state=PROPOSED)
-> route pending proposed-Claim projection
-> next real ProofAttempt.proposedLemmas
-> AttemptArtifactHarvester
-> independent claim_salvage_review
```

The Pivot cannot directly create `VERIFIED`, `FACT`, or `EXTERNALLY_ADMITTED_FACT` authority.
The pending projection is persisted in schema 12; older schema 12 JSON that lacks the optional
field restores it as empty. Once harvested, the route projection is removed exactly once.

### 15.3 Checkpoint-ledger transaction boundary

`ContinuationFunctions.CheckpointLedger` now exposes an immutable snapshot containing all
checkpoints, `latestByBranch`, audit history, version, and a stable hash. Pivot apply snapshots
and restores this global owner together with Route, Strategy Archive, Proof Graph, convergence,
deferred expansion, pending tasks, admitted strategies, blueprints, goal links, Lemma Memory,
Claim Lifecycle, and the Semantic Pivot ledger.

The tested failure points now include:

```text
AFTER_CHECKPOINT_BRANCH
DURING_CHECKPOINT_PERSIST
BEFORE_APPLIED_CHECKPOINT_PERSIST
```

The production transaction no longer persists a checkpoint after branching while its ledger is
`APPLYING`. It creates and commits the receipt in memory, then atomically persists one complete
`APPLIED` checkpoint. If ordinary persistence fails, rollback writes a compensating checkpoint
from the restored state. A retry then creates one Pivot application and one checkpoint branch,
while a duplicate retry creates neither.

### 15.4 Follow-up diagnostics

```text
PROPOSED CLAIM BINDING AND MATERIALIZATION
KNOWN_CLAIM_WRONG_HASH_REJECTIONS=1
GHOST_PROPOSED_CLAIM_REJECTIONS=1
VALID_PROPOSED_CLAIMS_CREATED=1
PROPOSED_CLAIM_DIRECT_VERIFICATIONS=0
PROPOSED_CLAIM_DIRECT_FACT_PROMOTIONS=0
PROPOSED_CLAIM_ROLLBACK_LEAKS=0
POST_RESTORE_PROPOSED_CLAIM_LOSSES=0
POST_RESTORE_DUPLICATE_PROPOSED_CLAIMS=0

CHECKPOINT BRANCH AND PERSISTENCE ATOMICITY
AFTER_CHECKPOINT_BRANCH_ROLLBACKS=1
CHECKPOINT_LEDGER_HASH_CHANGES=0
CHECKPOINT_BRANCH_LEAKS=0
LATEST_BRANCH_POINTER_LEAKS=0
CHECKPOINT_AUDIT_LEAKS=0
TASK_LEASE_LEAKS=0
PENDING_TASK_LEAKS=0
PARTIAL_PIVOT_RECEIPTS=0
POST_RETRY_PIVOT_APPLIES=1
POST_RETRY_CHECKPOINT_BRANCHES=1
POST_RETRY_DUPLICATE_BRANCHES=0
RESULT=PASS
```

The existing 20-round production test also remained green through round 10 restore:

```text
ROUNDS=20
RESTORE_ROUND=10
PIVOT_LEDGER_HASH_BEFORE_RESTORE=d67c4944a8b1967316ef1a0c2513f26163f870ea8854a485572b5b7255fac19a
PIVOT_LEDGER_HASH_AFTER_RESTORE=d67c4944a8b1967316ef1a0c2513f26163f870ea8854a485572b5b7255fac19a
ROOT_HASH_CHANGES=0
NEGATIVE_REGISTRY_HASH_CHANGES=0
POST_RESTORE_PIVOT_LOSSES=0
POST_RESTORE_DUPLICATE_APPLIES=0
RESULT=PASS
```

### 15.5 Follow-up tests and final gates

The seven new test classes contain eleven tests:

- `PivotClaimStatementHashBindingTest`: three statement-binding/materializability cases.
- `ContinuationCheckpointLedgerSnapshotTest`: one complete snapshot/restore case.
- `DesktopPivotProposedClaimMaterializationTest`: one production materialization case and one
  rollback case.
- `DesktopPivotProposedClaimRestoreExactlyOnceTest`: one schema 12 restore case.
- `DesktopSemanticPivotCheckpointBranchAtomicityTest`: one post-branch rollback case.
- `DesktopSemanticPivotPersistFailureRecoveryTest`: one production scenario that iterates both
  persistence failure windows, followed by retry and duplicate retry.
- `DesktopSemanticPivotHardCrashRecoveryTest`: two fresh-Coordinator restore cases covering a
  crash before the atomic state move and a crash immediately after it.

The final clean reactor executed `2274` tests with zero failures and zero errors. Four expected
tests were skipped. The module split was Contracts 48, Core 1068, Server unit 847, Server
integration 26, Desktop 136, and Compatibility 149. All 21 Docker-backed PostgreSQL integration
tests passed.

The unmodified `verify-all.ps1 -Offline` completed from the repository's required source-layout
projection and reported:

```text
MAVEN_CLEAN_VERIFY=PASS
POSTGRESQL_INTEGRATION_TESTS=PASS
DEPENDENCY_CHECK_PATH_NORMALIZATION=PASS
PHASE_17_COVERAGE=PASS
PHASE_17_SECURITY=PASS
SOURCE_IMMUTABILITY=PASS
SOURCE_FILES=401
SOURCE_MANIFEST_SHA256=9f3def2bec8cea99d0a18b51fbb5fa8ce53f44a24ce869f6495cac809b7e3770
SPOTBUGS_OR_SECURITY_BUGS=0
FULL_VERIFICATION=PASS
```

The first full verification found seven SpotBugs findings in the new snapshot/hash code. They
were fixed with defensive collection accessors and constant-time hash comparison; no
suppression, threshold, performance gate, or old test was weakened.

### 15.6 Follow-up file purpose and scope

- `SemanticPivotProposal`, `PivotClaimUseChange`, `PivotProposedClaimDraft`, compiler,
  authority context, and deterministic auditor: complete draft contract and trusted hash gate.
- `ContinuationFunctions.CheckpointLedger`: immutable full-ledger snapshot and rollback.
- `DesktopSolveCoordinator`, `DesktopSolveCheckpoint`, and `SemanticPivotFailurePoint`:
  production materialization, persistence, restore, compensation, and failure windows.
- `PromptCatalog`: proposer instruction for the complete non-authoritative draft.
- The six new Core/Desktop test files and focused fixture updates: black-box behavior,
  exactly-once restore, atomicity, and real-state counters.

The follow-up implementation commit changed 27 files with 1019 insertions and 17 deletions.
It did not modify issue 001-005-specific implementation, Provider selection, concurrency,
Temporal, budget/token handling, Python Sidecar thresholds, or any issue 007-013 behavior.

## 16. Process-crash-safe authoritative checkpoint boundary

### 16.1 Confirmed pre-fix failure

The prior RuntimeException tests proved in-process compensation, but the coordinator still
persisted a formal `APPLYING` checkpoint between `branchForStrategy` and receipt creation. A
new test-only `Error` termination hook was first added without changing that order. Because
`Error` bypasses both RuntimeException compensation layers, the pre-fix production path
reported:

```text
HARD_CRASHES_INJECTED=1
APPLYING_CHECKPOINTS_OBSERVED=1
RESTORE_FAILURES=1
PIVOT_APPLIES=0
CHECKPOINT_BRANCHES=0
RESULT=FAIL
```

The checkpoint was read back from the real `structured/desktop-solve-state.json`, the first
Coordinator was discarded, and a fresh Coordinator called the production restore path. Restore
failed at `reconcileSemanticPivotProjection` with the partial apply frontier, confirming a real
process-death behavior gap rather than an in-process test artifact.

### 16.2 Implemented option A

The authoritative apply order is now:

```text
stage the Pivot in memory
-> mutate Route, Strategy, Claim, Obligation, Task, and CheckpointLedger projections
-> create SemanticPivotApplyReceipt
-> commitApply, producing the complete APPLIED ledger record
-> atomically persist one complete semantic_pivot_apply checkpoint
```

`persistUnchecked("semantic_pivot_checkpoint_branch", false)` was removed. The authoritative
state file can therefore contain only the complete pre-Pivot state or the complete `APPLIED`
state. It cannot contain a newly written `APPLYING` Pivot. No checkpoint schema change or
durable roll-forward frontier is required.

If termination occurs before the atomic state-file move, restore sees the complete pre-Pivot
state and the stable Pivot may be submitted again. If termination occurs after that move,
restore sees the complete `APPLIED` record and receipt; submitting the same Pivot is an
idempotent no-op. Existing RuntimeException compensation remains in place for ordinary write
failures and rewrites the restored state when persistence had begun.

### 16.3 Hard-crash test

`DesktopSemanticPivotHardCrashRecoveryTest` uses
`SimulatedSemanticPivotProcessTermination extends Error`, so neither production
RuntimeException catch executes. Each case reads the real state file, closes the old Agent pool,
creates a fresh Coordinator, invokes production restore, and resubmits the same stable Pivot.

Final diagnostic for termination before the authoritative atomic write:

```text
SEMANTIC PIVOT HARD-CRASH RECOVERY DIAGNOSTIC
HARD_CRASHES_INJECTED=1
APPLYING_CHECKPOINTS_OBSERVED=0
RESTORE_FAILURES=0
PARTIAL_PIVOT_FRONTIERS_AFTER_RESTORE=0
PIVOT_APPLIES=1
CHECKPOINT_BRANCHES=1
DUPLICATE_PIVOT_APPLIES=0
DUPLICATE_CHECKPOINT_BRANCHES=0
GHOST_PROPOSED_CLAIMS=0
DUPLICATE_PROPOSED_CLAIMS=0
PARTIAL_OBLIGATION_WRITES=0
TASK_LEASE_LEAKS=0
PENDING_TASK_LEAKS=0
ROOT_HASH_CHANGES=0
NEGATIVE_REGISTRY_HASH_CHANGES=0
RESULT=PASS
```

`APPLYING_CHECKPOINTS_OBSERVED` is intentionally zero after the fix because option A removes
that durable state rather than recovering it. The same test class also terminates immediately
after the authoritative state move and reports:

```text
POST_ATOMIC_MOVE_APPLIED_RESTORES=1
POST_ATOMIC_MOVE_DUPLICATE_APPLIES=0
```

### 16.4 Final verification

```text
CORE_PIVOT_AND_CHECKPOINT_TESTS=48 PASS
SERVER_PIVOT_TESTS=4 PASS
DESKTOP_PIVOT_TESTS=23 PASS
FULL_REACTOR_TESTS=2274
FULL_REACTOR_FAILURES=0
FULL_REACTOR_ERRORS=0
POSTGRESQL_INTEGRATION_TESTS=21 PASS
CORE_BRANCH_COVERAGE=75.305335
SPOTBUGS_OR_SECURITY_BUGS=0
SOURCE_FILES=401
SOURCE_MANIFEST_SHA256=9f3def2bec8cea99d0a18b51fbb5fa8ce53f44a24ce869f6495cac809b7e3770
FULL_VERIFICATION=PASS
```

The first full-module attempt correctly failed `-Werror` because the simulated termination
class was initially an auxiliary class in the failure-point source file. It was moved to its own
source file and the unmodified compiler gate passed; no warning suppression was added.

This final follow-up changed six files with 239 insertions and eight deletions. It remains on
`fix/006-semantic-pivot`, changes no issue 001-005-specific implementation, and does not start
issue 007 or alter Provider, concurrency, Temporal, budget, token, or Python Sidecar behavior.
