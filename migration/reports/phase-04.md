# Phase 04 Report

**Result:** PASS  
**Scope:** PostgreSQL, Flyway, artifacts, outbox/inbox, and run lease  
**Started:** 2026-07-30T12:40:01.220Z  
**Completed:** 2026-07-30T13:26:30.647Z  
**Git commit:** N/A (workspace is not a Git worktree)

## Prerequisite and source authority

Phase 03 was `passed` before phase 04 started. The only authoritative Python
source remains:

```text
migration/input/Math-Agents-feature-mathproofmesh-v0.8.0-goal-plan-failure-utility-control.zip
SHA-256 5e09eb8a704ea5f841078bafcc1c5e72e4d17a76b2fef7a344e41d55e013c1b2
```

The frozen 401-file source manifest passed before and after implementation:

```text
9f3def2bec8cea99d0a18b51fbb5fa8ce53f44a24ce869f6495cac809b7e3770
```

No alternate ZIP, branch, commit, worktree, or source tree was searched or
used. All writes remained inside `JavaMathProofMesh-0.8.0`.

## Added and modified files

Build and infrastructure:

- `pom.xml`
- `mathproofmesh-server/pom.xml`
- `compose.yaml`
- `migration/image-lock.env`
- `migration/dependency-check-suppressions.xml`
- `mathproofmesh-server/src/main/resources/application-postgres.yaml`
- `mathproofmesh-server/src/test/resources/application-test.yaml`
- `mathproofmesh-server/src/main/resources/db/migration/V1__initial_schema.sql`

Production persistence package:

- `ArtifactMetadata.java`, `ArtifactMetadataSink.java`
- `ArtifactStore.java`, `ArtifactValidationException.java`
- `CheckpointRepository.java`
- `DomainEvent.java`, `EventLogRepository.java`
- `InboxRepository.java`, `OutboxRecord.java`, `OutboxRepository.java`
- `JdbcArtifactMetadataSink.java`
- `RunLease.java`, `RunLeaseRepository.java`, `LeaseConflictException.java`
- `RunRecord.java`, `RunRepository.java`, `OptimisticLockException.java`
- `TransactionalEventStore.java`
- `LegacyRunStorePort.java`
- `PersistenceException.java`, `PersistenceMetrics.java`, `package-info.java`

Tests and migration control:

- `PersistencePolicyTest.java`
- `PersistencePostgresIT.java`
- `StoreParityTest.java`
- `WorkingCheckpointAndMetricsParityTest.java`
- `scripts/update-phase04-mappings.py`
- `migration/source-state.csv`
- `migration/test-state.csv`
- `migration/dependency-lock.yaml`
- `migration/reports/phase-04-dependency-tree.txt`
- `migration/reports/phase-04-gates.json`
- `migration/reports/phase-04.md`
- `migration/state.json`

## Migrated Python sources and tests

| Python path | Java evidence | Result |
| --- | --- | --- |
| `src/mathproofmesh/store.py` | persistence package, artifact and PostgreSQL tests | migrated |
| `tests/test_store.py` | `StoreParityTest` | 4/4 semantics ported |
| `tests/test_working_checkpoint_and_metrics.py` | `WorkingCheckpointAndMetricsParityTest`, `PersistencePostgresIT` | 3/3 semantics ported |

There are no phase-04 auxiliary rows. The three inventories remain 142 source
rows, 167 test rows, and 92 auxiliary rows, covering 401 unique frozen paths.

## Design decisions

### PostgreSQL and Flyway

The server uses Spring JDBC/JdbcClient with PostgreSQL. JPA, Hibernate, and H2
are absent. Both `flyway-core` and `flyway-database-postgresql` are present at
12.4.0. `V1__initial_schema.sql` creates the 40 application tables specified
by chapter 10, including run-scoped indexes, state constraints, lowercase
64-character SHA-256 constraints, optimistic versions, leases, the
append-only event log, and outbox/inbox tables.

Applied Flyway migrations are immutable. A fresh PostgreSQL 18.4 container
migrated from an empty schema, and a second Flyway instance reported no
pending migration.

### Artifacts

`ArtifactStore` uses the exact
`artifacts/sha256/<first2>/<full_hash>` content-addressed layout. Writes use a
same-directory temporary file, force file data, atomically replace with a
bounded Windows sharing-violation retry, and force the parent directory where
the platform permits it. Reads and writes validate actual bytes rather than
trusting caller metadata.

Absolute paths, traversal, malformed hashes, symlinks, junctions/reparse
points, hash mismatch, size overflow, and quota overflow fail closed. Artifact
metadata records media type, size, source, retention policy, hash, and run
ownership.

