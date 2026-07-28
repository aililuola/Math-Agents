# MathProofMesh v0.8.2 Semantic Execution Fix Map

## Preflight Baseline

- Repository: `https://github.com/aililuola/Math-Agents.git`
- Working branch:
  `feature/mathproofmesh-v0.8.0-goal-plan-failure-utility-control`
- Baseline commit: `0a3cd230bd5c7cdf6740c2a4c718f80f2d2fe7b7`
- Baseline version: `0.8.1`
- Target version: `0.8.2`
- Source Python files: `128`
- Test Python files: `134`
- Benchmark Python files: `6`
- Baseline Pytest: `515 passed, 1 warning`
- Baseline Ruff check: passed
- Baseline Ruff format check: `272 files already formatted`
- Baseline compileall: passed for `src`, `tests`, and `benchmarks`

The pre-existing edits to `BUILD_INFO.json`, `README.md`,
`docs/VALIDATION.md`, and `docs/MATHPROOFMESH_0.7_OVERVIEW.md` were reviewed
before preflight. They included output-token and budget metadata changes, so
they were not committed into this repair. They are preserved in the named Git
stash `preserve pre-v0.8.2 user documentation and budget metadata edits`.

The fetched branch, version, and source layout match the implementation
specification. No replacement branch or parallel control layer is required.

## Existing Authority Map

| Required object | Actual implementation | Current role and v0.8.2 mapping |
| --- | --- | --- |
| `StrategyCard`, `StrategySet` | `src/mathproofmesh/schemas.py` | Existing Planner contract. Keep its mathematical hash and compatibility surface stable; archive cards and attach blueprint/lineage through proof-control sidecars. |
| `RouteAdmissionGate` | `src/mathproofmesh/proof_control/gates.py` | Existing admission authority. It currently receives a target produced by `register_strategy`; v0.8.2 must make that target come from a validated blueprint compiled first. |
| `RouteTargetBinding` | `src/mathproofmesh/proof_control/models.py`; selection in `route_target.py` | Existing sidecar target binding. Extend its provenance to a blueprint and forbid an unreviewed main-goal copy as the only bridge. |
| `GoalAlignmentAnalyzer` | `src/mathproofmesh/proof_control/goal_alignment.py` | Reuse after blueprint direct-target selection. It must not compare an uncompiled domain strategy directly with the main goal. |
| `MinimalSufficiencyAnalyzer` | `src/mathproofmesh/proof_control/goal_alignment.py` | Reuse for nontrivial direct-target and bridge checks in rewrite semantic validation. |
| `BlueprintRewriteRequest` | `src/mathproofmesh/proof_control/models.py` | Existing request record. Extend through lineage and semantic-assessment sidecars rather than replacing it. |
| `ControlActionDispatcher` | `src/mathproofmesh/proof_control/action_dispatcher.py` | Existing idempotent action authority. All new rewrite, task, pivot, wake, route-state, and intervention mutations must pass through it. |
| `ProofGraphStore` | `src/mathproofmesh/proof_graph/store.py` | Existing mathematical graph authority. Add a proof-control pre-add semantic policy analogous to its pre-close policy; keep graph hashes unchanged. |
| `ProofObligation` | `src/mathproofmesh/schemas.py` | Existing mathematical object. Preserve its content-hash payload; domain, semantic quality, blueprint provenance, and quarantine stay in sidecar state. |
| `ClaimVerificationLedger` | `ClaimVerificationLedgerEntry` in `proof_control/models.py`; controller in `claim_lifecycle.py` | The real implementation is entry-based, not a separate ledger class. Extend entries with typed dependency and referee provenance. |
| `ClaimLifecycleController` | `src/mathproofmesh/proof_control/claim_lifecycle.py` | Existing transition authority. Add claim-level referee disposition application and require a referee record before `FACT_CANDIDATE`. |
| Route Team / Route Referee result | `RouteTeam`, `RouteTeamResult` in `src/mathproofmesh/teams/route_team.py` | Existing result is artifact-level and accepts `BrokerDecision` or `VerificationReport`. Add claim dispositions without treating an overall pass as per-claim acceptance. |
| `MessageEnvelope`, `MessageReceipt` | `src/mathproofmesh/schemas.py` | Existing immutable message and receipt contracts. Add typed dependency sidecars without changing `immutable_payload`; keep receipt/liveness semantics distinct. |
| `ComputationGate` | `src/mathproofmesh/computation/policy.py` | Existing typed-computation gate. Reuse for compiled finite falsification contracts; never execute free text as Python. |
| `ComputationTask` | Actual types are `ExperimentSpec`, `ComputationPlan`, and `DeferredExperimentRequest` in `schemas.py`, executed by `computation/broker.py` | Do not create a competing computation authority. `ExecutableTaskRecord` points to these contracts or to an explicit Counterexample Hunter assignment. |
| `InferenceRiskScanner` | `src/mathproofmesh/proof_control/inference_risk.py` | Existing deterministic scope/property scanner. Add structured verifier-issue mapping and critical-step unknown-risk handling here. |
| `BottleneckCompressor` | `src/mathproofmesh/proof_control/bottleneck.py` | Existing cluster authority. Filter by mathematical domain and accepted semantic quality before clustering. |
| `CriticalAssumptionMatrix` | `src/mathproofmesh/proof_control/common_mode.py` | Existing family and challenger logic. Tighten source-first domain filtering and semantic family keys. |
| `MessageUtilityController` | `src/mathproofmesh/proof_control/message_utility.py` | Existing utility contract/receipt authority. Add explicit broadcast admission and stable keep-local decisions. |
| `RouteRegistry` | `src/mathproofmesh/communication/route_registry.py` | Existing route identity/status authority. Extend its compatible state machine with WAITING, FROZEN, wake, and intervention records. |
| `MetaPivotState` | `src/mathproofmesh/proof_control/models.py`; execution in `controller.py` and `orchestrator.py` | Existing request/execution state. Add material outcome and mechanism-attempt records; no state effect means no `EXECUTED`. |
| Resume / stall recovery / route freezing | `MathProofMesh.resume` and `_restore_state_from_checkpoint` in `orchestrator.py`; `resume_phase.py`; `stall_recovery.py`; `RouteRegistry.mark_no_progress/mark_stalled` | Add a deterministic resume decision before runner/provider work and separate auto-wakeable waiting from intervention-only freezing. |

