from __future__ import annotations

import asyncio
import hashlib
import logging
import time
from collections import deque
from dataclasses import dataclass, field
from typing import Any, Iterable

import httpx

from ..config import AgentConfig, SystemConfig
from ..schemas import AgentMetric, UsageRecord
from .anthropic import AnthropicClient
from .base import LLMClient, LLMResponse, Message
from .deepseek import DeepSeekClient
from .gemini import GeminiClient
from .mock import MockClient, MockResponder
from .openai_compatible import OpenAICompatibleClient

logger = logging.getLogger(__name__)


class SlidingWindowRateLimiter:
    def __init__(self, requests_per_minute: int | None) -> None:
        self.limit = requests_per_minute
        self._timestamps: deque[float] = deque()
        self._lock = asyncio.Lock()

    async def acquire(self) -> None:
        if self.limit is None:
            return
        while True:
            async with self._lock:
                now = time.monotonic()
                while self._timestamps and now - self._timestamps[0] >= 60.0:
                    self._timestamps.popleft()
                if len(self._timestamps) < self.limit:
                    self._timestamps.append(now)
                    return
                sleep_for = max(0.01, 60.0 - (now - self._timestamps[0]))
            await asyncio.sleep(sleep_for)


@dataclass(slots=True)
class AgentRuntime:
    config: AgentConfig
    client: LLMClient
    global_semaphore: asyncio.Semaphore
    request_retries: int
    semaphore: asyncio.Semaphore = field(init=False)
    rate_limiter: SlidingWindowRateLimiter = field(init=False)
    trust_score: float = field(init=False)
    calls: int = 0
    failures: int = 0
    active_calls: int = 0
    input_tokens: int = 0
    output_tokens: int = 0
    estimated_cost_usd: float = 0.0
    total_latency_ms: float = 0.0

    def __post_init__(self) -> None:
        self.semaphore = asyncio.Semaphore(self.config.max_concurrency)
        self.rate_limiter = SlidingWindowRateLimiter(self.config.requests_per_minute)
        self.trust_score = self.config.trust_prior

    @property
    def id(self) -> str:
        return self.config.id

    @property
    def provider(self) -> str:
        return self.config.provider

    def supports_role(self, role: str) -> bool:
        return role in self.config.roles or "general" in self.config.roles

    def specialty_score(self, hints: Iterable[str] | None) -> float:
        if not hints:
            return 0.0
        specialties = {s.lower() for s in self.config.specialties}
        return sum(1.0 for hint in hints if hint.lower() in specialties) / max(1, len(set(hints)))

    async def call(
        self,
        messages: list[Message],
        *,
        temperature: float | None = None,
        max_output_tokens: int | None = None,
        json_mode: bool = False,
        schema_name: str | None = None,
        schema: dict[str, Any] | None = None,
    ) -> LLMResponse:
        await self.rate_limiter.acquire()
        async with self.global_semaphore, self.semaphore:
            self.active_calls += 1
            try:
                last_error: Exception | None = None
                for attempt in range(self.request_retries + 1):
                    try:
                        response = await self.client.complete(
                            messages,
                            temperature=self.config.temperature if temperature is None else temperature,
                            max_output_tokens=(
                                self.config.max_output_tokens
                                if max_output_tokens is None
                                else max_output_tokens
                            ),
                            json_mode=json_mode,
                            schema_name=schema_name,
                            schema=schema,
                        )
                        self._record(response)
                        return response
                    except httpx.HTTPStatusError as exc:
                        last_error = exc
                        status = exc.response.status_code
                        retryable = status == 429 or status >= 500
                        if not retryable or attempt >= self.request_retries:
                            break
                    except (httpx.TimeoutException, httpx.NetworkError) as exc:
                        last_error = exc
                        if attempt >= self.request_retries:
                            break
                    except Exception as exc:  # Provider-specific parse/transport failures.
                        last_error = exc
                        if attempt >= self.request_retries:
                            break
                    delay = min(8.0, 0.5 * (2**attempt))
                    logger.warning(
                        "Agent %s call failed (%s); retrying after %.1fs",
                        self.id,
                        type(last_error).__name__ if last_error else "unknown",
                        delay,
                    )
                    await asyncio.sleep(delay)
                self.failures += 1
                raise RuntimeError(f"agent {self.id} failed after retries: {last_error}") from last_error
            finally:
                self.active_calls -= 1

    def _record(self, response: LLMResponse) -> None:
        self.calls += 1
        self.input_tokens += response.input_tokens
        self.output_tokens += response.output_tokens
        self.total_latency_ms += response.latency_ms
        self.estimated_cost_usd += (
            response.input_tokens / 1_000_000 * self.config.pricing.input_per_million
            + response.output_tokens / 1_000_000 * self.config.pricing.output_per_million
        )

    def update_trust(self, delta: float) -> None:
        self.trust_score = min(1.0, max(0.0, self.trust_score + delta))

    def metric(self) -> AgentMetric:
        return AgentMetric(
            agent_id=self.id,
            calls=self.calls,
            usage=UsageRecord(
                input_tokens=self.input_tokens,
                output_tokens=self.output_tokens,
                total_tokens=self.input_tokens + self.output_tokens,
                estimated_cost_usd=self.estimated_cost_usd,
                latency_ms=self.total_latency_ms,
            ),
            trust_score=self.trust_score,
            failures=self.failures,
        )


