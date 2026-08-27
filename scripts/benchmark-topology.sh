#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
export JAVA_HOME="$ROOT/.tools/jdk-25"
export MAVEN_USER_HOME="$ROOT/.cache/wrapper-home-link"
export TMPDIR="$ROOT/.cache/tmp"

"$ROOT/mvnw" -B -ntp \
  -pl :mathproofmesh-compatibility -am \
  -Dtest=TopologyBenchmarkTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
cat "$ROOT/target/benchmark-reports/topology-java.json"
