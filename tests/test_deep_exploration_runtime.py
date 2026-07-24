from __future__ import annotations

import asyncio
from pathlib import Path
from typing import Any

import pytest
from pydantic import BaseModel

from mathproofmesh.activity import ActivityStream
from mathproofmesh.agents import (
    AgentFailoverExhausted,
    AgentStreamIdleTimeoutError,
    BudgetExhaustedError,
    ReasoningBudgetExhaustedError,
    StructuredAgentRunner,
)
from mathproofmesh.llm.base import LLMClient, LLMResponse, Message
from mathproofmesh.llm.pool import AgentPool, ProviderCircuitOpenError
from mathproofmesh.mock_demo import build_demo_config
from mathproofmesh.orchestrator import ProofMeshOrchestrator
from mathproofmesh.prompts import PromptBundle
from mathproofmesh.store import ArtifactStore


class _TinyResult(BaseModel):
    ok: bool


class _ReasoningStreamWithoutArtifact(LLMClient):
    def __init__(
        self,
        model: str,
        approx_tokens: int,
        *,
        last_data_age_seconds: float = 0.0,
        chunks: int = 10,
    ) -> None:
        super().__init__(model)
        self.approx_tokens = approx_tokens
        self.last_data_age_seconds = last_data_age_seconds
        self.chunks = chunks

    def progress_snapshot(self) -> dict[str, Any]:
        return {
            "streaming": True,
            "chunks": self.chunks,
            "approx_output_tokens": self.approx_tokens,
            "reasoning_characters": self.approx_tokens * 3,
            "content_characters": 0,
            "last_data_age_seconds": self.last_data_age_seconds,
        }

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
        await asyncio.Event().wait()
        raise AssertionError("unreachable")


class _DelayedArtifactStream(LLMClient):
    def __init__(self, model: str, progress: dict[str, Any]) -> None:
        super().__init__(model)
        self.progress = dict(progress)

    def progress_snapshot(self) -> dict[str, Any]:
        return dict(self.progress)

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
        await asyncio.sleep(0.05)
        return LLMResponse(
            text='{"ok": true}',
            model=self.model,
            provider="mock",
            output_tokens=int(self.progress.get("approx_output_tokens", 0)),
        )


class _LengthWithoutArtifact(LLMClient):
    def __init__(self, model: str) -> None:
        super().__init__(model)
        self.calls = 0

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
        self.calls += 1
        return LLMResponse(
            text="",
            model=self.model,
            provider="mock",
            output_tokens=max_output_tokens,
            raw={"finish_reason": "length"},
        )


class _RequestScopedProgressClient(LLMClient):
    def __init__(self) -> None:
        super().__init__("request-scoped")
        self.started = 0
        self.progress: dict[int, dict[str, Any]] = {}

    def progress_snapshot(self) -> dict[str, Any]:
        # Deliberately poisonous shared state. A request-scoped monitor must not
        # expose this active request to another call queued on the same Agent.
        return {
            "streaming": True,
            "last_data_age_seconds": 301.0,
            "approx_output_tokens": 1000,
        }

    def progress_snapshot_for(self, request: object) -> dict[str, Any]:
        return dict(self.progress.get(id(request), {}))

    def clear_progress_for(self, request: object) -> None:
        self.progress.pop(id(request), None)

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
        task = asyncio.current_task()
        assert task is not None
        self.started += 1
        if self.started == 1:
            self.progress[id(task)] = {
                "streaming": True,
                "last_data_age_seconds": 301.0,
                "approx_output_tokens": 1000,
            }
            await asyncio.Event().wait()
        self.progress[id(task)] = {
            "streaming": True,
            "last_data_age_seconds": 0.0,
            "approx_output_tokens": 2,
        }
        await asyncio.sleep(0.03)
        return LLMResponse(
            text='{"ok": true}',
            model=self.model,
            provider="mock",
            output_tokens=2,
        )


