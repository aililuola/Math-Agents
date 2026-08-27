# Typed Communication and Sparse Topology

MathProofMesh exchanges only validated `MessageEnvelope` records. Free-form
chat text is not a cross-route transport. A message is admitted in this fixed
order: schema and size, problem identity, source ownership, target route,
time-to-live, artifact safety, quantified scope, dependency closure, evidence
tier, reviewer independence, content hash, semantic deduplication, capacity,
then one transactional persistence step.

## Delivery Lifecycle

Every target has a stable delivery key derived from the message and target
route. The durable states are `queued`, `delivered`, `prompt_consumed`,
`acknowledged`, `expired`, `rejected`, and `deferred`.

`prompt_consumed` and the provider request are committed atomically before a
provider is called. A consumed delivery is never placed into another prompt,
including after restart. A receipt proves acknowledgement, not mathematical
use. `actually_used` is set only when receipt claims match a verified
downstream proof effect. Message invalidation removes active delivery state,
retains an immutable audit record and permits an exact republication.

## Routing

Routes begin isolated. Cross-route delivery is restricted to registered sparse
neighbors and bounded per route and round. Route membership carries a role;
source ownership and author/referee separation are checked before admission.
Neighbor lists are capped and deterministic. Semantic route duplicates are
merged without deleting historical route identity, while a counterexample
cools a route until an explicit revision is registered.

High and critical messages retain reserved queue capacity. Mutable delivery
metadata does not affect semantic content identity. Quantifier ordering,
variable bindings, assumptions, evidence and dependencies do.

## Operations

PostgreSQL is authoritative. Accepted messages, deliveries and outbox events
share one transaction. Prompt requests, receipts, verified utility and
invalidation audit records are run scoped. On resume, clients must use the
stored delivery state rather than reconstructing an inbox from messages.

No benchmark or test invokes a provider. Run the deterministic topology suite
with `scripts/benchmark-topology.ps1` on Windows or
`scripts/benchmark-topology.sh` on POSIX.

The byte-exact Python protocol and topology documents remain under
`docs/legacy/python-baseline/` for audit only.
