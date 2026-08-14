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
