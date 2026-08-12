#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)

if [ -x "$ROOT/.tools/jdk-25/bin/java" ]; then
  JAVA_HOME="$ROOT/.tools/jdk-25"
  export JAVA_HOME
elif [ -z "${JAVA_HOME:-}" ]; then
  echo "JDK 25 is required through JAVA_HOME" >&2
  exit 1
fi

MAVEN_USER_HOME="$ROOT/.cache/maven-wrapper-home"
TMPDIR="$ROOT/.cache/tmp"
export MAVEN_USER_HOME TMPDIR
mkdir -p "$MAVEN_USER_HOME" "$TMPDIR"

OFFLINE_ARGS=
if [ "${1:-}" = "--offline" ]; then
  OFFLINE_ARGS="-o"
fi

cd "$ROOT"
./mvnw -B -ntp $OFFLINE_ARGS clean verify
if [ -n "$OFFLINE_ARGS" ]; then
  test -f "$ROOT/migration/reports/phase-17-sbom.json"
  test -f "$ROOT/migration/reports/dependency-check/dependency-check-report.json"
else
  ./mvnw -B -ntp \
    org.cyclonedx:cyclonedx-maven-plugin:2.9.2:makeAggregateBom
  ./mvnw -B -ntp \
    org.owasp:dependency-check-maven:12.2.2:aggregate
fi
if command -v python3 >/dev/null 2>&1; then
  PYTHON=python3
else
  PYTHON=python
fi
$PYTHON ./scripts/sanitize-dependency-check-report.py
$PYTHON ./scripts/verify-coverage.py
$PYTHON ./scripts/verify-security.py
if [ -n "$OFFLINE_ARGS" ]; then
  test -f "$ROOT/migration/baseline/phase-17-performance-reference.json"
  test -f "$ROOT/migration/reports/phase-17-performance.json"
else
  $PYTHON ./scripts/phase17-performance.py
fi
./scripts/check-original-immutable.sh

echo "FULL VERIFICATION: PASS"
