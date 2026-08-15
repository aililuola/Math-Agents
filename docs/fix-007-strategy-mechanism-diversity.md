# Issue 007: Strategy Mechanism Diversity and Critical-Claim Preflight

## 1. Status and Git provenance

- Status: `CLOSED` after the initial implementation, structured-mechanism hardening, and final typed-operation/context/preflight recovery follow-up passed all gates below.
- Branch: `fix/007-strategy-mechanism-diversity`
- Baseline branch: `java`
- Baseline commit: `d42c896ef353259707f017d7e2a90dbd706e28b7`
- Issue 006 prerequisite commit: `c73803297396057c52d4b6ef193ed00240fee9d7`
- Initial implementation commit: `c1b64e77d1534446eeaa7b67063b15313d3fc297`
- Follow-up hardening commit: `80c60634ab98d715a68119fabc621db36abfab38`
- Final closure commit: `34dfa1b`
- Baseline checkpoint schema: `12`
- Resulting checkpoint schema: `15`

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
root hashes, Issue 005 canonical target IDs, required-claim semantic keys, bounded object roles,
bounded representation kind, the complete directed Blueprint topology, server-validated typed
operation declarations, and registered falsification-contract kinds. The hard path no longer
calls `classifyOperations(strategy.coreIdea())`. Raw `coreIdea`, `bottleneck`, expected lemma
prose, falsification prose, and prerequisite prose are not serialized into the hard hash.

`MechanismOperationDeclaration` binds a fixed `MechanismOperationKind` to existing Blueprint
inputs and outputs. The server resolves bounded selectors, validates node existence and directed
reachability, rejects duplicate operation IDs and contradictory declarations on the same edge,
and compiles only bounded `ProofOperationKind` values. An absent or `UNKNOWN` graph is marked
unknown and receives a conservative candidate-local identity; it cannot hard-merge two
strategies. Title, route, agent, model score, and tag order remain excluded.

`StrategyMechanismProfile` may still classify prose for non-authoritative coverage information.
It cannot establish equivalence, Claim truth, Negative Knowledge, proof closure, or a hard
merge. `SAME_STRUCTURAL_MECHANISM` is produced only when both typed operation graphs are known
and their server-compiled hard signatures match.

### 4.2 Critical Claim authority

`CriticalClaimKeyCompiler` binds each Claim to the problem hash, normalized statement,
assumptions, ordered quantifiers, variable bindings, scope, polarity, and required/supporting
necessity. Production no longer assigns one global context to every Claim.
`CriticalClaimContextBinding` and `CriticalClaimContextCompiler` bind each Claim to its own
server-compiled Blueprint node, incoming dependency edges, local assumptions, ordered
quantifiers, variable bindings, scope limitations, and polarity, layered over the immutable Root
Goal and strategy prerequisites. Context-only Claim nodes remain auditable Blueprint metadata
and are not duplicated as Proof Graph obligations. Exact alpha-renaming is supported, but
conditional, differently quantified, differently scoped, or opposite-polarity facts do not
support the Claim. `TrustedStrategyPreflightEvidenceSource` reads only existing authoritative
sources:

- Permanent Negative Knowledge and verified counterexamples from Issue 002.
- Verified Claim and Fact projections from Issue 003.
- Existing trusted evidence references.

Model-reported `verified`, confidence, preflight status, and score never create authority.
`POSSIBLE_EQUIVALENT` remains quarantine rather than permanent equivalence. Bounded
non-refutation remains exploratory evidence and never promotes a Fact.

Required Claims that are verified-refuted, permanently blocked, or in preflight error are hard
rejected. A refuted supporting Claim marks the candidate for regeneration, without entering the
Issue 008 Claim-repair lifecycle.

### 4.3 Registered computation preflight

