# Issue 009: Typed Mathematical Artifact Broker and Verifiable Attribution

## 1. Status and Git provenance

- Status: CLOSED after the final exact-attribution audit patch, the Issue 009 suites, the
  Issue 001-008 regression, and the complete release gate passed.
- Branch: `fix/009-mathematical-artifact-broker`
- Baseline branch: `java`
- Baseline commit: `20c0c9fdb61cc44b508be71aa223ed64ccd01b2f`
- Phase 009A commit: `0ab219e` (`fix(broker): transmit typed mathematical artifacts`)
- Phase 009B commit: `e4555fa` (`fix(broker): verify explicit artifact use and downstream utility`)
- Legacy compatibility commit: `867168c` (`fix(broker): preserve legacy message broker compatibility`)
- Security-gate commit: `5d78b6b` (`fix(broker): satisfy artifact security gates`)
- Coverage-policy test commit: `e6d3e8b` (`test(broker): cover artifact contracts and branch policies`)
- Exact-attribution closure commit: `7f7061f`
  (`fix(broker): bind utility to exact post-baseline effects`)
- Baseline checkpoint schema: 16
- Resulting checkpoint schema: 17

The branch was created from the completed Issue 008 baseline. Nothing was committed to `java`
or `main`. Issue 010 has not been started.

## 2. Production-path audit

The pre-fix audit followed every legacy broker boundary in production:

1. `DesktopSolveCoordinator.failureMessage(...)` created a `FAILURE_RECORD` from a route failure
   class and `recommendedAction`.
2. The legacy `MessageBroker.publish(...)` path could admit that generic record for other routes.
3. `consumeBrokerContext(...)` injected raw `MessageEnvelope` objects into `broker_messages`.
4. `acknowledgeConsumedMessages(...)` attributed every proof step in a later attempt to every
   pending message, without an explicit mathematical-use declaration.
5. The legacy utility path sampled proof debt immediately before and after in the same state.
6. Verified-claim distribution was route-success oriented and did not reliably publish Court-
   verified local claims salvaged from failed routes.
7. `MessageStoreSnapshot` restored delivery and receipt state, but had no independently typed
   mathematical artifact, use-manifest, lineage, effect, or invalidation projection.

The resulting production flow now has separate modern entry points:

```text
Claim Court / trusted counterexample / exact proof audit authority
-> BrokerArtifactCompiler
-> BrokerArtifactAuthorityResolver
-> BrokerArtifactPublicationService
-> BrokerArtifactTargetingService
-> MathematicalArtifactBroker.publish
-> MathematicalArtifactBroker.consumeForPrompt
-> bounded BrokerPromptArtifact projection
-> BrokerArtifactUseManifest
-> BrokerArtifactReceiptService and BrokerArtifactUseLedger
-> BrokerArtifactEffectVerifier
-> BrokerArtifactUtilityLedger
-> checkpoint snapshot / restore / invalidation
```

The old `MessageEnvelope` API remains available for Typed Memory, old checkpoints, and legacy
broker compatibility. It is not the authority carrier for the modern production broker.

## 3. Test-first pre-fix behavioral evidence

The five black-box tests were first run against the Issue 008 baseline without relying on a
missing Issue 009 class. They directly exposed the old behavior:

```text
GENERIC_FAILURE_BROADCAST_ATTEMPTS=1
GENERIC_FAILURE_MESSAGES_ADMITTED=1
TARGET_PROMPT_GENERIC_FAILURE_MESSAGES=1
EXPECTED=0

SALVAGED_VERIFIED_CLAIMS=1
BROKER_ARTIFACTS_PUBLISHED=0
TARGET_ROUTE_RECEIVED=0
EXPECTED_TARGET_ROUTE_RECEIVED=1

DELIVERED_MESSAGES=1
EXPLICIT_MESSAGE_USES=0
ACCEPTED_USED_RECEIPTS=1
FALSE_UTILITY_RECORDS=1
EXPECTED_FALSE_UTILITY_RECORDS=0

SEMANTICALLY_DISTINCT_MESSAGES=2
BROKER_RECORDS_EXPECTED=2
BROKER_RECORDS_ACTUAL=1

ACTUAL_DEBT_REDUCTION=0
EXPECTED_DEBT_REDUCTION>0
```

