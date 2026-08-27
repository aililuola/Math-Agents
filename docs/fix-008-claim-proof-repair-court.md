# Issue 008: Claim Truth, Proof Validity, and Bounded Repair Court

## 1. Status and Git provenance

- Status: CLOSED after both implementation phases and every gate in this record passed.
- Branch: fix/008-claim-proof-repair-court
- Baseline branch: java
- Baseline commit: a5a539c8780bc735a3e9a273d7c370151491ff04
- Phase 008A commit: 8735c1399c2aad32e201972bfeae55dcee022018
- Phase 008B commit: 4e8648c7b051a30594af5ca2adef9571dffb21c6
- Baseline checkpoint schema: 15
- Resulting checkpoint schema: 16

The branch was created only after the completed Issue 007 implementation was present on java.
No work was committed directly to java or main, and Issue 009 was not started.

## 2. Pre-fix production audit

The original production path collapsed three distinct questions into one legacy PASS, FAIL, or
UNCERTAIN result:

    ClaimReviewDecision.verdict == FAIL
    -> AttemptArtifactStatus.REJECTED
    -> ClaimStatus.REJECTED

The audited entry points were ClaimReviewDecision, ClaimReviewBatch, VerificationIssue,
VerificationVerdict, ClaimStatus, AttemptArtifactStatus,
AttemptArtifactLedger.applyReviewBatch, LemmaMemory.applyClaimReviewDecision,
LemmaMemory.applyClaimReport, ClaimLifecycleController,
DesktopSolveCoordinator.reviewAttemptArtifacts,
DesktopSolveCoordinator.applyClaimDecision,
DesktopSolveCoordinator.integrateVerifiedAttemptArtifacts, and the
claim_salvage_review prompt.

The same audit traced Claim rejection persistence, exact counterexample authority, Fact
promotion, and restore. It confirmed that the legacy FAIL value did not say whether the
statement was false, the submitted proof was invalid, a dependency was missing, or a local
repair was possible.

## 3. Test-first behavioral evidence

The three black-box tests were run before the production fix. They exposed existing behavior
without relying on a missing Issue 008 API.

    TRUE_STATEMENTS=1
    PROOF_ERRORS=1
    EXPECTED_REJECTED_CLAIMS=0
    ACTUAL_REJECTED_CLAIMS=1

    FALSE_STATEMENT_CASES=1
    TRUE_BAD_PROOF_CASES=1
    DISTINCT_OUTCOMES_EXPECTED=2
    DISTINCT_OUTCOMES_ACTUAL=1

    REPAIRABLE_PROOF_CASES=1
    MINIMAL_REPAIR_CALLS=0
    BLIND_ADJUDICATION_CALLS=0
    CLAIM_STATUS=REJECTED

After the fix, the true statement with a bad proof remains open instead of being rejected, the
false statement and invalid proof receive different outcomes, and the repairable proof enters
bounded repair plus independent blind adjudication.

## 4. Implemented Claim Court

The production flow is:

    Harvested local Claim
    -> immutable Claim Freeze
    -> Statement Falsification
    -> trusted counterexample witness review when needed
    -> Proof Audit
    -> bounded Minimal Repair when allowed
    -> isolated Blind Adjudication
    -> atomic Authority Projection

The authority model separates:

- ClaimStatementStatus: OPEN or REFUTED.
- ClaimProofStatus: UNREVIEWED, VALID, INVALID_REPAIRABLE,
  INVALID_UNREPAIRABLE, or REPAIRED_PENDING_ADJUDICATION.
- ClaimCourtOutcome: VERIFIED, REFUTED, PROOF_INVALID_BUT_CLAIM_OPEN,
  REPAIR_EXHAUSTED, INCONCLUSIVE, or DEFERRED_INDEPENDENCE_UNAVAILABLE.

Only VERIFIED enters the existing verified Claim and Fact path. Only REFUTED maps to
ClaimStatus.REJECTED. Proof-invalid, exhausted, inconclusive, and independence-unavailable
outcomes map to UNCERTAIN and leave the mathematical statement open.

## 5. Freeze and statement authority

FrozenClaimSnapshot binds the case to problemHash, rootGoalHash, Claim ID, statement and semantic
hashes, conclusion, assumptions, ordered quantifiers, variable bindings, scope, polarity,
dependencies, initial proof revision, source Attempt, source Route, and author.

The repair path cannot change the statement, conclusion, assumptions, quantifiers, variable
bindings, scope, polarity, or dependency identity. Such a patch fails deterministically as a
frozen-Claim mutation or nonlocal reformulation.

Statement falsification is deliberately non-authoritative model output. A model may return no
candidate, a candidate witness, or inconclusive. It cannot declare VERIFIED_REFUTATION,
CLAIM_FALSE, or PERMANENT_NEGATIVE. REFUTED requires exact, auditable authority from the
existing permanent-negative or verified-counterexample boundary, a replayable trusted artifact,
or an independently accepted witness bound to the frozen statement context. Merely finding no
counterexample never increases Claim authority.

## 6. Proof audit and bounded repair

Proof Audit records step-bound issues with bounded ProofIssueKind and ProofRepairability values.
It distinguishes invalid inference, missing justification or dependency, circularity,
quantifier/scope mistakes, case gaps, calculations, construction validity, evidence mismatch,
tool/citation gaps, statement reformulation, and global proof-architecture failure.

