# Typed Memory

MathProofMesh keeps three memory tiers. A `Fact` is reusable only after independent
verification, sufficient reusable evidence, resolved dependencies, and the configured
confidence and normalization thresholds. A bounded experiment or numerical sample remains
an `Insight`. Replayed counterexamples and invalidated claims are retained as `Negative`
memory rather than deleted.

`MemoryPromotionPolicy` is the only Fact admission authority.
`MemoryInvalidationService` is the only in-memory demotion authority. The PostgreSQL
repository applies the durable equivalent in one transaction: store the Negative, invalidate
the affected memory closure, reopen proof obligations, and append the event and outbox row.
The propagation batch ID makes retries idempotent.

PostgreSQL is authoritative. `TypedMemory` is a deterministic, thread-safe projection used
for policy evaluation and scheduling. Provenance, dependency edges, invalidation reasons,
versions, and audit events remain visible after demotion.

The original Python design note is retained byte-for-byte at
`docs/legacy/python-baseline/TYPED_MEMORY.md`.
