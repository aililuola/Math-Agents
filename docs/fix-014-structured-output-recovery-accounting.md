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

The benchmark token plan now reserves 16,000 average input tokens plus a 32,000-token output
envelope for every physical call. Calls and rounds are unchanged.

| Tier | Calls | Rounds | Run token cap | Output cap per call |
| --- | ---: | ---: | ---: | ---: |
| SMOKE | 24 | 6 | 1,152,000 | 32,000 |
| CORE | 40 | 8 | 1,920,000 | 32,000 |
| ADVANCED | 64 | 12 | 3,072,000 | 32,000 |
| STRESS | 96 | 16 | 4,608,000 | 32,000 |

The immutable 34-run ceiling is 2,128 calls and 102,144,000 tokens. At the frozen worst-case
output price it is USD 88.86528, leaving USD 11.13472 below the separately enforced USD 100 cap.

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

## Protected behavior

No API key, raw provider response, authorization header, target output, database file, or checkpoint
is part of this change. Issues 001-013 remain authoritative and their tests were not removed,
skipped, relaxed, or rewritten to accept weaker behavior. In particular, this repair does not turn
a billing record into mathematical progress and does not let terminal accounting replace the
semantic checkpoint.
