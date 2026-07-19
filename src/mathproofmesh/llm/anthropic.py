from __future__ import annotations

import time
from typing import Any

import httpx

from .base import LLMClient, LLMResponse, Message


class AnthropicClient(LLMClient):
    def __init__(
        self,
        *,
        api_key: str,
        model: str,
        base_url: str = "https://api.anthropic.com/v1",
        timeout_seconds: float = 180.0,
        extra_headers: dict[str, str] | None = None,
    ) -> None:
        super().__init__(model=model, timeout_seconds=timeout_seconds)
        self.base_url = base_url.rstrip("/")
        headers = {
            "x-api-key": api_key,
            "anthropic-version": "2023-06-01",
            "content-type": "application/json",
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
        system_parts: list[str] = []
        converted: list[dict[str, Any]] = []
        for message in messages:
            if message["role"] == "system":
                system_parts.append(message["content"])
            else:
                role = "assistant" if message["role"] == "assistant" else "user"
                converted.append({"role": role, "content": message["content"]})
        payload: dict[str, Any] = {
            "model": self.model,
            "max_tokens": max_output_tokens,
            "temperature": temperature,
            "messages": converted,
        }
        if system_parts:
            payload["system"] = "\n\n".join(system_parts)

        started = time.perf_counter()
        response = await self._client.post(f"{self.base_url}/messages", json=payload)
        response.raise_for_status()
        data = response.json()
        elapsed = (time.perf_counter() - started) * 1000.0
        content = "".join(
            block.get("text", "")
            for block in data.get("content", [])
            if isinstance(block, dict) and block.get("type") == "text"
        )
        usage = data.get("usage") or {}
        return LLMResponse(
            text=content,
            model=data.get("model", self.model),
            provider="anthropic",
            input_tokens=int(usage.get("input_tokens", 0) or 0),
            output_tokens=int(usage.get("output_tokens", 0) or 0),
            latency_ms=elapsed,
            request_id=response.headers.get("request-id") or data.get("id"),
            raw=data,
        )

    async def aclose(self) -> None:
        await self._client.aclose()
