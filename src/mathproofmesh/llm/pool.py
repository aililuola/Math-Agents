from __future__ import annotations

import asyncio
import hashlib
import logging
import time
from collections import defaultdict, deque
from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Any, Iterable

import httpx

from ..config import AgentConfig, SystemConfig
from ..schemas import AgentMetric, UsageRecord
from .anthropic import AnthropicClient
from .base import LLMClient, LLMResponse, Message
from .deepseek import DeepSeekClient
from .gemini import GeminiClient
from .mock import MockClient, MockResponder
from .openai_compatible import OpenAICompatibleClient

if TYPE_CHECKING:
    from ..verification.capability_profile import AgentCapabilityProfile

logger = logging.getLogger(__name__)

CAPABILITY_ROLE_MAP: dict[str, str] = {
    "planner": "prover",
    "explorer": "prover",
    "route_prover": "prover",
    "general": "prover",
    "route_skeptic": "skeptic",
    "counterexample_hunter": "skeptic",
    "route_referee": "route_referee",
    "structural_verifier": "structural_verifier",
    "detailed_verifier": "detailed_verifier",
    "final_verifier": "detailed_verifier",
    "analogy_agent": "analogy_agent",
    "construction_inventor": "construction_inventor",
    "representation_switchboard": "representation_switchboard",
    "invariant_hypothesis_agent": "invariant_hypothesis_agent",
    "reverse_goal_analyzer": "reverse_goal_analyzer",
    "meta_strategist": "meta_strategist",
    "inspiration_referee": "inspiration_referee",
    "bridge_prover": "bridge_prover",
    "conflict_resolver": "conflict_resolver",
    "tool_specialist": "tool_agent",
    "experimenter": "tool_agent",
}


class AgentCallFailure(RuntimeError):
    """Raised after one API key exhausts its call-level retry budget."""

    def __init__(
        self,
        agent_id: str,
        error: Exception | None,
        *,
        retryable: bool,
        status_code: int | None = None,
    ) -> None:
        self.agent_id = agent_id
        self.error = error
        self.retryable = retryable
        self.status_code = status_code
        super().__init__(
            f"agent {agent_id} failed after retries: {type(error).__name__ if error else 'unknown'}: {error}"
        )


class ProviderCircuitOpenError(RuntimeError):
    """Raised when distinct keys show that a shared provider transport is down."""

    def __init__(
        self,
        provider_scope: str,
        agent_ids: list[str],
        *,
        retry_after_seconds: float,
        cause: Exception | None = None,
    ) -> None:
        self.provider_scope = provider_scope
        self.agent_ids = sorted(set(agent_ids))
        self.retry_after_seconds = max(0.0, retry_after_seconds)
        self.cause = cause
        super().__init__(
            f"provider circuit open for {provider_scope}; distinct agents="
            f"{self.agent_ids}; retry after {self.retry_after_seconds:.1f}s"
        )