### Transaction and concurrency semantics

`TransactionalEventStore` persists the domain event and outbox record in one
transaction. Outbox claims use `FOR UPDATE SKIP LOCKED`; failed claims become
available after their lease; inbox effects deduplicate on
`(consumer_name,event_id)`.

Run leases provide monotonically increasing fencing tokens. Concurrent
takeover has exactly one winner, and stale tokens are rejected. Run updates
also require the expected optimistic version. Database triggers reject update
and delete attempts on `event_log`. The legacy file store is represented only
by a read-only port.

All repository SQL is static and parameterized. Every run-owned query
explicitly carries `run_id`.

## Commands and results

```text
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts\check-original-immutable.ps1
PASS; 401 files; manifest SHA-256
9f3def2bec8cea99d0a18b51fbb5fa8ce53f44a24ce869f6495cac809b7e3770

docker compose --env-file migration/image-lock.env config --quiet
PASS; PostgreSQL image is a full immutable digest

.\mvnw.cmd -B -ntp -pl :mathproofmesh-server -am test
PASS; phase unit and policy tests

.\mvnw.cmd -B -ntp -pl :mathproofmesh-server -am verify
PASS; real PostgreSQL 18.4 Testcontainers integration

powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts\verify-all.ps1
PASS; online JDK 25 clean verify, CycloneDX, OWASP, and source check

powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts\verify-all.ps1 -Offline
PASS; offline JDK 25 clean verify and source check

.\mvnw.cmd -B -ntp -o -pl :mathproofmesh-server -am dependency:tree -Dverbose -DoutputFile=P:\migration\reports\phase-04-dependency-tree.txt
PASS

python scripts\update-phase04-mappings.py
PASS; 1 source and 2 test phase rows updated

docker ps --filter label=org.testcontainers=true
PASS; no remaining Testcontainers containers
```

The transient `P:` drive was used only to keep Windows build paths within tool
limits and was removed after every command.

## Test results

| Test area | Result |
| --- | ---: |
| Artifact parity and attack cases | 7 passed |
| Working checkpoint and metrics parity | 2 passed |
| Persistence policy/static SQL/schema inventory | 3 passed |
| Real PostgreSQL integration | 9 passed |
| Phase-04-specific total | 21 passed |
| Full reactor total | 703 passed |
| Failures / errors / skipped | 0 / 0 / 0 |
| Online clean verification | PASS |
| Offline clean verification | PASS |

The nine PostgreSQL integration cases cover an empty migration, restart
idempotency, all 40 tables, run isolation, optimistic versions, lease fencing,
concurrent takeover, crash rollback, outbox recovery, inbox deduplication,
append-only events, working checkpoint isolation, and artifact ownership.

## Dependency and security evidence

The resolved persistence/test stack is:

```text
org.springframework:spring-jdbc:7.0.8
org.flywaydb:flyway-core:12.4.0
org.flywaydb:flyway-database-postgresql:12.4.0
org.postgresql:postgresql:42.7.12
org.testcontainers:testcontainers-junit-jupiter:2.0.5
org.testcontainers:testcontainers-postgresql:2.0.5
net.java.dev.jna:jna-jpms:5.19.1
```

PostgreSQL and Ryuk ran only by these immutable references:

```text
postgres@sha256:1961f96e6029a02c3812d7cb329a3b03a3ac2bb067058dec17b0f5596aca9296
testcontainers/ryuk@sha256:7c1a8a9a47c780ed0f983770a662f80deb115d95cce3e2daa3d12115b8cd28f0
```

Dependency convergence, no-Snapshot, duplicate-class, and all five
SpotBugs/FindSecBugs reports passed with zero findings.

The deterministic CycloneDX 1.6 SBOM contains 88 components and 89 dependency
entries. Its SHA-256 is
`53fff79ac235a38abf4efb333e7a47783cdb37491f9d9900a6b7c79b11e52963`.

OWASP Dependency-Check 12.2.2 analyzed 112 dependencies and has zero
unsuppressed vulnerable dependencies and zero unsuppressed findings. The
updated July 2026 NVD data identified CVE-2026-54399 and CVE-2026-54428 in
HttpCore 5.3.6 shaded into docker-java's zerodep transport. That transport is
test scope only, absent from production artifacts, and communicates only with
the trusted local Docker named pipe. Two hash-specific suppression records
document this non-reachable threat model, owner `dependency/CI maintenance`,
and expiry `2026-10-31`. They must be removed earlier if a fixed
Testcontainers/docker-java transport becomes available.