class AgentPool:
    def __init__(
        self,
        config: SystemConfig,
        *,
        mock_responders: dict[str, MockResponder] | None = None,
    ) -> None:
        self.config = config
        self._global_semaphore = asyncio.Semaphore(config.runtime.max_parallel_calls)
        self._agents: dict[str, AgentRuntime] = {}
        self._selection_counter = 0
        mock_responders = mock_responders or {}
        for agent_config in config.agents:
            if not agent_config.enabled:
                continue
            client = self._make_client(agent_config, mock_responders.get(agent_config.id))
            self._agents[agent_config.id] = AgentRuntime(
                config=agent_config,
                client=client,
                global_semaphore=self._global_semaphore,
                request_retries=config.runtime.request_retries,
            )

    def _make_client(self, cfg: AgentConfig, mock_responder: MockResponder | None) -> LLMClient:
        if cfg.provider == "mock":
            return MockClient(model=cfg.model, responder=mock_responder, profile=cfg.mock_profile)
        api_key = cfg.resolve_key()
        if cfg.provider == "openai_compatible":
            return OpenAICompatibleClient(
                api_key=api_key,
                model=cfg.model,
                base_url=cfg.base_url or "https://api.openai.com/v1",
                timeout_seconds=cfg.timeout_seconds,
                extra_headers=cfg.extra_headers,
            )
        if cfg.provider == "deepseek":
            return DeepSeekClient(
                api_key=api_key,
                model=cfg.model,
                base_url=cfg.base_url or "https://api.deepseek.com",
                timeout_seconds=cfg.timeout_seconds,
                extra_headers=cfg.extra_headers,
                thinking_enabled=cfg.thinking_enabled,
                reasoning_effort=cfg.reasoning_effort or "high",
                streaming=cfg.streaming,
                user_id=cfg.user_id or cfg.id,
            )
        if cfg.provider == "anthropic":
            return AnthropicClient(
                api_key=api_key,
                model=cfg.model,
                base_url=cfg.base_url or "https://api.anthropic.com/v1",
                timeout_seconds=cfg.timeout_seconds,
                extra_headers=cfg.extra_headers,
            )
        if cfg.provider == "gemini":
            return GeminiClient(
                api_key=api_key,
                model=cfg.model,
                base_url=cfg.base_url or "https://generativelanguage.googleapis.com/v1beta",
                timeout_seconds=cfg.timeout_seconds,
                extra_headers=cfg.extra_headers,
            )
        raise ValueError(f"unsupported provider: {cfg.provider}")

    @property
    def agents(self) -> list[AgentRuntime]:
        return list(self._agents.values())

    def get(self, agent_id: str) -> AgentRuntime:
        try:
            return self._agents[agent_id]
        except KeyError as exc:
            raise KeyError(f"unknown agent: {agent_id}") from exc

    def select(
        self,
        role: str,
        *,
        exclude: set[str] | None = None,
        specialty_hints: list[str] | None = None,
        prefer_provider_not: str | None = None,
    ) -> AgentRuntime:
        exclude = exclude or set()
        candidates = [a for a in self.agents if a.id not in exclude and a.supports_role(role)]
        if not candidates:
            candidates = [a for a in self.agents if a.id not in exclude]
        if not candidates:
            # Last-resort reuse. Per-agent semaphores still prevent accidental concurrent key sharing.
            candidates = self.agents
        if not candidates:
            raise RuntimeError("agent pool is empty")

        self._selection_counter += 1

        def score(agent: AgentRuntime) -> tuple[float, float, int, str]:
            cross_provider = 0.12 if prefer_provider_not and agent.provider != prefer_provider_not else 0.0
            specialty = 0.18 * agent.specialty_score(specialty_hints)
            load_penalty = 0.08 * agent.active_calls + 0.001 * agent.calls
            # Tiny deterministic round-robin perturbation prevents a single high-prior key from taking every role.
            stable_id = int.from_bytes(hashlib.sha256(agent.id.encode("utf-8")).digest()[:4], "big")
            rotation = ((stable_id + self._selection_counter) % 17) / 10000.0
            return (
                agent.trust_score + cross_provider + specialty - load_penalty + rotation,
                -agent.active_calls,
                -agent.calls,
                agent.id,
            )

        return max(candidates, key=score)

    def select_many(
        self,
        role: str,
        count: int,
        *,
        exclude: set[str] | None = None,
        specialty_hints: list[str] | None = None,
        distinct_when_possible: bool = True,
    ) -> list[AgentRuntime]:
        selected: list[AgentRuntime] = []
        current_exclude = set(exclude or set())
        for _ in range(count):
            try:
                agent = self.select(
                    role,
                    exclude=current_exclude if distinct_when_possible else (exclude or set()),
                    specialty_hints=specialty_hints,
                )
            except RuntimeError:
                break
            selected.append(agent)
            if distinct_when_possible:
                current_exclude.add(agent.id)
                if len(current_exclude) >= len(self._agents):
                    current_exclude = set(exclude or set())
        return selected

    def total_calls(self) -> int:
        return sum(a.calls for a in self.agents)

    def total_tokens(self) -> int:
        return sum(a.input_tokens + a.output_tokens for a in self.agents)

    def total_cost_usd(self) -> float:
        return sum(a.estimated_cost_usd for a in self.agents)

    def metrics(self) -> list[AgentMetric]:
        return [a.metric() for a in sorted(self.agents, key=lambda x: x.id)]

    async def aclose(self) -> None:
        await asyncio.gather(*(agent.client.aclose() for agent in self.agents), return_exceptions=True)
