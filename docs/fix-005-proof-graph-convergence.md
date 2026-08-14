# Issue 005: Proof Graph Canonicalization and Convergence Control

## 1. Status and Git provenance

- Status: `CLOSED`
- Branch: `fix/005-proof-graph-convergence`
- Baseline branch: `java`
- Baseline commit: `cc2471e8a9904e34775350afac43e1ad7a018f56`
- Commit A: `7ec67509bacba069c981d33a5106e47b2ed41813`
  - `fix(proof-graph): canonicalize duplicate obligations and bottleneck families`
- Commit B: `50f9f01cba5f66defc73e98ce9a4ead2151f698d`
  - `fix(control): drive focused recovery from graph convergence`
- This record is a separate documentation-only commit so that it can record the real
  Commit B hash without a self-referential commit hash.

The work stayed on the issue-005 branch. It did not modify `main` or commit directly
to `java`.

## 2. Production entry-point audit

The production search covered obligation writes, proof-task creation, proof-debt reads,
generic expansion, and restore paths.

### Obligation writes

- `ProofGraphStore.addObligation(...)` remains protected by
  `NegativeAwareProofGraphWriter`.
- `ProofGraphStore.addObligationCanonicalized(...)` records the raw occurrence and
  canonical projection only after the existing negative-knowledge admission path.
- `DesktopSolveCoordinator.addControlledObligation(...)` performs a convergence and
  capacity preview before `addObligationThroughControl(...)` commits the canonicalized
  write.
- Blueprint, bridge, revision, inspiration, and focused-recovery obligations use that
  controlled writer. Deferred proposals remain raw occurrences and are also recorded in
  `DeferredExpansionLedger`.
- `MAIN_GOAL` authority and closure behavior were not changed.

### Proof-task creation

- `DesktopSolveCoordinator.enqueueProofTask(...)` is the production queue entry point.
- Automatic work is leased by canonical target or bottleneck family; independent route
  work retains route-occurrence scope.
- Focused-recovery decisions run before queue mutation. Duplicate leases and duplicate
  pending tasks are rejected before insertion.

### Proof-debt reads

- `ProofGraphStore.proofDebt(...)` preserves the raw route debt view.
- `canonicalProofDebt()`, `activeCanonicalProofDebt()`,
  `deferredCanonicalProofDebt()`, and `globalCanonicalProofDebt()` provide distinct
  canonical control views.
- Deferred work does not create a false debt decrease; duplicate raw occurrences are not
  authoritative progress.

### Generic expansion

- Inspiration materialization, representation switch, structural analogy, unscoped
  bridge, new strategy, revision, and their proof-task projections consult the shared
  convergence decision before mutation.
- Focused prover, focused skeptic, exact falsification, and selected-family bridge repair
  remain admissible in focused mode.
- Already-running provider calls are not cancelled.

### Restore

- `DesktopSolveCoordinator.restore(...)` restores the graph first, then the convergence
  monitor and deferred-expansion ledger, and finally reconciles task leases and existing
  negative-knowledge revalidation.
- Schema v9 defaults the newly introduced v10 convergence state to normal mode and the
  deferred ledger to empty. Restore performs no model call and does not infer historical
  convergence episodes.

## 3. Implementation summary

### Phase 005A

The graph now has three separate levels:

1. Raw obligation occurrence: immutable provenance, route, strategy, source artifact,
   dependency plan, status, round, and audit history.
2. Canonical obligation target: deterministic `EXACT` or
   `TRUSTED_ALPHA_EQUIVALENT` identity only.
3. Bottleneck family: scheduling and prompt focus for related but non-equivalent targets.

`POSSIBLE_EQUIVALENT`, quantifier, polarity, scope, kind, or problem-hash conflicts do not
hard merge. Dependency plans and route provenance are unions of alternatives, not a
single overwritten plan. Family review has no Claim, Fact, Negative, or main-goal
authority.

### Phase 005B

`ProofGraphConvergenceMonitor` classifies production graph rounds using verified-claim,
exact-refutation, closed-target, active/deferred/global canonical-debt, and target-growth
signals. Two consecutive no-progress or divergence rounds enter `FOCUSED_RECOVERY`.
Authoritative progress enters `RECOVERY_COOLDOWN`, then returns to normal expansion.

The focused plan is deterministic, restore-safe, quota-limited, family/canonical-task
deduplicated, and projected into prompts as a non-authoritative sidecar. Capacity is
limited to 8 active canonical targets per route, 20 per campaign, and 2 new targets per
focused episode. Over-capacity proposals are deferred, never deleted.

## 4. Modified files

All paths below are relative to the repository root.

### Contracts and server

```text
mathproofmesh-contracts/src/main/java/io/github/aililuola/mathproofmesh/contract/
  ObligationFamilyReviewBatch.java
  ObligationFamilyReviewDecision.java

mathproofmesh-server/src/main/java/io/github/aililuola/mathproofmesh/agent/
  PromptCatalog.java

mathproofmesh-server/src/test/java/io/github/aililuola/mathproofmesh/agent/
  ObligationFamilyReviewContractsTest.java
  PromptCatalogObligationFamilyReviewTest.java
```

### Core production

