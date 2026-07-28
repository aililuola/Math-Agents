# MathProofMesh v0.8.1 Semantic Control Fix Map

## Baseline

- Repository: `https://github.com/aililuola/Math-Agents.git`
- Working branch:
  `feature/mathproofmesh-v0.8.0-goal-plan-failure-utility-control`
- Baseline commit: `eea9c0572498c4649d68290725c8b053c70dca6c`
- Target release: `0.8.1`
- Baseline test result: `440 passed, 1 warning`
- Baseline Ruff check: passed
- Baseline Ruff format check: `252 files already formatted`
- Baseline compileall: passed for `src`, `tests`, and `benchmarks`
- Existing user work left untouched:
  `BUILD_INFO.json`, `README.md`, `docs/VALIDATION.md`, and
  `docs/MATHPROOFMESH_0.7_OVERVIEW.md`

The implementation remains a sidecar over the v0.7 mathematical objects and
authorities. It does not change the content-hash payloads of `ClaimCard`,
`MessageEnvelope`, `ProofObligation`, `ProofCheckpoint`, or other existing
mathematical artifacts.

## Existing Authorities

| Authority | Existing implementation | v0.8.1 rule |
| --- | --- | --- |
| Fact admission | `TypedMemory`, `MessageBroker.publish`, evidence-tier gates | Proof control may block or request work, but never writes a Fact directly. |
| Obligation closure | `ProofGraphStore.close_obligation` and its pre-close policy | Proof control never closes an obligation directly. |
| Route ownership and sparse topology | `RouteRegistry`, `SparseTopologyRouter`, Route Team | Preserve; control actions request route changes through existing authorities. |
| Proof graph | `ProofGraphStore` | Preserve mathematical nodes and hashes; domain/alias/control data stays sidecar. |
| Claim storage | `LemmaMemory` | Claim lifecycle decisions are centralized in the new controller before legacy status projection. |
| Computation | `ToolBroker` and existing computation schemas | Fast Lane emits bounded typed tasks only; it never executes arbitrary Python. |
| Inspiration | `InspirationEngine` and `_run_inspiration_round` | Deferred review cannot materialize routes or globally reviewed insights. |
| Checkpoint/resume | `_checkpoint`, `export_hierarchical_checkpoint`, component `from_state` methods | Extend with tolerant `0.8.1` sidecar fields and exactly-once action recovery. |
| SSE/activity | `ActivityStream` and existing event emitters | Preserve protocol and event transport. |

## Shared Action Layer

The common implementation will live in
`src/mathproofmesh/proof_control/action_dispatcher.py` and models will live in
`proof_control/models.py`.

- `ControlActionType` covers creation, binding, rewrite, weakening, bridge,
  countermodel, induction, assumption challenge, bottleneck, falsification,
  route update, inspiration defer/reassign, meta pivot, and direct-premise
  closure requests.
- `ControlActionRecord` is stored in
  `ProofControlState.control_actions`.
- Idempotency is derived from problem hash, action type, sorted source IDs,
  sorted route IDs, sorted target obligation IDs, and a stable payload.
- The dispatcher owns proposal, admission, execution, postcondition checks,
  checkpoint state, pending-action resume, and explicit failures.
- Diagnostic modules may create records, but may not mutate route targets,
  blueprints, countermodel queues, induction activation, route-update state, or
  pivot state directly.
- Off mode records nothing and changes no runtime behavior. Shadow mode records
  proposed/admitted decisions without authoritative mutation. Active mode
  executes only admitted actions through registered handlers.

## Work-Package Map

