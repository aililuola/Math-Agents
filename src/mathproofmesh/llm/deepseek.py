from __future__ import annotations

import hashlib
import json
import time
from typing import Any, Literal

import httpx

from .base import LLMClient, LLMResponse, Message


ReasoningEffort = Literal["high", "max"]


class DeepSeekClient(LLMClient):
    """DeepSeek V4 client using the official OpenAI-compatible HTTP endpoint.

    MathProofMesh sends self-contained system/user requests and executes deterministic
    tools locally between model calls. Provider-side tool-call transcripts are therefore
    not replayed here. The model's private ``reasoning_content`` is never forwarded to
    another agent and is not persisted; only the final structured content and
    non-sensitive reasoning metadata are retained.

    ``streaming`` controls how the provider response reaches this adapter. When enabled,
    DeepSeek emits Server-Sent Events (SSE), which are incrementally consumed and locally
    aggregated into the same :class:`LLMResponse` returned by non-streaming calls. The
    public behavior of the rest of MathProofMesh is therefore unchanged.
    """

    def __init__(
        self,
        *,
        api_key: str,
        model: str = "deepseek-v4-pro",
        base_url: str = "https://api.deepseek.com",
        timeout_seconds: float = 600.0,
        extra_headers: dict[str, str] | None = None,
        thinking_enabled: bool = True,
        reasoning_effort: ReasoningEffort = "max",
        streaming: bool = False,
        user_id: str | None = None,
    ) -> None:
        super().__init__(model=model, timeout_seconds=timeout_seconds)
        self.base_url = base_url.rstrip("/")
        self.thinking_enabled = thinking_enabled
        self.reasoning_effort = reasoning_effort
        self.streaming = streaming
        self.user_id = user_id
        headers = {
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
            "Accept": "application/json",
        }
        headers.update(extra_headers or {})
        self._client = httpx.AsyncClient(timeout=timeout_seconds, headers=headers)

    async def list_models(self) -> list[str]:
        response = await self._client.get(f"{self.base_url}/models")
        response.raise_for_status()
        data = response.json()
        return [str(item.get("id")) for item in data.get("data", []) if item.get("id")]

    async def complete(
        self,
        messages: list[Message],
        *,
        temperature: float,
        max_output_tokens: int,
        json_mode: bool = False,
        schema_name: str | None = None,
        schema: dict[str, Any] | None = None,
    ) -> LLMResponse:
        payload: dict[str, Any] = {
            "model": self.model,
            "messages": messages,
            "max_tokens": max_output_tokens,
            "stream": self.streaming,
            "thinking": {"type": "enabled" if self.thinking_enabled else "disabled"},
        }
        if self.thinking_enabled:
            payload["reasoning_effort"] = self.reasoning_effort
            # DeepSeek V4 thinking mode ignores sampling controls such as temperature.
        else:
            payload["temperature"] = temperature
        if self.user_id:
            payload["user_id"] = self.user_id
        if json_mode:
            payload["response_format"] = {"type": "json_object"}

        started = time.perf_counter()
        if self.streaming:
            # The final SSE usage-only chunk is requested so budgets and cost accounting
            # retain the same semantics as non-streaming responses.
            payload["stream_options"] = {"include_usage": True}
            return await self._complete_streaming(payload, started)

        response = await self._client.post(f"{self.base_url}/chat/completions", json=payload)
        response.raise_for_status()
        data = response.json()
        elapsed = (time.perf_counter() - started) * 1000.0

        choice = data.get("choices", [{}])[0]
        message = choice.get("message") or {}
        content = self._text_value(message.get("content"))
        reasoning_text = self._text_value(message.get("reasoning_content"))
        usage = data.get("usage") or {}

        safe_raw: dict[str, Any] = {
            "id": data.get("id"),
            "object": data.get("object"),
            "created": data.get("created"),
            "model": data.get("model", self.model),
            "finish_reason": choice.get("finish_reason"),
            "usage": usage,
            "streaming": {"enabled": False},
            "reasoning": {
                "present": bool(reasoning_text),
                "characters": len(reasoning_text),
                "sha256": (
                    hashlib.sha256(reasoning_text.encode("utf-8")).hexdigest()
                    if reasoning_text
                    else None
                ),
            },
        }

        return LLMResponse(
            text=content,
            model=str(data.get("model", self.model)),
            provider="deepseek",
            input_tokens=int(usage.get("prompt_tokens", 0) or 0),
            output_tokens=int(usage.get("completion_tokens", 0) or 0),
            latency_ms=elapsed,
            request_id=response.headers.get("x-request-id") or data.get("id"),
            raw=safe_raw,
        )

    async def _complete_streaming(
        self,
        payload: dict[str, Any],
        started: float,
    ) -> LLMResponse:
        """Consume a DeepSeek SSE response and return one ordinary ``LLMResponse``.

        Only final answer fragments are accumulated as user-visible text. Private
        ``reasoning_content`` fragments are hashed incrementally and discarded, avoiding
        both a second full in-memory copy and accidental persistence in raw artifacts.
        """

        content_parts: list[str] = []
        reasoning_hash = hashlib.sha256()
        reasoning_characters = 0
        reasoning_present = False
        usage: dict[str, Any] = {}
        response_id: str | None = None
        response_object: str | None = None
        created: int | None = None
        response_model = self.model
        finish_reason: str | None = None
        request_id: str | None = None
        chunk_count = 0
        content_chunk_count = 0
        reasoning_chunk_count = 0
        done_received = False
        usage_received = False
        first_event_latency_ms: float | None = None

        async with self._client.stream(
            "POST",
            f"{self.base_url}/chat/completions",
            json=payload,
            headers={"Accept": "text/event-stream"},
        ) as response:
            response.raise_for_status()
            request_id = response.headers.get("x-request-id")

            async for raw_line in response.aiter_lines():
                line = raw_line.strip()
                if not line or line.startswith(":") or not line.startswith("data:"):
                    continue

                event = line[5:].strip()
                if event == "[DONE]":
                    done_received = True
                    break
                if not event:
                    continue

                if first_event_latency_ms is None:
                    first_event_latency_ms = (time.perf_counter() - started) * 1000.0

                try:
                    chunk = json.loads(event)
                except json.JSONDecodeError as exc:
                    raise ValueError("DeepSeek returned a malformed SSE data event") from exc
                if not isinstance(chunk, dict):
                    raise ValueError("DeepSeek returned a non-object SSE data event")
                if chunk.get("error"):
                    error = chunk["error"]
                    if isinstance(error, dict):
                        error_type = str(error.get("type") or "stream_error")
                        error_message = str(error.get("message") or "DeepSeek stream failed")
                    else:
                        error_type = "stream_error"
                        error_message = str(error)
                    raise RuntimeError(f"DeepSeek {error_type}: {error_message}")

                chunk_count += 1
                response_id = str(chunk.get("id") or response_id or "") or None
                response_object = str(chunk.get("object") or response_object or "") or None
                if chunk.get("created") is not None:
                    created = int(chunk["created"])
                response_model = str(chunk.get("model") or response_model)

                chunk_usage = chunk.get("usage")
                if isinstance(chunk_usage, dict):
                    usage = chunk_usage
                    usage_received = True

                choices = chunk.get("choices") or []
                if not isinstance(choices, list) or not choices:
                    # With include_usage=true, the final usage chunk intentionally has
                    # an empty choices array.
                    continue

                choice = choices[0] if isinstance(choices[0], dict) else {}
                if choice.get("finish_reason") is not None:
                    finish_reason = str(choice["finish_reason"])
                delta = choice.get("delta") or {}
                if not isinstance(delta, dict):
                    continue

                content = self._text_value(delta.get("content"))
                if content:
                    content_parts.append(content)
                    content_chunk_count += 1

                reasoning = self._text_value(delta.get("reasoning_content"))
                if reasoning:
                    reasoning_present = True
                    reasoning_characters += len(reasoning)
                    reasoning_hash.update(reasoning.encode("utf-8"))
                    reasoning_chunk_count += 1

        if not done_received:
            raise httpx.RemoteProtocolError(
                "DeepSeek SSE stream ended before the data: [DONE] terminator"
            )
        if not usage_received:
            raise httpx.RemoteProtocolError(
                "DeepSeek SSE stream ended without the requested usage summary"
            )

        elapsed = (time.perf_counter() - started) * 1000.0
        safe_raw: dict[str, Any] = {
            "id": response_id,
            "object": response_object,
            "created": created,
            "model": response_model,
            "finish_reason": finish_reason,
            "usage": usage,
            "streaming": {
                "enabled": True,
                "chunks": chunk_count,
                "content_chunks": content_chunk_count,
                "reasoning_chunks": reasoning_chunk_count,
                "first_event_latency_ms": first_event_latency_ms,
                "done_received": done_received,
                "usage_received": usage_received,
            },
            "reasoning": {
                "present": reasoning_present,
                "characters": reasoning_characters,
                "sha256": reasoning_hash.hexdigest() if reasoning_present else None,
            },
        }

        return LLMResponse(
            text="".join(content_parts),
            model=response_model,
            provider="deepseek",
            input_tokens=int(usage.get("prompt_tokens", 0) or 0),
            output_tokens=int(usage.get("completion_tokens", 0) or 0),
            latency_ms=elapsed,
            request_id=request_id or response_id,
            raw=safe_raw,
        )

    @staticmethod
    def _text_value(value: Any) -> str:
        if value is None:
            return ""
        if isinstance(value, list):
            return "".join(
                item.get("text", "") if isinstance(item, dict) else str(item)
                for item in value
            )
        return str(value)

    async def aclose(self) -> None:
        await self._client.aclose()
