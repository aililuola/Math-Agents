# Phase 14 Report

**Result:** PASS  
**Scope:** REST API, resumable SSE, CLI, reports, and observability  
**Started:** 2026-07-31T03:36:45.225Z  
**Completed:** 2026-07-31T04:22:48.247Z  
**Git commit:** N/A (workspace is not a Git worktree)

## Authority

Phase 13 was `passed` before work began. The only source used was the locked
authority ZIP with SHA-256
`5e09eb8a704ea5f841078bafcc1c5e72e4d17a76b2fef7a344e41d55e013c1b2`.
The frozen 401-file manifest remained unchanged at
`9f3def2bec8cea99d0a18b51fbb5fa8ce53f44a24ce869f6495cac809b7e3770`.
All writes remained inside `JavaMathProofMesh-0.8.0`.

## Implementation

The server exposes loopback-bound health, solve, resume, resumable SSE, run
status, activity, route, proof-graph, and content-addressed artifact endpoints.
Every endpoint except health requires a configured bearer token. CORS is off,
unknown and provider-override fields are rejected, input sizes are bounded,
concurrency overflow returns 429, and query endpoints do not mutate run state.

SSE events use monotonic IDs and honor `Last-Event-ID`. Activity, heartbeat,
result, and error events retain exact lifecycle semantics; terminal events are
not duplicated. Activity text, metrics, trace records, HTTP errors, and logs
redact secrets and private reasoning. Artifact reads enforce hash, size, and
content-addressing checks.

The picocli root provides `solve`, `resume`, `demo`, `probe`, and `serve`.
Mock demo behavior, hierarchical reports, run reports, and progress labels
retain the Python authority semantics. Metrics use bounded tag values, trace
IDs propagate through requests, and management health/metrics remain
loopback-only.

## Verification

```text
4 mapped parity test classes                       PASS
12 authority-named Python function cases           PASS
7 API security/streaming/CLI/observability gates   PASS
scripts\verify-all.ps1                             PASS
scripts\verify-all.ps1 -Offline                    PASS
scripts\check-original-immutable.ps1               PASS; 401 files
```

The clean reactor ran 1,401 tests from 175 XML reports with zero failures,
errors, or skips. Online and offline runs used JDK 25 and Maven Wrapper 3.3.4
only-script. Dependency convergence, release-only dependencies,
duplicate-class checks, Modulith structure, SpotBugs, and FindSecBugs all
passed.

CycloneDX 1.6 contains 111 components and 112 dependency entries. OWASP
Dependency-Check inspected 115 dependencies and found nothing at or above the
CVSS 7.0 gate. The visible below-gate finding remains `CVE-2021-4277` in
cron-utils 9.2.1 at CVSS 5.3.

Spring Web and Actuator were added at the locked Spring Boot 4.1.0 version.
Embedded Tomcat was explicitly advanced from 11.0.22 to 11.0.24; the final
dependency tree contains only 11.0.24 Tomcat runtime artifacts.

## Mapping

All 6 phase-14 source rows are `migrated`. All 4 test rows, representing 12
Python test functions, are `ported`. Both auxiliary rows are
`translated_verified`. `ACTIVITY_TIMELINE.md` and `examples/problem.txt`
remain byte-exact at their authority SHA-256 values, while current operations
semantics are consolidated in `docs/observability.md`.

## Gate Checklist

- [x] Phase 13 prerequisite passed.
- [x] Loopback, bearer authentication, CORS, bounds, and 429 policy pass.
- [x] Solve, resume, status, activity, route, graph, and artifact APIs pass.
- [x] SSE IDs, reconnect, heartbeat, redaction, and terminal delivery pass.
- [x] Provider overrides and unknown request fields are rejected.
- [x] Run query endpoints are read-only.
- [x] All five picocli commands and Mock demo behavior pass.
- [x] Reports distinguish verified claims from incomplete progress.
- [x] Metrics, trace correlation, bounded tags, and structured logs pass.
- [x] All 12 phase-14 mapping rows have terminal verified status.
- [x] Online and offline JDK 25 Maven Wrapper verification pass.

## Evidence

- `migration/reports/phase-14-gates.json`
- `migration/reports/phase-14-dependency-tree.txt`
- `migration/reports/phase-14-verify-online.log`
- `migration/reports/phase-14-verify-offline.log`
- `migration/reports/phase-01-sbom.json`
- `migration/reports/dependency-check/dependency-check-report.json`
- `migration/source-state.csv`
- `migration/test-state.csv`
- `migration/auxiliary-state.csv`

## Stop Condition

Phase 14 passed every gate. Phase 15 was not started before this report and its
gate evidence were captured.