| WP | Current location and defect | Existing code to reuse | Planned implementation and tests |
| --- | --- | --- | --- |
| 1 Route Admission | `ProofControlLayer.register_strategy` targets the main goal; `RouteAdmissionGate.evaluate` treats a weaker intermediate result as a rewrite and can return `REWRITE` without a request. | `ProofGraphStore.core_dependency_closure`, graph edges, `GoalAlignmentAnalyzer`, `BlueprintRewriter` | Add `RouteTargetBinding` and nearest-open-target selection in `route_target.py`; bind via dispatcher; evaluate sufficiency for the direct target and an auditable path to the main goal; enforce rewrite postcondition. Tests: `test_route_admission_intermediate_lemmas.py`. |
| 2 Goal Alignment | `GoalAlignmentControlConfig` exists, but low confidence, missing outlines, and unknown relations are not consistently fail-closed; unknown countermodel status can remain `not_requested`. | `GoalAlignmentAnalyzer`, `ScopeGuard`, graph implication edges | Add `GoalAlignmentContractResult`, enum exceptions, `PremiseClosureAnalyzer`, strict contract validator, and dispatcher-backed countermodel/direct-premise actions. Tests: `test_goal_alignment_contract_enforcement.py`, `test_direct_premise_closure.py`. |
| 3 Claim lifecycle | `LemmaMemory.mark_attempt_verified` still projects an incomplete attempt onto child Claim status; states are too coarse and updates have multiple writers. | `LemmaMemory`, `VerificationReport`, broker Fact gate | Add sidecar `ClaimVerificationLedgerEntry` and `ClaimLifecycleController`; attempt reports add provenance only; claim-specific reports and exact invalidations govern transitions; legacy `ClaimStatus` becomes a compatibility projection. Test: `test_claim_verification_monotonicity.py`. |
| 4 Property strengthening | `InferenceRiskScanner` covers quantifier/scope risks but lacks relation-lattice strengthening checks. | Existing risk registration, message/close gates, failure classification | Add set-relation/property-strength signatures and six generic risk types; high-confidence risks block Fact/closure and dispatch bridge or countermodel work. Test: `test_property_strengthening_risks.py`. |
| 5 Action materialization | `ProofControlLayer` currently emits events and directly mutates several records; events have no executable/postcondition lifecycle. | `BlueprintRewriter`, graph/broker/router APIs, proof-control state persistence | Route all specified diagnosis-to-action mappings through the idempotent dispatcher and require explicit result references or failure reasons. Test: `test_control_action_materialization.py`. |
| 6 Induction activation | `_register_induction_hints` binds proposals to all main goals; candidates are not independently reviewed, activated, or injected as a concrete route plan. | `InductionMeasureSelector`, open graph obligations, route prompt control hints | Bind the triggering mathematical obligation, validate well-foundedness and strict descent, record review, dispatch activation, materialize a blueprint node, and expose an `ACTIVE INDUCTION/DESCENT SCHEME` prompt block. Test: `test_induction_measure_activation.py`. |
| 7 Common-Mode | `CriticalAssumptionMatrix` normalizes strings and may treat protocol/process text as mathematical assumptions; equivalent formulations are not family-grouped. | Route/strategy/message provenance and existing challenger shape | Add `AssumptionDomain`, `AssumptionFamily`, semantic canonicalization, mathematical-only risk scoring, and dispatcher-created challenger actions. Test: `test_common_mode_assumption_domains.py`. |
| 8 Obligation domains/Bottleneck | All obligations enter core debt and clustering; `BottleneckCompressor.materialize_clusters` is called directly and does not maintain canonical alias/status synchronization. | Proof graph dependency/centrality helpers and existing clusters | Add sidecar `ObligationDomainRecord`; exclude process/tool/verification obligations from mathematical gates and debt; dispatch cluster materialization while retaining original nodes and canonical aliases. Test: `test_obligation_domains_and_clusters.py`. |
| 9 Fast Lane | `falsification.py` only evaluates eligibility/results; strategy and risk specifications do not become typed executable tasks. | Existing computation request/result models and deterministic tool broker | Add `FalsificationTaskMaterializer` for exact finite typed handlers; unsupported requests become explicit deferred agent/manual tasks; bounded non-refutation never proves a universal claim. Test: `test_falsification_task_materialization.py`. |
| 10 Message liveness | `MessageBroker.inbox` prioritizes only counterexamples and counts prompt presentation as consumption; no guaranteed route-update opportunity exists. | Broker queues, receipts, utility records, route prompt builder | Add message priority, independent reserved slots, sidecar delivery-liveness state, and dispatcher-backed `SCHEDULE_ROUTE_UPDATE`; acknowledge semantic receipt/use separately. Test: `test_message_liveness_and_priority.py`. |
| 11 NearMiss | `_extract_near_miss` can manufacture generic records from process failures, empty answers, and whole incomplete proofs; target defaults to the main goal. | `NearMissLedger`, verifier issue metadata, realizer repair controller | Require a concrete mathematical candidate, exact failed constraints, salvage, and repair operator; exclude protocol/budget/parse/checkpoint failures; route repairs by failure semantics as non-authoritative hints. Test: `test_near_miss_semantic_quality.py`. |
| 12 Inspiration review | `_review_inspiration_proposals` converts unavailable referee calls into local `store_insight` reviews; novelty dedup is based on broad signatures. | `InspirationEngine` reservation/requeue flow and local library | Defer and optionally reassign review through dispatcher; keep unreviewed proposals route-local; add full `MechanismChainSignature` dedup without banning base technique tokens; pass only mathematical control context. Test: `test_inspiration_review_defer_and_chain_dedup.py`. |
| 13 Meta Pivot | `SolveState.global_meta_pivot_used` is a boolean request marker; hard stop can occur without proving the requested pivot executed and was evaluated. | `_apply_global_progress_gate`, inspiration meta-replan, checkpoint payload | Add sidecar `MetaPivotState` state machine and dispatcher execution; pending states block hard stop; resume continues exactly once and only evaluated no-progress for the same signature permits stop. Test: `test_meta_pivot_state_machine.py`. |

