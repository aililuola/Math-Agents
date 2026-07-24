from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import httpx
import pytest
from pydantic import BaseModel

from mathproofmesh.agents import (
    AgentFailoverExhausted,
    StructuredAgentRunner,
    StructuredOutputError,
)
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


class _StaticPayloadClient(LLMClient):
    def __init__(self, payload: dict[str, Any]) -> None:
        super().__init__("static-payload")
        self.payload = payload
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
            text=json.dumps(self.payload),
            model=self.model,
            provider="mock",
            output_tokens=250,
            raw={"finish_reason": "stop"},
        )


def _continuation_payload(*, claim_step_ref: str) -> dict[str, Any]:
    step = {
        "step_id": "s14",
        "statement": "The bounded prefix establishes the next scoped lemma.",
        "justification": "This follows from the listed committed dependencies.",
        "dependencies": [],
        "is_key_step": True,
        "confidence": 0.9,
    }
    return {
        "action": "complete",
        "delta": {
            "delta_id": "delta-s14",
            "problem_hash": "problem-hash",
            "path_id": "path-1",
            "strategy_id": "strategy-1",
            "parent_checkpoint_id": "checkpoint-1",
            "agent_id": "planner",
            "round_index": 0,
            "segment_index": 1,
            "referenced_checkpoint_step_ids": [],
            "new_steps": [step],
            "new_claims": [
                {
                    "claim_id": "claim-s14",
                    "statement": "The scoped lemma holds.",
                    "conclusion": "The scoped lemma holds.",
                    "proof_steps": [claim_step_ref],
                    "dependencies": ["s14"],
                    "self_confidence": 0.9,
                }
            ],
            "remaining_subgoals": [],
            "candidate_final_answer": None,
            "proof_complete": True,
            "ready_for_verification": False,
            "self_confidence": 0.9,
        },
        "message_receipts": [],
        "reason": "The proof prefix is useful but the final answer was omitted.",
    }


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
async def test_length_empty_response_skips_repair_and_full_semantic_failover(
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
    primary.client = exhausted
    backup_client = _RecoveryClient()
    backup.client = backup_client
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
            output_tier=1,
        )

    with pytest.raises(AgentFailoverExhausted) as captured:
        await runner.call_with_failover(
            "inspiration_referee",
            bundle_factory,
            primary_agent=primary,
            max_failover_agents=1,
        )

    assert captured.value.tried_agents == ["planner"]
    assert captured.value.progress["artifact_recovery_tokens"] == 12000
    assert exhausted.calls == 1
    assert exhausted.max_output_tokens == 96_000
    assert backup_client.messages == []
    assert backup_client.max_output_tokens == 0
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
        output_tier=1,
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
async def test_route_delta_is_normalized_locally_without_another_deep_call(
    tmp_path: Path,
) -> None:
    config = build_demo_config(str(tmp_path / "runs"))
    config.runtime.parse_retries = 1
    pool = AgentPool(config)
    agent = pool.get("planner")
    client = _StaticPayloadClient(_continuation_payload(claim_step_ref="s14"))
    agent.client = client
    runner = StructuredAgentRunner(
        config,
        pool,
        ArtifactStore(tmp_path / "runs", "local-delta-normalization"),
    )
    bundle = PromptBundle(
        stage="route_prove",
        system="Return JSON.",
        user="Use only the supplied checkpoint.",
        response_model=ContinuationTurn,
    )

    result = await runner.call(
        "planner",
        bundle,
        fixed_agent=agent,
        budget_bucket="depth",
    )

    turn = result.value
    assert client.calls == 1
    assert turn.action.value == "submit_delta"
    assert turn.delta is not None
    assert turn.delta.proof_complete is False
    assert turn.delta.candidate_final_answer is None
    assert turn.delta.ready_for_verification is True
    assert turn.delta.completed_subgoal == turn.delta.new_steps[-1].statement
    assert turn.delta.remaining_subgoals
    assert turn.delta.new_claims[0].proof_steps[0].step_id == "s14"
    assert turn.delta.normalization_notes == [
        "missing_final_answer_downgraded_to_partial"
    ]


@pytest.mark.asyncio
async def test_unknown_claim_step_id_is_not_guessed_by_local_normalization(
    tmp_path: Path,
) -> None:
    config = build_demo_config(str(tmp_path / "runs"))
    config.runtime.parse_retries = 0
    pool = AgentPool(config)
    agent = pool.get("planner")
    client = _StaticPayloadClient(_continuation_payload(claim_step_ref="unknown-step"))
    agent.client = client
    runner = StructuredAgentRunner(
        config,
        pool,
        ArtifactStore(tmp_path / "runs", "unknown-step-reference"),
    )
    bundle = PromptBundle(
        stage="route_prove",
        system="Return JSON.",
        user="Use only the supplied checkpoint.",
        response_model=ContinuationTurn,
    )

    with pytest.raises(StructuredOutputError):
        await runner.call(
            "planner",
            bundle,
            fixed_agent=agent,
            budget_bucket="depth",
        )

    assert client.calls == 1


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
