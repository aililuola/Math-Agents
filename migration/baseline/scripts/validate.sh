#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

export PYTHONPATH="$ROOT:$ROOT/src${PYTHONPATH:+:$PYTHONPATH}"

python -m compileall -q src tests benchmarks
if command -v ruff >/dev/null 2>&1; then
  ruff check .
  ruff format --check .
else
  printf '%s\n' 'warning: ruff is not installed; skipping lint' >&2
fi
python -m pytest -q

DEMO_ROOT="${1:-$ROOT/validation-runs}"
rm -rf "$DEMO_ROOT/demo_sum_of_odds"
python -m mathproofmesh.cli demo --run-root "$DEMO_ROOT" --continuation --activity off