## Expected File Changes

New proof-control modules:

- `src/mathproofmesh/proof_control/action_dispatcher.py`
- `src/mathproofmesh/proof_control/claim_lifecycle.py`
- `src/mathproofmesh/proof_control/route_target.py`
- `src/mathproofmesh/proof_control/obligation_domains.py`
- `src/mathproofmesh/proof_control/falsification_tasks.py`
- `src/mathproofmesh/proof_control/inspiration_review.py`
- `src/mathproofmesh/proof_control/meta_pivot.py`

Existing integration modules:

- `src/mathproofmesh/proof_control/models.py`
- `src/mathproofmesh/proof_control/state.py`
- `src/mathproofmesh/proof_control/controller.py`
- `src/mathproofmesh/proof_control/gates.py`
- `src/mathproofmesh/proof_control/goal_alignment.py`
- `src/mathproofmesh/proof_control/inference_risk.py`
- `src/mathproofmesh/proof_control/induction.py`
- `src/mathproofmesh/proof_control/common_mode.py`
- `src/mathproofmesh/proof_control/bottleneck.py`
- `src/mathproofmesh/proof_control/falsification.py`
- `src/mathproofmesh/proof_control/near_miss.py`
- `src/mathproofmesh/proof_control/message_utility.py`
- `src/mathproofmesh/proof_control/proof_roles.py`
- `src/mathproofmesh/proof_control/__init__.py`
- `src/mathproofmesh/communication/broker.py`
- `src/mathproofmesh/route_pipeline.py`
- `src/mathproofmesh/prompts.py`
- `src/mathproofmesh/memory.py`
- `src/mathproofmesh/orchestrator.py`
- `src/mathproofmesh/resume_phase.py`
- `src/mathproofmesh/report.py`

Test and release files:

- The sixteen required `tests/test_v081_*` and named WP regression files
- `tests/test_no_problem_specific_production_logic.py` or equivalent static check
- Mock E2E fixtures only; no captured provider output
- `RELEASE_NOTES_0.8.1.md`
- Version-only edits to `pyproject.toml`, `src/mathproofmesh/__init__.py`,
  `BUILD_INFO.json`, `README.md`, and `docs/VALIDATION.md`

The exact file set may shrink when an existing module is sufficient. New
parallel authorities will not be introduced merely to match this forecast.

## Checkpoint and Compatibility Map

- `ProofControlState.schema_version` advances from `0.8` to `0.8.1`.
- Missing v0.8.1 fields restore to empty/default sidecar records and emit one
  migration event.
- v0.7 checkpoints with no proof-control state continue to initialize an empty
  sidecar through the existing migration path.
- Executed actions restore as executed and are never re-run.
- Admitted/executing actions resume through the same idempotency key and
  postcondition check.
- Deferred inspiration reviews remain deferred and cannot become materialized
  routes during restore.
- Meta-pivot status, source progress signature, created route IDs, and
  evaluation result are checkpointed together.
- Existing YAML files remain valid because all new configuration fields have
  compatibility defaults under the existing proof-control block.

## Hash, Fact, and Closure Risks

- Obligation domain, route target binding, lifecycle, delivery liveness, and
  pivot state are sidecar records. They do not enter existing immutable payloads.
- The dispatcher may call registered authority adapters but has no direct
  `TypedMemory.add_fact` or `ProofGraphStore.close_obligation` handler.
- `CLOSE_BY_DIRECT_PREMISE` requests normal graph/broker verification and
  closure; it is not itself closure evidence.
- Bounded computation produces counterexamples, boundary evidence, or
  inconclusive results. It never promotes a Fact.
- High-confidence inference risk remains visible until a verified bridge,
  counterexample, or claim-level review resolves it.

## Static Guardrails

- Production code must contain no conditionals for a particular theorem,
  problem hash, variable spelling, or domain token such as the prohibited
  examples in the implementation specification.
- No changes are permitted to output-token settings, segment lengths, Deep
  Exploration tiers, SSE, agent counts, or default budgets.
- Strict gates must not be disabled, exceptions must not be swallowed, failure
  evidence must not be deleted, and active mode must not fall back to legacy
  behavior to manufacture progress.
- No real provider API is called during implementation or validation.
