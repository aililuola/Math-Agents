# ADR 0005: Python sidecar uses stdio

## Status

Accepted for the 0.8.0 migration.

## Context

Selected symbolic operations need Python compatibility, but a network service
would create an unnecessary attack and deployment surface.

## Decision

The Java worker pool communicates with the Python computation sidecar through
versioned, UTF-8, line-delimited JSON-RPC 2.0 on stdin/stdout. The sidecar may
not listen on a network socket, access the database, receive provider secrets,
or modify the source tree.

## Consequences

Requests need strict schemas, budgets, timeouts, output limits, process-tree
termination, and evidence hashes. Sidecar crashes remain isolated from Java
state and can be audited and retried under Java policy.
