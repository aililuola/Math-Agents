# Issue 014: Structured-output recovery and terminal usage accounting

## Scope and baseline

This repair was opened on `fix/014-structured-output-recovery-accounting` from
`a922a9a5d07188d503a8b708a75a1c30f26f871b`. It addresses the first defect found by the
authorized five-key real-provider benchmark after Issues 001-013 had passed their release gates.
It does not resume or rewrite a stopped campaign and does not weaken any mathematical-authority,
security, concurrency, or cost gate.

## Real-run failure evidence

The stopped campaign is preserved at
`benchmark/olympiad-5key-v1/results/real-20260823T222112Z`. P01/T1 stopped before strategy
admission with both of these hard findings:

| Code | Meaning |
| --- | --- |
| `ISSUE_011_STRUCTURED_STRATEGY_OUTPUT_UNRECOVERABLE` | Primary strategy output, artifact recovery, and JSON repair did not yield a complete typed artifact. |
| `ISSUE_013_REPAIR_CALL_CHECKPOINT_ACCOUNTING_GAP` | The last semantic checkpoint preceded two billed recovery calls, while the terminal ledger correctly retained them. |

The four physical calls consumed 26,500 input tokens and 28,575 output tokens, cost
USD 0.036387750, and took 254,596 ms. The old semantic checkpoint contained the two calls at its
last complete recovery frontier; the terminal ledger and immutable provider artifacts contained all
four calls. The campaign was frozen, given a hard-gate stop report, sanitized, and packaged. It was
never resumed.

## Test-first evidence

The first focused run failed five assertions against the old production behavior:

1. The frozen 34-run token/cost totals still described the smaller v2 envelopes.
2. The primary strategy output limit was 10,000 tokens.
3. Artifact recovery inherited the same 10,000-token limit.
4. JSON repair used an 8,192-token limit.
5. The stopped-run accounting model treated a valid terminal usage extension as checkpoint drift.

After raising the visible tier limits, the new production-chain test exposed a second hidden clamp:
post-failure recovery still received 16,000 tokens. The final failing envelopes were therefore
`[32000,16000]` and `[32000,16000,16000]` before that fallback was removed.

An initial attempt to persist catch-time usage directly into the semantic checkpoint also caused
three existing regressions. It moved the semantic checkpoint past its last completed mathematical
frontier, created a state file before the first intentional checkpoint, and compared frozen-price
budget commitments with actual provider cost. That design was discarded rather than weakening the
old tests.

## Production repair

The implemented design keeps the two authorities distinct:

| Concern | Final behavior |
| --- | --- |
| Primary and recovery output headroom | Primary strategy, compact artifact recovery, post-failure recovery, and JSON repair all use the frozen 32,000-token benchmark ceiling. |
| Recovery prompt | Requests exactly one compact schema-conforming public artifact, without private reasoning, prose, or fabricated evidence. |
| Semantic checkpoint | Remains the last complete mathematical/recovery frontier and is never advanced merely because a later call was billed. |
| Terminal usage | May monotonically extend the semantic checkpoint only when the extension is exactly reconstructible from immutable durable provider-request artifacts. |
| Fail-closed behavior | Usage regression, durable evidence conflict, missing durable evidence for an extension, or mismatched totals is an Issue 013 violation. |
| Exported evidence | `usage-reconciliation.json` records checkpoint usage, terminal usage, post-checkpoint deltas, reconciliation status, and durable evidence count. |

The benchmark token plan reserves 16,000 average input tokens plus a 32,000-token output envelope
for every physical call. Rounds remain unchanged; the final SMOKE and CORE call caps also include
the bounded pre-route admission fan-out described below.

| Tier | Calls | Rounds | Run token cap | Output cap per call |
| --- | ---: | ---: | ---: | ---: |
| SMOKE | 48 | 6 | 2,304,000 | 32,000 |
| CORE | 48 | 8 | 2,304,000 | 32,000 |
| ADVANCED | 64 | 12 | 3,072,000 | 32,000 |
| STRESS | 96 | 16 | 4,608,000 | 32,000 |

The immutable 34-run ceiling is 2,304 calls and 110,592,000 tokens. At the frozen worst-case
output price it is USD 96.21504, leaving USD 3.78496 below the separately enforced USD 100 cap.

## Focused verification

The focused matrix executed 28 tests with zero failures, errors, or skips. It included the new
production Coordinator recovery/accounting test, plan and executor tests, exporter observation
tests, the real benchmark production path with a fake provider, and both existing failure/checkpoint
usage regressions.

