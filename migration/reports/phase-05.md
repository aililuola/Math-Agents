# Phase 05 Report

**Result:** PASS  
**Scope:** Typed message broker, sparse routing, receipts, utility, and exactly-once delivery  
**Started:** 2026-07-30T13:31:54.326Z  
**Completed:** 2026-07-30T14:13:18.249Z  
**Git commit:** N/A (workspace is not a Git worktree)

## Prerequisite and source authority

Phase 04 was `passed` before phase 05 started. The only authoritative Python
source remains the locked migration ZIP:

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

## Implementation

The core communication package now owns strict typed admission, ordered
rejection reasons, delivery state, prompt consumption, authenticated receipts,
verified downstream utility, route registration, semantic deduplication, TTL,
priority, and bounded isolation. The four states `delivered`,
`prompt_consumed`, `acknowledged`, and `actually_used` remain distinct.

Delivery uniqueness is keyed by `(run_id, recipient_id, stage, message_id)`.
Both in-memory and PostgreSQL repositories make delivery and domain effects
idempotent. Resume cannot re-emit a prompt-consumed delivery. Invalidation
archives the prior delivery and its audit history, removes active receipt and
utility state transactionally, and permits an explicit republication.

`SparseTopologyRouter` implements deterministic SHA-256 embeddings, cosine and
math-aware similarity, typed strategy selection, relevant-claim selection,
and bounded sparse neighbor selection without any provider call. The Java
benchmark validates six fixture cases and eleven variants and emits JSON and
Markdown reports with `provider_calls=0`.

Flyway migration `V2__typed_message_broker.sql` adds the durable typed-message
tables and constraints. Real PostgreSQL tests cover restart, exactly-once
consumption, audited invalidation, and republication.

## Migrated inventories

All seven phase-05 Python source rows are `migrated`, all thirteen test rows
are `ported`, and all eleven auxiliary rows are `translated_verified`.
Byte-exact topology fixtures, benchmark runner, and legacy communication
documents retain their authoritative SHA-256 values. Java-facing operational
documentation lives in `docs/communication.md` and
`docs/benchmarks/topology/README.md`.

The inventory remains 142 source rows, 167 test rows, and 92 auxiliary rows,
covering 401 unique frozen paths.

## Verification

```text
scripts\check-original-immutable.ps1
PASS; 401 files; frozen manifest SHA-256 matched

.\mvnw.cmd -B -ntp -pl :mathproofmesh-core -am test
PASS; 45 communication and topology tests

.\mvnw.cmd -B -ntp -pl :mathproofmesh-server -am verify
PASS; 4 JdbcMessageRepositoryIT cases against PostgreSQL 18.4

.\mvnw.cmd -B -ntp -pl :mathproofmesh-compatibility -am
  -Dtest=TopologyBenchmarkTest test
PASS; 6 cases, 11 variants, 0 provider calls

scripts\verify-all.ps1
PASS; online clean verify, SBOM, OWASP, and source immutability

scripts\verify-all.ps1 -Offline
PASS; offline clean verify and source immutability

.\mvnw.cmd -B -ntp -o -pl :mathproofmesh-server -am
  dependency:tree -Dverbose
PASS; migration/reports/phase-05-dependency-tree.txt

docker ps --filter label=org.testcontainers=true
PASS; no remaining containers
```

The full reactor ran 755 tests from 34 XML reports with zero failures, errors,
or skips in both online and offline verification. Phase-specific coverage is
15 admission tests, 17 delivery/receipt tests, 5 route-registry tests, 8
sparse-topology tests, 4 PostgreSQL message-repository tests, and 1 benchmark
test.

All modules pass dependency convergence, release-only dependency, duplicate
class, SpotBugs, and FindSecBugs gates. CycloneDX 1.6 contains 88 components
and 89 dependency entries. OWASP Dependency-Check 12.2.2 inspected 112
dependencies with no unsuppressed vulnerable dependency or finding. The two
documented test-only HttpCore suppressions remain scoped to the trusted local
Docker transport and expire on 2026-10-31.

## Failed attempts and fixes

1. An initial bounded-isolation test used two semantically identical facts, so
   the intended second delivery was correctly deduplicated. The fixture was
   corrected to use distinct insights; production behavior was not weakened.
2. SpotBugs rejected locale-sensitive Unicode lowercasing in topology
   normalization. The implementation now uses explicit ASCII folding while
   retaining code-point-aware math symbol handling; the final report has zero
   findings.
3. The first full verification invocation found the transient `P:` drive still
   mapped from a prior command. The verified target mapping was released and
   the complete gate was rerun successfully.
4. An early focused Failsafe invocation inherited
   `failIfNoSpecifiedTests=true` in parent modules. It was rerun with the
   standard parent-module override; no test was skipped from the final reactor.

## Gate checklist

- [x] Phase 04 prerequisite is `passed`.
- [x] Authority ZIP and frozen 401-file manifest match.
- [x] Admission order and all fourteen rejection reasons match the baseline.
- [x] Delivery, prompt consumption, acknowledgment, and actual use are distinct.
- [x] Resume never re-emits prompt-consumed delivery.
- [x] Message delivery and domain effects are idempotent across restart.
- [x] Receipts are authenticated and utility requires verified downstream effect.
- [x] Invalidation is transactional, audited, and permits explicit republication.
- [x] Sparse topology, math similarity, and route limits pass deterministic tests.
- [x] The fixture benchmark covers 6 cases and 11 variants with zero provider calls.
- [x] PostgreSQL 18.4 and both immutable container digests pass.
- [x] Online and offline JDK 25 Maven Wrapper verification pass.
- [x] Dependency, static analysis, SBOM, and OWASP gates pass.
- [x] All 31 phase-05 mapping rows reached terminal verified state.
- [x] No Testcontainers container remains.
- [x] Phase 06 had not started when this report was captured.

## Evidence

- `migration/reports/phase-05-gates.json`
- `migration/reports/phase-05-dependency-tree.txt`
- `migration/reports/phase-01-sbom.json`
- `migration/reports/dependency-check/dependency-check-report.json`
- `target/benchmark-reports/topology-java.json`
- `target/benchmark-reports/topology-java.md`
- `migration/source-state.csv`
- `migration/test-state.csv`
- `migration/auxiliary-state.csv`

## Stop condition

Phase 05 passed every gate. Phase 06 may now begin; no phase-06 implementation
was included in this phase.