Only LOCAL_PATCH and VERIFIED_DEPENDENCY_PATCH can enter Minimal Repair. Patch validation enforces:

- unchanged frozen Claim semantics;
- bounded patch and operation counts;
- edits restricted to audited step IDs;
- exact old-step hashes;
- no unverified dependency insertion;
- no dependency cycle;
- no statement reformulation disguised as proof text;
- no direct VERIFIED, FACT, or permanent-negative authority from the Repairer.

ClaimProofRevisionLedger records the original and repaired proof revisions with stable hashes,
evidence references, audit history, monotonic versions, and snapshot/restore support. Exhausted
or nonlocal repair leaves the Claim open.

## 7. Blind adjudication and role isolation

ClaimBlindReviewPacket excludes previous verdicts, confidence values, author identity, Auditor
identity, Repairer identity, and Falsifier identity. ClaimCourtRolePolicy enforces distinct
author, Falsifier, Auditor, Repairer, and Blind Adjudicator roles at the required boundaries.
When sufficient independent roles are unavailable, the result is
DEFERRED_INDEPENDENCE_UNAVAILABLE rather than an authority downgrade.

A blind PASS may project VERIFIED only after the server validates the repaired revision. A
FAIL_PROOF keeps the Claim open. A counterexample candidate returns to the trusted witness
authority path and does not become an immediate refutation.

## 8. Atomicity and crash recovery

ClaimCourtLedger, ClaimProofRevisionLedger, and ClaimCourtStageExecutionLedger own durable,
typed projections. Stage execution records bind action keys, request and response hashes,
artifact references, statuses, and stable versions so completed provider stages are not replayed.

The Desktop projection snapshots and restores the Court, proof revisions, stage executions,
Lemma/Typed Memory, Claim lifecycle, Attempt artifacts, Proof Graph, pending tasks, leases, and
related campaign projections around each authoritative mutation.

Caught mutation failures produced:

    FAILURE_POINTS=10
    PARTIAL_COURT_RECORDS=0
    PARTIAL_PROOF_REVISIONS=0
    PARTIAL_CLAIM_STATUS_WRITES=0
    PARTIAL_FACT_WRITES=0
    PARTIAL_PROOFGRAPH_WRITES=0
    TASK_LEASE_LEAKS=0
    PENDING_TASK_LEAKS=0
    RESULT=PASS

Five Error-based simulated process terminations cover durable statement result, proof-audit
result, repair revision, blind result, and final authority checkpoint windows. A new Coordinator
restores the durable frontier and deterministically rolls forward without duplicating a provider
call, repair, Fact, or projection.

    HARD_CRASH_POINTS=5
    RESTORE_FAILURES=0
    PARTIAL_COURT_FRONTIERS=0
    DUPLICATE_PROVIDER_CALLS=0
    DUPLICATE_REPAIRS=0
    DUPLICATE_FACTS=0
    GHOST_PROOF_REVISIONS=0
    CLAIM_STATUS_OUTCOME_MISMATCHES=0
    RESULT=PASS

## 9. Checkpoint migration

DesktopSolveCheckpoint schema 15 -> 16 adds:

- ClaimProofRevisionSnapshot
- ClaimCourtSnapshot
- ClaimCourtStageExecutionSnapshot

Missing v15 fields deserialize as empty. Restore rebuilds minimal legacy proof revisions from
existing Claim authority without calling a provider. A legacy VERIFIED Claim remains VERIFIED
and retains exactly one Fact; a legacy REJECTED Claim remains REJECTED and gains no Fact. The
first v16 save persists the rebuilt projection, and a second restore neither loses nor duplicates
it. Root Goal and Negative Knowledge hashes remain unchanged.

## 10. Modified files

### Contracts

- Claim truth/proof authority: ClaimCourtOutcome, ClaimStatementStatus,
  ClaimStatementAssessment, ClaimProofStatus.
- Falsification and witness review: ClaimStatementFalsificationBatch,
  ClaimStatementFalsificationDecision, StatementFalsificationDisposition,
  StatementCounterexampleCandidate, ClaimCounterexampleWitnessReviewBatch,
  ClaimCounterexampleWitnessReviewDecision.
- Proof audit: ClaimProofAuditBatch, ClaimProofAuditDecision, ClaimProofAuditVerdict,
  ProofAuditIssue, ProofIssueKind, ProofRepairability.
- Repair and blind court: ClaimMinimalRepairBatch, ClaimMinimalRepairDecision,
  ClaimMinimalRepairDisposition, ClaimProofPatch, ClaimProofPatchOperation,
  ClaimProofPatchOperationType, ClaimBlindAdjudicationBatch,
  ClaimBlindAdjudicationDecision, ClaimBlindAdjudicationVerdict.

### Core production

- Existing authority adapters: AttemptArtifactLedger, ClaimLifecycleController, and LemmaMemory
  now project proof-invalid-open separately from exact refutation and preserve verified
  monotonicity.
- Freeze and decision authority: ClaimFreezeService, FrozenClaimSnapshot,
  FrozenClaimSemanticContext, ClaimCourtDecisionService, ClaimCourtOutcomeProjector,
  ClaimCourtRolePolicy, ClaimStatementAuthorityService, ClaimRefutationEvidence, and
  ClaimRefutationEvidenceType.
