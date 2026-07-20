# Migrating From 0.6 To 0.7

## Compatibility First

Old YAML files load unchanged. The default remains:

```yaml
topology:
  mode: legacy_sparse
```

This retains v0.6 scheduling, CLI and HTTP behavior. The existing `solve`,
`resume`, `probe` and `serve` commands and `/solve`, `/solve/stream`, `/resume`
and `/resume/stream` endpoints are unchanged.

## Recommended Rollout

Start with the shipped formal DeepSeek profile, where typed communication and
route teams are enabled while proof graph and Inspiration Engine use `shadow`.
Inspect reports before making control decisions active.

```yaml
topology:
  mode: hierarchical_sparse
  typed_communication: {enabled: true}
  route_teams: {enabled: true}
  cross_route: {enabled: true}
  proof_graph: {enabled: true, mode: shadow}
  typed_memory: {enabled: true, strict_fact_gate: true}
  inspiration: {enabled: true, mode: shadow}
```

Use `config.deepseek-v4-pro.topology-active.yaml` only after shadow diagnostics
are acceptable. It activates both graph and inspiration materialization. CLI
overrides are `--topology-mode`, `--proof-graph-mode`,
`--disable-route-teams` and `--disable-cross-route`.

## Checkpoint Migration

A v0.6 checkpoint without broker, graph or inspiration state initializes those
stores empty, preserves all `ProofCheckpoint` objects and records
`checkpoint_migrated_to_v0_7`. A v0.7 checkpoint preserves exactly-once prompt
delivery and stable inspiration materialization IDs.

## Rollback

Set `topology.mode: legacy_sparse`, or keep hierarchical routing while setting
proof graph and inspiration modes to `shadow`/`off`. Do not reuse an active
v0.7 checkpoint as a v0.6 checkpoint; instead resume with 0.7 in legacy mode or
start a new run.

## Known Limits

The analogy library is local, formalization is a backend interface rather than
full Lean coverage, representation switching does not implement a complete
geometry engine, and benchmark token/cost values are offline proxies. Missing
analogy or heterogeneous-provider resources degrade safely and are reported.
