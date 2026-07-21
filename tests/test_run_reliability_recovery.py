from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import httpx
import pytest
from pydantic import BaseModel

from mathproofmesh.agents import StructuredAgentRunner
from mathproofmesh.llm.base import LLMClient, LLMResponse, Message
from mathproofmesh.llm.pool import AgentCallFailure, AgentPool
from mathproofmesh.mock_demo import build_demo_config
from mathproofmesh.orchestrator import ProofMeshOrchestrator
from mathproofmesh.prompts import PromptBundle, _validated_model_example
from mathproofmesh.schemas import (
    ContinuationTurn,
    ExecutionStatus,
    MathStatus,
    RunStatus,
)
from mathproofmesh.store import ArtifactStore


class _TinyResult(BaseModel):
    ok: bool


class _ConnectFailureClient(LLMClient):
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
        raise httpx.ConnectError("shared provider is offline")


class _LengthWithoutArtifactClient(LLMClient):
    def __init__(self) -> None:
        super().__init__("length-only")
        self.calls = 0
        self.max_output_tokens = 0

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
        self.max_output_tokens = max_output_tokens
        return LLMResponse(
            text="",
            model=self.model,
            provider="mock",
            output_tokens=max_output_tokens - 1,
            raw={"finish_reason": "length"},
        )


class _RecoveryClient(LLMClient):
    def __init__(self) -> None:
        super().__init__("recovery")
        self.messages: list[Message] = []
        self.max_output_tokens = 0

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
        self.messages = messages
        self.max_output_tokens = max_output_tokens
        return LLMResponse(
            text=json.dumps({"ok": True}),
            model=self.model,
            provider="mock",
            output_tokens=4,
            raw={"finish_reason": "stop"},
        )


class _MalformedThenRepairClient(LLMClient):
    def __init__(self) -> None:
        super().__init__("repair")
        self.max_output_tokens: list[int] = []
        self.messages: list[list[Message]] = []

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
        self.messages.append(messages)
        self.max_output_tokens.append(max_output_tokens)
        if len(self.messages) == 1:
            text = json.dumps({"action": "complete", "reason": "missing delta"})
        else:
            assert schema is not None
            text = json.dumps(
                _validated_model_example(ContinuationTurn, schema),
                ensure_ascii=False,
            )
        return LLMResponse(
            text=text,
            model=self.model,
            provider="mock",
            output_tokens=100,
            raw={"finish_reason": "stop"},
        )


def test_all_model_authored_cryptographic_hashes_are_discarded() -> None:
    payload = {
        "problem_hash": "authoritative-problem-hash",
        "content_hash": "invented",
        "request_hash": "invented",
        "nested": {
            "normalized_hash": "invented",
            "code_hash": "invented",
            "result_hash": "invented",
            "semantic_hash": "invented",
        },
    }

    StructuredAgentRunner._strip_server_owned_hashes(payload)

    assert payload["problem_hash"] == "authoritative-problem-hash"
    assert payload["content_hash"] == ""
    assert payload["request_hash"] == ""
    assert set(payload["nested"].values()) == {""}


@pytest.mark.asyncio
async def test_transport_failure_does_not_lower_agent_trust(tmp_path: Path) -> None:
    config = build_demo_config(str(tmp_path / "runs"))
    pool = AgentPool(config)
    agent = pool.get("explorer-a")
    agent.client = _ConnectFailureClient("offline")
    before = agent.trust_score

    with pytest.raises(AgentCallFailure):
        await agent.call(
            [{"role": "user", "content": "test"}],
            max_output_tokens=512,
        )

    metric = agent.metric()
    assert metric.trust_score == before
    assert metric.failures == 0
    assert metric.failed_attempts == 1
    assert metric.failure_categories == {"transport": 1}