- Repair and isolation: ClaimProofPatchValidator, ClaimBlindReviewPacket, and
  ClaimBlindReviewPacketFactory.
- Durable state: ClaimCourtLedger, ClaimCourtRecord, ClaimCourtSnapshot,
  ClaimCourtStageExecutionLedger and its record/snapshot/status types,
  ClaimProofRevisionLedger and its record/snapshot/status types, plus typed audit events,
  stages, statuses, config, values, and atomic projection.

### Server and Desktop production

- PromptCatalog: adds only the five Claim Court prompts and keeps other stage contracts intact.
- DesktopSolveCoordinator: invokes the real freeze, falsification, witness review, proof audit,
  bounded repair, blind adjudication, atomic projection, exactly-once, and restore paths.
- DesktopSolveCheckpoint: schema 16 Court, proof-revision, and execution snapshots.
- ClaimCourtFailurePoint and SimulatedClaimCourtProcessTermination: deterministic exception and
  process-crash injection boundaries used by production-path tests.

### Tests

- Contracts: ClaimCourtContractValidationTest.
- Core: the required freeze, authority, truth/proof separation, lifecycle projection, role,
  problem-neutrality, repair validation, revision, blind isolation, exhaustion, snapshot,
  execution-ledger, and atomic-projection tests under memory and claimcourt.
- Server: ClaimCourtContractsTest and all falsification, witness, audit, repair, blind, and packet
  isolation prompt tests.
- Desktop: the three pre-fix black boxes plus production statement-refutation, repairable and
  unrepairable proof, blind isolation, role independence, Route theorem boundary, atomicity,
  hard-crash, 20-round restore, v15 migration, protected-authority, prompt isolation, and live
  execution coverage.

## 11. Focused verification

| Invocation | Tests | Failures | Errors | Skipped | Result |
|---|---:|---:|---:|---:|---|
| Core Issue 008 suite | 37 | 0 | 0 | 0 | PASS |
| Server Issue 008 suite | 7 | 0 | 0 | 0 | PASS |
| Desktop Issue 008 command | 15 (Core 1 + Desktop 14) | 0 | 0 | 0 | PASS |

The fixed black-box outputs were:

    TRUE_STATEMENTS=1
    PROOF_ERRORS=1
    EXPECTED_REJECTED_CLAIMS=0
    ACTUAL_REJECTED_CLAIMS=0

    FALSE_STATEMENT_CASES=1
    TRUE_BAD_PROOF_CASES=1
    DISTINCT_OUTCOMES_EXPECTED=2
    DISTINCT_OUTCOMES_ACTUAL=2

    CLAIM_COURT_SCHEMAS=[ClaimStatementFalsificationBatch, ClaimProofAuditBatch,
      ClaimMinimalRepairBatch, ClaimBlindAdjudicationBatch]
    REPAIRABLE_PROOF_CASES=1
    MINIMAL_REPAIR_CALLS=1
    BLIND_ADJUDICATION_CALLS=1
    CLAIM_STATUS=VERIFIED

## 12. Twenty-round diagnostic

    CLAIM COURT DIAGNOSTIC
    ROUNDS=20
    RESTORE_ROUND=10
    CLAIM_CASES=20
    FROZEN_CLAIMS=20
    ORIGINAL_VALID_PROOFS=5
    REPAIRABLE_INVALID_PROOFS=5
    UNREPAIRABLE_INVALID_PROOFS=5
    REFUTED_STATEMENTS=5
    STATEMENT_FALSIFICATION_CALLS=20
    PROOF_AUDIT_CALLS=15
    MINIMAL_REPAIR_CALLS=5
    BLIND_ADJUDICATION_CALLS=10
    SUCCESSFUL_REPAIRS=5
    REPAIR_EXHAUSTED_CASES=0
    VERIFIED_OUTCOMES=10
    REFUTED_OUTCOMES=5
    PROOF_INVALID_BUT_CLAIM_OPEN_OUTCOMES=5
    FALSE_CLAIM_REPAIR_ATTEMPTS=0
    PROOF_FAILURE_FALSE_REJECTIONS=0
    UNVERIFIED_COUNTEREXAMPLE_REFUTATIONS=0
    CLAIM_STATUS_VERIFIED=10
    CLAIM_STATUS_REJECTED=5
    CLAIM_STATUS_UNCERTAIN=5
    DIRECT_FACT_PROMOTIONS_BY_REPAIRER=0
    DIRECT_CLAIM_VERIFICATIONS_BY_REPAIRER=0
    DIRECT_NEGATIVE_REGISTRATIONS=0
    MAIN_GOAL_CLOSURES=0
    DUPLICATE_COURT_CASES=0
    DUPLICATE_REPAIR_PATCHES=0
    DUPLICATE_BLIND_ADJUDICATIONS=0
    DUPLICATE_FACT_PROMOTIONS=0
    POST_RESTORE_CASE_LOSSES=0
    POST_RESTORE_REVISION_LOSSES=0
    POST_RESTORE_STAGE_REPLAYS=0
    POST_RESTORE_OUTCOME_CHANGES=0
    ROOT_HASH_CHANGES=0
    NEGATIVE_REGISTRY_HASH_CHANGES=0
    COURT_LEDGER_HASH_BEFORE_RESTORE=f26ea0b1db9aa32303d8a7c0e81739c989e33ff06b79b041f7c89bbd8dec90c4
    COURT_LEDGER_HASH_AFTER_RESTORE=f26ea0b1db9aa32303d8a7c0e81739c989e33ff06b79b041f7c89bbd8dec90c4
    PROOF_REVISION_HASH_BEFORE_RESTORE=8feb9dc1eafdb9d7604c499ab642c41c1dfbe19fd737392ca20ea7805b09180b
    PROOF_REVISION_HASH_AFTER_RESTORE=8feb9dc1eafdb9d7604c499ab642c41c1dfbe19fd737392ca20ea7805b09180b
    RESULT=PASS

