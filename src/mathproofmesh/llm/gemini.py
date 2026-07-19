from __future__ import annotations

import time
from typing import Any
from urllib.parse import quote

import httpx

from .base import LLMClient, LLMResponse, Message


class GeminiClient(LLMClient):
    def __init__(
        self,
        *,
        api_key: str,
        model: str,
        base_url: str = "https://generativelanguage.googleapis.com/v1beta",
        timeout_seconds: float = 180.0,
        extra_headers: dict[str, str] | None = None,
    ) -> None:
        super().__init__(model=model, timeout_seconds=timeout_seconds)
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self._client = httpx.AsyncClient(
            timeout=timeout_seconds,
            headers={"Content-Type": "application/json", **(extra_headers or {})},
        )

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
        contents: list[dict[str, Any]] = []
        for message in messages:
            if message["role"] == "system":
                system_parts.append(message["content"])
                continue
            role = "model" if message["role"] == "assistant" else "user"
            contents.append({"role": role, "parts": [{"text": message["content"]}]})

        generation_config: dict[str, Any] = {
            "temperature": temperature,
            "maxOutputTokens": max_output_tokens,
        }
        if json_mode:
            generation_config["responseMimeType"] = "application/json"
            if schema:
                # Not every Gemini endpoint accepts the full JSON Schema dialect.
                # Keep schema in the textual prompt; only set MIME type here.
                pass

        payload: dict[str, Any] = {
            "contents": contents,
            "generationConfig": generation_config,
        }
        if system_parts:
            payload["systemInstruction"] = {
                "parts": [{"text": "\n\n".join(system_parts)}]
            }

        model = quote(self.model, safe="-._/")
        url = f"{self.base_url}/models/{model}:generateContent?key={quote(self.api_key, safe='')}"
        started = time.perf_counter()
        response = await self._client.post(url, json=payload)
        response.raise_for_status()
        data = response.json()
        elapsed = (time.perf_counter() - started) * 1000.0

        parts = data.get("candidates", [{}])[0].get("content", {}).get("parts", [])
        text = "".join(part.get("text", "") for part in parts if isinstance(part, dict))
        usage = data.get("usageMetadata") or {}
        return LLMResponse(
            text=text,
            model=self.model,
            provider="gemini",
            input_tokens=int(usage.get("promptTokenCount", 0) or 0),
            output_tokens=int(usage.get("candidatesTokenCount", 0) or 0),
            latency_ms=elapsed,
            request_id=response.headers.get("x-request-id"),
            raw=data,
        )

    async def aclose(self) -> None:
        await self._client.aclose()
