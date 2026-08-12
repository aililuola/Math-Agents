# Phase 00 Report

## Result

**PHASE 00: PASS**

- Phase: immutable baseline, environment, and framework preflight
- Attempt: 2
- Started: 2026-07-30T05:58:45.6849726Z
- Completed: 2026-07-30T08:31:52.5505971Z
- Phase 01 started: no

The previous `BLOCKED` result is retained only as a superseded failed attempt.
Its cause was an incorrect workspace-root selection, not a source ZIP or
401-file snapshot failure.

## Workspace Gate

The first gate selected the unique directory containing all five required
entries:

`C:\Users\yanxinyu\Desktop\JavaMathProofMeshMigration\Math-Agents-feature-mathproofmesh-v0.8.0-goal-plan-failure-utility-control`

| Required entry | Result |
|---|---|
| `pyproject.toml` | PASS |
| `BUILD_INFO.json` | PASS |
| `src/mathproofmesh` | PASS |
| `tests` | PASS |
| `JavaMathProofMesh-0.8.0` | PASS |

`TARGET_ROOT` is the `JavaMathProofMesh-0.8.0` child of that directory. Every
workspace file, explicitly downloaded archive, cache, virtual environment,
extracted file, log, tool, generated file, and container bind mount used by
this phase is under `TARGET_ROOT`. Docker image layers remain in
engine-managed storage and did not create workspace files.

## Specifications Read

The following files were read in full before phase execution:

- `CODEX_START_HERE.md`
- `CODEX_MASTER_INSTRUCTIONS.md`
- `MIGRATION_PLAN.md`
- `PHASE_GATES.yaml`
- `PYTHON_SOURCE_MIGRATION_MAP.csv`
- `PYTHON_TEST_MIGRATION_MAP.csv`
- `OPS_CONFIG_DOC_MIGRATION_MAP.csv`
- `SOURCE_SNAPSHOT_SHA256SUMS.txt`
- `SHA256SUMS.txt`

No Git branch, historical commit, alternate worktree, or alternate ZIP was
searched or adopted.

## Source Gates

| Check | Actual | Result |
|---|---:|---|
| `SHA256SUMS.txt` entries | 12 / 12 match | PASS |
| Authoritative ZIP SHA-256 | `5e09eb8a704ea5f841078bafcc1c5e72e4d17a76b2fef7a344e41d55e013c1b2` | PASS |
| ZIP ordinary files | 401, one common source prefix | PASS |
| Canonical manifest SHA-256 | `9f3def2bec8cea99d0a18b51fbb5fa8ce53f44a24ce869f6495cac809b7e3770` | PASS |
| Workspace snapshot excluding `.git` and target | 401 path/size/hash matches | PASS |
| ZIP content versus canonical manifest | no missing, extra, or changed path | PASS |
| Source mapping rows | 142 | PASS |
| Test mapping files | 167 | PASS |
| Auxiliary mapping rows | 92 | PASS |
| Mapping union | 401 unique; 0 duplicate, missing, or extra | PASS |
| Final immutable-source script | 401 files, canonical manifest hash | PASS |

The exact ZIP is:

`migration/input/Math-Agents-feature-mathproofmesh-v0.8.0-goal-plan-failure-utility-control.zip`

The final immutable check command was:

```powershell
.\scripts\check-original-immutable.ps1
```

Exit code: `0`. Evidence:
`migration/logs/source-immutability.log` and
`migration/logs/source-immutability-final.log`.

## Python Baseline

The authoritative ZIP was extracted to `.work/source`. The isolated
environment is `.venv-baseline`; installation was non-editable. Bytecode,
pytest cache, temp, home, and application-data paths were redirected beneath
`TARGET_ROOT`.

| Check | Actual | Result |
|---|---:|---|
| Python | 3.14.2, amd64 | PASS |
| `pip check` | no broken requirements | PASS |
| `pytest --collect-only` | 759 items | PASS |
| Complete baseline | `759 passed, 821 warnings in 43.57s` | PASS |
| Explicit test functions | 707 | PASS |
| Parameterized expansions | 52 | PASS |
| Test modules / support files | 164 / 3 | PASS |

Representative final command:

