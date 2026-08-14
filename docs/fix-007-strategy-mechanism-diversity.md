# Issue 007: Strategy Mechanism Diversity and Critical-Claim Preflight

## 1. Status and Git provenance

- Status: `CLOSED` after all gates recorded below passed.
- Branch: `fix/007-strategy-mechanism-diversity`
- Baseline branch: `java`
- Baseline commit: `d42c896ef353259707f017d7e2a90dbd706e28b7`
- Issue 006 prerequisite commit: `c73803297396057c52d4b6ef193ed00240fee9d7`
- Implementation commit: `c1b64e77d1534446eeaa7b67063b15313d3fc297`
- Baseline checkpoint schema: `12`
- Resulting checkpoint schema: `13`

The work stayed on the Issue 007 branch. It did not modify `main`, commit directly to
`java`, or start Issue 008.

## 2. Production entry-point audit

| Surface | Production location | Result |
|---|---|---|
| Initial candidate generation | `DesktopSolveCoordinator.generateAndAdmitStrategies` | Provider artifact remains raw input; selection now uses the Issue 007 pipeline |
| Route widening | `DesktopSolveCoordinator.widenRoutes` | Negative gate plus whole-active-portfolio mechanism/common-mode gate |
| Strategy Archive write | `applyStrategyPortfolioAtomically` | Occurs only after one committed global decision |
| Blueprint write | `applyStrategyPortfolioAtomically` | Occurs only for selected candidates |
| Goal Link write | `applyStrategyPortfolioAtomically` | Occurs only for selected candidates |
| Admitted Strategy write | `applyStrategyPortfolioAtomically` | Replaced atomically with the selected portfolio |
| Route creation | `applyStrategyPortfolioAtomically`, then guarded widening | Occurs after selection and all deterministic gates |
| Model success prior | `StrategyFeasibilityCalibrator` | Contribution is capped at 10 percent of total score |
| Legacy text selector | `SparseTopologyRouter.selectDiverseStrategies` | Kept for compatibility, but no production call remains in initial portfolio or widening |

The active flow is:

```text
Raw Strategy Candidates
-> Blueprint Compilation
-> Server-owned Hard Mechanism Signature
-> Non-authoritative Soft Mechanism Profile
-> Critical-Claim Preflight
-> Server-calibrated Feasibility
-> Global Portfolio Optimization
-> Optional One-shot Gap Replenishment
-> Atomic Strategy and Route Admission
```

## 3. Pre-fix behavioral evidence

The four black-box tests were first executed against the baseline production behavior.
They failed behaviorally, without depending on any new Issue 007 API:

```text
TITLE DIVERSITY ILLUSION
RAW_CANDIDATES=6
TRUE_MECHANISMS=1
EXPECTED_SELECTED=1
ACTUAL_SELECTED=4

UNRESOLVED COMMON MODE
UNRESOLVED_COMMON_MODE_GROUP_SIZE=5
EXPECTED_MAX_SELECTED_FROM_GROUP=1
ACTUAL_SELECTED_FROM_GROUP=2

TRUSTED COUNTEREXAMPLE IGNORED
VERIFIED_REFUTED_REQUIRED_CLAIMS=1
EXPECTED_ADMISSIONS=0
ACTUAL_ADMISSIONS=1

MODEL PRIOR OVERRIDE
MODEL_PRIOR_A=0.99
MODEL_PRIOR_B=0.55
EXPECTED_FIRST_SELECTED=B
ACTUAL_FIRST_SELECTED=A
```

## 4. Implemented semantics

### 4.1 Hard signature and soft profile

`StrategyMechanismAnalyzer` builds a deterministic hard signature from the bound problem and
root hashes, canonical targets, required-claim semantic keys, object roles, representation,
dependency DAG shape, proof transformation, and falsification contract. It excludes title,
strategy ID, route ID, agent ID, estimated success/cost, and free-form tag order.

