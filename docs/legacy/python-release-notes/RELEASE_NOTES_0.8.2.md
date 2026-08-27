# MathProofMesh 0.8.2

Release date: 2026-07-26

MathProofMesh 0.8.2 completes the mathematical-semantic validity,
task-executability, and recovery-policy repair on
`feature/mathproofmesh-v0.8.0-goal-plan-failure-utility-control`. It does not
change `main`, token limits, segment lengths, Deep Exploration tiers, SSE,
agent counts, or budget defaults.

## Fixed

- Strategy cards are archived before selection, compiled into validated
  multi-node blueprints, and bound to a nontrivial direct target before Route
  Admission. Rejected tentative nodes do not enter mathematical proof debt.
- Blueprint rewrites require a child strategy, preserved mechanism lineage, a
  non-self implication, a concrete first step, and successful re-admission.
- Typed dependency references distinguish local steps, local claims, global
  Facts, obligations, and messages. Ambiguous legacy references create review
  work instead of silently resolving to the wrong namespace.
- Route Referee claim dispositions reach the monotonic Claim ledger exactly
  once. An overall route pass no longer promotes every enclosed claim.
- Countermodel and falsification requests compile to typed executable tasks
  with an executor, wake condition, expiry, and terminal result. Bounded
  non-refutation remains evidence and never proves a universal statement.
- Meta Pivot records ordered mechanism attempts and requires a material state
  effect before execution can succeed.
- Structured verifier issues produce deterministic inference-risk records;
  unresolved critical semantic risks block Fact-candidate promotion.
- Obligation candidates pass a semantic-quality policy before graph insertion.
  Placeholders, action text, self-implications, and invalid goal copies remain
  quarantined sidecar evidence.
- Mathematical, search, process, protocol, tool, verification, and safety
  domains remain separated. Only accepted mathematical obligations contribute
  to core debt, bottleneck compression, common-mode analysis, and synthesis
  readiness.
- Zero-utility cross-route messages are kept local unless an explicit
  critical, refutation, or closure exception applies.
- Routes distinguish wakeable `WAITING` work from intervention-only `FROZEN`
  work. Wake and intervention transitions are idempotent and checkpointed.
- Terminal resume planning runs before provider work. An unchanged hard-stop
  state with no pending or wakeable work returns existing progress with zero
  model calls.
- A complete verified synthesis candidate may scope unrelated planning debt
  without hiding explicit dependencies, invalid links, risks, conflicts, or
  unadmitted evidence.

## Compatibility

- Proof control remains disabled by default and retains `off`, `shadow`, and
  `active` modes.
- All new control state is sidecar metadata; existing mathematical object
  content hashes remain unchanged.
- `ProofControlLayer` neither writes Facts nor closes obligations directly.
- Existing v0.7 YAML and checkpoints, v0.8.0 sidecars, and v0.8.1 checkpoints
  remain readable. Missing v0.8.2 collections migrate to empty defaults.
- Broker, Route Team, Proof Graph, Typed Memory, Inspiration, DeepSeek SSE, and
  checkpoint/resume behavior remain available.
- Production control logic contains no problem-specific theorem, symbol,
  `problem_hash`, or current-run condition.

## Offline Validation

- `pytest`: 604 passed
- required E2E/resume selection: 47 passed
- Ruff check and Ruff format check: passed
- `compileall` over `src`, `tests`, and `benchmarks`: passed
- topology Mock benchmark: 21/21 contracts, 0 provider calls
- proof-control Mock benchmark: 14/14 contracts, 0 provider calls
- blueprint rewrite, falsification, Meta Pivot, Active Inspiration, terminal
  resume, mid-task wake/resume, and checkpoint migration E2E paths: passed
- Windows desktop packaging: 17 regression tests, packaged health check,
  hidden window smoke, and installed health check passed

No real provider API was called. No API keys, `.env`, run artifacts, caches,
distribution artifacts, or provider outputs are part of the release commit.