@pytest.mark.asyncio
async def test_length_empty_response_skips_repair_and_restarts_from_checkpoint(
    tmp_path: Path,
) -> None:
    config = build_demo_config(str(tmp_path / "runs"))
    for agent_config in config.agents:
        agent_config.max_output_tokens = 96_000
        agent_config.provider_max_output_tokens = 384_000
    pool = AgentPool(config)
    primary = pool.get("planner")
    backup = pool.get("verifier-b")
    exhausted = _LengthWithoutArtifactClient()
    recovered = _RecoveryClient()
    primary.client = exhausted
    backup.client = recovered
    runner = StructuredAgentRunner(
        config,
        pool,
        ArtifactStore(tmp_path / "runs", "length-recovery"),
    )

    def bundle_factory(_agent) -> PromptBundle:
        return PromptBundle(
            stage="route_prove",
            system="Return JSON.",
            user="VERIFIED CHECKPOINT: checkpoint-1; CURRENT OBLIGATION: obligation-1",
            response_model=_TinyResult,
            output_tier=2,
        )

    result, tried = await runner.call_with_failover(
        "inspiration_referee",
        bundle_factory,
        primary_agent=primary,
        max_failover_agents=1,
    )

    assert result.value.ok is True
    assert tried == ["planner", "verifier-b"]
    assert exhausted.calls == 1
    assert exhausted.max_output_tokens == 96_000
    assert recovered.max_output_tokens == 32_000
    assert (
        "Do not continue or reconstruct its private reasoning"
        in recovered.messages[1]["content"]
    )
    assert primary.metric().failure_categories["reasoning_budget_exhausted"] == 1


@pytest.mark.asyncio
async def test_schema_repair_uses_a_small_budget_and_a_valid_cross_field_example(
    tmp_path: Path,
) -> None:
    config = build_demo_config(str(tmp_path / "runs"))
    config.runtime.parse_retries = 1
    config.runtime.json_repair_max_output_tokens = 8192
    for agent_config in config.agents:
        agent_config.max_output_tokens = 96_000
        agent_config.provider_max_output_tokens = 384_000
    pool = AgentPool(config)
    agent = pool.get("planner")
    client = _MalformedThenRepairClient()
    agent.client = client
    runner = StructuredAgentRunner(
        config,
        pool,
        ArtifactStore(tmp_path / "runs", "schema-repair"),
    )
    bundle = PromptBundle(
        stage="route_prove",
        system="Return JSON.",
        user="Use only the supplied checkpoint.",
        response_model=ContinuationTurn,
        output_tier=2,
    )

    result = await runner.call(
        "planner",
        bundle,
        fixed_agent=agent,
        budget_bucket="depth",
    )

    assert result.value.action.value == "submit_delta"
    assert client.max_output_tokens == [96_000, 8192]
    repair_prompt = client.messages[1][1]["content"]
    assert "MINIMAL JSON SHAPE EXAMPLE" in repair_prompt
    assert '"new_steps"' in repair_prompt


@pytest.mark.asyncio
async def test_shared_provider_failure_pauses_run_without_a_false_math_result(
    tmp_path: Path,
) -> None:
    config = build_demo_config(str(tmp_path / "runs"))

    def disconnected(schema_name, messages, schema):
        raise httpx.ConnectError("provider connection unavailable")

    responders = {agent.id: disconnected for agent in config.agents}
    result = await ProofMeshOrchestrator(
        config,
        mock_responders=responders,
    ).solve("Prove that 1 + 1 = 2.", run_id="provider-interrupted")

    assert result.status == RunStatus.PAUSED_EXTERNAL_FAILURE
    assert result.math_status == MathStatus.INCONCLUSIVE
    assert result.execution_status == ExecutionStatus.NETWORK_INTERRUPTED
    assert result.final_proof is None
    assert result.research_progress_report is not None
    assert any("\u4e00" <= char <= "\u9fff" for char in result.summary)
    assert all(metric.failures == 0 for metric in result.agent_metrics)
    root = Path(result.run_directory)
    assert (root / "reports" / "run_report.md").exists()
    assert (root / "checkpoints" / "paused_external_failure_latest.json").exists()
