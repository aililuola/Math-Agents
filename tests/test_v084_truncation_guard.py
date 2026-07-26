from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest
from pydantic import BaseModel

from mathproofmesh.agents import ReasoningBudgetExhaustedError, StructuredAgentRunner
from mathproofmesh.llm.base import LLMClient, LLMResponse, Message
from mathproofmesh.llm.pool import AgentPool
from mathproofmesh.mock_demo import build_demo_config
from mathproofmesh.prompts import PromptBundle
from mathproofmesh.store import ArtifactStore


class _TinyArtifact(BaseModel):
    answer: str
    confidence: float


class _ScriptedClient(LLMClient):
    """Returns queued responses while counting every transport call."""

    def __init__(self, responses: list[LLMResponse]) -> None:
        super().__init__("scripted")
        self.responses = list(responses)
        self.calls = 0
        self.requests: list[list[Message]] = []

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
        self.requests.append(messages)
        return self.responses.pop(0)


def _response(text: str, finish_reason: str) -> LLMResponse:
    return LLMResponse(
        text=text,
        model="scripted",
        provider="mock",
        input_tokens=10,
        output_tokens=50,
        raw={"finish_reason": finish_reason},
    )


def _runner(tmp_path: Path, client: _ScriptedClient) -> StructuredAgentRunner:
    config = build_demo_config(str(tmp_path / "runs"))
    # The demo profile disables parse retries; the guard under test must be
    # exercised with the JSON repair pass available.
    config.runtime.parse_retries = 1
    pool = AgentPool(config)
    pool.get("planner").client = client
    store = ArtifactStore(tmp_path / "runs", "truncation-guard")
    return StructuredAgentRunner(config, pool, store)


def _bundle(user: str) -> PromptBundle:
    return PromptBundle(
        stage="unit_guard_stage",
        system="Return one JSON object matching the schema.",
        user=user,
        response_model=_TinyArtifact,
        temperature=0.2,
    )


@pytest.mark.asyncio
async def test_truncated_invalid_output_never_reaches_json_repair(
    tmp_path: Path,
) -> None:
    truncated_text = '{"answer": "the proof begins with an unfinished'
    client = _ScriptedClient(
        [
            _response(truncated_text, "length"),
            # Would be consumed only by a (forbidden) repair call.
            _response(json.dumps({"answer": "fabricated", "confidence": 0.9}), "stop"),
        ]
    )
    runner = _runner(tmp_path, client)
    agent = runner.pool.get("planner")

    with pytest.raises(ReasoningBudgetExhaustedError) as excinfo:
        await runner.call(
            "planner",
            _bundle("[STAGE:unit_guard_stage]\nProve the lemma."),
            fixed_agent=agent,
        )

    assert client.calls == 1, "no JSON repair call may follow a truncated artifact"
    assert excinfo.value.progress["truncated"] is True
    assert excinfo.value.progress["finish_reason"] == "length"
    assert excinfo.value.progress["content_characters"] == len(truncated_text)


@pytest.mark.asyncio
async def test_repair_prompt_carries_original_stage_tag_and_user_context(
    tmp_path: Path,
) -> None:
    original_user = "[STAGE:unit_guard_stage]\n" + (
        "Prove that the sum of the first n odd numbers equals n squared. " * 40
    )
    client = _ScriptedClient(
        [
            _response('{"answer": "confidence field is missing"}', "stop"),
            _response(json.dumps({"answer": "repaired", "confidence": 0.5}), "stop"),
        ]
    )
    runner = _runner(tmp_path, client)
    agent = runner.pool.get("planner")

    result = await runner.call("planner", _bundle(original_user), fixed_agent=agent)

    assert result.value.answer == "repaired"
    assert client.calls == 2
    repair_system = client.requests[1][0]["content"]
    repair_user = client.requests[1][1]["content"]
    assert "[STAGE:unit_guard_stage_json_repair]" in repair_user
    # The immutable context block restores the original stage tag and the first
    # 1200 characters of the original user prompt.
    assert "[STAGE:unit_guard_stage]" in repair_user
    assert original_user[:1200] in repair_user
    assert "truncated" in repair_system
