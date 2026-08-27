# Desktop history and reasoning remediation audit

Date: 2026-07-31

## Scope

This remediation restores the Python-authority desktop behavior for:

- right-click deletion of completed or failed run history through the Windows Recycle Bin;
- automatic archival of every provider-returned `reasoning_content` fragment;
- right-click topology-node inspection of archived and live reasoning;
- live reconnect and continuation of the red-bordered reasoning inspector;
- durable activity events whose task identifiers match reasoning-trace identifiers.

No outer Python source, test, build-information, or configuration file was modified.

## Root cause

The migrated UI still contained the history and topology context menus, but the Java runtime did
not complete their backend contract. The desktop repository looked for
`reports/reasoning_traces.jsonl`, while both the Python authority and the Java trace store use
`reports/reasoning_traces.txt`. Provider `reasoning_content` was not connected to the trace store,
live activity used task identifiers that could not be joined to trace identifiers, and Java live
runs did not persist the activity stream required by the topology view.

## Remediation

- Added incremental, bounded SSE observation without weakening UTF-8, timeout, disconnect, or
  response-size protections.
- Bound provider calls to an inheritable reasoning-trace scope and archived streaming and
  non-streaming `reasoning_content` through `ReasoningTraceStore`.
- Persisted `activity.jsonl` for live desktop runs and aligned agent task identifiers across the
  activity, topology, and reasoning APIs.
- Corrected the desktop repository to read `reports/reasoning_traces.txt`, filter by task, and
  support byte-offset incremental reads.
- Added resumable reasoning SSE delivery with `Last-Event-ID`, heartbeat, retry, and terminal
  handling.
- Preserved the existing history deletion menu, topology-node context menu, and red-bordered
  reasoning inspector. Historical Java runs that never archived provider reasoning remain
  unrecoverable; new runs and older compatible Python runs are readable.
- Added real live-backend tests for completed solving, rejected computation, release-safe failure,
  durable activity, strict response contracts, and bounded UTF-8 report truncation.

## Verification

- Targeted live-backend suite: 5 tests, 0 failures.
- Full Maven, unit, and integration suite: 1,952 tests in 230 suites, 0 failures, 0 errors,
  0 skipped.
- Docker/PostgreSQL integration: PASS.
- SpotBugs: PASS with zero findings.
- Phase-17 coverage: PASS; desktop line coverage 1,722 / 2,456 (70.114007%).
- Critical scenarios: 9 / 9 PASS.
- OWASP dependency check, CycloneDX SBOM, security policy, performance comparison, and immutable
  401-file source snapshot: PASS.
- Full verification log: `migration/reports/desktop-reasoning-remediation-verify.log`.

## Package and installation

- Installer SHA-256:
  `5b9e6df3921270950fb1f8e97679985d66b573cefbb965737c2c7186944939ce`
- Portable ZIP SHA-256:
  `6134d23a9039b5aa642624b49e29f09df11b8d4100406f96fb4ab2ea4b657868`
- Installed desktop JAR SHA-256:
  `3a3fc714ac7d80e8b682c21434b41eae694ace12bcc6c2e41eaf70c2eecf2d87`
- Installed path:
  `C:\Users\yanxinyu\AppData\Local\Programs\MathProofMesh`
- Desktop shortcut target:
  `C:\Users\yanxinyu\AppData\Local\Programs\MathProofMesh\MathProofMesh.exe`
- Installed image: 7,202 files, 370,034,772 bytes.
- Packaged-image and staged-image file counts, byte counts, and all four application JAR hashes
  matched before replacement.
- Installed clean-start health check: PASS, version 0.8.0.
- Visible installed-launch smoke check: PASS.

The application data directory was not replaced or removed. Existing runs, settings, and DPAPI
credential material remain outside the program directory.