Candidates with a registered typed calculation contract now traverse the real
`strategy_preflight_plan` provider stage. The returned plan must equal the server-compiled
claim-to-contract binding and pass `StrategyPreflightPlanValidator`. The existing Java
`ComputationBroker` executes the bounded read-only falsification request, audits independent
replay, and records a durable `StrategyPreflightExecutionRecord` before admission. Unknown
contracts are `UNTESTABLE`; invalid inputs are `ERROR`; bounded non-refutation is never upgraded
to verified support. The durable frontier is explicitly typed as `RESERVED`, `RUNNING`,
`RESULT_DURABLE`, `COMPLETED`, or `ABORTED` and binds execution ID, action key, typed input hash,
artifact reference, and replay hash. An empty reservation can safely execute once after restore;
a durable result rolls forward without recomputation; an uncertain running frontier is
quarantined rather than converted into a false mathematical rejection. Completed executions are
never replayed, and arbitrary code, shell commands, dependencies, and Docker images remain
forbidden.

### 4.4 Calibration, common mode, and global selection

The server score combines root alignment, blueprint completeness, verified Claim coverage,
mechanism novelty, complementarity, common-mode penalty, and cost. An all-unresolved required
Claim set has a hard upper bound of `0.45`. The model prior is capped both by configuration and
by a 10 percent ratio to the final score.

Common-mode risk applies only when candidates share the same unresolved required Claim.
Sharing the root goal, definitions, verified facts, verified lemmas, supporting Claims, or merely
similar text does not consume the common-mode cap.

`StrategyPortfolioOptimizer` performs a deterministic bounded global search. It enforces one
candidate per hard structural signature and one candidate per unresolved required Claim group,
while considering the entire active portfolio. It first removes candidates below the configured
feasibility, Blueprint-completeness, or required-evidence floors. The former fixed `+10` reward
per candidate is gone, so an undersized qualified portfolio is preserved instead of being padded
with low-quality routes. It no longer performs greedy title-distance selection.

### 4.5 One-shot replenishment and atomicity

If the qualified first portfolio is undersized, `PortfolioReplenishmentLedger` permits exactly
one `portfolio_gap_replenishment` provider call for that episode. The prompt contains only the
missing structural signatures and forbidden unresolved Claim keys. An undersized result after
that call is accepted rather than entering an unbounded generation loop.

Candidate, mechanism, preflight, portfolio, archive, blueprint, goal-link, admitted-strategy,
route, Proof Graph, pending-task, convergence, deferred-expansion, and checkpoint-ledger state
are snapshotted before active commit. A caught failure restores all projections. A simulated
hard process termination before the one authoritative apply checkpoint leaves the durable state
at the complete pre-apply frontier, so restore and retry produce one receipt and one route set.

### 4.6 Restore and migration

Checkpoint schema `12 -> 13` added:

- `StrategyCandidateSnapshot`
- `StrategyMechanismSnapshot`
- `StrategyPreflightSnapshot`
- `StrategyPortfolioSnapshot`
- `PortfolioReplenishmentSnapshot`

V12 active strategies are retained as `LEGACY_ACTIVE`. Their hard signatures are rebuilt
deterministically, their soft profiles become `UNKNOWN`, and they are not retroactively
preflighted, rejected, regenerated, or routed again. New candidates use the full gate.

Checkpoint schema `13 -> 14` extends `StrategyPreflightSnapshot` with immutable provider plans
and exactly-once execution records. Missing V13 fields restore as empty; existing Issue 007
candidate, mechanism, portfolio, and route projections remain unchanged.

Checkpoint schema `14 -> 15` upgrades each execution record to the typed durable frontier above.
Legacy `started` records migrate conservatively to `RUNNING` quarantine; legacy `completed`
records derive replayable artifact and evidence hashes and keep their completed authority. Newer
weaker snapshots cannot downgrade a durable or completed frontier.

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
- `MechanismOperationKind.java` and `MechanismOperationDeclaration.java`: bounded typed operation
  vocabulary and Blueprint bindings.
