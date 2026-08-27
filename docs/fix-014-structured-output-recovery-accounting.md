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

### Campaign finding ownership and conservative input reservation

The next cold-start campaign from `c8e6a84`, preserved at
`benchmark/olympiad-5key-v1/results/real-20260824T074110Z`, exercised the stream-continuation
repair successfully. P01 passed the five credential preflights, immutable-root validation,
triage, initial strategy generation, the formerly failing structural-gap replenishment, strategy
preflight, and three-route admission. Its immutable root hash remained
`422cfd9130270941afe5978658df9217534cc98b4569939ada880d97818a1a7a`.

All three concurrent exploration requests then returned complete public responses, but each
worker failed while committing its research checkpoint. The responses included `KEEP_ACTIVE`
dispositions for campaign-level findings. Those findings are owned by the synthetic
`campaign-research` route, while the strict ledger correctly rejects ordinary cross-route
mutation. The Coordinator had passed the unpartitioned batch directly into that ledger, causing
an `IllegalArgumentException` before any ProofAttempt could be committed.

The meta-review request exposed a separate physical-budget reservation boundary. The estimated
input was 24,273 tokens and the provider reported 24,348, a 75-token transport/framing delta. The
strict physical envelope correctly entered `ACTUAL_USAGE_OVERRUN`; because overrun is a global
hard stop, subsequent proof work was rejected despite remaining campaign capacity. P01 therefore
stopped `INCOMPLETE` after 14 physical calls, 125,856 input tokens, 119,003 output tokens, and USD
0.158279970. The campaign was frozen and never resumed.

Production now filters a route's finding-update batch before applying it. A route-local update is
unchanged. A `KEEP_ACTIVE` observation of a campaign-owned finding is an explicit no-op because
omission already means active. Every state-changing cross-route disposition still fails closed
with `CROSS_ROUTE_FINDING_MUTATION_FORBIDDEN`; no Claim, Fact, Negative Knowledge, route attempt,
or root-goal authority is created. Prompt policy now also tells agents not to place campaign IDs
in route-local `finding_updates` and to use the existing explicit adoption plus Issue-003 review
path instead.

The real responses also revealed a representation-only mismatch: two attempts supplied semantic
context bindings for pre-existing Strategy claims while declaring no attempt-local
`proposed_lemmas`. Structured normalization now removes only bindings whose IDs are absent from
the same attempt's proposed-lemma set. It does not invent a Claim, add a binding, verify a lemma,
or relax strict validation for bindings that remain.

Finally, the deterministic provider-input estimator retains its UTF-8 and 25-percent content
headroom but increases fixed message/transport framing reservation from 128 to 512 tokens. The
physical hard gate and global overrun behavior are unchanged. A production runner regression
replays the observed 24,348-token boundary and proves that 24,657 tokens are reserved before
dispatch, the envelope settles normally, and no global overrun is recorded.

The pre-fix matrix produced four deterministic failures: unknown attempt-local binding rejection,
zero submitted attempts after the campaign `KEEP_ACTIVE` response, an estimator result of 24,273
below the observed 24,348 input, and an overrun physical envelope at the same boundary. After the
repair, the focused Contracts, Server, and Desktop matrix ran 36 tests with zero failures or
errors. The production-path diagnostics were:

```text
CAMPAIGN FINDING DISPOSITION ISOLATION DIAGNOSTIC
CAMPAIGN_KEEP_ACTIVE_OBSERVATIONS=1
SUBMITTED_ATTEMPTS=1
ROUTE_WORKER_FAILURES=0
CAMPAIGN_FINDING_MUTATIONS=0
DIRECT_FACT_PROMOTIONS=0
DIRECT_CLAIM_VERIFICATIONS=0
DIRECT_NEGATIVE_REGISTRATIONS=0
ROOT_HASH_CHANGES=0
NEGATIVE_REGISTRY_HASH_CHANGES=0
RESULT=PASS

CROSS_ROUTE_CAMPAIGN_MUTATION_DIAGNOSTIC
CROSS_ROUTE_MUTATION_BLOCKS=1
CAMPAIGN_FINDING_MUTATIONS=0
SUBMITTED_ATTEMPT_LEAKS=0
AUTHORITY_LEAKS=0
RESULT=PASS
```

The subsequent `scripts/verify-all.ps1 -Offline` run completed with
`FULL VERIFICATION: PASS`: 2,967 tests (68 Contracts, 1,411 Core, 959 Server including PostgreSQL
integration, 380 Desktop, and 149 Compatibility) ran with zero failures or errors and six
intentional skips. Docker/Testcontainers, all Flyway migrations, SpotBugs/FindSecBugs, coverage,
security, source immutability, dependency, Temporal, and the unchanged Python Sidecar performance
gate all passed.

The stopped campaign was packaged offline with zero provider calls during packaging, zero source-
secret leaks, and zero checksum failures as
`MathProofMesh_olympiad-5key-v1_c8e6a84352cd_20260824T081856Z.zip`; its SHA-256 is
`36649142e0aedc3195f2d7ec40a4e04e8beba4a66eef50d830369712e472a10b`.

### Claim identity mismatch isolation before Claim Court

The next cold-start campaign from `d4829d5`, preserved at
`benchmark/olympiad-5key-v1/results/real-20260824T085619Z`, confirmed that campaign-finding
ownership, attempt-binding normalization, and the enlarged provider-input reservation all passed
their former boundaries. P01 completed five-key preflight, root freezing, triage, strategy
generation, route admission, three concurrent explorations, independent review, and committed
checkpoint processing. Its immutable root hash remained
`422cfd9130270941afe5978658df9217534cc98b4569939ada880d97818a1a7a`.

At `claim_memory_graph`, several attempt-local proposed lemmas reused pre-existing Strategy
critical Claim IDs while carrying different statements. The existing Claim Court binding guard
correctly refused to attach an altered statement to an authoritative Claim identity, but planning
propagated `CLAIM_COURT_CONTEXT_STATEMENT_MISMATCH` as a campaign-wide exception. P01 therefore
stopped `INVALID` after 14 calls, 96,533 input tokens, 96,907 output tokens, 193,440 total tokens,
and USD 0.126300945. No altered Claim received Court, Fact, or permanent Negative Knowledge
authority, and the campaign was frozen rather than resumed.

Production now performs the same normalized statement-identity comparison before Claim Court work
is scheduled. A mismatched artifact remains durable in the Attempt Artifact Ledger as
`UNCERTAIN`, is excluded from Claim Court dispatch, and is not inserted into Lemma Memory. Its
sibling claims and routes may continue. An exact normalized statement match still follows the
ordinary Claim Court path. The original hard guard in `claimCourtSemanticContext` remains in place,
so this is isolation of an invalid local artifact rather than a relaxation or statement rewrite.

The new production-path regression failed before the repair with the same
`CLAIM_COURT_CONTEXT_STATEMENT_MISMATCH:critical-claim` stack through
`freezeClaimForCourt`. After the repair, the new test and its adjacent Claim Court, binding,
batch-concurrency, and campaign-finding matrix ran eight tests with zero failures or errors:

```text
CLAIM COURT STATEMENT IDENTITY ISOLATION DIAGNOSTIC
MISMATCH_QUARANTINES=1
MISMATCH_COURT_CALLS=0
MISMATCH_FACT_LEAKS=0
ROOT_HASH_CHANGES=0
PERMANENT_NEGATIVE_HASH_CHANGES=0
RESULT=PASS
```

The subsequent `scripts/verify-all.ps1 -Offline` run completed with
`FULL VERIFICATION: PASS`: 2,969 tests (68 Contracts, 1,411 Core, 959 Server including PostgreSQL
integration, 382 Desktop, and 149 Compatibility) ran with zero failures or errors and six
intentional skips. Docker/Testcontainers, all Flyway migrations, SpotBugs/FindSecBugs, coverage,
security, source immutability, dependency, Temporal, and the unchanged Python Sidecar performance
gate all passed.

The stopped campaign was packaged offline with zero provider calls during packaging, zero source-
secret leaks, and zero checksum failures as
`MathProofMesh_olympiad-5key-v1_d4829d53ae2c_20260824T091823Z.zip`; its SHA-256 is
`30cf19ef6001eeb27778d146295a396e2e85de3c0057648bbb625638e0aded5a`.

### Complete blind-proof transport and invalid Claim-context isolation

The next cold-start campaign from `f617b96`, preserved at
`benchmark/olympiad-5key-v1/results/real-20260824T100153Z`, completed two runs before the hard
stop. P01 reached synthesis and constructed a `FinalProof` containing 17 auditable proof steps,
but final validation projected only `FinalProof.answer()` into `final_proof_text`. The independent
blind reviewer therefore correctly rejected a short restatement of the theorem as containing no
proof. P01 stopped `INCOMPLETE` after 35 calls and USD 0.273077340; its immutable root hash stayed
`422cfd9130270941afe5978658df9217534cc98b4569939ada880d97818a1a7a`.

P02 then exposed a separate local-artifact boundary. An attempt-local Claim declared four
quantifiers but no corresponding variable bindings. The hard Claim Court compiler correctly
raised `UNBOUND_CLAIM_COURT_QUANTIFIER`, but the exception stopped the whole run instead of
quarantining only that invalid artifact. P02 stopped `INVALID` after 12 calls and USD 0.105640620;
its immutable root hash stayed
`6abfc35bd7e9e1ea68146ce4de07b32dd5a477379d632f8385557ca03d78abc4`.
The frozen campaign recorded 47 calls and USD 0.378717960 in total and was never resumed.

