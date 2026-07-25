# MathProofMesh v0.8 Proof-Control Implementation Map

## 1. Baseline

- Baseline branch: `feature/mathproofmesh-v0.7.0-hierarchical-sparse-topology`
- Feature branch: `feature/mathproofmesh-v0.8.0-goal-plan-failure-utility-control`
- Baseline commit: `866e7270a01babb93342d272b99865b449e3b934`
- Remote comparison after `git fetch` and `git pull --ff-only`: `HEAD` and
  `origin/feature/mathproofmesh-v0.7.0-hierarchical-sparse-topology` are equal.
- Baseline test command: `.\.venv\Scripts\python.exe -m pytest -q`
- Baseline test result: `360 passed, 1 warning in 29.70s`
- The warning is the existing Starlette `httpx` deprecation warning.
- No real provider call is used by this implementation or its validation.

The worktree already contained user changes in `BUILD_INFO.json`, `README.md`,
`docs/VALIDATION.md`, and the untracked
`docs/MATHPROOFMESH_0.7_OVERVIEW.md`. They are not part of Phase 0 and must not
be reverted or accidentally staged.

## 2. Non-Negotiable Boundaries

- The proof-control root config is disabled and `mode: off` by default.
- All mathematical control metadata is sidecar state under
  `src/mathproofmesh/proof_control/`.
- `MessageEnvelope`, `ClaimCard`, `ProofObligation`, and `ProofStep` hash
  payloads remain unchanged.
- `ProofControlLayer` never calls `TypedMemory.add_fact`, any Fact promotion
  method, or `ProofGraphStore.close_obligation`.
- The layer may return an auditable permission decision; the existing
  orchestrator, Broker, Typed Memory, or Proof Graph remains the authority that
  performs a mutation.
- Shadow mode records recommendations but cannot alter route selection,
  scheduling, obligation closure, deepening, or synthesis.
- Active mode requires hierarchical sparse topology, active Proof Graph, Typed
  Memory, and Typed Communication.
- Existing Broker, Route Team, Proof Graph, Typed Memory, Inspiration, SSE,
  Deep Exploration registry, and checkpoint/resume paths are extended rather
  than replaced.
- Existing YAML and v0.7 checkpoints remain loadable. A missing
  `proof_control` payload creates empty state.
- No API key, `.env`, `runs/`, cache, `build/`, `dist/`, or provider output is
  staged.
- No MCTS, full Lean integration, Orchestrator decomposition, or output-token
  policy work is in scope.
- The following fields and all YAML values that populate them are frozen:
  `AgentConfig.max_output_tokens`,
  `AgentConfig.provider_max_output_tokens`,
  `ContinuationConfig.max_output_tokens_per_segment`,
  `ContinuationConfig.segments_per_explore_call`,
  `DeepExplorationPolicyConfig.tiers`,
  `DeepExplorationPolicyConfig.high_tier_threshold_tokens`, and
  `partial_repair_max_output_tokens`.

## 3. Work-Package Mapping