- `CriticalClaimContextBinding.java`: Claim-local assumptions, quantifiers, variables, scope,
  polarity, and Blueprint-node sidecar.
- `StrategyCard.java`: backward-compatible optional typed operation and Claim-context metadata.

### Core production

The new `strategydiversity` package contains:

- Mechanism identity/profile: `StrategyMechanismAnalyzer`, `StrategyMechanismSignature`,
  `StrategyMechanismProfile`, `StrategyMechanismPrimitive`, `StrategyMechanismRelation`,
  `StrategyMechanismRegistry`, `StrategyMechanismSnapshot`.
- Critical Claim preflight: `CriticalClaimSemanticKey`, `CriticalClaimKeyCompiler`,
  `CriticalClaimContext`, `CriticalClaimContextCompiler`,
  `CriticalClaimPreflightSpec`, `CriticalClaimPreflightEvidence`,
  `CriticalClaimPreflightResult`, `CriticalClaimPreflightStatus`,
  `StrategyPreflightEvidenceSource`, `TrustedStrategyPreflightEvidenceSource`,
  `StrategyCriticalClaimPreflight`, `StrategyPreflightPlanCompiler`,
  `StrategyPreflightExecutionRecord`, `StrategyPreflightExecutionStatus`,
  `StrategyPreflightReport`, `StrategyPreflightRegistry`, `StrategyPreflightSnapshot`.
- Structured hard identity: `StructuredRepresentationKind`, `ProofOperationKind`,
  `MechanismOperationNode`, and `StrategyMechanismStructureCompiler`.
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
- `DesktopSolveCheckpoint.java`: schema 15 and crash-recoverable Issue 007 plan/execution state.
- `StrategyPortfolioFailurePoint.java`: deterministic exception and hard-crash injection points.
- `StrategyPreflightFailurePoint.java`: reservation/result-frontier process-crash injection points.
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
| Core | 47 | 0 | 0 | 0 | PASS |
| Server | 5 | 0 | 0 | 0 | PASS |
| Desktop | 22 | 0 | 0 | 0 | PASS |

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
- Java module total: 2216 tests, 0 failures, 0 errors; 4 pre-existing conditional skips.
- All nine critical scenario groups passed with zero critical skips.
- PostgreSQL Testcontainers passed:
  `JdbcMessageRepositoryIT`, `MemoryProofGraphPostgresIT`, `PersistencePostgresIT`,
  `Phase17CheckpointOutboxPerformanceIT`, and `ProviderCallPostgresIT`.
- Contracts adjusted branch coverage: `1991 / 2282 = 87.248028%`, above the unchanged 85% gate.
- Core branch coverage: `5188 / 6911 = 75.068731%`, above the unchanged 75% gate.
- SpotBugs and FindSecBugs: 0 findings in all five Java modules.
- OWASP dependency gate: 115 dependencies scanned, 0 visible findings, 0 findings at CVSS 7+.
- License gate: 111 components, 0 missing and 0 unreviewed licenses.
- Secret scan: 1483 files, 0 findings.
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

## 11. Structured-mechanism follow-up verification

The follow-up started from documentation commit `49973ac` and closed four concrete gaps in the
initial Issue 007 implementation:

1. Hard mechanism identity now uses canonical target IDs, bounded representations and roles,
   complete directed topology, bounded operation nodes, and context-bound required Claim keys.
   Natural-language paraphrases are not hashed directly.
2. Claim keys and trusted evidence now bind assumptions, ordered quantifiers, variable bindings,
   scope limitations, polarity, and necessity. Only exact or trusted alpha-equivalent evidence
   can become `VERIFIED_SUPPORTED`.
3. `strategy_preflight_plan` now executes in the real Desktop production chain through existing
   registered Java computation contracts, with durable exactly-once plan/execution state.
4. Portfolio quality floors run before global optimization; low-feasibility candidates cannot
   suppress one-shot gap replenishment or pad a requested portfolio.