```text
STRUCTURED OUTPUT RECOVERY ACCOUNTING DIAGNOSTIC
PRIMARY_OUTPUT_LIMIT=32000
ARTIFACT_RECOVERY_OUTPUT_LIMIT=32000
JSON_REPAIR_OUTPUT_LIMIT=32000
BILLED_PROVIDER_CALLS=4
SEMANTIC_CHECKPOINT_PROVIDER_CALLS=2
TERMINAL_LEDGER_PROVIDER_CALLS=4
DURABLE_PROVIDER_CALL_EVIDENCE=4
POST_CHECKPOINT_PROVIDER_CALLS=2
UNADMITTED_STRATEGY_LEAKS=0
RESULT=PASS
```

The success branch also produced three typed strategies after one compact artifact-recovery call.
The failure branch admitted no strategy or route, retained all four physical calls, and reconciled
the two post-checkpoint calls without changing the semantic checkpoint.

### Static and full release gates

The first complete release run reached Desktop SpotBugs after all 369 Desktop tests had passed and
reported one `DLS_DEAD_LOCAL_STORE`: the reconciliation status local had an initializer that every
normal and exceptional branch replaced. The initializer was removed, the focused 28-test matrix
passed again, and a no-test Maven verify reported zero SpotBugs findings in every module.

The final `scripts/verify-all.ps1 -Offline` run completed with `FULL VERIFICATION: PASS`:

| Module/path | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| Contracts | 65 | 0 | 0 | 0 |
| Core | 1,408 | 0 | 0 | 0 |
| Server unit tests | 928 | 0 | 0 | 3 |
| Server PostgreSQL/security integration tests | 27 | 0 | 0 | 0 |
| Desktop | 369 | 0 | 0 | 3 |
| Compatibility | 149 | 0 | 0 | 0 |
| **Total** | **2,946** | **0** | **0** | **6** |

The same run passed Docker/Testcontainers against PostgreSQL 18.4, all seven Flyway migrations,
SpotBugs/FindSecBugs, dependency/security checks, coverage, source immutability, compatibility,
Temporal, and Python Sidecar performance. No threshold or gate was changed.

## Second cold-start finding: field-specific strategy references

The first cold-start campaign from `28082bca9750e2db359b7fe68436385b331ec493` is preserved at
`benchmark/olympiad-5key-v1/results/real-20260824T001036Z` and was not resumed. P01 completed six
provider calls (49,031 input tokens, 105,065 output tokens, USD 0.112735035) but admitted no route.
All twelve strategy candidates failed deterministic compilation: the first six invented server-owned
blueprint node IDs, and the six gap replacements placed `@all_intermediates` in claim-local
assumption references. A partially started P02 was treated only as non-authoritative stop evidence.

This exposed an ambiguity in the generation prompt, not a reason to relax the compiler. The prompt
listed operation selectors and claim-context selectors together, while the replenishment request did
not return the exact deterministic errors from rejected candidates. The repair therefore:

1. publishes separate typed selector sets for mechanism operations and critical-claim bindings;
2. requires `@claim` for `claim_blueprint_node_id` and allows only `@roots` in
   `local_assumption_node_ids`;
3. prohibits invented server-owned node IDs and duplicated root context;
4. carries ordered `invalid_strategy_contract_errors` into the single production gap request; and
5. leaves every deterministic admission and authority gate unchanged.

Before the production change, the server policy test failed because
`mechanism_operation_selectors` was absent, and the real Coordinator replenishment test failed
because `invalid_strategy_contract_errors` was absent. After the change, the focused server and
Desktop matrices passed six tests with no failure or error. The production-chain diagnostic is:

```text
STRATEGY BINDING CONTRACT RECOVERY DIAGNOSTIC
INVALID_STRATEGY_CONTRACT_ERRORS=4
INVENTED_NODE_REJECTIONS=2
MISSCOPED_SELECTOR_REJECTIONS=2
REPLENISHMENT_PROMPTS=1
VALID_REPLACEMENT_ADMISSIONS=4
INVALID_ROUTE_LEAKS=0
ROOT_HASH_CHANGES=0
RESULT=PASS
```

The stopped campaign was packaged without a provider call as
`MathProofMesh_olympiad-5key-v1_28082bca9750_20260824T004052Z.zip`; its SHA-256 is
`fc7efc6336316dc11ac6bd7a72047da7c91b2664c657edd088d91d2e05543211`.

