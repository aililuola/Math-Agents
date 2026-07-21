# Typed Message Protocol

Cross-route communication is a mathematical object, not chat text. The stable
envelope is `MessageEnvelope` with `schema_version`, IDs, problem hash, source
route/agent/role, explicit targets, statement and normalized statement,
assumptions, conclusion, ordered quantifiers, variable bindings, dependencies,
scope limits, evidence, memory tier, verification state, confidence, artifact
references, round/TTL and a deterministic content hash.

## Message Types

The protocol supports `claim_proposal`, `verified_lemma`, `proof_obligation`,
`counterexample`, `contradiction_notice`, `computation_plan`,
`computation_certificate`, `formal_certificate`, `repair_request`,
`bridge_lemma_request`, `strategy_rewrite_request`, `failure_record` and
`route_checkpoint`.

## Evidence And Memory

Evidence is one of `unverified_idea`, `numerical_heuristic`,
`bounded_experiment`, `exact_symbolic_identity`,
`complete_finite_enumeration`, `sat_smt_certificate`, `counterexample`,
`natural_proof_audited` or `formal_kernel_certificate`. Memory is exactly one
of `fact`, `insight` or `negative`.

`numerical_heuristic` and `bounded_experiment` cannot establish a reusable
fact. A finite enumeration can do so only when its certificate proves that the
finite domain covers the original claim. Counterexamples enter negative memory
after independent replay. Formal-kernel certificates may pass the fact gate.

## Scope And Hashing

Each quantified variable has a stable variable ID, quantifier kind, domain and
position. Bindings refer to those IDs. Unbound variables, duplicate IDs or a
changed quantifier order are rejected. Mutable delivery metadata is excluded
from `content_hash`; mathematical payload, assumptions, dependencies, evidence
and tier are included. Reworded duplicate provenance never raises verification
status.

```json
{
  "schema_version": "1",
  "message_id": "fact_01",
  "problem_hash": "<sha256>",
  "source_agent_id": "agent-a",
  "source_route_id": "route-a",
  "source_role": "prover",
  "target_route_ids": ["route-b"],
  "message_type": "verified_lemma",
  "statement": "For every integer n, L(n) holds.",
  "normalized_statement": "forall n:int l(n)",
  "assumptions": [],
  "conclusion": "L(n)",
  "quantifiers": [{"order": 0, "variable_id": "n", "display_name": "n", "kind": "forall", "domain": "integer", "restrictions": []}],
  "variable_bindings": [{"variable_id": "n", "display_name": "n", "domain": "integer", "owner_scope": "claim", "aliases": []}],
  "dependencies": [],
  "scope_limitations": [],
  "evidence_type": "natural_proof_audited",
  "memory_tier": "fact",
  "verification_status": "verified",
  "verification_confidence": 0.94,
  "normalization_confidence": 0.96,
  "round_created": 2,
  "ttl_rounds": 2,
  "content_hash": "<deterministic-sha256>"
}
```

## Receipts And Exactly Once

`MessageReceipt` binds the message hash, target route, delivery key, status and
round. The receiver must independently return `parsed_assumptions`,
`parsed_conclusion`, `parsed_quantifiers` and `parsed_variable_bindings`; the
Broker recomputes the semantic hash from those parsed fields rather than
reusing the sender's scope. Status is `accepted`, `rejected`, `duplicate`,
`expired` or `deferred`.
`prompt_consumed` is checkpointed separately from acknowledgement. On resume,
a consumed delivery is never emitted again even when its receipt remains
pending.

Optional `referenced_in_step_ids` and `claimed_closed_obligation_ids` are only
utility claims. After the checkpoint passes, the Broker phase checks them
against the actual Delta and Proof Graph. Receipt acceptance alone never
increases mathematical message utility.

Rejected examples include a mismatched problem hash, external/path-traversing
artifact reference, author-refereed fact, bounded experiment labeled as fact,
unparsed universal quantifier, unresolved dependency, dependency cycle, known
counterexample, expired TTL or a target outside the sparse neighbor cap.
