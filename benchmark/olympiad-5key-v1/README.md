# MathProofMesh Olympiad Five-Key Benchmark

This directory contains the validation-only harness for benchmark
`olympiad-5key-v1`. It is pinned to baseline commit
`ea94a34041fd32a4f94ecb1a3532ddc314430a47`.

Only the text in `problems/Pxx/problem.txt` may enter provider prompts. Problem
metadata, difficulty labels, evaluation checklists, historical results, and
external solutions are deliberately absent from those files.

The default test suite is offline and uses a fake provider. Real provider runs
are default-deny and additionally require all five named environment secrets,
`BENCHMARK_ALLOW_REAL_PROVIDER=true`, and an explicit positive
`BENCHMARK_GLOBAL_COST_CAP_USD` that covers the immutable worst-case estimate.
Secrets are never written to this directory, logs, reports, checksums, or run
bundles.

Generated run evidence lives below `results/` and is intentionally ignored by
Git. The final sanitized ZIP is also local-only.
