# v0.8 Common-Mode Execution Closure Map

## Scope

This change closes two generic control failures:

1. Routes that share the same unresolved, load-bearing dependency are not counted
   as independent merely because their agents, titles, wording, or local mechanisms
   differ.
2. A detected common-mode family produces durable executable work and is scheduled
   through the idempotent control dispatcher; it cannot remain only a sidecar
   `AssumptionChallengerTask` record.

Baseline:

- Branch: `feature/mathproofmesh-v0.8.0-goal-plan-failure-utility-control`
- Starting commit: `b31d8fd970f6c8e42795083c82b24312b28bc3d6`
- Package version: `0.8.2`

The implementation is problem-independent. Production logic contains no checks for
the current problem, its hash, or problem-specific symbols and mathematical topics.

## Source Map

| Control responsibility | Implementation |
| --- | --- |
| Sidecar dependency schema | `proof_control/models.py`: `DependencyAtom`, extended `CriticalAssumption` and `AssumptionFamily` |
| Challenge action/result contracts | `proof_control/models.py`: `AssumptionChallengeAction`, `AssumptionChallengeProposal`, `AssumptionChallengeReview`, `AssumptionChallengeResult` |
| Checkpoint persistence | `proof_control/state.py`: dependency atoms, challenger tasks, and challenge results |
| Multi-source dependency extraction | `proof_control/common_mode.py`: `CriticalAssumptionMatrix.build` |
| Typed dependency and scope-aware family grouping | `proof_control/common_mode.py`: `_same_assumption_family`, `_build_families` |
| Candidate dependency-family matching | `proof_control/common_mode.py`: `matching_family_ids`, `strategy_is_independent` |
| Route admission protection | `proof_control/gates.py`: `_risky_common_dependencies`; `proof_control/controller.py`: `admit_routes` |
| Durable executable task creation | `proof_control/tasks.py`: `create_assumption_challenger_task` |
| Idempotent materialization and execution | `proof_control/controller.py`: `_handle_create_assumption_challenger`, `_handle_execute_assumption_challenger` |
| Resolution reconciliation after resume | `proof_control/controller.py`: `_apply_assumption_challenge_resolution` |
| Scheduler priority and independent review | `orchestrator.py`: `_execute_pending_assumption_challengers` |
| Structured challenge/review contracts | `prompts.py`: `challenge_critical_assumption`, `review_critical_assumption_challenge` |
| Stop/deepening liveness gates | `proof_control/controller.py`: `common_mode_blocks_stagnation_stop`, `allow_deepening`; `orchestrator.py`: `_global_stagnation_should_stop` |

## Dependency Evidence

`CriticalAssumptionMatrix` extracts auditable sidecar evidence from:

- strategy prerequisites and critical claims;
- typed proof-step dependencies and resolvable legacy dependency IDs;
- explicit assumptions in key-step justifications;
- attempt unresolved gaps;
- message assumptions and verified facts;
- proof obligations and their graph neighborhoods;
- critical/error verifier premise summaries.

Each `DependencyAtom` records the source and route, normalized statement hash,
typed dependency IDs, scope signature, proof-graph neighborhood, mechanism
signature, verification status, and load-bearing score. Existing mathematical
objects and their content hashes are not modified.

Typed obligation references are expanded through the existing proof-graph
dependency closure with cycle protection. Thus two routes with different immediate
bridges still expose a shared unresolved predecessor.

Family grouping uses typed dependency identity first. Distinct nonempty typed
dependency sets are not merged. When typed identity is unavailable, grouping is
conservative and considers normalized content, compatible scope, graph-neighborhood
overlap, and semantic tags.

## Execution Lifecycle

In active mode, a risky family follows this path:

```text
DETECTED
  -> CREATE_ASSUMPTION_CHALLENGER dispatcher action
  -> durable AssumptionChallengerTask
  -> READY ExecutableTaskRecord
  -> EXECUTE_ASSUMPTION_CHALLENGER dispatcher action
  -> RUNNING
  -> VERIFIED | REFUTED | AVOIDED | INCONCLUSIVE | BLOCKED
```

Stable identities make both materialization and execution exactly-once across
checkpoint/resume. Shadow mode records the proposed creation action but creates no
runtime task. Off mode retains the existing behavior.

The orchestrator reserves the challenger a real scheduling opportunity before
meta-pivot and inspiration work. It selects a challenger independent of the
affected route authors and a distinct reviewer. The result is accepted only as:

- `VERIFIED`: explicit proof steps pass independent review;
- `REFUTED`: an exact counterexample is independently confirmed;
- `AVOIDED`: a newly admitted and registered route has a different mechanism and
  does not match the challenged dependency family;
- `INCONCLUSIVE`: the artifacts do not establish one of the outcomes above;
- `BLOCKED`: execution resources or an independent reviewer are unavailable.

A resolved outcome requires both challenger evidence and independent review
evidence. `AVOIDED` additionally requires a materialized route. `VERIFIED` resolves
only proof-control sidecar risk so work may continue; it does not create a Fact or
close an obligation. Normal verification and synthesis gates remain authoritative.

## Focused Regression Coverage

`tests/test_common_mode_execution_closure.py` verifies:

1. Different route mechanisms sharing one typed dependency form one cut-set family.
2. Similar mathematical themes with distinct typed dependency closures do not merge.
3. Critical verifier premise summaries enter common-mode detection.
4. Distinct direct bridges with a shared transitive proof-graph predecessor are
   recognized as one load-bearing common-mode family.
5. Challenger execution is exactly-once across checkpoint/resume.
6. Pending executable challenger work prevents premature hard stop.
7. An unreviewed claimed resolution is rejected and cannot alter Facts or the goal.
8. A reworded shared dependency is rejected as a supposedly independent route.
9. The orchestrator runs a challenger and distinct reviewer without a real API,
   preserves Facts and the main goal, resolves verified sidecar risk, and preserves
   that resolution after resume.
10. Shadow mode observes the finding without materializing executable work.

All test agents and artifacts are deterministic fakes. No Provider API is invoked.

## Preserved Constraints

- No changes to token limits, segment lengths, Deep Exploration tiers, agent counts,
  or budget defaults.
- No changes to DeepSeek SSE.
- No Fact creation and no obligation closure by `ProofControlLayer`.
- No legacy fallback, generic fallback strategy, swallowed failure, or deleted
  evidence.
- No complete MCTS, Lean integration, or orchestrator decomposition.

## Validation

Offline validation on 2026-07-26:

- Full Pytest suite: `730 passed` (one pre-existing Starlette/httpx deprecation
  warning).
- Focused Common-Mode suite: `10 passed`.
- Ruff: all checks passed; all 309 files formatted.
- `compileall`: passed for `src`, `tests`, and `benchmarks`.
- Proof-control mock benchmark: 14/14 contracts, 0 Provider calls.
- Topology mock benchmark: 21/21 contracts, 0 Provider calls.
