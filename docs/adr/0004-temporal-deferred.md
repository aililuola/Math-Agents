# ADR 0004: Temporal integration is deferred

## Status

Accepted for the 0.8.0 migration.

## Context

Introducing a workflow engine before domain state machines, persistence, and
recovery semantics are proven would obscure parity failures.

## Decision

Temporal Java SDK 1.37.0 is dependency-locked in phase 00 but production
workflow integration waits until phase 13. Local development uses the pinned
Temporal CLI 1.8.1 image with embedded Server 1.31.2, headless SQLite storage,
and loopback-only exposure.

## Consequences

Earlier phases implement and test explicit state transitions without Temporal.
Temporal later coordinates durable work but does not replace PostgreSQL as the
business-state authority.
