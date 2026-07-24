from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import Any


Message = dict[str, str]


@dataclass(slots=True)
class LLMResponse:
    text: str
    model: str
    provider: str
    input_tokens: int = 0
    output_tokens: int = 0
    latency_ms: float = 0.0
    request_id: str | None = None
    raw: dict[str, Any] = field(default_factory=dict)

    @property
    def total_tokens(self) -> int:
        return self.input_tokens + self.output_tokens


class LLMClient(ABC):
    def __init__(self, model: str, timeout_seconds: float = 180.0) -> None:
        self.model = model
        self.timeout_seconds = timeout_seconds

    @abstractmethod
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
        raise NotImplementedError

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
        """Complete with optional per-call reasoning controls.

        Providers without per-call thinking controls intentionally ignore the two
        policy arguments. This keeps provider-specific controls out of the common
        orchestration contract without breaking third-party or test clients.
        """

        return await self.complete(
            messages,
            temperature=temperature,
            max_output_tokens=max_output_tokens,
            json_mode=json_mode,
            schema_name=schema_name,
            schema=schema,
        )

    async def aclose(self) -> None:
        return None

    def progress_snapshot(self) -> dict[str, Any]:
        """Return content-free transport progress for Activity heartbeats.

        Provider adapters may override this. The snapshot must never expose private
        reasoning text or response content.
        """

        return {}

    def progress_snapshot_for(self, request: object) -> dict[str, Any]:
        """Return progress for one call task when the provider supports it."""

        return self.progress_snapshot()

    def clear_progress_for(self, request: object) -> None:
        """Release request-scoped progress after the orchestration task ends."""

        return None
