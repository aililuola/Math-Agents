# Proof Control

Proof Control is an advisory layer around the mathematical authorities in
MathProofMesh. It creates risks, blueprints, tasks, gate decisions, and
idempotent control actions. It never writes a Fact, closes a proof obligation,
or changes the frozen problem payload or mathematical hash.

## Modes

- `off`: no control work is applied.
- `shadow`: decisions and diagnostics are recorded, but business state,
  scheduling, graph state, memory, and budgets are unchanged.
- `active`: admitted actions may be delegated to their owning authority
  services through an exactly-once action key.

Reasoning token limits and deep-exploration limits are runtime configuration
owned outside Proof Control and remain unchanged in every mode.

## Semantic Pipeline

1. Build a non-authoritative semantic sidecar and audit protected formula,
   task, polarity, quantifier, domain, and implication-order invariants.
2. Classify obligation domains before core-debt, bottleneck, or route-target
   selection.
3. Check goal alignment, scope, quantifier order, inference strengthening, and
   minimal sufficiency.
4. Compile the strategy into an auditable blueprint before route admission.
5. Materialize executable falsification, countermodel, induction, common-mode,
   and repair tasks. Non-executable work is deferred with a wake condition.
6. Apply admitted actions through `ControlActionDispatcher`; retries return the
   stored result for the same action key.

## Evidence Rules

Bounded failure to find a counterexample does not verify a universal claim.
Near misses remain non-authoritative. A failed concrete realizer does not
refute its abstract structure. Common route agreement is not independent
evidence, and a challenger result requires a different reviewer.

Delivery is separate from verified mathematical use. A normal message with
zero expected or verified utility remains local and consumes no neighbor
quota. Counterexamples retain their fast path but never bypass the Fact,
sandbox, or independent-review gates.

## Resume

The persisted sidecar is canonical and sorted. Resume uses stored action,
task, wake-condition, and checkpoint state rather than replaying completed
work. Meta pivots execute once per stable route/round identity. A terminal run
returns its persisted result with zero provider calls.

The byte-exact Python documents and benchmark runner are retained below
`docs/legacy/python-baseline` and `migration/baseline/auxiliary`. The Java
compatibility benchmark reads the ten frozen JSON cases with no provider,
network, or mutable external dependency.
