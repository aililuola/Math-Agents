# Desktop run and topology remediation audit

Date: 2026-07-31

## Scope

This remediation addresses two production desktop defects reported against run
`desktop-20260731-182407-30f0fb`:

1. The live solve stopped at `freeze_problem` with `IllegalArgumentException` before any
   provider call or token use.
2. Topology nodes flashed while hovered and the terminal failure node could appear detached
   from its immediate predecessor.

User data, provider credentials, run history, and budget/profile configuration were preserved.

## Root causes

- The detailed production workflow emits trusted events such as `stage_started` and
  `problem_frozen`. `ActivityStream` persisted the first detailed event, but the public API event
  constructor still enforced an older fixed vocabulary. It rejected `stage_started`, aborting the
  run before the first provider call.
- The browser projection allowed later event updates to replace a task's original start time,
  parent, and initial event type. This could reorder an existing node as SSE updates arrived.
- The topology renderer destroyed and recreated every SVG node on each event, while its hover
  rule moved the hovered hit target by one pixel. Together those behaviors caused repeated
  pointer enter/leave transitions in JavaFX WebView.
- Terminal events were not consistently parented to the active failed stage, so a failure could
  render as an isolated root.

## Corrections

- Detailed internal events remain intact in `activity.jsonl`; the public SSE/API projection now
  maps them to the stable public vocabulary instead of rejecting them.
- The live backend tracks the active stage, emits `stage_failed` before the terminal failure, and
  attaches that terminal event to the failed stage.
- The desktop activity projection preserves immutable topology identity fields and assigns stable
  initial task kinds for stages, agent calls, and computation experiments.
- The topology renderer now updates keyed SVG node elements in place, removes only stale nodes,
  preserves immutable ordering/parent fields, and settles earlier running nodes when a terminal
  event is observed.
- Hover no longer translates the SVG hit target, and native SVG title tooltips were removed in
  favor of a stable accessible label.

## Verification

- JavaScript syntax checks for `app.js` and `topology.js`: PASS.
- Targeted server event-vocabulary and execution-backend tests: PASS.
- Targeted desktop gate, live-progress, and live-backend tests: PASS.
- Full six-module Maven `verify` on JDK 25, including Docker/Testcontainers/PostgreSQL,
  SpotBugs/FindSecBugs, dependency policy, and all module tests: PASS.
- Packaged clean-start desktop health check: PASS, version 0.8.0.
- Installed desktop health check from `C:\Program Files\MathProofMesh`: PASS, version 0.8.0.
- Installed `mathproofmesh-server-0.8.0.jar` and `mathproofmesh-desktop-0.8.0.jar` SHA-256 values
  exactly match the packaged build.

## Distribution and installation

- Installer: `target/desktop-dist/MathProofMesh-0.8.0.exe`
- Installer SHA-256:
  `422bdb738c7803afc8544b452ec062efe8d5e9478da9b050e5a2bc11f276dd96`
- Portable ZIP: `target/desktop-dist/MathProofMesh-0.8.0-windows-x64-portable.zip`
- Portable ZIP SHA-256:
  `f926678b9b3f3c78a08d501012e109354065067fe0c85e18b862d7690e8fae3c`
- Installed launcher: `C:\Program Files\MathProofMesh\MathProofMesh.exe`
- Public desktop shortcut target: `C:\Program Files\MathProofMesh\MathProofMesh.exe`

The prior same-version Windows Installer product was removed and this verified build was
installed in its place. The installed application was then launched successfully. No credential
material is recorded in this audit.