The two production-path regressions were run before the implementation change. The blind-review
test failed because the captured `BlindVerificationReport` request contained only the final
answer and omitted both the proof-step statement and justification. The Claim Court test failed
with the real `UNBOUND_CLAIM_COURT_QUANTIFIER:tournament-T` stack through
`freezeClaimForCourt`. Neither test uses a live provider.

`BlindReviewPacketFactory` now accepts the complete typed `FinalProof` and deterministically
renders an identity-free review body containing the answer, ordered proof steps, justifications,
proof-step dependencies, calculations, declared dependencies, and caveats. It deliberately omits
confidence, problem hash, source attempt IDs, route IDs, agents, and raw artifact references. The
blind-review policy, independent reviewer, and pass criteria were not weakened.

Before Claim Court dispatch, the Coordinator now applies the existing authoritative semantic-
context compiler as a deterministic precheck. Missing modern local bindings, unbound quantified
variables, and duplicate variable identities leave a durable `UNCERTAIN` Attempt Artifact but do
not enter Lemma Memory, Claim Court, Fact Memory, or permanent Negative Knowledge. Unknown
compiler failures still propagate fail closed. The existing strict compiler remains unchanged;
the repair isolates invalid local input rather than inventing bindings or rewriting quantifiers.

The expanded adjacent matrix ran 24 tests with zero failures, errors, or skips. During the first
full release-gate run, the existing `DesktopUnboundLocalClaimFailsClosedTest` exposed the related
missing-binding code path before the new Lemma Memory filter; that path was added to the same
local quarantine boundary and the targeted five-test rerun passed:

```text
CLAIM COURT VARIABLE IDENTITY ISOLATION DIAGNOSTIC
UNBOUND_QUANTIFIER_QUARANTINES=1
UNBOUND_QUANTIFIER_COURT_CALLS=0
UNBOUND_QUANTIFIER_FACT_LEAKS=0
ROOT_HASH_CHANGES=0
PERMANENT_NEGATIVE_HASH_CHANGES=0
RESULT=PASS

UNBOUND_MODERN_LOCAL_CLAIM_ADMISSIONS=0
UNBOUND_MODERN_LOCAL_CLAIM_ROOT_FALLBACKS=0
```

The subsequent `scripts/verify-all.ps1 -Offline` rerun completed with
`FULL VERIFICATION: PASS`: 2,971 tests ran with zero failures or errors and six intentional
skips. Docker/Testcontainers and PostgreSQL integration, all Flyway migrations,
SpotBugs/FindSecBugs, coverage, security, source immutability, dependency, Temporal, and the
unchanged Python Sidecar performance gate all passed.

The stopped campaign was packaged offline with zero provider calls during packaging, zero source-
secret leaks, and zero checksum failures as
`MathProofMesh_olympiad-5key-v1_f617b962be31_20260824T105815Z.zip`; its SHA-256 is
`578aef0dcff38ae83734c7bd6bf881c0adbd3c7fe128d89985ffb23e86f163a7`.

### Redundant local declaration normalization in strategy recovery

The next cold-start campaign from `5636639`, preserved at
`benchmark/olympiad-5key-v1/results/real-20260824T115003Z`, stopped on P01 run
`p01-t1-20260824T115011Z-9cec617d`. The initial strategy response reached the 32,000-token output
limit and ended with unterminated JSON. Bounded JSON repair recovered a syntactically valid
payload, but its critical-Claim contexts encoded local definitions as quantifiers with the
unsupported literal `kind=let`. Nested result repair repeated the same representation, and strict
contract validation correctly ended with `kind has an unsupported literal: let`. The run stopped
`INVALID` after four billed calls, 28,238 input tokens, 51,755 output tokens, and USD 0.057310380.
Its immutable root hash remained
`422cfd9130270941afe5978658df9217534cc98b4569939ada880d97818a1a7a`.

`StructuredPayloadNormalizer` now removes a `let` pseudo-quantifier only when it is a redundant,
fully bound local declaration: the containing object is an identified Claim context, the matching
variable binding is unique, identity fields agree after whitespace normalization, the owner scope
is exactly `claim_local`, and every restriction is nonblank text. Every restriction is preserved
as a local assumption before removal. The normalizer never maps `let` to `forall`, `exists`, or
`exists_unique`; ambiguous, missing, duplicated, mismatched, nonlocal, empty, blank, or nontext
forms remain untouched and therefore fail strict validation. The downstream semantic-context
compiler and Claim authority gates are unchanged.

The runner regression failed before the production change with `StructuredOutputError`, caused by
the same unsupported `let` literal. After the repair, the adjacent cross-module matrix ran 47
tests with zero failures, errors, or skips: 17 Contracts normalization tests, 19 Server structured-
runner tests, and 11 Desktop recovery, root-prompt, and Claim-isolation tests. The first full gate
then correctly detected insufficient Contracts branch coverage. An additional ambiguity matrix
exercised every fail-closed boundary without weakening the threshold; adjusted Contracts branch
coverage finished at 85.585260 percent.

The final `scripts/verify-all.ps1 -Offline` run completed with `FULL VERIFICATION: PASS`: 2,976
tests (71 Contracts, 1,411 Core, 961 Server including PostgreSQL integration, 384 Desktop, and 149
Compatibility) ran with zero failures or errors and six intentional skips. Docker/Testcontainers,
all Flyway migrations, SpotBugs/FindSecBugs, coverage, security, source immutability, dependency,
Temporal, and the unchanged Python Sidecar performance gate all passed.

The stopped campaign was packaged offline with zero provider calls during packaging, zero source-
secret leaks, and zero checksum failures as
`MathProofMesh_olympiad-5key-v1_563663959e79_20260824T120823Z.zip`; its SHA-256 is
`8eb2d196ee77bfe00fbf3dec1fe52da27ded1b781e9b3487fbde2df965bc8055`.

### Closed affirmative polarity normalization in strategy recovery

The next cold-start campaign from `49a5061`, preserved at
`benchmark/olympiad-5key-v1/results/real-20260824T134231Z`, completed P01 and P02 as
`INCOMPLETE` before P03 stopped the campaign. P03's initial strategy response reached the
32,000-token output limit with unterminated JSON. General repair produced a complete payload but
placed relation categories such as `identity`, `equivalence`, `derived_inequality`,
`nonnegative_inequality`, `global_maximum`, and `unique_solution` in the binary
`critical_claim_context_bindings[].polarity` field. Nested result repair translated some labels
but kept the same invalid representation. Strict validation correctly rejected the payload with
an unsupported polarity literal. P03 stopped `INVALID` after four calls and USD 0.058068150; its
immutable root hash remained
`edfebe22a6b9c907c98998c9e37988da3e1d1664fb518c7541fabfdf3707d5f8`.
The frozen campaign recorded 74 calls and USD 0.812405565 in total and was never resumed.

`StructuredPayloadNormalizer` now canonicalizes only a closed, field-specific set of clearly
affirmative relation-category aliases to `positive`, and only inside Strategy critical-Claim
context bindings. Exact `positive` and `negative` values are preserved. Unknown or narrative
values remain untouched and therefore still fail strict contract validation. The implementation
does not infer polarity from arbitrary prose, rewrite statements, change assumptions, or grant
Claim or Fact authority. The repair prompt also states the existing binary polarity contract so a
bounded provider repair is less likely to repeat the representation error.

The new Contracts regression failed before the production change with
`ContractValidationException: polarity has an unsupported literal: identity`. After the repair,
the focused Contracts and Server matrix ran 38 tests, and the adjacent Contracts, Server, and
Desktop recovery/authority matrix ran 46 tests, all with zero failures or errors. A first static-
analysis pass rejected a broad Unicode-normalization implementation. That implementation was
removed rather than suppressed; the final code uses no SpotBugs exemption and keeps the alias set
exact and closed.

The final `scripts/verify-all.ps1 -Offline` run completed with `FULL VERIFICATION: PASS`: 2,980
tests (73 Contracts, 1,411 Core, 963 Server including PostgreSQL integration, 384 Desktop, and 149
Compatibility) ran with zero failures or errors and six intentional skips. Adjusted Contracts
branch coverage was 85.545194 percent. Docker/Testcontainers, all Flyway migrations,
SpotBugs/FindSecBugs, coverage, security, source immutability, dependency, Temporal, and the
unchanged Python Sidecar performance gate all passed.

The stopped campaign was packaged offline with zero provider calls during packaging, zero source-
secret leaks, and zero checksum failures as
`MathProofMesh_olympiad-5key-v1_49a50614f263_20260824T153811Z.zip`; its SHA-256 is
`f6d224a8bdf4e11b4556927f3cc230ca7e578176427a0dc3488dbc2ae06be850`.

### Invalid optional portfolio-replenishment isolation

The next cold-start campaign from `9ddb3a2`, preserved at
`benchmark/olympiad-5key-v1/results/real-20260824T161024Z`, stopped on P01 run
`p01-t1-20260824T161033Z-418a52df`. Its initial `StrategySet` passed strict parsing and reached
deterministic portfolio preparation. The optional structural-gap replenishment response and its
single bounded repair then supplied an invalid Claim context: `polarity=conclusion` and an
ambiguous quantifier `kind=bound`. Strict validation correctly rejected both responses, but the
resulting `StructuredOutputError` escaped the optional replenishment boundary and invalidated the
whole run. The run stopped after seven physical calls and USD 0.058268685. Its immutable root hash
remained `422cfd9130270941afe5978658df9217534cc98b4569939ada880d97818a1a7a`, and no invalid
candidate, route, Claim, Fact, obligation, or other authority was admitted.

