# Phase 07 Report

**Result:** PASS  
**Scope:** Agent runtime, providers, budget, rate limiting, and call ledger  
**Started:** 2026-07-30T14:51:30.447Z  
**Completed:** 2026-07-30T16:17:29.227Z  
**Git commit:** N/A (workspace is not a Git worktree)

## Authority

Phase 06 was `passed` before work began. The only source used was the locked
authority ZIP with SHA-256
`5e09eb8a704ea5f841078bafcc1c5e72e4d17a76b2fef7a344e41d55e013c1b2`.
The 401-file frozen manifest remained byte-for-byte unchanged with combined
SHA-256
`9f3def2bec8cea99d0a18b51fbb5fa8ce53f44a24ce869f6495cac809b7e3770`.
All writes remained inside `JavaMathProofMesh-0.8.0`.

## Implementation

`LlmProvider` now has fixture-verified DeepSeek, Anthropic, Gemini,
OpenAI-compatible, and deterministic Mock implementations. The production
transport uses JDK 25 `HttpClient`, Jackson, and a project-owned bounded SSE
parser. Each adapter maps its endpoint, authentication, request schema,
streaming protocol, usage, reasoning metadata, request ID, and error model.
No Spring AI dependency was introduced, and Maven Enforcer rejects it.

`AgentRuntime` uses virtual-thread I/O with fair bounded semaphores, a
sliding-window RPM limiter, Retry-After-aware bounded retry, credential-safe
failover, and a persistent circuit breaker. Authentication failures never
retry the same credential. `AgentPool` selects deterministically by role,
expertise, provider, load, and trust while excluding authors from review.

`StructuredAgentRunner` saves a redacted prompt artifact, reserves budget,
plans the call, dispatches, stores the raw response artifact, extracts the
first balanced JSON object, performs strict contract parsing, and permits
only bounded representation repair. Unknown remote outcomes become
`ambiguous` and reserve possible duplicate cost rather than claiming
external exactly-once delivery.

Flyway `V4__provider_call_runtime.sql`, `JdbcProviderCallRepository`, and
`JdbcCircuitStateStore` persist the full call state machine, immutable
request identity, usage/cost/latency, idempotent result application, and
circuit snapshots. Usage totals can be reconstructed from the call ledger.

## Verification

```text
ProviderAdapterFixtureTest                   PASS; 7 tests
BoundedSseParserTest                         PASS; 5 tests
DeepseekParityTest                           PASS; 11 tests
AgentRuntimePolicyTest                       PASS; 6 tests
PoolParityTest                               PASS; 3 tests
ReviewIsolationParityTest                    PASS; 1 test
StructuredAgentRunnerTest                    PASS; 6 tests
GuardsAndContextParityTest                   PASS; 7 tests
TypedPromptSerializationParityTest           PASS; 4 tests
ProviderCallPostgresIT                       PASS; 3 tests
scripts\verify-all.ps1                       PASS
scripts\verify-all.ps1 -Offline              PASS
scripts\check-original-immutable.ps1         PASS; 401 files
```

The clean reactor ran 849 tests from 51 XML reports with zero failures,
errors, or skips. Both online and offline runs used JDK 25, the Maven Wrapper,
PostgreSQL 18.4, and locked Postgres/Ryuk image digests. No Testcontainers
container remained.

Dependency convergence, release-only dependencies, duplicate-class checks,
Modulith structure, static SQL policy, SpotBugs, and FindSecBugs passed.
CycloneDX 1.6 contains 88 components and 89 dependency entries. OWASP
Dependency-Check inspected 112 dependencies and found nothing at or above
the CVSS 7.0 gate. It reports one unsuppressed medium finding,
`CVE-2021-4277` in cron-utils 9.2.1 at CVSS 5.3.

The scan downloads public NVD/KEV data only. Analyzers that submit project
coordinates were disabled. Two exact package/CPE rules suppress false
identification of Flyway and Testcontainers Java modules as PostgreSQL
servers; the existing two test-only HttpCore CVE suppressions retain their
owner and expiry.

## Mapping

All 10 phase-07 source rows are `migrated`, all 4 test rows are `ported`, and
both auxiliary rows are `translated_verified`. Legacy DeepSeek and prompt
protocol documents remain byte-for-byte under `docs/legacy/python-baseline`,
with Java authority guidance in `docs/providers.md`.

## Failed Attempts

1. Clean SpotBugs initially reported 13 findings. Mutable inputs were copied,
   prompt text was made line-ending stable, ASCII header handling was made
   explicit, and only narrowly justified suppressions remained.
2. Modulith verification exposed an agent/provider cycle. Shared failure and
   usage value types moved to the provider-owned boundary, and the review
   isolation test moved to the package it verifies.
3. The static persistence gate rejected concatenated SQL. All provider-call
   query variants are now immutable static SQL constants.
4. The first security rerun failed while downloading RetireJS and hosted
   suppressions. JavaScript analyzers were removed from this Java-only build,
   hosted suppressions were replaced by the audited local file, and
   dependency-coordinate submission analyzers were disabled.
5. The refreshed NVD data then exposed false PostgreSQL-server CPE matches
   on the Flyway and Testcontainers Java modules. Exact package URL plus CPE
   suppression rules remove only those false associations. The real medium
   cron-utils finding remains visible below the configured gate.

## Gate Checklist

- [x] Phase 06 prerequisite passed.
- [x] Five Provider adapters pass recorded protocol fixtures.
- [x] SSE fragmentation, tail, timeout, size, cancellation, and reconnect pass.
- [x] Budget, fair bounded concurrency, RPM, failover, and circuit tests pass.
- [x] Retry-After is honored and 401/403 never retry the same key.
- [x] Reviewer selection excludes every winning-chain author.
- [x] Call state, ambiguity, usage reconciliation, and apply-once persist.
- [x] Prompt and reasoning artifacts are redacted; repair cannot alter math.
- [x] Spring AI dependency count and paid/live provider call count are zero.
- [x] Online and offline JDK 25 Maven Wrapper verification pass.
- [x] All 16 phase-07 mapping rows have terminal verified status.
- [x] No Testcontainers container remains.

## Evidence

- `migration/reports/phase-07-gates.json`
- `migration/reports/phase-07-dependency-tree.txt`
- `migration/logs/phase-07-verify.log`
- `migration/logs/phase-07-verify-offline.log`
- `migration/logs/phase-07-source-immutability.log`
- `migration/reports/phase-01-sbom.json`
- `migration/reports/dependency-check/dependency-check-report.json`
- `migration/source-state.csv`
- `migration/test-state.csv`
- `migration/auxiliary-state.csv`

## Stop Condition

Phase 07 passed every gate. Phase 08 had not started when this report and its
gate evidence were captured.
