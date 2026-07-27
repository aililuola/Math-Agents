# MathProofMesh v0.8.6 Bilingual Semantic Audit Map

## Scope and invariants

This bugfix closes two remaining language-control gaps without changing the
authoritative problem statement or any mathematical object hash:

1. Text-only Chinese and English renderings of the same load-bearing
   assumption need a conservative language-independent identity.
2. A proposed English problem sidecar must not become usable merely because
   the proposing model asserts that it preserved the source semantics.

The implementation remains problem-independent. Production code must not
branch on a theorem, a problem hash, or topic-specific tokens such as `prime`,
`gcd`, or `a_n`. Proof-control `off`, `shadow`, and `active` behavior, old
YAML/checkpoints, Broker, Route Team, Proof Graph, Typed Memory, Inspiration,
SSE, and checkpoint/resume remain compatible.

This change does not modify token limits, segment lengths, Deep Exploration
tiers, agent counts, or budget defaults. It makes no real Provider calls.

## Source map

| Gap | Production code | Regression tests | Postcondition |
|---|---|---|---|
| Cross-language text-only Common-Mode matching | `proof_control/semantic_profile.py`, `proof_control/common_mode.py` | `tests/test_common_mode_assumption.py`, `tests/test_common_mode_execution_closure.py` | Matching bilingual assumptions share a conservative concept signature; polarity, quantifier, domain, and relation conflicts fail closed |
| Self-reported translation preservation | `proof_control/semantic_profile.py`, `proof_control/semantic_view.py`, `schemas.py` | `tests/test_problem_semantic_view.py` | A usable English sidecar requires deterministic agreement on task intent, polarity, quantifiers, logical relations, and known mathematical domains in addition to protected fragments |
| Audit persistence and hash stability | `schemas.py`, `orchestrator.py` | `tests/test_problem_semantic_view.py`, checkpoint compatibility tests | Audit findings are sidecar metadata with defaults; `exact_statement`, `goal_hash`, and `integrity_hash` never change |
| Safe fallback | `proof_control/semantic_view.py`, prompt serialization | `tests/test_problem_semantic_view.py` | Uncertain or conflicting translations are rejected and downstream reasoning continues from the original statement |

## Conservative matching policy

Language-independent concept tags come only from a fixed, general mathematical
and logical vocabulary. Cross-language text matching requires:

- opposite source languages;
- no conflict in polarity, task intent, quantifier direction, logical
  relation, or recognized domain;
- at least two shared semantic concepts; and
- sufficient bidirectional concept coverage.

Typed dependency identity and Proof Graph dependency closure remain stronger
than textual similarity.

## Validation

1. Add failing adversarial tests before production changes.
2. Run targeted Common-Mode and semantic-view tests after each phase.
3. Run full Pytest, Ruff check, Ruff format check, compileall, offline
   topology/proof-control benchmarks, E2E, and checkpoint/resume tests.
4. Keep real Provider calls at zero and do not commit runtime output, caches,
   distributions, credentials, or `.env` files.
