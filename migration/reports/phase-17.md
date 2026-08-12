# Phase 17 Report

**Result:** PASS  
**Scope:** Hardening, coverage, performance, release packaging, and final acceptance  
**Started:** 2026-07-31T06:31:16.918Z  
**Completed:** 2026-07-31T09:21:42.235Z  
**Git commit:** N/A (workspace is not a Git worktree)

## Authority

Phase 16 was `passed` before phase 17 began. The only Python authority was
`migration/input/Math-Agents-feature-mathproofmesh-v0.8.0-goal-plan-failure-utility-control.zip`
with SHA-256
`5e09eb8a704ea5f841078bafcc1c5e72e4d17a76b2fef7a344e41d55e013c1b2`.
The frozen 401-file manifest remained byte-exact at
`9f3def2bec8cea99d0a18b51fbb5fa8ce53f44a24ce869f6495cac809b7e3770`.
All writes remained under `JavaMathProofMesh-0.8.0`.

## Full Matrix

The final clean reactor executed 1,934 Java tests across 224 XML suites:

```text
mathproofmesh-contracts        39
mathproofmesh-core            902
mathproofmesh-server          825
mathproofmesh-desktop          19
mathproofmesh-compatibility   149
failures / errors / skipped     0 / 0 / 0
```

The matrix includes unit, contract, property, and parameterized suites;
PostgreSQL Testcontainers; Temporal TestWorkflowEnvironment; Mock and SSE
provider adapters; the Python sidecar; REST, resumable SSE, CLI, and reports;
JavaFX smoke and clean-start health; legacy import and resume; and failure,
injection, timeout, quota, and recovery scenarios.

Both final `clean verify` executions passed. The online run regenerated the
CycloneDX SBOM, OWASP report, security report, coverage report, and the
same-machine performance comparison. The offline run resolved only locked
local artifacts and reused the generated security and performance evidence.

## Security

- OWASP Dependency-Check 12.2.2 scanned 115 dependencies. The final online
  rescan reports zero unsuppressed findings, including zero at or above the
  CVSS 7.0 blocking threshold.
- The former `CVE-2021-4277` result on test-scope cron-utils 9.2.1 was an
  erroneous `utils_project:utils` CPE association. NVD assigns that CVE to
  fredsmith/utils `screenshot_sync`, not the Java cron-utils artifact. An
  exact package-URL plus erroneous-CPE suppression now removes only that
  false positive and expires on 2027-01-31 for mandatory review.
- SpotBugs and FindSecBugs reported zero findings in all five modules.
- CycloneDX 1.6 lists 111 components. All 111 have reviewed licenses; there
  are no missing or unreviewed licenses.
- The high-confidence secret scan covered 1,020 active files and found no
  private key or provider token.
- SSRF, SQL and path injection, traversal, Zip Slip, unsafe parsing, SSE and
  log injection, prompt/tool injection, sidecar process bounds, concurrency,
  timeout, and resource-exhaustion suites passed.
- Five Dependency-Check suppressions retain CVE or explicit N/A scope,
  reason, owner, review instruction, and an unexpired review date.
- Development services bind to loopback. Production operations require
  TLS/mTLS, authentication, least privilege, and tested backup/restore.

## Coverage

The conservative JaCoCo gate report records:

```text
contracts adjusted line / branch       95.099541% / 87.420814%
core overall line / branch             91.062632% / 75.100461%
audited core invariants line / branch  94.431555% / 86.145765%
server line                            87.709665%
desktop line                           70.380623%
critical scenarios                    100%
```

Only explicitly marked generated defensive-accessor blocks are excluded from
the contracts branch calculation. The audited invariant class list is exact
and recorded in `phase-17-coverage.json`. Hashing, message admission and
exactly-once delivery, typed memory, counterexample propagation, checkpoint
CAS, lease, outbox/inbox, Proof Control actions, Temporal decisions, provider
recovery, and REST/SSE/CLI resume all have executed zero-skip critical suites.

## Performance

The first passing run established a machine-specific reference on Windows 11,
20 logical CPUs, 34,188,517,376 bytes of physical memory, and Eclipse Temurin
25.0.4+7 with JaCoCo enabled. The final comparison passed all seven scenarios:

```text
10,000 message admission/dedup/delivery       ratio 1.016586
100 concurrent Mock agent calls              ratio 0.951206
large Proof Graph                            ratio 1.045738
1,000 Checkpoint/Outbox retry                ratio 0.132453
Python sidecar cold/warm calls                ratio 1.019167
10,000-event SSE stream and resume            ratio 0.865969
Temporal multi-route/replay/Continue-As-New   ratio 0.972832
```

Every ratio is below the 1.20 same-machine limit. The database benchmark uses
a bounded reusable connection rather than creating thousands of short-lived
connections, eliminating ephemeral-port exhaustion while preserving all
1,000 Checkpoint and two-attempt Outbox assertions.

## Release

