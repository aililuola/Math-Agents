# Issue 006: Semantic Pivot

## 1. Status and Git provenance

- Status: `CLOSED` after the final gates recorded below pass.
- Branch: `fix/006-semantic-pivot`
- Baseline branch: `java`
- Baseline commit: `5bb2460e7fe7dd2a33c72fb32eaf3f0dd0ab1237`
- Baseline checkpoint schema: `11`
- Resulting checkpoint schema: `12`
- Implementation commit: `14303bcbfd5f5e80395ccb5d47807505d4f9a643`.
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
PIVOT_LEDGER_HASH_BEFORE_RESTORE=fa7596c492e7a78e04405ce060808f31e793d57efb816b3266f148722e0e1e30
PIVOT_LEDGER_HASH_AFTER_RESTORE=fa7596c492e7a78e04405ce060808f31e793d57efb816b3266f148722e0e1e30
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
| Contracts/Core Pivot suite | 32 | 0 | 0 | 0 | PASS |
| Server Pivot suite | 4 | 0 | 0 | 0 | PASS |
| Desktop production suite | 18 | 0 | 0 | 0 | PASS |

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
| Core | 1064 | 0 | 0 | 0 | PASS |
| Server unit | 847 | 0 | 0 | 3 | PASS |
| Server integration | 26 | 0 | 0 | 0 | PASS |
| Desktop | 129 | 0 | 0 | 1 | PASS |
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
75% gate. The final clean verification measured `75.377644%` core branch coverage. No Python
Sidecar performance or coverage/security/license threshold was changed.

Implementation diff stat:

```text
implementation=91 files/8778 insertions/43 deletions; branch_with_record=92 files/9235 insertions/43 deletions
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