The first full release retry ran all 370 Desktop tests successfully, then failed SpotBugs because
an inline 25-line feedback projection pushed the already large Coordinator beyond SpotBugs' class
analysis limit and exposed 29 cascading findings. No finding was filtered or suppressed. The pure
projection was moved into `StrategyPortfolioGapFeedback`, leaving only the production call in the
Coordinator. A standalone reactor `verify` then fully analyzed all 165 Desktop classes with zero
SpotBugs findings.

The final `scripts/verify-all.ps1 -Offline` retry passed every release gate. It ran 2,948 tests
(65 Contracts, 1,408 Core, 929 Server unit, 27 Server integration, 370 Desktop, and 149
Compatibility) with zero failures or errors and six intentional skips. PostgreSQL/Testcontainers,
all Flyway migrations, SpotBugs/FindSecBugs, dependency and secret checks, coverage, source
immutability, Temporal, and the unchanged Python Sidecar performance gate all passed.

## Third cold-start finding: server-authorized preflight bindings

The next cold-start campaign from `1d8554cb53335e2164ccf9975185e36f58751939` is preserved at
`benchmark/olympiad-5key-v1/results/real-20260824T011233Z` and was not resumed. P01/T1 made five
physical calls (29,081 input tokens, 54,274 output tokens, 83,355 total tokens,
USD 0.059868615, and 674,947 ms) before stopping `INVALID`. Root Goal hashes were identical and no
strategy, route, Claim, Fact, computation, or proof obligation was admitted.

The generated strategy declared registered request `S1_CHK_1` but did not reference that request
from critical Claim `S1_C1`. The deterministic compiler therefore produced no authorized Claim to
contract binding. A later model response tried to invent `S1_C1 -> S1_CHK_1`; the stable-hash
authority gate correctly rejected the remapping, but its `IllegalArgumentException` escaped the
candidate loop and aborted the campaign. This was a candidate contract failure, not a budget or
mathematical-authority failure.

The repair keeps the exact authority gate and changes only failure containment and contract
guidance:

1. generation must put the exact computation `request_id` in the intended critical Claim's
   `evidence_refs`;
2. the provider preflight stage runs only when the server compiler has already produced at least
   one bound Claim;
3. the provider must echo the complete server binding candidate without adding, deleting, or
   remapping a binding; and
4. a non-identical preflight response rejects only that candidate, records the deterministic error,
   and permits the existing one-shot portfolio replenishment path to continue.

Before the production repair, the two new Desktop cases observed four unnecessary provider
preflight calls for unbound requests and an uncaught remapping exception. The server prompt-policy
tests also failed because neither exact generation-time binding nor exact preflight echoing was
required. After repair, the focused server/Desktop matrix ran 19 tests with zero failures, errors,
or skips. Its production diagnostic was:

```text
PREFLIGHT BINDING AUTHORITY RECOVERY DIAGNOSTIC
UNAUTHORIZED_MODEL_REMAPPINGS=4
CANDIDATE_CONTRACT_REJECTIONS=4
CAMPAIGN_ABORTS=0
REPLENISHMENT_REQUESTS=1
VALID_REPLACEMENT_ADMISSIONS=4
INVALID_ROUTE_LEAKS=0
RESULT=PASS
```

The stopped campaign was packaged without another provider call as
`MathProofMesh_olympiad-5key-v1_1d8554cb5333_20260824T012714Z.zip`; its SHA-256 is
`fa04222c888d6a40d97c67ba69e83695813c2eea6130d60e0578e9f3ee5583f1`.

### Execution identity in exported evidence

Inspection of that stopped bundle found a separate auditability defect: `git-state.txt` used the
frozen Benchmark origin branch and commit as if they were the actual execution branch and HEAD,
and always claimed a clean baseline. The protocol intentionally keeps
`ea94a34041fd32a4f94ecb1a3532ddc314430a47` as the immutable Benchmark origin, but it separately
requires every Run to record its actual branch, HEAD, and dirty status.

