# Phase 17 Remediation Attempts

This audit note distinguishes non-final attempts from the final phase result.

## Coverage Attempt

The first offline finalization attempt completed the Maven reactor but stopped
at `core_overall_branch_ge_75`. That execution measured 5,039 of 6,719 core
branches, or 74.996279%. The result was not accepted.

`Phase17ResidualBranchCoverageTest` added public-boundary cases for null
normalization, record validation, ledger update reuse, and exact-expression
null evaluation. The final clean online and offline runs measured 75.100461%
and passed.

## Database Benchmark Attempt

A repeated online attempt reached
`Phase17CheckpointOutboxPerformanceIT` after several complete database runs.
The original benchmark created a new JDBC connection for each of thousands of
operations and exhausted Windows ephemeral ports while releasing Outbox rows.
The result was not accepted.

The benchmark now uses a transaction-aware `SingleConnectionDataSource`,
closes it after the suite, and retains all 1,000 Checkpoint inserts, 1,000
Outbox events, release/reclaim, two-attempt, and publish assertions. A targeted
Testcontainers run passed in 7.135 seconds. Both subsequent final clean runs
also passed.

## Final Evidence

Only these files represent the final result:

- `migration/reports/phase-17-verify-online.log`
- `migration/reports/phase-17-verify-offline.log`
- `migration/reports/phase-17-coverage.json`
- `migration/reports/phase-17-performance.json`
- `migration/reports/phase-17-gates.json`
