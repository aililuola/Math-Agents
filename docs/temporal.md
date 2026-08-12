# Temporal Development and Workflow Boundary

MathProofMesh 0.8.0 uses Temporal for durable scheduling, not for mathematical
state. PostgreSQL checkpoints, message state, Fact promotion, the proof graph,
and action-key ledgers remain authoritative. Temporal history contains only
run, route, checkpoint, action, and artifact identifiers plus bounded summaries.
It must not contain prompts, proof bodies, raw provider responses, private
reasoning, credentials, or filesystem roots.

The only workflows are `MathProofMeshSolveWorkflow` and
`RouteExplorationWorkflow`. Final blind review is one or more idempotent
`FinalReviewActivity` calls from the solve workflow; there is no
`FinalReviewWorkflow`. Workflow implementations contain no Spring repository,
JDBC, HTTP, provider, filesystem, Python, wall-clock, random UUID, thread, lock,
virtual-thread, environment, or dynamic-configuration access. All I/O crosses a
typed Activity port.

Every domain-writing Activity supplies an idempotency/action key and fencing
token to a transactional store. An Activity retry, worker replacement, or
completion-before-ack therefore returns the already committed result rather
than applying a message, checkpoint, control action, or Fact twice. Heartbeats
contain only action IDs and safe state labels.

Signals are `pause`, `resume`, `cancel`, and `wakeRoute`. Updates are
`increaseBudget` and `submitAuditedDirective`; both validate input and dedupe by
caller-supplied update ID. Queries are `status`, `currentStage`,
`routeSummary`, and `budgetSummary`. Workflow evolution uses a Temporal version
marker. Continue-As-New bounds scheduling history while retaining the database
checkpoint as mathematical restart authority.

## Local Development

The development service is not a production deployment. Production must use
Temporal Cloud or a separately reviewed self-hosting ADR with TLS/mTLS,
authentication, least privilege, monitoring, and backup.

The local image is the immutable digest in `migration/image-lock.env`. The
Compose service is headless, uses persistent SQLite, has a read-only root
filesystem, drops every capability, enables no-new-privileges, and publishes
only `127.0.0.1:7233`.

```powershell
.\scripts\temporal-dev.ps1 -Command Up
.\scripts\temporal-dev.ps1 -Command Health
.\scripts\temporal-dev.ps1 -Command Down
```

`Down` retains the named volume. Destructive reset requires explicit secondary
confirmation:

```powershell
.\scripts\temporal-dev.ps1 -Command Reset -ConfirmReset
```

Unit, replay, signal, update, and fault-injection tests use
`TestWorkflowEnvironment`; the real development service is reserved for the
digest, hardening, persistence-restart, loopback, and health gates.
