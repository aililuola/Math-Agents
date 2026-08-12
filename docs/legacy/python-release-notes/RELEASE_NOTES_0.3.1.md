# MathProofMesh 0.3.1 — DeepSeek SSE transport

This release adds optional Server-Sent Events (SSE) response reading to the dedicated DeepSeek adapter while preserving non-streaming behavior as the configuration default.

## What changed

- `AgentConfig.streaming: bool = false` was added.
- When a DeepSeek agent sets `streaming: true`, requests send:

  ```json
  {
    "stream": true,
    "stream_options": {"include_usage": true}
  }
  ```

- The adapter consumes `data:` events, ignores blank lines and `: keep-alive` comments, aggregates content deltas, reads the final usage-only chunk, and requires the `data: [DONE]` terminator.
- A stream that ends before `[DONE]` is treated as incomplete, so the existing retry policy can retry the whole provider call without forwarding a partial result.
- `reasoning_content` remains private: its chunks are counted and hashed incrementally, but the text is never persisted or forwarded.
- Both supplied DeepSeek V4 Pro profiles enable streaming for all five agents.

## Two different SSE channels

1. **Provider SSE:** DeepSeek API → MathProofMesh backend. This release adds this channel.
2. **Activity SSE:** MathProofMesh backend → browser/client through `/solve/stream`. This already existed in 0.3.0.

Provider SSE does not expose partial proof text to another agent. The complete structured output is still assembled and schema-validated before inter-agent communication.

## Validation

- `python -m compileall -q src tests`: PASS
- `ruff check src tests`: PASS
- `PYTHONPATH=src pytest -q`: 24 passed
- Deterministic source demo: verified, 19 calls
- Installed-wheel SSE HTTP mock: PASS
- Installed-wheel deterministic demo: verified, 19 calls
- Secret scan: no live key or contiguous live-key pattern is stored; the redaction test constructs a synthetic credential only at runtime

No live DeepSeek request was made for this release build.
