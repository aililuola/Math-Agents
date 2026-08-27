# Contracts and Prompt Protocol

Java records in `mathproofmesh-contracts` are the authoritative wire
contracts. Jackson parsing rejects unknown properties, duplicate keys, nulls
for non-null fields, scalar coercion, and domain-invariant violations. Canonical
JSON and SHA-256 rules are documented and tested in the contracts module.

The preserved Python prompt reference is available at
[`legacy/python-baseline/PROMPT_PROTOCOL.md`](legacy/python-baseline/PROMPT_PROTOCOL.md).

## Structured prompts

`PromptCatalog` defines stage-specific instructions, while `PromptFactory`
serializes only caller-supplied sanitized context. Every prompt:

- preserves the original hypotheses, quantifiers, domains, definitions, and
  requested conclusion;
- distinguishes verified facts, conjectures, failures, and unresolved gaps;
- requests one JSON object for an explicit Java response type;
- forbids private chain-of-thought and invented citations;
- leaves server-owned content hashes empty;
- states assumptions, scope, dependencies, obligations, and a falsification
  condition.

Blind structural and detailed review prompts additionally reject agent IDs,
route IDs, scores, self-confidence, earlier reviews, and votes. Author and
reviewer selection is enforced independently by `AgentPool`.

## Output handling

`StructuredAgentRunner` extracts the first balanced JSON object and performs a
strict contract parse. If that fails, `BoundedJsonRepairer` may remove one
`result`, `data`, or `response` wrapper. It cannot invent, remove, paraphrase,
strengthen, or weaken a mathematical leaf value. A second failure raises a
structured-output error; it does not convert malformed text into a valid
mathematical result.

Prompt and response artifacts are content addressed. Secrets, bearer tokens,
provider keys, and explicit configured secrets are redacted before any
artifact write. Provider call completion and downstream result application
have separate idempotency records.
