# Operations

## Installation

Use JDK 25 and the checked-in Maven Wrapper. Run `scripts/verify-all.ps1` on
Windows or `scripts/verify-all.sh` on POSIX before deployment. The release
archive contains the server, CLI launchers, sidecar lock, Flyway migrations,
container composition, SBOM, reports, and checksums.

## Configuration

Configuration is strict: unknown fields, unsafe paths, unapproved endpoints,
and inconsistent budget or recovery settings fail at startup. Store secrets in
environment or an external secret provider, never in YAML. Live provider
calls require the explicit `MPM_ALLOW_LIVE_PROVIDER_CALLS=true` opt-in.

Bind the application, PostgreSQL, and Temporal only to trusted interfaces.
The included development composition binds to loopback and is not a
production topology.

## PostgreSQL

Use PostgreSQL 18 or a tested compatible release. Create a dedicated database
and least-privilege application role. Flyway migrations live in
`mathproofmesh-server/src/main/resources/db/migration` and run in order.
Never edit an applied migration; add a new versioned migration.

Back up the database and content-addressed artifact root as one consistency
set. Encrypt backups, record their hashes, retain the Flyway history table,
and regularly restore into an isolated environment. A backup is not accepted
until restore, integrity, resume, and artifact hash checks pass.

## Temporal

Production Temporal must use TLS or mTLS, authentication, a dedicated
namespace, retention appropriate to proof runs, and server-side backups.
Workers may restart or replay, but PostgreSQL remains domain authority.
Monitor retry storms, workflow history growth, task queue latency, activity
timeouts, and Continue-As-New frequency.

For target-local development:

```powershell
.\scripts\temporal-dev.ps1 -Command Up
.\scripts\temporal-dev.ps1 -Command Down
```

## Providers

Allowlist provider base URLs and models. Set per-provider concurrency, rate,
timeout, retry, token, and cost budgets. Treat 401/403 as credential faults;
do not retry the same key. Inspect the provider-call and usage ledgers rather
than raw prompts or responses. Raw provider artifacts should have short,
explicit retention and must never contain secrets or private chain-of-thought.

## Sidecar

Create the sidecar environment from
`python-compute-service/requirements.lock`. Do not add packages without
updating the lock, SBOM/license review, and differential tests. The service
communicates by stdio only. Run it under a restricted OS identity with no
network, database credentials, or writable application roots.

## Backup And Recovery

1. Quiesce writes or capture a database-consistent snapshot.
2. Back up PostgreSQL and the artifact root.
3. Record SHA-256 checksums, versions, Flyway state, and encryption metadata.
4. Restore to an isolated network.
5. Verify artifact hashes and database constraints.
6. Resume a nonterminal Mock run from its committed checkpoint.
7. Confirm terminal resume performs zero provider calls.

Run leases and fencing tokens reject stale workers. Outbox/inbox identities
make event publication and consumption idempotent. Never repair a committed
checkpoint in place; append a corrected checkpoint or branch.

## Upgrade And Legacy Import

Verify the old release and take a tested backup before upgrade. Apply Flyway
migrations once, deploy server and workers together, then run health, Mock,
resume, and SSE checks. Roll back application binaries only when the database
schema remains compatible; otherwise restore the consistency set.

Legacy import must point to a read-only copied run directory. The importer
rejects links, external paths, corrupt hashes, invalid checkpoint chains, and
oversized trees. Duplicate manifest hashes return the same target identity.
Review quarantine output before any operator-authored remediation.

## Troubleshooting

- **Startup validation fails:** inspect the named field and remove unknown or
  unsafe configuration. Do not weaken strict parsing.
- **Database migration fails:** stop, preserve logs and Flyway state, and
  repair the environment rather than editing an applied migration.
- **Temporal replay fails:** preserve history and compare deployed workflow
  code. Do not move I/O into workflow methods.
- **Provider calls stall:** inspect budgets, rate limits, Retry-After, circuit
  state, and call-ledger ambiguity.
- **SSE reconnect misses data:** reconnect with the last accepted monotonic
  event ID and verify retention.
- **Sidecar fails:** inspect bounded protocol errors and worker health; do not
  enable arbitrary Python.
- **Legacy import is quarantined:** fix the copied input or provide audited
  operator evidence. Never bypass quarantine.

## Limits

The supplied Temporal service is single-node development infrastructure.
Desktop packaging targets Windows x64. Live provider behavior depends on
operator accounts and is not exercised by acceptance tests. Performance
reference values are machine-specific and allow at most 20 percent regression
on the same hardware without documented approval.
