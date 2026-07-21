# MathProofMesh 0.7.0

## Highlights

- Added an opt-in hierarchical sparse topology with route-local prover,
  skeptic, tool and referee teams.
- Added typed mathematical messages, sparse broker routing, receipts,
  exactly-once resume and strict Fact/Insight/Negative evidence boundaries.
- Added the Proof Obligation Graph, proof debt, bridge and contradiction
  brokers, mechanism-aware duplicate-route handling and blind final audit.
- Added the complete P0 Inspiration Engine: Representation Switchboard,
  verified-local Analogy Agent, Auxiliary Construction Inventor,
  Invariant/Monovariant Agent, Reverse Goal Analyzer, Persistent
  Meta-Strategist, Surprise Budget, Novelty Gate and independent Inspiration
  Referee.
- Added validation escalation, proof-mutation evaluation, domain-role
  capability profiles and a selective formal micro-certificate interface.
- Added stable topology/inspiration checkpoint state, Activity events, reports,
  six offline benchmark cases and eleven required ablations.

## Active Runtime Closure

The active profile now exercises the hierarchical components in the live proof
path rather than only recording post-hoc topology metadata:

- Typed prompt context is recursively JSON-normalized, including nested
  Pydantic models, enums, dataclasses, paths and collections.
- Each route reads its bounded Broker inbox and typed memory before the next
  continuation call, then returns a semantic receipt for every delivered
  message. The Broker recomputes receipt hashes and persists exactly-once
  delivery state across resume.
- Ordinary active routes run through Prover, risk-directed Skeptic/Tool and an
  independent Referee before an artifact may be promoted to global FactMemory.
  Missing or failed required roles fail closed and leave the artifact local.
- Active Inspiration, Bridge and Contradiction typed calls are covered by
  end-to-end deterministic tests. Inspiration and route-team state are emitted
  as stable structured artifacts for audit.

## Secondary Audit Closure

- Hierarchical Route Provers receive no legacy global ClaimMemory context;
  all cross-route premises must arrive through Broker delivery and TypedMemory.
- Receipts independently restate ordered quantifiers and variable bindings.
  A scope or quantifier reversal is rejected before checkpoint advancement.
- Prover, Skeptic, Tool Specialist and Referee assignments are pairwise
  distinct. Missing independence leaves the artifact route-local.
- Active hierarchical configuration now requires continuation, so users cannot
  accidentally bypass the live typed route pipeline.
- Validation escalation has an executor for deterministic, blind,
  adversarial, cross-provider and tool/formal evidence. Agent capability
  scores now participate in domain-role dispatch.
- Blind final packets include Typed Fact scope/evidence/artifact metadata,
  anonymized referee provenance and Typed NegativeMemory.
- Inspiration tasks pass unified scheduler admission before model calls;
  `require_inspiration_referee` and `max_new_routes_per_trigger` are enforced.
- Message utility is credited only after a committed, verified delta cites the
  message or closes a claimed obligation. Receipt acceptance alone is zero.
- Computation-backed route artifacts invoke a real independent Tool Specialist
  typed prompt in addition to deterministic replay.
- Active output uses a 384K provider ceiling with a 64K operating limit per
  Agent and continuation segment.

## Compatibility

`legacy_sparse` remains the default for generic configuration and preserves the
0.6 workflow. The DeepSeek formal and smoke profiles start hierarchical graph
and inspiration behavior in shadow mode. The separate
`config.deepseek-v4-pro.topology-active.yaml` enables active materialization.

No API key, `.env`, run output, provider response, cache, wheel or source archive
is part of this release commit.

## Validation

The release gate runs the complete Pytest suite, Ruff check and format check,
`compileall`, all shipped configuration parses and the offline topology
benchmark. Real provider calls are intentionally excluded from automated
validation.

## Final P0 Audit Closure

- Rejected `InspirationProposal` values now use an exact blind-negative
  serializer. The packet preserves the mathematical proposal and rejection
  rationale without exposing route or Agent identity.
- Hierarchical verification, synthesis, blind review and final revision now
  share one admissible global-fact policy. A global premise must be a live
  TypedMemory Fact admitted by the Broker with independent-referee provenance;
  unresolved dependencies fail closed.
- Legacy LemmaMemory remains available to `legacy_sparse`, while hierarchical
  resume quarantines legacy-only checkpoint claims instead of promoting them.
- Dynamic and AST regressions cover rejected active Inspiration, rejected
  route-local claims, verifier tool follow-ups, synthesis, blind packets,
  revision, dependency gates and old-checkpoint resume.
