from __future__ import annotations

import hashlib
import json
import time
from collections.abc import AsyncIterator
from typing import Any, Literal

import httpx

from .base import LLMClient, LLMResponse, Message


ReasoningEffort = Literal["high", "max"]


class DeepSeekClient(LLMClient):
    """DeepSeek V4 client using the official OpenAI-compatible HTTP endpoint.

    MathProofMesh calls the model with self-contained system/user messages and executes
    deterministic tools locally between model calls. Consequently, provider-side tool-call
    transcripts are not replayed here. The model's private ``reasoning_content`` is never
    forwarded to another agent and is not persisted; only the final structured content and
    non-sensitive reasoning metadata are retained.

    ``streaming`` controls only how this adapter reads the provider response. When enabled,
    DeepSeek sends Server-Sent Events (SSE), which are aggregated locally into the same
    ``LLMResponse`` shape used by the non-streaming path. The default remains non-streaming
    for backward compatibility.
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
        self._progress: dict[str, Any] = {}

    def progress_snapshot(self) -> dict[str, Any]:
        snapshot = dict(self._progress)
        last_data_at = snapshot.pop("last_data_at_monotonic", None)
        if last_data_at is not None:
            snapshot["last_data_age_seconds"] = max(
                0.0, time.monotonic() - float(last_data_at)
            )
        return snapshot

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
        self._progress = {
            "streaming": self.streaming,
            "chunks": 0,
            "reasoning_characters": 0,
            "content_characters": 0,
            "approx_output_tokens": 0,
            "last_data_at_monotonic": time.monotonic(),
        }
        payload: dict[str, Any] = {
            "model": self.model,
            "messages": messages,
            "max_tokens": max_output_tokens,
            "stream": self.streaming,
            "thinking": {"type": "enabled" if self.thinking_enabled else "disabled"},
        }
        if self.thinking_enabled:
            payload["reasoning_effort"] = self.reasoning_effort
            # DeepSeek V4 thinking mode does not use sampling controls such as temperature.
        else:
            payload["temperature"] = temperature
        if self.user_id:
            payload["user_id"] = self.user_id
        if json_mode:
            payload["response_format"] = {"type": "json_object"}

        started = time.perf_counter()
        if self.streaming:
            payload["stream_options"] = {"include_usage": True}
            return await self._complete_streaming(payload, started)

        response = await self._client.post(
            f"{self.base_url}/chat/completions", json=payload
        )
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
            "streaming": False,
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
        content_parts: list[str] = []
        reasoning_hash = hashlib.sha256()
        reasoning_characters = 0
        reasoning_present = False
        usage: dict[str, Any] = {}
        usage_received = False
        response_id: str | None = None
        response_object: str | None = None
        created: int | None = None
        response_model = self.model
        finish_reason: str | None = None
        request_id: str | None = None
        chunk_count = 0
        done_received = False
        first_chunk_latency_ms: float | None = None

        async with self._client.stream(
            "POST",
            f"{self.base_url}/chat/completions",
            json=payload,
            headers={"Accept": "text/event-stream"},
        ) as response:
            response.raise_for_status()
            request_id = response.headers.get("x-request-id")
            async for event in self._iter_sse_data(response):
                if event == "[DONE]":
                    done_received = True
                    break
                try:
                    chunk = json.loads(event)
                except json.JSONDecodeError as exc:
                    preview = event[:240].replace("\n", "\\n")
                    raise RuntimeError(
                        f"invalid DeepSeek SSE JSON chunk: {preview}"
                    ) from exc
                if not isinstance(chunk, dict):
                    raise RuntimeError("DeepSeek returned a non-object SSE payload")
                if chunk.get("error"):
                    error = chunk["error"]
                    if isinstance(error, dict):
                        detail = (
                            error.get("message") or error.get("type") or "unknown error"
                        )
                    else:
                        detail = str(error)
                    raise RuntimeError(f"DeepSeek streaming error: {detail}")

                chunk_count += 1
                self._progress["chunks"] = chunk_count
                self._progress["last_data_at_monotonic"] = time.monotonic()
                if first_chunk_latency_ms is None:
                    first_chunk_latency_ms = (time.perf_counter() - started) * 1000.0

                if chunk.get("id") is not None:
                    response_id = str(chunk["id"])
                if chunk.get("object") is not None:
                    response_object = str(chunk["object"])
                if chunk.get("created") is not None:
                    created = int(chunk["created"])
                if chunk.get("model") is not None:
                    response_model = str(chunk["model"])
                if isinstance(chunk.get("usage"), dict):
                    usage = dict(chunk["usage"])
                    usage_received = bool(usage)

                choices = chunk.get("choices") or []
                if not isinstance(choices, list) or not choices:
                    # With include_usage=true, the final usage-only chunk has choices=[].
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
                    self._progress["content_characters"] = int(
                        self._progress.get("content_characters", 0)
                    ) + len(content)

                reasoning = self._text_value(delta.get("reasoning_content"))
                if reasoning:
                    reasoning_present = True
                    reasoning_characters += len(reasoning)
                    reasoning_hash.update(reasoning.encode("utf-8"))
                    self._progress["reasoning_characters"] = reasoning_characters
                total_characters = int(
                    self._progress.get("reasoning_characters", 0)
                ) + int(self._progress.get("content_characters", 0))
                self._progress["approx_output_tokens"] = (total_characters + 3) // 4

        if not done_received:
            raise RuntimeError("DeepSeek SSE stream ended before data: [DONE]")
        if (
            payload.get("stream_options", {}).get("include_usage")
            and not usage_received
        ):
            raise RuntimeError(
                "DeepSeek SSE stream ended without the requested final usage summary"
            )

        elapsed = (time.perf_counter() - started) * 1000.0
        safe_raw: dict[str, Any] = {
            "id": response_id,
            "object": response_object,
            "created": created,
            "model": response_model,
            "finish_reason": finish_reason,
            "usage": usage,
            "streaming": True,
            "stream": {
                "chunks": chunk_count,
                "done_received": done_received,
                "usage_received": usage_received,
                "first_chunk_latency_ms": first_chunk_latency_ms,
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
    async def _iter_sse_data(response: httpx.Response) -> AsyncIterator[str]:
        """Yield complete SSE ``data`` payloads, including a final ``[DONE]`` marker.

        DeepSeek currently emits one JSON object per ``data:`` line, but this parser also
        accepts standards-compliant multi-line data fields and ignores comments/other SSE
        fields. This keeps provider transport details out of the orchestration layer.
        """

        data_lines: list[str] = []
        async for raw_line in response.aiter_lines():
            line = raw_line.rstrip("\r")
            if not line:
                if data_lines:
                    yield "\n".join(data_lines).lstrip("\ufeff")
                    data_lines.clear()
                continue
            if line.startswith(":"):
                continue
            if not line.startswith("data:"):
                continue
            value = line[5:]
            if value.startswith(" "):
                value = value[1:]
            data_lines.append(value)

        if data_lines:
            yield "\n".join(data_lines).lstrip("\ufeff")

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
