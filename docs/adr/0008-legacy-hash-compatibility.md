# ADR 0008: Preserve legacy hash compatibility

## Status

Accepted for the 0.8.0 migration.

## Context

Frozen Python artifacts use canonical JSON, content hashes, semantic hashes,
goal hashes, and selected immutable payloads as identity and integrity
boundaries. Silent drift would break import, deduplication, and replay.

## Decision

Java implements the frozen Python canonicalization and hashing behavior
byte-for-byte where compatibility is required. Phase 00 hash vectors, schemas,
enum literals, and fixtures are the executable reference. Any new hash format
must be versioned rather than redefining an existing identifier.

## Consequences

Compatibility tests include Unicode ordering, numeric edge cases, nested
structures, and representative domain models. Legacy imports remain auditable,
and intentional future changes require an explicit migration path.
