# Phase 16 Report

**Result:** PASS  
**Scope:** Legacy Python run import, version migration, resume, and shadow parity  
**Started:** 2026-07-31T05:38:42.117Z  
**Completed:** 2026-07-31T06:31:16.918Z  
**Git commit:** N/A (workspace is not a Git worktree)

## Authority

Phase 15 was `passed` before work began. The only authority was the locked ZIP
with SHA-256
`5e09eb8a704ea5f841078bafcc1c5e72e4d17a76b2fef7a344e41d55e013c1b2`.
The frozen 401-file manifest remained byte-exact at
`9f3def2bec8cea99d0a18b51fbb5fa8ce53f44a24ce869f6495cac809b7e3770`.
All writes remained under `JavaMathProofMesh-0.8.0`.

## Importer

`LegacyRunImporter` reads a closed legacy directory without modifying it. It
rejects root or child links, reparse/special files, path escapes, external
references, duplicate JSON keys, invalid JSONL, and configured file/count/size
limits. Two complete input passes must produce identical per-file hashes.

The canonical sorted file manifest is the unique import identity. Problem,
artifact, and checkpoint hashes and sizes are checked before staging.
Checkpoint parents, cycles, and the latest committed pointer are validated.
The ordered migrator applies 0.7, 0.8.0 sidecar, 0.8.1 exactly-once, and 0.8.2
checkpoint/dependency semantics. Legacy dependencies use an isolated
namespace. Unverified Fact claims and legacy claim/receipt bypasses are
quarantined and cannot be resurrected.

Duplicate imports return the same target identity. Terminal runs resume with
zero provider calls. Nonterminal runs resume only from their latest committed
checkpoint.

## Shadow Parity

The comparator requires and compares:

```text
problem_contract  strategies  messages  deliveries  memory
proof_graph       checkpoints recovery  usage       final_state
```

Only explicitly declared natural-language JSON pointers may differ.
Identity, hash, status, state, checkpoint, dependency, and receipt differences
cannot be waived. Difference reports contain hashes rather than private raw
content.

The 98 authority-named cases cover every phase-16 mapped Python test
function. `PythonJavaShadowDifferentialTest` also executes the target-local
Python oracle and Java comparator on the same deterministic Mock snapshot.
There were zero critical or unexplained differences and zero live provider
calls.

## Verification

```text
LegacyRunImporterTest                              20 PASS
ShadowComparatorTest                               10 PASS
Phase16AuthorityParityTest                         98 PASS
PythonJavaShadowDifferentialTest                     1 PASS
mathproofmesh-compatibility total                  148 PASS
full reactor                                     1,549 PASS
scripts/verify-all.ps1 online                         PASS
scripts/verify-all.ps1 offline                        PASS
source immutability, 401 files                        PASS
```

The clean reactor produced 182 XML reports with zero failures, errors, or
skips. JDK 25, Maven Wrapper 3.3.4 only-script, dependency convergence,
release-only dependencies, duplicate classes, SpotBugs, and FindSecBugs
passed. The final online and offline logs contain complete stdout and stderr.

CycloneDX 1.6 contains 111 components and 112 dependency entries. OWASP
Dependency-Check inspected 115 dependencies and found nothing at or above the
CVSS 7.0 gate. The visible below-gate result is `CVE-2021-4277` in cron-utils
9.2.1 at CVSS 5.3.

## Mapping Closure

All source, test, and auxiliary state rows are now terminal:

```text
source-state.csv       142/142 migrated
test-state.csv         167/167: 149 ported, 18 differential
auxiliary-state.csv      92/92: 49 translated, 6 reimplemented,
                                5 verified, 32 copied
unique frozen paths     401/401
pending rows                  0
```

The six phase-17 auxiliary rows were completed at this boundary because the
phase-16 gate explicitly requires all 92 auxiliary rows terminal. Byte-exact
copies of the old README, architecture, deployment, validation, and validation
script were verified. Current Java README, architecture, operations, testing,
verification, CI template, and full verification scripts were audited.
Phase 17 still owns the actual hardening, performance, packaging, and final
acceptance executions.

## Audit Attempts

Earlier non-passing attempts are preserved:

- attempt 1 found a target-local desktop package lock;
- attempt 2 exposed PowerShell native-stderr pipeline behavior;
- attempt 3 could not cross the Docker named-pipe sandbox boundary;
- transcript-only success logs were retained after the logger was corrected.

The package output was validated before target-local cleanup. Final online and
offline verification ran outside the Docker sandbox boundary and passed.

## Gate Checklist

- [x] Phase 15 prerequisite passed.
- [x] Import is read-only, bounded, two-pass hashed, and path-confined.
- [x] Problem, artifact, checkpoint, version, and provenance gates pass.
- [x] Duplicate, corrupt, external-path, and quarantine tests pass.
- [x] Terminal resume makes zero provider calls.
- [x] Nonterminal resume starts from a committed checkpoint.
- [x] All 98 mapped authority cases and the Python-Java oracle pass.
- [x] Shadow report has no unexplained critical difference.
- [x] All 142 source, 167 test, and 92 auxiliary rows are terminal.
- [x] The three state files equal the exact 401-path frozen manifest.
- [x] Online and offline full Maven verification pass.
- [x] Original Python authority remains unchanged.

## Evidence

- `migration/reports/phase-16-gates.json`
- `migration/reports/phase-16-differential.json`
- `migration/reports/phase-16-dependency-tree.txt`
- `migration/reports/phase-16-verify-online.log`
- `migration/reports/phase-16-verify-offline.log`
- `migration/reports/phase-01-sbom.json`
- `migration/reports/dependency-check/dependency-check-report.json`
- `migration/source-state.csv`
- `migration/test-state.csv`
- `migration/auxiliary-state.csv`

## Stop Condition

Phase 16 passed every gate. No phase-17 release execution began before this
report and its gate evidence were captured.