`StrategyMechanismProfile` records broad mechanism primitives only as non-authoritative
coverage information. It cannot establish equivalence, Claim truth, Negative Knowledge, or
proof closure. `SAME_STRUCTURAL_MECHANISM` is produced only by the hard signature.

### 4.2 Critical Claim authority

`CriticalClaimKeyCompiler` binds each Claim to the problem hash, normalized statement,
assumptions, ordered quantifiers, variable bindings, scope, polarity, and required/supporting
necessity. `TrustedStrategyPreflightEvidenceSource` reads only existing authoritative sources:

- Permanent Negative Knowledge and verified counterexamples from Issue 002.
- Verified Claim and Fact projections from Issue 003.
- Existing trusted evidence references.

Model-reported `verified`, confidence, preflight status, and score never create authority.
`POSSIBLE_EQUIVALENT` remains quarantine rather than permanent equivalence. Bounded
non-refutation remains exploratory evidence and never promotes a Fact.

Required Claims that are verified-refuted, permanently blocked, or in preflight error are hard
rejected. A refuted supporting Claim marks the candidate for regeneration, without entering the
Issue 008 Claim-repair lifecycle.

### 4.3 Calibration, common mode, and global selection

The server score combines root alignment, blueprint completeness, verified Claim coverage,
mechanism novelty, complementarity, common-mode penalty, and cost. An all-unresolved required
Claim set has a hard upper bound of `0.45`. The model prior is capped both by configuration and
by a 10 percent ratio to the final score.

Common-mode risk applies only when candidates share the same unresolved required Claim.
Sharing the root goal, definitions, verified facts, verified lemmas, supporting Claims, or merely
similar text does not consume the common-mode cap.

`StrategyPortfolioOptimizer` performs a deterministic bounded global search. It enforces one
candidate per hard structural signature and one candidate per unresolved required Claim group,
while considering the entire active portfolio. It no longer performs greedy title-distance
selection.

### 4.4 One-shot replenishment and atomicity

If the qualified first portfolio is undersized, `PortfolioReplenishmentLedger` permits exactly
one `portfolio_gap_replenishment` provider call for that episode. The prompt contains only the
missing structural signatures and forbidden unresolved Claim keys. An undersized result after
that call is accepted rather than entering an unbounded generation loop.

Candidate, mechanism, preflight, portfolio, archive, blueprint, goal-link, admitted-strategy,
route, Proof Graph, pending-task, convergence, deferred-expansion, and checkpoint-ledger state
are snapshotted before active commit. A caught failure restores all projections. A simulated
hard process termination before the one authoritative apply checkpoint leaves the durable state
at the complete pre-apply frontier, so restore and retry produce one receipt and one route set.

### 4.5 Restore and migration

Checkpoint schema `12 -> 13` adds:

- `StrategyCandidateSnapshot`
- `StrategyMechanismSnapshot`
- `StrategyPreflightSnapshot`
- `StrategyPortfolioSnapshot`
- `PortfolioReplenishmentSnapshot`

V12 active strategies are retained as `LEGACY_ACTIVE`. Their hard signatures are rebuilt
deterministically, their soft profiles become `UNKNOWN`, and they are not retroactively
preflighted, rejected, regenerated, or routed again. New candidates use the full gate.

## 5. Domain neutrality

The new Core production package has zero references to `GreedyGcd`,
`isGreedyGcdSequenceProblem`, `GreedyGcdNegativeKnowledgeSeeds`, the original initial values,
prime-divisor shortcuts, hitting sets, support reduction, or periodicity.

The generic corpus covers finite graphs, linear algebra, finite sets/maps, and synthetic Claim
DAGs. Optional legacy GCD guidance is isolated in
`GreedyGcdDomainStrategySeedProvider` under Desktop, activates only after an exact family
detector, and is not a dependency of the Issue 007 Core or its tests.

## 6. Modified files

### Contracts

