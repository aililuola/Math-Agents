# Proof Scope And Inference Risks

## Scope Signatures

`ScopeSignature` is sidecar metadata keyed by the source object's existing ID.
It does not change `MessageEnvelope`, `ClaimCard`, `ProofObligation`, or
`ProofStep` serialization or content hashes.

The signature records:

- index scope: `all`, `eventual`, `finite_prefix`, `bounded_range`,
  `single_instance`, or `unknown`;
- uniformity: `uniform`, `pointwise`, `exists_per_instance`, or `unknown`;
- object scope: `full_object`, `projection`, `quotient`, `residue_classes`,
  `substructure`, or `unknown`;
- ordered quantifiers and variable bindings;
- domain constraints, exceptional cases, and normalization confidence.

`ScopeGuard` compares the premise and target signatures before a candidate can
support Fact promotion or obligation closure. Unknown normalization can be
reviewed, but uncertainty is never converted into a stronger scope.

## Risk Taxonomy

The deterministic scanner recognizes ten inference risks:

| Risk | Unsafe jump |
|---|---|
| `necessary_to_sufficient` | A necessary condition is used as a sufficient condition. |
| `eventual_to_global` | An eventual statement is used for every index. |
| `pointwise_to_uniform` | Per-instance control is treated as one uniform bound or witness. |
| `finite_range_to_finite_state` | Finite-valued increments are treated as a finite-state or periodic process. |
| `image_inclusion_to_surjectivity` | Image inclusion is treated as equality with the codomain. |
| `projection_to_original` | Equality or stability of a projection is lifted to the full object. |
| `local_to_global` | A local property is asserted globally without a bridge. |
| `existence_to_uniform_existence` | Separate witnesses are replaced by one uniform witness. |
| `pairwise_to_common_witness` | Pairwise witnesses are treated as a common witness. |
| `empirical_to_universal` | A bounded experiment is treated as a universal proof. |

Each `InferenceRiskRecord` names its subject, premises, conclusion, rule,
explanation, confidence, required bridge obligations, and optional
countermodel task. It begins as `open` and can become `cleared`, `refuted`, or
`accepted_with_bridge`.

## Active Semantics

In `off`, no control decision changes v0.7 behavior.

In `shadow`, the system records scope mismatches, risks, countermodel requests,
and would-block gate decisions. It still lets the existing runtime proceed.

In `active`:

- an open scope risk can add a denial before Fact promotion;
- a weaker or incomparable scope cannot close a stronger obligation;
- a finite countermodel can refute an implication but cannot prove the
  positive universal claim when no counterexample is found;
- synthesis is blocked while an open scope risk lies in the core dependency
  closure.

These checks are additive. They do not replace the Broker's evidence policy or
the Proof Graph's verified closure checks.

## Countermodels

Countermodel tasks are bounded, deterministic control requests. A task ID is
stable for the subject, risk type, and rule. The task itself is never a Fact
and is marked `premise_eligible: false`.

The exact falsification fast lane can bypass only the soft Meta-review step
when the task:

- identifies a claim or obligation;
- uses a registered typed computation, never automatic sandboxed Python;
- uses exact arithmetic, a bounded case count, runtime, and memory;
- states the action to take if refuted;
- remains within the existing Tool Broker hard budgets.

An independently reproduced exact counterexample is eligible for the existing
Negative Memory and Counterexample-message path. A search that finds nothing
remains bounded Insight and cannot verify an infinite statement.

## Logic-Trap Coverage

The offline proof-control benchmark includes concrete cases for eventual/all,
finite-range/nonperiodic, image inclusion/surjectivity,
projection/full-object, and bounded empirical/universal boundaries. The
benchmark invokes no provider and fails nonzero when a component contract is
violated.
