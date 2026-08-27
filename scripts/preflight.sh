#!/usr/bin/env sh
set -eu

TARGET_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
EXPECTED_MANIFEST=9f3def2bec8cea99d0a18b51fbb5fa8ce53f44a24ce869f6495cac809b7e3770
EXPECTED_POSTGRES=postgres@sha256:1961f96e6029a02c3812d7cb329a3b03a3ac2bb067058dec17b0f5596aca9296
EXPECTED_TEMPORAL=temporalio/temporal@sha256:59561b9ef060eaeb1f46cb6a1842d6cbdd8a393eb3b6d315ecef5fe2f0b1d7a6

if [ -x "$TARGET_ROOT/.tools/jdk-25/bin/java" ]; then
  JAVA_HOME="$TARGET_ROOT/.tools/jdk-25"
elif [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA_HOME=$JAVA_HOME
else
  echo "JDK 25 not found" >&2
  exit 1
fi
export JAVA_HOME
PATH="$JAVA_HOME/bin:$PATH"
export PATH

java_version=$("$JAVA_HOME/bin/java" -version 2>&1)
printf '%s\n' "$java_version" | grep 'version "25\.' >/dev/null
"$JAVA_HOME/bin/javac" -version
git --version

if [ -x "$TARGET_ROOT/.venv-baseline/bin/python" ]; then
  PYTHON="$TARGET_ROOT/.venv-baseline/bin/python"
elif [ -x "$TARGET_ROOT/.venv-baseline/Scripts/python.exe" ]; then
  PYTHON="$TARGET_ROOT/.venv-baseline/Scripts/python.exe"
else
  PYTHON=$(command -v python3 || command -v python)
fi
"$PYTHON" -c 'import sys; assert sys.version_info >= (3, 11); print(sys.version)'

DOCKER=$(command -v docker || true)
if [ -z "$DOCKER" ] && command -v cygpath >/dev/null 2>&1 && [ -n "${LOCALAPPDATA:-}" ]; then
  candidate="$(cygpath -u "$LOCALAPPDATA")/Programs/DockerDesktop/resources/bin/docker.exe"
  if [ -x "$candidate" ]; then
    DOCKER=$candidate
  fi
fi
if [ -z "$DOCKER" ]; then
  echo "Docker CLI not found" >&2
  exit 1
fi
"$DOCKER" version
"$DOCKER" compose version

postgres_image=$(sed -n 's/^POSTGRES_IMAGE=//p' "$TARGET_ROOT/migration/image-lock.env")
temporal_image=$(sed -n 's/^TEMPORAL_DEV_IMAGE=//p' "$TARGET_ROOT/migration/image-lock.env")
[ "$postgres_image" = "$EXPECTED_POSTGRES" ]
[ "$temporal_image" = "$EXPECTED_TEMPORAL" ]
"$DOCKER" image inspect "$postgres_image" >/dev/null
"$DOCKER" image inspect "$temporal_image" >/dev/null
temporal_version=$(
  "$DOCKER" run --rm --network none --read-only --cap-drop ALL \
    --security-opt no-new-privileges:true "$temporal_image" --version
)
printf '%s\n' "$temporal_version" | grep 'temporal version 1\.8\.1' >/dev/null
printf '%s\n' "$temporal_version" | grep 'Server 1\.31\.2' >/dev/null

grep '^wrapperVersion=3\.3\.4$' "$TARGET_ROOT/.mvn/wrapper/maven-wrapper.properties" >/dev/null
grep '^distributionType=only-script$' "$TARGET_ROOT/.mvn/wrapper/maven-wrapper.properties" >/dev/null
grep '^distributionSha256Sum=5af3b743dd8b876b5c45da33b676251e5f1687712644abb4ee519ca56e1d89ce$' \
  "$TARGET_ROOT/.mvn/wrapper/maven-wrapper.properties" >/dev/null
test ! -e "$TARGET_ROOT/.mvn/wrapper/maven-wrapper.jar"
test ! -e "$TARGET_ROOT/.mvn/wrapper/MavenWrapperDownloader.java"

export MAVEN_USER_HOME="$TARGET_ROOT/.cache/maven-wrapper-home"
export TMPDIR="$TARGET_ROOT/.cache/tmp"
mkdir -p "$MAVEN_USER_HOME" "$TMPDIR"
"$TARGET_ROOT/mvnw" --version
"$TARGET_ROOT/mvnw" "-Dmaven.repo.local=$TARGET_ROOT/.cache/maven-repository" \
  -o -f "$TARGET_ROOT/migration/preflight/pom.xml" -B -ntp validate
"$TARGET_ROOT/mvnw" "-Dmaven.repo.local=$TARGET_ROOT/.cache/maven-repository" \
  -o -f "$TARGET_ROOT/migration/preflight/pom.xml" -B -ntp \
  dependency:go-offline >/dev/null

grep -E '^759 passed, .* in [0-9.]+s$' \
  "$TARGET_ROOT/migration/logs/python-baseline.log" >/dev/null
PYTHONDONTWRITEBYTECODE=1 "$PYTHON" - "$TARGET_ROOT" <<'PY'
import csv
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
with (root / "migration/source-state.csv").open(encoding="utf-8", newline="") as handle:
    source = list(csv.DictReader(handle))
with (root / "migration/test-state.csv").open(encoding="utf-8", newline="") as handle:
    tests = list(csv.DictReader(handle))
with (root / "migration/auxiliary-state.csv").open(encoding="utf-8", newline="") as handle:
    auxiliary = list(csv.DictReader(handle))
paths = (
    [row["source_file"] for row in source]
    + [row["python_test_file"] for row in tests]
    + [row["source_file"] for row in auxiliary]
)
assert (len(source), len(tests), len(auxiliary)) == (142, 167, 92)
assert len(paths) == len(set(paths)) == 401
PY

"$TARGET_ROOT/scripts/check-original-immutable.sh"
actual_manifest=$(
  "$PYTHON" -c \
    'import hashlib,pathlib,sys; print(hashlib.sha256(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest())' \
    "$TARGET_ROOT/SOURCE_SNAPSHOT_SHA256SUMS.txt"
)
[ "$actual_manifest" = "$EXPECTED_MANIFEST" ]

echo "PHASE 00 PREFLIGHT: PASS"
