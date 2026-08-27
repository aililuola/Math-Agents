# Desktop reasoning-exhaustion remediation audit

Date: 2026-07-31

## Scope

This remediation investigates and corrects the apparent `00:00` stall and unavailable node
reasoning reported for run `desktop-20260731-182407-30f0fb`. Provider credentials, run history,
learned state, and the authoritative budget/profile values were preserved.

## Forensic result

- The resumed attempt was not idle. The `ds-planner` triage call ran for approximately 149
  seconds.
- The provider reported 1,613 prompt tokens and 12,000 completion tokens. All 12,000 completion
  tokens were reasoning tokens; the public response body was empty.
- The reasoning archive contains 12,000 records for task `agent:triage:ds-planner`, including a
  completed trace with 34,521 characters. No reasoning content was lost.
- Because no public structured artifact was returned, the old runtime attempted strict JSON
  parsing and ended the stage with `StructuredOutputError`.
- The displayed 35-minute run clock was measured from the original run creation time instead of
  the latest resume attempt. Per-node clocks rendered a static event offset and therefore stayed
  at `00:00` while the provider call was active.

## Corrections

- The production coordinator now applies the migrated Python per-stage thinking policy. In the
  authoritative profile, triage is explicitly `disabled`, strategy generation is `max`, route
  work is `tiered`, and review stages are `high`.
- A reasoning-only response that reaches the output ceiling is now classified as
  `ReasoningBudgetExhaustedError` before JSON repair. The same classification also applies when a
  previously successful provider call is replayed during resume.
- The coordinator performs one bounded, thinking-disabled structured-artifact recovery call after
  reasoning exhaustion. It does not discard or overwrite the original reasoning trace.
- Agent failures now emit a terminal `agent_failed` event, preventing an agent node from remaining
  indefinitely in the running state after its stage fails.
- Resume elapsed time starts from the resume metadata rather than the original run creation time.
  Running topology nodes update their own duration every second.
- Right-clicking a stage node resolves to its corresponding agent-call node when one exists, so
  the saved reasoning stream is opened rather than the non-model stage envelope.

## Verification

- Structured-agent reasoning policy, exhaustion classification, and idempotent replay tests:
  PASS.
- Desktop stage-policy, live progress, resource-integrity, and UI gate tests: PASS.
- JavaScript syntax checks for `app.js` and `topology.js`: PASS.
- Full six-module Maven `verify` on JDK 25, including Docker/Testcontainers/PostgreSQL integration
  tests, SpotBugs/FindSecBugs, dependency policy, and all module tests: PASS.
- Packaged clean-start desktop health check: PASS, version 0.8.0.
- Installed desktop health check from `C:\Program Files\MathProofMesh`: PASS, version 0.8.0.
- Installed server and desktop JAR SHA-256 values exactly match the packaged application image.
- Seven existing run directories and the encrypted credential/settings files remained present
  after the same-version reinstall.

## Distribution and installation

- Installer: `target/desktop-dist/MathProofMesh-0.8.0.exe`
- Installer SHA-256:
  `67637bc4a8ae413cdc4fb4c73b9bd20e7e57dc09c876446320a1a5c074b694f4`
- Portable ZIP: `target/desktop-dist/MathProofMesh-0.8.0-windows-x64-portable.zip`
- Portable ZIP SHA-256:
  `1ea12c7a5334a58234a05114aa5ba02917b60519a225804872e4f61ebe0fc1f4`
- Installed launcher: `C:\Program Files\MathProofMesh\MathProofMesh.exe`
- Public desktop shortcut target: `C:\Program Files\MathProofMesh\MathProofMesh.exe`

The previous system-wide 0.8.0 package was removed with elevated Windows Installer permissions,
this verified build was installed, and the installed application was launched successfully. No
credential material or reasoning text is recorded in this audit.