All six active POMs use version `0.8.0`; none contains `SNAPSHOT`. The release
contains:

- executable server and CLI Spring Boot jars plus Windows and POSIX launchers;
- Python sidecar source, runtime and build lock files;
- four Flyway SQL migrations;
- locked PostgreSQL, Temporal, and Testcontainers/Ryuk image configuration;
- Docker Compose, application profiles, examples, operations documentation,
  migration maps, reports, and internal SHA-256 checksums;
- Windows x64 portable desktop ZIP and EXE installer, generated by JDK 25
  `jlink`/`jpackage` and target-local WiX 5.0.2;
- the outer `JavaMathProofMesh-0.8.0.zip` and its SHA-256 checksum.

Desktop app-image health returned version 0.8.0. The packaged CLI completed a
provider-free Mock solve, and the legacy demonstration returned terminal runs
without provider calls while resuming an active 0.7 run at its latest committed
checkpoint.

## Mapping Closure

```text
source-state.csv       142/142 migrated
test-state.csv         167/167: 149 ported, 18 differential
auxiliary-state.csv      92/92: 49 translated_verified,
                                6 reimplemented_verified,
                                5 verified, 32 copied_verified
unique frozen paths     401/401
pending rows                  0
```

Each path occurs in exactly one state table. Their union equals the frozen
401-path manifest exactly.

## Final Python Gate

The final authority run passed all 759 Python tests. A separate immutable
source check then rehashed all 401 files and reproduced the locked manifest
SHA-256. Neither operation wrote to the outer Python project or authority
snapshot.

## Remediation Record

The first final offline attempt exposed a narrow nondeterministic coverage edge
at 74.996279% core branch coverage. Two residual branch tests lifted the stable
clean result to 75.100461%. A later repeated online run exposed ephemeral-port
exhaustion in the 1,000-row database benchmark. The benchmark now reuses one
transaction-aware connection and passed targeted, final online, and final
offline execution. These attempts are summarized in
`migration/audit/phase-17-remediation-attempts.md`; neither was treated as a
final result.

After final acceptance, the remaining medium Dependency-Check result was
reviewed against the NVD product and CPE record. It was confirmed as a
cross-ecosystem false positive, precisely suppressed for only
`pkg:maven/com.cronutils/cron-utils@9.2.1` plus
`cpe:/a:utils_project:utils`, and verified by a fresh online aggregate scan.
The sanitized report and security gate now record 115 dependencies and zero
unsuppressed findings. No runtime dependency or desktop artifact changed.

A later installed-desktop run exposed a closed event-vocabulary mismatch: the
live backend emitted four legitimate sandbox/computation/failure event types
that the API event record rejected. The vocabulary and regression coverage
were corrected, and the workbench now distinguishes application `error`
messages from EventSource transport failures. The full six-module Maven verify,
security check, 401-file authority check, rebuilt package health check, and
installed-program health check all passed. The regenerated 0.8.0 package was
installed in place without changing credentials or run data. Full evidence is
in `migration/audit/desktop-run-event-remediation-2026-07-31.md`.

## Gate Checklist

- [x] Phase 16 prerequisite passed.
- [x] Online and offline full verification pass on the final code state.
- [x] Full functional, persistence, Temporal, sidecar, API, desktop, legacy,
      failure, security, and resource matrix passes with no skips.
- [x] Coverage and 100% critical-scenario gates pass.
- [x] All seven same-machine performance comparisons are within 20%.
- [x] OWASP, SpotBugs, FindSecBugs, SBOM, license, and secret gates pass.
- [x] Version 0.8.0 contains no active `SNAPSHOT`.
- [x] Server, CLI, desktop, sidecar, SQL, Compose, docs, and checksums ship.
- [x] README packaged Mock solve and old-run resume demonstrations pass.
- [x] Final Python 759-test baseline and 401-file immutability pass.
- [x] No required incomplete-code marker or disabled test remains.
- [x] The three migration maps close the exact 401-file authority set.

## Evidence

- `migration/reports/phase-17-gates.json`
- `migration/reports/phase-17-verify-online.log`
- `migration/reports/phase-17-verify-offline.log`
- `migration/reports/phase-17-coverage.json`
- `migration/reports/phase-17-security.json`
- `migration/reports/phase-17-licenses.json`
- `migration/reports/phase-17-sbom.json`
- `migration/reports/dependency-check/dependency-check-report.json`
- `migration/reports/phase-17-performance.json`
- `migration/baseline/phase-17-performance-reference.json`
- `migration/reports/phase-17-demonstrations.json`
- `migration/reports/phase-17-python-baseline.json`
- `migration/reports/phase-17-dependency-tree.txt`
- `migration/audit/desktop-run-event-remediation-2026-07-31.md`
- `target/desktop-dist/SHA256SUMS.txt`
- `target/release/SHA256SUMS.txt`
- `MIGRATION_COMPLETION_REPORT.md`

## Stop Condition

Phase 17 is the final phase. No phase 18 exists or was started.
