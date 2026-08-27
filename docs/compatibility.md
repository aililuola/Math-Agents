# Legacy Run Compatibility

MathProofMesh 0.8.0 imports legacy Python run directories without modifying
them. The compatibility boundary accepts v0.7, v0.8.0, v0.8.1, and v0.8.2
state. Later or unknown formats fail closed.

## Read-Only Import

`LegacyRunImporter` performs two complete hash passes. It rejects links,
reparse/special files, path escapes, absolute or external references, duplicate
JSON keys, oversized inputs, and files that change during the import. The
canonical source manifest is the sorted sequence:

```text
<lowercase SHA-256><two spaces><relative POSIX path><LF>
```

The manifest SHA-256 is the unique import key. Repeating an import returns the
same import identity and deterministic target run ID. The result retains the
source root, per-file hash and size, source version, ordered migration steps,
and quarantine decisions.

The following integrity checks are mandatory:

- `problem_hash` must match the exact bytes of the declared problem file.
- Every Artifact hash and declared size must match its confined file.
- Every Checkpoint parent must exist; the chain must be acyclic.
- The latest pointer must identify a committed Checkpoint.
- JSON and JSONL inputs must parse with duplicate-key detection.

No importer method writes to the selected source directory. Production callers
persist the returned canonical result through normal run, Artifact, and
`legacy_import` repositories only after all gates pass.

## Version Chain

Migration is ordered and never skips an intermediate semantic boundary:

1. v0.7 to v0.8.0 adds the versioned stdio JSONL sidecar boundary.
2. v0.8.0 to v0.8.1 adds exactly-once delivery and semantic-state metadata.
3. v0.8.1 to v0.8.2 adds run-local Checkpoint and typed dependency namespaces.

Legacy `legacy_external` dependencies become `external_claim` references in
the explicit `legacy` namespace. Other typed dependencies receive their
required delta, attempt, broker, or legacy namespace. Original claim and proof
payload hashes are not rewritten by sidecar metadata.

An unaudited legacy `FACT` or `verified` Claim is changed to `quarantined`.
Independent audit provenance is required before normal verification can
promote it. Legacy receipt and claim bypass flags are also quarantined and
cannot restore authority.

## Resume Rules

Terminal runs return their stored terminal result with zero Provider calls.
Non-terminal runs resume only from the latest committed Checkpoint, also with
zero Provider calls during planning. A missing, working, corrupt, or externally
referenced Checkpoint blocks import rather than falling back to legacy state.

## Shadow Comparison

`ShadowComparator` compares Python and Java snapshots across:

- ProblemContract and strategies
- message admission, delivery, and receipt state
- three-tier Memory
- Proof Graph
- Checkpoints and recovery
- usage accounting and final state

Structure, ordering, identity, hashes, namespaces, receipt state, Checkpoint
state, and final status must match exactly. A natural-language difference is
accepted only at an explicit JSON pointer declared non-deterministic. Reports
store hashes of differing values rather than raw text. A waiver cannot cover
an ID, hash, status, state, dependency, receipt, or Checkpoint field.

## Preserved Authority Documents

Historical release notes are preserved byte-for-byte under
`docs/legacy/python-release-notes`. Compatibility and semantic-control
documents are preserved under `docs/legacy/python-baseline`. These files are
audit evidence; this document and the Java implementation define current
runtime behavior.
