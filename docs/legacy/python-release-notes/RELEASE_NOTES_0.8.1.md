# MathProofMesh 0.8.1

Release date: 2026-07-25

MathProofMesh 0.8.1 is a semantic-control and action-closure bugfix release on
`feature/mathproofmesh-v0.8.0-goal-plan-failure-utility-control`. It does not
change `main`, token limits, segment lengths, Deep Exploration tiers, SSE,
agent counts, or budget defaults.

## Fixed

- Route Admission binds a route to its nearest mathematical obligation, so a
  useful intermediate lemma is not rejected merely for being weaker than the
  final theorem.
- Goal Alignment thresholds, non-empty outlines, countermodel requirements,
  and strict fail-closed behavior are enforced by the admission contract.
- Claim verification is monotonic and independent of the enclosing Attempt;
  demotion requires explicit invalidating evidence.
- Relation-lattice checks reject unsupported strengthening from local,
  existential, overlap, or image facts to global properties.
- Control diagnoses become idempotent dispatcher actions with admission,
  execution, postcondition, failure, and resume records.
- Reviewed induction measures enter obligation-bound route blueprints and
  prompts.
- Mathematical assumptions, protocol text, and process/tool/verification
  obligations remain in separate domains; only mathematical debt contributes
  to bottleneck decisions.
- Bottleneck bridges and falsification work are materialized as executable
  tasks; absence of a counterexample never proves a claim.
- High-priority messages retain processing capacity, semantic receipts track
  actual use, and a queued message can schedule an explicit Route Update turn.
- Mathematical NearMiss records exclude process failures and route bounded
  repairs to the appropriate module.
- Inspiration review supports durable defer/reassign behavior and
  mechanism-chain deduplication without banning ordinary mathematical tools.
- Meta Pivot uses a persisted requested/admitted/executing/executed/evaluated
  or failed state machine and recovers exactly once after interruption.

## Compatibility

- Proof control remains disabled by default and supports `off`, `shadow`, and
  `active`.
- Metadata remains a sidecar and does not alter mathematical object hashes.
- The control layer does not write Facts or close proof obligations directly.
- Existing v0.7 YAML and checkpoints remain readable. v0.8.0 sidecars missing
  v0.8.1 fields migrate to empty defaults.
- Broker, Route Team, Proof Graph, Typed Memory, Inspiration, SSE, and
  checkpoint/resume behavior remain available.

## Offline Validation

- `pytest`: 515 passed
- required E2E/resume selection: 17 passed
- Ruff check: passed
- Ruff format check: 272 files formatted
- `compileall` over `src`, `tests`, and `benchmarks`: passed
- topology Mock benchmark: 21/21 contracts, 0 provider calls
- proof-control Mock benchmark: 14/14 contracts, 0 provider calls
- Windows desktop packaging: 17 regression tests, packaged health check, and
  hidden window smoke test passed

No real provider API was called. No API keys, `.env`, run artifacts, caches,
distribution artifacts, or provider outputs are part of this release commit.
