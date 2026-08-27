# Verification

JavaMathProofMesh verifies proof content by risk and authority rather than by
route popularity or model confidence. The executable policy lives in
`io.github.aililuola.mathproofmesh.verification`.

## Validation Ladder

The fixed order is:

1. local deterministic structural checks;
2. a fresh-context blind review by the same model family;
3. an adversarial blind prompt;
4. an optional heterogeneous provider;
5. an exact tool or formal micro-certificate.

Structural review runs first. A structural failure does not spend detailed
review budget. High-risk Fact promotion and final-proof review use every
available required level. A missing heterogeneous provider degrades to
adversarial blind review plus a tool or formal check and records the missing
capability. A missing required tool or formal backend leaves the result
pending. The executor treats an absent handler, crash, or missing evidence as
a failed level.

## Blind Review

The reviewer cannot be any author in the winning proof chain. Its packet
contains only:

- the authoritative original problem view;
- the sanitized proof;
- explicitly cited typed Fact evidence;
- bounded Negative evidence, with counterexamples first;
- completeness diagnostics for required Fact and Negative context.

Reviewer packets exclude agent and route identity, ranking, scores,
confidence, votes, the original prompt, private reasoning, raw artifact paths,
and internal paths. Artifact references are replaced by content-addressed
descriptors. Legacy Claim cards are quarantined unless the run explicitly uses
`legacy_sparse`. A missing cited Fact or omitted mandatory counterexample
fails the blind context integrity gate.

## Capability Profile

Empirical capability is indexed by `(agent, mathematical domain, role)`.
Mutation detection, exact-tool agreement, first-error localization, overturns,
and recent tasks update separate cells with recency decay. Self-reported
confidence is counted only as an ignored input and never changes trust. The
profile may rank eligible agents, but it cannot change mathematical truth or
bypass review isolation.

## Mutation

The mutation harness covers all fixed fault families:

- drop an assumption;
- reverse a quantifier;
- alter a comparison sign;
- break a dependency;
- insert a circular step.

Each result records both fault detection and correct first-error localization.
False acceptance lowers the relevant verifier capability cell.

## Formal Micro-Certificates

`FormalVerifierBackend` is an optional interface. Lean is disabled by default.
Formal budget is allocated to shared, central, quantified, or main
obligations. Certificates bind the exact problem, obligation, statement, and
assumptions. A compiler failure opens a `formalization_task`; it does not
refute the natural-language Claim.

## Claim Authority

Claim verification is monotonic under routine attempt feedback. An incomplete
parent attempt does not demote an independently verified child Claim. Explicit
claim-level failure, an independently replayed counterexample, or dependency
invalidation can reject or invalidate it. A scoped verified delta may enter
its own Fact gate even when a later route step is incomplete, but only after
independent validation and explicit global-share admission.

Review feedback is tagged, machine-readable, and always
`premise_eligible=false`. It identifies open work and cannot extend a verified
checkpoint.

## Legacy References

The frozen Python design documents remain byte-exact at:

- `docs/legacy/python-baseline/AGENT_CAPABILITY_PROFILE.md`
- `docs/legacy/python-baseline/VALIDATION_ESCALATION.md`

## Release Verification

The validation model is exercised through unit, contract, property,
parameterized, mutation, differential, PostgreSQL, Temporal replay, REST/SSE,
CLI, JavaFX, sidecar, and legacy-import tests. Every release runs the matrix
online and offline with JDK 25 and Maven Wrapper 3.3.4.

Security verification includes endpoint and path confinement, SSRF, SQL
injection, traversal and Zip Slip, strict deserialization, SSE and log
injection, prompt/tool injection, resource exhaustion, secret scanning,
SpotBugs/FindSecBugs, CycloneDX, license review, and OWASP
Dependency-Check. CVSS 7.0 or greater blocks release.

JaCoCo coverage and machine-specific performance results are enforced and
recorded under `migration/reports`. The final gate also reruns all 759 frozen
Python tests and proves that the exact 401-file source manifest is unchanged.

The byte-exact Python validation history is preserved at
`docs/legacy/python-baseline/VALIDATION.md`. Current commands, thresholds, and
failure policy are documented in `docs/testing.md`.