A black-box 34-run fake-provider test failed against the old writer because the recorded branch did
not equal `git rev-parse --abbrev-ref HEAD`. The harness now captures one immutable Git execution
snapshot before the campaign begins. Branch and HEAD are read without a shell directly from Git
metadata; the launcher supplies the exact pre-launch dirty boolean from
`git status --porcelain=v1`. Each Run records `execution_branch`, `execution_commit`, and
`execution_dirty` in its manifest, while `git-state.txt` records the actual execution identity and
separately labels the frozen `benchmark_origin_branch` and `benchmark_origin_commit`. The bundle
validator checks both projections. The frozen origin field and schema constant were not changed.

The first complete release retry exposed two new Desktop SpotBugs findings: a nullable parent-path
projection and a command-injection warning for the initial Git subprocess implementation. Neither
finding was filtered or suppressed. The final implementation uses checked parent paths and parses
Git metadata through `java.nio.file`; no production subprocess remains. A no-test reactor `verify`
then analyzed all 168 Desktop classes with zero findings.

The final `scripts/verify-all.ps1 -Offline` run completed with `FULL VERIFICATION: PASS`. It ran
2,951 tests (65 Contracts, 1,408 Core, 929 Server unit, 27 Server PostgreSQL/security integration,
373 Desktop, and 149 Compatibility) with zero failures or errors and six intentional skips.
PostgreSQL 18.4 Testcontainers, all Flyway migrations, SpotBugs/FindSecBugs, dependency and secret
checks, coverage, source immutability, Temporal, and the unchanged Python Sidecar performance gate
all passed.

### Initial route exploration envelope compatibility

The next cold-start campaign, preserved at
`benchmark/olympiad-5key-v1/results/real-20260824T021316Z`, exposed a deterministic tier
compatibility defect. `P01/T1` used 12 calls and admitted two routes; `P02/T1` used only two calls
and admitted three routes. Both then stopped before ready-queue admission with
`INITIAL_ROUTE_EXPLORATION: ACTION_BUDGET_ENVELOPE_EXHAUSTED`, despite retaining respectively 12
and 22 calls. This reproduced on two independent real problems, so the campaign was frozen rather
than resumed. A partially started P03 is non-authoritative stop evidence only.

The failing regression computed the complete bounded frontier before any real call: triage,
initial strategy generation, one replenishment, at most two six-candidate preflight batches, three
initial `DEEPEN` envelopes, and the protected finalization reserve. It found 12 incompatible runs:

```text
OLYMPIAD INITIAL EXPLORATION BUDGET COMPATIBILITY DIAGNOSTIC
RUNS_CHECKED=34
WORST_CASE_PRE_ROUTE_ADMISSION_CALLS=15
INITIAL_ROUTES=3
INCOMPATIBLE_RUNS=12
INCOMPATIBLE_IDENTITIES=[P01/T1, P02/T1, P03/T1, P04/T1, P05/T1, P06/T1, P07/T1, P08/T1, P09/T1, P10/T1, P09/T2, P09/T3]
RESULT=FAIL
```

SMOKE and CORE now each provide 48 calls and 2,304,000 tokens while preserving the 32,000-token
per-call output ceiling. More importantly, `benchmarkConfig` performs the same multidimensional
capacity proof before provider construction and fails with
`BENCHMARK_INITIAL_EXPLORATION_ENVELOPE_EXHAUSTED` if a future tier cannot reach its initial
research queue. The corrected diagnostic reports all 34 runs compatible. The USD 100 global hard
cap, finalization reserve, action envelopes, authority gates, and issue-specific tests remain
unchanged.

The stopped campaign was packaged without another provider call as
`MathProofMesh_olympiad-5key-v1_61e0bdf40c3d_20260824T024519Z.zip`; its SHA-256 is
`251c91a405ddcddd69a2ae0eb26b758f80a7bf06c632c39360361ab6483a98ff`.

### Final blind-review root binding

The cold-start campaign from `2c0329a012b0067e71fbf9313ac3da57d7f7e578`, preserved at
`benchmark/olympiad-5key-v1/results/real-20260824T030907Z`, proved that the initial-route budget
repair reached real research. `P01/T1` admitted four mechanisms, explored isolated routes, passed
independent route review and Claim Court, closed the main goal, and synthesized a complete proof.
Its structural final reviewer passed with intact problem authority.

The run still ended `INCOMPLETE` because both `BLIND_SAME_MODEL` and `ADVERSARIAL_BLIND` were
rejected locally before network transport with `benchmark provider prompt has no exact root
statement`. Their shared `finalBlindReviewContext` contained the sanitized blind packet, including
its problem, but omitted the top-level `immutable_problem` contract required by the Benchmark
transport guard. This was deterministic prompt construction drift, not a mathematical rejection.
The campaign was stopped while P02 was beginning strategy generation and was never resumed.