- `CriticalClaimPreflightPlan.java`: bounded non-authoritative preflight request contract.
- `StrategyPreflightPlan.java`: candidate-bound collection of preflight plans.

### Core production

The new `strategydiversity` package contains:

- Mechanism identity/profile: `StrategyMechanismAnalyzer`, `StrategyMechanismSignature`,
  `StrategyMechanismProfile`, `StrategyMechanismPrimitive`, `StrategyMechanismRelation`,
  `StrategyMechanismRegistry`, `StrategyMechanismSnapshot`.
- Critical Claim preflight: `CriticalClaimSemanticKey`, `CriticalClaimKeyCompiler`,
  `CriticalClaimPreflightSpec`, `CriticalClaimPreflightEvidence`,
  `CriticalClaimPreflightResult`, `CriticalClaimPreflightStatus`,
  `StrategyPreflightEvidenceSource`, `TrustedStrategyPreflightEvidenceSource`,
  `StrategyCriticalClaimPreflight`, `StrategyPreflightReport`, `StrategyPreflightRegistry`,
  `StrategyPreflightSnapshot`.
- Candidate and common-mode state: `StrategyCandidateStatus`, `StrategyCandidateRecord`,
  `StrategyCandidateLedger`, `StrategyCandidateSnapshot`, `CommonModeRiskRecord`,
  `CommonModeRiskRegistry`.
- Calibration and portfolio: `StrategyDiversityConfig`, `StrategyFeasibilityCalibrator`,
  `StrategyFeasibilityScore`, `StrategyPortfolioCandidate`, `StrategyPortfolioConstraint`,
  `StrategyPortfolioDecision`, `StrategyPortfolioAuditEvent`, `StrategyPortfolioOptimizer`,
  `StrategyPortfolioRegistry`, `StrategyPortfolioSnapshot`, `StrategyPortfolioApplyPlan`,
  `StrategyPortfolioApplyReceipt`.
- Replenishment and guidance: `PortfolioReplenishmentLedger`,
  `PortfolioReplenishmentSnapshot`, `GenericStrategyGenerationPolicy`,
  `DomainStrategySeedProvider`, `StrategySeed`, `StrategySemanticNormalizer`.

### Server and Desktop production

- `PromptCatalog.java`: domain-neutral strategy, required/supporting Claim, falsification, and
  registered-contract preflight guidance.
- `StrategyPreflightPlanValidator.java`: rejects code, commands, unknown contracts, duplicate or
  cross-problem plans, and model authority fields.
- `DesktopSolveCoordinator.java`: production pipeline, one-shot replenishment, atomic commit,
  restore, and whole-portfolio widening gate.
- `DesktopSolveCheckpoint.java`: schema 13 and five Issue 007 snapshots.
- `StrategyPortfolioFailurePoint.java`: deterministic exception and hard-crash injection points.
- `GreedyGcdDomainStrategySeedProvider.java`: isolated optional legacy domain extension.

### Tests

- Core: the 18 classes under `strategydiversity`, including signature/relation/profile,
  semantic-key, authority, bounded non-refutation, calibration, optimizer, snapshot,
  generalization, state-owner, and trusted-adapter coverage.
- Server: `StrategyPreflightPlanContractsTest`, `StrategyPreflightPlanPromptTest`,
  `StrategyPortfolioGapReplenishmentPromptTest`, and `GenericStrategyGenerationPolicyTest`.
- Desktop: the four pre-fix black boxes plus production mechanism/preflight/global selection,
  replenishment, atomicity, widening, 20-round restore, hard-crash, domain-neutral prompt,
  V12 migration, protected-authority, and production harness tests.

## 7. Issue 007 specialized tests

| Suite | Tests | Failures | Errors | Skipped | Result |
|---|---:|---:|---:|---:|---|
| Core | 28 | 0 | 0 | 0 | PASS |
| Server | 5 | 0 | 0 | 0 | PASS |
| Desktop | 15 | 0 | 0 | 0 | PASS |

