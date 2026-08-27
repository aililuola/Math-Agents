# Desktop Live Triage Remediation - 2026-07-31

## Trigger

Installed desktop run `desktop-20260731-132328-30f0fb` displayed no progress
while it waited for the triage provider call. After approximately 176.7 seconds,
the run ended as `failed` with `StructuredOutputError`.

The provider call itself completed normally. DeepSeek returned 9,537 total
tokens (909 prompt, 8,628 completion, including 8,118 reasoning tokens),
8,630 stream chunks, and a terminal stream marker. No automatic retry was
performed after diagnosis.

## Root Cause

The generated prompt schema and the strict Java response contract disagreed:

- `PromptJsonSchema` inspected record components and accessors, but several
  generated defensive accessors did not carry their backing field's
  `@JsonProperty`. The prompt therefore exposed `keyRisks`, `likelyTools`, and
  `taskRequirements`, while `TriageResult` accepts `key_risks`, `likely_tools`,
  and `task_requirements`.
- `TriageResult` restricts `proof_mode` to `direct`, `decomposition`, or
  `hybrid`, but the prompt schema did not expose that closed set. The provider
  returned `extremal_number_theory`, which was valid under the prompt schema
  but invalid under the constructor contract.
- `DesktopRunManager` replayed `RunApiService` events only after `solve()`
  returned. Provider waits therefore appeared as a blank progress panel even
  though the backend had emitted stage and agent events.

The API keys, network path, provider stream, Docker runtime, and computation
sandbox were healthy and were not the cause of this failure.

## Correction

- Prompt schema property discovery now falls back to record backing-field
  annotations, keeping generated schemas aligned with Jackson wire names.
- Added runtime `ContractAllowedValues` metadata and emitted JSON Schema
  `enum` values for the live response-contract tree, including triage,
  verification, proof-step, critical-claim, and tool-request literals.
- Added a local API event observer. Desktop sessions now publish committed
  backend events while `solve()` is still running, with cursor-based replay to
  prevent duplicates at completion.
- Failed live runs now persist a safe failure summary such as
  `StructuredOutputError` instead of a null desktop error.

## Verification

- Focused prompt-schema and strict triage tests: 7 passed.
- Focused pre-completion desktop progress test: 1 passed.
- Six-module offline Maven `verify`: PASS, including Docker/Testcontainers,
  SpotBugs, FindSecBugs, coverage gates, and integration tests.
- Security verification: PASS.
- Frozen Python authority check: 401/401 files unchanged.
- Phase 17 completion verification: PASS (18/18).
- Rebuilt app-image health check: PASS, version 0.8.0.
- Installed per-user application health check: PASS, version 0.8.0.
- Installed contracts, server, and desktop JAR hashes exactly match the rebuilt
  app-image.
- Persisted desktop settings remain `sandbox_enabled=true` with profile
  `proof_control_active`.

Desktop artifact SHA-256 values:

```text
MathProofMesh-0.8.0.exe
9e67b73f10a2f2f9f78e18213546da57978caebb29e6d85e33b5476b7f09e77c

MathProofMesh-0.8.0-windows-x64-portable.zip
bea06abccdb506da7e91bb43b1385376a0cd545b663ebab079a376ad72f0c189
```

## Installation

The repaired app-image was atomically installed at
`%LOCALAPPDATA%\Programs\MathProofMesh`. The previous per-user directory was
retained as a timestamped backup. The obsolete public-desktop shortcut that
targeted `C:\Program Files\MathProofMesh` was removed from the desktop and
archived under `target/remediation`; the remaining desktop shortcut targets
the repaired per-user installation.
