# Phase 00 Retry Ledger

This ledger preserves non-final attempts without allowing them to override the
current gate result.

| Attempt | Result | Cause | Disposition / evidence |
|---|---|---|---|
| Prior phase 00 invocation | BLOCKED | Incorrect `WORKSPACE_ROOT` selection | Superseded; see `phase-00-prior-blocked-attempt.md`. ZIP and 401-file integrity were not failures. |
| Python baseline attempt 1 | FAIL: 253 failed, 506 passed | Windows `WinError 206` from the deeply nested temporary run paths | No source defect. Repeated through a target-local `subst` path with all caches still under `TARGET_ROOT`; final result was 759 passed. See `migration/logs/python-baseline-attempt-1-long-path.log`. |
| Maven Wrapper bootstrap attempt 1 | FAIL before an accepted wrapper state | The `only-script` batch bootstrap used a temporary path on a different volume from the short mapped target | Discarded. Repeated with target-local temp and wrapper home paths; the generated files and distribution checksum are locked. See `migration/logs/maven-wrapper-generation.log` and `migration/preflight/reports/maven-bootstrap-checksums.txt`. |
| Dependency convergence iteration | FAIL before final preflight | Overlapping Temporal/gRPC dependency paths and Testcontainers legacy JNA artifacts required explicit convergence | Guava and error-prone annotations were aligned; legacy JNA was excluded and replaced by the fixed JPMS artifacts. Final six-rule Enforcer run and offline resolution pass. |
| PostgreSQL image pull retries | TRANSIENT FAIL | Docker Hub TLS handshake and anonymous-token timeouts | No fallback registry, mirror image, or mutable tag was accepted. Retry completed against the requested official tag and the resulting repo digest was locked. See `migration/logs/docker-pull-postgres.log`. |
| Final baseline metadata refresh attempt 1 | FAIL before execution | The temporary `P:` mapping was not visible in a later command process | Repeated with map, export, and unmap in the same process. Final `BASELINE.json` contains the real workspace path plus Docker and Compose versions. |
| Post-state immutable check attempt 1 | FAIL before script execution | The parent PowerShell process enforced a script execution policy | Repeated with process-scoped `-ExecutionPolicy Bypass`; the script passed with 401 files and the canonical manifest hash. See `migration/logs/source-immutability-final.log`. |

PowerShell may label native stderr as `NativeCommandError` in captured Docker
logs even when the native process exits zero. Final native exit status, health
status, immutable image ID, and cleanup results are authoritative.