These are pre-fix behavioral failures, not merely evidence that a proposed API was absent.

## 4. Typed artifact contract

`BrokerArtifactEnvelope` is independent from `MessageEnvelope` and binds server-generated
identity and authority to:

- `problemHash` and immutable `rootGoalHash`;
- artifact type and authority;
- full typed payload;
- source Route, Attempt, Claim, and Claim Revision;
- source obligations and proof steps;
- evidence references;
- reusable consequences and forbidden inferences;
- retained verified Claim IDs and next exact obligation;
- creation round, TTL, semantic hash, content hash, and schema version.

The supported payload family is:

- `VERIFIED_CLAIM`
- `VERIFIED_COUNTEREXAMPLE`
- `VERIFIED_NO_GO`
- `REVIEWED_OBSTRUCTION`
- `REUSABLE_CONSTRUCTION`
- `EXACT_EXAMPLE`
- `FORMAL_CERTIFICATE`
- `BOUNDED_OBSERVATION`

Claim-bound payloads retain statement, conclusion, assumptions, ordered quantifiers, variable
bindings, scope limitations, polarity, statement hash, semantic hash, and dependency Claim IDs.
Counterexamples additionally retain exact target identity, witness, evidence references, and
affected exact obligations. Reviewed obstructions retain the exact failed step, issue kind,
repairability, retained valid Claims, first missing justification, and next obligation.

## 5. Authority matrix

The compiler resolves authority only from trusted server projections:

| Artifact type | Authority | Required source |
| --- | --- | --- |
| `VERIFIED_CLAIM` | `VERIFIED` | Court VERIFIED plus blind-verified proof revision and active Fact/lifecycle projection |
| `VERIFIED_COUNTEREXAMPLE` | `REFUTED` | Court REFUTED plus applied exact counterexample and Issue 002 trusted authority |
| `VERIFIED_NO_GO` | `REFUTED` | Refuted statement with exact blocked inference |
| `REVIEWED_OBSTRUCTION` | `REVIEWED_OPEN` | Exact Claim proof audit / located failed proof step |
| `REUSABLE_CONSTRUCTION` | `VERIFIED` | Verified construction Claim |
| `EXACT_EXAMPLE` | `VERIFIED` or `BOUNDED` | Audited exact example or explicitly bounded evidence |
| `FORMAL_CERTIFICATE` | `VERIFIED` | Trusted formal-checker certificate |
| `BOUNDED_OBSERVATION` | `BOUNDED` | Evidence with an explicit finite scope |

Both `sourceAuthorityValid` and `sourceProjectionActive` must hold. A raw Research Finding,
model declaration, model-supplied confidence, or model-supplied authority cannot compile a
modern artifact. Artifact ID, authority, semantic hash, and content hash are server-owned.

## 6. Mathematical/control boundary

`BrokerControlBoundaryPolicy` rejects generic or operational traffic with stable codes such as
`GENERIC_FAILURE_RECORD`, `NON_MATHEMATICAL_CONTROL_MESSAGE`,
`MISSING_EXACT_MATHEMATICAL_PAYLOAD`, and `UNAUTHORIZED_ARTIFACT_AUTHORITY`.

In `DesktopSolveCoordinator`, a route failure remains available to local failure audit,
Temporary Negative Memory, proof-task scheduling, recovery, and control events. Even when
`shareFailureRecords` is enabled, the modern cross-route broker records a rejection event and
does not create a mathematical delivery. Values such as `BRIDGE`, `create_minimal_bridge`,
`REVISE`, and `SWITCH_REPRESENTATION` therefore cannot enter another route's mathematical
prompt.

The modern broker does not promote Facts, verify Claims, register permanent negatives, close
the main goal, or automatically apply a Semantic Pivot. Utility never changes mathematical
authority.

## 7. Failed-route salvage and publication

`DesktopSolveCoordinator.distributeVerifiedClaims()` now enumerates authoritative Claim Court
records rather than only successful routes. `BrokerArtifactPublicationService` admits an
artifact based on its trusted source projection, not the terminal status of its route.

This publishes, exactly once per stable revision/semantic identity:

- Court-verified local Claims from failed routes;
- exact verified counterexamples from failed routes;
- reviewed-open proof obstructions with an exact failed step;
- the corresponding artifacts from successful routes.