The new production regression was written and run before the implementation change. It reproduced
the campaign failure through the real `StructuredAgentRunner` and Coordinator path, ending in
`StructuredOutputError` caused by `ContractValidationException: polarity has an unsupported
literal: conclusion`. It then passed only after the optional batch received a local failure
boundary.

`replenishStrategyPortfolioOnce` now catches only a strict structured-output failure from this
optional batch. It closes the stable replenishment episode with an empty candidate list, emits a
rejection audit event, persists that completed frontier, and retains the already prepared source
portfolio. It does not normalize `conclusion`, guess what `bound` means, infer mathematical
semantics, admit any part of the invalid supplement, or catch unrelated provider and runtime
failures. The completed empty episode also prevents a post-restore provider replay.

The focused adjacent matrix ran nine tests with zero failures, errors, or skips. It covered invalid
optional output, bounded repair, gap replenishment exactly once, invalid binding recovery,
low-quality replenishment, unknown-mechanism isolation, hard-crash recovery, structured-output
usage accounting, and the 20-round root-goal production chain. The production diagnostic was:

```text
INVALID OPTIONAL PORTFOLIO REPLENISHMENT DIAGNOSTIC
REPLENISHMENT_PROVIDER_CALLS=1
INVALID_REPLENISHMENT_CANDIDATE_LEAKS=0
SOURCE_PORTFOLIO_ADMISSIONS=1
SOURCE_ROUTE_ADMISSIONS=1
ROOT_HASH_CHANGES=0
NEGATIVE_REGISTRY_HASH_CHANGES=0
POST_RESTORE_REPLENISHMENT_CALLS=0
POST_RESTORE_STATE_DRIFTS=0
RESULT=PASS
```

The subsequent `scripts/verify-all.ps1 -Offline` run completed with
`FULL VERIFICATION: PASS`: 2,981 tests (73 Contracts, 1,411 Core, 963 Server including PostgreSQL
integration, 385 Desktop, and 149 Compatibility) ran with zero failures or errors and six
intentional skips. Docker/Testcontainers, PostgreSQL 18.4, all seven Flyway migrations,
SpotBugs/FindSecBugs, coverage, security, source immutability, dependency, Temporal, and the
unchanged Python Sidecar performance gate all passed. Adjusted Contracts branch coverage remained
85.545194 percent.

The frozen campaign was packaged offline with zero provider calls during packaging, zero source-
secret leaks, and zero checksum failures as
`MathProofMesh_olympiad-5key-v1_9ddb3a265793_20260824T162744Z.zip`; its SHA-256 is
`96ae90926eb00ddad95219aee22724a4df4f8ba5d8a06aa19668242e1b5d6ed4`.

### Mechanism-selector set semantics and topology-safe replenishment

The next cold-start campaign from `1bc5d0b`, preserved at
`benchmark/olympiad-5key-v1/results/real-20260824T165456Z`, completed P01 through P04 before the
hard stop. P01, P02, and P03 completed as `INCOMPLETE` without an Issue 001-013 violation. P04
completed after four calls and USD 0.068162325, but all six initial strategies and all six one-shot
replacement strategies were deterministically rejected with `mechanism operation output is not
reachable from an input`. Across the four complete Runs, the campaign used 98 calls and
USD 0.861792855. P05 had only begun triage/strategy work when the campaign was deliberately
stopped; its partial output is non-authoritative stop evidence and was not resumed.

Every rejected strategy treated the selector chain
`@roots -> @direct_targets -> @all_intermediates -> @main_goal` as ordered layers. Server semantics
are intentionally stricter: each selector expands to a node set, `@direct_targets` identifies the
final expected-lemma target set, and `@all_intermediates` contains all lemma nodes, including nodes
that precede direct targets. A direct target therefore need not reach every member of the latter
set. The strict reachability compiler behaved correctly and was not relaxed.

The first test-first reactor stopped in `GenericStrategyGenerationPolicyTest` because the generated
Prompt lacked `mechanism_operation_topology_contract`, `selectors_expand_to_sets`, safe templates,
and the stable `DIRECT_TARGETS_TO_ALL_INTERMEDIATES_NOT_A_LAYER` warning. Because Maven stopped at
that Server failure, the new Desktop production-chain regression was not executed in the same
pre-fix reactor. After the production change, both tests and the adjacent nine-test matrix passed.

The repair publishes one immutable, machine-readable topology contract in ordinary strategy
guidance and reuses the same contract only when a replenishment batch contains a deterministic
reachability failure. Such failures receive the stable code
`MECHANISM_OPERATION_REACHABILITY_MISMATCH`, the exact deterministic error, and topology-safe
generation templates. The stage Prompt also states explicitly that selectors are sets, prohibits
the unsafe direct-target-to-all-intermediates edge, and recommends safe root/direct-target/main-goal
paths. It does not rewrite a candidate, infer a graph, weaken reachability, admit an invalid
strategy, or change Claim, Fact, Root Goal, Negative Knowledge, budget, or recovery authority.

```text
MECHANISM OPERATION TOPOLOGY RECOVERY DIAGNOSTIC
REACHABILITY_REJECTIONS=4
TOPOLOGY_FEEDBACK_PROMPTS=1
VALID_REPLACEMENT_ADMISSIONS=4
INVALID_ROUTE_LEAKS=0
ROOT_HASH_CHANGES=0
RESULT=PASS
```

The subsequent `scripts/verify-all.ps1 -Offline` run completed with
`FULL VERIFICATION: PASS`: 2,982 tests (73 Contracts, 1,411 Core, 963 Server including PostgreSQL
integration, 386 Desktop, and 149 Compatibility) ran with zero failures or errors and six
intentional skips. Docker/Testcontainers, PostgreSQL 18.4, all seven Flyway migrations,
SpotBugs/FindSecBugs, coverage, security, source immutability, dependency, Temporal, and the
unchanged Python Sidecar performance gate all passed. Adjusted Contracts branch coverage remained
85.545194 percent.

The stopped campaign was packaged offline with zero provider calls during packaging, zero source-
secret leaks, and zero checksum failures as
`MathProofMesh_olympiad-5key-v1_1bc5d0b313ad_20260824T190201Z.zip`; its SHA-256 is
`2ba5370e7d8cd354b949c928e77fb9e31e5ba3ee6a2de49d250084cdda1d3fff`.

### Unknown research-finding update isolation

The next cold-start campaign from `03481b1`, preserved at
`benchmark/olympiad-5key-v1/results/real-20260824T193101Z`, stopped on P01 run
`p01-t1-20260824T193110Z-a3db0fa0`. Its seventh physical call was the optional strategy-portfolio
replenishment. The nested `StrategySet` was otherwise usable, but the response also placed four
model-local labels in `finding_updates.dispositions`: `candidate_lemma_identity`,
`candidate_lemma_prime_valuation`, `exact_example_1`, and `next_micro_obligation_1`. These were
not server-issued `research_finding_*` IDs. The strict Research Checkpoint Ledger correctly threw
`IllegalArgumentException` rather than mutating unknown authority, but the Coordinator allowed
that optional sidecar error to discard the valid structured result and stop the campaign.

P01 stopped `INVALID` after seven calls, 26,555 input tokens, 53,703 output tokens, 80,258 total
tokens, and USD 0.058273035. Its immutable Root Goal hash did not change, and no unknown finding,
Claim, Fact, Negative Knowledge, route, or proof obligation received authority. The campaign was
frozen and was never resumed.

The new production-chain regression was run before the implementation change. It expected four
stable rejection-audit events and observed zero because the first unknown ID escaped through
`ResearchCheckpointLedger#requireFinding`. The repair keeps that protected Ledger unchanged.
`ResearchFindingUpdateBoundary` now partitions optional model dispositions before the strict
ledger transition: exact route-owned IDs retain their existing behavior; a campaign-owned
`KEEP_ACTIVE` remains the existing no-op; a known cross-route mutation still fails closed; and an
unknown ID is excluded and recorded as `reject_unknown_finding_update` with stable code
`UNKNOWN_RESEARCH_FINDING_UPDATE`. The audit is idempotent across restore and grants no finding or
mathematical authority. Worker-frontier merge carries the rejection audit while still applying
the valid nested result.

The structured-stage Prompt now requires exact, already supplied `research_finding_*` IDs and
forbids local labels or same-response finding drafts in dispositions. This is guidance only; the
deterministic production boundary remains authoritative.

```text
UNKNOWN RESEARCH FINDING UPDATE RECOVERY DIAGNOSTIC
UNKNOWN_FINDING_UPDATES=4
UNKNOWN_FINDING_UPDATE_REJECTIONS=4
VALID_RESULT_APPLICATIONS=1
PUBLIC_FINDINGS_PERSISTED=1
UNKNOWN_FINDING_AUTHORITY_MUTATIONS=0
ROOT_HASH_CHANGES=0
NEGATIVE_REGISTRY_HASH_CHANGES=0
RESULT=PASS
```

The adjacent Core, Server, and Desktop matrix ran 18 tests with zero failures or errors, including
research-ledger boundary validation, checkpointed Prompt policy, worker atomicity, campaign
finding propagation, 20-round restore, and v7 migration. The protected-authority test also proves
that the Issue-007-frozen `ResearchCheckpointLedger` remains byte-for-byte unchanged from its
authority baseline.