NEGATIVE_REGISTRY_HASH_CHANGES uses the permanent-negative authority projection. Failed-route
integration may add legitimate temporary negatives, so the test intentionally distinguishes
those audit records from forbidden permanent authority changes.

## 13. Issues 001-007 regressions

All explicit protected regressions were rerun after the final Issue 008 code:

| Issue | Result |
|---|---|
| 001 Exact Root Goal | Core 14 + Desktop 1, PASS |
| 002 Permanent Negative Knowledge | Core 18 + Desktop 11, PASS |
| 003 Attempt/Route/Claim separation | Core 13 + Desktop 7, PASS |
| 004 Incremental Research Checkpoints | Contracts 4 + Core 16 + Server 13 + Desktop 10, PASS |
| 005 Canonicalization and Convergence | Core 46 + Desktop 21, PASS |
| 006 Semantic Pivot | Core 25 + Server 4 + Desktop 15, PASS |
| 007 Strategy Mechanism Diversity | Contracts 2 + Core 52 + Server 5 + Desktop 13, PASS |

Issue 002 retained its 30-round, 150-reentry, widening, trusted-alias, and
possible-equivalent quarantine boundaries. Issue 003 retained local Claim salvage and v6
verified-Fact continuity. Issues 004-007 retained their restore, atomicity, canonicalization,
deferred-reactivation, Pivot hard-crash, typed mechanism, Claim-context, and preflight
hard-crash coverage.

## 14. Protected production no-diff

Relative to a5a539c8780bc735a3e9a273d7c370151491ff04, git reported no differences for
the explicitly protected Issue 001, 002, 004, 005, 006, and 007 production files:

- ExactGoalContractChecker, RootGoalContract, ProblemSemanticViewService, SemanticProfileService.
- NegativeKnowledgeRegistry, NegativeKnowledgeAdmissionGate, NegativeKnowledgeSemanticKey,
  VerifiedCounterexampleAuthority.
- ResearchCheckpointFrameParser, ResearchCheckpointLedger, ResearchCheckpointedPromptFactory,
  ReasoningTraceBinding, ReasoningTraceStore.
- ObligationSemanticSignature, ObligationCanonicalizationRegistry,
  ProofGraphConvergenceMonitor, DeferredExpansionLedger.
- SemanticPivotCompiler, SemanticPivotDeterministicAuditor, SemanticPivotLedger,
  SemanticPivotController.
- StrategyMechanismAnalyzer, CriticalClaimContextCompiler, StrategyCriticalClaimPreflight,
  StrategyPortfolioOptimizer.

Issue 003 adapters were modified only in the expressly allowed Claim-Court projection area.
Root Goal, Negative Knowledge authority, Research Finding authority, canonical graph and
convergence, Semantic Pivot, Strategy Portfolio, Provider routing, concurrency, Token/budget,
Temporal, and Python Sidecar behavior were not changed.

## 15. Module and full verification

The module regression command completed successfully:

| Module | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| Contracts | 54 | 0 | 0 | 0 |
| Core | 1164 | 0 | 0 | 0 |
| Server | 859 | 0 | 0 | 3 |
| Desktop | 182 | 0 | 0 | 1 |

The final scripts/verify-all.ps1 -Offline run exited 0 with FULL VERIFICATION: PASS:

- Java unit/module tests above: 2259.
- Server Failsafe integration tests: 26, with all five named PostgreSQL Testcontainers suites
  passing: JdbcMessageRepositoryIT, MemoryProofGraphPostgresIT, PersistencePostgresIT,
  Phase17CheckpointOutboxPerformanceIT, and ProviderCallPostgresIT.
- Compatibility tests: 149.
- Full total: 2434 tests, 0 failures, 0 errors, 4 conditional skips.
- Contracts adjusted line coverage: 91.61036%.
- Contracts adjusted branch coverage: 85.890302%.
- Core line coverage: 91.309769%.
- Core branch coverage: 75.338429%.
- Server line coverage: 87.728838%.
- Desktop line coverage: 77.470211%.
- SpotBugs and FindSecBugs: 0 findings in all five Java modules.
- OWASP: 115 dependencies, 0 visible findings, 0 findings at CVSS 7 or higher.
- Licenses: 111 components, 0 missing and 0 unreviewed licenses.
- Secret scan: 1618 files, 0 findings.
- Frozen source: 401 files, manifest
  9f3def2bec8cea99d0a18b51fbb5fa8ce53f44a24ce869f6495cac809b7e3770, PASS.