class _BlockingScopedProgressClient(LLMClient):
    def __init__(self) -> None:
        super().__init__("blocking-scoped")
        self.progress: dict[int, dict[str, Any]] = {}
        self.release = asyncio.Event()

    def progress_snapshot_for(self, request: object) -> dict[str, Any]:
        return dict(self.progress.get(id(request), {}))

    def clear_progress_for(self, request: object) -> None:
        self.progress.pop(id(request), None)

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
        task = asyncio.current_task()
        assert task is not None
        self.progress[id(task)] = {
            "streaming": True,
            "chunks": 1,
            "last_data_age_seconds": 0.0,
            "approx_output_tokens": 1,
        }
        await self.release.wait()
        return LLMResponse(
            text='{"ok": true}',
            model=self.model,
            provider="mock",
            output_tokens=1,
        )


def test_stage_thinking_policy_is_artifact_aware_and_tiered(tmp_path: Path) -> None:
    config = build_demo_config(str(tmp_path / "runs"))
    runner = StructuredAgentRunner(
        config,
        AgentPool(config),
        ArtifactStore(tmp_path / "runs", "thinking-policy"),
    )

    def bundle(stage: str, output_tier: int | None = None) -> PromptBundle:
        return PromptBundle(
            stage=stage,
            system="Return JSON.",
            user="Return JSON.",
            response_model=_TinyResult,
            output_tier=output_tier,
        )

    assert runner._thinking_policy(
        bundle("triage"), effective_max_output_tokens=12000, repair=False
    ) == (False, None)
    assert runner._thinking_policy(
        bundle("strategy_generation"),
        effective_max_output_tokens=24000,
        repair=False,
    ) == (True, "high")
    assert runner._thinking_policy(
        bundle("route_prove", 0),
        effective_max_output_tokens=64000,
        repair=False,
    ) == (True, "high")
    assert runner._thinking_policy(
        bundle("route_prove", 1),
        effective_max_output_tokens=96000,
        repair=False,
    ) == (True, "max")
    assert runner._thinking_policy(
        bundle("route_prove", 2),
        effective_max_output_tokens=128000,
        repair=False,
    ) == (True, "max")
    assert runner._thinking_policy(
        bundle("route_prove", 2),
        effective_max_output_tokens=8192,
        repair=True,
    ) == (False, None)


@pytest.mark.asyncio
async def test_exact_provider_length_failure_carries_64k_recovery_budget(
    tmp_path: Path,
) -> None:
    config = build_demo_config(str(tmp_path / "runs"))
    config.runtime.request_retries = 0
    pool = AgentPool(config)
    agent = pool.get("explorer-a")
    agent.config.max_output_tokens = 128000
    agent.config.provider_max_output_tokens = 384000
    agent.client = _LengthWithoutArtifact("reasoning-only")
    runner = StructuredAgentRunner(
        config,
        pool,
        ArtifactStore(tmp_path / "runs", "artifact-recovery-budget"),
    )

    with pytest.raises(ReasoningBudgetExhaustedError) as captured:
        await runner.call(
            "explorer",
            PromptBundle(
                stage="route_prove",
                system="Return JSON.",
                user="Use only the verified checkpoint.",
                response_model=_TinyResult,
                max_output_tokens=64000,
                output_tier=0,
            ),
            fixed_agent=agent,
        )

    assert captured.value.usage.output_tokens == 64000
    assert captured.value.progress["artifact_recovery_tokens"] == 8000
    assert agent.output_tokens == 64000


@pytest.mark.asyncio
async def test_continuous_reasoning_is_not_stopped_by_elapsed_time(
    tmp_path: Path,
) -> None:
    config = build_demo_config(str(tmp_path / "runs"))
    config.runtime.activity_heartbeat_seconds = 0.01
    pool = AgentPool(config)
    agent = pool.get("explorer-a")
    agent.client = _DelayedArtifactStream(
        "active-reasoning",
        {
            "streaming": True,
            "chunks": 20,
            # Character-derived estimates are intentionally not authoritative.
            "approx_output_tokens": 120000,
            "reasoning_characters": 480000,
            "content_characters": 0,
            "last_data_age_seconds": 0.0,
        },
    )
    runner = StructuredAgentRunner(
        config,
        pool,
        ArtifactStore(tmp_path / "runs", "continuous-reasoning"),
    )
    tier = config.deep_exploration_policy.tiers[0]
    # Old configuration files may still carry this field, but it must not stop
    # a live stream. A pre-change runner would abort this test before 0.05s.
    object.__setattr__(tier, "no_content_timeout_seconds", 0.01)

    response = await runner._call_with_activity_heartbeat(
        agent,
        [{"role": "user", "content": "return a bounded JSON artifact"}],
        temperature=0.0,
        max_output_tokens=64000,
        json_mode=True,
        schema_name="TinyResult",
        schema=_TinyResult.model_json_schema(),
        activity_task=None,
        stage="route_prove",
        tier_policy=tier,
    )

    assert response.text == '{"ok": true}'