The first complete release retry detected that protected-file violation from the initial design;
the Ledger extension was removed rather than weakening the protection test. The second retry ran
all tests successfully but SpotBugs reported `SKIPPED_CLASS_TOO_BIG` after inline filtering pushed
the Coordinator over its analysis limit, followed by cascading false findings. The boundary was
extracted into its own production class; no SpotBugs rule was filtered or suppressed. A no-test
reactor verify then reported zero findings in every module.

The final `scripts/verify-all.ps1 -Offline` run completed with `FULL VERIFICATION: PASS`: 2,984
tests (73 Contracts, 1,411 Core, 964 Server including PostgreSQL integration, 387 Desktop, and 149
Compatibility) ran with zero failures or errors and six intentional skips. Docker/Testcontainers,
PostgreSQL 18.4, all seven Flyway migrations, SpotBugs/FindSecBugs, coverage, security, source
immutability, dependency, Temporal, and the unchanged Python Sidecar performance gate all passed.

### Closure-critical Claim Court budget isolation

The next cold-start campaign from `a07afdc9d213c0ef070bdc71b0ae98f51f0d9ef3` is preserved at
`benchmark/olympiad-5key-v1/results/real-20260825T004540Z`. P01/T1 run
`p01-t1-20260825T004549Z-a1765948` returned `INCOMPLETE`, but the mathematical route itself had
not failed: `route-1` was verified, `attempt-route-1-r0` was complete, the synthesized proof was
correct, and all structural and final model reviews passed with confidence from 0.98 to 1.0. The
immutable Root Goal hash did not change.

The deterministic final gate failed because `main-goal` and all 13 Proof Graph obligations were
still open. The internally created Route Theorem Artifact
`attempt-artifact-3c3c4cb0d9e8390678531311` remained `REVIEW_PENDING`, had no Claim Court review
IDs, and therefore could not become a Fact or call `closeRouteTheoremObligations`. This was an
orchestration false negative, not evidence that the elementary gcd proof was incomplete.

The exact starvation boundary was the initial route-exploration action envelope. It reserved and
consumed exactly 21 calls. Claim Court shared that fixed envelope even though its real fan-out is
determined only after harvesting Attempt Artifacts. Optional local Claims consumed the remaining
physical-call capacity, and the Route Theorem statement-falsification stage then failed with
`BudgetExhaustedError`. The run as a whole had used only 33 of 48 calls, 408,361 tokens, and
USD 0.26352126, so 15 run calls remained available outside the exhausted action envelope.

The benchmark launcher and both Java processes were deliberately stopped before source changes.
The campaign is retained only as pre-fix evidence and will not be resumed; the next benchmark must
be a cold start.

The repair makes the following production guarantees:

1. Route exploration is settled before Claim Court planning, so a fixed pre-harvest estimate no
   longer governs dynamic Claim Court fan-out.
2. Claim Court receives a durable action envelope from the remaining exploration capacity while
   the immutable synthesis/final-verification reserve remains protected in every resource
   dimension.
3. A verified Route Theorem is sorted ahead of local Claims before the 64-item review limit, so a
   full optional batch cannot crowd it out.
4. Closure-critical Route Theorems execute in deterministic one-item authoritative epochs before
   the concurrent supporting-Claim epoch. Optional work can no longer preempt main-goal closure.
5. A restored active Claim Court envelope is reused only when its epoch and work-item identity
   match the same sorted Claim set and restore-stable authority hash. A different active envelope
   is settled before a new decision is bound to the current budget-envelope frontier, preventing
   stale or unrelated capacity from being reactivated after rollback.
6. Mathematical authority is unchanged: only a Claim Court `VERIFIED` Route Theorem may become a
   Fact and close the main goal. A failed, uncertain, unreviewed, or unverified theorem still fails
   closed.

The new production regression was run before the implementation change. Its first Claim Court
request was `optional-budget-claim-0` instead of `claim-route-1-theorem-r1`, so the required
closure-first assertion failed. After the repair, both the bounded-budget path and the full-batch
crowding boundary pass:

```text
ROUTE THEOREM BUDGET STARVATION DIAGNOSTIC
INITIAL_FIXED_ENVELOPE_RESERVED=1
OPTIONAL_LOCAL_CLAIMS=4
ROUTE_THEOREM_COURT_FIRST=1
OPTIONAL_CLAIM_PREEMPTIONS=0
ROUTE_THEOREM_PROMOTIONS=1
MAIN_GOAL_CLOSURES=1
RESULT=PASS

ROUTE THEOREM BATCH CROWDING DIAGNOSTIC
OPTIONAL_LOCAL_CLAIMS=64
CLAIM_REVIEW_BATCH_LIMIT=64
ROUTE_THEOREMS_RETAINED=1
ROUTE_THEOREMS_CROWDED_OUT=0
RESULT=PASS
```

The adjacent Claim Court atomicity retry also passed all ten injected failure points with zero
partial Court records, proof revisions, Claim status writes, Fact writes, Proof Graph writes, task
lease leaks, or pending-task leaks. Claim Court hard-crash recovery, concurrent completion-order
determinism, 20-round Claim Court restore, 20-round Claim salvage restore, evidence-aware budget,
initial Olympiad envelope compatibility, and no-budget-bypass checks remained green.

The first full-module retry exposed one restore-specific regression: unconditional settlement of
an active Claim Court envelope caused the v20 prepared-epoch replay test to quarantine a durable
prepared result. The final implementation derives Claim Court batch identity from the sorted Claim
IDs and the restore-stable mathematical authority hash, so the exact prepared batch reuses its
envelope without making a second provider call. A different batch cannot reuse it. The focused
matrix then ran five tests with zero failures, errors, or skips, including all ten Claim Court
atomicity points and all five hard-crash points. The prepared-epoch diagnostic reported one modern
replayed commit, one mutation receipt, one merge receipt, zero provider-call replays, and no legacy
fail-open acceptance.

The first complete release retry after this repair also found that the additional inline Claim
Court orchestration pushed `DesktopSolveCoordinator` past SpotBugs' analyzable class-size limit.
The ordering and partition logic was moved into `DesktopClaimCourtBatchExecutor`, and callback
implementations were isolated in generated nested classes. No SpotBugs rule, suppression, or
performance threshold was weakened. A no-test reactor verify then reported zero SpotBugs findings
in every module.

The final module regression completed successfully: 73 Contracts tests, 1,411 Core tests, 964
Server tests, and 389 Desktop tests ran with zero failures or errors; the Server and Desktop totals
contained six intentional skips. The final `scripts/verify-all.ps1 -Offline` run completed with
`FULL VERIFICATION: PASS`: 2,986 tests (73 Contracts, 1,411 Core, 964 Server, 389 Desktop, and 149
Compatibility) ran with zero failures or errors and six intentional skips. Docker/Testcontainers,
PostgreSQL 18.4, all seven Flyway migrations, SpotBugs/FindSecBugs, coverage, security, source
immutability, dependency checks, Temporal checks, and the unchanged Python Sidecar performance
gate all passed.

### Optional computation hints and shared-reviewer lease contention

The next cold-start campaign from `131e2ccdc81963df244a8a7efb9f9a0c72268543` is preserved at
`benchmark/olympiad-5key-v1/results/real-20260825T050539Z`. P01/T1 run
`p01-t1-20260825T050546Z-9743918b` returned `INCOMPLETE` after 37 calls, 187,749 input tokens,
170,692 output tokens, 358,441 total tokens, and USD 0.230172855. It was not a run-budget
exhaustion: the SMOKE tier permits 48 calls and 2,304,000 tokens. The immutable Root Goal hash was
unchanged.

All three independent routes had complete symbolic proofs of the elementary gcd statement. The
failure had two separate orchestration causes:

1. Route 1 and Route 2 passed skeptic, structural, and detailed review, but their strategies each
   contained an optional `computation_hint`. Although neither strategy nor proof step bound a
   calculation artifact or submitted a `calculation_check`, the old risk mapping treated a hint as
   numerical evidence, required a Tool Specialist, and then rejected the proof because there was no
   computation trace to replay.
2. Route 3 produced a complete seven-step proof, but Route 2 and Route 3 were scheduled
   concurrently against the same fixed reviewer. Route 2 held that agent lease longer than the
   fixed acquisition timeout, so Route 3 never entered skeptic, structural, or detailed review and
   remained `reviewComplete=false`.

The repair preserves fail-closed handling for real computation dependencies while separating
advisory search ideas from evidence. Only a typed `calculation_check`, a bound
`calculation_evidence_ref`, or an actual durable computation trace can require Tool Specialist
review. A `computation_hint` remains available for optional falsification planning but cannot by
itself create a proof-closing tool gate. The same policy is used at route creation, restore, and
post-attempt risk reassessment.

Fixed reviewer identities are now explicit `resourceIds` in each authoritative route-review
conflict set. The existing frozen-snapshot scheduler serializes work that shares any reviewer
resource before a worker attempts to acquire an Agent Pool lease. Distinct reviewer sets remain
concurrent. The new conflict dimension is immutable, null-safe for older serialized work, and the
existing five-argument constructor remains source compatible.

The focused 28-test matrix passed with zero failures, errors, or skips. It covers the production
fake-provider path, all persisted route-review resource bindings, every concurrency durability
boundary, explicit computation evidence and requests, optional-hint route admission, and shared
reviewer scheduling:

```text
OPTIONAL COMPUTATION HINT ADMISSION DIAGNOSTIC
HINT_ONLY_STRATEGIES=4
ADMITTED_ROUTES=4
TOOL_SPECIALISTS_ASSIGNED=0
RESULT=PASS

SHARED REVIEWER RESOURCE SCHEDULING DIAGNOSTIC
ROUTE_REVIEWS=3
SUCCESSFUL_REVIEWS=3
MAXIMUM_CONCURRENT_SHARED_REVIEWERS=1
LEASE_TIMEOUTS=0
RESULT=PASS
```