```text
mathproofmesh-core/src/main/java/io/github/aililuola/mathproofmesh/proofgraph/
  BottleneckFamilyRecord.java
  BottleneckFamilySchedulingState.java
  BottleneckRelationType.java
  BridgeBroker.java
  CanonicalObligationRecord.java
  CanonicalObligationSchedulingState.java
  CanonicalObligationStatus.java
  CanonicalizedObligationWriteResult.java
  DeferredExpansionLedger.java
  DeferredExpansionRecord.java
  DeferredExpansionSnapshot.java
  FocusedExpansionDecision.java
  FocusedRecoveryActionType.java
  FocusedRecoveryBrief.java
  FocusedRecoveryPlan.java
  ObligationCanonicalizationAuditEvent.java
  ObligationCanonicalizationRegistry.java
  ObligationCanonicalizationSnapshot.java
  ObligationCreationContext.java
  ObligationFamilyReviewService.java
  ObligationIdentityStrength.java
  ObligationOccurrenceRecord.java
  ObligationOccurrenceSchedulingState.java
  ObligationSemanticSignature.java
  ObligationSourceType.java
  ProofGraphControlMode.java
  ProofGraphConvergenceConfig.java
  ProofGraphConvergenceMonitor.java
  ProofGraphConvergenceSnapshot.java
  ProofGraphConvergenceTrigger.java
  ProofGraphRoundClassification.java
  ProofGraphRoundMetrics.java
  ProofGraphSnapshot.java
  ProofGraphStore.java
  ProofGraphWorkItem.java
  ProofTaskScope.java
```

### Core tests

```text
mathproofmesh-core/src/test/java/io/github/aililuola/mathproofmesh/proofgraph/
  BottleneckFamilyRegistryTest.java
  CanonicalCounterexampleBoundaryTest.java
  CanonicalDebtNoFalseDecreaseTest.java
  CanonicalObligationStatusAggregationTest.java
  CanonicalProofTaskLeaseTest.java
  CanonicalTargetCapacityTest.java
  DeferredExpansionLedgerTest.java
  FocusedExpansionGateTest.java
  FocusedRecoverySelectionTest.java
  FocusedRecoveryTaskLeaseTest.java
  ObligationCanonicalizationRegistryTest.java
  ObligationCanonicalizationSnapshotTest.java
  ObligationCanonicalizationTestFixtures.java
  ObligationDependencyAlternativePreservationTest.java
  ObligationFamilyReviewBoundaryTest.java
  ObligationSemanticSignatureTest.java
  ProofGraphCanonicalControlBoundaryTest.java
  ProofGraphCanonicalDebtTest.java
  ProofGraphCanonicalViewTest.java
  ProofGraphControlModeTransitionTest.java
  ProofGraphConvergenceBoundaryTest.java
  ProofGraphConvergenceControlBoundaryTest.java
  ProofGraphConvergenceMetricsTest.java
  ProofGraphConvergenceSnapshotTest.java
  ProofGraphConvergenceTestFixtures.java
  ProofGraphRoundClassificationTest.java
```

### Desktop production and tests

```text
mathproofmesh-desktop/src/main/java/io/github/aililuola/mathproofmesh/desktop/
  DesktopSolveCheckpoint.java
  DesktopSolveCoordinator.java

mathproofmesh-desktop/src/test/java/io/github/aililuola/mathproofmesh/desktop/
  DesktopBottleneckFamilyProductionTest.java
  DesktopCanonicalCapacityProductionTest.java
  DesktopCanonicalFocusRouteMappingTest.java
  DesktopCanonicalPromptProjectionTest.java
  DesktopCanonicalProofTaskDedupTest.java
  DesktopCrossRouteDuplicateObligationBlackBoxTest.java
  DesktopFocusedRecoveryProductionTest.java
  DesktopFocusedRecoveryPromptTest.java
  DesktopFocusedTaskDedupTest.java
  DesktopGenericExpansionGateTest.java
  DesktopObligationCanonicalizationAtomicityTest.java
  DesktopObligationCanonicalizationMultiRoundRestoreTest.java
  DesktopObligationCanonicalizationProductionTest.java
  DesktopPermanentNegativeKnowledgeProductionTest.java
  DesktopProofDebtNotControllingSchedulerBlackBoxTest.java
  DesktopProofGraphConvergenceAtomicityTest.java
  DesktopProofGraphConvergenceControllerTest.java
  DesktopProofGraphConvergenceMultiRoundRestoreTest.java
  DesktopProofGraphIssue005BlackBoxSupport.java
  DesktopSharedBottleneckTaskExplosionBlackBoxTest.java
  DesktopV6VerifiedClaimContinuityTest.java
  DesktopV8ObligationCanonicalizationMigrationTest.java
  DesktopV9ConvergenceMigrationTest.java
  GreedyGcdObligationExplosionCompressionTest.java
  ObligationCanonicalizationProtectedAuthorityTest.java
  ProofGraphConvergenceProtectedAuthorityTest.java
```

The two pre-existing desktop tests changed only for checkpoint-constructor compatibility;
their issue-002/003 assertions were not weakened.

## 5. Pre-fix black-box behavior evidence

These failures were observed against baseline behavior, before the production fix. They
do not rely on missing new API types.

```text
CROSS ROUTE DUPLICATE OBLIGATION BASELINE
RAW_OPEN_OBLIGATIONS=5
EXPECTED_CANONICAL_TARGETS=1
ACTUAL_SCHEDULABLE_WORK_ITEMS=5
DEPENDENCY_PLAN_SIGNATURES=5

SHARED BOTTLENECK TASK EXPLOSION BASELINE
SHARED_BOTTLENECK_GROUPS_DETECTED=1
EXPECTED_FAMILY_TASKS=1
ACTUAL_AUTOMATIC_TASKS=4

PROOF DEBT SCHEDULER CONTROL BASELINE
CONSECUTIVE_NO_PROGRESS_ROUNDS=3
GENERIC_EXPANSION_ATTEMPTS=12
GENERIC_EXPANSION_BLOCKS=0
FOCUSED_RECOVERY_ENTRIES=0
GENERIC_EXPANSION_LEAKS=0
```