@pytest.mark.asyncio
async def test_five_minutes_without_sse_data_is_transport_stall(
    tmp_path: Path,
) -> None:
    config = build_demo_config(str(tmp_path / "runs"))
    config.runtime.activity_heartbeat_seconds = 0.01
    pool = AgentPool(config)
    agent = pool.get("explorer-a")
    agent.client = _ReasoningStreamWithoutArtifact(
        "idle-stream",
        1000,
        last_data_age_seconds=301.0,
    )
    runner = StructuredAgentRunner(
        config,
        pool,
        ArtifactStore(tmp_path / "runs", "idle-stream"),
    )

    with pytest.raises(AgentStreamIdleTimeoutError) as captured:
        await runner._call_with_activity_heartbeat(
            agent,
            [{"role": "user", "content": "return a bounded JSON artifact"}],
            temperature=0.0,
            max_output_tokens=64000,
            json_mode=True,
            schema_name="TinyResult",
            schema=_TinyResult.model_json_schema(),
            activity_task=None,
            stage="route_prove",
            tier_policy=config.deep_exploration_policy.tiers[0],
        )

    assert captured.value.usage.output_tokens == 1000
    assert "no SSE data for 301s" in str(captured.value)


@pytest.mark.asyncio
async def test_missing_first_sse_chunk_uses_shorter_transport_timeout(
    tmp_path: Path,
) -> None:
    config = build_demo_config(str(tmp_path / "runs"))
    config.runtime.activity_heartbeat_seconds = 0.01
    config.runtime.stream_first_chunk_timeout_seconds = 5.0
    pool = AgentPool(config)
    agent = pool.get("explorer-a")
    agent.client = _ReasoningStreamWithoutArtifact(
        "no-first-chunk",
        0,
        last_data_age_seconds=6.0,
        chunks=0,
    )
    runner = StructuredAgentRunner(
        config,
        pool,
        ArtifactStore(tmp_path / "runs", "first-chunk-timeout"),
    )

    with pytest.raises(AgentStreamIdleTimeoutError, match="no first SSE chunk"):
        await runner._call_with_activity_heartbeat(
            agent,
            [{"role": "user", "content": "return JSON"}],
            temperature=0.0,
            max_output_tokens=12000,
            json_mode=True,
            schema_name="TinyResult",
            schema=_TinyResult.model_json_schema(),
            activity_task=None,
            stage="claim_extraction",
        )


@pytest.mark.asyncio
async def test_distinct_first_chunk_stalls_open_shared_provider_circuit(
    tmp_path: Path,
) -> None:
    config = build_demo_config(str(tmp_path / "runs"))
    config.runtime.activity_heartbeat_seconds = 0.01
    config.runtime.stream_first_chunk_timeout_seconds = 5.0
    config.runtime.provider_circuit_failure_threshold = 2
    pool = AgentPool(config)
    agents = [pool.get("explorer-a"), pool.get("verifier-a")]
    for agent in agents:
        agent.client = _ReasoningStreamWithoutArtifact(
            f"idle-{agent.id}",
            0,
            last_data_age_seconds=6.0,
            chunks=0,
        )
    runner = StructuredAgentRunner(
        config,
        pool,
        ArtifactStore(tmp_path / "runs", "first-chunk-circuit"),
    )

    async def one(agent) -> LLMResponse:  # type: ignore[no-untyped-def]
        return await runner._call_with_activity_heartbeat(
            agent,
            [{"role": "user", "content": "return JSON"}],
            temperature=0.0,
            max_output_tokens=12000,
            json_mode=True,
            schema_name="TinyResult",
            schema=_TinyResult.model_json_schema(),
            activity_task=None,
            stage="claim_extraction",
        )

    results = await asyncio.gather(
        *(one(agent) for agent in agents),
        return_exceptions=True,
    )

    assert any(isinstance(item, AgentStreamIdleTimeoutError) for item in results)
    assert any(isinstance(item, ProviderCircuitOpenError) for item in results)


