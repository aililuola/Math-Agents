# Issue 013: Evidence-aware budget, token, and stop policy

## Phase 0 baseline and production map

This record starts from `c1f97ce4e271867b76335a8adfb1347ef3640658` on
`fix/012-sustained-multi-key-concurrency`. At this point the worktree was clean and the Issue 013
branch was created as `fix/013-evidence-aware-budget-token-stop-policy`.

No verification result is claimed in this section. It records the pre-fix production surface and
the failure hypotheses that the tests must expose.

### Existing authorities

| Concern | Existing production authority before Issue 013 |
| --- | --- |
| Scheduler decision | `AdaptiveBudgetManager`; one Desktop construction and one Desktop decision call |
| Soft allocation | `SoftBudgetAllocator`; call-share allocator, currently exercised by parity tests rather than the live scheduler |
| Physical call cap | `CallLedger`; reserves calls, aggregate tokens, and estimated cost immediately before provider dispatch |
| Durable provider fact | `ProviderCallRecord`, `ProviderCallRepository`, and `DurableProviderUsageCollector` |
| Provider execution | `StructuredAgentRunner`; Desktop live runtime constructs the production instances |
| Frozen concurrency | `DesktopResearchEpochExecutor`, `ResearchReadyQueue`, agent leases, deterministic merge, and epoch receipts from Issue 012 |
| Desktop persistence | `DesktopSolveCheckpoint`, schema 21 |
| Database persistence | Flyway V1 through V6; V6 stores research concurrency epochs |
| Temporal | deterministic solve and route workflows plus idempotent workflow activities |

### Decision and admission paths

The only production construction of `AdaptiveBudgetManager` is in `DesktopSolveCoordinator`. The
live decision call is in the scheduler decision cursor and currently passes a caller-created
`round-N` action key, attempt evidence, route count, remaining calls, coverage, and uncertainty.
The result can schedule only `WIDEN`, `DEEPEN`, or `REVISE`. A subsequent Coordinator fallback can
also schedule those actions without consulting the manager.

`SoftBudgetAllocator` currently allocates only call counts among breadth, depth, verification, and
synthesis. It has no production admission call that binds a complete action to token and cost
resources before the Issue 012 ready queue.

`DesktopResearchEpochExecutor` is the production frozen-epoch executor. Issue 013 admission must
occur before work is submitted to its `ResearchReadyQueue`; workers must consume an assigned
envelope rather than recompute a global decision.

### Provider and token paths

`StructuredAgentRunner` owns the physical `CallLedger.reserve`, provider dispatch, durable call
transition, normal settlement, release, and ambiguous-result settlement path. JSON repair and
failover are physical calls and therefore remain under that same ledger.

Ordinary Desktop stages currently obtain a configured output limit directly from
`runtime.stage_output_token_limits` in both `DesktopSolveCoordinator` and
`DesktopLiveRunExecutionBackend`. `AgentRuntime` separately clamps the request to the agent and
provider maximum. Continuation has per-segment limits and delta verification settings; Deep
Exploration has independent 64K/96K/128K tiers and partial-repair limits. These independent clamps
are not yet resolved with action remaining tokens, global remaining tokens, or finish reserve by
one pure resolver.

### Persistence and recovery

Desktop recovery restores aggregate durable provider usage into a fresh `CallLedger`, reconciles
it through `DurableProviderUsageCollector`, and then restores the Issue 001-012 authority stores.
Schema 21 has no canonical budget decision, action envelope, pricing, zero-gain, or certified-gain
snapshot. Flyway V6 is the latest migration and has no durable budget envelope tables.

Temporal workflows replay deterministic workflow state and idempotent activities, but the current
workflow contract does not carry the canonical budget-state or decision identity required by this
issue.

### Protected mathematical and concurrency surface

Issue 013 may add a scheduling sidecar to the Coordinator, checkpoint, provider path, and workflow.
It must not change Root Goal authority, Negative Knowledge authority, Attempt/Claim lifecycle,
Research Checkpoint authority, canonical Proof Graph semantics, Semantic Pivot semantics, Strategy
Portfolio admission, Claim Court role separation, Mathematical Artifact Broker authority,
reproducible-computation authority, reconciled run-state semantics, or Issue 012 frozen epoch,
lease, receipt, and crash-atomicity rules.

### Pre-fix risks to reproduce