The production fix projects the already sanitized blind problem view into top-level
`immutable_problem`, binds `problem_hash`, and leaves the independently sanitized blind packet
unchanged. It does not expose the full `ProblemContract`, semantic sidecars, reviewer identity, or
other blind-review forbidden metadata.

Before the production change, the real production-path regression observed two final blind
prompts and two missing root bindings. After repair, the focused production, transport-guard, and
20-round root-goal regression matrix passed five tests:

```text
OLYMPIAD FINAL VALIDATION ROOT PROMPT DIAGNOSTIC
FINAL_BLIND_PROMPTS=2
FINAL_BLIND_ROOT_BINDING_FAILURES=0
RESULT=PASS
```

The subsequent `scripts/verify-all.ps1 -Offline` run completed with
`FULL VERIFICATION: PASS`. It ran 2,953 tests (65 Contracts, 1,408 Core, 956 Server including
PostgreSQL integration, 375 Desktop, and 149 Compatibility) with zero failures or errors and six
intentional skips. SpotBugs analyzed all 168 Desktop classes with zero findings; coverage,
security, source immutability, dependency, Temporal, and unchanged Python Sidecar performance
gates all passed.

The stopped campaign was packaged offline with zero provider calls, zero source-secret leaks, and
zero checksum failures as
`MathProofMesh_olympiad-5key-v1_2c0329a012b0_20260824T034301Z.zip`; its SHA-256 is
`1a55f17060b299a4d57085cc29d7711e09d4c5604dea422f35392407f8ce9baf`.

### Record-only inspiration reservation reconciliation

The next cold-start campaign from `fbefcd211e4807134dcd90de1e0ee5c6568a85b1`, preserved at
`benchmark/olympiad-5key-v1/results/real-20260824T040430Z`, confirmed that the final blind-review
root-binding exception no longer occurred. `P01/T1` passed the five-key preflight, immutable-goal
checks, strategy generation and deterministic preflight, admitted a route, and entered real
isolated exploration. The route's recoverable structured-output failure was contained without
granting mathematical authority.

The run then stopped `INVALID` in the bounded inspiration stage. The Benchmark profile uses
Shadow inspiration: `reserveCycle` returned a record-only reservation that intentionally did not
consume budget, but the engine had not retained that returned reservation in its reconciliation
registry. The coordinator's common `finally` path therefore failed locally with
`IllegalArgumentException: unknown inspiration reservation`. `P01/T1` used 16 physical calls and
USD 0.112558425; no later problem was started, and the campaign was never resumed.

The regression first failed in both Off and Shadow modes at `InspirationEngine#reconcileReservation`.
The engine now retains the record-only reservation as an accounting record, without enabling a
provider call or business mutation. Reconciliation deterministically records zero consumed and
overrun calls, releases the complete planned amount, and remains idempotent. Active inspiration
budget semantics are unchanged.

After the repair, the new regression plus the existing inspiration reservation and policy suites
ran six tests with zero failures, errors, or skips.

The cross-module focused matrix then ran 21 tests with zero failures, errors, or skips, including
the real Coordinator production-path fixture, final blind-review Prompt binding, Inspiration
failure/resume policy, and the 20-round immutable Root Goal propagation test. The subsequent
`scripts/verify-all.ps1 -Offline` run completed with `FULL VERIFICATION: PASS`: 2,954 tests
(65 Contracts, 1,409 Core, 956 Server including PostgreSQL integration, 375 Desktop, and 149
Compatibility) ran with zero failures or errors and six intentional skips. Docker/Testcontainers,
all Flyway migrations, SpotBugs/FindSecBugs, coverage, security, source immutability, dependency,
Temporal, and the unchanged Python Sidecar performance gate all passed.

The stopped campaign was packaged offline with zero provider calls during packaging, zero
source-secret leaks, and zero checksum failures as
`MathProofMesh_olympiad-5key-v1_fbefcd211e48_20260824T042605Z.zip`; its SHA-256 is
`4e75a54783912dc48873c2df47f3d395e8c099697917d255af49abc92899d8c4`.

### Claim Court proof-revision identity under concurrent empty proofs

