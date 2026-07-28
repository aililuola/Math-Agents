from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest

from mathproofmesh.agents import StructuredAgentRunner
from mathproofmesh.llm.base import LLMClient, LLMResponse, Message
from mathproofmesh.llm.pool import AgentPool
from mathproofmesh.mock_demo import build_demo_config
from mathproofmesh.prompts import PromptBundle
from mathproofmesh.store import ArtifactStore
from pydantic import BaseModel


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
        self.json_modes: list[bool] = []

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
        self.json_modes.append(json_mode)
        return self.responses.pop(0)


def _response(text: str, finish_reason: str = "stop") -> LLMResponse:
    return LLMResponse(
        text=text,
        model="scripted",
        provider="mock",
        input_tokens=10,
        output_tokens=50,
        raw={"finish_reason": finish_reason},
    )


def _runner(
    tmp_path: Path, client: _ScriptedClient, *, two_phase: bool
) -> StructuredAgentRunner:
    config = build_demo_config(str(tmp_path / "runs"))
    config.runtime.parse_retries = 1
    config.runtime.two_phase_output = two_phase
    pool = AgentPool(config)
    pool.get("planner").client = client
    store = ArtifactStore(tmp_path / "runs", "two-phase")
    return StructuredAgentRunner(config, pool, store)


_SCHEMA_BLOCK = (
    "JSON SCHEMA:\n"
    '{"type": "object", "properties": {"answer": {"type": "string"}, '
    '"confidence": {"type": "number"}}, "required": ["answer", "confidence"]}\n\n'
    "MINIMAL JSON SHAPE EXAMPLE (replace placeholders):\n"
    '{"answer": "...", "confidence": 0.5}'
)


def _explore_bundle() -> PromptBundle:
    user = (
        "[STAGE:independent_exploration]\n"
        "You are explorer explorer-a, assigned exactly one strategy.\n"
        "Prove that the sum of the first n odd numbers equals n squared.\n"
        "The response must retain the problem_hash exactly as given and set "
        "agent_id='explorer-a', round_index=1.\n\n"
        "REMAINING GLOBAL CALL BUDGET: 9\n"
        "OUTPUT LANGUAGE: zh-CN\n\n"
        f"{_SCHEMA_BLOCK}"
    )
    return PromptBundle(
        stage="independent_exploration",
        system="Return exactly one JSON object conforming to the supplied JSON Schema.",
        user=user,
        response_model=_TinyArtifact,
        temperature=0.3,
    )


def _continuation_bundle() -> PromptBundle:
    user = (
        "[STAGE:proof_continuation]\n"
        "Continue one proof path from a verified external checkpoint.\n\n"
        "AUTHORITATIVE IDS:\n"
        "agent_id='explorer-a'\n"
        "round_index=2\n"
        "segment_index=3\n"
        "parent_checkpoint_id='ckpt-7'\n"
        "REMAINING GLOBAL CALL BUDGET: 5\n"
        "OUTPUT LANGUAGE: zh-CN\n\n"
        f"{_SCHEMA_BLOCK}"
    )
    return PromptBundle(
        stage="proof_continuation",
        system="Return exactly one JSON object conforming to the supplied JSON Schema.",
        user=user,
        response_model=_TinyArtifact,
        temperature=0.2,
    )


_PHASE1_TEXT = "设 n 为正整数。第一步：1+3+...+(2n-1)=n^2，用归纳法证明。证毕。"


@pytest.mark.asyncio
async def test_two_phase_makes_exactly_two_calls_and_splits_prompts(
    tmp_path: Path,
) -> None:
    payload = {"answer": "n^2", "confidence": 0.9}
    client = _ScriptedClient(
        [
            _response(_PHASE1_TEXT),
            _response(json.dumps(payload, ensure_ascii=False)),
        ]
    )
    runner = _runner(tmp_path, client, two_phase=True)
    agent = runner.pool.get("planner")
    bundle = _explore_bundle()

    result = await runner.call(
        "planner", bundle, fixed_agent=agent, budget_bucket="depth"
    )

    assert client.calls == 2

    phase1_system = client.requests[0][0]["content"]
    phase1_user = client.requests[0][1]["content"]
    assert phase1_system == bundle.system
    # Phase 1 keeps every non-schema instruction (stage tag, IDs, language)...
    assert "[STAGE:independent_exploration]" in phase1_user
    assert "OUTPUT LANGUAGE: zh-CN" in phase1_user
    assert "The response must retain the problem_hash" in phase1_user
    # ...but carries no JSON schema block and asks for free natural language.
    assert "JSON SCHEMA" not in phase1_user
    assert "free natural language" in phase1_user
    assert client.json_modes[0] is False

    phase2_system = client.requests[1][0]["content"]
    phase2_user = client.requests[1][1]["content"]
    assert "completed mathematical write-up" in phase2_system
    assert "Do not add, remove, strengthen or weaken any mathematics" in phase2_system
    # Phase 2 carries the original schema block, the bookkeeping IDs, and the
    # complete phase-1 free text.
    assert _SCHEMA_BLOCK in phase2_user
    assert _PHASE1_TEXT in phase2_user
    assert "agent_id='explorer-a', round_index=1" in phase2_user
    assert client.json_modes[1] is True

    # The parsed result equals the fake's structured payload.
    assert result.value.answer == payload["answer"]
    assert result.value.confidence == payload["confidence"]

    # Two ledger entries in the same budget bucket, one per phase.
    assert runner.ledger.calls_started == 2
    assert runner.ledger.stage_calls["independent_exploration"] == 1
    assert runner.ledger.stage_calls["independent_exploration_format"] == 1
    assert runner.ledger.bucket_calls["depth"] == 2
    # Usage aggregates both phases.
    assert result.usage.output_tokens == 100


