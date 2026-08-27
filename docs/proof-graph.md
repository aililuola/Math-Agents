# Proof Obligation Graph

The proof graph records obligations, typed claim nodes, and typed relations such as
dependency, construction, strengthening, weakening, contradiction, equivalence, and
closure. PostgreSQL is authoritative; `ProofGraphStore` rebuilds a JGraphT projection with
four bulk reads rather than per-node queries.

The graph rejects missing endpoints and cycles in acyclic relations. It supports dependency
closure, deterministic topological order, proof debt, shared bottlenecks, duplicate-route
detection, conflict detection, minimal subgraphs, and immutable freeze snapshots. Closing an
obligation requires verified Fact evidence and optimistic version agreement.

A replayed counterexample invalidates its Fact and dependent memory, disables affected
closure edges, recursively reopens obligations as `needs_reverify`, and writes one audit
event/outbox record in the same database transaction. A repeated propagation batch returns
the first result without applying another mutation.

The original Python design note is retained byte-for-byte at
`docs/legacy/python-baseline/PROOF_OBLIGATION_GRAPH.md`.
