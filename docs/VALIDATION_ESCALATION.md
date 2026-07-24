# Validation Escalation

Validation is risk-based and independent of route popularity. The default
ladder is:

```text
local deterministic checks
-> fresh-context same-model blind review
-> adversarial-prompt blind review
-> optional heterogeneous model/provider review
-> exact tool or formal micro-certificate
```

`ValidationEscalator` builds the required stages from risk, reviewer
disagreement, fact-promotion intent and final-proof status. High-risk facts and
final proofs cannot skip deterministic checks. When cross-provider review is
configured but no heterogeneous agent exists, the system records a diagnostic
and safely falls back to adversarial blind review plus tool/formal checking.

Blind final packets include only the problem, cleaned proof and explicitly
cited evidence. Runtime prompt guards reject `agent_id`, `route_id`, route
score, self-confidence, previous review or vote fields. The structural judge
runs before the detailed judge.

## Formal Micro-Certification

`FormalizationCandidateSelector` prioritizes shared, high-centrality,
quantifier-risky or algebraically dense obligations. `FormalStatementPacket`,
`FormalVerifierBackend`, `FormalCertificateRef` and
`CompilerFeedbackInterpreter` form a backend-neutral interface. Backend
absence returns `pending`. Compiler rejection creates a formalization
obligation and does not refute the natural-language claim. A valid formal
kernel certificate is an allowed Fact evidence type.

## Proof Mutation

The deterministic mutation harness drops assumptions, reverses quantifiers,
alters signs, breaks dependencies and inserts circular steps. False acceptance
and first-error localization update the corresponding verifier capability cell.
Mock mutation cases live under `benchmarks/topology/` and never call a real
provider in CI.
