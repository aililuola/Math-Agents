#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
OFFLINE=
if [ "${1:-}" = "--offline" ]; then
  OFFLINE="-o"
fi

"$ROOT/mvnw" -B -ntp $OFFLINE \
  -pl :mathproofmesh-server -am \
  verify \
  -Dtest=Phase17MessagePerformanceBenchmarkTest,Phase17GraphPerformanceBenchmarkTest,Phase17ConcurrentMockPerformanceBenchmarkTest,Phase17PythonSidecarPerformanceBenchmarkTest,Phase17SseResumePerformanceBenchmarkTest,Phase17TemporalPerformanceBenchmarkTest \
  -Dit.test=Phase17CheckpointOutboxPerformanceIT \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dfailsafe.failIfNoSpecifiedTests=false

if command -v python3 >/dev/null 2>&1; then
  python3 "$ROOT/scripts/phase17-performance.py"
else
  python "$ROOT/scripts/phase17-performance.py"
fi
echo "PHASE 17 BENCHMARKS: PASS"
