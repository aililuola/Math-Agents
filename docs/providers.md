# Provider Runtime

MathProofMesh implements provider integrations directly on the JDK
`HttpClient` boundary. The implementation does not use Spring AI or a
provider SDK. Tests use only in-memory HTTP/SSE fixtures and the mock provider.
Live network calls are denied unless the caller explicitly opts in.

The preserved Python reference is available at
[`legacy/python-baseline/DEEPSEEK_V4_PRO.md`](legacy/python-baseline/DEEPSEEK_V4_PRO.md).
ADR 0007 records the decision to keep protocol behavior in owned adapters.

## Supported providers

| Provider | Endpoint suffix | Credential header | Usage fields |
|---|---|---|---|
| OpenAI-compatible | `/chat/completions` | `Authorization: Bearer ...` | `prompt_tokens`, `completion_tokens` |
| DeepSeek | `/chat/completions` | `Authorization: Bearer ...` | OpenAI-compatible usage plus thinking controls |
| Anthropic | `/messages` | `x-api-key` and pinned API version | `input_tokens`, `output_tokens` |
| Gemini | `/models/{model}:generateContent` or `:streamGenerateContent?alt=sse` | `x-goog-api-key` | `promptTokenCount`, `candidatesTokenCount` |
| Mock | no network | none | deterministic fixture values |

Each adapter owns its URL, authentication, request shape, response parsing,
request ID extraction, usage mapping, streaming protocol, and error mapping.
Credential headers cannot be replaced through `extra_headers`.

## Streaming bounds

SSE reads run on virtual threads and enforce:

- a maximum response byte count;
- a first-chunk timeout;
- an idle timeout between chunks;
- cooperative cancellation and stream closure;
- CRLF/LF normalization, comments, multi-line `data`, UTF-8 fragmentation,
  BOM handling, and a final unterminated event.

OpenAI-compatible and DeepSeek streams must end with `[DONE]` and include the
requested usage block. Anthropic must emit `message_stop`. Gemini accepts its
documented close-after-event behavior. A retryable disconnect is re-dispatched
by `AgentRuntime` under the same bounded retry policy; an unknown remote result
is recorded as `ambiguous`, never silently treated as failed.

Private reasoning text is never returned in metadata or persisted. Only a
presence bit, character count, and SHA-256 digest are retained.

## Reliability

Every external attempt follows this order:

1. save a redacted prompt artifact;
2. reserve call, token, and cost budget;
3. insert a `provider_call` in `planned`;
4. transition through `dispatched` and, when applicable, `streaming`;
5. persist a redacted raw-response artifact and terminal state;
6. parse the first balanced JSON object under the strict contract;
7. attempt one bounded representation-only repair;
8. reconcile usage, cost, latency, retries, and possible duplicate cost.

`provider_call` request identity is immutable. A call may be applied only after
`succeeded`, and `(run_id, application_key)` plus `(run_id, call_id)` unique
constraints prevent duplicate downstream effects.

HTTP 401 and 403 are not retried with the same credential, but failover may use
another configured agent. HTTP 408, 409, 429, 5xx, network, and timeout
failures are retryable, with `Retry-After` taking precedence over exponential
backoff when it is longer. Provider-scope circuit state is persisted and opens
only under configured terminal rules or failures from distinct credentials.

## Operations

Do not place credentials in YAML committed to the repository. Resolve them from
the configured environment variable or another deployment secret source.
Never log request headers, prompt bodies, raw reasoning, or provider response
bodies. Reconciliation compares the runtime `CallLedger` with totals rebuilt
from terminal `provider_call` rows.
