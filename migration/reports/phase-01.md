# Phase 01 Report

**PHASE 01: PASS**

- Phase: `01`
- Name: Maven multi-module skeleton and secure build
- Attempt: `1`
- Started (UTC): `2026-07-30T08:39:16.9946542Z`
- Completed (UTC): `2026-07-30T09:47:29.3843225Z`
- Prerequisite: phase 00 is `passed`
- Next phase started: `false`

## Decision

Every phase-01 gate in `MIGRATION_PLAN.md` and `PHASE_GATES.yaml` passes.
The JDK 25 reactor builds online and offline, all framework smoke tests pass,
the dependency graph converges with no external snapshots, SpotBugs and
FindSecBugs report no findings, the CycloneDX SBOM is valid, and the final
OWASP report contains no vulnerability findings. The frozen outer Python
source remains byte-for-byte unchanged.

Phase 02 was not started.

## Prerequisite And Scope

`migration/state.json` was read before phase work. It recorded phase 00 as
`passed` with the following reusable evidence:

- authoritative ZIP SHA-256:
  `5e09eb8a704ea5f841078bafcc1c5e72e4d17a76b2fef7a344e41d55e013c1b2`;
- frozen source manifest: 401 files with combined SHA-256
  `9f3def2bec8cea99d0a18b51fbb5fa8ce53f44a24ce869f6495cac809b7e3770`;
- Python baseline: 759 passed;
- Eclipse Temurin 25.0.4+7;
- Maven 3.9.16 through Maven Wrapper 3.3.4 `only-script`;
- framework and container preflight passed.

The earlier phase-00 `BLOCKED` result remains preserved only as a prior failed
attempt caused by an incorrect `WORKSPACE_ROOT`; it has no effect on the
current phase-00 or phase-01 result.

This phase created only build structure, version metadata, boundary markers,
test fixtures, and framework smoke tests. It did not migrate phase-02 domain
contracts or start any later-phase behavior.

All writes remained under `JavaMathProofMesh-0.8.0`. The outer
`pyproject.toml`, `BUILD_INFO.json`, `src`, `tests`, and every other frozen
source path were read-only.

## Required Outputs

The parent reactor contains the five required modules:

| Module | Phase-01 responsibility |
| --- | --- |
| `mathproofmesh-contracts` | version metadata, package documentation, and the future stable contract boundary |
| `mathproofmesh-core` | framework-free core boundary marker and graph library ownership |
| `mathproofmesh-server` | minimal Spring Boot application plus Spring Modulith and Temporal smoke tests |
| `mathproofmesh-desktop` | JavaFX/JNA boundary depending forward on server |
| `mathproofmesh-compatibility` | migration fixtures, baseline parity, and ArchUnit dependency checks |

`python-compute-service` is an independent packaging boundary. In phase 01 it
contains only its own `pyproject.toml` and boundary documentation; it imports
neither the outer frozen Python package nor any Java runtime module.

The required Maven Wrapper files were reused without regeneration:

| File | SHA-256 |
| --- | --- |
| `mvnw` | `cae96cef89ebea3531221f4ae17c23cf8edf67d00eae8306d4186ae1bbed4d02` |
| `mvnw.cmd` | `46eedb8419bd14fe70d5bb2916d7b6f51806e51b39d5b76a42610384ca929c1c` |
| `.mvn/wrapper/maven-wrapper.properties` | `51b221ede4e074d19bb44d6d485bb7b298e77874572a09697aba0a9e234e0745` |

No `maven-wrapper.jar` or `MavenWrapperDownloader.java` exists.

## Dependency Lock

The reactor fixes Java release 25 and imports these BOMs:

| Dependency family | Version |
| --- | --- |
| Spring Boot | 4.1.0 |
| Spring Modulith | 2.1.0 |
| Temporal Java SDK | 1.37.0 |
| JavaFX Windows artifacts | 25.0.4 |
| Picocli | 4.7.7 |
| JGraphT | 1.5.3 |
| JNA JPMS | 5.19.1 |
| ArchUnit | 1.4.2 |

The first complete OWASP scan found vulnerabilities in transitive versions
selected by the framework BOMs. The final graph therefore applies these
reviewed patch overrides:

| Component | Final version | Reason |
| --- | --- | --- |
| Jackson Databind | 2.21.5 | remediates CVE-2026-54515 |
| Log4j API and `log4j-to-slf4j` | 2.25.5 | remediates CVE-2026-49844 |
| PostgreSQL JDBC | 42.7.12 | remediates CVE-2026-54291 |