After the fix, the first two schedulable counts are 1 and 1. The convergence black-box
records 10 blocked generic actions, one focused entry, and zero leaks.

## 6. Phase 005A verification

- Core required suite: 11 tests, 0 failures/errors/skips.
- Server required suite: 2 tests, 0 failures/errors/skips.
- Desktop required suite: 12 tests, 0 failures/errors/skips.

### 87-proposal compression

```text
OBLIGATION CANONICALIZATION DIAGNOSTIC
RAW_OBLIGATION_PROPOSALS=87
RAW_OCCURRENCES_RECORDED=87
CANONICAL_TARGETS=19
BOTTLENECK_FAMILIES=7
SCHEDULABLE_FAMILY_WORK_ITEMS=7
UNSAFE_HARD_MERGES=0
POSSIBLE_EQUIVALENT_HARD_MERGES=0
QUANTIFIER_COLLISIONS=0
POLARITY_COLLISIONS=0
SCOPE_COLLISIONS=0
KIND_COLLISIONS=0
DEPENDENCY_ALTERNATIVE_LOSSES=0
ROUTE_PROVENANCE_LOSSES=0
MAIN_GOAL_CLOSURES=0
```

### 20-round canonical restore

```text
OBLIGATION CANONICALIZATION MULTI-ROUND DIAGNOSTIC
ROUNDS=20
RESTORE_SCHEMA_VERSION=10
RESTORE_ROUND=10
RAW_OBLIGATION_PROPOSALS=120
RAW_OCCURRENCES_RECORDED=120
CANONICAL_TARGETS=4
MULTI_MEMBER_BOTTLENECK_FAMILIES=1
SCHEDULABLE_WORK_ITEMS=3
DUPLICATE_CANONICAL_TASKS=0
DUPLICATE_FAMILY_TASKS=0
INDEPENDENT_ROUTE_TASKS_SUPPRESSED=0
POST_RESTORE_CANONICAL_LOSSES=0
POST_RESTORE_FAMILY_LOSSES=0
POST_RESTORE_TASK_DUPLICATES=0
```

The phase-A test first passed with schema v9. It was rerun after phase B through the real
v10 checkpoint path; the canonical targets, families, dependency alternatives, route
tasks, and leases remained stable.

### v8 to v9 migration

A v8 `ProofGraphSnapshot` without the canonicalization projection rebuilds the registry
deterministically from raw obligations. Two independent restores produce the same
canonicalization hash, retain every raw occurrence, and emit
`canonicalization_rebuilt_from_raw`. No model/provider call is made.

## 7. Phase 005B verification

- Core required suite: 10 tests, 0 failures/errors/skips.
- Core boundary/coverage suite: 12 tests, 0 failures/errors/skips.
- Desktop required suite: 11 tests, 0 failures/errors/skips.
- Combined issue-005 required and boundary suites: 58 tests, all passed.

### 8-round control diagnostic

```text
PROOF GRAPH CONVERGENCE 8-ROUND DIAGNOSTIC
ROUNDS=8
FOCUSED_RECOVERY_ENTRIES=1
RECOVERY_COOLDOWN_ENTRIES=1
GENERIC_EXPANSION_ATTEMPTS=12
GENERIC_EXPANSION_BLOCKS=12
GENERIC_EXPANSION_LEAKS=0
FOCUSED_FAMILY_TASKS=1
FINAL_CONTROL_MODE=NORMAL_EXPANSION
RESULT=PASS
```

### 20-round convergence and restore diagnostic

```text
PROOF GRAPH CONVERGENCE DIAGNOSTIC
ROUNDS=20
RESTORE_ROUND=10
RAW_PROPOSALS=38
RAW_OCCURRENCES_RECORDED=38
CANONICAL_TARGETS_CREATED=14
DUPLICATE_OCCURRENCES=24
STAGNATION_EPISODES=2
DIVERGENCE_EPISODES=0
FOCUSED_RECOVERY_ENTRIES=2
FOCUSED_RECOVERY_EXITS=1
RECOVERY_COOLDOWN_ENTRIES=1
GENERIC_EXPANSION_ATTEMPTS=54
GENERIC_EXPANSION_BLOCKS=52
GENERIC_EXPANSION_LEAKS=0
FOCUSED_FAMILY_TASKS_CREATED=2
DUPLICATE_FOCUSED_FAMILY_TASKS=0
UNRELATED_FAMILY_TASKS_CREATED=0
MAX_ACTIVE_CANONICAL_TARGETS_PER_ROUTE=4
MAX_ACTIVE_CANONICAL_TARGETS_CAMPAIGN=4
CAPACITY_DEFERRED_PROPOSALS=0
CAPACITY_HARD_DELETIONS=0
VERIFIED_CLAIM_GAINS=1
EXACT_REFUTATION_GAINS=1
CLOSED_CANONICAL_TARGET_GAINS=2
CANONICAL_DEBT_DECREASE_EVENTS=2
CANONICAL_DEBT_FALSE_DECREASES=0
RAW_DUPLICATES_COUNTED_AS_PROGRESS=0
POST_RESTORE_STATE_CHANGES=0
POST_RESTORE_GENERIC_EXPANSION_LEAKS=0
POST_RESTORE_DUPLICATE_FOCUSED_TASKS=0
ROOT_HASH_CHANGES=0
NEGATIVE_REGISTRY_HASH_CHANGES=0
ATTEMPT_ARTIFACT_LEDGER_UNAUTHORIZED_CHANGES=0
CLAIM_LIFECYCLE_UNAUTHORIZED_CHANGES=0
RESEARCH_CHECKPOINT_LEDGER_HASH_CHANGES=0
CANONICALIZATION_REGISTRY_HASH_CHANGES=0
DIRECT_FACT_PROMOTIONS=0
DIRECT_CLAIM_VERIFICATIONS=0
DIRECT_NEGATIVE_REGISTRATIONS=0
MAIN_GOAL_CLOSURES=0
CONVERGENCE_HASH_BEFORE_RESTORE=e5da0a353bb2c1cd8c1f5ad876cec965eaa1fc7faf0b876e85844427b40098ca
CONVERGENCE_HASH_AFTER_RESTORE=e5da0a353bb2c1cd8c1f5ad876cec965eaa1fc7faf0b876e85844427b40098ca
ACTIVE_CANONICAL_DEBT=10.9175
DEFERRED_CANONICAL_DEBT=21.6
GLOBAL_CANONICAL_DEBT=32.5175
RESULT=PASS
```

