# Computation and Evidence

MathProofMesh treats computation as a bounded, typed source of evidence. It is
not a substitute for a proof. Every request is bound to a run, path, purpose,
target claim, explicit domain, resource limits, and a stable canonical
identity. Results carry the exact request hash, tool identity, evidence
strength, checked scope, replay notes, and any certificate or counterexample.

## Native handlers

The default registry executes deterministic Java 25 handlers for:

- exhaustive modular identities over explicit finite residue domains;
- bounded integer relations and counterexample search;
- graph certificates with independently replayed edges;
- exact rational recurrence checks;
- bounded greedy sequences and candidate-period checks;
- exact rational geometry predicates;
- guarded finite number-theory operations;
- contract, schema, budget, cache, and evidence admission.

Exact handlers use `BigInteger` and reduced rational arithmetic. Floating-point
values are never presented as an exact proof. Guard limits fail closed with an
inconclusive result rather than beginning an unbounded calculation.

## Evidence rules

- A replayed counterexample can refute its precisely bound claim.
- A bounded search that finds nothing is `not_refuted`, never proved.
- Finite enumeration is a certificate only when the declared domain is the
  complete finite domain of the original claim.
- Sandboxed custom output is bounded evidence. A reported counterexample
  remains a candidate until a typed handler independently replays it.
- A result without a claim mapping, request hash, scope, and admissible
  certificate cannot promote a memory item to `Fact`.
- Cache entries are keyed by run, canonical execution identity, and exact tool
  version. The same computation can be reused within a run without leaking
  results between runs.

## Restricted sidecar

`python-compute-service/` contains the only supported Python computation
sidecar. It retains the locked SymPy and Z3 semantics needed for symbolic
simplification, equivalence, factorization, numeric counterexample search, and
bounded real-inequality checks.

The transport is UTF-8, one JSON-RPC 2.0 object per stdin/stdout line, with
protocol version `1.0`. A request contains `protocol_version`, `request_id`,
`method`, `params`, and `limits`. A response contains exactly one of `result`
or `error`, plus `certificate`, `stdout_hash`, `tool_version`, and `cpu_ms`.
Java validates the request ID, schema, bounds, hash, tool version, evidence
semantics, and certificate before accepting a result.

The worker opens no TCP listener and receives an allowlisted environment with
no provider credentials. stdout and stderr are bounded; diagnostics are
redacted. Timeout, malformed JSON, crash, unknown method, identifier mismatch,
oversized output, or invalid evidence terminates the request. Timeout handling
kills the complete descendant process tree.

Runtime dependencies are exact and hash-locked in
`python-compute-service/requirements.lock`. An offline installation uses only
the audited wheel cache:

```text
python -m pip install --no-index --find-links <wheel-cache> \
  --require-hashes -r python-compute-service/requirements.lock
```

The phase-08 lock targets the governed Windows AMD64 runtime. Supporting a new
platform requires a separately downloaded and audited wheel set and an updated
lock; silently resolving another artifact is forbidden.

## Optional custom programs

Arbitrary Python is disabled by default. It is available only when
`sandboxed_python_enabled=true` and the configured image is an immutable
`name@sha256:<digest>` reference. Before execution, a trusted CPython AST
validator rejects attributes, dunder names, reflection, dynamic execution,
file/process/network access, and undeclared imports.

The Docker command uses no network, a read-only root filesystem, a non-root
user, dropped capabilities, `no-new-privileges`, and explicit CPU, memory, PID,
output, and wall-clock limits. Input and output must satisfy server-owned JSON
schemas. Image names and schemas are never supplied by a model.

## Operations

The default server profile keeps the sidecar and custom sandbox off until
explicitly configured. Health checks use `service.py --self-check` and do not
open a port. Operators should monitor timeout, rejection, crash, queue, and
worker-latency metrics without logging source programs, private reasoning, or
secrets.

The byte-exact Python-era policy remains under
`docs/legacy/python-baseline/COMPUTATION_POLICY.md` for audit. This document is
the Java implementation authority where the two differ.
