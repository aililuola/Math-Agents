#!/usr/bin/env sh
set -eu

target_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
source_root="$target_root/.work/source"
python="$target_root/.venv-baseline/bin/python"

export PYTHONDONTWRITEBYTECODE=1
export PYTHONPYCACHEPREFIX="$target_root/.cache/pycache"
export PYTHONPATH="$source_root/src"
export PYTEST_ADDOPTS="-o cache_dir=$target_root/.cache/pytest --basetemp=$target_root/.cache/pytest-tmp"
export HOME="$target_root/.work/home"
export TMPDIR="$target_root/.work/tmp"
export MPM_ALLOW_LIVE_PROVIDER_CALLS=false
unset DEEPSEEK_API_KEY OPENAI_API_KEY ANTHROPIC_API_KEY GEMINI_API_KEY || true

cd "$source_root"
exec "$python" -m pytest -q
