MathProofMesh v0.5.1 changed files

Copy the contents of this directory over the root of your local Math-Agents repository,
preserving the directory structure. These files are the complete v0.5.1 scheduler,
continuation, synthesis failover, final-audit, configuration, documentation, and test
changes relative to the clean v0.5.0 baseline.

Do not copy caches, dist files, .env, runs, or temporary automation files.

After copying, run:
  python -m pip install -e ".[dev,server]"
  python -m pytest -q
  python -m ruff check .
  python -m ruff format --check .
  python -m compileall -q src

Expected: 60 passed, 1 skipped; Ruff and compileall pass.
