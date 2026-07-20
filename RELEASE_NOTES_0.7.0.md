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