No coverage, performance, security, license, source-immutability, or Python Sidecar threshold was
lowered. Generated target files, logs, checkpoints, and Phase 17 reports are not committed.

## 16. Closure diagnostic

    ISSUE 008 CLAIM COURT DIAGNOSTIC
    STATEMENT_PROOF_SEPARATION_RESULT=PASS
    TRUE_BAD_PROOF_FALSE_REJECTIONS=0
    VERIFIED_COUNTEREXAMPLE_REFUTATIONS=5
    UNVERIFIED_COUNTEREXAMPLE_REFUTATIONS=0
    NO_COUNTEREXAMPLE_FOUND_AUTHORITY_ESCALATIONS=0
    REPAIRABLE_PROOF_CASES=5
    SUCCESSFUL_MINIMAL_REPAIRS=5
    REPAIRER_DIRECT_VERIFICATIONS=0
    REPAIRER_DIRECT_FACT_PROMOTIONS=0
    BLIND_ADJUDICATIONS=10
    BLIND_ROLE_LEAKS=0
    BLIND_VERIFIED_CLAIMS=10
    PROOF_INVALID_BUT_CLAIM_OPEN=5
    PROOF_INVALID_CLAIMS_MARKED_REJECTED=0
    FALSE_CLAIM_REPAIR_ATTEMPTS=0
    DUPLICATE_COURT_CASES=0
    DUPLICATE_REPAIR_PATCHES=0
    DUPLICATE_BLIND_ADJUDICATIONS=0
    PARTIAL_COURT_WRITES=0
    POST_RESTORE_COURT_CASE_LOSSES=0
    POST_RESTORE_REVISION_LOSSES=0
    POST_RESTORE_STAGE_REPLAYS=0
    POST_RESTORE_DUPLICATE_FACTS=0
    ROOT_HASH_CHANGES=0
    NEGATIVE_REGISTRY_HASH_CHANGES=0
    ISSUE_001_REGRESSION=PASS
    ISSUE_002_REGRESSION=PASS
    ISSUE_003_REGRESSION=PASS
    ISSUE_004_REGRESSION=PASS
    ISSUE_005_REGRESSION=PASS
    ISSUE_006_REGRESSION=PASS
    ISSUE_007_REGRESSION=PASS
    PROTECTED_FILES_NO_DIFF=PASS
    FULL_VERIFICATION=PASS
    ISSUE_008_STATUS=CLOSED

Implementation-only diff before this documentation commit:

    120 files changed, 9304 insertions(+), 275 deletions(-)

The final worktree and remote branch state are verified after the documentation commit and push.

## 17. Authority-binding follow-up

An independent review of the first Issue 008 implementation found three remaining authority
boundaries. They were closed on the same `fix/008-claim-proof-repair-court` branch; Issue 009 was
not started. The baseline for this follow-up is
`a5a539c8780bc735a3e9a273d7c370151491ff04`, and the previously committed Issue 008 head was
`966957e1bd026d716c48bfdb361ab88842d2b3f6`.

### 17.1 Pre-fix behavioral evidence

The new black-box tests were written and run before the production changes. They failed against
the old production behavior as follows:

- `ClaimProofPatchUntrustedEvidenceInjectionTest`: an arbitrary Repairer-supplied `EvidenceRef`
  was accepted (`expected false but was true`).
- `ClaimCounterexampleFullContextExactnessTest`: the frozen quantifier list was empty instead of
  retaining the Claim-local quantifiers.
- `ClaimCourtProofRevisionIdentityTest`: two different proof revisions produced the same Court ID,
  `claim-court-50ff354395ec2d227054855e`.

These are behavioral failures, not merely missing-type compilation failures.

### 17.2 Trusted repair evidence

`ClaimTrustedEvidenceResolver` now resolves every `ADD_VERIFIED_EVIDENCE_REF` against a
server-owned `TrustedClaimEvidence` capability. Resolution binds artifact reference, content hash,
problem hash, exact frozen Claim semantic hash, verification state, replay state, active state,
and proof-step use permission. The stable rejection codes are:

- `UNKNOWN_EVIDENCE_REF`
- `EVIDENCE_CONTENT_HASH_MISMATCH`
- `EVIDENCE_PROBLEM_SCOPE_MISMATCH`
- `EVIDENCE_CLAIM_SCOPE_MISMATCH`
- `EVIDENCE_NOT_VERIFIED`
- `EVIDENCE_REPLAY_NOT_VERIFIED`
- `EVIDENCE_AUTHORITY_ESCALATION`

The patch validator replaces model text with the canonical server reference. The Blind packet
factory resolves the revision and every step-level reference again, so restore-time revocation or
scope changes fail closed. Desktop production exposes only exact verified Fact evidence and
replay-valid verified computation evidence. A Repairer cannot mint authority by writing an
`EvidenceRef` into JSON.

    REPAIR EVIDENCE AUTHORITY DIAGNOSTIC
    UNTRUSTED_EVIDENCE_PATCH_ATTEMPTS=1
    UNTRUSTED_EVIDENCE_PATCH_ACCEPTS=0
    UNTRUSTED_EVIDENCE_IN_BLIND_PACKETS=0
    DIRECT_EVIDENCE_AUTHORITY_ESCALATIONS=0
    VERIFIED_EVIDENCE_PATCH_ATTEMPTS=1
    VERIFIED_EVIDENCE_PATCH_ACCEPTS=1
    RESULT=PASS