The 20-round scenario does not reach the capacity ceiling. The separate production
capacity test fills one route to 8 active targets, records the ninth proposal as both a
raw occurrence and one deferred-ledger record, excludes it from schedulable work, and
reports positive deferred/global debt. Nothing is deleted.

### v9 to v10 migration

The v9 checkpoint has no convergence or deferred-expansion fields. Deserialization and
restore produce `NORMAL_EXPANSION`, empty history, no focused plan, and an empty deferred
ledger without guessing history or calling a provider.

The existing canonical registry and task lease survive persistence, JSON, and restore:

```text
V9_CANONICAL_HASH_BEFORE_PERSIST=e225947c52af436a680638782cdbefda1743dee17b9c9f4cec438b1ef9f05355
V9_CANONICAL_HASH_AFTER_PERSIST=e225947c52af436a680638782cdbefda1743dee17b9c9f4cec438b1ef9f05355
V9_CANONICAL_HASH_IN_CHECKPOINT=e225947c52af436a680638782cdbefda1743dee17b9c9f4cec438b1ef9f05355
V9_CANONICAL_HASH_AFTER_JSON=e225947c52af436a680638782cdbefda1743dee17b9c9f4cec438b1ef9f05355
V9_CANONICAL_HASH_AFTER_RESTORE=e225947c52af436a680638782cdbefda1743dee17b9c9f4cec438b1ef9f05355
V9_CANONICAL_VERSION_BEFORE=6
V9_CANONICAL_VERSION_AFTER=6
```

## 8. Earlier-issue regressions and protected authority

The explicit issue-001, issue-002, issue-003, and issue-004 regression commands passed.
The final full Maven verification also executed those suites again.

- Issue 001: root statement/hash stable; rejected semantic sidecars do not leak.
- Issue 002: 150 negative re-entry attempts remain blocked with zero active-state leaks;
  route widening remains gated and possible-equivalent candidates are not permanently
  hard blocked.
- Issue 003: local Claim salvage, exact counterexample scope, verified legacy Fact
  continuity, and main-goal authority remain unchanged.
- Issue 004: checkpoint findings survive restore and reach the real route prompt without
  direct Fact/Claim/Negative promotion.

Relative to the baseline commit, every protected issue-001 through issue-004 production
file has an empty diff:

```text
PROTECTED_FILES_NO_DIFF
```

## 9. Full verification gates

`scripts/verify-all.ps1 -Offline` ran through an isolated directory topology containing
the frozen 401-file original-source tree and a junction to this working tree. This was
necessary because the checkout itself lives under `.publish`, whereas the immutable
source checker intentionally derives the original-source root from the Java target's
parent directory. Neither the checker nor its baseline was changed.

The command completed with exit code 0:

```text
FULL VERIFICATION: PASS
```

Results captured from the generated reports before restoring those runtime reports:

- Total Surefire/Failsafe results: 2176 tests, 0 failures, 0 errors, 4 skips.
- PostgreSQL Testcontainers IT: 21 tests, all passed.
  - `JdbcMessageRepositoryIT`: 4
  - `MemoryProofGraphPostgresIT`: 4
  - `PersistencePostgresIT`: 9
  - `Phase17CheckpointOutboxPerformanceIT`: 1
  - `ProviderCallPostgresIT`: 3
- Coverage: PASS.
  - Core line: 91.384898%
  - Core branch: 75.482965%
  - Audited invariant line: 93.594903%
  - Audited invariant branch: 85.116557%
- SpotBugs/FindSecBugs: PASS, 0 findings in all five Java modules.
- Security: PASS; OWASP dependency gate and secret scan passed.
- License: PASS; 111 components, 0 missing, 0 unreviewed.
- Offline performance artifacts were present and no Python-sidecar threshold or gate was
  changed.
- Source immutability: PASS, 401 files,
  manifest `9f3def2bec8cea99d0a18b51fbb5fa8ce53f44a24ce869f6495cac809b7e3770`.

Generated coverage/security/license reports were restored to `HEAD` and are not part of
the commits.

## 10. Diff statistics