## Current Control Flow and Defects

The current initial-route call chain is:

```text
Planner StrategySet
-> optional deterministic generic fallback merge
-> ProofControlLayer.admit_routes
-> ProofControlLayer.register_strategy
-> choose_nearest_target_obligation
-> optional single generated subgoal
-> GoalAlignmentAnalyzer
-> RouteAdmissionGate
-> RouteRegistry.register_route in the orchestrator
```

This is the confirmed WP1 defect: there is no durable multi-node
`StrategyBlueprint` before target selection and admission. The current rewrite
handler can also synthesize a textual implication whose source and target are
the same main goal and count the resulting obligation as an execution effect.

The v0.8.2 target call chain is:

```text
Planner StrategySet
-> archive every original StrategyCard
-> StrategyBlueprintCompiler
-> BlueprintSemanticGate
-> tentative non-main obligations and directed edges
-> direct-target binding
-> GoalAlignmentAnalyzer / RouteAdmissionGate
-> promote accepted tentative nodes to open
-> RouteRegistry.register_route
```

Rejected blueprint nodes remain sidecar audit evidence and never enter core
proof debt.

## Work-Package Map

| WP | Existing class/function and actual file | Partial implementation | Planned modification | Compatibility risk |
| ---: | --- | --- | --- | --- |
| 1 Strategy Blueprint before Admission | `ProofControlLayer.register_strategy/admit_routes` in `proof_control/controller.py`; `choose_nearest_target_obligation` in `route_target.py`; `ProofGraphStore.add_obligation/add_edge` | A single bottleneck subgoal may be created before admission, but no compiled DAG, semantic validation, durable provenance, or failure quarantine exists. | Add blueprint models/compiler/gate inside `proof_control`; archive first, compile `L1 -> L2 -> G`, add accepted candidates as tentative, bind direct target, then run admission. | Graph duplicate collapsing and active/shadow behavior must remain stable. Failed nodes must not affect debt or hashes. |
| 2 Nontrivial Rewrite and Lineage | `_handle_rewrite_blueprint`, `_blueprint_rewrite_postcondition`, `_ensure_route_admission_rewrite` in `proof_control/controller.py` | Dispatcher and rewrite request exist, but the fallback bridge can be a template or self-implication; no revised `StrategyCard` or lineage is required. | Add lineage, revised-result, and rewrite-assessment models plus `RewriteSemanticGate`; execute only after a child strategy, non-self edge, concrete first step, mechanism preservation, and re-admission result exist. | Preserve old requests and action IDs on restore. Never delete or supersede the parent merely because a fallback exists. |
| 3 Typed Dependency Namespace | String dependencies in `ProofStep`, `ClaimCard`, `MessageEnvelope`, and `ProofObligation` in `schemas.py`; resolution in `memory.py` and `communication/broker.py` | Dependencies are untyped and broker resolution treats them primarily as global memory IDs. | Add compatible `dependency_refs`, deterministic migrator, and `DependencyResolver` in `proof_control`; resolve local step/claim closure before global Fact lookup; ambiguous legacy IDs create normalization review. | New fields must be excluded from legacy mathematical/checkpoint hashes when empty. Old unprefixed IDs cannot be auto-invalidated. |
| 4 Route Referee to Claim Ledger | `RouteTeamResult` in `teams/route_team.py`; `ClaimLifecycleController` in `proof_control/claim_lifecycle.py` | Overall referee pass controls global sharing, but no per-Claim disposition reaches the ledger. `promote_fact_candidate` can currently proceed without a referee review ID. | Add claim referee records/dispositions, deterministic mapping for explicit accepted claim IDs, ledger application, and exactly-once review provenance. | Overall legacy pass must defer unmapped claims rather than accept them all. Existing Fact authority remains the Broker. |
| 5 Executable Countermodel/Falsification | `CountermodelTaskRecord` and `FalsificationTaskRecord` in `proof_control/models.py`; materialization in `falsification.py` and `controller.py`; typed execution in `computation/broker.py` | Tasks can remain pending/deferred with free text, no executor, no wake condition, and no terminal reason. | Add typed contract compiler, common executable-task state, wake conditions, expiry, deterministic handler or Counterexample Hunter assignment, and result semantics. | No free-text Python execution. Bounded non-refutation remains evidence only and never verifies a universal claim. |
| 6 Effective Meta Pivot | `MetaPivotState`; `ProofControlLayer.request_meta_pivot/evaluate_meta_pivot`; `MathProofMesh._execute_pending_meta_pivot` | State records created routes/facts/obligations, but execution may be marked successful before a material effect and mechanism fall-through is incomplete. | Add `MetaPivotOutcome`, ordered mechanism attempts, unavailability reasons, and postcondition requiring a new route/strategy/math obligation/executable task/fact/counterexample/material route change. | Preserve exactly-once action restore and pending-pivot stop protection. Empty/deferred outcomes must remain auditable. |
| 7 Verifier Issue to Inference Risk | `VerificationIssue`/`VerificationReport` in `schemas.py`; `InferenceRiskScanner` and `ProofControlLayer.process_verification_report` | Failed reports classify plan failure but do not deterministically turn each semantic issue into a risk record. | Add structured issue sidecars and issue-code mapper; scan central or promotion-bound claims; open ambiguous risk for unknown high-centrality scope; block Fact promotion until cleared. | Legacy free-text issues need conservative mapping without theorem-specific words or silent false certainty. |
| 8 Obligation Semantic Quality | `ProofGraphStore.add_obligation`; `ProofControlLayer.register_obligation`; domain classification in `proof_control/domains.py` | Nonempty canonical text is enough for graph insertion. Placeholder/action/self-implication/main-goal-copy obligations may enter clustering and debt. | Add sidecar quality assessment and pre-add policy. Rejected mathematical candidates become SEARCH/PROCESS tasks or quarantine records, not graph nodes. | Main-goal creation remains allowed. Restore must quarantine legacy invalid bridges without deleting history. |
| 9 Strict Domain Separation | `AssumptionDomain`, `ObligationDomain`, classifiers in `domains.py`; filtering in `controller.py`, `bottleneck.py`, `common_mode.py` | Partial mathematical/process/tool/verification separation exists, but SEARCH/PROTOCOL/SAFETY obligation domains are absent and fallback text classification can pollute core debt. | Extend domains; use creation source first; restrict target binding, core debt, bottleneck, common mode, and synthesis readiness to mathematical quality-passed objects. | Missing old domain state must migrate to conservative sidecar records without changing graph content. |
| 10 Zero-Utility Broadcast Gate | `MessageUtilityController`; `ProofControlLayer._message_gate`; `MessageBroker.publish` | A cross-route contract can be created with expected reduction `0.0`; its existence is enough to continue broadcasting. | Add `BroadcastDecision` and a Broker cross-route policy hook. Normal zero-utility messages are accepted locally with an audited keep-local decision; critical/high/refute/close exceptions remain broadcastable. | KEEP_LOCAL must not be reported as rejected mathematical content or consume neighbor delivery quota. Decisions must restore stably. |
| 11 Route WAITING/FROZEN and Wake | `RouteStatus`/`RouteDescriptor` in `schemas.py`; status methods in `communication/route_registry.py` | COOLING can auto-reactivate, while stalled routes become `FROZEN_STALLED`; task wakeability is not represented. | Add compatible WAITING/FROZEN/TERMINAL semantics, wake scheduler, and freeze records. Deferred task plus automatic condition means WAITING, never intervention-only FROZEN. | Old `FROZEN_STALLED` checkpoints remain readable and receive explicit migration semantics. Sparse-neighbor scheduling must exclude waiting/frozen routes. |
| 12 Terminal Resume Policy | `MathProofMesh.resume` in `orchestrator.py`; `_restore_state_from_checkpoint`; `resume_phase.py` | Completed runs no-op, but hard-stopped inconclusive runs can enter resumed rounds and provider work without first proving resumable work exists. | Add deterministic `ResumePlanner` and decision sidecar before runner/provider work. Same terminal stagnation state with no pending/wakeable work returns existing progress with zero calls; explicit intervention creates an idempotent action. | Preserve the old CLI/API default signature and early/mid-run resume behavior. Config/goal/state changes must still permit work. |
| 13 Domain Strategy Preservation | Planner selection and `_fallback_strategy_set` in `orchestrator.py`; no original archive currently exists | Generic templates are merged before proof-control admission and may displace domain strategies during selection/regeneration. | Archive Planner strategies before selection/admission; add parent/root lineage; treat fallback only as a child/parallel candidate requiring blueprint and semantic gates; never mutate/delete the original. | Legacy checkpoints may lack raw Planner cards. Recover only when evidence exists; do not manufacture originals or auto-select legacy fallback. |

