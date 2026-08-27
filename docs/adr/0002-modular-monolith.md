# ADR 0002: Modular monolith

## Status

Accepted for the 0.8.0 migration.

## Context

The target needs strict ownership boundaries but does not need the operational
cost or distributed-failure surface of microservices.

## Decision

Use one Maven reactor and one deployable Java application with contracts, core,
server, desktop, and compatibility modules. Enforce dependency direction with
Maven, Spring Modulith, and ArchUnit. Do not introduce internal network APIs
between these modules.

## Consequences

Transactions and refactoring stay local while module boundaries remain
machine-checked. A later service split requires a separate evidence-backed ADR.