1. The `actionKey` decision cache can return a stale decision after evidence or remaining resources change.
2. The scheduler sees only remaining calls, so token or cost exhaustion can reject work only at the provider boundary.
3. Complex continuation and verification actions can be estimated as one call.
4. Exploration can consume resources needed for synthesis and final verification.
5. Valid model output with no committed public mathematical gain can continue to receive depth budget.
6. A remote provider with zero or unknown pricing can look free while a cost cap is active.
7. An ambiguous dispatched call can retain call/cost accounting without conservatively retaining its token exposure.
8. Completion order can affect ad hoc evidence collection unless all budget inputs are canonicalized.
9. Checkpoint schema 21 cannot restore decisions, envelopes, pricing, certified gain, or zero-gain state.

### Configuration map

Generic, smoke, and live profiles already expose total calls, optional total tokens and cost,
budget shares, stage output token limits, continuation settings, agent/provider output limits, and
Deep Exploration tiers. Issue 013 will extend those existing values and validation rules rather
than introduce a second configuration authority. Live pricing remains configuration-backed; the
implementation will not query external pricing services.

## Pre-fix evidence

The test-first run was performed before adding any Issue 013 production type or changing the
provider ledger.

| Test | Pre-fix result | Exposed gap |
| --- | --- | --- |
| `Issue013BudgetArchitecturePreFixTest` | 2 errors | `BudgetStateSnapshot`, `BudgetResourceVector`, and the action-envelope/token resolver API did not exist |
| `CallLedgerAmbiguousUsageRetentionTest` | 1 failure | an 8,000-token dispatched reservation committed as ambiguous retained the call and cost but reported 0 retained tokens |

This is both architecture-missing and observable behavior evidence. The second test directly
demonstrates `AMBIGUOUS_USAGE_FAIL_OPEN_RELEASES=1` at the baseline rather than treating missing new
APIs as a complete behavioral regression proof.

## Implementation and verification

### Production implementation

The implementation extends the existing budget and provider path instead of introducing a second
authority:

| Layer | Change |
| --- | --- |
| Canonical decision state | Added immutable `BudgetStateSnapshot`, `BudgetDecisionIdentity`, decision snapshots, canonical ordering, and state-hash keyed decision reuse |
| Evidence policy | Added committed-evidence `PathBudgetStats`, explicit failure-level discounts, bounded repair, forced-widen, and deterministic stop reasons |
| Resource model | Added a `BigDecimal`-based calls/input/output/total/cost vector and whole-action cost estimation for all six scheduler actions |
| Action admission | Added durable action envelopes, deterministic child reservation IDs, finish-reserve protection, bucket accounting, overrun detection, and uncertain-use quarantine |
| Token admission | Added one stage-token resolver and applied the minimum of agent, provider, stage, continuation/deep tier, action, global, and finish-reserve bounds |
| Provider accounting | Bound each physical provider call to a child reservation, actual selected pricing, actual usage settlement, and conservative ambiguous-result retention |
| Gain and stop policy | Added hash-bound `CertifiedGainReceipt` values and per-target/mechanism zero-gain state; text length, model confidence, and token consumption do not count as gain |
| Desktop | Added `DesktopBudgetRuntime` and `DesktopBudgetScheduler`; reservation is persisted before ready-queue execution and terminal resume performs no provider call |
| Temporal | Added replay-safe budget state and decision identities to workflow contracts and activities |
| Recovery | Added checkpoint schema 22 snapshots for decisions, envelopes, reservations, usage, pricing, zero gain, and certified gain |
| PostgreSQL | Added Flyway V7 durable pricing, decision, envelope, reservation, usage-event, and zero-gain tables with fencing and foreign keys |

`DesktopBudgetScheduler` owns scheduler admission and `BudgetHost` is the Coordinator adapter. This
extraction was deliberate: the first inline implementation made `DesktopSolveCoordinator.class`
too large for complete SpotBugs analysis. The extracted production path brought SpotBugs back to
zero skipped classes and zero findings without adding suppressions or weakening the gate.

### Logical commits

| Commit | Purpose |
| --- | --- |
| `58e0aa9` | Canonical budget state, evidence-aware decisions, resource vectors, envelopes, gain/zero-gain policy, and Core tests |
| `1d04da6` | Physical provider reservation, strict pricing, stage token enforcement, ambiguous-result retention, and Provider tests |
| `e2050d8` | Flyway V7 persistence, fencing, exactly-once usage events, and Temporal deterministic replay |
| `794890d` | Desktop production admission, checkpoint schema 22, restore, architecture guard, and black-box tests |
| `47e453f` | Local verification record and acceptance diagnostics |
| `f731c43` | Deterministic interrupt-boundary test synchronization required by Linux CI |