```text
Commit A: 57 files changed, 3845 insertions(+), 76 deletions(-)
Commit B: 51 files changed, 4439 insertions(+), 79 deletions(-)
Baseline through Commit B: 95 files changed, 8218 insertions(+), 89 deletions(-)
```

## 11. Final diagnostic

```text
ISSUE 005 FINAL DIAGNOSTIC
================================================================

PHASE_005A_RESULT=PASS
RAW_OBLIGATIONS_87_TEST=87
CANONICAL_TARGETS_87_TEST=19
BOTTLENECK_FAMILIES_87_TEST=7
UNSAFE_HARD_MERGES=0
DEPENDENCY_ALTERNATIVE_LOSSES=0
ROUTE_PROVENANCE_LOSSES=0

PHASE_005B_RESULT=PASS
STAGNATION_EPISODES=2
FOCUSED_RECOVERY_ENTRIES=2
GENERIC_EXPANSION_LEAKS=0
DUPLICATE_FOCUSED_TASKS=0
CANONICAL_DEBT_FALSE_DECREASES=0
CAPACITY_HARD_DELETIONS=0

ROOT_HASH_CHANGES=0
NEGATIVE_REGISTRY_HASH_CHANGES=0
ATTEMPT_ARTIFACT_LEDGER_UNAUTHORIZED_CHANGES=0
CLAIM_LIFECYCLE_UNAUTHORIZED_CHANGES=0
RESEARCH_CHECKPOINT_LEDGER_HASH_CHANGES=0

ISSUE_001_REGRESSION=PASS
ISSUE_002_REGRESSION=PASS
ISSUE_003_REGRESSION=PASS
ISSUE_004_REGRESSION=PASS

PROTECTED_FILES_NO_DIFF=PASS
FULL_VERIFICATION=PASS
WORKTREE_CLEAN=true

ISSUE_005_STATUS=CLOSED
================================================================
```

No issue-006 through issue-013 production behavior was modified. In particular, this
work did not change semantic pivoting, Broker behavior, computation infrastructure,
provider selection, API-key concurrency, token allocation, budgets, retry/failover,
Temporal concurrency, or Python-sidecar performance thresholds.

## Final focused-recovery binding and deferred-reactivation patch

### Patch identity and audited production paths

This final issue-005 patch remains on `fix/005-proof-graph-convergence` and is based on
`47f1784127fb76a719bd4cf1da97c96da9bf3f03`. Its commit message is
`fix(control): bind recovery tasks and reactivate deferred targets`; the resulting Git
hash is reported in the final delivery report because a commit cannot contain its own
hash.

The production audit covered these paths before implementation:

- Recovery-task admission converges at `DesktopSolveCoordinator.enqueueProofTask(...)`.
  Sources include focused prover, focused skeptic, proof-debt stall, meta-review,
  family bridge, exact falsification, verified-claim reuse, and generic expansion.
- Deferred records are created by the controlled proof-obligation and proof-task paths
  only after `ProofGraphConvergenceMonitor.decideExpansion(...)` rejects scheduling.
- Control-mode changes are observed after convergence samples, including entry into
  focused recovery, recovery cooldown, and return to normal expansion.
- Capacity is released by graph closure/refutation or scheduling retirement and is
  reconsidered at the next production control sample.
- Checkpoint restoration reinstalls Root/Negative/Graph/Convergence/Deferred/Routes/
  Tasks first, then runs deterministic deferred reconciliation.

### Modified files and purpose

Production changes are limited to the issue-005 scheduling authority:

- `FocusedRecoveryActionType.java`: separates `recoveryAction` from
  `requiresSelectedBinding` and gives source classification a stable precedence.
- `ProofGraphConvergenceMonitor.java`: owns the selected-family/target decision and
  applies capacity, mode, binding, and quota checks to every action.
- `ProofGraphConvergenceConfig.java`: adds the bounded per-round deferred-reactivation
  limit while retaining the legacy constructor.
- `DeferredExpansionRecord.java`, `DeferredExpansionLedger.java`, and the new
  `DeferredExpansionStatus.java`: implement the monotonic deferred lifecycle.
- `DeferredExpansionReactivationCandidate.java`,
  `DeferredExpansionReactivationDecision.java`,
  `DeferredExpansionReactivationOutcome.java`, and
  `DeferredExpansionReactivationPlanner.java`: provide deterministic eligibility,
  ordering, and the two-reactivations-per-round bound.
- `CanonicalSchedulingTransitionCode.java` and
  `CanonicalSchedulingTransitionResult.java`: type graph scheduling transitions.
- `ProofGraphStore.java`: atomically changes raw-occurrence and canonical scheduling
  projections without changing mathematical status or the canonical debt formula.
- `DesktopSolveCheckpoint.java`: advances the checkpoint schema from v10 to v11.
- `DesktopSolveCoordinator.java` and `DeferredReactivationFailurePoint.java`: gate every
  task, reconcile deferred work, stage lease/task/ledger mutation, and roll back injected
  failures.

Tests added or updated are:

- Core: `FocusedRecoverySelectedBindingPolicyTest`,
  `ProofTaskControlActionClassificationTest`,
  `DeferredExpansionLedgerLifecycleTest`,
  `DeferredExpansionReactivationOrderingTest`,
  `DeferredExpansionReactivationEligibilityTest`,
  `DeferredExpansionNoFalseDebtTest`,
  `DeferredExpansionSnapshotCompatibilityTest`, and
  `CanonicalSchedulingTransitionTest`.