Testcontainers 2.0.5 remains fixed in dependency management and in the
phase-00 preflight lock for later integration phases. It is not a phase-01
dependency because phase 01 has no container-backed test. This removes the
otherwise unused `docker-java-transport-zerodep` graph that embedded vulnerable
HttpCore 5.3.6. No CVE suppression was added.

The full cumulative lock is `migration/dependency-lock.yaml`. The immutable
phase-00 container references remain:

- `postgres@sha256:1961f96e6029a02c3812d7cb329a3b03a3ac2bb067058dec17b0f5596aca9296`
- `temporalio/temporal@sha256:59561b9ef060eaeb1f46cb6a1842d6cbdd8a393eb3b6d315ecef5fe2f0b1d7a6`

## Build Gates

Final online verification:

```text
.\mvnw.cmd -B verify
```

Result: `BUILD SUCCESS` for the parent and all five modules. Evidence:
`migration/logs/phase-01-verify.log`.

Final offline verification, after the final `dependency:go-offline` run:

```text
.\mvnw.cmd -B -o verify
```

Result: `BUILD SUCCESS` for the parent and all five modules. Evidence:
`migration/logs/phase-01-verify-offline.log`.

The final test reports contain 11 tests, 0 failures, 0 errors, and 0 skipped:

| Test | Cases | Gate |
| --- | ---: | --- |
| `VersionInfoTest` | 1 | Java version metadata |
| `ModulithStructureTest` | 1 | Spring Modulith model verification |
| `TemporalTestingSmokeTest` | 1 | in-process Temporal testing service |
| `MathProofMeshApplicationIT` | 1 | Spring Boot context without business endpoints |
| `ModuleDependencyRulesTest` | 4 | ArchUnit module direction |
| `MathProofMeshTestFixturesTest` | 2 | isolated demo and artifact-store fixtures |
| `VersionBaselineParityTest` | 1 | frozen Python version metadata parity |

Maven Enforcer passed the following rules throughout the reactor:

- Java version 25 and Maven version 3.9.16;
- release-only external dependencies;
- dependency convergence;
- no duplicate dependency declarations;
- no duplicate classes;
- per-module forward dependency boundaries.

The only `SNAPSHOT` coordinates in the dependency tree are the five modules
and parent of this reactor at `0.8.0-SNAPSHOT`. There are zero external
snapshot dependencies. SpotBugs with FindSecBugs reports zero findings in
every module. A placeholder scan over phase-01 source and configuration found
no `TODO`, `FIXME`, `UnsupportedOperationException`, or placeholder return.

## Dependency Evidence

The final commands completed successfully:

```text
.\mvnw.cmd -B dependency:go-offline
.\mvnw.cmd -B -o dependency:tree -Dverbose -DoutputType=text
.\mvnw.cmd -B -o dependency:resolve-plugins
```

Evidence:

| Artifact | SHA-256 |
| --- | --- |
| `migration/reports/phase-01-dependency-tree.txt` | `f46c69aa7b94eca90d0660236f4dd372968f173e76bbbe7ebb50f773f2a9c3aa` |
| `migration/reports/phase-01-plugin-resolution.txt` | `d2ca6518462fa413c386d10f2e03b5aac4f16f5b19d0e510009ab0c65225b54e` |

The dependency tree contains no version-conflict omission, no Testcontainers
or Docker Java runtime, and resolves Jackson Databind 2.21.5, Log4j 2.25.5,
and PostgreSQL JDBC 42.7.12. Published dependency, plugin, SBOM, and security
reports contain no local Maven repository or workstation path.

## SBOM Gate

The explicit aggregate goal succeeded:

```text
.\mvnw.cmd -B org.cyclonedx:cyclonedx-maven-plugin:2.9.2:makeAggregateBom
```

`migration/reports/phase-01-sbom.json` is a validated CycloneDX 1.6 JSON BOM
with 86 components and 87 dependency entries.

SHA-256:
`797d35fc270e385a5c14094a3454817080a2971af89f87b8ddc789dcd41b8a67`.

## OWASP Gate

The authoritative NVD 2.0 feeds were downloaded into the target-local cache.
The final scan reused that current cache with updates disabled for the
repeatable gate:

```text
.\mvnw.cmd -B org.owasp:dependency-check-maven:12.2.2:aggregate -DautoUpdate=false
```

The .NET Assembly Analyzer is disabled because this is a Java-only reactor.
Java archive, JAR, dependency merging, CPE, NVD CVE, suppression, CISA KEV,
bundling, and unused-suppression analysis remain active.

Final result:

| Metric | Result |
| --- | ---: |
| Dependencies analyzed | 93 |
| Vulnerable dependencies | 0 |
| Vulnerability findings | 0 |
| High or critical findings | 0 |
| Build threshold | CVSS 7.0 |
| Maven result | `BUILD SUCCESS` |

Reports:

| Artifact | SHA-256 |
| --- | --- |
| `migration/reports/dependency-check/dependency-check-report.json` | `237883fda45b79efca94754cfd883efdce610d092f2f97795f309fda53e20e07` |
| `migration/reports/dependency-check/dependency-check-report.html` | `71b22813619633252efdda5c944d73cdc00bbabfd4adc289c85891f05d28cf04` |

Dependency-Check writes absolute artifact paths by default. Before
publication, the 120 Maven-repository prefixes and four reactor POM prefixes
in each report were mechanically normalized to `${MAVEN_REPOSITORY}` and
`${PROJECT_ROOT}`. The reports were then reparsed and rescanned for local
paths. The unmodified tool output is retained only as execution evidence in
`migration/logs/phase-01-dependency-check-raw`; its JSON and HTML SHA-256
values are respectively
`579af6c5cf7f3726999458fb2a41ca3dc6236c9d39a0ffd7bdd0d62e4fefa74e`
and
`844f223e98362f0a8bc4ee0c317f1e62ce12b649ec8bbede876649cbe9a8ff24`.

The failed high-risk scan and the pre-remediation SBOM/dependency evidence
remain archived under names containing `before-security-remediation`; they are
audit history, not the final gate result.

## License Gate

The target `LICENSE` is byte-for-byte identical to the frozen source license:

```text
SHA-256 1e2ed19e10224e2cd6674f0377a6ea1ede3aed8866dd498409320821ea55f139
```

`NOTICE` documents the Java migration, independent Python sidecar boundary,
and third-party licensing. Every one of the five ordinary module JARs contains
both `META-INF/LICENSE` and `META-INF/NOTICE`.

## Mapping State

The phase-specific mapping rows were completed:

| Inventory | Source row | Final status |
| --- | --- | --- |
| `migration/source-state.csv` | `src/mathproofmesh/__init__.py` | `migrated` |
| `migration/test-state.csv` | `tests/conftest.py` | `ported` |
| `migration/auxiliary-state.csv` | `.gitignore` | `translated_verified` |
| `migration/auxiliary-state.csv` | `LICENSE` | `copied_verified` |

After the updates, the inventories still contain exactly 142 source rows, 167
test rows, and 92 auxiliary rows. Their union is exactly 401 unique frozen
paths, with no duplicate, missing, or extra path.

## Source Immutability

The post-phase source check reports:

```text
SOURCE IMMUTABILITY: PASS
files=401
manifest_sha256=9f3def2bec8cea99d0a18b51fbb5fa8ce53f44a24ce869f6495cac809b7e3770
```

Evidence: `migration/logs/phase-01-source-immutability.log`.

This independently confirms that no phase-01 work changed or added any file
in the outer frozen source snapshot.

## Attempt Ledger

Failed commands were retained rather than hidden:

1. The first `validate` exposed missing explicit JavaFX classifier versions.
   The second exposed duplicate-class analysis running before reactor
   artifacts existed. Versions were fixed and the duplicate-class rule was
   moved to `verify`.
2. Early `verify` runs exposed an ambiguous JUnit overload, Failsafe using the
   repackaged Boot archive as test classes, and the server Boot archive
   replacing the ordinary reactor JAR. Tests were made unambiguous, Failsafe
   now uses compiled classes, and Boot attaches an `exec` classifier.
3. The first offline CycloneDX call correctly failed because that goal requires
   Maven online mode. It was rerun online and validated successfully.
4. The initial default NVD update was stopped after proving too slow, its
   partial cache was purged, and the scan was rerun against official NVD 2.0
   feeds.
5. The first complete OWASP scan failed the CVSS gate on the four dependency
   groups documented above. Patch versions were applied and the unused
   Testcontainers graph was removed.
6. A repeat scan exposed the irrelevant .NET Assembly Analyzer failing its
   archive traversal defense while extracting `GrokAssembly.dll`. That
   analyzer was disabled for this Java-only reactor; the final scan passed
   with zero findings.

No failed attempt was relabeled as a pass. The final online build, offline
build, SBOM, OWASP report, dependency evidence, mapping coverage, licenses,
and source immutability checks are the evidence for this PASS decision.

## Stop Condition

Phase 01 is complete. `migration/state.json` records phase 01 as `passed`,
keeps `current_phase` at `01`, and records `phase_02_started: false`.
No phase-02 task, mapping row, source file, or gate was started.