The fixed black-box outputs are:

```text
TITLE_ONLY: ACTUAL_SELECTED=1
COMMON_MODE: ACTUAL_SELECTED_FROM_GROUP=1
TRUSTED_REFUTATION: ACTUAL_ADMISSIONS=0
CALIBRATED_SELECTION: ACTUAL_FIRST_SELECTED=B
```

Gap replenishment output:

```text
REPLENISHMENT_REQUESTS=1
REPLENISHMENT_PROVIDER_CALLS=1
REPLENISHMENT_CANDIDATES=2
SECOND_REPLENISHMENT_CALLS=0
POST_RESTORE_REPLENISHMENT_CALLS=0
DUPLICATE_REPLENISHMENT_CANDIDATES=0
DUPLICATE_ROUTE_CREATIONS=0
RESULT=PASS
```

Atomic admission output:

```text
PARTIAL_ARCHIVE_WRITES=0
PARTIAL_BLUEPRINT_WRITES=0
PARTIAL_GOAL_LINK_WRITES=0
PARTIAL_ADMITTED_STRATEGIES=0
PARTIAL_ROUTE_CREATIONS=0
PARTIAL_PROOF_GRAPH_WRITES=0
TASK_LEASE_LEAKS=0
```

## 8. Twenty-round diagnostic

```text
STRATEGY MECHANISM PORTFOLIO DIAGNOSTIC
ROUNDS=20
RESTORE_ROUND=10
RAW_STRATEGY_CANDIDATES=160
BLUEPRINT_COMPILED_CANDIDATES=160
PREFLIGHTED_CANDIDATES=160
VERIFIED_REFUTED_REQUIRED_CLAIMS=20
REFUTED_REQUIRED_STRATEGY_ADMISSIONS=0
PERMANENT_NEGATIVE_CONFLICT_ADMISSIONS=0
TITLE_ONLY_DIVERSITY_ADMISSIONS=0
SAME_MECHANISM_MULTI_ADMISSIONS=0
UNRESOLVED_COMMON_MODE_CAP_VIOLATIONS=0
SHARED_VERIFIED_FACT_FALSE_COMMON_MODE=0
MODEL_SUCCESS_OVERRIDE_EVENTS=0
BOUNDED_NON_REFUTATION_FACT_PROMOTIONS=0
DISTINCT_MECHANISM_PORTFOLIOS=20
PORTFOLIO_SIZE_TARGET=4
PORTFOLIO_SIZE_SHORTFALLS=0
DUPLICATE_SELECTED_STRATEGIES=0
DUPLICATE_ROUTE_CREATIONS=0
REJECTED_STRATEGY_ACTIVE_STATE_LEAKS=0
POST_RESTORE_CANDIDATE_LOSSES=0
POST_RESTORE_PREFLIGHT_REPLAYS=0
POST_RESTORE_PORTFOLIO_CHANGES=0
POST_RESTORE_DUPLICATE_ROUTES=0
ROOT_HASH_CHANGES=0
NEGATIVE_REGISTRY_HASH_CHANGES=0
ATTEMPT_ARTIFACT_LEDGER_HASH_CHANGES=0
CLAIM_LIFECYCLE_HASH_CHANGES=0
RESEARCH_CHECKPOINT_LEDGER_HASH_CHANGES=0
CANONICALIZATION_REGISTRY_HASH_CHANGES=0
CONVERGENCE_STATE_HASH_CHANGES=0
SEMANTIC_PIVOT_LEDGER_HASH_CHANGES=0
DIRECT_FACT_PROMOTIONS=0
DIRECT_CLAIM_VERIFICATIONS=0
DIRECT_NEGATIVE_REGISTRATIONS=0
MAIN_GOAL_CLOSURES=0
RESULT=PASS
```

Final full-verification restore hashes:

