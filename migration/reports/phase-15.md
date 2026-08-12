# Phase 15 Report

**Result:** PASS  
**Scope:** JavaFX desktop, DPAPI credential protection, and Windows jpackage delivery  
**Started:** 2026-07-31T04:25:07.908Z  
**Completed:** 2026-07-31T05:38:42.117Z  
**Git commit:** N/A (workspace is not a Git worktree)

## Authority

Phase 14 was `passed` before work began. The only source used was the locked
authority ZIP with SHA-256
`5e09eb8a704ea5f841078bafcc1c5e72e4d17a76b2fef7a344e41d55e013c1b2`.
The frozen 401-file manifest remained unchanged at
`9f3def2bec8cea99d0a18b51fbb5fa8ce53f44a24ce869f6495cac809b7e3770`.
All writes remained inside `JavaMathProofMesh-0.8.0`.

## Implementation

The desktop module now launches a JavaFX 25.0.4 WebView over a Spring Boot
4.1.0 server bound to a random loopback port. The UI uses only HTTP and SSE,
never database repositories, and receives a one-time in-memory session token
instead of provider credentials. External and file navigation, downloads, and
developer tools are blocked. The authority HTML, JavaScript, CSS, topology, and
icon resources are packaged on the classpath with byte-exact SHA-256 parity.

Windows credentials use JNA 5.19.1 DPAPI. Only ciphertext and entropy metadata
are stored; plaintext is excluded from logs and exports. Wrong-user and
corruption behavior is fail-closed. Non-Windows execution requires environment
or external credential providers. Run deletion is confined to validated roots
and requires explicit confirmation.

The target-local packaging flow uses JDK 25 `jlink` and `jpackage`, plus WiX
5.0.2, to produce an app image, portable ZIP, and Windows EXE installer. The
packaged app passed its clean-start health check as version `0.8.0`.

## Verification

```text
2 mapped parity test classes                       PASS
13 authority-named Python function cases           PASS
6 desktop lifecycle/security/package gates         PASS
mathproofmesh-desktop selected tests (19 total)    PASS
scripts\verify-all.ps1                             PASS
scripts\verify-all.ps1 -Offline                    PASS
packaged app-image health check                    PASS
scripts\check-original-immutable.ps1               PASS; 401 files
```

The clean reactor ran 1,420 tests from 178 XML reports with zero failures,
errors, or skips. Online and offline runs used JDK 25 and Maven Wrapper 3.3.4
only-script. Dependency convergence, release-only dependencies,
duplicate-class checks, Modulith structure, SpotBugs, and FindSecBugs passed
with zero final findings.

The first full quality pass exposed 39 desktop SpotBugs findings. Concrete
nullability and logging issues were fixed, and narrowly scoped suppressions
were added only where framework or DPAPI boundaries were intentional. Two
earlier clean attempts also encountered generated Windows package locks and
long paths; only validated target-local generated directories were removed.
The final online and offline runs are the evidence for this PASS.

CycloneDX 1.6 contains 111 components and 112 dependency entries. OWASP
Dependency-Check inspected 115 dependencies and found nothing at or above the
CVSS 7.0 gate. The visible below-gate finding remains `CVE-2021-4277` in
cron-utils 9.2.1 at CVSS 5.3.

## Release Artifacts

```text
MathProofMesh-0.8.0.exe
  size   160105984
  sha256 797502f3f254d53fdf3270a07322d6159d3859e1cbe104ad7439c2f03bd07fcb

MathProofMesh-0.8.0-windows-x64-portable.zip
  size   160687056
  sha256 97f934428918bfe100abd0c1926b066a669e525a216c9722ccb6885d138a65f1

SHA256SUMS.txt
  sha256 28ce6810854641e03cbfd912ef4c470a08ebf8a25e758a4205d9acbd19ce10f3
```

## Mapping

All 15 phase-15 source rows are `migrated`. Both test rows, representing 13
Python test functions, are `ported`. All 10 packaging and metadata rows are
`translated_verified`. The three state tables still form the exact 401-path
frozen source inventory.

## Gate Checklist

- [x] Phase 14 prerequisite passed.
- [x] JavaFX UI communicates only with loopback HTTP/SSE APIs.
- [x] WebView cannot read provider keys or navigate external/file URLs.
- [x] Server lifecycle, SSE, resume, settings, topology, and timeline pass.
- [x] DPAPI round-trip, wrong-user, corruption, and redaction pass.
- [x] No plaintext secret is written.
- [x] Safe run deletion and path confinement pass.
- [x] App image, portable ZIP, EXE installer, checksums, and health smoke pass.
- [x] All 27 phase-15 mapping rows have terminal verified status.
- [x] Online and offline JDK 25 Maven Wrapper verification pass.

## Evidence

- `migration/reports/phase-15-gates.json`
- `migration/reports/phase-15-dependency-tree.txt`
- `migration/reports/phase-15-verify-online.log`
- `migration/reports/phase-15-verify-offline.log`
- `migration/reports/phase-01-sbom.json`
- `migration/reports/dependency-check/dependency-check-report.json`
- `target/desktop-dist/SHA256SUMS.txt`
- `migration/source-state.csv`
- `migration/test-state.csv`
- `migration/auxiliary-state.csv`

## Stop Condition

Phase 15 passed every gate. Phase 16 was not started before this report and its
gate evidence were captured.
