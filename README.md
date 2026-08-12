# JavaMathProofMesh 0.8.0

JavaMathProofMesh is a Java 25 modular monolith for durable, auditable
multi-agent mathematical proof work. PostgreSQL owns domain state, Temporal
owns durable scheduling, and a restricted Python JSON-RPC sidecar provides
only the small set of symbolic operations that are not native Java.

The frozen Python authority is retained only for migration evidence and
differential tests. Production Java code never imports it.

## Requirements

- JDK 25. Windows verification uses the pinned Temurin distribution in
  `.tools/jdk-25`.
- Docker Engine or Docker Desktop with Compose v2 for PostgreSQL, Temporal,
  and Testcontainers.
- Python 3.11 or newer for the restricted sidecar and authority-only
  differential checks.
- PowerShell 5.1 or newer on Windows, or a POSIX shell.

Maven is not installed globally. The Maven Wrapper 3.3.4 downloads the pinned
Maven 3.9.16 distribution after verifying its SHA-256.

## Verify

Windows:

```powershell
.\scripts\verify-all.ps1
```

Linux or macOS:

```sh
./scripts/verify-all.sh
```

The command runs unit, integration, replay, differential, security, and
source-immutability gates. After one online run, add `-Offline` on Windows or
`--offline` on POSIX to prove the locked build resolves without network
access.

## Provider-Free Demo

Package the release and run a complete deterministic Mock flow:

```powershell
.\scripts\package-release.ps1
target\release\JavaMathProofMesh-0.8.0\bin\mathproofmesh.cmd `
  demo --run-id readme-demo
```

The same release bundle provides the server entry point:

```powershell
target\release\JavaMathProofMesh-0.8.0\bin\mathproofmesh-server.cmd
```

No provider key or live network call is required for `demo`.

## Local Services

The development composition uses immutable image digests and loopback-only
ports:

```powershell
.\scripts\temporal-dev.ps1 -Command Up
```

PostgreSQL Testcontainers is started automatically by integration tests.
Production deployments must supply external PostgreSQL and Temporal services
with TLS or mTLS, authentication, least-privilege identities, and managed
backup policies. The development composition is not a production topology.

## Configuration And Providers

Start from `.env.local.example` and the strict YAML fixtures under
`config/`. Unknown fields fail validation. Secrets are accepted only through
environment or external credential providers; desktop credentials use
Windows DPAPI. Provider endpoints are allowlisted and cannot be selected by a
problem, prompt, or request payload.

Live provider traffic is disabled unless
`MPM_ALLOW_LIVE_PROVIDER_CALLS=true` is set explicitly. DeepSeek, Anthropic,
Gemini, OpenAI-compatible, and Mock adapters share the same bounded retry,
rate, budget, usage, and call-ledger rules.

## Data And Recovery

Flyway migrations are in
`mathproofmesh-server/src/main/resources/db/migration`. Committed proof
checkpoints, event/outbox/inbox records, provider-call state, and run leases
are PostgreSQL authority. Artifacts are immutable and content-addressed.
Temporal may replay orchestration, but it never replaces database truth.

Resume is idempotent. A terminal imported or native run performs zero provider
calls. A nonterminal run resumes only from its latest committed checkpoint.
Legacy imports are read-only, hash every input twice, quarantine unaudited
claims, and use the source manifest hash as their idempotency identity.

## Desktop

`scripts/package-desktop.ps1` builds a Windows app image, portable ZIP, and
EXE installer with JDK 25 `jlink`/`jpackage` and target-local WiX. The JavaFX
WebView talks only to a random loopback Spring server by HTTP/SSE. It has no
database repository or provider-key access.

## Documentation

- [Architecture](docs/architecture.md)
- [Operations](docs/operations.md)
- [Testing](docs/testing.md)
- [Security](docs/security.md)
- [Providers](docs/providers.md)
- [Temporal](docs/temporal.md)
- [Legacy compatibility](docs/compatibility.md)
- [Verification model](docs/verification.md)

## Security Boundaries

Do not expose the default HTTP, PostgreSQL, or Temporal development endpoints
off loopback. Do not place secrets in YAML, command arguments, logs, reports,
SSE events, or artifacts. Arbitrary model-generated Python is disabled. The
sidecar has a fixed operation allowlist, bounded input/output/time/resources,
and no database, secret, or workflow authority.

The release includes CycloneDX SBOM, license inventory, OWASP
Dependency-Check, SpotBugs/FindSecBugs, migration coverage, performance, and
SHA-256 reports under `migration/reports`.

## Known Limits

- The packaged desktop installer is Windows x64. The server and CLI remain
  platform-neutral Java 25 artifacts.
- The bundled Temporal composition is a single-node development service.
- Live providers require operator-supplied accounts and were never called by
  the migration acceptance suite.
- Natural-language equivalence is compared only on declared nondeterministic
  fields; structure, hashes, checkpoints, receipts, and states must match.