The pre-fix follow-up tests first exposed these behaviors: directed chain and fork Blueprints had
the same topology hash; ordinary mathematical paraphrases produced different hard hashes; a
conditional Fact supported an unconditional Claim; a registered falsification contract was not
executed; and feasibility scores `0.01`/`0.02` were selected to fill the requested count.

Final focused diagnostics were:

```text
STRUCTURED MECHANISM AND PREFLIGHT FOLLOW-UP
PARAPHRASED_SAME_MECHANISM_CANDIDATES=6
PARAPHRASED_SAME_MECHANISM_ADMISSIONS=1
PARAPHRASE_BYPASS_LEAKS=0

CONDITIONAL_FACT_FALSE_SUPPORTS=0
QUANTIFIER_SCOPE_FALSE_SUPPORTS=0
CROSS_SCOPE_COMMON_MODE_COLLISIONS=0
PARAPHRASED_REQUIRED_CLAIM_BYPASSES=0

PREFLIGHT_PLANS_GENERATED=1
PREFLIGHT_PLAN_PROVIDER_CALLS=1
REGISTERED_CONTRACT_EXECUTIONS=1
VERIFIED_COUNTEREXAMPLES_FOUND=1
REFUTED_STRATEGY_ADMISSIONS=0
POST_RESTORE_PREFLIGHT_EXECUTIONS=0
ARBITRARY_CODE_EXECUTIONS=0

BOUNDED_NON_REFUTATIONS=1
BOUNDED_NON_REFUTATION_VERIFIED_SUPPORTS=0
INDEPENDENTLY_REPLAYED_COUNTEREXAMPLES=1
UNREPLAYED_COUNTEREXAMPLE_AUTHORITIES=0

SELECTED=2
LOW_FEASIBILITY_ADMISSIONS=0
PORTFOLIO_SHORTFALL=2
REPLENISHMENT_REQUESTS=1
SECOND_REPLENISHMENT_CALLS=0
FINAL_SELECTED=2
RESULT=PASS
```

The final 20-round restore hashes were:

```text
CANDIDATE_HASH_BEFORE_RESTORE=88770460b7ffe1d1db3ab6032c99cafe80d56495f342b3860fe1f56e363e4208
CANDIDATE_HASH_AFTER_RESTORE=88770460b7ffe1d1db3ab6032c99cafe80d56495f342b3860fe1f56e363e4208
MECHANISM_HASH_BEFORE_RESTORE=f260bf78ac0b19e0360795cad2ee01a019d3a2535e3aaedc2473492bfb593a2f
MECHANISM_HASH_AFTER_RESTORE=f260bf78ac0b19e0360795cad2ee01a019d3a2535e3aaedc2473492bfb593a2f
PORTFOLIO_HASH_BEFORE_RESTORE=0eb903f2d647ce80b8488eb1f5ffd2c839b38fe4d08797ff8ff274bb6d82acc6
PORTFOLIO_HASH_AFTER_RESTORE=0eb903f2d647ce80b8488eb1f5ffd2c839b38fe4d08797ff8ff274bb6d82acc6
```

The final `verify-all.ps1 -Offline` run completed with exit code 0, including all five PostgreSQL
Testcontainers suites, unchanged coverage/security/license/SpotBugs gates, and the frozen 401-file
source manifest. The three generated Phase 17 report files were intentionally not committed.

## 12. Final typed-operation, Claim-context, and preflight recovery closure

Commit `34dfa1b` closes the three remaining Issue 007 gaps without starting Issue 008.

### 12.1 Hard signature authority

The hard signature is now independent of Strategy-author prose. The six out-of-vocabulary
paraphrases use identical server-validated typed operation graphs and collapse to one admission.
Unknown operation graphs do not hard-merge. Invalid nodes, reversed reachability, duplicate
operation IDs, and contradictory edge kinds fail deterministic compilation.

