# Desktop Run Event Remediation - 2026-07-31

## Trigger

Installed desktop run `desktop-20260731-124109-30f0fb` entered `failed` in
approximately 0.23 seconds with `IllegalArgumentException`, zero provider
calls, and zero tokens. The desktop log contained only the sanitized exception
type.

## Root Cause

The live desktop backend completed its sandbox preflight and then emitted a
`sandbox_preflight` API event. The closed API event vocabulary did not yet
admit `sandbox_preflight`, `computation`, `agent_failed`, or `run_failed`, even
though the backend intentionally emits all four. Construction of the first
event therefore failed, and the fallback `run_failed` event could fail for the
same reason and mask the original context.

The workbench also used the SSE event name `error` for an application failure.
The browser's transport-level `EventSource.onerror` handler saw that same
message and left a terminal run displaying the misleading status
`progress stream reconnecting`.

## Correction

- Added the four backend event types to the API event vocabulary.
- Added parameterized regression coverage for every newly admitted type.
- Made the workbench distinguish an SSE application `MessageEvent` from a
  transport error and explicitly restore connected service status on terminal
  delivery.
- Regenerated the desktop app-image, portable ZIP, and EXE installer, then
  repaired the installed 0.8.0 application in place.

No provider credential, provider profile, reasoning setting, sandbox policy,
run history, or DPAPI credential payload was changed. The failed attempt made
no paid provider call and consumed no provider token.

## Verification

- JavaScript syntax check: PASS.
- Focused API vocabulary tests: 4 passed.
- Focused desktop gate tests: 6 passed.
- Six-module offline Maven `verify`: PASS, including Docker/Testcontainers
  integration tests, SpotBugs, and FindSecBugs.
- Security verification: PASS.
- Frozen Python authority check: 401/401 files unchanged.
- Rebuilt app-image health check: PASS, version 0.8.0.
- Installed application health check: PASS, version 0.8.0.
- All four installed MathProofMesh JAR hashes match the rebuilt artifacts.

Desktop artifact SHA-256 values:

```text
MathProofMesh-0.8.0.exe
65879f705bad9881ccfd72a519c027504789450cde609d60fb6767a11ed142f9

MathProofMesh-0.8.0-windows-x64-portable.zip
13c46257edd2ea70c61ed5f9b21297af3974329f54c5a11d9b4bdb629c79578c
```