### 17.3 Complete Claim-local semantic freeze

`ClaimCourtSemanticContextCompiler` now receives the server-compiled context and freezes its
assumptions, ordered quantifiers, variable bindings, scope limitations, and polarity. Desktop
reuses the Issue 007 critical-Claim context compiler and verifies the bound statement before
freezing. Legacy fallback is explicitly marked `LEGACY_INCOMPLETE_SEMANTIC_CONTEXT`; it is not
silently represented as a complete modern context. Quantifier variables without bindings and
duplicate bindings fail closed.

    CLAIM CONTEXT EXACTNESS DIAGNOSTIC
    CLAIM_CONTEXTS=6
    DISTINCT_SEMANTIC_HASHES=6
    QUANTIFIER_FALSE_REFUTATIONS=0
    POLARITY_FALSE_REFUTATIONS=0
    ASSUMPTION_FALSE_REFUTATIONS=0
    SCOPE_FALSE_REFUTATIONS=0
    VARIABLE_BINDING_FALSE_REFUTATIONS=0
    RESULT=PASS

### 17.4 Statement identity and proof-revision identity

The stable Statement identity is now:

    statementCaseId = hash(problemHash, rootGoalHash, claimSemanticHash)

Each proof is reviewed under its own identity:

    proofCourtCaseId = hash(statementCaseId, initialProofRevisionId, proofHash)

`ClaimCourtLedger.findProofCase` resolves exact current identities and legacy v16 IDs without
allowing identity collisions. A corrected proof for the same Statement opens a new Court case;
resubmitting the identical corrected proof reuses the existing case and does not repeat provider
calls or Fact promotion. Restore retains both revisions and their outcomes.

    CORRECTED PROOF COURT IDENTITY DIAGNOSTIC
    STATEMENT_IDENTITIES=1
    PROOF_REVISIONS=2
    PROOF_COURT_CASES=2
    V1_OUTCOME=PROOF_INVALID_BUT_CLAIM_OPEN
    V2_OUTCOME=VERIFIED
    CORRECTED_PROOF_REVIEW_CALLS=1
    CORRECTED_PROOF_SKIPPED_BY_OLD_TERMINAL_CASE=0
    DUPLICATE_V2_COURT_CASES=0
    DUPLICATE_V2_PROOF_REVISIONS=0
    DUPLICATE_V2_PROVIDER_CALLS=0
    DUPLICATE_V2_FACT_PROMOTIONS=0
    CORRECTED_PROOF_POST_RESTORE_CASE_LOSSES=0
    CORRECTED_PROOF_POST_RESTORE_REVISION_LOSSES=0
    CORRECTED_PROOF_POST_RESTORE_FACT_LOSSES=0
    ROOT_HASH_CHANGES=0
    NEGATIVE_REGISTRY_HASH_CHANGES=0
    RESULT=PASS

The production Claim lookup deliberately separates the source proof from the canonical Lemma
authority projection. This preserves corrected proof steps even when Lemma Memory deduplicates the
Statement, while an already verified canonical Claim cannot be downgraded. A permanent Negative
Knowledge rejection during positive Fact projection now rolls back the staged Court projection,
records an admission rejection on the artifact, and leaks no positive state.

### 17.5 Follow-up tests

New Core tests:

- `ClaimProofPatchUntrustedEvidenceInjectionTest`
- `ClaimProofPatchEvidenceHashMismatchTest`
- `ClaimCounterexampleFullContextExactnessTest`
- `ClaimCourtProofRevisionIdentityTest`

New Desktop production tests:

- `DesktopBlindPacketTrustedEvidenceOnlyTest`
- `DesktopRepairEvidenceRestoreRevalidationTest`
- `DesktopRepairerEvidenceAuthorityBoundaryTest`
- `DesktopClaimCourtSemanticContextBindingTest`
- `DesktopClaimCourtQuantifierIsolationTest`
- `DesktopClaimCourtPolarityIsolationTest`
- `DesktopClaimCourtVariableBindingIsolationTest`
- `DesktopCorrectedProofReopensCourtTest`
- `DesktopDuplicateProofRevisionExactlyOnceTest`
- `DesktopCorrectedProofRestoreTest`

The existing `ClaimProofPatchValidatorBoundaryTest` positive case now supplies an explicit trusted
evidence capability. No old assertion was deleted or weakened. The existing
`ClaimSalvageProjectionAtomicityTest` also caught an intermediate negative-projection exception;
the final implementation preserves its original zero-leak behavior.

Focused results after the final code:

| Invocation | Tests | Failures | Errors | Skipped | Result |
|---|---:|---:|---:|---:|---|
| Contracts selected by Core Claim suite | 4 | 0 | 0 | 0 | PASS |
| Core Claim/Claim Court suite | 53 | 0 | 0 | 0 | PASS |
| Server Claim Court prompt suite | 7 | 0 | 0 | 0 | PASS |
| Core dependency selected by Desktop suite | 1 | 0 | 0 | 0 | PASS |
| Desktop Issue 008 production suite | 25 | 0 | 0 | 0 | PASS |

