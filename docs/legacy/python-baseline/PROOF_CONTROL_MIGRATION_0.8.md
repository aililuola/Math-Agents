# Migrating From 0.7 To 0.8

## Compatibility

Existing v0.7 YAML remains valid. `TopologyConfig` supplies:

```yaml
topology:
  proof_control:
    enabled: false
    mode: off
```

With proof control off, checkpoint shape and runtime behavior remain
v0.7-compatible. Broker delivery, Route Teams, Proof Graph, Typed Memory,
Inspiration, DeepSeek SSE, CLI/HTTP entry points, and checkpoint/resume are not
replaced.

## Recommended Rollout

1. Run the existing v0.7 configuration with proof control off.
2. Use `config.deepseek-v4-pro.proof-control-shadow.yaml`. Compare recorded
   recommendations with actual route, deepening, and synthesis behavior.
3. Enable `config.deepseek-v4-pro.proof-control-active.yaml` only after scope,
   route-admission, and readiness diagnostics are acceptable.

Active proof control requires hierarchical sparse topology, an active Proof
Graph, Typed Memory, and Typed Communication. Invalid combinations fail config
validation rather than silently falling back.

## Checkpoint Migration

An enabled 0.8 stage checkpoint uses schema `0.8` and adds
`proof_control_state`. The sidecar contains stable mappings and lists for goal
links, roles, scopes, risks, structures, realizers, repairs, induction
proposals, failures, blueprint rewrites, bottleneck clusters, assumptions,
message contracts and use receipts, Near-Misses, all three gates, core-debt
history, falsification outcomes, and events.

When resuming a v0.7 checkpoint:

- all v0.7 proof checkpoints, graph, Broker, Typed Memory, Inspiration, budget,
  and route state are restored normally;
- missing proof-control state initializes empty;
- a deterministic migration event is recorded once;
- repeated resume does not duplicate receipts, gate records, or events.

When proof control is off, the orchestrator continues to emit the v0.7
checkpoint shape without a new required payload.

## Rollback

Set:

```yaml
topology:
  proof_control:
    enabled: false
    mode: off
```

This disables proof-control scheduling and gate effects. Sidecar artifacts from
an earlier enabled run may remain available for audit but are not mathematical
premises.

For a clean v0.7 consumer, use a checkpoint created with proof control off or
start a new run. Do not manually strip an active checkpoint while a run is in
progress.

## Frozen Settings

The migration does not change any `max_output_tokens` field, continuation
segment length/count, Deep Exploration tier, high-tier threshold, or partial
repair output limit. Both v0.8 profiles are tested against
`config.deepseek-v4-pro.topology-active.yaml` after removing only
`system_name` and `topology.proof_control`.

No API key or new environment variable is required. Automated validation uses
only deterministic Mock responders and registered local tools; it makes no real
provider call.

## Validation

Run:

```powershell
python -m pytest -q
python -m ruff check .
python -m ruff format --check .
python -m compileall -q src tests benchmarks
python benchmarks/topology/run_mock_benchmark.py
python benchmarks/proof_control/run_mock_benchmark.py
```

The proof-control benchmark covers fourteen offline contracts across ten data
fixtures and compares off, shadow, and active gate semantics.
