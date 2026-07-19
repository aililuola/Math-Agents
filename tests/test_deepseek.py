from __future__ import annotations

import json

import httpx
import pytest
from pydantic import ValidationError

from mathproofmesh.config import AgentConfig, SystemConfig
from mathproofmesh.llm.deepseek import DeepSeekClient
from mathproofmesh.llm.pool import AgentPool


def test_deepseek_agent_config_requires_thinking_for_effort() -> None:
    with pytest.raises(ValidationError):
        AgentConfig(
            id="bad",
            provider="deepseek",
            model="deepseek-v4-pro",
            api_key_env="TEST_KEY",
            thinking_enabled=False,
            reasoning_effort="max",
        )


def test_deepseek_agent_config_accepts_v4_pro_max() -> None:
    config = AgentConfig(
        id="agent-1",
        provider="deepseek",
        model="deepseek-v4-pro",
        api_key_env="TEST_KEY",
        thinking_enabled=True,
        reasoning_effort="max",
        streaming=True,
        user_id="mathproofmesh-agent-1",
    )
    assert config.reasoning_effort == "max"
    assert config.thinking_enabled is True
    assert config.streaming is True


@pytest.mark.asyncio
async def test_agent_pool_constructs_deepseek_client(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("TEST_DEEPSEEK_KEY", "test-secret")
    config = SystemConfig(
        agents=[
            AgentConfig(
                id="ds-agent",
                provider="deepseek",
                model="deepseek-v4-pro",
                api_key_env="TEST_DEEPSEEK_KEY",
                thinking_enabled=True,
                reasoning_effort="max",
                streaming=True,
                user_id="isolated-agent",
            )
        ]
    )
    pool = AgentPool(config)
    try:
        client = pool.get("ds-agent").client
        assert isinstance(client, DeepSeekClient)
        assert client.reasoning_effort == "max"
        assert client.thinking_enabled is True
        assert client.streaming is True
        assert client.user_id == "isolated-agent"
    finally:
        await pool.aclose()


@pytest.mark.asyncio
async def test_deepseek_payload_and_reasoning_redaction() -> None:
    captured: dict[str, object] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured.update(json.loads(request.content.decode("utf-8")))
        return httpx.Response(
            200,
            headers={"x-request-id": "req-test"},
            json={
                "id": "chat-test",
                "object": "chat.completion",
                "created": 1,
                "model": "deepseek-v4-pro",
                "choices": [
                    {
                        "index": 0,
                        "finish_reason": "stop",
                        "message": {
                            "role": "assistant",
                            "reasoning_content": "private intermediate reasoning",
                            "content": '{"ok":true}',
                        },
                    }
                ],
                "usage": {"prompt_tokens": 11, "completion_tokens": 13},
            },
        )

    client = DeepSeekClient(
        api_key="test-secret",
        model="deepseek-v4-pro",
        thinking_enabled=True,
        reasoning_effort="max",
        user_id="mathproofmesh-agent-1",
    )
    await client._client.aclose()
    client._client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    try:
        response = await client.complete(
            [{"role": "user", "content": "Return JSON."}],
            temperature=0.9,
            max_output_tokens=4096,
            json_mode=True,
        )
    finally:
        await client.aclose()

    assert captured["model"] == "deepseek-v4-pro"
    assert captured["thinking"] == {"type": "enabled"}
    assert captured["reasoning_effort"] == "max"
    assert captured["user_id"] == "mathproofmesh-agent-1"
    assert captured["response_format"] == {"type": "json_object"}
    assert captured["stream"] is False
    assert "stream_options" not in captured
    assert "temperature" not in captured
    assert response.text == '{"ok":true}'
    assert response.provider == "deepseek"
    assert response.raw["reasoning"]["present"] is True
    assert response.raw["reasoning"]["characters"] > 0
    assert response.raw["streaming"] is False
    assert "reasoning_content" not in json.dumps(response.raw)


@pytest.mark.asyncio
async def test_deepseek_streaming_payload_and_reasoning_redaction() -> None:
    captured: dict[str, object] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured.update(json.loads(request.content.decode("utf-8")))
        events = [
            {
                "id": "chat-stream",
                "object": "chat.completion.chunk",
                "created": 2,
                "model": "deepseek-v4-pro",
                "choices": [
                    {
                        "index": 0,
                        "finish_reason": None,
                        "delta": {
                            "role": "assistant",
                            "reasoning_content": "private ",
                        },
                    }
                ],
                "usage": None,
            },
            {
                "id": "chat-stream",
                "object": "chat.completion.chunk",
                "created": 2,
                "model": "deepseek-v4-pro",
                "choices": [
                    {
                        "index": 0,
                        "finish_reason": None,
                        "delta": {"reasoning_content": [{"text": "reasoning"}]},
                    }
                ],
                "usage": None,
            },
            {
                "id": "chat-stream",
                "object": "chat.completion.chunk",
                "created": 2,
                "model": "deepseek-v4-pro",
                "choices": [
                    {
                        "index": 0,
                        "finish_reason": None,
                        "delta": {"content": '{"ok":'},
                    }
                ],
                "usage": None,
            },
            {
                "id": "chat-stream",
                "object": "chat.completion.chunk",
                "created": 2,
                "model": "deepseek-v4-pro",
                "choices": [
                    {
                        "index": 0,
                        "finish_reason": "stop",
                        "delta": {"content": "true}"},
                    }
                ],
                "usage": None,
            },
            {
                "id": "chat-stream",
                "object": "chat.completion.chunk",
                "created": 2,
                "model": "deepseek-v4-pro",
                "choices": [],
                "usage": {"prompt_tokens": 17, "completion_tokens": 19},
            },
        ]
        body = ": keep-alive\n\n"
        body += "".join(f"data: {json.dumps(event)}\n\n" for event in events)
        body += "data: [DONE]\n\n"
        return httpx.Response(
            200,
            headers={
                "content-type": "text/event-stream",
                "x-request-id": "req-stream",
            },
            content=body.encode("utf-8"),
        )

    client = DeepSeekClient(
        api_key="test-secret",
        model="deepseek-v4-pro",
        thinking_enabled=True,
        reasoning_effort="max",
        streaming=True,
        user_id="mathproofmesh-agent-1",
    )
    await client._client.aclose()
    client._client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    try:
        response = await client.complete(
            [{"role": "user", "content": "Return JSON."}],
            temperature=0.9,
            max_output_tokens=4096,
            json_mode=True,
        )
    finally:
        await client.aclose()

    assert captured["stream"] is True
    assert captured["stream_options"] == {"include_usage": True}
    assert captured["thinking"] == {"type": "enabled"}
    assert captured["reasoning_effort"] == "max"
    assert captured["response_format"] == {"type": "json_object"}
    assert "temperature" not in captured
    assert response.text == '{"ok":true}'
    assert response.input_tokens == 17
    assert response.output_tokens == 19
    assert response.request_id == "req-stream"
    assert response.raw["reasoning"]["characters"] == len("private reasoning")
    assert response.raw["streaming"] is True
    assert response.raw["stream"]["chunks"] == 5
    assert response.raw["stream"]["done_received"] is True
    assert response.raw["stream"]["first_chunk_latency_ms"] is not None
    assert "reasoning_content" not in json.dumps(response.raw)


@pytest.mark.asyncio
async def test_deepseek_streaming_rejects_incomplete_sse() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        body = (
            'data: {"id":"chat-cut","model":"deepseek-v4-pro",'
            '"choices":[{"delta":{"content":"partial"}}]}\n\n'
        )
        return httpx.Response(
            200,
            headers={"content-type": "text/event-stream"},
            content=body.encode("utf-8"),
        )

    client = DeepSeekClient(api_key="test-secret", streaming=True)
    await client._client.aclose()
    client._client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    try:
        with pytest.raises(RuntimeError, match=r"ended before data: \[DONE\]"):
            await client.complete(
                [{"role": "user", "content": "Return JSON."}],
                temperature=0.0,
                max_output_tokens=256,
                json_mode=True,
            )
    finally:
        await client.aclose()


@pytest.mark.asyncio
async def test_deepseek_model_list_probe() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/models"
        return httpx.Response(
            200,
            json={
                "object": "list",
                "data": [
                    {
                        "id": "deepseek-v4-flash",
                        "object": "model",
                        "owned_by": "deepseek",
                    },
                    {
                        "id": "deepseek-v4-pro",
                        "object": "model",
                        "owned_by": "deepseek",
                    },
                ],
            },
        )

    client = DeepSeekClient(api_key="test-secret")
    await client._client.aclose()
    client._client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    try:
        models = await client.list_models()
    finally:
        await client.aclose()
    assert models == ["deepseek-v4-flash", "deepseek-v4-pro"]


@pytest.mark.asyncio
async def test_deepseek_streaming_requires_requested_usage_summary() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        body = (
            'data: {"id":"chat-no-usage","model":"deepseek-v4-pro",'
            '"choices":[{"finish_reason":"stop","delta":{"content":"{}"}}]}\n\n'
            "data: [DONE]\n\n"
        )
        return httpx.Response(
            200,
            headers={"content-type": "text/event-stream"},
            content=body.encode("utf-8"),
        )

    client = DeepSeekClient(api_key="test-secret", streaming=True)
    await client._client.aclose()
    client._client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    try:
        with pytest.raises(RuntimeError, match="final usage summary"):
            await client.complete(
                [{"role": "user", "content": "Return JSON."}],
                temperature=0.0,
                max_output_tokens=256,
                json_mode=True,
            )
    finally:
        await client.aclose()
