from __future__ import annotations

import asyncio
import hashlib
import json
import ssl
import time
from collections.abc import AsyncIterator
from typing import Any, Literal

import httpx

from ..reasoning_trace import ReasoningTraceCall, current_reasoning_trace
from .base import LLMClient, LLMResponse, Message


ReasoningEffort = Literal["high", "max"]


class DeepSeekClient(LLMClient):
    """DeepSeek V4 client using the official OpenAI-compatible HTTP endpoint.

    MathProofMesh calls the model with self-contained system/user messages and executes
    deterministic tools locally between model calls. Consequently, provider-side tool-call
    transcripts are not replayed here. The model's ``reasoning_content`` is never
    forwarded to another agent. When a run-scoped trace binding is active, the provider-
    emitted text is persisted locally for the desktop node inspector; provider response
    metadata still retains only non-sensitive counts and hashes.

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
        self._progress_by_task: dict[int, dict[str, Any]] = {}

    def progress_snapshot(self) -> dict[str, Any]:
        return self._snapshot(self._progress)

    def progress_snapshot_for(self, request: object) -> dict[str, Any]:
        return self._snapshot(self._progress_by_task.get(id(request), {}))

    def clear_progress_for(self, request: object) -> None:
        self._progress_by_task.pop(id(request), None)

    @staticmethod
    def _snapshot(progress: dict[str, Any]) -> dict[str, Any]:
        snapshot = dict(progress)
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
        return await self.complete_with_policy(
            messages,
            temperature=temperature,
            max_output_tokens=max_output_tokens,
            json_mode=json_mode,
            thinking_enabled=self.thinking_enabled,
            reasoning_effort=self.reasoning_effort,
        )

    async def complete_with_policy(
        self,
        messages: list[Message],
        *,
        temperature: float,
        max_output_tokens: int,
        json_mode: bool = False,
        schema_name: str | None = None,
        schema: dict[str, Any] | None = None,
        thinking_enabled: bool | None = None,
        reasoning_effort: str | None = None,
    ) -> LLMResponse:
        enabled = (
            self.thinking_enabled if thinking_enabled is None else thinking_enabled
        )
        effort = self.reasoning_effort if reasoning_effort is None else reasoning_effort
        if effort not in {"high", "max"}:
            raise ValueError(f"unsupported DeepSeek reasoning_effort: {effort!r}")
        binding = current_reasoning_trace()
        trace_call = (
            binding.store.begin_call(
                task_id=binding.task_id,
                agent_id=binding.agent_id,
                stage=binding.stage,
                thinking_enabled=enabled,
                reasoning_effort=effort if enabled else None,
            )
            if binding is not None
            else None
        )
        try:
            response = await self._complete_with_policy(
                messages,
                temperature=temperature,
                max_output_tokens=max_output_tokens,
                json_mode=json_mode,
                thinking_enabled=enabled,
                reasoning_effort=effort,
                trace_call=trace_call,
            )
        except asyncio.CancelledError:
            if trace_call is not None:
                trace_call.finish("cancelled")
            raise
        except BaseException as exc:
            if trace_call is not None:
                trace_call.finish("failed", error_type=type(exc).__name__)
            raise
        if trace_call is not None:
            trace_call.finish("completed")
        return response

    async def _complete_with_policy(
        self,
        messages: list[Message],
        *,
        temperature: float,
        max_output_tokens: int,
        json_mode: bool,
        thinking_enabled: bool,
        reasoning_effort: str,
        trace_call: ReasoningTraceCall | None,
    ) -> LLMResponse:
        task = asyncio.current_task()
        task_key = id(task) if task is not None else id(object())
        progress = {
            "streaming": self.streaming,
            "chunks": 0,
            "reasoning_characters": 0,
            "content_characters": 0,
            "approx_output_tokens": 0,
            "last_data_at_monotonic": time.monotonic(),
        }
        self._progress_by_task[task_key] = progress
        self._progress = progress
        payload: dict[str, Any] = {
            "model": self.model,
            "messages": messages,
            "max_tokens": max_output_tokens,
            "stream": self.streaming,
            "thinking": {"type": "enabled" if thinking_enabled else "disabled"},
        }
        if thinking_enabled:
            payload["reasoning_effort"] = reasoning_effort
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
            return await self._complete_streaming(
                payload,
                started,
                progress,
                trace_call,
            )

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
        if trace_call is not None and reasoning_text:
            trace_call.append(reasoning_text)
        usage = data.get("usage") or {}

        safe_raw: dict[str, Any] = {
            "id": data.get("id"),
            "object": data.get("object"),
            "created": data.get("created"),
            "model": data.get("model", self.model),
            "finish_reason": choice.get("finish_reason"),
            "usage": usage,
            "streaming": False,
            "request_policy": {
                "thinking_enabled": thinking_enabled,
                "reasoning_effort": reasoning_effort if thinking_enabled else None,
            },
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
        progress: dict[str, Any],
        trace_call: ReasoningTraceCall | None,
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

        try:
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
                                error.get("message")
                                or error.get("type")
                                or "unknown error"
                            )
                        else:
                            detail = str(error)
                        raise RuntimeError(f"DeepSeek streaming error: {detail}")

                    chunk_count += 1
                    progress["chunks"] = chunk_count
                    progress["last_data_at_monotonic"] = time.monotonic()
                    if first_chunk_latency_ms is None:
                        first_chunk_latency_ms = (
                            time.perf_counter() - started
                        ) * 1000.0

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
                        progress["content_characters"] = int(
                            progress.get("content_characters", 0)
                        ) + len(content)

                    reasoning = self._text_value(delta.get("reasoning_content"))
                    if reasoning:
                        reasoning_present = True
                        reasoning_characters += len(reasoning)
                        reasoning_hash.update(reasoning.encode("utf-8"))
                        progress["reasoning_characters"] = reasoning_characters
                        if trace_call is not None:
                            trace_call.append(reasoning)
                    if trace_call is not None:
                        trace_call.flush_due()
                    total_characters = int(
                        progress.get("reasoning_characters", 0)
                    ) + int(progress.get("content_characters", 0))
                    # This is deliberately observational only. DeepSeek's final usage
                    # chunk is the sole authoritative output-token count.
                    progress["approx_output_tokens"] = (total_characters + 3) // 4
        except (
            httpx.TransportError,
            ssl.SSLError,
            ConnectionError,
            TimeoutError,
        ) as exc:
            # Salvage the partial chain of thought: force-flush whatever streamed
            # before the disconnect into the trace archive, marked as failed.
            if trace_call is not None:
                trace_call.finish("failed", error_type=type(exc).__name__)
            self._annotate_stream_disconnect(
                exc,
                reasoning_characters,
                "".join(content_parts),
            )
            raise

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
            "request_policy": {
                "thinking_enabled": payload["thinking"]["type"] == "enabled",
                "reasoning_effort": payload.get("reasoning_effort"),
            },
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
    def _annotate_stream_disconnect(
        exc: BaseException,
        reasoning_characters: int,
        partial_content: str,
    ) -> None:
        """Record the salvaged reasoning size inside the propagated error message.

        Retry and circuit-breaker layers dispatch on the exception class, so the
        original instance is annotated in place instead of being wrapped.
        """

        # Only public answer content is exposed to the retry layer. Provider
        # reasoning remains confined to the local trace archive.
        setattr(exc, "mathproofmesh_partial_content", partial_content)
        setattr(
            exc,
            "mathproofmesh_partial_content_sha256",
            hashlib.sha256(partial_content.encode("utf-8")).hexdigest()
            if partial_content
            else None,
        )
        note = (
            f"{reasoning_characters} reasoning characters were received and "
            "preserved in the reasoning trace before the stream disconnected; "
            f"{len(partial_content)} public content characters are available "
            "for bounded retry recovery"
        )
        annotated = f"{str(exc) or type(exc).__name__} [{note}]"
        strerror = getattr(exc, "strerror", None)
        if isinstance(strerror, str) and strerror:
            # OSError.__str__ prefers errno/strerror over args.
            exc.strerror = f"{strerror} [{note}]"
        exc.args = (annotated,)

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
