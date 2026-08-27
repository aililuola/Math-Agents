# Goal-Plan-Failure-Utility Control

## Purpose

MathProofMesh 0.8 adds an opt-in control layer over the v0.7 hierarchical
runtime. It measures whether work advances the actual theorem, detects unsafe
scope changes, preserves useful structure after local failure, and gates
routes, repeated deepening, and synthesis.

The layer is not a proof authority. `ProofControlLayer` does not write a
TypedMemory Fact and does not close a Proof Graph obligation. It produces
sidecar metadata and auditable decisions; the existing Broker, Route Team,
verifiers, Typed Memory, and Proof Graph retain their v0.7 responsibilities.

## Modes

The generic default remains disabled:

```yaml
topology:
  proof_control:
    enabled: false
    mode: off
```

The shipped rollout profiles are:

- `config.deepseek-v4-pro.proof-control-shadow.yaml`: records all control
  decisions while preserving v0.7 routing, deepening, and synthesis behavior.
- `config.deepseek-v4-pro.proof-control-active.yaml`: applies route admission,
  continue-deepening, bottleneck, scope, and synthesis decisions.

`active` requires `hierarchical_sparse`, active Proof Graph, Typed Memory, and
Typed Communication. Shadow verdicts use `shadow_block` and never mutate
runtime scheduling.

## The 17 Controls

| WP | Control | Runtime effect |
|---:|---|---|
| 1 | Claim-goal alignment | Classifies equivalent, sufficient, necessary-only, heuristic, unrelated, or unknown links. |
| 2 | Minimal sufficiency | Penalizes overstrong targets and requests a weaker audited bridge. |
| 3 | Proof roles | Separates core bridges, sufficient reductions, technical lemmas, heuristics, and counterexamples. |
| 4 | Core proof debt | Scores the open main-goal dependency closure rather than raw local activity. |
| 5 | Inference risk taxonomy | Records ten common invalid implication patterns. |
| 6 | Scope guard | Prevents weaker or incomparable scope from closing a stronger obligation. |
| 7 | Abstract/realizer separation | Keeps a viable abstract reduction when one concrete construction fails. |
| 8 | Realizer repair | Produces bounded, structure-preserving repair tasks. |
| 9 | Induction/descent selection | Proposes well-founded measures, including occurrence-index measures. |
| 10 | Failure and blueprint control | Classifies execution, bridge, plan, and framing failures and requests bounded rewrites. |
| 11 | Bottleneck compression | Groups semantically similar obligations without deleting original graph nodes. |
| 12 | Common-mode detection | Challenges a shared unverified premise instead of counting route votes as evidence. |
| 13 | Message utility | Requires a target/effect contract and locally verified use before utility credit. |
| 14 | Near-Miss ledger | Retains independently identified salvageable structure after a failed candidate. |
| 15 | Falsification fast lane | Admits exact bounded refutation tasks without weakening computation limits. |
| 16 | Route admission | Passes, rewrites, or blocks routes according to goal alignment and proof role. |
| 17 | Continue and synthesis gates | Stops repeated no-progress deepening and synthesis with open core debt or scope risk. |

## Runtime Order

1. The orchestrator creates or restores the ordinary v0.7 hierarchical
   runtime.
2. When enabled, it attaches a `ProofControlLayer` to the existing graph,
   memory, Broker, route registry, and artifact store.
3. Strategies receive sidecar goal links before route admission.
4. Attempts and deltas register scope, proof role, inference risk, induction
   hints, and abstract/realizer metadata.
5. Existing verifiers run unchanged. Their reports drive failure
   classification, Near-Miss extraction, realizer repair, risk clearing, and
   core-debt updates.
6. Round-end control computes bottleneck clusters and common-mode assumptions.
7. Active mode checks Continue Gate before `DEEPEN` and Synthesis Readiness
   before the Synthesizer. Existing leases, budgets, and final blind review
   remain authoritative.
8. Sidecar state is persisted under schema `0.8`.

## Authority Boundaries

| Operation | Proof control | Existing authority |
|---|---|---|
| Publish or deliver a typed message | Recommend/admit according to a utility contract | Message Broker |
| Promote a Fact | May add an extra denial in active mode | Typed Memory and Broker Fact gate |
| Close an obligation | May add an extra scope/readiness denial | Proof Graph verified-evidence gate |
| Run a computation | May request the exact bounded fast lane | Tool Broker and registered handler |
| Schedule/deepen a route | Returns route/continue decisions | Orchestrator and scheduler |
| Produce the final proof | Readiness decision only | Synthesizer and blind final verifiers |

All proof-control records refer to existing IDs. They never become mathematical
premises merely because they are present in the sidecar.

## Artifacts And Metrics

Enabled runs write:

- `structured/proof_control.json`
- `reports/proof_control_summary.json`
- proof-control events in the existing Activity stream
- `proof_control_state` in schema-0.8 stage checkpoints

The report distinguishes goal-alignment pass/block/ambiguous counts, core and
auxiliary Facts, core-debt AUC, scope and countermodel counts, failure classes,
blueprint rewrites, bottleneck compression, common-mode assumptions,
contracted/delivered/used messages, Near-Miss repair success, fast-lane
counterexample rate, and the three gate rates.

## Frozen Reasoning Configuration

Proof control does not modify output-token limits, continuation segment
lengths, segment counts, or Deep Exploration tiers. The shadow and active YAML
profiles are full copies of the v0.7 active topology profile with only
`system_name` and `topology.proof_control` changed. A regression test compares
the complete parsed configs after removing those two fields.
