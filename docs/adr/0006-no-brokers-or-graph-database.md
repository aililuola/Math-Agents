# ADR 0006: No Kafka, Redis, RabbitMQ, or Neo4j in the first release

## Status

Accepted for the 0.8.0 migration.

## Context

The required delivery, caching, graph, and recovery behavior can be provided
within PostgreSQL and the modular monolith. Extra infrastructure would multiply
failure modes before parity is established.

## Decision

The first Java release uses PostgreSQL transactional outbox/inbox tables,
bounded in-process caches, and JGraphT projections. It does not use Kafka,
Redis, RabbitMQ, or Neo4j.

## Consequences

Operational prerequisites stay small and transactional semantics stay explicit.
Adding one of the excluded systems requires measured scale evidence and a new
ADR covering consistency, recovery, and ownership.