## Model and Module Placement

New production logic remains under the existing independent
`src/mathproofmesh/proof_control` package:

- `strategy_blueprint.py`: compilation, validation, rewrite semantics, archive,
  and lineage operations.
- `dependencies.py`: typed dependency migration and scoped resolution.
- `semantic_quality.py`: obligation quality and graph pre-add decisions.
- `tasks.py`: common executable-task, wake-condition, and route-wake scheduler.
- `resume_policy.py`: deterministic terminal resume decision.

Shared wire schemas may receive optional fields in `schemas.py` where the
existing objects already live. Those fields are compatibility projections
only; proof-control state remains the authority and the old content-hash
payloads stay unchanged.

Existing modules to integrate rather than duplicate:

- `proof_control/models.py`, `state.py`, `controller.py`, `gates.py`,
  `goal_alignment.py`, `falsification.py`, `inference_risk.py`, `domains.py`,
  `bottleneck.py`, `common_mode.py`, `message_utility.py`, and `__init__.py`
- `proof_graph/store.py`
- `communication/broker.py` and `communication/route_registry.py`
- `teams/route_team.py`
- `memory.py`, `orchestrator.py`, `resume_phase.py`, `prompts.py`, and
  `report.py`

## Sidecar and Hash Invariants

- `StrategyBlueprint`, lineage, archive, rewrite assessment, typed dependency
  normalization, referee records, executable tasks, wake conditions, pivot
  outcomes, obligation quality, resume decisions, broadcast decisions, and
  freeze records are persisted in `ProofControlState`.