@pytest.mark.asyncio
async def test_queued_call_cannot_read_another_calls_stream_progress(
    tmp_path: Path,
) -> None:
    config = build_demo_config(str(tmp_path / "runs"))
    config.runtime.activity_heartbeat_seconds = 0.01
    config.runtime.request_retries = 0
    pool = AgentPool(config)
    agent = pool.get("explorer-a")
    agent.config.max_concurrency = 1
    agent.semaphore = asyncio.Semaphore(1)
    agent.client = _RequestScopedProgressClient()
    runner = StructuredAgentRunner(
        config,
        pool,
        ArtifactStore(tmp_path / "runs", "request-progress-isolation"),
    )

    async def one() -> LLMResponse:
        return await runner._call_with_activity_heartbeat(
            agent,
            [{"role": "user", "content": "return JSON"}],
            temperature=0.0,
            max_output_tokens=64000,
            json_mode=True,
            schema_name="TinyResult",
            schema=_TinyResult.model_json_schema(),
            activity_task=None,
            stage="route_prove",
            tier_policy=config.deep_exploration_policy.tiers[0],
        )

    first = asyncio.create_task(one())
    await asyncio.sleep(0)
    second = asyncio.create_task(one())
    results = await asyncio.gather(first, second, return_exceptions=True)

    assert isinstance(results[0], AgentStreamIdleTimeoutError)
    assert isinstance(results[1], LLMResponse)
    assert results[1].text == '{"ok": true}'


@pytest.mark.asyncio
async def test_queued_call_is_labeled_as_queued_not_live_streaming(
    tmp_path: Path,
) -> None:
    config = build_demo_config(str(tmp_path / "runs"))
    config.runtime.activity_heartbeat_seconds = 0.01
    pool = AgentPool(config)
    agent = pool.get("explorer-a")
    agent.config.max_concurrency = 1
    agent.semaphore = asyncio.Semaphore(1)
    client = _BlockingScopedProgressClient()
    agent.client = client
    store = ArtifactStore(tmp_path / "runs", "queued-activity")
    activity = ActivityStream(store, persist=False)
    runner = StructuredAgentRunner(config, pool, store, activity=activity)
    first_task_id = activity.start_task("agent_call", title="first")
    second_task_id = activity.start_task("agent_call", title="second")

    async def one(task_id: str) -> LLMResponse:
        return await runner._call_with_activity_heartbeat(
            agent,
            [{"role": "user", "content": "return JSON"}],
            temperature=0.0,
            max_output_tokens=12000,
            json_mode=True,
            schema_name="TinyResult",
            schema=_TinyResult.model_json_schema(),
            activity_task=task_id,
            stage="claim_extraction",
        )

    first = asyncio.create_task(one(first_task_id))
    await asyncio.sleep(0)
    second = asyncio.create_task(one(second_task_id))
    await asyncio.sleep(0.04)
    queued_events = [
        event for event in activity.events if event.task_id == second_task_id
    ]

    assert queued_events[-1].event_type == "agent_call_queued"
    assert queued_events[-1].metrics["queueing"] is True
    assert queued_events[-1].metrics["last_data_age_seconds"] is None
    assert "0 chunks" not in queued_events[-1].detail

    client.release.set()
    responses = await asyncio.gather(first, second)
    assert all(response.text == '{"ok": true}' for response in responses)


@pytest.mark.asyncio
async def test_optional_batch_cancels_siblings_when_provider_circuit_opens() -> None:
    cancelled = asyncio.Event()
    circuit = ProviderCircuitOpenError(
        "mock:https://provider.invalid",
        ["agent-a", "agent-b"],
        retry_after_seconds=60.0,
    )

    async def slow() -> None:
        try:
            await asyncio.Event().wait()
        finally:
            cancelled.set()

    async def fail() -> None:
        await asyncio.sleep(0)
        raise circuit

    (
        results,
        observed,
    ) = await ProofMeshOrchestrator._gather_optional_batch_until_provider_circuit(
        [slow(), fail()]
    )

    assert observed is circuit
    assert cancelled.is_set()
    assert results == [circuit, circuit]