## Failed attempts and fixes

1. The first Testcontainers run could not access Docker's Windows named pipe
   inside the filesystem sandbox. The same required test was rerun with the
   approved Docker permission and passed against real PostgreSQL; H2 was not
   substituted.
2. Before the target-local temporary-directory setting was applied, JUnit
   created seven temporary directories in the user temp directory. Their
   exact resolved paths were verified and removed. Surefire and Failsafe now
   direct all temporary files to `.cache/tmp` inside the target.
3. The first SpotBugs run found six path/null-handling issues in
   `ArtifactStore`. Containment and null proofs were made explicit, hash
   comparison became constant-time, and the one unavoidable path-taint report
   received a narrowly scoped source annotation with its validation proof.
   The final five reports contain zero findings.
4. One policy-test assertion initially inspected the wrong dependency scope.
   The assertion was corrected to inspect the resolved server test graph,
   without weakening the JPA/Hibernate/H2 prohibition.
5. Testcontainers initially selected mutable `testcontainers/ryuk:0.14.0`.
   Its actual pulled manifest was inspected, locked to the full digest above,
   and both online and offline runs proved that exact substitution.
6. The refreshed NVD database caused the first security scan to fail on two
   newly published HttpCore CVEs. The first hash-specific rule covered
   `httpcore5` but not the separately inventoried `httpcore5-h2` metadata.
   A second exact-hash rule was added. The final scan passed with no
   unsuppressed finding and records both failed attempts rather than hiding
   them.

## Performance and resource changes

No phase-04 throughput threshold is specified. Database state replaces
file-level authority and adds transaction, index, and lease costs. Artifact
payloads remain on content-addressed disk storage rather than in PostgreSQL.
Outbox claims are bounded and use `SKIP LOCKED` to avoid convoying. Artifact
size and per-store quota are enforced before durable publication.

Testcontainers and PostgreSQL are test-only resources. Ryuk removed all
ephemeral containers after each JVM; the final container inventory was empty.

## Gate checklist

- [x] PASS: phase 03 prerequisite is `passed`.
- [x] PASS: authoritative ZIP and 401-file source manifest match.
- [x] PASS: `flyway-core` and `flyway-database-postgresql` are present.
- [x] PASS: empty PostgreSQL 18.4 migration and restart idempotency pass.
- [x] PASS: all 40 chapter-10 application tables are present.
- [x] PASS: repositories use Spring JDBC and real PostgreSQL, not H2/JPA.
- [x] PASS: all run-owned state and queries enforce run isolation.
- [x] PASS: outbox crash/recovery and inbox duplicate cases pass.
- [x] PASS: lease contention, fencing, and optimistic-lock cases pass.
- [x] PASS: append-only event history rejects update and delete.
- [x] PASS: artifact traversal, symlink/reparse, hash, size, and quota cases pass.
- [x] PASS: no concatenated SQL exists.
- [x] PASS: online and offline JDK 25 Maven Wrapper clean verification pass.
- [x] PASS: dependency, SpotBugs/FindSecBugs, SBOM, and OWASP gates pass.
- [x] PASS: all three phase-04 mapping rows reached terminal verified state.
- [x] PASS: no Testcontainers containers remain.
- [x] PASS: phase 05 has not started.

## Residual issues

The Testcontainers Ryuk image-substitution environment variable is deprecated
upstream, although the final runs used the exact locked digest. Owner:
dependency/CI maintenance. Target: phase 17 or the next Testcontainers release.

The two test-only HttpCore CVE suppressions expire on 2026-10-31. They are
non-blocking only under the documented local trusted-Docker threat model.
Owner: dependency/CI maintenance.

The build logs retain upstream JDK deprecation warnings from protobuf
`Unsafe` usage and Mockito dynamic agent attachment. Owner: dependency/CI
maintenance. Target: phase 17.

## Evidence

- `migration/reports/phase-04-gates.json`
- `migration/reports/phase-04-dependency-tree.txt`
- `migration/reports/phase-01-sbom.json`
- `migration/reports/dependency-check/dependency-check-report.json`
- `migration/reports/dependency-check/dependency-check-report.html`
- `migration/dependency-check-suppressions.xml`
- `migration/dependency-lock.yaml`
- `migration/source-state.csv`
- `migration/test-state.csv`
- `migration/auxiliary-state.csv`

## Stop condition

Phase 04 is complete. At report capture time phase 05 had not started and no
phase-05 implementation or placeholder existed.
