# Architecture

JavaMathProofMesh is a Java 25 modular monolith with PostgreSQL domain
authority, Temporal durable orchestration, and one restricted Python compute
process. The frozen Python application is migration evidence, not a runtime
dependency.

## Dependency Direction

```text
contracts <- core <- server <- desktop
    ^          ^        ^         ^
    +----------+--------+---------+--- compatibility
```

- `mathproofmesh-contracts` contains strict records, enums, canonical JSON,
  validation, and hashes. It has no Spring dependency.
- `mathproofmesh-core` contains framework-free proof, message, memory, graph,
  verification, control, inspiration, and native computation policy.
- `mathproofmesh-server` contains configuration, providers, PostgreSQL,
  Flyway, artifacts, REST/SSE/CLI, the sidecar adapter, and Temporal.
- `mathproofmesh-desktop` contains JavaFX/JNA and drives loopback server APIs.
- `mathproofmesh-compatibility` contains the read-only importer, shadow
  comparator, authority fixtures, and migration-only tests.

Maven Enforcer and ArchUnit reject reverse dependencies and duplicate classes.

## Domain Authority

PostgreSQL is authoritative for runs, problem contracts, routes, claims,
messages and receipts, memory, proof obligations and graph edges, committed
checkpoints, verification, control actions, artifacts, usage, provider calls,
events, outbox/inbox delivery, leases, and legacy imports. Mutable aggregates
use optimistic versions. Run-scoped unique keys prevent cross-run
deduplication.

Committed mathematical payload is append-only. Counterexamples invalidate
facts transitively without erasing history. Working checkpoints are never
visible as committed proof. Filesystem artifacts are immutable,
content-addressed, bounded, and confined to configured roots.

## Proof Pipeline

The fixed stages are problem freeze, triage, diverse strategy generation,
isolated route exploration, checkpoint continuation, claim extraction,
structural verification, detailed escalation, meta-review, adaptive control,
synthesis, and independent final review. Route communication crosses only the
typed broker. A sparse topology avoids all-to-all debate while preserving
auditable deliveries and receipts.

Fact, Insight, and Negative memory have separate admission and promotion
rules. Claim confidence, route popularity, and model self-report never grant
mathematical authority. Only independently verified evidence may close an
obligation or enter a final proof.

## Durable Execution

Only `MathProofMeshSolveWorkflow` and `RouteExplorationWorkflow` are Temporal
workflows. Workflow code is deterministic; all I/O, provider calls, database
work, sidecar work, and final review run as idempotent Activities.

The database remains authoritative during replay. Stable action keys,
checkpoint compare-and-set, provider call ledgers, outbox/inbox identities,
and fencing tokens provide exactly-once state transitions over at-least-once
execution. Continue-As-New bounds workflow history.

## Providers And Compute

Provider adapters share strict endpoint allowlists, bounded virtual-thread
concurrency, rate limits, timeout/retry policy, circuit state, budgets, usage
reconciliation, and a crash-safe call ledger. Authentication failures are not
retried with the same credential.

Deterministic geometry, graph, sequence, modular, and number-theory handlers
are native Java. SymPy/Z3-compatible operations use a versioned JSON-RPC
stdio sidecar with fixed operations, AST validation, deterministic seeds,
bounded workers, timeouts, byte limits, and no workflow or database authority.

## Interfaces

Spring exposes loopback-first REST and monotonic resumable SSE. The CLI uses
the same application services. JavaFX launches an embedded loopback server and
uses only HTTP/SSE. Its WebView blocks external and file navigation,
downloads, developer tools, and provider credential access.

## Security And Failure Model

Unknown configuration and JSON fields fail. No Jackson default typing or
arbitrary class deserialization is enabled. Database access is parameterized
and run-scoped. Provider URLs, SQL URLs, sandbox commands, artifact roots, and
image references are operator-owned, never prompt-owned.

Secrets are redacted from records, logs, errors, JSON, SSE, snapshots, and
artifacts. Development containers use immutable digests, loopback bindings,
read-only filesystems where supported, dropped capabilities, non-root users,
and explicit resource limits. Production requires TLS or mTLS, authentication,
least privilege, monitoring, and tested backups.

## Compatibility

Legacy imports read a closed directory tree twice, reject links and external
references, verify per-file/problem/artifact/checkpoint hashes, and derive a
unique manifest identity. Ordered migrations preserve 0.7, 0.8.0 sidecar,
0.8.1 exactly-once, and 0.8.2 checkpoint/dependency semantics. Unverified
legacy truth claims and bypass receipts remain quarantined.

The shadow comparator covers contracts, strategies, messages, deliveries,
memory, proof graph, checkpoints, recovery, usage, and final state. Only
explicit natural-language pointers may be declared nondeterministic.

Byte-exact copies of the Python design baseline are retained under
`docs/legacy/python-baseline`.