```text
OUT_OF_VOCABULARY_SAME_MECHANISM_CANDIDATES=6
ADMISSIONS=1
PARAPHRASE_BYPASS_LEAKS=0
UNKNOWN_OPERATION_HARD_MERGES=0
```

### 12.2 Claim-local production context

The tests invoke the actual Desktop `controlStrategy -> StrategyBlueprintCompiler ->
criticalClaimContexts` path. Five identical `P(x)` statements receive five real
`critical_claim` Blueprint bindings and distinct semantic contexts. Context-only Claim nodes do
not enter the Proof Graph mutation path, so they cannot create duplicate obligations or
canonical self-edges.

```text
PER_CLAIM_CONTEXTS=5
DISTINCT_CONTEXT_KEYS=5
CLAIM_BLUEPRINT_BINDINGS=5
LOCAL_ASSUMPTION_FALSE_SUPPORTS=0
LOCAL_QUANTIFIER_FALSE_SUPPORTS=0
POLARITY_FALSE_SUPPORTS=0
```

### 12.3 Crash-safe registered preflight

Two simulated process terminations cover the durable windows after reservation and after result
persistence but before completion. Restore safely runs an empty reservation once, rolls a
durable result forward without executing the computation again, and preserves the selected
portfolio. A persisted uncertain `RUNNING` record is quarantined rather than hard-rejected.

```text
PREFLIGHT_CRASHES_INJECTED=2
SAFE_REEXECUTIONS_AFTER_EMPTY_RESERVATION=1
INCOMPLETE_FRONTIER_HARD_REJECTIONS=0
RESULT_DURABLE_FRONTIERS=1
RESULT_ROLL_FORWARDS=1
DUPLICATE_COMPUTATION_EXECUTIONS=0
DUPLICATE_PREFLIGHT_EVIDENCE=0
POST_RESTORE_STRATEGY_SELECTION_CHANGES=0
```

Legacy snapshot coverage confirms `started -> RUNNING` and `completed -> COMPLETED` migration,
including derived replay data. Registry merge tests confirm that a later weaker or higher-version
frontier cannot downgrade a completed result.

### 12.4 Test-first evidence and final gates

Before production implementation, the first new Core tests failed compilation with seven errors
because `MechanismOperationDeclaration`, `MechanismOperationKind`, and
`operationGraphKnown` did not exist. This is recorded as pre-fix architecture-missing evidence,
not misrepresented as a complete old-system behavioral replay.

The final focused follow-up suites were:

| Module | Tests | Failures | Errors | Skipped | Result |
|---|---:|---:|---:|---:|---|
| Contracts | 2 | 0 | 0 | 0 | PASS |
| Core | 10 | 0 | 0 | 0 | PASS |
| Desktop | 8 | 0 | 0 | 0 | PASS |

The first broad regression correctly exposed that context-only Claim nodes were being projected
as ordinary Proof Graph obligations. The final implementation keeps them in the auditable
Blueprint while excluding duplicate graph writes; both the bounded-non-refutation test and the
20-round Semantic Pivot canonicalization test then passed with zero graph/canonicalization
leaks.

Final `verify-all.ps1 -Offline` results:

```text
Contracts: 50 tests, 0 failures, 0 errors, 0 skipped
Core:      1122 tests, 0 failures, 0 errors, 0 skipped
Server:     878 tests, 0 failures, 0 errors, 3 conditional skips
Desktop:    166 tests, 0 failures, 0 errors, 1 conditional skip
Java total: 2216 tests, 0 failures, 0 errors, 4 conditional skips

CONTRACTS_ADJUSTED_BRANCH_COVERAGE=87.248028%
CORE_BRANCH_COVERAGE=75.068731%
CRITICAL_SCENARIOS=9/9
FULL_VERIFICATION=PASS
```

