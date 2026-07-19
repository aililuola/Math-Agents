from __future__ import annotations

import time
from typing import Any

import httpx

from .base import LLMClient, LLMResponse, Message


class OpenAICompatibleClient(LLMClient):
    def __init__(
        self,
        *,
        api_key: str,
        model: str,
        base_url: str = "https://api.openai.com/v1",
        timeout_seconds: float = 180.0,
        extra_headers: dict[str, str] | None = None,
    ) -> None:
        super().__init__(model=model, timeout_seconds=timeout_seconds)
        self.base_url = base_url.rstrip("/")
        headers = {
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
        }
        headers.update(extra_headers or {})
        self._client = httpx.AsyncClient(timeout=timeout_seconds, headers=headers)

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
            "temperature": temperature,
            "max_tokens": max_output_tokens,
        }
        if json_mode:
            # JSON object mode has the broadest compatibility across OpenAI-style providers.
            # The exact Pydantic schema is also included in the prompt by StructuredAgentRunner.
            payload["response_format"] = {"type": "json_object"}

        started = time.perf_counter()
        response = await self._client.post(
            f"{self.base_url}/chat/completions", json=payload
        )
        response.raise_for_status()
        data = response.json()
        elapsed = (time.perf_counter() - started) * 1000.0

        choice = data.get("choices", [{}])[0]
        content = choice.get("message", {}).get("content", "")
        if isinstance(content, list):
            content = "".join(
                item.get("text", "") if isinstance(item, dict) else str(item)
                for item in content
            )
        usage = data.get("usage") or {}
        return LLMResponse(
            text=str(content),
            model=data.get("model", self.model),
            provider="openai_compatible",
            input_tokens=int(usage.get("prompt_tokens", 0) or 0),
            output_tokens=int(usage.get("completion_tokens", 0) or 0),
            latency_ms=elapsed,
            request_id=response.headers.get("x-request-id") or data.get("id"),
            raw=data,
        )

    async def aclose(self) -> None:
        await self._client.aclose()