- Desktop: `DesktopUnselectedRecoveryActionBypassBlackBoxTest`,
  `DesktopFocusedRecoverySelectedBindingGateTest`,
  `DesktopDeferredTargetNeverReactivatedBlackBoxTest`,
  `DesktopDeferredCapacityReactivationTest`,
  `DesktopFocusedDeferredReactivationRestoreTest`,
  `DesktopDeferredReactivationAtomicityTest`,
  `DesktopV10DeferredExpansionLifecycleMigrationTest`,
  `DesktopDeferredReactivationMultiRoundTest`, and their shared test support.
- Existing coverage was strengthened in
  `ProofGraphConvergenceControlBoundaryTest`,
  `DesktopProofGraphConvergenceMultiRoundRestoreTest`, and
  `DesktopProofGraphIssue005BlackBoxSupport` without deleting or weakening an assertion.

### Pre-fix behavioral failures

The tests first ran against the old production behavior, without depending on missing
new APIs. They exposed both defects directly:

```text
UNSELECTED RECOVERY BYPASS BLACK-BOX DIAGNOSTIC
UNSELECTED_RECOVERY_ATTEMPTS=5
EXPECTED_BLOCKS=5
ACTUAL_BLOCKS=2
UNRELATED_RECOVERY_TASK_LEAKS=3
SELECTED_RECOVERY_ACTION_ALLOWED=1
RESULT=FAIL
```

```text
DEFERRED TARGET NEVER REACTIVATED BLACK-BOX DIAGNOSTIC
DEFERRED_TARGETS_BEFORE_RELEASE=1
AVAILABLE_CAPACITY_AFTER_RELEASE=1
EXPECTED_REACTIVATIONS=1
ACTUAL_REACTIVATIONS=0
DEFERRED_TARGET_STILL_UNSCHEDULED=1
REACTIVATION_TASKS=0
RESULT=FAIL
```

After the fix, the same black-box observations are `ACTUAL_BLOCKS=5`,
`UNRELATED_RECOVERY_TASK_LEAKS=0`, `ACTUAL_REACTIVATIONS=1`,
`DEFERRED_TARGET_STILL_UNSCHEDULED=0`, and `REACTIVATION_TASKS=1`.

### Selected-binding verification

The requested Core suite passed 22 tests with zero failures/errors/skips. The additional
three-case canonical transition test covers fail-closed outcomes and all aggregate
scheduling-state precedence branches. The requested Desktop suite passed 9 tests with
zero failures/errors/skips.

```text
SELECTED BINDING RECOVERY DIAGNOSTIC
---------------------------------------------------------------
ROUNDS=10
RESTORE_ROUND=5
SELECTED_FOCUSED_PROVER_ALLOWS=10
SELECTED_FOCUSED_SKEPTIC_ALLOWS=10
SELECTED_FAMILY_BRIDGE_ALLOWS=10
SELECTED_PROOF_DEBT_ALLOWS=10
SELECTED_META_REVIEW_ALLOWS=10
UNSELECTED_FOCUSED_PROVER_BLOCKS=10
UNSELECTED_FOCUSED_SKEPTIC_BLOCKS=10
UNSELECTED_FAMILY_BRIDGE_BLOCKS=10
UNSELECTED_PROOF_DEBT_BLOCKS=10
UNSELECTED_META_REVIEW_BLOCKS=10
EXACT_FALSIFICATION_SELECTED_ALLOWS=10
EXACT_FALSIFICATION_UNSELECTED_ALLOWS=10
UNRELATED_RECOVERY_TASK_LEAKS=0
POST_RESTORE_RECOVERY_TASK_LEAKS=0
DUPLICATE_RECOVERY_TASKS=0
TASK_LEASE_LEAKS=0
RESULT=PASS
---------------------------------------------------------------
```

`EXACT_FALSIFICATION` is the explicit cross-family exception for an existing exact
target. It still cannot bypass new-target capacity or focused new-target quota. Explicit
`unscoped-bridge` classification also remains unscoped even when a family can be
derived, preventing accidental promotion to a family-repair action.

### Deferred lifecycle verification

```text
CAPACITY REACTIVATION DIAGNOSTIC
---------------------------------------------------------------
ACTIVE_TARGETS_AT_CAPACITY=8
CAPACITY_DEFERRED_RECORDS=1
CAPACITY_RELEASE_EVENTS=1
REACTIVATED_AFTER_RELEASE=1
ACTIVE_TARGETS_AFTER_REACTIVATION=8
DUPLICATE_REACTIVATIONS=0
DUPLICATE_REACTIVATION_TASKS=0
GLOBAL_DEBT_FALSE_DECREASES=0
GLOBAL_DEBT_BEFORE_REACTIVATION=19.5575
GLOBAL_DEBT_AFTER_REACTIVATION=19.5575
RESULT=PASS
---------------------------------------------------------------
```

```text
FOCUSED DEFERRED REACTIVATION DIAGNOSTIC
---------------------------------------------------------------
FOCUSED_DEFERRED_RECORDS=1
RESTORE_ROUND=2
POST_COOLDOWN_REACTIVATED=1
POST_RESTORE_REACTIVATION_LOSSES=0
POST_RESTORE_DUPLICATE_REACTIVATIONS=0
POST_RESTORE_DUPLICATE_TASKS=0
GLOBAL_DEBT_FALSE_DECREASES=0
UNAUTHORIZED_FACT_PROMOTIONS=0
UNAUTHORIZED_CLAIM_VERIFICATIONS=0
MAIN_GOAL_CLOSURES=0
ROOT_HASH_CHANGES=0
NEGATIVE_REGISTRY_HASH_CHANGES=0
RESULT=PASS
---------------------------------------------------------------
```

