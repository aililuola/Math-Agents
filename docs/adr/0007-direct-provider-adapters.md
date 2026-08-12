# ADR 0007: Direct provider adapters

## Status

Accepted for the 0.8.0 migration.

## Context

Provider protocol details, streaming behavior, usage accounting, error
classification, and retry policy are part of MathProofMesh correctness.

## Decision

Implement DeepSeek, Anthropic, Gemini, OpenAI-compatible, and mock adapters
directly with the JDK HttpClient and strict JSON/SSE parsing. Do not use Spring
AI or provider-specific Spring Boot starters.

## Consequences

The application owns request fields, authentication, bounded streaming,
timeouts, retry classification, redaction, and cost evidence. Protocol changes
must be covered by recorded fixtures and mock-server tests.
