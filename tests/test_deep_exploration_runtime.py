from __future__ import annotations

import asyncio
from pathlib import Path
from typing import Any

import pytest
from pydantic import BaseModel

from mathproofmesh.agents import (
    AgentFailoverExhausted,
    ReasoningBudgetExhaustedError,
    StructuredAgentRunner,
)
from mathproofmesh.llm.base import LLMClient, LLMResponse, Message
from mathproofmesh.llm.pool import AgentPool
from mathproofmesh.mock_demo import build_demo_config
from mathproofmesh.prompts import PromptBundle
from mathproofmesh.store import ArtifactStore


class _TinyResult(BaseModel):
    ok: bool


class _ReasoningStreamWithoutArtifact(LLMClient):
    def __init__(self, model: str, approx_tokens: int) -> None:
        super().__init__(model)
        self.approx_tokens = approx_tokens

    def progress_snapshot(self) -> dict[str, Any]:
        return {
            "chunks": 10,
            "approx_output_tokens": self.approx_tokens,
            "reasoning_characters": self.approx_tokens * 3,
            "content_characters": 0,
            "last_data_age_seconds": 0.0,
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


@pytest.mark.asyncio
async def test_32k_answer_reserve_aborts_at_24k_and_attributes_usage(
    tmp_path: Path,
) -> None:
    config = build_demo_config(str(tmp_path / "runs"))
    config.runtime.activity_heartbeat_seconds = 0.01
    pool = AgentPool(config)
    agent = pool.get("explorer-a")
    agent.client = _ReasoningStreamWithoutArtifact("reasoning-only", 24000)
    runner = StructuredAgentRunner(
        config,
        pool,
        ArtifactStore(tmp_path / "runs", "answer-reserve"),
    )
    tier = config.deep_exploration_policy.tiers[0]

    with pytest.raises(ReasoningBudgetExhaustedError) as captured:
        await runner._call_with_activity_heartbeat(
            agent,
            [{"role": "user", "content": "return a bounded JSON artifact"}],
            temperature=0.0,
            max_output_tokens=32000,
            json_mode=True,
            schema_name="TinyResult",
            schema=_TinyResult.model_json_schema(),
            activity_task=None,
            stage="route_prove",
            tier_policy=tier,
        )

    assert captured.value.usage.output_tokens == 24000
    assert agent.output_tokens == 24000
    assert "8000 tokens were reserved" in str(captured.value)


@pytest.mark.asyncio
async def test_no_artifact_failure_gets_only_one_bounded_semantic_recovery(
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
            output_tier=2,
        )

    with pytest.raises(AgentFailoverExhausted) as captured:
        await runner.call_with_failover(
            "explorer",
            bundle_factory,
            primary_agent=pool.get("explorer-a"),
            max_failover_agents=2,
        )

    assert len(captured.value.tried_agents) == 2
    assert sum(client.calls for client in clients.values()) == 2
    assert captured.value.usage.output_tokens == 128000
    assert clients["explorer-a"].calls == 1