class ProviderCircuitBreaker:
    """Provider-scoped circuit breaker shared by every configured API key."""

    def __init__(self, config: SystemConfig) -> None:
        runtime = config.runtime
        self.enabled = runtime.provider_circuit_breaker_enabled
        self.threshold = runtime.provider_circuit_failure_threshold
        self.window_seconds = runtime.provider_circuit_window_seconds
        self.cooldown_seconds = runtime.provider_circuit_cooldown_seconds
        self.terminal_http_statuses = set(runtime.provider_terminal_http_statuses)
        self.shared_auth_http_statuses = set(runtime.provider_shared_auth_http_statuses)
        self._failures: dict[str, deque[tuple[float, str, str]]] = defaultdict(deque)
        self._open_until: dict[str, float] = {}

    @staticmethod
    def scope_for(config: AgentConfig) -> str:
        endpoint = (config.base_url or config.provider).rstrip("/").casefold()
        return f"{config.provider}:{endpoint}"

    def _prune(self, scope: str, now: float) -> None:
        history = self._failures[scope]
        while history and now - history[0][0] > self.window_seconds:
            history.popleft()

    def assert_available(self, scope: str) -> None:
        if not self.enabled:
            return
        now = time.time()
        opened_until = self._open_until.get(scope, 0.0)
        if opened_until <= now:
            if scope in self._open_until:
                self._open_until.pop(scope, None)
                self._failures.pop(scope, None)
            return
        agents = [agent_id for _, agent_id, _ in self._failures.get(scope, ())]
        raise ProviderCircuitOpenError(
            scope,
            agents,
            retry_after_seconds=opened_until - now,
        )

    def record_transport_failure(
        self, scope: str, agent_id: str, error: Exception
    ) -> None:
        if not self.enabled:
            return
        now = time.time()
        self._prune(scope, now)
        self._failures[scope].append((now, agent_id, type(error).__name__))
        distinct_agents = {item[1] for item in self._failures[scope]}
        if len(distinct_agents) < self.threshold:
            return
        opened_until = now + self.cooldown_seconds
        self._open_until[scope] = opened_until
        raise ProviderCircuitOpenError(
            scope,
            sorted(distinct_agents),
            retry_after_seconds=self.cooldown_seconds,
            cause=error,
        ) from error

    def record_http_failure(
        self,
        scope: str,
        agent_id: str,
        status_code: int,
        error: Exception,
    ) -> None:
        """Open immediately for account-wide failures, or after distinct keys agree."""

        if not self.enabled:
            return
        is_terminal = status_code in self.terminal_http_statuses
        is_shared_candidate = (
            status_code in self.shared_auth_http_statuses or status_code >= 500
        )
        if not is_terminal and not is_shared_candidate:
            return
        now = time.time()
        self._prune(scope, now)
        self._failures[scope].append((now, agent_id, f"http_{status_code}"))
        distinct_agents = {item[1] for item in self._failures[scope]}
        if not is_terminal and len(distinct_agents) < self.threshold:
            return
        self._open_until[scope] = now + self.cooldown_seconds
        raise ProviderCircuitOpenError(
            scope,
            sorted(distinct_agents),
            retry_after_seconds=self.cooldown_seconds,
            cause=error,
        ) from error

    def record_success(self, scope: str) -> None:
        if scope not in self._open_until:
            self._failures.pop(scope, None)

    def export_state(self) -> dict[str, Any]:
        return {
            "failures": {
                scope: [
                    {"timestamp": timestamp, "agent_id": agent_id, "error_type": kind}
                    for timestamp, agent_id, kind in entries
                ]
                for scope, entries in self._failures.items()
            },
            "open_until": dict(self._open_until),
        }

    def restore_state(self, state: dict[str, Any]) -> None:
        now = time.time()
        self._failures.clear()
        for scope, entries in dict(state.get("failures", {})).items():
            for entry in entries:
                timestamp = float(entry.get("timestamp", 0.0) or 0.0)
                if now - timestamp <= self.window_seconds:
                    self._failures[str(scope)].append(
                        (
                            timestamp,
                            str(entry.get("agent_id", "")),
                            str(entry.get("error_type", "unknown")),
                        )
                    )
        self._open_until = {
            str(scope): float(value)
            for scope, value in dict(state.get("open_until", {})).items()
            if float(value) > now
        }


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
    provider_circuit: ProviderCircuitBreaker
    provider_scope: str
    semaphore: asyncio.Semaphore = field(init=False)
    rate_limiter: SlidingWindowRateLimiter = field(init=False)
    trust_score: float = field(init=False)
    calls: int = 0
    failures: int = 0
    failed_attempts: int = 0
    failure_categories: dict[str, int] = field(default_factory=dict)
    consecutive_failures: int = 0
    cooldown_until: float = 0.0
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

    @property
    def in_cooldown(self) -> bool:
        return time.monotonic() < self.cooldown_until

    def specialty_score(self, hints: Iterable[str] | None) -> float:
        if not hints:
            return 0.0
        specialties = {s.lower() for s in self.config.specialties}
        return sum(1.0 for hint in hints if hint.lower() in specialties) / max(
            1, len(set(hints))
        )

    async def call(
        self,
        messages: list[Message],
        *,
        temperature: float | None = None,
        max_output_tokens: int | None = None,
        json_mode: bool = False,
        schema_name: str | None = None,
        schema: dict[str, Any] | None = None,
        thinking_enabled: bool | None = None,
        reasoning_effort: str | None = None,
    ) -> LLMResponse:
        self.provider_circuit.assert_available(self.provider_scope)
        await self.rate_limiter.acquire()
        async with self.global_semaphore, self.semaphore:
            self.active_calls += 1
            try:
                last_error: Exception | None = None
                last_retryable = True
                last_status: int | None = None
                for attempt in range(self.request_retries + 1):
                    self.provider_circuit.assert_available(self.provider_scope)
                    try:
                        response = await self.client.complete_with_policy(
                            messages,
                            temperature=self.config.temperature
                            if temperature is None
                            else temperature,
                            max_output_tokens=(
                                self.config.max_output_tokens
                                if max_output_tokens is None
                                else max_output_tokens
                            ),
                            json_mode=json_mode,
                            schema_name=schema_name,
                            schema=schema,
                            thinking_enabled=thinking_enabled,
                            reasoning_effort=reasoning_effort,
                        )
                        self._record(response)
                        self.provider_circuit.record_success(self.provider_scope)
                        return response
                    except httpx.HTTPStatusError as exc:
                        last_error = exc
                        last_status = exc.response.status_code
                        self.failed_attempts += 1
                        self.failure_categories["http_status"] = (
                            self.failure_categories.get("http_status", 0) + 1
                        )
                        last_retryable = (
                            last_status == 408
                            or last_status == 409
                            or last_status == 429
                            or last_status >= 500
                        )
                        if (
                            last_status in self.provider_circuit.terminal_http_statuses
                            or last_status
                            in self.provider_circuit.shared_auth_http_statuses
                            or (last_status >= 500 and attempt >= self.request_retries)
                        ):
                            self.provider_circuit.record_http_failure(
                                self.provider_scope,
                                self.id,
                                last_status,
                                exc,
                            )
                        if not last_retryable or attempt >= self.request_retries:
                            break
                    except (
                        httpx.TimeoutException,
                        httpx.NetworkError,
                        httpx.RemoteProtocolError,
                    ) as exc:
                        last_error = exc
                        last_retryable = True
                        self.failed_attempts += 1
                        category = self._failure_category(exc)
                        self.failure_categories[category] = (
                            self.failure_categories.get(category, 0) + 1
                        )
                        if isinstance(
                            exc,
                            (
                                httpx.ConnectError,
                                httpx.ConnectTimeout,
                                httpx.RemoteProtocolError,
                            ),
                        ):
                            self.provider_circuit.record_transport_failure(
                                self.provider_scope, self.id, exc
                            )
                        if attempt >= self.request_retries:
                            break
                    except (
                        Exception
                    ) as exc:  # Provider-specific parse/transport failures.
                        last_error = exc
                        last_retryable = True
                        self.failed_attempts += 1
                        category = self._failure_category(exc)
                        self.failure_categories[category] = (
                            self.failure_categories.get(category, 0) + 1
                        )
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
                shared_transport = isinstance(
                    last_error,
                    (
                        httpx.ConnectError,
                        httpx.ConnectTimeout,
                        httpx.RemoteProtocolError,
                    ),
                )
                if not shared_transport:
                    self.failures += 1
                    self.consecutive_failures += 1
                    cooldown_seconds = min(
                        120.0, 5.0 * (2 ** max(0, self.consecutive_failures - 1))
                    )
                    self.cooldown_until = time.monotonic() + cooldown_seconds
                raise AgentCallFailure(
                    self.id,
                    last_error,
                    retryable=last_retryable,
                    status_code=last_status,
                ) from last_error
            finally:
                self.active_calls -= 1

    @staticmethod
    def _failure_category(error: Exception) -> str:
        if isinstance(
            error,
            (httpx.ConnectError, httpx.ConnectTimeout, httpx.RemoteProtocolError),
        ):
            return "transport"
        if isinstance(error, httpx.TimeoutException):
            return "timeout"
        if isinstance(error, httpx.HTTPStatusError):
            return "http_status"
        return "provider_protocol"

    def _record(self, response: LLMResponse) -> None:
        self.calls += 1
        self.consecutive_failures = 0
        self.cooldown_until = 0.0
        self.input_tokens += response.input_tokens
        self.output_tokens += response.output_tokens
        self.total_latency_ms += response.latency_ms
        self.estimated_cost_usd += (
            response.input_tokens / 1_000_000 * self.config.pricing.input_per_million
            + response.output_tokens
            / 1_000_000
            * self.config.pricing.output_per_million
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
            successful_responses=self.calls,
            failed_attempts=self.failed_attempts,
            failure_categories=dict(self.failure_categories),
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
        self.provider_circuit = ProviderCircuitBreaker(config)
        self._agents: dict[str, AgentRuntime] = {}
        self._selection_counter = 0
        self._capability_profile: AgentCapabilityProfile | None = None
        self._capability_domain = "algebra"
        mock_responders = mock_responders or {}
        for agent_config in config.agents:
            if not agent_config.enabled:
                continue
            client = self._make_client(
                agent_config, mock_responders.get(agent_config.id)
            )
            self._agents[agent_config.id] = AgentRuntime(
                config=agent_config,
                client=client,
                global_semaphore=self._global_semaphore,
                request_retries=config.runtime.request_retries,
                provider_circuit=self.provider_circuit,
                provider_scope=ProviderCircuitBreaker.scope_for(agent_config),
            )

    def _make_client(
        self, cfg: AgentConfig, mock_responder: MockResponder | None
    ) -> LLMClient:
        if cfg.provider == "mock":
            return MockClient(
                model=cfg.model, responder=mock_responder, profile=cfg.mock_profile
            )
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
                base_url=cfg.base_url
                or "https://generativelanguage.googleapis.com/v1beta",
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

    def set_capability_context(
        self,
        profile: "AgentCapabilityProfile | None",
        *,
        domain: str,
    ) -> None:
        self._capability_profile = profile
        self._capability_domain = domain

    def capability_score(self, agent: AgentRuntime, role: str) -> float:
        profile = self._capability_profile
        capability_role = CAPABILITY_ROLE_MAP.get(role)
        if profile is None or capability_role is None or not profile.config.enabled:
            return 0.5
        return profile.score(agent.id, self._capability_domain, capability_role)

    def select(
        self,
        role: str,
        *,
        exclude: set[str] | None = None,
        specialty_hints: list[str] | None = None,
        prefer_provider_not: str | None = None,
    ) -> AgentRuntime:
        exclude = exclude or set()
        candidates = [
            a
            for a in self.agents
            if a.id not in exclude and a.supports_role(role) and not a.in_cooldown
        ]
        if not candidates:
            candidates = [
                a for a in self.agents if a.id not in exclude and a.supports_role(role)
            ]
        if not candidates:
            candidates = [
                a for a in self.agents if a.id not in exclude and not a.in_cooldown
            ]
        if not candidates:
            candidates = [a for a in self.agents if a.id not in exclude]
        if not candidates:
            # Last-resort reuse. Per-agent semaphores still prevent accidental concurrent key sharing.
            candidates = self.agents
        if not candidates:
            raise RuntimeError("agent pool is empty")

        self._selection_counter += 1

        def score(agent: AgentRuntime) -> tuple[float, float, int, str]:
            cross_provider = (
                0.12
                if prefer_provider_not and agent.provider != prefer_provider_not
                else 0.0
            )
            specialty = 0.18 * agent.specialty_score(specialty_hints)
            load_penalty = 0.08 * agent.active_calls + 0.001 * agent.calls
            # Tiny deterministic round-robin perturbation prevents a single high-prior key from taking every role.
            stable_id = int.from_bytes(
                hashlib.sha256(agent.id.encode("utf-8")).digest()[:4], "big"
            )
            rotation = ((stable_id + self._selection_counter) % 17) / 10000.0
            capability = self.capability_score(agent, role)
            return (
                0.72 * capability
                + 0.28 * agent.trust_score
                + cross_provider
                + specialty
                - load_penalty
                + rotation,
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
                    exclude=current_exclude
                    if distinct_when_possible
                    else (exclude or set()),
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

    def failover_candidates(
        self,
        role: str,
        *,
        exclude: set[str],
        specialty_hints: list[str] | None = None,
        prefer_provider_not: str | None = None,
        limit: int = 2,
    ) -> list[AgentRuntime]:
        """Return distinct backup agents without weakening per-key isolation."""
        candidates = [
            agent
            for agent in self.agents
            if agent.id not in exclude
            and not agent.in_cooldown
            and (agent.supports_role(role) or role == "general")
        ]
        if not candidates:
            candidates = [
                agent
                for agent in self.agents
                if agent.id not in exclude
                and (agent.supports_role(role) or role == "general")
            ]
        if not candidates:
            candidates = [
                agent
                for agent in self.agents
                if agent.id not in exclude and not agent.in_cooldown
            ]
        if not candidates:
            candidates = [agent for agent in self.agents if agent.id not in exclude]

        def score(agent: AgentRuntime) -> tuple[float, float, float, str]:
            cross_provider = (
                0.12
                if prefer_provider_not and agent.provider != prefer_provider_not
                else 0.0
            )
            specialty = 0.18 * agent.specialty_score(specialty_hints)
            load_penalty = (
                0.08 * agent.active_calls + 0.002 * agent.failures + 0.001 * agent.calls
            )
            return (
                agent.trust_score + cross_provider + specialty - load_penalty,
                -agent.active_calls,
                -agent.failures,
                agent.id,
            )

        return sorted(candidates, key=score, reverse=True)[: max(0, limit)]

    def total_calls(self) -> int:
        return sum(a.calls for a in self.agents)

    def total_tokens(self) -> int:
        return sum(a.input_tokens + a.output_tokens for a in self.agents)

    def total_cost_usd(self) -> float:
        return sum(a.estimated_cost_usd for a in self.agents)

    def metrics(self) -> list[AgentMetric]:
        return [a.metric() for a in sorted(self.agents, key=lambda x: x.id)]

    def restore_metrics(self, metrics: Iterable[AgentMetric | dict[str, Any]]) -> None:
        """Restore persisted usage counters without restoring provider hidden state."""
        for raw in metrics:
            metric = (
                raw if isinstance(raw, AgentMetric) else AgentMetric.model_validate(raw)
            )
            agent = self._agents.get(metric.agent_id)
            if agent is None:
                continue
            agent.calls = metric.calls
            agent.input_tokens = metric.usage.input_tokens
            agent.output_tokens = metric.usage.output_tokens
            agent.estimated_cost_usd = metric.usage.estimated_cost_usd
            agent.total_latency_ms = metric.usage.latency_ms
            agent.trust_score = metric.trust_score
            agent.failures = metric.failures
            if (
                metric.successful_responses
                and metric.successful_responses != metric.calls
            ):
                agent.calls = metric.successful_responses
            agent.failed_attempts = metric.failed_attempts
            agent.failure_categories = dict(metric.failure_categories)

    def provider_circuit_state(self) -> dict[str, Any]:
        return self.provider_circuit.export_state()

    def restore_provider_circuit_state(self, state: dict[str, Any]) -> None:
        self.provider_circuit.restore_state(state)

    async def aclose(self) -> None:
        await asyncio.gather(
            *(agent.client.aclose() for agent in self.agents), return_exceptions=True
        )