@pytest.mark.asyncio
async def test_two_phase_preserves_raw_artifacts_for_both_phases(
    tmp_path: Path,
) -> None:
    payload = {"answer": "ok", "confidence": 0.8}
    client = _ScriptedClient(
        [
            _response(_PHASE1_TEXT),
            _response(json.dumps(payload, ensure_ascii=False)),
        ]
    )
    runner = _runner(tmp_path, client, two_phase=True)
    agent = runner.pool.get("planner")

    result = await runner.call("planner", _explore_bundle(), fixed_agent=agent)

    events = [
        json.loads(line)
        for line in runner.store.events_path.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]
    by_type = {event["event_type"] for event in events}
    assert "two_phase_freeform_completed" in by_type
    assert "agent_call_completed" in by_type
    completed = next(
        event["payload"]
        for event in events
        if event["event_type"] == "two_phase_completed"
    )
    assert completed["phase1_raw_ref"]
    assert completed["phase2_raw_ref"] == result.raw_ref
    assert completed["phase1_raw_ref"] != completed["phase2_raw_ref"]


@pytest.mark.asyncio
async def test_two_phase_continuation_carries_authoritative_ids(
    tmp_path: Path,
) -> None:
    payload = {"answer": "delta", "confidence": 0.7}
    client = _ScriptedClient(
        [
            _response(_PHASE1_TEXT),
            _response(json.dumps(payload, ensure_ascii=False)),
        ]
    )
    runner = _runner(tmp_path, client, two_phase=True)
    agent = runner.pool.get("planner")

    result = await runner.call("planner", _continuation_bundle(), fixed_agent=agent)

    assert client.calls == 2
    phase2_user = client.requests[1][1]["content"]
    assert "AUTHORITATIVE IDS:" in phase2_user
    assert "parent_checkpoint_id='ckpt-7'" in phase2_user
    assert _SCHEMA_BLOCK in phase2_user
    assert result.value.answer == "delta"


@pytest.mark.asyncio
async def test_two_phase_format_failure_uses_existing_repair_path(
    tmp_path: Path,
) -> None:
    payload = {"answer": "repaired", "confidence": 0.6}
    client = _ScriptedClient(
        [
            _response(_PHASE1_TEXT),
            _response('{"answer": "missing confidence field"}'),
            _response(json.dumps(payload, ensure_ascii=False)),
        ]
    )
    runner = _runner(tmp_path, client, two_phase=True)
    agent = runner.pool.get("planner")

    result = await runner.call("planner", _explore_bundle(), fixed_agent=agent)

    assert client.calls == 3
    repair_user = client.requests[2][1]["content"]
    assert "[STAGE:independent_exploration_format_json_repair]" in repair_user
    assert result.value.answer == "repaired"


@pytest.mark.asyncio
async def test_flag_off_makes_exactly_one_call_with_unchanged_prompts(
    tmp_path: Path,
) -> None:
    payload = {"answer": "single", "confidence": 0.5}
    client = _ScriptedClient([_response(json.dumps(payload, ensure_ascii=False))])
    runner = _runner(tmp_path, client, two_phase=False)
    agent = runner.pool.get("planner")
    bundle = _explore_bundle()

    result = await runner.call("planner", bundle, fixed_agent=agent)

    assert client.calls == 1
    assert client.requests[0][0]["content"] == bundle.system
    assert client.requests[0][1]["content"] == bundle.user
    assert client.json_modes == [True]
    assert result.value.answer == "single"
    assert runner.ledger.calls_started == 1


@pytest.mark.asyncio
async def test_stage_outside_two_phase_stages_stays_single_phase(
    tmp_path: Path,
) -> None:
    payload = {"answer": "triaged", "confidence": 0.4}
    client = _ScriptedClient([_response(json.dumps(payload, ensure_ascii=False))])
    runner = _runner(tmp_path, client, two_phase=True)
    agent = runner.pool.get("planner")
    user = f"[STAGE:triage]\nClassify the problem.\n\n{_SCHEMA_BLOCK}"
    bundle = PromptBundle(
        stage="triage",
        system="Return exactly one JSON object conforming to the supplied JSON Schema.",
        user=user,
        response_model=_TinyArtifact,
        temperature=0.0,
    )

    result = await runner.call("planner", bundle, fixed_agent=agent)

    assert client.calls == 1
    assert client.requests[0][1]["content"] == bundle.user
    assert result.value.answer == "triaged"
