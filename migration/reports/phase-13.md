# Phase 13 Report

**Result:** PASS  
**Scope:** Temporal durable workflow orchestration  
**Started:** 2026-07-31T03:11:44.404Z  
**Completed:** 2026-07-31T03:35:17.903Z  
**Git commit:** N/A (workspace is not a Git worktree)

## Authority

Phase 12 was `passed` before work began. The only source used was the locked
authority ZIP with SHA-256
`5e09eb8a704ea5f841078bafcc1c5e72e4d17a76b2fef7a344e41d55e013c1b2`.
The frozen 401-file manifest remained unchanged at
`9f3def2bec8cea99d0a18b51fbb5fa8ce53f44a24ce869f6495cac809b7e3770`.
All writes remained inside `JavaMathProofMesh-0.8.0`.

## Implementation

The fixed orchestration stage machine is now scheduled by exactly two Temporal
workflows: `MathProofMeshSolveWorkflow` and `RouteExplorationWorkflow`.
Preflight, planning, agent calls, computation, Broker publication, memory,
proof graph, verification, synthesis, blind final review, persistence, and
reporting remain idempotent Activities. There is no final-review workflow and
business logic is not duplicated in workflow code.

Workflow history contains stable identifiers and bounded summaries rather than
prompts, raw proofs, provider secrets, or transcripts. Workflow code uses
Temporal time, waiting, child workflows, retries, version markers, and
Continue-As-New. It performs no repository, JDBC, HTTP, provider, filesystem,
random UUID, or non-Temporal concurrency work.

Pause, resume, cancel, and route wake are durable signals. Budget increases and
audited directives are validated updates with stable deduplication identifiers.
Status, current stage, route summary, and budget summary are queries. Activity
options use fixed timeouts, bounded retries, and heartbeat support.

Domain writes use stable action keys and an inbox-shaped action store, so
activity retries, workflow replay, Continue-As-New, and duplicate commands do
not apply mathematical or provider effects twice. PostgreSQL checkpoints remain
the mathematical authority; Temporal is the scheduling authority. Legacy
checkpoint migration and terminal resume policies retain this boundary.

## Temporal Service

`compose/temporal-dev.yaml` uses the exact locked image digest, binds gRPC only
to `127.0.0.1`, runs as the image's `temporal` user, has a read-only root
filesystem, drops all capabilities, sets `no-new-privileges`, and persists
SQLite in a named volume. The health command returned `SERVING`.

A dedicated namespace with ID
`37c64119-b25e-4b83-9833-9d2ca6003a93` was created, the service was brought
down and recreated without deleting its volume, and the same namespace ID was
successfully described after restart. The container was then stopped while its
volume was retained.

The initial container attempt mounted a new volume at an absent root-owned
directory and could not create SQLite. The mount was corrected to the existing
non-root home directory, the empty failed volume was explicitly reset, and the
complete persistence and hardening gate was rerun successfully. The transcript
retains both attempts.

## Verification

```text
8 mapped parity test classes                       PASS
25 authority-named Python function cases           PASS
4 Temporal environment/replay/control cases        PASS
real Temporal Compose persistence and hardening    PASS
scripts\verify-all.ps1                             PASS
scripts\verify-all.ps1 -Offline                    PASS
scripts\check-original-immutable.ps1               PASS; 401 files
```

The clean reactor ran 1,382 tests from 170 XML reports with zero failures,
errors, or skips. Online and offline runs used JDK 25 and Maven Wrapper 3.3.4
only-script. Dependency convergence, release-only dependencies,
duplicate-class checks, Modulith structure, SpotBugs, and FindSecBugs all
passed.

CycloneDX 1.6 contains 88 components and 89 dependency entries. OWASP
Dependency-Check inspected 112 dependencies and found nothing at or above the
CVSS 7.0 gate. The visible below-gate finding remains `CVE-2021-4277` in
cron-utils 9.2.1 at CVSS 5.3.

## Mapping

The phase-13 source row is `migrated`. All 8 test rows, representing 25 Python
test functions, are `ported`. The checkpoint/resume authority document remains
byte-exact at SHA-256
`e47ad6bbceb86438abf4bd4139990f5ea73a3b3c6ee608f36fd90236ff273db4`;
its active semantics are consolidated in `docs/compatibility.md` and
`docs/temporal.md`.

## Gate Checklist

- [x] Phase 12 prerequisite passed.
- [x] Exactly two deterministic workflow interfaces exist.
- [x] All I/O and business effects remain in idempotent Activities.
- [x] Final review is an Activity, not a workflow.
- [x] Signals, validated updates, and queries pass.
- [x] Activity retry, heartbeat, replay, and Continue-As-New pass.
- [x] Stable action keys prevent duplicate domain effects.
- [x] Fault injection and legacy checkpoint recovery pass.
- [x] Workflow histories exclude raw/private proof material.
- [x] In-process and Temporal execution semantics agree.
- [x] Exact-digest Compose health, hardening, and persistence pass.
- [x] All 10 phase-13 mapping rows have terminal verified status.
- [x] Online and offline JDK 25 Maven Wrapper verification pass.

## Evidence

- `migration/reports/phase-13-gates.json`
- `migration/reports/phase-13-dependency-tree.txt`
- `migration/logs/phase-13-temporal-compose.log`
- `migration/logs/phase-13-verify.log`
- `migration/logs/phase-13-verify-offline.log`
- `migration/reports/phase-01-sbom.json`
- `migration/reports/dependency-check/dependency-check-report.json`
- `migration/source-state.csv`
- `migration/test-state.csv`
- `migration/auxiliary-state.csv`

## Stop Condition

Phase 13 passed every gate. Phase 14 was not started before this report and its
gate evidence were captured.