The publication ledger and semantic registry make replay idempotent. The source route is never
selected as its own cross-route target.

## 8. Semantic identity and relevance routing

`BrokerArtifactSemanticKey` binds problem hash, root-goal hash, type, authority, complete Claim
context, source Claim Revision, exact target, and counterexample witness hash. Thus `forall P`
and `exists P`, positive and negative polarity, distinct scopes, distinct variable bindings,
different witnesses, and different trusted revisions do not collide.

`RouteMathematicalNeedProfile` contains only server-owned route state:

- active canonical target IDs;
- unresolved required Claim keys;
- unresolved dependency Claim keys;
- focused bottleneck families;
- active object roles;
- exact proof issue kinds;
- strategy epoch.

`BrokerArtifactTargetingService` uses exact identifiers and typed intersections. Plain-text
similarity is not sufficient. Authority provides deterministic priority only after relevance
has been established. Irrelevant routes receive no delivery.

## 9. Bounded prompt projection

`BrokerArtifactPromptProjectionService` emits a bounded `BrokerPromptArtifact` containing the
minimum typed mathematical sidecar needed downstream: exact statement/context, authority,
source revision, evidence, reusable consequences, blocked inferences, next obligation, and
allowed use kinds. Counterexamples additionally project the exact target Claim ID, target
semantic hash, witness, and exact affected obligations. Reviewed obstructions project the exact
failed step, issue kind, repairability, and first missing justification. Verified no-go
artifacts project the exact blocked inference.

The prompt contract explicitly states that receipt does not imply use. `REVIEWED_OPEN` is not a
proved premise, bounded evidence cannot prove an unrestricted Claim, and final-proof citation
is restricted to compatible verified artifact types. Prompt consumption orders queued artifacts
by server-computed relevance priority, then stable delivery ID; previously verified active
utility may only refine the priority after exact relevance has already been established.

`InitialExplorationTurn` gained an optional, backward-compatible
`BrokerArtifactUseManifest`. The five-argument constructor remains supported.

## 10. Explicit use, receipts, lineage, and utility

Every claimed use must name an actually delivered artifact ID, a compatible
`BrokerArtifactUseKind`, and real affected proof-step, Claim, or obligation IDs. Counterexample
and verified-no-go uses must echo the server-projected target semantic hash and bind exactly the
payload target Claim; their obligation targets must remain within the payload's exact target
set. Reviewed-obstruction repair, focus, and Pivot uses must bind the exact failed step or next
exact obligation. The use ledger validates this manifest against the consumed provider request.
A missing manifest produces `NOT_USED`; parsing or prompt inclusion does not produce an accepted
use.

The receipt state machine distinguishes:

- `NOT_USED`
- `USED_PENDING_EFFECT`
- `USED_EFFECT_VERIFIED`
- `REJECTED_INVALID_USE`
- `INVALIDATED`
- `EXPIRED`

Lineage binds artifact, delivery, explicit use kind, provider request, downstream proof steps,
Claims, obligations, local repair, Pivot, and computation-plan IDs. The server writes the reverse
binding when the concrete repair, Pivot, or computation is created. An unrelated pre-existing ID
on the same Route cannot satisfy the lineage.

`BrokerArtifactEffectVerifier` compares the durable prompt-consumption baseline with later
authoritative state. Verified Claims, refuted Claims, committed steps, retired dependencies,
repairs, Pivots, and computation plans must be post-baseline additions; closed obligations must
have been open at consumption. Only those deltas can produce committed-step reuse, derived
verified Claim, exact refutation, closed obligation, retired dependency, focus change, local
repair, Semantic Pivot, computation plan, or final-proof citation.

Proof debt is sampled before provider use at prompt consumption and after downstream
integration. Utility is written only when both explicit lineage and a verified effect exist.
No model-supplied usage summary can create utility.

## 11. Snapshot, migration, invalidation, and recovery

Checkpoint schema 16 -> 17 adds seven independent snapshots:

- artifact registry;
- publication ledger;
- deliveries and prompt-consumption baselines;
- receipts;
- explicit-use lineage;
- verified utility;
- invalidations.