```powershell
P:\.venv-baseline\Scripts\python.exe -m pytest P:\.work\source\tests
```

Exit code: `0`. Evidence: `migration/logs/python-baseline.log` and
`.work/pytest-collect.txt`.

The first full run exited nonzero because deep Windows test temp paths reached
`WinError 206`; it reported 253 failed and 506 passed. This was an environment
path-length failure, not a source result. It is preserved in
`migration/logs/python-baseline-attempt-1-long-path.log`; the retry used a
target-local short drive mapping and did not write to the outer Python tree.

## Baseline Exports

`migration/tools/export_baseline.py` reverified every extracted source hash
before exporting:

| Artifact class | Count / result |
|---|---:|
| Pydantic JSON Schemas | 236 |
| Enum inventories | 82 |
| Hash golden vectors | 16 |
| Imported modules | 137 |
| Config fields | 318 |
| Environment variables | 11 |
| Normalized profiles | 6 |
| Source config fixtures | 8 |
| Phase-00 auxiliary copies | 5 |

The hash vectors include strings, empty input, Chinese text, supplementary
Unicode characters, scalar/container JSON values, and the required contract
objects. Config inventories record source fields, types, defaults, environment
names, secret-source handling, unknown-field policy, and validation metadata.
No real provider secret was loaded.

Exit code: `0`. Evidence:
`migration/baseline/export-summary.json`,
`migration/baseline/mapping-coverage-proof.json`, and
`migration/logs/baseline-export.log`.

State tables contain 142 source, 167 test, and 92 auxiliary records. Their
status columns were initialized for later phases; the five phase-00 auxiliary
copies are already marked verified.

## Toolchain

| Tool | Verified version | Result |
|---|---|---|
| Eclipse Temurin JDK | 25.0.4+7 | PASS |
| `java` / `javac` | 25.0.4 / 25.0.4 | PASS |
| Git | 2.45.1.windows.1 | PASS |
| Python | 3.14.2 | PASS |
| Docker client/server | 29.6.2 / 29.6.2 | PASS |
| Docker Compose plugin | v5.3.1 | PASS |

JDK 25 was installed without user action under `.tools/jdk-25`. The official
Adoptium archive is 141,164,204 bytes; vendor and computed SHA-256 both equal:

`7caab7db43bf4b94a2e6252c699e70d90084f9aa7c943cd3414761fd540937ae`

## Maven Wrapper

The official Maven 3.9.16 ZIP was downloaded to
`.cache/bootstrap-maven`. Its 9,395,475-byte archive matched the official
SHA-512:

`ed41650d42485cfc243fad22158caf9cbb5dc408ce7a09ddb94dd42a019de929ca43065bfa450612cf12bf78b5cafa3884b96c090de326ff590448c933454af3`

Its computed SHA-256 is:

`5af3b743dd8b876b5c45da33b676251e5f1687712644abb4ee519ca56e1d89ce`

The verified bootstrap Maven invoked only this fully qualified goal:

```text
org.apache.maven.plugins:maven-wrapper-plugin:3.3.4:wrapper
```

with `-Dtype=only-script`, `-Dmaven=3.9.16`, the official distribution URL,
and the computed distribution SHA-256. Exit code: `0`.

The generated properties contain:

```properties
wrapperVersion=3.3.4
distributionType=only-script
distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.16/apache-maven-3.9.16-bin.zip
distributionSha256Sum=5af3b743dd8b876b5c45da33b676251e5f1687712644abb4ee519ca56e1d89ce
```

No `maven-wrapper.jar` or `MavenWrapperDownloader.java` exists. A clean,
independent Wrapper download and checksum verification reported Maven 3.9.16.
Generated-file hashes and download time are locked in
`migration/dependency-lock.yaml` and
`migration/preflight/reports/maven-bootstrap-checksums.txt`.

## Framework Preflight

The fixed preflight POM resolved Spring Boot 4.1.0, Spring Modulith 2.1.0,
Temporal 1.37.0, JavaFX 25.0.4 `win`, PostgreSQL/Flyway/Testcontainers,
Picocli 4.7.7, JGraphT 1.5.3, JNA JPMS 5.19.1, ArchUnit 1.4.2, and every fixed
build/security plugin.

