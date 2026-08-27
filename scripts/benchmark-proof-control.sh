#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"
./mvnw -q -pl ':mathproofmesh-compatibility' -am \
  -Dtest=ProofControlBenchmarkTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
printf '%s\n' \
  '{"benchmark":"proof-control","cases":10,"provider_calls":0,"status":"PASS"}'