| WP | Capability | Existing v0.7 integration points | New implementation | Existing files to modify | Model call | Scheduling | Fact gate |
|---:|---|---|---|---|---|---|---|
| 1 | Claim-Goal Alignment Gate | `ProblemContract`, `StrategyCard.critical_claims`, `ProofGraphStore`, `PromptFactory.strategies()` | `proof_control/goal_alignment.py`: `GoalAlignmentAnalyzer` | `prompts.py`, `orchestrator.py` | Only batched deterministic `UNKNOWN` review through the existing runner | Route admission signal | May block promotion/closure only through a returned decision |
| 2 | Minimal Sufficiency / Target Weakening | `ActionKind.REVERSE_GOAL`, `ActionKind.BRIDGE`, Meta directives | `MinimalSufficiencyAnalyzer` in `goal_alignment.py` | `prompts.py`, `orchestrator.py` | Ambiguous bridge proposal only | Produces a reviewed rewrite/bridge request | Candidate begins as control metadata, never Fact |
| 3 | Proof Role Classification | `ClaimCard`, `MessageEnvelope`, graph dependencies | `proof_control/proof_roles.py`: `ProofRoleClassifier` | `report.py`, `orchestrator.py` | No | Adds role signals only | Sidecar only |
| 4 | Core Proof Debt | `ProofGraphStore.proof_debt()`, `PathStats`, `AdaptiveBudgetManager` | Core-closure queries and `core_proof_debt()` | `proof_graph/store.py`, `schemas.py`, `config.py`, `budget.py`, `orchestrator.py` | No | Active-only scoring; shadow records | Read-only |
| 5 | Inference Risk Taxonomy | `EvidenceType`, `MessageEnvelope` scope, verifier reports | `proof_control/inference_risk.py`: `InferenceRiskScanner` | `prompts.py`, `orchestrator.py` | Ambiguous risk review only | Creates bridge/falsification signals | Open risk can deny promotion/closure in active mode |
| 6 | Scope Signature and Guard | Message quantifiers/bindings/scope limitations, Claim scope limitations | `proof_control/scope_guard.py`: `ScopeGuard` | `proof_graph/store.py`, `orchestrator.py` | Ambiguous normalization only through runner | No direct score | Defensive pre-close policy preserves existing Fact gate |
| 7 | Abstract Structure / Realizer Separation | Inspiration construction proposals, verification first error | `proof_control/realizer.py`: `AbstractRealizerController` | `prompts.py`, `orchestrator.py`, Inspiration context adapter | Ambiguous extraction only | Repair signal | Candidate failure cannot demote abstract structure to Negative Fact |
| 8 | Realizer Repair Operators | `ActionKind.REVISE`, `ActionKind.DEEPEN`, Negative Memory | Four bounded repair operators in `realizer.py` | `prompts.py`, `orchestrator.py` | One reviewed candidate within configured cap | Existing action kinds only | Repair result is not Fact |
| 9 | Induction / Descent Selector | Proof steps, unresolved gaps, Near-Miss hints | `proof_control/induction.py`: `InductionMeasureSelector` | `prompts.py`, `orchestrator.py` | Ambiguous candidate selection only | Blueprint/obligation signal | Proposal is not Fact |
| 10 | Failure Classification and Blueprint Rewrite | Legacy `FailureLevel`, Meta review, route `requires_revision` | `proof_control/failure_control.py` | `prompts.py`, `orchestrator.py` | Ambiguous classification/rewrite only | Maps to existing `ActionKind` values | Preserves verified artifacts; no Fact writes |
| 11 | Bottleneck Compression | Exact duplicate index/aliases, `find_shared_bottlenecks()` | `proof_control/bottleneck.py`: semantic sidecar clusters | `proof_graph/store.py`, `orchestrator.py` | Ambiguous clusters only | Canonical cluster signals; original nodes remain | Read-only |
| 12 | Critical Assumption / Common Mode | Strategy prerequisites/critical claims, messages, open dependencies, duplicate-route detector | `proof_control/common_mode.py` | `orchestrator.py`, Inspiration context adapter | Challenger task can use existing roles via runner | Adds common-mode penalty/challenge | Votes never raise evidence tier |
| 13 | Message Utility Contract / Usage Receipt | `MessageReceipt`, `MessageBroker.record_utility()`, `broker_phase.record_verified_message_usage()` | `proof_control/message_utility.py` | `communication/broker.py`, `broker_phase.py`, `orchestrator.py` | No self-reported use | Active contract admission and utility signal | Trusted local verification creates usage receipt |
| 14 | NearMissLedger | Verification reports, ProofDelta, route review, post-failure diagnostic | `proof_control/near_miss.py` | `prompts.py`, `route_pipeline.py`, `orchestrator.py` | Ambiguous extraction only | Bounded non-authoritative route hint | Never enters `fact_inbox` |
| 15 | Falsification Fast Lane | `ComputationGate`, `ComputationContext`, typed computation Broker | `proof_control/falsification.py` plus strict policy predicate | `computation/policy.py`, `orchestrator.py` | No meta-review for eligible exact typed task | Reuses existing computation budget | Counterexample may enter Negative Memory through existing authority; no-result remains bounded Insight |
| 16 | Route Admission Gate | Strategy generation, duplicate detector, route registry, critical claims | `proof_control/gates.py`: `RouteAdmissionGate` | `orchestrator.py`, `prompts.py` | Ambiguous batch review / one regeneration | Shadow records; active filters/rewrite once | No |
| 17 | Continue and Synthesis Gates / Controller | Deep Exploration registry, scheduler, `_explore_path_segmented()`, `_synthesize()`, checkpoint/resume | `proof_control/gates.py`, `proof_control/controller.py`, `proof_control/state.py` | `orchestrator.py`, `resume_phase.py`, `report.py` | Controller never invokes a provider directly | Active blocks repeated no-progress deepen and premature synthesis | Read-only gate decisions |

## 4. New Package and Artifacts

Planned package:

```text
src/mathproofmesh/proof_control/
  __init__.py
  models.py
  state.py
  controller.py
  goal_alignment.py
  proof_roles.py
  scope_guard.py
  inference_risk.py
  realizer.py
  induction.py
  failure_control.py
  bottleneck.py
  common_mode.py
  message_utility.py
  near_miss.py
  falsification.py
  gates.py
```

Planned configuration:

```text
config.deepseek-v4-pro.proof-control-shadow.yaml
config.deepseek-v4-pro.proof-control-active.yaml
```

Planned documentation and benchmark assets:

```text
docs/GOAL_PLAN_FAILURE_UTILITY_CONTROL.md
docs/PROOF_SCOPE_AND_INFERENCE_RISKS.md
docs/NEAR_MISS_AND_REALIZER_REPAIR.md
docs/PROOF_CONTROL_MIGRATION_0.8.md
benchmarks/proof_control/
```

## 5. Precise Runtime Order

1. Create or restore the v0.7 hierarchical runtime.
2. When enabled, attach `ProofControlLayer` to the already-created Proof Graph,
   Typed Memory, Broker, and Route Registry.
3. Ensure the immutable problem goal has one sidecar-linked main-goal
   obligation without modifying `ProblemContract`.
4. Register strategies and evaluate route admission before explorer
   assignment.
5. Add bounded `NON-AUTHORITATIVE CONTROL HINTS` to route context.
6. Before verification: extract scope, scan inference risk, identify
   abstract/realizer structure, detect induction triggers, link to goal, and
   classify proof role.
7. After verification: classify failure, record Near-Miss, update realizer
   status, reconcile risks and trusted message use, then update core debt.
8. At each round end: compress bottlenecks, rebuild critical assumptions, and
   expose proof-control graph signals before adaptive scheduling.
9. Before active `DEEPEN`: evaluate Continue Gate; the existing Deep
   Exploration registry continues to own leases and counts.
10. Before active synthesis: evaluate Readiness Gate; Final Blind Review is
    unchanged if synthesis is admitted.
11. Persist sidecar state in schema `0.8`; restore schema `0.7` with empty
    proof-control state and a migration event.

## 6. Hash and Checkpoint Risk Register

| Risk | Mitigation | Regression test |
|---|---|---|
| Sidecar fields alter mathematical content hashes | Do not add fields to the four hash payload/model definitions; snapshot hashes before/after sidecar registration | `test_no_proof_control_hash_regression.py` |
| Control code bypasses Fact gate | No Fact mutation APIs in package; AST scan and existing Broker/Typed Memory authority remain | `test_no_proof_control_fact_bypass.py` |
| Scope guard accidentally weakens existing closure gate | Optional defensive hook defaults to allow; active control can only add a denial before existing verified-Fact checks | `test_scope_guard.py` |
| Shadow changes behavior | Every gate normalizes a would-block result to `SHADOW_BLOCK` while returning runtime allow | Gate and E2E tests |
| v0.7 checkpoint lacks sidecar payload | Optional `proof_control_state`; empty initialization plus migration event | `test_proof_control_resume.py` |
| Resume duplicates receipts/events | Stable dictionary keys, sorted export, idempotent registration, and exactly-once tests | Resume/E2E tests |
| Cluster compression deletes graph data | Clusters store member IDs only; graph nodes and edges are untouched | `test_bottleneck_compression.py` |
| Common agreement becomes evidence | Common-mode matrix reads verification status and never mutates it | `test_common_mode_assumption.py` |
| Fast lane treats no counterexample as proof | Result semantics preserve bounded evidence/Insight unless an exact counterexample is found | `test_falsification_fast_lane.py` |
| 13.1 token settings drift | Snapshot every prohibited field across baseline, shadow, and active configs | `test_proof_control_does_not_change_reasoning_token_limits.py` |

## 7. Phase and Commit Plan

| Phase | Scope | Required focused validation | Commit subject |
|---:|---|---|---|
| 0 | Baseline and this map | Baseline full pytest | `docs: map v0.7 proof-control integration points` |
| 1 | Config, sidecar models/state, default off | Config/model/state/hash tests | `feat: add proof-control configuration and sidecar state` |
| 2 | Goal, minimal sufficiency, role, scope, risk; shadow | Goal/scope/risk tests | `feat: add goal alignment scope and inference-risk analysis` |
| 3 | Core debt, graph queries, compression, common mode | Graph/debt/cluster/common-mode tests | `feat: add core proof debt and bottleneck compression` |
| 4 | Abstract/realizer, repair, induction, Near-Miss | Repair/induction/Near-Miss tests | `feat: add structure-preserving repair and near-miss memory` |
| 5 | Failure, blueprint, message utility | Failure/utility tests | `feat: add failure-aware blueprint control and message utility contracts` |
| 6 | Fast lane and three gates in shadow | Computation and gate tests | `feat: add proof-control admission continuation and synthesis gates` |
| 7 | Active orchestrator wiring and resume | Resume, active E2E, legacy/off E2E | `feat: activate goal-plan-failure-utility control with checkpoint resume` |
| 8 | Logic traps, benchmark, docs, release/version | Full pytest, Ruff, format, compileall, both mock benchmarks | `test: add proof-control logic-trap and end-to-end suite`; `docs: release MathProofMesh v0.8 proof control layer` |