### 17.6 Final regression and quality gate

The final module run passed:

| Module | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| Contracts | 54 | 0 | 0 | 0 |
| Core | 1172 | 0 | 0 | 0 |
| Server | 859 | 0 | 0 | 3 |
| Desktop | 192 | 0 | 0 | 1 |

This is 2277 unit tests with zero failures and zero errors. It includes all Issue 001-008
regressions, including Root Goal, permanent Negative Knowledge, Claim lifecycle, incremental
research persistence, canonical convergence, Semantic Pivot, and strategy-mechanism diversity.

The final `scripts/verify-all.ps1 -Offline` run used Docker Server 29.6.2 and exited 0:

- 2426 unit/compatibility tests plus 26 Failsafe integration tests: 2452 total.
- 0 failures, 0 errors, and 4 conditional unit-test skips.
- All seven integration suites passed, including all five PostgreSQL Testcontainers suites:
  `JdbcMessageRepositoryIT`, `MemoryProofGraphPostgresIT`, `PersistencePostgresIT`,
  `Phase17CheckpointOutboxPerformanceIT`, and `ProviderCallPostgresIT`.
- SpotBugs/FindSecBugs, dependency/security, license, coverage, frozen-source, and Python Sidecar
  gates passed without changing their thresholds.

Generated `target`, logs, checkpoints, and refreshed Phase 17 report files are not committed. No
Root Goal, Negative Knowledge registry, research-finding authority, canonical graph/convergence,
Semantic Pivot, strategy portfolio, provider, concurrency, budget, Temporal, PostgreSQL schema, or
Python Sidecar behavior was changed. Issue 008 is closed; Issue 009 remains untouched.

## 18. End-to-end evidence and local-context follow-up

A second independent audit of remote head
`6629d3a6e31b8a58ccb16224a69366953642d812` followed the complete path from computation or Fact
evidence through Claim Court and back into Typed Memory. It found three remaining Issue 008
boundaries: the computation capability issuer did not bind every Claim dimension, Court Fact
projection discarded structured context, and a new attempt-local Claim could silently inherit the
root context. This follow-up closes all three on the same Issue 008 branch. Issue 009 was not
started.

### 18.1 Pre-fix behavioral evidence

The first black-box tests were written and run before changing production code:

- `DesktopCourtVerifiedFactPreservesContextTest` failed because a Court-verified Fact lost its
  ordered quantifiers and variable bindings during projection.
- `DesktopFactEvidenceConclusionIsolationTest` failed with `expected false but was true`: a Fact
  with the wrong conclusion could be accepted as exact evidence for the current frozen Claim.

These are direct behavioral failures of the old production path, not missing-API compilation
evidence.

### 18.2 Exact computation evidence binding

`ClaimEvidenceSemanticBinding` is now carried by both `ExperimentSpec` and `ExperimentResult` and
is covered by their request/result hashes. It binds:

- problem, Claim ID, statement hash, and full Claim semantic hash;
- statement and conclusion;
- assumptions, ordered quantifiers, variable bindings, scope limitations, and polarity;
- dependency Claim IDs;
- the exact computation domains.

The Desktop capability issuer now requires a nonblank exact target Claim ID, an identical request
and result binding, an independently replay-valid verified result, an exact server-recomputed
binding for the frozen Claim, and an exact result-scope/domain match. It no longer copies the
current frozen semantic hash onto an unbound or partially bound old computation result. Contract
repair and runtime rebinding preserve this immutable binding, and cached results are rehashed for
the requesting binding rather than retaining a stale result identity.

    COMPUTATION EVIDENCE CONTEXT DIAGNOSTIC
    EXACT_CONTEXT_COMPUTATION_CAPABILITIES=1
    BLANK_TARGET_CLAIM_ID_EVIDENCE_ACCEPTS=0
    QUANTIFIER_MISMATCH_EVIDENCE_ACCEPTS=0
    SCOPE_MISMATCH_EVIDENCE_ACCEPTS=0
    POLARITY_MISMATCH_EVIDENCE_ACCEPTS=0
    MISSING_RESULT_BINDING_EVIDENCE_ACCEPTS=0
    RESULT=PASS

### 18.3 Lossless Court Fact projection

A modern verified Fact is now projected from the authoritative `FrozenClaimSnapshot` plus the
blind-verified `ClaimProofRevisionRecord`, not merely from the reduced `ClaimCard`. The projection
retains statement, conclusion, assumptions, ordered quantifiers, variable bindings, scope,
dependencies, statement hash, semantic hash, and polarity. Only run-scoped `artifact://`
references are promoted as Fact artifacts.

`MessageEnvelope` has backward-compatible optional Claim statement hash, semantic hash, and
polarity fields. Claim-bound content identity now includes variable bindings and scope as well as
the other Claim dimensions. `requireSameClaim`, receipt validation, and memory-tier transitions
preserve and compare the complete identity. Legacy envelopes retain their original JSON and hash
vectors because absent optional fields use the legacy hash path.

