# Deterministic Topology Benchmark

This Java compatibility benchmark validates seven immutable fixtures that
cover six component contracts and eleven feature variants. It checks the
typed broker, bounded computation evidence, contradiction handling, semantic
route deduplication, exactly-once resume, mutation review and sparse bridge
delivery.

The checked Python result is baseline evidence, not a new Java execution
result. A Java run writes:

- `target/benchmark-reports/topology-java.json`
- `target/benchmark-reports/topology-java.md`

Run `scripts/benchmark-topology.ps1` or `scripts/benchmark-topology.sh`. The
suite uses fixed fixture data, has no network dependency and performs exactly
zero provider calls.
