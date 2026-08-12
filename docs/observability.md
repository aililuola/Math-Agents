# Observability and Activity Timeline

## Boundary

Operational telemetry is deliberately separate from mathematical authority.
Metrics, traces, logs, and activity events can explain scheduling and progress,
but cannot promote a claim, close an obligation, commit a checkpoint, or change
the frozen problem identity.

The byte-exact Python reference is retained at
`docs/legacy/python-baseline/ACTIVITY_TIMELINE.md`.

## Activity Events

`ActivityStream` emits a monotonic, append-only sequence and resumes numbering
from an existing `activity.jsonl`. Updates preserve the first event type,
start time, and inferred run/stage parent. Final JSON and Markdown projections
collapse updates to one latest snapshot per logical task without rewriting the
append log.

Events contain stage, task and agent identifiers, elapsed time, status,
importance, a bounded user-facing title and detail, progress, and small bounded
metrics. Recursive metric containers have depth and item limits. Credential
fields, bearer values, API keys, common key formats, control characters, and
oversized values are redacted or truncated before listeners, persistence, SSE,
or console output.

Long provider calls may emit content-free `agent_call_heartbeat` events. They
never include the prompt, response body, reasoning chunks, credentials, or
provider wire payload.

## SSE

The public stream uses the fixed event types `run_started`, `stage_changed`,
`agent_started`, `agent_completed`, `route_updated`, `message`, `checkpoint`,
`verification`, `budget`, `warning`, `result`, `error`, and `heartbeat`.
Every persisted event has a strictly increasing numeric ID. `Last-Event-ID`
returns only later events and never renumbers the stream.

Responses use `text/event-stream`, `Cache-Control: no-cache`, and
`X-Accel-Buffering: no`. Data is restricted to stage, agent ID, elapsed time,
status, redacted summary, result reference, and trace ID.

## Reasoning Archive

Provider-emitted reasoning, when explicitly retained, is stored only in the
private run-scoped `reports/reasoning_traces.txt` append log. It is never
included in SSE, normal logs, errors, activity reports, or public artifacts.
The archive has start/delta/end records, call indices, hashes, byte cursors,
restart continuity, and secret-aware buffering so a credential split across
provider chunks is still redacted.

## Correlation

The API accepts a valid W3C `traceparent` or `X-Trace-Id`, otherwise it creates
a 128-bit trace ID. The same ID is returned in `X-Trace-Id`, placed in MDC,
attached to API events and reports, and recorded by the application service.
Structured logs contain only method, bounded path, status, and trace ID.

## Metrics

Micrometer records API calls, latency, errors, provider token and cost
reconciliation, queue depth, routes, messages, checkpoints, computation, and
leases. The deterministic demo performs zero provider calls and records zero
token and cost increments.

Actuator runs on a separate loopback management port. Only health and metrics
are exposed; environment, configuration properties, heap dump, and shutdown
endpoints are disabled.

## API Security

The business server binds to `127.0.0.1` by default. `/health` performs no
provider or model operation. Every other endpoint requires a bearer token,
compared in constant time. CORS is not enabled. Request headers, body size,
concurrency, run IDs, artifact hashes, media types, and download sizes are
bounded. HTTP payloads cannot set provider URLs, credentials, sandbox images,
or filesystem paths because unknown fields fail closed.