The JDBC repository needed no schema migration: it persists and restores the complete envelope as
JSONB, so the new fields survive PostgreSQL storage without a parallel column mapping. Checkpoint
round-trip tests independently verify the same property through Typed Memory restore.

    COURT FACT CONTEXT DIAGNOSTIC
    CONCLUSION_MISMATCH_FACT_ACCEPTS=0
    POLARITY_MISMATCH_FACT_ACCEPTS=0
    VERIFIED_FACT_QUANTIFIER_LOSSES=0
    VERIFIED_FACT_BINDING_LOSSES=0
    VERIFIED_FACT_SCOPE_LOSSES=0
    VERIFIED_FACT_POLARITY_LOSSES=0
    CONTEXT_DISTINCT_FACT_COLLISIONS=0
    FACT_EVIDENCE_ROUND_TRIP_FAILURES=0
    RESULT=PASS

### 18.4 Explicit attempt-local Claim context

`ProofAttempt` now carries a versioned list of `ClaimSemanticContextBinding` records. Manifest
version 1 requires every modern attempt-local proposed Claim to have one explicit binding. Missing,
duplicate, unknown-target, or version-inconsistent bindings fail closed. A bound local Claim enters
Court with its own assumptions, ordered quantifiers, variable bindings, scope, and polarity;
absence cannot silently fall back to the root context.

Old serialized attempts with neither field restore as manifest version 0. Their Claims are marked
`LEGACY_INCOMPLETE_SEMANTIC_CONTEXT`; they may remain visible for compatibility but cannot mint a
new exact Claim-bound Fact capability. Route-theorem Claims retain the immutable root context.
Server-generated Research Finding and Pivot Claim additions receive explicit non-authoritative
bindings and still require the existing Issue 003 review path.

    ATTEMPT-LOCAL CLAIM CONTEXT DIAGNOSTIC
    UNBOUND_MODERN_LOCAL_CLAIM_ADMISSIONS=0
    UNBOUND_MODERN_LOCAL_CLAIM_ROOT_FALLBACKS=0
    BOUND_LOCAL_CLAIM_COURT_CASES=1
    LOCAL_CLAIM_QUANTIFIER_FALSE_REFUTATIONS=0
    LOCAL_CLAIM_POLARITY_FALSE_REFUTATIONS=0
    RESULT=PASS

### 18.5 New tests

Contracts:

- `ClaimSemanticBindingContractsTest` (4 tests)
- `ProofAttemptSemanticContextMigrationTest` (1 test)

Core:

- `ClaimBoundMessageReceiptTest` (2 tests)

Desktop production path:

- `DesktopComputationEvidenceFullContextBindingTest`
- `DesktopBlankTargetClaimIdEvidenceRejectedTest`
- `DesktopFactEvidenceConclusionIsolationTest`
- `DesktopFactEvidencePolarityIsolationTest`
- `DesktopCourtVerifiedFactPreservesContextTest`
- `DesktopCourtFactEvidenceRoundTripTest`
- `DesktopContextDistinctFactCollisionTest`
- `DesktopAttemptLocalClaimContextBindingTest`
- `DesktopUnboundLocalClaimFailsClosedTest`
- `DesktopLocalClaimQuantifierIsolationTest`
- `DesktopLocalClaimPolarityIsolationTest`

The Desktop tests invoke the real coordinator capability issuer, Claim Court integration, Fact
projection, Typed Memory, and checkpoint restore through the production harness. They do not call
a real model provider or external network.

### 18.6 Validation

The initial complete verification correctly failed the unchanged Contracts coverage gate after the
new records were introduced:

    contracts_adjusted_line_ge_90=FAIL
    contracts_adjusted_branch_ge_85=FAIL

No threshold or exclusion was changed. Additional Contract behavior and rejection-branch tests
raised the final measured coverage to:

    CONTRACTS_ADJUSTED_LINE=91.831140%
    CONTRACTS_ADJUSTED_BRANCH=85.952012%

The final module results were:

| Module | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| Contracts | 59 | 0 | 0 | 0 |
| Core | 1174 | 0 | 0 | 0 |
| Server | 859 | 0 | 0 | 3 |
| Desktop | 203 | 0 | 0 | 1 |
| Compatibility | 149 | 0 | 0 | 0 |

The final `scripts/verify-all.ps1 -Offline` run used Docker Server 29.6.2 and exited 0:

- 2444 unit/compatibility tests plus 26 Failsafe integration tests: 2470 total;
- 0 failures and 0 errors, with 4 conditional unit-test skips;
- all seven integration suites passed, including `JdbcMessageRepositoryIT`,
  `MemoryProofGraphPostgresIT`, `PersistencePostgresIT`,
  `Phase17CheckpointOutboxPerformanceIT`, and `ProviderCallPostgresIT`;
- SpotBugs/FindSecBugs, OWASP, licenses, secret scan, coverage, frozen-source, and Python Sidecar
  gates all passed without changing thresholds.

Generated `target`, logs, checkpoints, and refreshed Phase 17 report JSON files are not committed.
There is no PostgreSQL migration or checkpoint schema bump. Root Goal, permanent Negative
Knowledge, Claim lifecycle, Research Finding authority, canonical graph/convergence, Semantic
Pivot, strategy diversity, provider, concurrency, budget, Temporal, and Python Sidecar behavior
remain protected by the full regression. Issue 008 is closed; Issue 009 remains untouched.
