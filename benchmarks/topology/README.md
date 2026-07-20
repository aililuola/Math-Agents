# Topology Mock Benchmark

This deterministic suite covers six contract cases: shared bridge, exact
counterexample, mechanism duplicate, bounded-computation scope, exactly-once
resume and proof mutation. It compares the eleven variants required by the
0.7 specification, including all four Inspiration Engine ablations.

Run:

```powershell
python -m benchmarks.topology.run_mock_benchmark
```

Regenerate the checked result:

```powershell
python -m benchmarks.topology.run_mock_benchmark --output benchmarks/topology/mock_benchmark_results.json
```

The runner first executes local component contracts for Broker/Graph,
inspiration generation, analogy limits, novelty and resume. Variant metrics
then apply the declared feature switches to the fixed cases. `calls`, `tokens`
and `estimated_cost_usd` are labeled mock estimates; there are zero API calls
and no provider response is stored.