### Focused verification

The final Issue 013 matrix ran against Core, Server/Provider, Temporal, and Desktop production
paths. It executed 36 tests with zero failures, zero errors, and zero skipped tests:

| Module/path | Tests | Result |
| --- | ---: | --- |
| Core state/cost/envelope/gain/crash/validation | 15 | PASS |
| Server provider accounting, strict pricing, and Temporal replay | 16 | PASS |
| Desktop production, architecture, token, and v21 migration | 5 | PASS |
| **Total** | **36** | **PASS** |

The additional `BudgetInvariantValidationTest` exercises every action-profile and stage-envelope
dimension plus receipt, hash, pricing, resource-vector, and restore fail-closed branches. Core
full regression then passed 1,408 tests. The raw Core branch coverage is
`11,524 / 15,330 = 75.172864%`, above the unchanged 75% gate.

### Diagnostic output

```text
EVIDENCE-AWARE BUDGET TOKEN STOP DIAGNOSTIC
BUDGET_STATE_SNAPSHOTS=5
STALE_DECISION_REUSES=0
STATE_HASH_DECISION_CHANGES=1
MULTIDIMENSIONAL_ADMISSION_BYPASSES=0
LATE_PROVIDER_BUDGET_REJECTIONS=0
SYNTHESIS_RESERVE_VIOLATIONS=0
ZERO_GAIN_DEEPEN_ACCEPTS=0
BOUNDED_FORCED_WIDEN_REPEATS=0
UNPRICED_PROVIDER_FAIL_OPEN_ACCEPTS=0
DUPLICATE_BUDGET_SETTLEMENTS=0
DUPLICATE_PROVIDER_CALL_CHARGES=0
AMBIGUOUS_USAGE_FAIL_OPEN_RELEASES=0
COMPLETION_ORDER_DECISION_HASH_CHANGES=0
POST_RESTORE_BUDGET_DRIFT=0
TERMINAL_RESUME_PROVIDER_CALLS=0
RESULT=PASS
```

```text
STAGE TOKEN ENVELOPE DIAGNOSTIC
ORDINARY_STAGE_LIMIT_BYPASSES=0
AGENT_MAX_TOKEN_BYPASSES=0
PROVIDER_MAX_TOKEN_BYPASSES=0
GLOBAL_REMAINING_TOKEN_BYPASSES=0
ACTION_ENVELOPE_TOKEN_BYPASSES=0
CONTINUATION_WITHOUT_CERTIFIED_GAIN=0
DEEP_EXPLORATION_TIER_LOSSES=0
SYNTHESIS_TOKEN_RESERVE_VIOLATIONS=0
JSON_REPAIR_UNBOUNDED_CALLS=0
RESULT=PASS
```

```text
BUDGET CRASH RECOVERY DIAGNOSTIC
HARD_CRASH_POINTS=10
LOST_SETTLED_USAGE=0
DUPLICATE_PHYSICAL_CALL_CHARGES=0
DUPLICATE_DECISION_EXECUTIONS=0
IN_FLIGHT_BUDGET_FAIL_OPEN_ACCEPTS=0
UNCERTAIN_PROVIDER_CALL_REPLAYS=0
POST_RESTORE_GLOBAL_USAGE_DRIFT=0
POST_RESTORE_BUCKET_USAGE_DRIFT=0
POST_RESTORE_ZERO_GAIN_STATE_LOSSES=0
POST_SECOND_RESTORE_STATE_CHANGES=0
RESULT=PASS
```

```text
BUDGET DECISION DETERMINISM DIAGNOSTIC
COMPLETION_ORDERS_EXECUTED=3
DISTINCT_COMPLETION_ORDERS=3
BUDGET_STATE_HASH_CHANGES=0
BUDGET_DECISION_HASH_CHANGES=0
SELECTED_ACTION_SET_CHANGES=0
RESOURCE_ESTIMATE_HASH_CHANGES=0
ZERO_GAIN_RESULT_CHANGES=0
DUPLICATE_PROVIDER_CALL_CHARGES=0
DUPLICATE_BUDGET_DECISIONS=0
RESULT=PASS
```

### Persistence and migration

