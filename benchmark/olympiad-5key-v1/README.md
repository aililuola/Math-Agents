# MathProofMesh Olympiad Five-Key Benchmark

This directory contains the validation-only harness for benchmark
`olympiad-5key-v1`. It is pinned to baseline commit
`ea94a34041fd32a4f94ecb1a3532ddc314430a47`.
That commit remains the immutable Benchmark origin. Each Run separately records
the actual execution branch, commit, and dirty status captured before the
campaign starts, so a repair campaign never masquerades as the origin commit.

Only the text in `problems/Pxx/problem.txt` may enter provider prompts. Problem
metadata, difficulty labels, evaluation checklists, historical results, and
external solutions are deliberately absent from those files.

The default test suite is offline and uses a fake provider. Real provider runs
are default-deny and additionally require all five named environment secrets,
`BENCHMARK_ALLOW_REAL_PROVIDER=true`, and an explicit positive
`BENCHMARK_GLOBAL_COST_CAP_USD` that covers the immutable worst-case estimate.
The launcher must also set `BENCHMARK_EXECUTION_GIT_DIRTY` to the exact
pre-launch `git status --porcelain=v1` result (`true` when non-empty, otherwise
`false`); branch and HEAD are read directly from `.git` and cannot be supplied
by the provider environment.
Secrets are never written to this directory, logs, reports, checksums, or run
bundles.

Generated run evidence lives below `results/` and is intentionally ignored by
Git. The final sanitized ZIP is also local-only.

## Budget headroom revision

The first real P01/T1 campaign remains preserved as stopped evidence. It found
that the former `characters / 4` input estimate allocated 1,096 input tokens
for a provider-reported use of 1,151. New campaigns use a UTF-8-aware estimate
with tokenizer and message-framing headroom. Scheduler action envelopes reserve
16,000 input tokens per physical call on average; unused reservations are still
released when the provider reports actual usage.

| Tier | Calls | Rounds | Run tokens | Output tokens per call |
| --- | ---: | ---: | ---: | ---: |
| SMOKE | 24 | 6 | 1,152,000 | 32,000 |
| CORE | 40 | 8 | 1,920,000 | 32,000 |
| ADVANCED | 64 | 12 | 3,072,000 | 32,000 |
| STRESS | 96 | 16 | 4,608,000 | 32,000 |

Across all 34 isolated runs the immutable ceiling is 2,128 calls and
102,144,000 tokens. At the frozen worst-case output price this is USD 88.86528,
leaving USD 11.13472 below the separately enforced USD 100 user cap. Artifact
recovery, post-failure recovery, and JSON repair share the same 32,000-token
ceiling so a recovery call cannot silently fall back to a smaller envelope.
The hard overrun, settlement, authority, secret, and checkpoint gates remain
unchanged.

The real run is deliberately excluded from ordinary test execution. After the
seven required environment variables have been injected into the launching
process, invoke it explicitly from the repository root:

```powershell
.\mvnw.cmd -pl mathproofmesh-desktop -am `
  "-Dtest=OlympiadFiveKeyRealBenchmarkTest" `
  "-Dbenchmark.execute.real=true" `
  "-Dsurefire.failIfNoSpecifiedTests=false" `
  test
```

If a hard invariant stops a real campaign, do not resume provider execution.
After writing `aggregate/HARD-GATE-STOP-REPORT.md`, package the partial evidence
without network access:

```powershell
.\mvnw.cmd -pl mathproofmesh-desktop -am `
  "-Dtest=OlympiadStoppedCampaignPackagerTest" `
  "-Dbenchmark.package.stopped=<absolute-campaign-path>" `
  "-Dsurefire.failIfNoSpecifiedTests=false" `
  test
```