| Command | Exit |
|---|---:|
| `mvnw.cmd -f migration/preflight/pom.xml -B -ntp validate` | 0 |
| `mvnw.cmd -f migration/preflight/pom.xml -B -ntp help:effective-pom` | 0 |
| `mvnw.cmd -f migration/preflight/pom.xml -B -ntp dependency:go-offline` | 0 |
| `mvnw.cmd -f migration/preflight/pom.xml -B -ntp dependency:tree -Dverbose` | 0 |
| `mvnw.cmd -f migration/preflight/pom.xml -B -ntp dependency:resolve-plugins` | 0 |
| offline `dependency:go-offline` | 0 |

Final tree checks: zero Snapshot matches, zero `omitted for conflict`, zero
legacy non-JPMS JNA selections, and two selected JPMS JNA 5.19.1 artifacts.
All Enforcer rules passed: Java version, Maven version, release dependencies,
dependency convergence, duplicate POM dependency versions, and duplicate
classes.

Evidence:

- `migration/preflight/reports/effective-pom.xml`
- `migration/preflight/reports/dependency-tree.txt`
- `migration/preflight/reports/plugin-resolution.txt`
- `migration/preflight/reports/dependency-conflict-report.txt`
- `migration/logs/maven-preflight-offline.log`

## Container Preflight

| Service | Immutable reference | Verification | Result |
|---|---|---|---|
| PostgreSQL | `postgres@sha256:1961f96e6029a02c3812d7cb329a3b03a3ac2bb067058dec17b0f5596aca9296` | PostgreSQL 18.4; healthy; test database/query succeeded | PASS |
| Temporal | `temporalio/temporal@sha256:59561b9ef060eaeb1f46cb6a1842d6cbdd8a393eb3b6d315ecef5fe2f0b1d7a6` | CLI 1.8.1; Server 1.31.2; `SERVING` | PASS |

Both images are `linux/amd64`. Compose consumes only `POSTGRES_IMAGE` and
`TEMPORAL_DEV_IMAGE` from `migration/image-lock.env`.

Temporal ran the required headless command, used the target-local persistent
SQLite file, and exposed only `127.0.0.1:7233`. The SQLite artifact passed
`integrity_check`, contains 48 tables and the `default`, `mathproofmesh`, and
`temporal-system` namespaces, and has SHA-256
`35cd4bed729bfbec497f788c2ad462530ddb7e984feb27d30b420a8da1d5e3b0`.

Both service roots were read-only, capabilities were dropped, no-new-privileges
was enabled, process/memory/CPU limits were applied, and only loopback ports
were published. PostgreSQL used `127.0.0.1:55432`; the ephemeral preflight
credential was not written to committed evidence. Both Compose projects were
stopped and removed after verification. A final Docker query found zero
phase-00 containers and zero phase-00 networks.

The PostgreSQL pull encountered transient Docker Hub TLS/token timeouts.
Retries stayed on the requested official tag and accepted only the final locked
digest. Full retry evidence is in `migration/logs/docker-pull-postgres.log`.

## Retry Audit

All non-final attempts and their disposition are recorded in:

- `migration/audit/phase-00-prior-blocked-attempt.md`
- `migration/audit/phase-00-retry-ledger.md`

None is treated as the current result.

## Final Repeatability Gate

```powershell
.\scripts\preflight.ps1
```

Exit code: `0`, final line: `PHASE 00 PREFLIGHT: PASS`.

The run rechecked JDK, Git, Python, Docker Engine, Compose, Maven Wrapper,
offline Maven resolution, all six Enforcer rules, and outer-source
immutability. The POSIX companion scripts were created; this Windows host has
no Bash installation, so the active Windows scripts are the executed evidence.
A post-state immutable-source run again passed with 401 files, and the
temporary short-path drive mapping was absent at completion.

## Conclusion

Every phase-00 gate in `PHASE_GATES.yaml` passed. The authoritative Python ZIP,
the 401-file frozen source snapshot, mapping coverage, strict 759-test baseline,
exported compatibility evidence, JDK 25, Maven Wrapper, fixed framework
dependencies, and hardened container checks all match the required values.

**PHASE 00: PASS**

Execution stops here. Phase 01 was not started.