Missing v16 fields deserialize to empty. Deterministic legacy migration accepts only complete,
trusted mathematical records. A semantically complete verified lemma can migrate; generic
failure, repair, bridge, strategy-rewrite, and unverified-insight messages remain audit-only and
are not delivered. Restored legacy `PROMPT_CONSUMED` deliveries are not reattached as active
pending deliveries, do not receive automatic all-proof-step receipts, and do not contribute
scheduler-active utility. The legacy message store remains checkpoint audit data. Migration
calls no provider.

Invalidation prevents later delivery and excludes invalidated utility from active attribution.
Stable IDs make artifact, publication, delivery, prompt consumption, receipt, lineage, utility,
and invalidation replay idempotent.

The atomicity test injects failures after registry admission, publication, delivery, prompt
consumption, use receipt, lineage, and utility. In-memory transaction rollback leaves no partial
projection. The hard-crash test serializes real durable prompt and receipt frontiers, constructs
a fresh broker, restores, retries, and observes one artifact, delivery, receipt, lineage, and
utility. Provider-request replay returns the original durable consumption rather than creating
a duplicate.

## 12. Modified files

### Contracts

- Added `BrokerArtifactEnvelope`, `BrokerArtifactPayload`, all eight typed payload records,
  `BrokerArtifactType`, `BrokerArtifactAuthority`, `BrokerClaimSemanticContext`,
  `BrokerReusableConsequence`, `BrokerBlockedInference`, `BrokerPromptArtifact`,
  `BrokerArtifactUseManifest`, `BrokerArtifactUseClaim`, `BrokerArtifactUseKind`,
  `BrokerArtifactReceiptStatus`, and `BrokerVerifiedEffectType`.
- Extended `InitialExplorationTurn` only with the optional use manifest and a compatibility
  constructor.
- Added `BrokerArtifactContractCoverageTest` for complete contract construction, defensive
  copies, enum coverage, bounded-scope failure closure, and duplicate-use rejection.

### Core production

- Added the `communication.artifact` package: compiler, authority resolver, compilation
  request/result, control boundary, semantic key, targeting, prompt projection, publication,
  registry, delivery, receipt, use, lineage, effect, utility, invalidation, snapshots, values,
  failure points, `RouteMathematicalNeedProfile`, and `MathematicalArtifactBroker`.
- Kept legacy `MessageBroker` compatible while strengthening its legacy semantic key with
  conclusion, quantifiers, bindings, scope, polarity, and Claim hashes.
- Kept the legacy `MessageAdmissionPolicy` behavior, including configured legacy failure-record
  sharing, so old checkpoints and parity tests remain compatible. Modern Desktop production
  uses the independent typed boundary instead.

### Desktop production

- `DesktopSolveCoordinator`: trusted compilation/publication, failed-route salvage, exact route
  targeting, bounded prompt consumption, staged use manifest, receipt/effect/utility handling,
  local-only generic failure audit, v16 migration, snapshot/restore, and structured diagnostic
  artifacts.
- `DesktopSolveCheckpoint`: schema 17 and the seven broker snapshots.

### Server production

- `PromptCatalog`: typed artifact authority and explicit-use rules for independent exploration.

### Tests

- Core Phase 009A: 12 required suites for contracts, authority, compiler, semantic identity,
  control isolation, targeting, relevance, projection, publication, salvage, snapshot, and
  problem-independence.
- Core Phase 009B: 11 required suites for explicit use, compatibility, receipt state, baseline,
  lineage, effects, utility, false-utility rejection, invalidation, and snapshot restore.
- Core coverage: `BrokerArtifactBranchCoverageTest` exercises all authority sources, projection
  validity, exact relevance branches, authority priorities, semantic contexts, every supported
  downstream effect, and unchanged-state rejection.
- Final exact-attribution audit: pre-existing-state isolation, exact Counterexample/No-Go target
  validation, exact obstruction repair targets, repair/Pivot/computation reverse lineage,
  complete prompt payload projection, priority selection, legacy scheduler isolation, ledger
  idempotency, migration trust boundaries, and rollback/hard-crash frontiers.
- Server: the five required public prompt/contract boundary suites.
- Desktop: the five pre-fix black boxes plus production publication, targeting, projection,
  explicit use, effects, not-used behavior, control isolation, invalidation, atomicity, hard
  crash, multi-round restore, legacy migration, and protected-authority suites.