Injected failures after the graph transition, after lease acquisition, and after pending
task insertion all roll back cleanly:

```text
DEFERRED REACTIVATION ATOMICITY DIAGNOSTIC
GRAPH_PARTIAL_REACTIVATIONS=0
LEDGER_PARTIAL_REACTIVATIONS=0
TASK_LEASE_LEAKS=0
PENDING_TASK_LEAKS=0
DUPLICATE_RETRY_TASKS=0
RESULT=PASS
```

The 12-round production lifecycle, including a real restore at round 6, reported:

```text
DEFERRED EXPANSION LIFECYCLE DIAGNOSTIC
----------------------------------------------------------------
ROUNDS=12
RESTORE_ROUND=6
CAPACITY_DEFERRED=1
FOCUSED_RECOVERY_DEFERRED=1
REACTIVATED_AFTER_CAPACITY_RELEASE=1
REACTIVATED_AFTER_COOLDOWN=1
SATISFIED_BY_ACTIVE_TARGET=1
RETIRED_TERMINAL_TARGETS=1
EARLY_FOCUSED_REACTIVATION_LEAKS=0
DUPLICATE_REACTIVATIONS=0
DUPLICATE_REACTIVATION_TASKS=0
TASK_LEASE_LEAKS=0
POST_RESTORE_REACTIVATION_LOSSES=0
POST_RESTORE_STATUS_CHANGES=0
POST_RESTORE_DUPLICATE_TASKS=0
GLOBAL_DEBT_FALSE_DECREASES=0
RAW_OCCURRENCE_LOSSES=0
CANONICAL_TARGET_LOSSES=0
DIRECT_FACT_PROMOTIONS=0
DIRECT_CLAIM_VERIFICATIONS=0
DIRECT_NEGATIVE_REGISTRATIONS=0
MAIN_GOAL_CLOSURES=0
ROOT_HASH_CHANGES=0
NEGATIVE_REGISTRY_HASH_CHANGES=0
ATTEMPT_ARTIFACT_LEDGER_HASH_CHANGES=0
CLAIM_LIFECYCLE_HASH_CHANGES=0
RESEARCH_CHECKPOINT_LEDGER_HASH_CHANGES=0
CANONICALIZATION_REGISTRY_UNAUTHORIZED_CHANGES=0
DEFERRED_HASH_BEFORE_RESTORE=0e243e97d1b7f76e4708a934c7caff05eb04de84fd7af517ffd7883329d4222f
DEFERRED_HASH_AFTER_RESTORE=0e243e97d1b7f76e4708a934c7caff05eb04de84fd7af517ffd7883329d4222f
CONVERGENCE_HASH_BEFORE_RESTORE=3b0473274360e5928d085b7f89295f10a28e55fabc950f728c441d9c31930279
CONVERGENCE_HASH_AFTER_RESTORE=3b0473274360e5928d085b7f89295f10a28e55fabc950f728c441d9c31930279
RESULT=PASS
----------------------------------------------------------------
```

### v10 to v11 migration

Missing v10 lifecycle fields deterministically become `DEFERRED`; migration does not
guess that work was reactivated and makes no Provider call. Two independent migrations
produced the same ledger hash:

```text
LEGACY_RECORDS_MIGRATED=1
LEGACY_RECORDS_DEFAULTED_TO_DEFERRED=1
AUTOMATIC_REACTIVATION_GUESSES=0
ROOT_HASH_CHANGES=0
NEGATIVE_REGISTRY_HASH_CHANGES=0
ATTEMPT_ARTIFACT_LEDGER_HASH_CHANGES=0
CLAIM_LIFECYCLE_HASH_CHANGES=0
RESEARCH_CHECKPOINT_LEDGER_HASH_CHANGES=0
CANONICALIZATION_REGISTRY_HASH_CHANGES=0
CONVERGENCE_SNAPSHOT_HASH_CHANGES=0
PROVIDER_CALLS_DURING_MIGRATION=0
FIRST_MIGRATED_DEFERRED_HASH=9213fa127e776c7df12d5a6138aabda9de3391f45924ee5b81d3b785494d0b81
SECOND_MIGRATED_DEFERRED_HASH=9213fa127e776c7df12d5a6138aabda9de3391f45924ee5b81d3b785494d0b81
RESULT=PASS
```

### Existing issue-005 and earlier-issue regressions

The updated original 20-round convergence test still creates exactly two legitimate
focused-family tasks while suppressing unrelated or duplicate automatic work:

```text
STAGNATION_EPISODES=1
FOCUSED_RECOVERY_ENTRIES=2
GENERIC_EXPANSION_ATTEMPTS=58
GENERIC_EXPANSION_BLOCKS=52
GENERIC_EXPANSION_LEAKS=0
FOCUSED_FAMILY_TASKS_CREATED=2
DUPLICATE_FOCUSED_FAMILY_TASKS=0
UNRELATED_FAMILY_TASKS_CREATED=0
POST_RESTORE_STATE_CHANGES=0
POST_RESTORE_DEFERRED_RECONCILIATIONS=1
CONVERGENCE_HASH_BEFORE_RESTORE=e5da0a353bb2c1cd8c1f5ad876cec965eaa1fc7faf0b876e85844427b40098ca
CONVERGENCE_HASH_AFTER_RESTORE=e5da0a353bb2c1cd8c1f5ad876cec965eaa1fc7faf0b876e85844427b40098ca
RESULT=PASS
```

