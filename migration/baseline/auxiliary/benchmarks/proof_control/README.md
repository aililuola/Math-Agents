# Proof-Control Mock Benchmark

This deterministic, offline suite loads ten data fixtures and checks fourteen
proof-control contracts: overstrong targets, finite-range gaps, eventual
scope, projection information loss, image inclusion, abstract-realizer
repair, occurrence induction, common-mode assumptions, obligation
compression, delivered-but-unused messages, Near-Miss repair, falsification
fast lane, continue-deepening, and synthesis readiness.

Run:

```powershell
python benchmarks/proof_control/run_mock_benchmark.py
```

Optionally write the deterministic report:

```powershell
python benchmarks/proof_control/run_mock_benchmark.py --output path/to/result.json
```

The runner calls no provider and stores no provider response. It exercises the
actual proof-control analyzers and compares off, shadow, and active gate
semantics. Any failed component contract exits nonzero.
