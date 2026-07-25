# Near-Miss And Realizer Repair

## Why Separate Structure From Realization

A proof plan can contain a useful abstract reduction even when its first
concrete witness, representative, embedding, or descent construction violates
a boundary condition. MathProofMesh 0.8 records these as separate sidecar
objects:

- `AbstractStructureProposal` describes the representation, components,
  reduction, preserved constraints, and target obligations.
- `RealizerCandidate` describes one concrete construction, admissibility and
  boundary conditions, descent measure, expected decrease, and falsification
  tests.

A failed candidate is not evidence that the abstract structure is false. Only
evidence aimed at the structure itself may mark it `refuted_structure`.

## Near-Miss Admission

`NearMissLedger` extracts a record only when:

- an existing verifier returns `fail` or `uncertain`;
- problem integrity is intact;
- verifier confidence meets the configured threshold;
- a first error or concrete issue is present;
- at least one preserved or salvageable component is identified.

The record includes the abstract idea, failed concrete candidate, preserved
properties, failed constraints, first failure type, repair operators,
induction-measure hints, verifier report IDs, and confidence. It stays in
sidecar state and never enters the Fact inbox.

Problem-integrity failures and unsalvageable output are not Near-Misses.

## Repair Operators

Repairs are bounded per structure and use one of four explicit operators:

| Operator | Intended change |
|---|---|
| `replace_realizer_preserve_structure` | Replace the concrete construction while retaining the abstract reduction. |
| `minimal_admissible_realizer` | Search for the smallest construction satisfying all declared constraints. |
| `alternative_representative` | Keep the quotient or class structure but choose another representative. |
| `repair_boundary_conditions` | Repair endpoint, lower/upper bound, degeneracy, scope, or strict-descent failures. |

Every repair task names the failed candidate, required constraints, and target
obligations. Every replacement candidate must still state admissibility,
boundary conditions, a well-founded descent measure when relevant, and a
falsification test. The configured cap prevents open-ended search.

## Failure And Blueprint Interaction

Verifier output is classified at four control levels:

- `execution`: the plan may survive; repair the concrete implementation;
- `bridge`: retain local work and repair the missing implication;
- `plan`: stop local deepening and rewrite the proof blueprint;
- `framing`: re-anchor the route to the original goal and scope.

Blueprint rewrites preserve independently verified Fact and step IDs. They
identify invalidated plan elements, overstrong targets, weaker targets,
required bridge obligations, and whether a representation change is needed.
The controller requests a rewrite but does not execute mathematical state
changes itself.

## Induction And Descent

The measure selector looks for recursive same-type dependencies, repeated
features, and first-occurrence barriers. It can propose occurrence-count or
event-index induction when ordinary induction on `n` does not align with the
proof dependency.

Every proposal states:

- its well-founded domain;
- base cases;
- induction-step relation;
- strict-decrease argument;
- why the natural index is insufficient;
- the trigger features and target obligations.

The proposal is a plan artifact, not a proof.

## Learning Boundaries

Verified repairs may improve route context and the reported Near-Miss repair
rate. A delivered hint, candidate, or repair task receives no utility merely
for existing. Credit requires a committed verifier-backed use, obligation
closure, refutation, blueprint rewrite, or final citation.

Checkpoint restore preserves ledger IDs, realizer candidates, repair tasks,
and events. Re-registering an existing artifact is idempotent.