Explicit regression commands passed with no failures:

- Issue 001: 15 tests (Core 14, Desktop 1).
- Issue 002: 29 tests (Core 18, Desktop 11), including 10 route-widening blocks and
  possible-equivalent quarantine without a permanent hard block.
- Issue 003: 20 tests (Core 13, Desktop 7).
- Issue 004: 37 tests (Contracts 4, Core 16, Server 7, Desktop 10).
- Original issue 005 suites: 61 tests (Core 33, Server 2, Desktop 26).

### Complete verification

The first complete-gate attempt after the production fix correctly failed the unchanged
audited-branch threshold (`84.023324% < 85%`). No threshold was relaxed. The added
`CanonicalSchedulingTransitionTest` exercises the real fail-closed transition branches;
the final audited invariant branch coverage is `85.422741%`.

The final `scripts/verify-all.ps1 -Offline` execution completed with exit code 0 and
included Docker-backed PostgreSQL Testcontainers integration tests:

```text
MathProofMesh Contracts:      48 tests, 0 failures, 0 errors, 0 skipped
MathProofMesh Core:         1032 tests, 0 failures, 0 errors, 0 skipped
MathProofMesh Server:        869 tests, 0 failures, 0 errors, 3 skipped
MathProofMesh Desktop:       111 tests, 0 failures, 0 errors, 1 skipped
MathProofMesh Compatibility: 149 tests, 0 failures, 0 errors, 0 skipped
TOTAL:                      2209 tests, 0 failures, 0 errors, 4 skipped
```

The five PostgreSQL suites ran 21 tests with zero failures/errors/skips:
`JdbcMessageRepositoryIT` (4), `MemoryProofGraphPostgresIT` (4),
`PersistencePostgresIT` (9), `Phase17CheckpointOutboxPerformanceIT` (1), and
`ProviderCallPostgresIT` (3). All integration-test suites together ran 26 tests.

Final gates:

- Coverage PASS: Core line `91.306951%`, Core branch `75.370075%`, audited invariant
  line `93.816769%`, audited invariant branch `85.422741%`; every configured coverage
  gate passed.
- SpotBugs/FindSecBugs PASS: zero findings in all five Java modules.
- Security PASS: 115 dependencies scanned, zero visible findings and zero findings at
  or above CVSS 7; secret scan passed.
- License PASS: 111 components, zero missing and zero unreviewed licenses.
- Source immutability PASS: 401 files and manifest
  `9f3def2bec8cea99d0a18b51fbb5fa8ce53f44a24ce869f6495cac809b7e3770`.
- Existing offline performance artifacts passed; no Python-sidecar threshold changed.
- `FULL VERIFICATION: PASS`.

Generated verification reports, Maven targets, logs, checkpoints, databases, and
Testcontainers data are not included in the patch.

### Protected authority and final state

Relative to `47f1784127fb76a719bd4cf1da97c96da9bf3f03`, all protected issue-001 through
issue-004 production files and issue-005A identity/authority files have an empty diff:

```text
PROTECTED_FILES_NO_DIFF=PASS
PATCH_DIFF_STAT=37 files changed, 4000 insertions(+), 119 deletions(-)
```

No issue-006 through issue-013 production behavior was changed. The patch does not alter
Root Goal authority, permanent Negative Knowledge, Claim review, Research Checkpoint
authority, canonical identity, family authority, canonical debt mathematics, Provider,
Broker, concurrency, Temporal, token/budget policy, retry/failover, or Python-sidecar
performance thresholds.

```text
ISSUE 005 FINAL PATCH DIAGNOSTIC
================================================================
SELECTED_BINDING_GATE_RESULT=PASS
UNSELECTED_FOCUSED_PROVER_LEAKS=0
UNSELECTED_FOCUSED_SKEPTIC_LEAKS=0
UNSELECTED_FAMILY_BRIDGE_LEAKS=0
UNSELECTED_PROOF_DEBT_LEAKS=0
UNSELECTED_META_REVIEW_LEAKS=0
EXACT_FALSIFICATION_POLICY=PASS
POST_RESTORE_RECOVERY_TASK_LEAKS=0

DEFERRED_REACTIVATION_RESULT=PASS
CAPACITY_REACTIVATIONS=1
POST_COOLDOWN_REACTIVATIONS=1
SATISFIED_BY_ACTIVE_TARGET=1
RETIRED_TERMINAL_TARGETS=1
DUPLICATE_REACTIVATIONS=0
DUPLICATE_REACTIVATION_TASKS=0
GLOBAL_DEBT_FALSE_DECREASES=0
POST_RESTORE_REACTIVATION_LOSSES=0

ROOT_HASH_CHANGES=0
NEGATIVE_REGISTRY_HASH_CHANGES=0
ATTEMPT_ARTIFACT_LEDGER_HASH_CHANGES=0
CLAIM_LIFECYCLE_HASH_CHANGES=0
RESEARCH_CHECKPOINT_LEDGER_HASH_CHANGES=0
CANONICALIZATION_AUTHORITY_CHANGES=0

ISSUE_001_REGRESSION=PASS
ISSUE_002_REGRESSION=PASS
ISSUE_003_REGRESSION=PASS
ISSUE_004_REGRESSION=PASS
ISSUE_005_ORIGINAL_REGRESSION=PASS

PROTECTED_FILES_NO_DIFF=PASS
FULL_VERIFICATION=PASS
WORKTREE_CLEAN=true
ISSUE_005_STATUS=CLOSED
================================================================
```
