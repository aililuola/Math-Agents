#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
JAVA_HOME="$ROOT/.tools/jdk-25"
MAVEN_USER_HOME="$ROOT/.cache/wrapper-home-link"
TEMP="$ROOT/.cache/tmp"
TMP="$TEMP"
export JAVA_HOME MAVEN_USER_HOME TEMP TMP

cd "$ROOT"
./mvnw -o -pl :mathproofmesh-core \
  -Dtest=DirectedComputationSmokeParityTest test
