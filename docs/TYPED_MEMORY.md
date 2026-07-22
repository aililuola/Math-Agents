# Typed Memory

`TypedMemory` extends, rather than removes, the v0.6 `LemmaMemory` interface.
Legacy `verified()`, `uncertain()`, `rejected()`, `add_many()`,
`apply_claim_report()` and `mark_attempt_verified()` remain available.

## Tiers

`FactMemory` contains reusable independently verified claims. `InsightMemory`
contains search hints, bounded experiments, inspiration proposals and other
unproved ideas. `NegativeMemory` contains exact counterexamples, rejected
claims, invalidated dependents and retained failure records.

The compatibility mapping is `verified -> Fact`, `uncertain -> Insight` and
`rejected -> Negative`. Route context is filtered by visibility and configured
per-tier limits.

Final-stage context is also purpose-aware. Verification favors explicit
dependencies and strong evidence, synthesis additionally favors central
reusable Facts, Blind Review uses identity-free packets, and revision favors
Facts related to the failed proof. Explicit `message_id`/`content_hash`
references and their dependency closure are selected before lexical matches.

## Promotion Gate

Insight promotion requires verified status, threshold confidence, an allowed
evidence type, a distinct referee, complete quantifier normalization, resolved
dependencies, no cycle and no known counterexample. Repeated proposal or
majority agreement only merges provenance; it never promotes a claim.

Every Inspiration Engine output enters Insight first. Novelty is not evidence.
Bounded computation stays Insight even when no counterexample is found. An
independently replayed exact counterexample enters Negative.

Blind Review does not append the whole NegativeMemory. It first selects all
exact counterexamples and explicitly cited/direct conflicts, then fills the
remaining `max_negative_context` and character budget by relevance, evidence
strength and route centrality. Optional omissions are reported. If a mandatory
negative cannot fit, `negative_context_complete=false` deterministically blocks
final PASS rather than silently weakening the audit.

Artifact paths are not part of Blind packets. A Blind evidence descriptor keeps
only the actual file-content SHA-256, evidence/certificate type and replay
status; the original run-scoped path remains available only to trusted local
audit code.

## Demotion And Invalidation

When a fact is refuted, it and its transitive dependents leave FactMemory,
affected graph obligations reopen, and invalidation Activity is recorded.
Cycles are rejected or conservatively demoted. The exported state stores tiers,
messages, dependency links, invalidation records and merged provenance so
resume preserves the same trust boundary.