@pytest.mark.asyncio
async def test_started_artifact_is_not_subject_to_no_content_cutoff(
    tmp_path: Path,
) -> None:
    config = build_demo_config(str(tmp_path / "runs"))
    config.runtime.activity_heartbeat_seconds = 0.01
    pool = AgentPool(config)
    agent = pool.get("explorer-a")
    agent.client = _DelayedArtifactStream(
        "artifact-started",
        {
            "streaming": True,
            "chunks": 20,
            "approx_output_tokens": 24000,
            "reasoning_characters": 90000,
            "content_characters": 1,
            "last_data_age_seconds": 0.0,
        },
    )
    runner = StructuredAgentRunner(
        config,
        pool,
        ArtifactStore(tmp_path / "runs", "artifact-started"),
    )

    response = await runner._call_with_activity_heartbeat(
        agent,
        [{"role": "user", "content": "finish the JSON artifact"}],
        temperature=0.0,
        max_output_tokens=64000,
        json_mode=True,
        schema_name="TinyResult",
        schema=_TinyResult.model_json_schema(),
        activity_task=None,
        stage="route_prove",
        tier_policy=config.deep_exploration_policy.tiers[0],
    )

    assert response.text == '{"ok": true}'


@pytest.mark.asyncio
async def test_no_artifact_failure_does_not_repeat_the_full_explorer_call(
    tmp_path: Path,
) -> None:
    config = build_demo_config(str(tmp_path / "runs"))
    config.runtime.request_retries = 0
    for item in config.agents:
        item.max_output_tokens = 128000
        item.provider_max_output_tokens = 384000
    verifier = next(item for item in config.agents if item.id == "verifier-a")
    verifier.roles.append("explorer")
    pool = AgentPool(config)
    clients = {agent.id: _LengthWithoutArtifact(agent.id) for agent in pool.agents}
    for agent in pool.agents:
        agent.client = clients[agent.id]
    runner = StructuredAgentRunner(
        config,
        pool,
        ArtifactStore(tmp_path / "runs", "one-recovery"),
    )

    def bundle_factory(_agent) -> PromptBundle:
        return PromptBundle(
            stage="route_prove",
            system="Return JSON.",
            user="Use only checkpoint-1.",
            response_model=_TinyResult,
            max_output_tokens=96000,
            output_tier=1,
        )

    with pytest.raises(AgentFailoverExhausted) as captured:
        await runner.call_with_failover(
            "explorer",
            bundle_factory,
            primary_agent=pool.get("explorer-a"),
            max_failover_agents=2,
        )

    assert captured.value.tried_agents == ["explorer-a"]
    assert sum(client.calls for client in clients.values()) == 1
    assert captured.value.usage.output_tokens == 96000
    assert captured.value.progress["artifact_recovery_tokens"] == 12000
    reservation_id = captured.value.progress["capacity_reservation_id"]
    assert runner.ledger.capacity_reservations[reservation_id] == {
        "remaining_calls": 1,
        "remaining_tokens": 12000,
    }
    assert clients["explorer-a"].calls == 1
    runner.ledger.release_capacity(reservation_id)


@pytest.mark.asyncio
async def test_deep_call_does_not_start_without_capacity_for_recovery(
    tmp_path: Path,
) -> None:
    config = build_demo_config(str(tmp_path / "runs"))
    config.runtime.request_retries = 0
    config.budget.max_total_calls = 4
    config.budget.max_total_tokens = 100000
    pool = AgentPool(config)
    agent = pool.get("explorer-a")
    agent.config.max_output_tokens = 128000
    agent.config.provider_max_output_tokens = 384000
    client = _LengthWithoutArtifact("must-not-start")
    agent.client = client
    runner = StructuredAgentRunner(
        config,
        pool,
        ArtifactStore(tmp_path / "runs", "recovery-capacity-block"),
    )

    with pytest.raises(BudgetExhaustedError, match="cannot reserve"):
        await runner.call_with_failover(
            "explorer",
            lambda _agent: PromptBundle(
                stage="route_prove",
                system="Return JSON.",
                user="Use the checkpoint.",
                response_model=_TinyResult,
                max_output_tokens=96000,
                output_tier=1,
            ),
            primary_agent=agent,
        )

    assert client.calls == 0
    assert runner.ledger.capacity_reservations == {}