The final module regression passed with 73 Contracts tests, 1,411 Core tests, 964 Server tests,
and 394 Desktop tests, with zero failures or errors and six intentional skips. The final
`scripts/verify-all.ps1 -Offline` run completed with `FULL VERIFICATION: PASS`: 2,991 tests
(73 Contracts, 1,411 Core, 964 Server, 394 Desktop, and 149 Compatibility) ran with zero failures
or errors and six intentional skips. Docker/Testcontainers, PostgreSQL 18.4, all seven Flyway
migrations, SpotBugs/FindSecBugs, coverage, security, source immutability, dependency checks,
Temporal checks, and the unchanged Python Sidecar performance gate all passed.

### Provider output-metering headroom

The next cold-start campaign from `a2f5462`, preserved at
`benchmark/olympiad-5key-v1/results/real-20260825T142216Z`, confirmed that optional computation
hints and shared-reviewer scheduling no longer blocked P01. Its P01/T1 run
`p01-t1-20260825T142226Z-150cce4c` instead stopped `INVALID` after two physical calls and
USD 0.031808505. The strategy-generation request had an explicit 32,000-token output limit, but
the provider's usage meter reported 32,001 completion tokens for the truncated response. The
physical budget reservation was exactly 32,000 output tokens, so the correct actual-usage commit
marked the envelope `ACTUAL_USAGE_OVERRUN`. A JSON-repair call had already been planned, but could
not reserve capacity after that overrun. This was neither global cost exhaustion nor mathematical
failure.

The repair reserves one explicit provider output-metering token in every physical Call Ledger and
Budget Envelope reservation while leaving the logical stage, agent, provider, and benchmark
output ceilings unchanged. When a containing action envelope is tight, the resolver reduces the
logical provider request by that one token so the physical reservation still fits exactly inside
the authorized action capacity. Expected cost includes the headroom, exact reported usage remains
durable, and no run, campaign, call, token, or USD limit was increased. A response reported at
`requested + 1` can therefore proceed to bounded structured repair; `requested + 2` remains a hard
`ACTUAL_USAGE_OVERRUN` and cannot trigger another provider call.

The focused cross-module matrix ran 33 tests with zero failures, errors, or skips. It covered the
new tight-action-envelope resolution, both provider metering boundaries, structured-output repair,
durable accounting, evidence-aware budgets, and stage-token diagnostics:

```text
PROVIDER OUTPUT METERING HEADROOM DIAGNOSTIC
REQUESTED_OUTPUT_LIMIT=provider logical maximum
RESERVED_OUTPUT_CAPACITY=REQUESTED_OUTPUT_LIMIT+1
REQUESTED_PLUS_ONE_REPAIR_CALLS=2
REQUESTED_PLUS_ONE_ENVELOPE_OVERRUNS=0
REQUESTED_PLUS_TWO_PROVIDER_CALLS=1
REQUESTED_PLUS_TWO_RESULT=ACTUAL_USAGE_OVERRUN
ACTION_ENVELOPE_TOKEN_BYPASSES=0
RESULT=PASS
```

The module regression then passed 73 Contracts tests, 1,412 Core tests, 939 Server unit tests, and
394 Desktop tests with zero failures or errors and six intentional skips. The complete release
gate then completed with `FULL VERIFICATION: PASS`: 2,994 tests (73 Contracts, 1,412 Core,
966 Server including 27 PostgreSQL/security integration tests, 394 Desktop, and 149
Compatibility) ran with zero failures or errors and six intentional skips. Docker/Testcontainers,
PostgreSQL 18.4, all seven Flyway migrations, SpotBugs/FindSecBugs, coverage, security, source
immutability, dependency checks, Temporal checks, and the unchanged Python Sidecar performance
gate all passed.

### Strategy candidate isolation after structured repair

The next cold-start campaign from `c572a02`, preserved at
`benchmark/olympiad-5key-v1/results/real-20260825T151531Z`, proved that provider output metering no
longer blocked repair. Its P01/T1 run `p01-t1-20260825T151541Z-653e106c` instead stopped `INVALID`
after four physical calls and USD 0.052778115. The immutable Root Goal hash was unchanged and no
strategy or route was admitted.

The initial 32,000-token strategy response was truncated. Full-envelope repair returned six
complete strategy candidates, but one candidate used `positive` as a `QuantifierSpec.kind` while
also using `positive` correctly as its enclosing Claim-context polarity. The strict contract
correctly permits only `forall`, `exists`, and `exists_unique` for a quantifier kind. Nested result
repair received that exact validation error but repeated the invalid literal, so whole-set
deserialization discarded the other five complete candidates as collateral damage. This was not a
budget exhaustion or a mathematical proof failure.

The repair keeps the quantifier contract strict and does not guess scope. After the existing safe
representation normalizations, a `StrategySet` with multiple candidates is validated one
`StrategyCard` at a time. Candidate-local contract failures are isolated only when at least one
other complete candidate remains. The original provider response artifact is retained unchanged,
the resulting structured call is marked repaired, and the surviving candidates continue through
the normal server-owned preflight, diversity, admission, and optional replenishment paths. If
every candidate is invalid, none is silently removed: bounded repair or strict rejection still
applies. In particular, `positive` is never rewritten to a guessed universal or existential
quantifier.

Both full-output and nested-result repair prompts now state that `quantifiers[].kind` accepts only
`forall`, `exists`, or `exists_unique`, that `positive` and `negative` belong to binding polarity,
and that an optional invalid strategy must be omitted rather than having its mathematical scope
invented. Checkpointed normalization is also propagated to `StructuredCallResult.repaired` instead
of being lost at the nested-result mapping boundary.

The regression was written and run before the production change. The Contracts test failed with
`kind has an unsupported literal: positive`, demonstrating that one invalid candidate still
invalidated the complete `StrategySet`; the reactor stopped before the new Server test ran. After
the repair, the focused cross-module matrix passed 49 tests with zero failures, errors, or skips.
It covers mixed valid/invalid candidate isolation, all-invalid fail-closed behavior, checkpointed
StrategySet parsing, nested repair instructions, generic structured repair, public-checkpoint
retention, desktop recovery accounting, and existing portfolio replenishment isolation.

The module regression then passed 75 Contracts tests, 1,412 Core tests, 941 Server unit tests, and
394 Desktop tests with zero failures or errors and six intentional skips. The complete release
gate completed with `FULL VERIFICATION: PASS`: 2,998 tests (75 Contracts, 1,412 Core, 968 Server
including 27 PostgreSQL/security integration tests, 394 Desktop, and 149 Compatibility) ran with
zero failures or errors and six intentional skips. Docker/Testcontainers, PostgreSQL 18.4, all
seven Flyway migrations, SpotBugs/FindSecBugs, coverage, security, source immutability, dependency
checks, Temporal checks, and the unchanged Python Sidecar performance gate all passed.

### Self-contained proof supersedes an unused strategy planning check

The cold-start campaign from `eb4aa802a3680e04a3abed109a91f832a89eea53`, preserved at
`benchmark/olympiad-5key-v1/results/real-20260825T161845Z`, confirmed the preceding repairs on two
different problems: P01/T1 and P02/T1 both reached `COMPLETE`. P03/T1 run
`p03-t1-20260825T175827Z-c48c2bb0` then stopped `INCOMPLETE` after 33 calls, 277,190 input tokens,
199,340 output tokens, and USD 0.29400345. Its Root Goal hash remained unchanged.

P03's AM-GM route contained a complete self-contained proof: substitute `1-a=b+c`, `1-b=c+a`,
and `1-c=a+b`; multiply the three two-variable AM-GM inequalities; and use simultaneous equality
to obtain `a=b=c=1/3`. Skeptic, structural, detailed, deterministic, same-model blind, and
adversarial-blind validation all passed. The route was nevertheless marked `unverified` only
because the original Strategy Card had requested a planning-time symbolic calculation, the final
ProofAttempt did not depend on that calculation, no computation trace existed, and the old route
gate still demanded an independent replay. Its sole terminal diagnostic was `tool evidence was
not independently replayed`.

The repaired policy keeps the conservative Tool Specialist assignment made from the planning-time
risk assessment. Once a ProofAttempt is `COMPLETE`, has at least one proof step and a final answer,
has no unresolved gaps, and contains neither calculation requests nor calculation evidence
references, that unused strategy request no longer creates a hard final replay gate and no replay
provider call is made. This exception
does not apply when any durable computation trace exists, the Strategy binds an evidence reference,
the ProofAttempt requests a calculation, the ProofAttempt cites calculation evidence, or the
attempt is partial or has an unresolved gap. Those cases still fail closed and require independent
replay.

The new production-pipeline regression was run before the implementation change. It sent three
strategies with explicit planning checks through the real Desktop backend and submitted complete
symbolic ProofAttempts with no computation dependency. The old code returned `unverified`. After
the repair, the same production path reported:

```text
SELF-CONTAINED PROOF TOOL-GATE DIAGNOSTIC
STRATEGY_PLANNING_CHECKS=3
ATTEMPT_TOOL_DEPENDENCIES=0
TOOL_REPLAY_CALLS=0
VERIFIED_ROUTES=3
FALSE_TOOL_GATE_REJECTIONS=0
RESULT=PASS
```

