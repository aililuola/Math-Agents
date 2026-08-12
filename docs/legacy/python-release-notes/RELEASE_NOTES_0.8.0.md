# MathProofMesh 0.8.0

## Highlights

- Added an opt-in Goal-Plan-Failure-Utility control layer implementing all 17
  proof-control work packages.
- Added sidecar goal alignment, proof roles, scope signatures, ten inference
  risks, core proof debt, bottleneck compression, common-mode detection,
  abstract/realizer separation, bounded repair, induction measures, four-level
  failure classification, blueprint rewrites, message utility, Near-Misses,
  exact falsification, route admission, continue, and synthesis gates.
- Added separate off, shadow, and active behavior. Generic configuration
  remains off; the two shipped rollout profiles change no reasoning-token,
  continuation-segment, or Deep Exploration tier setting.
- Added schema-0.8 sidecar checkpoint state with deterministic v0.7 migration
  and exactly-once resume.
- Added auditable proof-control Activity events and summary metrics.
- Added ten deterministic logic-trap fixtures, fourteen component contracts,
  off/shadow/active comparison, active Inspiration integration, hash and
  authority regressions, and zero-provider offline benchmarks.

## Compatibility And Safety

- Existing v0.7 YAML and checkpoints remain loadable.
- Broker, Route Teams, Proof Graph, Typed Memory, Inspiration, DeepSeek SSE,
  computation policy, and final blind review remain the mutation authorities.
- `ProofControlLayer` does not write Facts or directly close obligations.
- Mathematical object hash payloads are unchanged.
- No complete MCTS, complete Lean integration, token-policy change, or
  Orchestrator decomposition is included.
- No API key, `.env`, run output, cache, build artifact, distribution artifact,
  or provider response is part of the release commits.

## Validation

The release gate runs the complete Pytest suite with `z3-solver`, Ruff check
and format check, `compileall`, the v0.7 topology benchmark, the v0.8
proof-control benchmark, legacy/off regression, checkpoint resume, active
proof-control E2E, and active Inspiration plus active proof control. All
automated runs are deterministic and make zero real provider calls.
