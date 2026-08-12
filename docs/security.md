# Secure Build

The build requires JDK 25 and Maven 3.9.16 through the locked Maven Wrapper.
Dependency convergence, release-only external dependencies, duplicate POM
entries, and duplicate classes are enforced before compilation.

Compilation uses UTF-8, Java release 25, parameter-name retention,
`-Xlint:all`, and `-Werror`. Maven `verify` runs unit/integration tests,
JaCoCo, and SpotBugs with FindSecBugs. OWASP Dependency-Check and CycloneDX
are explicit phase gates so their reports remain independently auditable.

The phase-01 dependency graph overrides three framework BOM patch pins:
Jackson Databind 2.21.5, Log4j API/SLF4J bridge 2.25.5, and PostgreSQL JDBC
42.7.12. These versions remediate findings from the first OWASP scan.
Testcontainers 2.0.5 remains locked for later container integration phases,
but is not a phase-01 runtime or test dependency because this phase has no
container-backed tests. The .NET Assembly Analyzer is disabled for this
Java-only reactor; Java archive, JAR, CPE, NVD, suppression, KEV, and bundling
analysis remain enabled.

Secrets, provider responses, run data, database files, Temporal data, caches,
and desktop packaging output are excluded from version control. No live
provider credential or endpoint is needed by the phase-01 build.