The next cold-start campaign from `197e8fc`, preserved at
`benchmark/olympiad-5key-v1/results/real-20260824T044504Z`, admitted three P01 routes and reached
concurrent Claim Court processing. Several independent local claims carried empty `proofSteps`.
The previous revision identity used only the proof hash, so every empty proof received the same
`claim-proof-original-4f53cda18c2baa0c0354bb5f` identity. Worker-frontier merge then rejected
the distinct claims as a conflicting revision. P01 stopped `INVALID` after 23 physical calls and
USD 0.155214525; no later problem was started and the campaign was never resumed.

The regression was written against the production freeze and worker-merge paths. Before repair,
distinct empty-proof claims received the same revision ID, concurrent merge raised
`Claim Court worker produced a conflicting revision`, and the failed merge had already changed
the Claim Court hash. The original revision identity now binds the proof hash to the immutable
problem, root-goal, and claim-semantic scope. It deliberately excludes route, attempt, and author
provenance, so an identical mathematical claim and proof remains exactly-once across later
attempts. Legacy proof-only revision IDs remain resolvable only when their proof component matches
the scoped ID.

`conductClaimCourt` now resolves an existing exact proof case before creating an original
revision. Concurrent worker merge validates a combined Claim Court, proof-revision, and stage-
execution frontier before mutating any ledger; on failure all three snapshots are restored. The
frontier validator is a dedicated production class so the coordinator stays within SpotBugs'
analysis limit rather than silently becoming an unanalyzed oversized class.

The focused post-fix matrix ran seven tests with zero failures or errors. It verified three
concurrent empty-proof claims produced three distinct cases and revisions with zero collisions,
legacy lookup remained compatible, repeated attempt provenance created no duplicate case,
revision, provider call, or Fact promotion, and adversarial merge failure changed none of the
three ledger hashes:

```text
CLAIM COURT EMPTY-PROOF CONCURRENCY DIAGNOSTIC
CONCURRENT_EMPTY_PROOF_CLAIMS=3
COURT_CASES=3
PROOF_REVISIONS=3
DISTINCT_REVISION_IDS=3
REVISION_ID_COLLISIONS=0
RESULT=PASS

DUPLICATE_V2_COURT_CASES=0
DUPLICATE_V2_PROOF_REVISIONS=0
DUPLICATE_V2_PROVIDER_CALLS=0
DUPLICATE_V2_FACT_PROMOTIONS=0
RESULT=PASS
```

The first full gate exposed an exactly-once regression because the initial scope also included
ephemeral attempt provenance; that binding was removed and covered by a new regression. The next
full gate passed every test but SpotBugs reported that `DesktopSolveCoordinator` had crossed its
class-analysis limit. Extracting the frontier merger restored complete analysis. The final
`scripts/verify-all.ps1 -Offline` run completed with `FULL VERIFICATION: PASS`: 2,958 tests
(65 Contracts, 1,411 Core, 956 Server including PostgreSQL integration, 377 Desktop, and 149
Compatibility) ran with zero failures or errors and six intentional skips. SpotBugs/FindSecBugs,
Docker/Testcontainers, all Flyway migrations, coverage, security, source immutability, dependency,
Temporal, and the unchanged Python Sidecar performance gate all passed.

The stopped campaign was packaged offline with zero provider calls during packaging, zero source-
secret leaks, and zero checksum failures as
`MathProofMesh_olympiad-5key-v1_197e8fc7d4b1_20260824T050313Z.zip`; its SHA-256 is
`603ba80a9c0bd6ba8dac43f1eb460bbda46278f125ba041e691b8f3fab029cb3`.

### Deterministic quantifier-alias recovery

The cold-start campaign from `815ce93`, preserved at
`benchmark/olympiad-5key-v1/results/real-20260824T060259Z`, passed all five credential preflights,
immutable-goal checks, and triage. P01 strategy generation then reached the 32,000-token output
ceiling and returned a truncated JSON object. Its first bounded repair returned a representation
wrapper; the second returned a complete five-strategy object but used `universal` and `unique` as
quantifier kinds. The strict contract accepts only `forall`, `exists`, and `exists_unique`, and the
normalizer did not yet recognize those exact semantic aliases. P01 therefore stopped `INVALID`
after four billed calls and USD 0.057195975. No later problem was started and the campaign was
never resumed.

The first regression failed before the production change with
`kind has an unsupported literal: universal`. Structured payload normalization now canonicalizes
only the explicit quantifier aliases `universal -> forall`, `existential -> exists`, and
`unique -> exists_unique` inside fields that are structurally named `quantifiers`. Quantifier
order, domain, restrictions, variable identity, and surrounding scope are untouched. Unknown
semantics such as `at_most_one` remain unmodified and still fail strict contract validation.

