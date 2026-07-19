from __future__ import annotations

import json
from typing import Any, Callable

from .base import LLMClient, LLMResponse, Message


MockResponder = Callable[[str | None, list[Message], dict[str, Any] | None], dict[str, Any] | str]


class MockClient(LLMClient):
    """Deterministic provider used only for tests and dry-runs."""

    def __init__(
        self,
        *,
        model: str = "mock-model",
        responder: MockResponder | None = None,
        profile: str | None = None,
    ) -> None:
        super().__init__(model=model, timeout_seconds=1.0)
        self.responder = responder
        self.profile = profile or "default"

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
        if self.responder is None:
            payload: dict[str, Any] | str = {"mock": True, "schema_name": schema_name}
        else:
            payload = self.responder(schema_name, messages, schema)
        text = payload if isinstance(payload, str) else json.dumps(payload, ensure_ascii=False)
        return LLMResponse(
            text=text,
            model=self.model,
            provider="mock",
            input_tokens=sum(len(m["content"].split()) for m in messages),
            output_tokens=len(text.split()),
            latency_ms=0.1,
            raw={"profile": self.profile},
        )