`DesktopSolveCheckpoint.CURRENT_SCHEMA_VERSION` is 22. A v21 checkpoint receives empty immutable
budget snapshots and the configured pricing snapshot, then persists those projections on its next
v22 save. `DesktopV21BudgetMigrationTest` passed with no provider calls and no change to the root
goal or pre-existing authority projections.

Flyway V7 applied successfully on PostgreSQL 18.4 through Docker Desktop 29.6.2. The Issue 013
PostgreSQL tests passed 10/10: `BudgetPersistencePostgresIT` 1/1 and
`PersistencePostgresIT` 9/9. The migration enforces envelope/decision foreign keys, provider-call
and pricing binding, exactly-once reservation/usage identities, nonnegative resource dimensions,
and stale-writer fencing.

### Full regression and release gates

The final Windows command was `scripts/verify-all.ps1 -Offline` with the repository-locked JDK
25. It completed with `FULL VERIFICATION: PASS`.

| Module | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| Contracts | 65 | 0 | 0 | 0 |
| Core | 1,408 | 0 | 0 | 0 |
| Server, including PostgreSQL IT | 950 | 0 | 0 | 3 |
| Desktop | 336 | 0 | 0 | 1 |
| Compatibility | 149 | 0 | 0 | 0 |
| **Total** | **2,908** | **0** | **0** | **4** |

The same run passed Docker/Testcontainers, all seven Flyway migrations, SpotBugs/FindSecBugs,
dependency and security checks, secret and license policy, source immutability, compatibility,
checkpoint/restore, Temporal replay, and Python Sidecar performance. No threshold was changed.

The workstation has only the Docker Desktop WSL2 distribution and no general Linux distribution,
so Linux JDK 25 verification ran in GitHub Actions. The first remote run, `32594014620`, exposed an
existing race in `ComputationResourceGuardBoundaryTest`: its worker could return before the caller
observed a pre-existing interrupt. The test still required `COMPUTATION_INTERRUPTED`, but the
fixture did not keep the worker pending long enough to exercise that branch deterministically.
Commit `f731c43` replaced the immediate-return fixture with a cancellation-released latch without
changing production code or weakening any assertion. The test then passed 20/20 repeated local
runs and the complete 1,408-test Core regression.

GitHub Actions run
[`32594464848`](https://github.com/aililuola/Math-Agents/actions/runs/32594464848) passed both
`verify` on `ubuntu-latest` and `package-windows` on `windows-latest` for `f731c43`. The Linux job
therefore exercised the JDK 25 full verification gate that was unavailable in the local WSL setup.

### Protected authority audit

The budget work changes shared Coordinator/checkpoint/workflow files only to add a budget sidecar
and admission boundary. Full regression retained the Issue 001-012 tests for immutable Root Goal,
Permanent Negative Knowledge, Attempt/Claim separation, Research Checkpoints, canonical Proof
Graph and convergence, Semantic Pivot, Strategy Portfolio, Claim Court roles, Mathematical
Artifact Broker, reproducible computation, reconciled run state, and frozen epoch/lease/receipt
crash atomicity. No mathematical authority decision, proof receipt rule, or provider exactly-once
authority was weakened.

### Current acceptance state

Local acceptance and the remote Linux/Windows release gates are complete.

```text
CANONICAL_BUDGET_STATE=PASS
STATE_HASHED_DECISIONS=PASS
EVIDENCE_AWARE_ACTION_SELECTION=PASS
VERIFY_SYNTHESIZE_STOP_ACTIONS=PASS
MULTIDIMENSIONAL_HARD_CAPS=PASS
ACTION_ENVELOPES=PASS
FINISH_RESERVE=PASS
STRICT_PRICING_WHEN_COST_CAPPED=PASS
ACTUAL_USAGE_EXACTLY_ONCE=PASS
AMBIGUOUS_USAGE_FAIL_CLOSED=PASS
STAGE_TOKEN_ENVELOPES=PASS
CERTIFIED_GAIN_POLICY=PASS
ZERO_GAIN_STOP_POLICY=PASS
TERMINAL_RESUME_ZERO_CALLS=PASS
DESKTOP_TEMPORAL_PARITY=PASS
CRASH_RECOVERY=PASS
COMPLETION_ORDER_INVARIANCE=PASS
SCHEMA_MIGRATION=PASS
POSTGRESQL_FENCING=PASS
ISSUES_001_012_REGRESSION=PASS
PROTECTED_AUTHORITY=PASS
FULL_VERIFICATION=PASS
GITHUB_ACTIONS=PASS
ISSUE_013_STATUS=CLOSED
```
