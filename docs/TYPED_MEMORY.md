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

## Promotion Gate

Insight promotion requires verified status, threshold confidence, an allowed
evidence type, a distinct referee, complete quantifier normalization, resolved
dependencies, no cycle and no known counterexample. Repeated proposal or
majority agreement only merges provenance; it never promotes a claim.

Every Inspiration Engine output enters Insight first. Novelty is not evidence.
Bounded computation stays Insight even when no counterexample is found. An
independently replayed exact counterexample enters Negative.

## Demotion And Invalidation

When a fact is refuted, it and its transitive dependents leave FactMemory,
affected graph obligations reopen, and invalidation Activity is recorded.
Cycles are rejected or conservatively demoted. The exported state stores tiers,
messages, dependency links, invalidation records and merged provenance so
resume preserves the same trust boundary.
