# Testing

## Full Matrix

`scripts/verify-all.ps1` and `scripts/verify-all.sh` run the release matrix:

- unit, contract, property, parameterized, and authority-named parity tests;
- PostgreSQL 18 Testcontainers and Flyway integration tests;
- Temporal `TestWorkflowEnvironment`, replay, signal/update, and crash tests;
- Mock provider, SSE parsing, usage, retry, and bounded concurrency tests;
- restricted Python sidecar protocol and differential tests;
- REST, resumable SSE, CLI, reporting, and observability tests;
- JavaFX lifecycle, WebView boundary, DPAPI, and package smoke tests;
- legacy import, migration, quarantine, resume, and shadow comparison tests;
- source immutability, dependency convergence, duplicate classes,
  SpotBugs/FindSecBugs, CycloneDX, and OWASP Dependency-Check.

The online run refreshes SBOM and vulnerability evidence. The offline run
proves all build dependencies are locked in the target-local cache.

## Coverage

JaCoCo reports are generated below `target/modules/*/site/jacoco`.
`scripts/verify-coverage.py` enforces:

- contracts line coverage at least 90 percent and branch coverage at least
  85 percent;
- core line coverage at least 85 percent and branch coverage at least
  75 percent;
- testable server and desktop business code line coverage at least 70
  percent.

Only explicitly marked generated defensive accessor blocks in contracts are
reported separately; constructors, validators, schemas, canonical JSON, all
handwritten domain policy, and raw server/desktop module lines remain in the
gates. Critical scenarios for hashes, message admission, memory promotion,
counterexample propagation, checkpoint CAS, leases, outbox/inbox, control
actions, and workflow decisions are explicitly inventoried in the coverage
report.

## Performance

`scripts/benchmark-phase17.ps1` records hardware, JVM flags, warmup,
iteration counts, elapsed time, throughput, and resource observations for:

- 10,000 message admission/deduplication/delivery operations;
- 100 concurrent bounded Mock calls;
- large proof-graph closure, counterexample propagation, and debt;
- 1,000 checkpoint/outbox retries;
- Python worker cold start and warm calls;
- SSE stream and resume;
- Temporal multi-route replay and Continue-As-New.

The first accepted report is the same-machine reference. Later results may not
regress more than 20 percent without a written explanation and approval.

## Python Authority

The final baseline uses `.venv-baseline` and `.work/source`, redirects cache,
home, temporary, and application-data paths under the target, disables live
providers, and expects exactly `759 passed`. It then recomputes the frozen
401-file manifest SHA-256:

```text
9f3def2bec8cea99d0a18b51fbb5fa8ce53f44a24ce869f6495cac809b7e3770
```

The only authority archive is
`migration/input/Math-Agents-feature-mathproofmesh-v0.8.0-goal-plan-failure-utility-control.zip`
with SHA-256
`5e09eb8a704ea5f841078bafcc1c5e72e4d17a76b2fef7a344e41d55e013c1b2`.

## Failure Policy

No required test may be disabled or converted to an assumption. A flaky,
environmental, or security failure remains a failed gate until its cause is
fixed and the complete command passes again. Earlier failed attempts are
retained as audit evidence; only the final successful run supports release.
