MathProofMesh v0.6.0 changed files

Copy the contents of this directory over the root of your local Math-Agents repository,
preserving the directory structure. These files are the complete v0.6.0 reasoning-first
computation, typed-tool, evidence replay, sandbox, configuration, documentation, and
test changes on top of the stable v0.5.1 workflow.

Do not copy caches, dist files, .env, runs, or temporary automation files.

After copying, run:
  python -m pip install -e ".[dev,server]"
  python -m pytest -q
  python -m ruff check .
  python -m ruff format --check .
  python -m compileall -q src

Expected: 75 passed, 1 skipped; Ruff and compileall pass.
