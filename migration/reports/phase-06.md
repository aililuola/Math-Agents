# Phase 06 Report

**Result:** PASS  
**Scope:** Three-tier memory and proof obligation graph  
**Started:** 2026-07-30T14:13:18.249Z  
**Completed:** 2026-07-30T14:51:30.447Z  
**Git commit:** N/A (workspace is not a Git worktree)

## Authority

Phase 05 was `passed` before work began. The only source used was the locked
authority ZIP with SHA-256
`5e09eb8a704ea5f841078bafcc1c5e72e4d17a76b2fef7a344e41d55e013c1b2`.
The 401-file frozen manifest remained byte-for-byte unchanged with combined
SHA-256
`9f3def2bec8cea99d0a18b51fbb5fa8ce53f44a24ce869f6495cac809b7e3770`.
All writes remained inside `JavaMathProofMesh-0.8.0`.

## Implementation

`LemmaMemory`, `TypedMemory`, `MemoryPromotionPolicy`, and
`MemoryInvalidationService` implement the baseline Fact/Insight/Negative
rules. Missing dependencies, author self-review, finite or numerical evidence,
and duplicate provenance cannot promote a Fact. Counterexamples retain
history while demoting the complete dependent closure.

`ProofGraphStore` provides cycle-safe JGraphT projection, deterministic
topological order, dependency closure, proof debt, shared bottlenecks,
conflicts, duplicate mechanisms, bridge handling, minimal subgraphs, freeze,
reopen, and version audit. PostgreSQL remains authoritative; no Neo4j
dependency was introduced.

Flyway `V3__memory_and_proof_graph_authority.sql` and
`JdbcMemoryProofGraphRepository` persist graph state and execute
counterexample propagation atomically: store Negative, invalidate memory,
recursively mark and reopen obligations, then append the domain event and
outbox record. Propagation is idempotent and serialized by the run lock.
Projection reload uses exactly four bulk queries and has no per-node
full-table query.

## Verification

```text
LemmaMemoryParityTest                         PASS; 6 tests
TypedMemoryParityTest                         PASS; 9 tests
ProofGraphParityTest                          PASS; 8 tests
ProofGraphServicesTest                        PASS; 5 tests
ProofGraphConcurrencyTest                     PASS; 2 tests
MemoryProofGraphPostgresIT                    PASS; 4 tests
MemoryProofGraphPolicyTest                    PASS; 3 tests
scripts\verify-all.ps1                        PASS
scripts\verify-all.ps1 -Offline               PASS
scripts\check-original-immutable.ps1          PASS; 401 files
```

The clean reactor ran 794 tests from 41 XML reports with zero failures,
errors, or skips. PostgreSQL 18.4, the locked Postgres and Ryuk image digests,
Flyway restart behavior, transaction rollback, concurrent replay, and
Testcontainers cleanup passed.

Dependency convergence, release-only dependencies, duplicate-class checks,
SpotBugs, and FindSecBugs passed with zero findings. CycloneDX 1.6 contains
88 components and 89 dependency entries. OWASP Dependency-Check inspected
112 dependencies with no unsuppressed vulnerable dependency or finding.

## Mapping

All 7 phase-06 source rows are `migrated`, all 3 test rows are `ported`, and
both auxiliary rows are `translated_verified`. The original proof-graph and
typed-memory documents are retained byte-for-byte under
`docs/legacy/python-baseline`, with Java authority documentation in
`docs/proof-graph.md` and `docs/memory.md`.

## Failed Attempts

1. The first focused verify exposed missing AssertJ test scope in the core
   module; the direct test dependency was added without changing its locked
   version.
2. The first PostgreSQL propagation test showed that the newly stored
   counterexample participated in its own dependency closure. Recursive
   traversal now excludes Negative memory and the counterexample identity.
3. Clean SpotBugs runs identified unsafe-monitor, defensive-copy, mutable
   projection, and integrity-hash comparison findings. Intentional ownership
   is documented at the narrow types, immutable snapshots are defensively
   copied, and hash comparison is constant-time.
4. Two online verification attempts found overly strict names in the new
   migration-policy test and one unnecessary SpotBugs suppression. Both were
   corrected; the final online and offline clean runs pass in full.

## Gate Checklist

- [x] Phase 05 prerequisite passed.
- [x] Fact/Insight/Negative promotion and demotion match the baseline.
- [x] Missing dependency, self-review, and finite experiment Fact attempts fail.
- [x] Counterexample invalidation is transactional and replay-idempotent.
- [x] Cycle, closure, conflict, debt, bottleneck, freeze, and reopen tests pass.
- [x] Concurrent mutation and rollback fault injection pass.
- [x] PostgreSQL is authoritative and JGraphT is a four-query projection.
- [x] No Neo4j dependency or N+1 graph reload exists.
- [x] Online and offline JDK 25 Maven Wrapper verification pass.
- [x] All 12 phase-06 mapping rows have terminal verified status.
- [x] No Testcontainers container remains.

## Evidence

- `migration/reports/phase-06-gates.json`
- `migration/reports/phase-06-dependency-tree.txt`
- `migration/reports/phase-01-sbom.json`
- `migration/reports/dependency-check/dependency-check-report.json`
- `migration/source-state.csv`
- `migration/test-state.csv`
- `migration/auxiliary-state.csv`

## Stop Condition

Phase 06 passed every gate. Phase 07 had not started when this report and its
gate evidence were captured.