All five PostgreSQL suites passed through Docker:
`JdbcMessageRepositoryIT`, `MemoryProofGraphPostgresIT`, `PersistencePostgresIT`,
`Phase17CheckpointOutboxPerformanceIT`, and `ProviderCallPostgresIT`. SpotBugs initially caught
ordinary String comparisons on the new hash fields; the implementation now uses the existing
constant-time hash comparator. No SpotBugs suppression or coverage/performance threshold was
added or weakened.

Issue 001 Root Goal authority, Issue 002 Negative Knowledge, Issue 003 Claim lifecycle,
Issue 004 Research Checkpoints, Issue 005 canonicalization/convergence, and Issue 006 Semantic
Pivot behavior remained protected by the full regression. No Issue 008 production work was
introduced.

## 13. Unresolved-mechanism and explicit Claim-binding closure

This final Issue 007 follow-up closes three additional admission loopholes without changing the
checkpoint schema or starting Issue 008.

### 13.1 Test-first behavioral evidence

The new tests were first run against the preceding implementation and exposed three behavioral
failures:

- six candidates with no operation graph produced six distinct admissions, padded a requested
  portfolio to four, and caused no replenishment request;
- a five-Claim candidate with only four context bindings reached `SELECTED` instead of failing
  closed;
- identical Blueprint operation subgraphs relabeled `INDUCTION` and `REDUCTION` produced two hard
  mechanism admissions.

These are behavioral failures against the existing production path, not merely missing-API
compile failures.

### 13.2 Production behavior

For newly generated candidates, an empty operation declaration list or any declaration whose
kind is `UNKNOWN` now transitions to `QUARANTINED_MECHANISM_UNRESOLVED` before preflight,
optimization, Archive mutation, or Route creation. `StrategyPortfolioOptimizer` repeats the
same check as a defense-in-depth boundary. Such candidates remain auditable and trigger the
existing one-shot gap replenishment, but they cannot count toward portfolio size or mechanism
diversity. Existing V12 strategies restored as `LEGACY_ACTIVE` retain the compatibility compiler
path and are not retroactively deleted.

Hard operation identity now hashes the canonical input/output role subgraph, deduplicates repeated
declarations of the same subgraph, and does not trust the model-proposed operation-kind label.
When equal structural signatures carry different declared kinds, the second candidate is rejected
with `MECHANISM_DECLARATION_CONFLICT`; the kind remains available as non-authoritative descriptive
metadata.

Every Critical Claim on a new candidate must have exactly one explicit
`CriticalClaimContextBinding`. Missing and duplicate bindings fail closed with stable codes
`MISSING_CRITICAL_CLAIM_CONTEXT_BINDING` and
`DUPLICATE_CRITICAL_CLAIM_CONTEXT_BINDING`; an unresolved bound Claim uses
`UNBOUND_CRITICAL_CLAIM`. The legacy compiler retains its Root Context fallback solely for
checkpoint migration. Trusted negative preflight matching now uses the same raw claim-local
context that was bound into the verified counterexample, preventing canonicalized display fields
from changing the Negative Knowledge semantic key.

### 13.3 Focused diagnostics

The unknown-mechanism test mixes empty operation lists and explicit `UNKNOWN` declarations. The
eight quarantines are the six initial candidates plus two candidates returned by the one allowed
replenishment request.

```text
UNKNOWN_MECHANISM_CANDIDATES=6
UNKNOWN_MECHANISM_QUARANTINES=8
UNKNOWN_MECHANISM_DISTINCT_ADMISSIONS=0
UNKNOWN_MECHANISM_PORTFOLIO_PADDING=0
REPLENISHMENT_REQUESTS=1

MISSING_BINDINGS=1
INCOMPLETE_CANDIDATE_ADMISSIONS=0
ACTIVE_STATE_LEAKS=0
COMPLETE_CANDIDATE_ADMISSIONS=1

UNREVIEWED_KIND_CONFLICTS=1
DISTINCT_MECHANISM_ADMISSIONS=0
```