No production code or test depends on GCD, prime support, hitting sets, translation
periodicity, or the greedy integer-sequence problem.

## 13. Issue 009 focused test results

All required tests ran without network providers, Python sidecars, or external model calls:

```text
Phase 009 Core required suites:      23 tests, 0 failures, 0 errors, 0 skipped
Phase 009 Server required suites:     5 tests, 0 failures, 0 errors, 0 skipped
Phase 009 Desktop required suites:   21 tests, 0 failures, 0 errors, 0 skipped
Additional contract coverage:        2 tests, 0 failures, 0 errors, 0 skipped
Additional core branch coverage:     5 tests, 0 failures, 0 errors, 0 skipped
Final exact-attribution patch:       14 tests, 0 failures, 0 errors, 0 skipped
```

The fixed black-box outputs are:

```text
GENERIC_FAILURE_MESSAGES_ADMITTED=0
TARGET_PROMPT_GENERIC_FAILURE_MESSAGES=0
BROKER_ARTIFACTS_PUBLISHED=1
TARGET_ROUTE_RECEIVED=1
EXPLICIT_MESSAGE_USES=0
ACCEPTED_USED_RECEIPTS=0
FALSE_UTILITY_RECORDS=0
BROKER_RECORDS_ACTUAL=2
ACTUAL_DEBT_REDUCTION=1.5
```

The final audit was first run against the pre-patch implementation. All five black-box classes
failed behaviorally, rather than merely failing to compile:

```text
Tests run: 5, Failures: 5, Errors: 0
COUNTEREXAMPLE_WRONG_TARGET_USE_ACCEPTS=1
COUNTEREXAMPLE_WRONG_OBLIGATION_USE_ACCEPTS=1
OBSTRUCTION_WRONG_REPAIR_TARGET_ACCEPTS=1
PREEXISTING_VERIFIED_CLAIM_UTILITIES=1
PREEXISTING_REFUTED_CLAIM_UTILITIES=1
PREEXISTING_CLOSED_OBLIGATION_UTILITIES=1
COUNTEREXAMPLE_WITNESS_PROJECTION_LOSSES=1
OBSTRUCTION_DETAIL_PROJECTION_LOSSES=1
UNRELATED_REPAIR_EFFECTS=1
UNRELATED_PIVOT_EFFECTS=1
UNRELATED_COMPUTATION_EFFECTS=1
```

After commit `7f7061f`, the asserted diagnostic is:

```text
EXACT ATTRIBUTION CLOSURE DIAGNOSTIC
PREEXISTING_VERIFIED_CLAIM_UTILITIES=0
PREEXISTING_REFUTED_CLAIM_UTILITIES=0
PREEXISTING_CLOSED_OBLIGATION_UTILITIES=0
COUNTEREXAMPLE_WRONG_TARGET_USE_ACCEPTS=0
COUNTEREXAMPLE_WRONG_OBLIGATION_USE_ACCEPTS=0
OBSTRUCTION_WRONG_REPAIR_TARGET_ACCEPTS=0
UNRELATED_REPAIR_EFFECTS=0
UNRELATED_PIVOT_EFFECTS=0
UNRELATED_COMPUTATION_EFFECTS=0
LEGACY_AUTO_ACCEPTED_RECEIPTS=0
LEGACY_SCHEDULER_ACTIVE_UTILITIES=0
COUNTEREXAMPLE_WITNESS_PROJECTION_LOSSES=0
OBSTRUCTION_DETAIL_PROJECTION_LOSSES=0
HIGH_PRIORITY_ARTIFACT_EVICTIONS=0
RESULT=PASS
```

## 14. Twenty-round production diagnostic

Every value below was calculated from broker stores, prompt projections, receipts, route state,
Claim state, proof state, and serialized checkpoint state, and was asserted before printing.