A production `StructuredAgentRunner` fixture also verifies that a bounded representation wrapper
containing these aliases is repaired and parsed with one physical provider response rather than
dispatching an avoidable second repair call. The focused Contracts and Server matrix ran 27 tests
with zero failures, errors, or skips. The subsequent `scripts/verify-all.ps1 -Offline` run completed
with `FULL VERIFICATION: PASS`: 2,961 tests (67 Contracts, 1,411 Core, 957 Server including
PostgreSQL integration, 377 Desktop, and 149 Compatibility) ran with zero failures or errors and
six intentional skips. Docker/Testcontainers, all Flyway migrations, SpotBugs/FindSecBugs,
coverage, security, source immutability, dependency, Temporal, and the unchanged Python Sidecar
performance gate all passed.

The stopped campaign was packaged offline with zero provider calls during packaging, zero source-
secret leaks, and zero checksum failures as
`MathProofMesh_olympiad-5key-v1_815ce9386c15_20260824T062208Z.zip`; its SHA-256 is
`445d18d8ceb50ca6e90663d6c1952ea724522141648052be26fe109a10653406`.

### Hash-bound stream continuation at the benchmark transport guard

The next cold-start campaign from `d3c2986`, preserved at
`benchmark/olympiad-5key-v1/results/real-20260824T064153Z`, confirmed that deterministic
quantifier aliases no longer blocked initial strategy generation. P01 passed all five credential
preflights, immutable-root validation, triage, a full initial strategy-generation response, and
four strategy preflight plans. It then requested the one-shot structural-gap replenishment.

The replenishment stream disconnected after producing a public output prefix. The production
retry path retained the original canonical user prompt and appended a bounded continuation user
message, but `OlympiadPromptTransportGuard` inspected only the last user message for the stage and
root binding. It therefore rejected the retry locally with
`benchmark provider prompt has no stage binding`. P01 stopped `INVALID` after eight billed calls
and USD 0.057336045; no later problem was started and the campaign was never resumed. This was a
transport validation defect, not a mathematical rejection or a budget failure.

The regression was first run against the old guard and failed at
`OlympiadPromptTransportGuard#stage:208` before any retry network call. Production now uses one
canonical `PublicOutputContinuation` protocol in both `AgentRuntime` and the benchmark guard. Each
continuation carries the exact public prefix plus its lowercase SHA-256; the guard selects the
first nonblank user message as the authoritative canonical prompt, validates its original stage
and immutable root, and accepts later user messages only when they match the exact continuation
grammar and their prefix hash verifies in constant time. A forged hash or arbitrary extra user
message still fails before network. Endpoint, forbidden-metadata, canonical-problem, repair-order,
and root-goal checks were not relaxed.

The focused Server/Desktop matrix ran 22 tests with zero failures, errors, or skips. It covered
the real DeepSeek streaming adapter, the production retry constructor, the transport guard's
canonical-root and forged-continuation boundaries, strategy replenishment exactly once, invalid
strategy replacement, and the 20-round production root-goal propagation chain. The subsequent
`scripts/verify-all.ps1 -Offline` run completed with `FULL VERIFICATION: PASS`: 2,962 tests
(67 Contracts, 1,411 Core, 957 Server including PostgreSQL integration, 378 Desktop, and 149
Compatibility) ran with zero failures or errors and six intentional skips. Docker/Testcontainers,
all Flyway migrations, SpotBugs/FindSecBugs, coverage, security, source immutability, dependency,
Temporal, and the unchanged Python Sidecar performance gate all passed.

The stopped campaign was packaged offline with zero provider calls during packaging, zero source-
secret leaks, and zero checksum failures as
`MathProofMesh_olympiad-5key-v1_d3c298629f80_20260824T071357Z.zip`; its SHA-256 is
`6ac16fe074d1a8652c2129fe0306e39e6c1d318d649ee8186141b7ab66ec73db`.

## Protected behavior

No API key, raw provider response, authorization header, target output, database file, or checkpoint
is part of this change. Issues 001-013 remain authoritative and their tests were not removed,
skipped, relaxed, or rewritten to accept weaker behavior. In particular, this repair does not turn
a billing record into mathematical progress and does not let terminal accounting replace the
semantic checkpoint.