The focused 33-test matrix passed with zero failures, errors, or skips. It covers the exact
production regression, the replay decision matrix, optional hints, full Desktop execution,
registered strategy preflight, bounded non-refutation, independently replayed counterexamples,
preflight crash recovery, full-context computation bindings, and failed-route artifact handling.
The module regression then passed 75 Contracts tests, 1,412 Core tests, 941 Server unit tests, and
397 Desktop tests with zero failures or errors and six intentional skips.

The first full release-gate run exposed an engineering-only regression: the initial implementation
made the already large `DesktopSolveCoordinator` exceed SpotBugs' analysis-size ceiling. All tests
had passed, but the static-analysis gate correctly rejected the build. The replay decision was
therefore kept in `RouteComputationEvidencePolicy` and the unnecessary Coordinator replanning code
was removed. A direct SpotBugs rerun then reported zero bugs and zero errors for Contracts, Core,
Server, and Desktop.

The final `verify-all.ps1 -Offline` run passed 3,001 tests: 75 Contracts, 1,412 Core, 941 Server
unit, 27 Server PostgreSQL/Testcontainers integration, 397 Desktop, and 149 Compatibility tests,
with zero failures or errors and six intentional skips. Docker/PostgreSQL, all Flyway migrations,
SpotBugs/FindSecBugs, dependency checks, coverage, security, source immutability, Temporal checks,
and the unchanged Python Sidecar performance gate all passed. The final result was `FULL
VERIFICATION: PASS`.

### Authoritative computation-target isolation

The next clean cold-start campaign from `0bbf2897abc239a3a1dbcbb6eee449fa0cda6736` is preserved at
`benchmark/olympiad-5key-v1/results/real-20260825T195001Z`. It confirmed the preceding repair on
three consecutive real problems: P01/T1, P02/T1, and P03/T1 all reached `COMPLETE`. In particular,
P03 no longer scheduled a Tool Specialist or rejected its self-contained symbolic proof because
of an unused planning check.

P04/T1 run `p04-t1-20260825T220505Z-f0ef93f9` then stopped `INVALID` after 18 physical calls,
149,096 input tokens, 151,533 output tokens, 300,629 total tokens, and USD 0.19669047. Its initial
and final Root Goal hashes were both
`2a62f00da1d899842fc8b1c25f62e1a48a7847bf6dcff6cabeb8cc60f0f23396`. The terminal failure was
`IllegalArgumentException (ComputationExecutionContext#<init>:31)`, not a budget failure or a
mathematical rejection.

The model supplied a nonblank target Claim ID together with an incomplete semantic binding whose
statement and semantic hashes were blank. The existing exact-target resolver correctly downgraded
that input to an isolated computation question. The execution path nevertheless constructed
`ComputationExecutionContext` from the raw model `ExperimentSpec`, so the constructor saw a Claim
ID without its required semantic hash and aborted. The Core outcome projector also had a second
authority bypass: when the authoritative context contained no Claim ID, it could re-import the raw
model Claim ID during projection.

The repair makes the server-resolved `ComputationTargetBinding` the only production authority for
the execution context. `ComputationOutcomeProjector` now preserves an empty Claim binding in an
authoritative context; fallback to the raw spec remains only for explicitly non-authoritative
legacy contexts. A downgraded computation may close its own isolated computation question, but it
cannot create or update the model-named Claim and cannot promote a Fact under that Claim identity.

The two regression tests were run before the production change. The Core test failed because the
projector returned `model-proposed-claim` instead of an empty Claim ID. The Desktop production-path
test failed at the same `ComputationExecutionContext` constructor as the real P04 run with
`claim-bound computation requires claimSemanticHash`.

After repair, the focused Core/Desktop pair passed with this diagnostic:

```text
UNTRUSTED_BINDING_EXECUTION_CONTEXT_FAILURES=0
UNTRUSTED_MODEL_CLAIM_AUTHORITY_BINDINGS=0
UNTRUSTED_MODEL_CLAIM_AUTHORITY_PROJECTIONS=0
ISOLATED_COMPUTATION_QUESTION_PROJECTIONS=1
RESULT=PASS
```

The adjacent computation-boundary matrix passed 12 tests with zero failures or errors. It covered
wrong focused obligations, similarity-only targets, full-context bindings, exact-context Negative
Knowledge, counterexample context round trips, native computation, and wrong-target certificate or
counterexample projection. All wrong-target closures, refutations, and authority bindings remained
zero.

The module regression passed 2,827 tests: 75 Contracts, 1,413 Core, 941 Server, and 398 Desktop
tests, with zero failures or errors and six intentional skips. The final
`scripts/verify-all.ps1 -Offline` run completed with `FULL VERIFICATION: PASS`: 3,003 tests
(75 Contracts, 1,413 Core, 941 Server unit, 27 Server PostgreSQL/security integration, 398 Desktop,
and 149 Compatibility) ran with zero failures or errors and six intentional skips. PostgreSQL
18.4 Testcontainers, all Flyway migrations, SpotBugs/FindSecBugs, dependency and security checks,
coverage, source immutability, Temporal checks, and the unchanged Python Sidecar performance gate
all passed.

### Bounded final-proof repair and independent rereview

The cold-start campaign preserved at
`benchmark/olympiad-5key-v1/results/real-20260825T232127Z` first confirmed that the earlier P01
failure was no longer reproducible. P01/T1 run `p01-t1-20260825T232134Z-192e3447` reached
`COMPLETE` after 33 calls at USD 0.247992195 with an unchanged Root Goal hash. P02/T1 run
`p02-t1-20260825T235605Z-cd2a46ad` then stopped `INCOMPLETE` after 36 calls, 233,953 input tokens,
267,310 output tokens, and USD 0.334329255. Its Root Goal hash also remained unchanged.

P02's proof used the correct extremal argument, but final step `s9` omitted the boundary case
`i=k-1` and its dependency on `s7`. The structural final reviewer identified that exact local
repair and reported `FailureLevel.PLAN` with `problem_integrity_ok=true`. Blind and adversarial
review found the proof locally repairable. The production pipeline nevertheless treated the first
structural failure as terminal even though `final_revision` was already part of the configured
prompt catalog. This was not a budget failure, a root-goal drift, or a wrong mathematical route.

The repair permits exactly one final-proof revision only when the structural reviewer failed the
final-proof target, confirmed problem integrity, found no critical or strategy-level defect,
requested no tool action, and supplied a nonblank repair hint for every error. The revision sees
the immutable source problem, original proof, exact review findings, proof graph, and authoritative
source-attempt IDs. Server code then restores the authoritative problem hash and source-attempt
IDs instead of trusting those fields from the model. A fresh structural-review call independently
reviews the revised proof. There is no repair loop: an ineligible defect, an integrity failure, a
failed rereview, or an ordinary repair failure remains unverified. The existing blind,
adversarial, deterministic, computation, and authority gates remain unchanged.

The production-path regression was first run against the old behavior and failed with
`expected <completed> but was <unverified>`. After the repair it reported:

```text
FINAL PROOF LOCAL REPAIR DIAGNOSTIC
INITIAL_STRUCTURAL_FAILURES=1
FINAL_REVISION_CALLS=1
STRUCTURAL_REREVIEWS=1
ROOT_HASH_REBIND_FAILURES=0
SOURCE_ATTEMPT_REBIND_FAILURES=0
RESULT=PASS
```

The focused policy and production-chain suite passed 7 tests, and the adjacent final-proof,
benchmark-path, execution-backend, structured-recovery, and token-envelope matrix passed 31 tests,
all with zero failures or errors. The four-module regression then passed 75 Contracts tests, 1,413
Core tests, 941 Server tests, and 405 Desktop tests with zero failures or errors and six intentional
skips. The real campaign was stopped immediately after the P02 defect, so its partially started P03
run is not treated as benchmark evidence.

The first full release-gate run let all 405 Desktop tests finish successfully but then rejected a
generic `RuntimeException` rethrow at the new fail-closed boundary. The implementation was narrowed
to rethrow the typed budget-exhaustion and provider-cancellation failures directly; no SpotBugs
suppression or gate relaxation was added. A direct four-module SpotBugs rerun then reported zero
bugs and zero errors. The final `scripts/verify-all.ps1 -Offline` run completed with `FULL
VERIFICATION: PASS`: 3,010 tests (75 Contracts, 1,413 Core, 941 Server unit, 27 Server
PostgreSQL/security integration, 405 Desktop, and 149 Compatibility) ran with zero failures or
errors and six intentional skips. Docker/PostgreSQL, all Flyway migrations,
SpotBugs/FindSecBugs, dependency and security checks, coverage, source immutability, Temporal
checks, and the unchanged Python Sidecar performance gate all passed.

### Empty-admissible strategy portfolio replenishment

The five-key campaign at `benchmark/olympiad-5key-v1/results/real-20260826T020609Z`
reused the already complete P01-P06 bundles without additional provider calls. Its first P07
attempt stopped before research after 3 calls and USD 0.031458765: strategy generation returned an
underfilled portfolio whose only candidate was rejected by deterministic admission, leaving zero
admissible routes. The existing replenishment path only handled an underfilled accepted portfolio,
so a recoverable generation result was incorrectly treated as terminal.

The production repair requests one bounded replenishment when, and only when, strategy admission
leaves an empty portfolio. It does not weaken candidate admission, count rejected strategies as
progress, or repeatedly replenish a nonempty underfilled portfolio. An initially broader change
was rejected during regression because it changed protected Campaign Finding behavior; the final
condition is deliberately limited to zero admissible strategies.