```text
MATHEMATICAL ARTIFACT BROKER DIAGNOSTIC
ROUNDS=20
RESTORE_ROUND=10
VERIFIED_CLAIM_ARTIFACTS=20
VERIFIED_COUNTEREXAMPLE_ARTIFACTS=10
REVIEWED_OBSTRUCTION_ARTIFACTS=20
TOTAL_ADMITTED_ARTIFACTS=50
GENERIC_CONTROL_BROADCAST_ATTEMPTS=20
GENERIC_CONTROL_BROADCAST_REJECTIONS=20
GENERIC_CONTROL_PROMPT_LEAKS=0
SALVAGED_FAILED_ROUTE_CLAIMS_PUBLISHED=20
SALVAGED_FAILED_ROUTE_CLAIM_LOSSES=0
RELEVANT_ROUTE_DELIVERIES=50
IRRELEVANT_ROUTE_DELIVERIES=0
SEMANTIC_DEDUPE_COLLISIONS=0
EXPLICIT_USE_MANIFESTS=30
USED_PENDING_EFFECT_RECEIPTS=30
USED_EFFECT_VERIFIED_RECEIPTS=30
NOT_USED_RECEIPTS=20
FALSE_USED_RECEIPTS=0
ALL_STEPS_AUTO_REFERENCED_RECEIPTS=0
INVALID_USE_ACCEPTS=0
VERIFIED_DOWNSTREAM_EFFECTS=30
UTILITY_WITHOUT_EXPLICIT_LINEAGE=0
UTILITY_WITHOUT_VERIFIED_EFFECT=0
PROOF_DEBT_BASELINE_ERRORS=0
FALSE_DEBT_REDUCTIONS=0
DUPLICATE_ARTIFACTS=0
DUPLICATE_DELIVERIES=0
DUPLICATE_RECEIPTS=0
DUPLICATE_UTILITY_RECORDS=0
POST_RESTORE_ARTIFACT_LOSSES=0
POST_RESTORE_DELIVERY_REPLAYS=0
POST_RESTORE_PROVIDER_CALL_REPLAYS=0
POST_RESTORE_RECEIPT_REPLAYS=0
POST_RESTORE_UTILITY_REPLAYS=0
INVALIDATED_ARTIFACT_REDELIVERIES=0
INVALIDATED_UTILITY_COUNTED_AS_ACTIVE=0
ROOT_HASH_CHANGES=0
NEGATIVE_REGISTRY_HASH_CHANGES=0
ATTEMPT_ARTIFACT_LEDGER_HASH_CHANGES=0
CLAIM_LIFECYCLE_HASH_CHANGES=0
RESEARCH_CHECKPOINT_LEDGER_HASH_CHANGES=0
CANONICALIZATION_REGISTRY_HASH_CHANGES=0
CONVERGENCE_STATE_HASH_CHANGES=0
SEMANTIC_PIVOT_LEDGER_HASH_CHANGES=0
STRATEGY_PORTFOLIO_HASH_CHANGES=0
CLAIM_COURT_HASH_CHANGES=0
DIRECT_FACT_PROMOTIONS=0
DIRECT_CLAIM_VERIFICATIONS=0
DIRECT_NEGATIVE_REGISTRATIONS=0
MAIN_GOAL_CLOSURES=0
REGISTRY_HASH_BEFORE_RESTORE=dd0241eaed3bdfddfeae8f307e4399363ff4d140bd7ffc2e197b2e076f77a241
REGISTRY_HASH_AFTER_RESTORE=dd0241eaed3bdfddfeae8f307e4399363ff4d140bd7ffc2e197b2e076f77a241
USE_HASH_BEFORE_RESTORE=d57f51985c649f0a90388d2a30c4e33dd06adbf78e253b5a5df2c368c4eafda7
USE_HASH_AFTER_RESTORE=d57f51985c649f0a90388d2a30c4e33dd06adbf78e253b5a5df2c368c4eafda7
UTILITY_HASH_BEFORE_RESTORE=e49e722ba9730999edd970e9a9721bf31c8b1636081c004934178677d7a421ad
UTILITY_HASH_AFTER_RESTORE=e49e722ba9730999edd970e9a9721bf31c8b1636081c004934178677d7a421ad
RESULT=PASS
```

## 15. Atomicity and hard-crash results

```text
PARTIAL_ARTIFACT_WRITES=0
PARTIAL_DELIVERIES=0
PARTIAL_PROMPT_CONSUMPTIONS=0
PARTIAL_RECEIPTS=0
PARTIAL_LINEAGE_WRITES=0
PARTIAL_UTILITY_WRITES=0
TASK_LEASE_LEAKS=0
```

Post-crash restore and retry produced one artifact, one delivery, one receipt, one lineage, and
one utility. No duplicate provider request, delivery, receipt, lineage, or utility was observed.