```text
CANDIDATE_HASH_BEFORE_RESTORE=70cee7315237354726fd206ffa0d971f73f466d0e612aae528586e9ddfda9c98
CANDIDATE_HASH_AFTER_RESTORE=70cee7315237354726fd206ffa0d971f73f466d0e612aae528586e9ddfda9c98
MECHANISM_HASH_BEFORE_RESTORE=391d98136c8c499c005e7ee3a454c4e29100aaba0591b0221febf3f6b46faa36
MECHANISM_HASH_AFTER_RESTORE=391d98136c8c499c005e7ee3a454c4e29100aaba0591b0221febf3f6b46faa36
PORTFOLIO_HASH_BEFORE_RESTORE=dd7086a5af43efeb4daca5175d16b2eb0fe65de81bb399e692ec53253c232cd5
PORTFOLIO_HASH_AFTER_RESTORE=dd7086a5af43efeb4daca5175d16b2eb0fe65de81bb399e692ec53253c232cd5
```

The mechanism hash includes generated projection identities and can differ between fresh test
processes; the asserted invariant is exact equality across serialization and restore within the
same run.

## 9. Regression and full verification

All explicit Issue 001-006 suites passed before the final full gate:

| Issue | Explicit regression result |
|---|---|
| 001 Root Goal | Core 14 and Desktop 1, PASS |
| 002 Negative Knowledge | Core 18 and Desktop 11, PASS |
| 003 Claim/Attempt separation | Core 13 and Desktop 7, PASS |
| 004 Research checkpoints | Contracts 4, Core 16, Server 4, Desktop 9, PASS |
| 005 Canonicalization/convergence | Core 35 and Desktop 14, PASS |
| 006 Semantic Pivot | Core 22, Server 4, Desktop 14, PASS |

Final `verify-all.ps1 -Offline` result:

- `FULL VERIFICATION: PASS`.
- Maven module total: 2322 tests, 0 failures, 0 errors; 4 pre-existing conditional skips.
- All nine critical scenario groups passed with zero critical skips.
- PostgreSQL Testcontainers passed:
  `JdbcMessageRepositoryIT`, `MemoryProofGraphPostgresIT`, `PersistencePostgresIT`,
  `Phase17CheckpointOutboxPerformanceIT`, and `ProviderCallPostgresIT`.
- Core branch coverage: `7380 / 9802 = 75.290757%`, above the unchanged 75% gate.
- SpotBugs and FindSecBugs: 0 findings in all five Java modules.
- OWASP dependency gate: 115 dependencies scanned, 0 visible findings, 0 findings at CVSS 7+.
- License gate: 111 components, 0 missing and 0 unreviewed licenses.
- Secret scan: 1454 files, 0 findings.
- Frozen original source: 401 files, manifest
  `9f3def2bec8cea99d0a18b51fbb5fa8ce53f44a24ce869f6495cac809b7e3770`, PASS.

## 10. Protected boundaries and closure

The baseline-to-implementation diff contains zero Issue 001-006 protected production files.
Root Goal, Negative Knowledge authority, Claim lifecycle, Research Checkpoint authority,
canonical Proof Graph identity/convergence, Semantic Pivot, Provider routing, concurrency,
Token/budget, Temporal, and Python Sidecar behavior were not changed.

```text
ISSUE 007 STRATEGY PORTFOLIO DIAGNOSTIC
DOMAIN_NEUTRAL_PRODUCTION_CORE=PASS
NUMBER_THEORY_DEPENDENCIES_IN_ISSUE007_CORE=0
PROTECTED_FILES_NO_DIFF=PASS
ISSUE_001_REGRESSION=PASS
ISSUE_002_REGRESSION=PASS
ISSUE_003_REGRESSION=PASS
ISSUE_004_REGRESSION=PASS
ISSUE_005_REGRESSION=PASS
ISSUE_006_REGRESSION=PASS
FULL_VERIFICATION=PASS
ISSUE_007_STATUS=CLOSED
```

Issue 008 has not been started.
