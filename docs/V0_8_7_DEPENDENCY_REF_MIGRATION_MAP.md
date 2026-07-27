# MathProofMesh v0.8.7 DependencyRef Migration Map

## Scope and invariants

This bugfix prevents a legacy structured dependency kind from terminating a
run during claim extraction or checkpoint resume. The observed payload used
`kind="external"` while the typed namespace requires one of
`local_step`, `local_claim`, or `external_result` according to the referenced
object.

The migration is deterministic and problem-independent:

1. A target matching a step ID in the same structured payload becomes
   `local_step`.
2. A target matching a claim ID in the same structured payload becomes
   `local_claim`.
3. An otherwise unscoped legacy `external` value becomes `external_result`.
4. Every migration carries durable sidecar metadata and live model responses
   also emit a `structured_output_normalized` audit event.
5. Unknown dependency kinds still fail strict validation.

Dependency metadata remains outside mathematical checkpoint hashes. This
change does not modify the authoritative problem, token or segment limits,
Deep Exploration, SSE, agent counts, budget defaults, or Provider behavior.
It makes no real Provider calls.

## Source map

| Gap | Production code | Regression tests | Postcondition |
|---|---|---|---|
| Legacy live structured payload | `agents.py` | `tests/test_structured_payload_normalization.py` | Nested `dependency_refs` are contextually canonicalized before response-model validation and the action is audited |
| Legacy checkpoint or sidecar payload | `proof_control/models.py` | `tests/test_dependency_ref_namespaces.py` | Exact `external` input restores as `external_result` with durable migration metadata |
| Claim-extraction protocol ambiguity | `prompts.py` | prompt regression assertion | Claim and step dependencies use their typed local namespaces; external artifacts use `external_result` |
| Strict failure boundary | `proof_control/models.py` | `tests/test_dependency_ref_namespaces.py` | Any unknown kind other than the one documented legacy alias still raises `ValidationError` |
| Hash and compatibility boundary | `schemas.py`, existing checkpoint payload methods | checkpoint/resume regression suite | Sidecar migration does not enter `ProofStep` or `ClaimCard` mathematical hashes; old checkpoints remain readable |

## Validation

1. Add failing regression tests before production changes.
2. Run the focused dependency, structured-output, claim-lifecycle, and resume
   tests.
3. Run full Pytest, Ruff check, Ruff format check, compileall, offline
   topology/proof-control benchmarks, E2E, and checkpoint/resume tests.
4. Build and smoke-test the Windows installer from the validated commit.
5. Do not commit runtime output, caches, distributions, credentials, `.env`,
   or real Provider responses.