## 16. Issue 001-008 regression and protected authority

The explicit Issue 001-008 regression was split into 13 Windows-safe batches because a single
command exceeded the Windows command-line limit:

```text
ISSUE_001_008_EXPLICIT_TEST_CLASSES=325
ISSUE_001_008_BATCHES=13
ISSUE_001_008_REGRESSION=PASS
```

Relative to baseline `20c0c9f`, 33 protected production paths were checked and none changed:

```text
PROTECTED_PATHS=33
PROTECTED_DIFF_COUNT=0
PROTECTED_FILES_NO_DIFF=PASS
```

This includes the Root Goal, permanent-negative authority, Claim lifecycle and Court authority,
Research Checkpoint authority, proof-graph convergence, Semantic Pivot, Strategy Portfolio, and
`MessageEnvelope` files named in the Issue 009 instructions.

## 17. Module regression and full verification

The final `verify-all.ps1 -Offline` run used the local Docker engine and completed successfully.
Unit-test report totals were:

```text
contracts:      61 tests, 0 failures, 0 errors, 0 skipped
core:         1215 tests, 0 failures, 0 errors, 0 skipped
server:        864 tests, 0 failures, 0 errors, 3 skipped
desktop:       225 tests, 0 failures, 0 errors, 1 skipped
compatibility: 149 tests, 0 failures, 0 errors, 0 skipped
TOTAL UNIT:   2514 tests, 0 failures, 0 errors, 4 skipped
```

Failsafe ran 26 integration tests with zero failures, errors, or skips. The requested PostgreSQL
Testcontainers suites all passed:

```text
JdbcMessageRepositoryIT:                 4/4 PASS
MemoryProofGraphPostgresIT:              4/4 PASS
PersistencePostgresIT:                   9/9 PASS
Phase17CheckpointOutboxPerformanceIT:    1/1 PASS
ProviderCallPostgresIT:                  3/3 PASS
```

No coverage, security, license, performance, or source-immutability threshold was reduced. Final
coverage was:

```text
contracts adjusted line:   92.016881% PASS (gate 90%)
contracts adjusted branch: 85.973725% PASS (gate 85%)
core line:                  91.451606% PASS (gate 85%)
core branch:                75.543336% PASS (gate 75%)
server line:                87.728838% PASS (gate 70%)
desktop line:               78.549718% PASS (gate 70%)
critical scenarios:         9/9 PASS
```

Security and release gates also passed:

```text
SpotBugs / FindSecBugs: 0 findings across all five code modules
OWASP Dependency-Check: 115 dependencies scanned, 0 visible findings
Secret scan: 1769 files scanned, 0 findings
Licenses: 111 components, 0 missing, 0 unreviewed
Original source immutability: PASS
FULL VERIFICATION: PASS
```

## 18. Diff and worktree

The final exact-attribution production/test patch is:

```text
27 files changed, 1766 insertions(+), 180 deletions(-)
```

Only Issue 009 source, compatibility adapters, tests, and this record are included. Generated
coverage, security, and license report refreshes were restored after verification. No `target`,
log, checkpoint, database, cache, or temporary file was committed.

## 19. Final acceptance

- Typed mathematical artifacts: PASS.
- Generic failure/control prompt leakage: 0.
- Failed-route verified Claim loss: 0.
- Semantic dedupe collisions: 0.
- Irrelevant-route deliveries: 0.
- False used receipts and automatic all-step attribution: 0.
- Utility without explicit lineage or verified effect: 0.
- Utility from pre-existing Verified/Refuted/Closed state: 0.
- Wrong exact target and unrelated repair/Pivot/computation attribution: 0.
- Legacy automatic receipt and scheduler-active utility: 0.
- Counterexample/obstruction prompt-detail loss and priority eviction: 0.
- Restore loss/replay and invalidated redelivery: 0.
- Root and Issue 002-008 authority hash changes: 0.
- Issue 001-008 explicit regression: PASS.
- Protected authority files no-diff: PASS.
- Full verification, Docker/PostgreSQL, coverage, security, license, and source immutability:
  PASS.
- Issue 010 and all unrelated architecture work: not started.

Run-generated `target`, log, checkpoint, database, and refreshed report files are not included in
the commits.