- Existing content-hash payloads for `StrategyCard`, `ClaimCard`,
  `MessageEnvelope`, `ProofObligation`, `ProofStep` checkpoint material, and
  graph nodes do not gain proof-control fields.
- `ProofControlLayer` continues to have no direct Fact writer and no direct
  obligation-closing authority. It may request work through the Dispatcher and
  existing Broker/Graph adapters only.
- Off mode keeps legacy runtime behavior and writes no authoritative control
  state. Shadow mode records decisions without blocking or mutating the
  mathematical authorities. Active mode enforces admitted Dispatcher actions
  and semantic gates.

## Checkpoint Migration

`ProofControlState.schema_version` will advance to `0.8.2`.

- Missing v0.8.2 maps/lists initialize empty and emit one
  `checkpoint_migrated_to_v0_8_2` event.
- Old untyped dependencies migrate by explicit prefix, then current local
  delta/attempt scope, then Broker Fact identity. Unresolved values become an
  ambiguous normalization task and do not auto-invalidate a Claim.
- Old self-implication or placeholder bridges remain in historical graph
  state but receive semantic quarantine and are excluded from core debt.
- Old generic fallbacks remain child candidates and are not auto-selected.
- Executed Dispatcher actions, referee reviews, task transitions, pivots,
  wakes, and intervention records restore exactly once by stable IDs.

## Phase Boundaries

| Phase | Scope | Required commit boundary |
| --- | --- | --- |
| 0 | This map plus failing regression tests for all 13 work packages | `docs: map v0.8.2 semantic execution repair points`; `test: reproduce blueprint rewrite dependency task pivot and resume failures` |
| 1 | WP1-WP2 blueprint, rewrite semantics, lineage | Separate implementation commit after focused tests |
| 2 | WP3-WP4 dependency namespaces and referee/ledger propagation | Separate implementation commit after focused tests |
| 3 | WP5-WP7 executable tasks, effective pivot, verifier risks | Separate implementation commit after focused tests |
| 4 | WP8-WP10 obligation quality/domain and broadcast admission | Separate implementation commit after focused tests |
| 5 | WP11-WP13 wakeable routes, terminal resume, strategy archive | Separate implementation commit after focused tests |
| 6 | Migration, E2E, release documentation, and version `0.8.2` | Final release commit after full validation |

No phase changes token limits, segment lengths, Deep Exploration, SSE, agent
counts, or budget defaults. Tests use deterministic Mock providers only.
