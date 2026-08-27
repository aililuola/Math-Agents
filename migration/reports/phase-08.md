# Phase 08 Report

**Result:** PASS  
**Scope:** Computation engine, evidence gates, and restricted Python sidecar  
**Started:** 2026-07-30T16:17:29.227Z  
**Completed:** 2026-07-30T17:50:31.281Z  
**Git commit:** N/A (workspace is not a Git worktree)

## Authority

Phase 07 was `passed` before work began. The only source used was the locked
authority ZIP with SHA-256
`5e09eb8a704ea5f841078bafcc1c5e72e4d17a76b2fef7a344e41d55e013c1b2`.
The 401-file frozen manifest remained byte-for-byte unchanged with combined
SHA-256
`9f3def2bec8cea99d0a18b51fbb5fa8ce53f44a24ce869f6495cac809b7e3770`.
All writes remained inside `JavaMathProofMesh-0.8.0`.

## Implementation

The core computation package provides deterministic Java handlers for modular
exhaustion, bounded integer search, graph certificates, recurrence checks,
bounded greedy sequences, candidate-period checks, exact rational geometry,
and small deterministic number theory. Typed contracts validate inputs before
execution; computation budgets, canonical identities, run-isolated caching,
tamper-evident records, and evidence gates remain separate concerns.

`ComputationBroker` and `CriticalCalculationGate` preserve the distinction
between `certified`, `counterexample_found`, `not_refuted`, and
`inconclusive`. A bounded experiment cannot enter Fact, a finite enumeration
is exhaustive only when it covers the full declared finite domain, and every
counterexample is independently replayed before entering Negative.

`python-compute-service` is an independent, versioned JSON-RPC 2.0 JSONL
stdio process with five allowlisted SymPy/Z3 methods and no TCP listener. The
Java worker clears and allowlists environment variables, bounds request,
response and stderr sizes, validates IDs, schemas and certificates, and kills
the process tree on timeout. SymPy 1.14.0, Z3 4.16.0.0, mpmath 1.3.0, and
setuptools 83.0.0 are locked to exact Windows AMD64 wheel hashes and install
offline with `--require-hashes`.

Arbitrary program execution remains disabled by default. Enabling it requires
a digest-pinned image. The generated Docker invocation has no network,
read-only root, non-root UID/GID, all capabilities dropped, no-new-privileges,
and CPU, memory, PID, output and time bounds. The AST gate rejects file,
network, process, reflection, attribute/dunder and dynamic-execution access.

## Verification

```text
ComputationParityTest                       PASS; 25 tests
ComputationBenchmarkParityTest              PASS; 1 test
ComputationContractsParityTest              PASS; 6 tests
ComputationEvidenceGateParityTest           PASS; 2 tests
CriticalCalculationGateParityTest           PASS; 8 tests
DirectedComputationSmokeParityTest           PASS; 1 test
ReasoningFirstSequenceToolsParityTest        PASS; 10 tests
JsonAndToolsParityTest                       PASS; 3 tests
PythonSidecarProtocolTest                    PASS; 8 tests
PythonSidecarDifferentialTest                PASS; 4 tests
SandboxSecurityIT                            PASS; 4 tests
ReasoningFirstComputationBenchmarkTest       PASS; 1 test
sidecar offline --require-hashes install     PASS
scripts\verify-all.ps1                       PASS
scripts\verify-all.ps1 -Offline              PASS
scripts\check-original-immutable.ps1         PASS; 401 files
```

The phase adds 73 focused tests. The clean reactor ran 922 tests from 63 XML
reports with zero failures, errors, or skips. Online and offline runs used JDK
25, Maven Wrapper 3.3.4 only-script, PostgreSQL 18.4, and locked container
digests. No Testcontainers container remained.

Dependency convergence, release-only dependencies, duplicate-class checks,
Modulith structure, SpotBugs, and FindSecBugs passed. CycloneDX 1.6 contains
88 components and 89 dependency entries. OWASP Dependency-Check inspected 112
dependencies and found nothing at or above the CVSS 7.0 gate. Its one visible
below-gate finding is `CVE-2021-4277` in cron-utils 9.2.1 at CVSS 5.3.

## Mapping

All 19 phase-08 source rows are `migrated`; all 8 test rows, representing 56
Python test functions, are `ported`; and all 4 auxiliary rows are terminal
`translated_verified` or `reimplemented_verified`. Each retained legacy
benchmark, policy, and script copy matches the frozen source SHA-256.

## Failed Attempts

1. A number-theory parity case exposed that p-adic valuation validated the
   wrong precondition order. The exact guard now runs before primality work.
2. SpotBugs found locale-sensitive normalization, an avoidable JSON cast, and
   process-command taint paths. Locale-free matching and typed pattern
   matching removed the first two; narrowly scoped suppressions document the
   validated allowlisted process boundaries.
3. Modulith reported a computation/sidecar test-package cycle. The sandbox
   integration test moved to the sidecar package it actually exercises.
4. The sandbox integration run initially could not access the Docker named
   pipe inside the filesystem sandbox. The required local-container gate was
   rerun with explicit execution approval and passed.
5. The first attempt to tee a full log converted native stderr warnings into
   terminating PowerShell errors. `verify-all.ps1` gained an optional native
   transcript path, and both online and offline evidence runs then passed.

## Gate Checklist

- [x] Phase 07 prerequisite passed.
- [x] All required Java-native computation handlers pass parity tests.
- [x] Java and the frozen Python handler semantics pass differential tests.
- [x] JSON-RPC version, request ID, schema, bounds and certificates are checked.
- [x] Malformed, unknown, injected, crashed, timed-out and oversized cases fail closed.
- [x] The sidecar has no TCP listener and receives no API keys.
- [x] Sandbox execution is disabled by default and requires a pinned digest.
- [x] Network, filesystem, user, capability and resource isolation are explicit.
- [x] Bounded evidence cannot be promoted to proof or Fact.
- [x] Cache identity is canonical and run-isolated.
- [x] Online and offline JDK 25 Maven Wrapper verification pass.
- [x] All 31 phase-08 mapping rows have terminal verified status.
- [x] No Testcontainers container remains.

## Evidence

- `migration/reports/phase-08-gates.json`
- `migration/reports/phase-08-differential.json`
- `migration/reports/phase-08-dependency-tree.txt`
- `migration/logs/phase-08-verify.log`
- `migration/logs/phase-08-verify-offline.log`
- `migration/reports/phase-01-sbom.json`
- `migration/reports/dependency-check/dependency-check-report.json`
- `python-compute-service/requirements.lock`
- `python-compute-service/build-requirements.lock`
- `migration/source-state.csv`
- `migration/test-state.csv`
- `migration/auxiliary-state.csv`

## Stop Condition

Phase 08 passed every gate. Phase 09 had not started when this report and its
gate evidence were captured.