Core coverage additionally executes missing, duplicate, complete, legacy-fallback, unknown-node,
empty-operation, and explicit-`UNKNOWN` branches. Existing production-path fixtures were upgraded
to provide explicit typed metadata; the intentional missing/unknown tests remain uncompleted so
they exercise the fail-closed path.

### 13.4 Restore and protected-authority regression

The final 20-round restore diagnostic remained clean:

```text
ROUNDS=20
RESTORE_ROUND=10
RAW_STRATEGY_CANDIDATES=160
PREFLIGHTED_CANDIDATES=160
VERIFIED_REFUTED_REQUIRED_CLAIMS=20
REFUTED_REQUIRED_STRATEGY_ADMISSIONS=0
SAME_MECHANISM_MULTI_ADMISSIONS=0
UNRESOLVED_COMMON_MODE_CAP_VIOLATIONS=0
DISTINCT_MECHANISM_PORTFOLIOS=20
PORTFOLIO_SIZE_SHORTFALLS=0
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
RESULT=PASS
```

```text
CANDIDATE_HASH_BEFORE_RESTORE=6db0779acf73b4775ae6814c3fdafa426546e7442847b14c2f23345bc046fc19
CANDIDATE_HASH_AFTER_RESTORE=6db0779acf73b4775ae6814c3fdafa426546e7442847b14c2f23345bc046fc19
MECHANISM_HASH_BEFORE_RESTORE=39adef4292fe0677dab9f71ca7a32027bf3bf43d8b3e4bdcbe3a3a02f4219a72
MECHANISM_HASH_AFTER_RESTORE=39adef4292fe0677dab9f71ca7a32027bf3bf43d8b3e4bdcbe3a3a02f4219a72
PORTFOLIO_HASH_BEFORE_RESTORE=ca591fa59b0191dc5eeccb9bcdd60f7b8f429812bad8fe1bc17b8b4c405687ad
PORTFOLIO_HASH_AFTER_RESTORE=ca591fa59b0191dc5eeccb9bcdd60f7b8f429812bad8fe1bc17b8b4c405687ad
```

### 13.5 Final gates

The first full verification run stopped at the unchanged Core branch-coverage gate:
`74.987825% < 75%`. No threshold or performance gate was weakened. Adding direct tests for the
new strict compiler branches raised final Core branch coverage to `75.280023%`.

Final `verify-all.ps1 -Offline` results:

```text
Contracts: 50 tests, 0 failures, 0 errors, 0 skipped
Core:      1126 tests, 0 failures, 0 errors, 0 skipped
Server:     878 tests, 0 failures, 0 errors, 3 conditional skips
Desktop:    168 tests, 0 failures, 0 errors, 1 conditional skip
Java total: 2222 tests, 0 failures, 0 errors, 4 conditional skips
Compatibility: 149 tests, 0 failures, 0 errors, 0 skipped

CONTRACTS_ADJUSTED_BRANCH_COVERAGE=85.245220%
CORE_BRANCH_COVERAGE=75.280023%
CRITICAL_SCENARIOS=9/9
FULL_VERIFICATION=PASS
```

All five Docker-backed PostgreSQL suites passed with no skip:
`JdbcMessageRepositoryIT`, `MemoryProofGraphPostgresIT`, `PersistencePostgresIT`,
`Phase17CheckpointOutboxPerformanceIT`, and `ProviderCallPostgresIT`. SpotBugs and FindSecBugs
reported zero findings across all five Java modules; 115 dependencies had zero visible or CVSS 7+
findings; 111 license components had zero missing or unreviewed entries; and the secret scan found
zero findings in 1510 files.

Issues 001-006 remained protected by the complete module and full-verification regressions. No
Root Goal, Negative Knowledge authority, Claim lifecycle, Research Checkpoint, canonicalization,
convergence, Semantic Pivot, Provider, concurrency, budget, Temporal, or Python Sidecar production
semantics were changed.