The focused replenishment and adjacent production matrix passed 12 tests. The Desktop regression
at that revision passed 407 tests with zero failures or errors. P07 was then rerun by itself and
reached `COMPLETE` after 44 calls at USD 0.298747125 with an unchanged Root Goal. P08 resumed from
its durable checkpoint, reached `COMPLETE` after 31 calls at USD 0.224465220, and did not repeat
triage. P01-P06 were not rerun.

### Stable single-writer research finding merges

P09/T1 run `p09-t1-20260826T210125Z-75ae9ff2` reached the durable
`research_epoch_all_settled` checkpoint after 14 physical calls, 118,301 input tokens, 113,110
output tokens, and USD 0.149866635. Its initial and current Root Goal hashes were both
`0cb0a46f4e99f355767519c56199cbb190bea8fe721a5735a68afb791d2857c9`. The run then stopped on
`research finding worker result conflicted`; it was not a mathematical `INCOMPLETE` result and no
later problem was started.

The focused research workers had correctly shared one frozen Epoch snapshot, but several workers
returned different dispositions for the same pre-existing Research Finding. The old stable commit
merged every complete worker snapshot before choosing the primary result. Consequently two
same-version records with different statuses could abort the commit even though only the stable
single writer was allowed to mutate Route authority.

The repair orders worker results deterministically by commit priority, stable ordinal, and work
item ID. The stable primary worker owns disposition changes to findings that existed at the frozen
frontier. Secondary workers cannot rewrite those findings, but their route-local append-only
findings, checkpoints, and matching audit events are still retained. Genuine checkpoint ID
collisions and non-frozen same-version conflicts continue to fail closed.

The new production-path test was first run before the repair and failed with the same
`IllegalStateException` and merge location as P09. After the change it reported:

```text
CONCURRENT RESEARCH FINDING MERGE DIAGNOSTIC
FOCUSED_WORKERS=4
CONFLICTING_SECONDARY_DISPOSITIONS=2
PRIMARY_FINDING_STATUS=DEFERRED
PRIMARY_FINDING_VERSION=1
COMMITTED_DISPOSITION_AUDITS=1
WORKER_APPEND_ONLY_FINDINGS_PRESERVED=4
WORKER_APPEND_ONLY_CHECKPOINTS_PRESERVED=4
ROOT_HASH_CHANGES=0
NEGATIVE_REGISTRY_HASH_CHANGES=0
RESULT=PASS
```

The adjacent frozen-Epoch, completion-order, atomicity, hard-crash recovery, checkpoint restore,
unknown-update, focused-worker, Campaign Finding, and empty-portfolio matrix passed 17 tests. The
full Desktop-and-dependencies regression passed 3,013 tests: 75 Contracts, 1,413 Core, 968 Server,
408 Desktop, and 149 Compatibility tests, with zero failures or errors and six intentional skips.
A no-test `verify` then passed all five modules; SpotBugs reported zero bugs and zero errors.
P01-P08 remain immutable completed evidence.

### Bounded recovery after every admitted route is exhausted

P09 resumed from the durable research checkpoint without repeating its first 14 provider calls and
crossed the former merge failure. It later stopped `INCOMPLETE` after 31 calls, 191,833 input
tokens, 181,620 output tokens, and USD 0.241456755. The Root Goal hash remained
`0cb0a46f4e99f355767519c56199cbb190bea8fe721a5735a68afb791d2857c9`. This was neither a budget
exhaustion nor an invalid proof translation: 17 calls, 1,930,547 tokens, USD 1.7630, five route
slots, and six scheduler rounds remained.

The sole admitted mechanism had produced a coherent complex-coordinate proof and passed its
Skeptic review, but a durable computation trace remained inconclusive and its replay could not be
completed inside the local Tool Specialist envelope. The computation evidence gate therefore
correctly failed closed. The route then exhausted its one revision, while no queued admitted
strategy remained. The scheduler only knew how to widen from that fixed queue, so it emitted
`STOP_NO_ADMISSIBLE_WORK` without asking for another independent mechanism.

The repair adds one stable `scheduler-recovery` portfolio episode. It is eligible only when open
obligations remain, every existing route is non-deepenable and non-revisable, the route and round
caps have capacity, calls remain, and focused recovery is inactive. Before any provider call or
route mutation, `DesktopBudgetScheduler` durably reserves the ordinary multidimensional `WIDEN`
envelope, whose estimate already includes one planner call and bounded route exploration. The
supplement then passes the existing Blueprint, explicit Claim Context, critical-Claim Preflight,
Negative Knowledge, mechanism-diversity, and route-widening gates. No computation, Claim, Fact,
or final-proof authority rule was relaxed.

The replenishment request, candidate set, portfolio decision, apply receipt, admitted strategies,
Blueprints, Goal Links, and next-strategy cursor are checkpointed. A fresh Coordinator can restore
the episode and consume remaining candidates without another provider call. An active recovery
budget envelope is also recognized after restore. An invalid or empty optional supplement records
a terminal empty result and does not loop.

The new test was run before the production change and failed at the real `widenRoutes()` boundary:
`Expecting value to be true but was false`. After repair, including JSON checkpoint round-trip and
fresh-Coordinator restore, it reported:

```text
EXHAUSTED PORTFOLIO RECOVERY DIAGNOSTIC
INITIAL_ADMITTED_MECHANISMS=1
EXHAUSTED_INITIAL_ROUTES=1
REMAINING_ROUTE_CAPACITY_PRESENT=true
SCHEDULER_REPLENISHMENT_CALLS=1
NEW_ROUTE_ADMISSIONS=3
REPEATED_REPLENISHMENT_CALLS=0
POST_RESTORE_REPLENISHMENT_CALLS=0
ROOT_HASH_CHANGES=0
NEGATIVE_REGISTRY_HASH_CHANGES=0
RESULT=PASS
```

The adjacent Portfolio replenishment, shortfall preservation, invalid-output isolation, mechanism
widening, hard-crash recovery, Campaign Finding, concurrent Finding merge, and budget no-bypass
matrix passed 11 tests with zero failures or errors.

The focused recovery test and architecture gate passed three tests. The complete Desktop reactor
regression then passed 2,838 tests: 75 Contracts, 1,413 Core, 941 Server, and 409 Desktop, with zero
failures, zero errors, and six intentional skips. The full run used PostgreSQL 18.4 Testcontainers
and all seven Flyway migrations. A following no-test `verify` passed all five reactor modules;
SpotBugs reported zero bugs and zero errors. No static-analysis rule or performance threshold was
relaxed.

### Route-theorem authority and synthesis closure

The next isolated P09 attempt, `p09-t1-20260826T225329Z-7872d424`, stopped `INCOMPLETE` after 38
physical calls at USD 0.338018055. The campaign total was USD 2.860314225, and the initial and final
Root Goal hashes remained
`0cb0a46f4e99f355767519c56199cbb190bea8fe721a5735a68afb791d2857c9`. A route-level reviewer and
the final blind and adversarial reviewers accepted a coherent eight-step complex-coordinate proof
of Simson's theorem. Claim Court, however, correctly returned `REPAIR_EXHAUSTED`: the proposed
route-theorem patch changed five proof steps and exceeded the existing deterministic limit of
three. That limit was not changed.

Two authority projections were inconsistent after that valid rejection. The route retained its
stale `verified` status even though its route-theorem artifact was `UNCERTAIN`, and the production
`deepseek-v4-pro.yaml` profile mapped an open-main-goal synthesis block to advisory pass because
optional Proof Control mode was off. The proof graph still contained an open `main-goal`, so final
deterministic validation rejected the result only after unnecessary synthesis calls.

The repair now revokes route-level verified authority whenever a `ROUTE_THEOREM` Claim Court case
does not finish `VERIFIED`. The route status and failure reason are included in the existing atomic
Claim Court projection snapshot, so injected projection failures restore the prior authority and a
deterministic retry applies the rejection exactly once. Synthesis now has an unconditional server
precondition: the canonical `main-goal` obligation must actually be closed. Optional Proof Control
mode can add stricter checks but can no longer turn an open main goal into synthesis authority.

The production-path test was first run against the old behavior and failed with `expected
<unverified> but was <verified>` while the Claim Court outcome was already
`REPAIR_EXHAUSTED`. After the repair, the focused and adjacent authority/atomicity matrix passed
seven tests with zero failures or errors and reported:

```text
ROUTE THEOREM CLAIM LIFECYCLE DIAGNOSTIC
ROUTE_LEVEL_REVIEWS_PASSED=1
ROUTE_THEOREM_REPAIR_EXHAUSTIONS=1
OVERSIZED_REPAIR_BYPASSES=0
STALE_VERIFIED_ROUTE_PROJECTIONS=0
PREMATURE_SYNTHESIS_ADMISSIONS=0
MAIN_GOAL_CLOSURES=0
RESULT=PASS
```

The complete Desktop reactor regression passed 2,841 tests: 75 Contracts, 1,413 Core, 941 Server,
and 412 Desktop, with zero failures, zero errors, and six intentional skips. PostgreSQL 18.4
Testcontainers started successfully and all seven Flyway migrations ran. The existing Claim Court
repair-size gate, Root Goal Contract, Negative Knowledge registry, and all Issue 001-013 authority
boundaries remain unchanged.

### Mandatory review budget cannot be starved by optional recovery

The next isolated P09 attempt, `p09-t1-20260827T003115Z-41020a5d`, stopped
`INCOMPLETE` after 38 physical calls at USD 0.459785865. P01-P08 were reused without provider
calls, and the Root Goal hash remained
`0cb0a46f4e99f355767519c56199cbb190bea8fe721a5735a68afb791d2857c9`. The preceding
route-theorem lifecycle repair behaved correctly: the invalid theorem authority was revoked, the
main goal stayed open, and no synthesis result leaked through. The new failure was scheduling
starvation inside a seven-call proof-task envelope.

