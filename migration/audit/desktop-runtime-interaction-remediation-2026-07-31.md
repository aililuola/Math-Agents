# Desktop runtime interaction remediation audit

Date: 2026-07-31

## Trigger

The installed desktop application did not open its run-history context menu, long provider calls
appeared stuck at `Classifying the problem`, and the header repeatedly displayed that the progress
stream was reconnecting. A stopped provider call could also be projected as a generic
`ProviderException` failure.

The archived reasoning trace for the latest affected run continued to grow while the UI appeared
stalled. The API keys, DeepSeek stream, Docker runtime, and computation sandbox were therefore not
the cause of that apparent stall.

## Root causes

- The JavaFX host disabled WebView context menus but did not forward secondary-button mouse events
  to the page's existing DOM `contextmenu` handlers.
- The workbench uses `window.confirm` for deletion, cancellation, and credential clearing. JavaFX
  had no confirm handler, whose default result is rejection.
- Both desktop SSE endpoints returned a finite `String`. Each successful response then closed,
  causing `EventSource` to enter its reconnect path even while the run was healthy.
- A cancelled provider task could return after the cancellation event and overwrite the desktop
  lifecycle with a late failed result.

## Correction

- Added a bounded JavaFX secondary-button bridge that dispatches the DOM context-menu event at the
  exact WebView client coordinate. This restores both run-history and topology-node menus.
- Added a native, owner-bound JavaFX confirmation dialog for all `window.confirm` calls.
- Replaced the finite progress and reasoning responses with live `SseEmitter` streams. They replay
  by cursor, remain open, emit heartbeats, continue delivering incremental events, and close only
  after a terminal event or client disconnect.
- Preserved byte-offset reasoning replay and secret redaction. A batch only advances its SSE event
  identifier after its final record, so reconnect cannot silently skip the rest of that batch.
- Serialized cancellation against activity/result publication and classified provider cancellation
  as `cancelled`, preventing a stopped run from becoming a generic provider failure.

## Verification

- Dedicated live-progress SSE test: the same connection received `connected`, live `activity`, and
  later `terminal` events.
- Desktop tests: 31 passed, 0 failures, 0 errors, 0 skipped.
- Full Maven unit and integration suite: 1,955 tests, 0 failures, 0 errors, 0 skipped.
- Docker/PostgreSQL integration, sandbox security integration, and SpotBugs: PASS.
- Phase-17 coverage: PASS; desktop line coverage 1,807 / 2,565 (70.448343%).
- OWASP Dependency-Check, CycloneDX SBOM, security policy, performance comparison, and the frozen
  401-file Python authority snapshot: PASS.
- Full verification log: `migration/reports/desktop-runtime-remediation-2026-07-31.log`.

## Package and installation

- Installer SHA-256:
  `9843a494e6cd90d90d8a32cf6cbd29695c3ab1c3dce60e15d22cc9c9a2c1ade3`
- Portable ZIP SHA-256:
  `42d7f591a32864b18ceadf402b552ffa48c2f605d462381fc27763bbdb7ca17d`
- Installed desktop JAR SHA-256:
  `564b652b6b481f9950dd217cd3d5d298c1db0ac2e24b9f0315d3ac2133946ea9`
- Installed path:
  `C:\Users\yanxinyu\AppData\Local\Programs\MathProofMesh`
- Desktop shortcut target:
  `C:\Users\yanxinyu\AppData\Local\Programs\MathProofMesh\MathProofMesh.exe`
- Installed image: 7,202 files, 370,038,609 bytes.
- Previous program image retained at:
  `C:\Users\yanxinyu\AppData\Local\Programs\MathProofMesh.backup-20260731-084018`
- Installed clean-start health check: PASS, version 0.8.0.
- Visible installed-launch smoke check: PASS; the window is owned by the installed executable.

The application data directory was not replaced or removed. The selected profile remains
`proof_control_active`, the sandbox remains enabled, and all five credentials remain protected by
Windows DPAPI current-user encryption.
