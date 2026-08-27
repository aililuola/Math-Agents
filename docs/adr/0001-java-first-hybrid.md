# ADR 0001: Java-first hybrid architecture

## Status

Accepted for the 0.8.0 migration.

## Context

The frozen Python system contains orchestration, persistence, provider, desktop,
and symbolic-computation behavior. The target must make Java the production
authority without discarding Python capabilities whose practical replacement
would add correctness risk.

## Decision

All production orchestration, domain rules, persistence, provider integration,
API, CLI, and desktop lifecycle move to Java 25. Python remains only as a
versioned, least-privilege computation sidecar for explicitly approved
SymPy/Z3 compatibility operations.

## Consequences

The sidecar is not a second application authority. Its protocol and resources
must be bounded, and Java owns validation, policy, persistence, and audit.
