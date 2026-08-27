# Inspiration Engine

The Java inspiration engine is a bounded advisory subsystem in
`mathproofmesh-core`. It detects observable proof stagnation, schedules an
enabled mechanism, assigns a limited proposer population, obtains an
independent referee review, and only then permits route-local materialization.

## Authority Boundary

Inspiration proposals are never proof evidence. The subsystem cannot write a
Fact, close an obligation or checkpoint, or change the problem hash. `off`
does not call a provider. `shadow` records decisions without changing
scheduler, graph, memory, route, or budget state. `active` is available only
after scheduler admission and all review and novelty gates pass.

## Mechanisms

The schedulable mechanisms are representation switching, verified local
analogy, auxiliary construction, invariant or monovariant hypothesis, reverse
goal analysis, bridge-lemma discovery, seeded surprise exploration, and
persistent meta replanning. Composition combines independently reviewed,
target-connected, complementary proposals under a fixed cost and fast
falsification requirement.

## Learning

Outcome reward and UCB profiles affect scheduling only. They do not become
evidence. Positive cross-run experience requires a Fact-gated verified gain
and, when configured, a final-proof citation. Rejected transfers enter the
negative analogy library. All persisted learning is project- and tenant-local.

The authoritative Python design document is retained unchanged at
`docs/legacy/python-baseline/INSPIRATION_ENGINE.md`.