Optional focused roles and structured-output recovery consumed six calls. The Skeptic consumed the
seventh, so the structural and detailed independent reviews were denied. Three supporting local
claims then consumed six Claim Court calls, leaving no capacity for the next bounded proof repair
although three unused strategies and open proof debt remained. This was not solved by widening the
frozen benchmark budget.

The production repair adds a multidimensional protected floor to a live action envelope. Initial
route exploration and proof-task batches protect the ordinary `VERIFY` estimate for every admitted
route; optional provider calls cannot cross that floor, including under concurrent reservation.
The floor is released immediately before independent route review and is reconstructed for an
active pre-review envelope after checkpoint restore. A bounded repair now runs one authoritative
focused Prover before the existing independent Skeptic, structural, and detailed review gates;
the four-role matrix remains unchanged for initial focused research.

Supporting Claim Court cases remain durable but are deferred when reviewing them would consume the
last capacity for one more proof repair. A verified route theorem remains closure-critical and is
never filtered by this policy. Pending proof-task scheduling now reserves the largest stable
affordable prefix rather than rejecting an entire batch because its tail does not fit. No Claim
Court authority rule, finalization reserve, benchmark limit, Root Goal rule, or Negative Knowledge
rule was relaxed.

The red test run first failed to compile because the prior production API had no protected physical
reservation, affordable-prefix selection, or supporting-court affordability decision. After the
repair, the focused tests passed four cases and reported:

```text
BOUNDED REPAIR FOCUSED-ROLE DIAGNOSTIC
BOUNDED_REPAIR_AUTHORITATIVE_PROVERS=1
OPTIONAL_MATRIX_CALLS=0
RESULT=PASS

PROTECTED_AUTHORITY_REVIEW_CALLS=3
OPTIONAL_CALLS_BEFORE_PROTECTION=4
OPTIONAL_CALLS_BLOCKED_AT_FLOOR=1
AFFORDABLE_BATCH_ATTEMPTS=[3, 2, 1]
AFFORDABLE_BATCH_ADMISSIONS=1
SUPPORTING_COURT_REPAIR_RESERVE_VIOLATIONS=0
```

The adjacent route-theorem, Claim Court, structured recovery, concurrent Finding, and no-bypass
matrix passed after updating the architecture assertion to require prefix reservation before the
first proof-task mutation. The complete Desktop reactor regression passed 2,845 tests: 75
Contracts, 1,413 Core, 941 Server, and 416 Desktop, with zero failures, zero errors, and three
intentional skips in each of the Server and Desktop modules (six total). PostgreSQL 18.4
Testcontainers started successfully and all seven Flyway
migrations ran. A no-test `verify` passed all five modules; SpotBugs reported zero bugs and zero
errors.

### Canonicalized Blueprint edges cannot crash route admission

The first isolated P09 rerun after the mandatory-review reservation change,
`p09-t1-20260827T023015Z-6feb65cb`, stopped `INVALID` after nine physical calls at USD
0.074594670. P01-P08 were reused without provider calls, and the initial and final Root Goal hashes
both remained `0cb0a46f4e99f355767519c56199cbb190bea8fe721a5735a68afb791d2857c9`.
The failure was a deterministic route-admission exception rather than a model-budget failure:
`ProofGraphStore#addEdge` rejected a self edge.

The Blueprint contained two different node IDs with the same mathematical statement. Proof Graph
canonicalization correctly collapsed the second obligation into an alias of the first, but the
edge materializer retained the two pre-canonicalization IDs. Resolving those aliases inside
`addEdge` therefore turned an ordinary Blueprint edge into a canonical self edge and aborted the
otherwise atomic portfolio admission.

The materializer now records the actual graph node ID after every obligation write, translates
both endpoints through that map, and deterministically elides only edges whose endpoints become
identical after canonicalization. Missing or non-materialized endpoints remain skipped under the
existing Blueprint policy. `ProofGraphStore` still rejects callers that directly attempt a real
self edge; its invariant was not weakened.

The production black-box test was run before the change and reproduced the exact live stack:
`ProofGraphStore.addEdge -> DesktopSolveCoordinator.addBlueprintObligations`. After repair, the
focused test and adjacent admission atomicity and 20-round canonicalization restore tests passed:

```text
DUPLICATE_BLUEPRINT_STEPS=2
CANONICAL_BLUEPRINT_NODES=1
CANONICAL_SELF_EDGE_FAILURES=0
RESULT=PASS

PARTIAL_ARCHIVE_WRITES=0
PARTIAL_BLUEPRINT_WRITES=0
PARTIAL_GOAL_LINK_WRITES=0
PARTIAL_ADMITTED_STRATEGIES=0
PARTIAL_ROUTE_CREATIONS=0
PARTIAL_PROOF_GRAPH_WRITES=0
TASK_LEASE_LEAKS=0
```

### Prepared research epochs tolerate durable non-authoritative findings

The next isolated P09 attempt, `p09-t1-20260827T030727Z-c2c5caad`, stopped `INVALID` after 11
physical calls at USD 0.117475230. P01-P08 were reused without provider calls. Two of the three
concurrent route-exploration results completed with durable public findings; the third result was
also recorded durably after its provider attempt failed. The epoch reached `MERGE_PREPARED`, with
all three result envelopes present and with no authority-mutation or merge receipt. The initial and
final Root Goal hashes both remained
`0cb0a46f4e99f355767519c56199cbb190bea8fe721a5735a68afb791d2857c9`.

The first checkpoint continuation made zero provider calls and failed after 22 seconds at
`DesktopSolveCoordinator#reconcileResearchEpochAuthorityCommitsAfterRestore:1076` with:

```text
QUARANTINED_PARTIAL_AUTHORITY_COMMIT:
[canonicalization, research_checkpoints, run_authority]
```

The mathematical authority had not changed. `ResearchCheckpointLedger` is the durable,
non-authoritative public-finding sidecar, but restore compared its advancing hash as though it were
Claim, Proof Graph, Root Goal, or Negative Knowledge authority. This rejected a valid prepared
epoch before its already durable results could be committed exactly once.

Prepared-epoch restore now uses a dedicated authority boundary. It is available only to a
`MERGE_PREPARED` epoch with no mutation or merge receipts. All authoritative projections must still
match the frozen anchor; only the research-checkpoint hash may advance. The complete current
research projection must also be bound to the same `problemHash` and pass checkpoint-to-finding,
route, provider-call, reverse-membership, and audit-reference integrity checks. Cross-problem or
internally inconsistent sidecars remain quarantined. Hash comparisons use constant-time equality,
and the boundary is isolated from the already large coordinator so the complete SpotBugs analysis
remains effective.

The focused restore test passed both the valid same-problem roll-forward and the cross-problem
fail-closed case:

```text
PREPARED RESEARCH EPOCH SIDECAR RESTORE DIAGNOSTIC
PREPARED_EPOCHS=1
DURABLE_RESEARCH_CHECKPOINTS=1
RESTORE_FAILURES=0
DUPLICATE_PROVIDER_CALLS=0
COMMITTED_EPOCHS=1
ROOT_HASH_CHANGES=0
NEGATIVE_REGISTRY_HASH_CHANGES=0
RESULT=PASS
```

The adjacent authoritative hard-crash, frozen-epoch pipeline, concurrent-task replay, 60-finding
multi-round restore, and v20 prepared-epoch protocol matrix passed seven tests with zero failures,
zero errors, and zero skips. The complete Desktop reactor regression passed 2,848 tests: 75
Contracts, 1,413 Core, 941 Server, and 419 Desktop, with zero failures, zero errors, and six
intentional skips. PostgreSQL 18.4 Testcontainers started successfully and all seven Flyway
migrations ran. A no-test `verify` passed all five modules; SpotBugs reported zero bugs and zero
errors.

### Strategy authority hashes survive a new JVM

A following zero-provider checkpoint diagnostic crossed the research-sidecar boundary but still
failed closed on `strategy_portfolio`. The underlying strategy records were mathematically
unchanged. Several authority-bearing fields are sets, however, and the former `Set.copyOf` copies
did not retain the iteration order written to the checkpoint. A fresh JVM could therefore emit the
same set members in a different JSON array order and produce a different `CanonicalJson` hash.
This was a persistence determinism defect, not permission to ignore the strategy authority hash.

The repair keeps these collections immutable while preserving their persisted encounter order.
Jackson is bound narrowly to `LinkedHashSet` for the affected strategy mechanism and preflight set
components, and their records retain that order in immutable defensive copies. No set member,
strategy, Claim, route, or authority comparison is removed. A new serialization regression uses
20 deliberately reversed semantic keys and verifies canonical hash stability across JSON
write/read for both strategy mechanism and preflight snapshots.

The focused test passed once, and the adjacent strategy, prepared-epoch restore, and authoritative
hard-crash matrix passed 49 tests: two Contracts, 33 Core, four Server, and ten Desktop tests, with
zero failures, errors, or skips. The 20-round production strategy diagnostic retained identical
candidate, mechanism, and portfolio hashes before and after restore.

## Protected behavior

No API key, raw provider response, authorization header, target output, database file, or checkpoint
is part of this change. Issues 001-013 remain authoritative and their tests were not removed,
skipped, relaxed, or rewritten to accept weaker behavior. In particular, this repair does not turn
a billing record into mathematical progress and does not let terminal accounting replace the
semantic checkpoint.
