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

    async def aclose(self) -> None:
        return None
