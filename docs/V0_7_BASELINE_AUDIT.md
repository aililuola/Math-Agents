# MathProofMesh 0.7.0 Baseline Audit

## Baseline identity

- Baseline branch: `feature/mathproofmesh-v0.6.0-reasoning-first-computation`
- Baseline commit: `5a77fffb0758240e021c86545fe6165fac52585a`
- Development branch: `feature/mathproofmesh-v0.7.0-hierarchical-sparse-topology`
- Declared version: `0.6.0` in `pyproject.toml`, `BUILD_INFO.json`, and
  `src/mathproofmesh/__init__.py`
- Audit date: 2026-07-20

## Baseline verification

- `python -m pytest`: 80 passed
- `ruff check .`: passed
- `ruff format --check .`: 57 files already formatted
- `python -m compileall -q src`: passed

The editable environment was refreshed with the existing `dev` and `server`
extras before running the baseline suite.

## Existing architecture

The 0.6.0 implementation is intentionally flat around a large
`ProofMeshOrchestrator`. Its main extension points are:

- `config.py`: Pydantic configuration and legacy sparse-topology settings.
- `schemas.py`: strict wire models, proof artifacts, scheduler actions, and run
  results.
- `topology.py`: legacy sparse route selection.
- `memory.py`: untyped `LemmaMemory` compatibility surface.
- `budget.py`: adaptive and soft budget allocators.
- `agents.py` and `prompts.py`: role dispatch and structured model prompts.
- `continuation.py`: segmented proof continuation.
- `store.py`: run artifacts and checkpoint persistence.
- `activity.py` and `report.py`: user-visible activity and final reporting.
- `orchestrator.py`: planning, exploration, verification, repair, synthesis,
  final verification, and resume orchestration.

## 0.6.0 computation mapping

Reasoning-first computation already lives under `src/mathproofmesh/computation`:

- `policy.py`: `ComputationGate` and targeted-falsification policy.
- `broker.py`: typed-tool selection and experiment execution.
- `cache.py`: deterministic request/result caching and ledger state.
- `sandbox.py`: optional isolated generated-Python execution.
- `handlers/`: symbolic, modular, bounded integer, graph, recurrence, and exact
  geometry handlers.
- `tools.py`: compatibility exports for older callers.

This subsystem remains authoritative in 0.7.0. It will publish typed
`ComputationPlan` and `ComputationCertificate` messages instead of being
reimplemented by the communication layer.

## 0.7.0 module mapping

The revised implementation specification maps onto new ownership boundaries:

- `communication/`: immutable typed envelopes, receipts, sparse routing,
  delivery idempotency, and route registry.
- `proof_graph/`: proof obligations, typed dependencies, contradiction and
  duplicate detection, bridge-task discovery, and proof debt.
- `teams/`: route-local prover/skeptic/tool/referee collaboration with explicit
  independence rules.
- `inspiration/`: the complete section 11A Inspiration Engine, including
  representation switching, analogy, construction, invariant/monovariant,
  reverse-goal, persistent meta-strategy, protected surprise exploration,
  novelty signatures, and independent inspiration review.
- `verification/`: escalation policy, empirical agent capability profiles,
  mutation testing, and optional formal micro-certificates.

Existing entry points remain in place. The new components are composed behind
`TopologyConfig.mode`; `legacy_sparse` must preserve 0.6.0 behavior, while
`hierarchical_sparse` enables the new runtime.

## Compatibility and migration risks

1. Old YAML files omit all 0.7.0 nested sections. Every new field therefore
   needs a backward-compatible default and strict cross-field validation only
   when its feature is enabled.
2. Old checkpoints contain neither broker delivery state nor proof graph,
   route registry, typed memory, or inspiration state. Resume must synthesize
   empty 0.7.0 state once and record a migration Activity event.
3. Message hashes must exclude mutable delivery metadata. Any accidental hash
   dependency on recipients, confidence, or timestamps would break retries and
   resume idempotency.
4. Shadow mode may observe and log but must not alter scheduling, memory
   promotion, route creation, or final proof output.
5. Counterexamples and contradictions have stronger semantics than positive
   bounded experiments. Positive numerical evidence must never promote a fact
   or close an unbounded obligation.
6. Route-local reviewers must not become their own independent referees when
   the configured agent pool is too small; the safe fallback is local-only
   review with an explicit diagnostic.
7. Inspiration proposals are hypotheses in Insight memory. Novelty is not
   correctness, and no proposal can close an obligation before the normal
   skeptic, evidence, and referee gates.
8. Graph freeze and blind-final-review sanitation must be enforced during
   synthesis so late writes or route provenance cannot bias the final audit.
9. The optional Python sandbox stays disabled by default and must not gain
   network, secrets, workspace mounts, or implicit execution through the new
   broker.

These risks are covered by compatibility, checkpoint/resume, gate, shadow-mode,
and end-to-end tests before the 0.7.0 release is published.
