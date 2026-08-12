# ADR 0003: PostgreSQL is authoritative

## Status

Accepted for the 0.8.0 migration.

## Context

Runs, claims, proof state, delivery state, provider calls, leases, and audit
records need transactional durability and deterministic recovery.

## Decision

PostgreSQL 18.4 is the authoritative production state store. Java uses explicit
Spring JDBC/JdbcClient repositories and Flyway migrations. In-memory graphs,
caches, desktop state, and Temporal state are projections or coordinators, not
independent authorities.

## Consequences

All durable mutations have transaction boundaries and audit semantics. JPA and
Hibernate are excluded, and recovery must be derivable from PostgreSQL records.
