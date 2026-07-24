from __future__ import annotations

import json
from pathlib import Path

import httpx
import pytest

from mathproofmesh.llm.deepseek import DeepSeekClient
from mathproofmesh.reasoning_trace import (
    ReasoningTraceBinding,
    ReasoningTraceStore,
    bind_reasoning_trace,
    build_reasoning_snapshot,
    read_reasoning_records,
)


def test_reasoning_trace_is_single_append_only_archive_with_secret_redaction(
    tmp_path: Path,
) -> None:
    secret = "secret-token-123456"
    store = ReasoningTraceStore(tmp_path, "run-1", secrets=[secret])
    first = store.begin_call(
        task_id="task-a",
        agent_id="agent-a",
        stage="route_prove",
        thinking_enabled=True,
        reasoning_effort="max",
    )
    second = store.begin_call(
        task_id="task-b",
        agent_id="agent-b",
        stage="route_skeptic",
        thinking_enabled=True,
        reasoning_effort="high",
    )

    first.append("A before secret-token-")
    second.append("B reasoning")
    first.append("123456 after")
    second.finish("completed")
    first.finish("completed")

    archive = tmp_path / "reports" / "reasoning_traces.txt"
    persisted = archive.read_text(encoding="utf-8")
    assert secret not in persisted
    assert "[REDACTED]" in persisted
    assert [path.name for path in archive.parent.iterdir()] == ["reasoning_traces.txt"]

    records_a, cursor = read_reasoning_records(archive, task_id="task-a")
    snapshot_a = build_reasoning_snapshot(records_a)
    assert cursor == archive.stat().st_size
    assert snapshot_a["calls"][0]["text"] == "A before [REDACTED] after"
    assert snapshot_a["calls"][0]["status"] == "completed"
    assert snapshot_a["calls"][0]["redacted"] is True

    records_b, _ = read_reasoning_records(archive, task_id="task-b")
    assert build_reasoning_snapshot(records_b)["calls"][0]["text"] == "B reasoning"

    resumed = ReasoningTraceStore(tmp_path, "run-1")
    resumed_call = resumed.begin_call(
        task_id="task-a",
        agent_id="agent-a",
        stage="json_repair",
        thinking_enabled=False,
        reasoning_effort=None,
    )
    resumed_call.finish("completed")
    records_a, _ = read_reasoning_records(archive, task_id="task-a")
    assert [
        call["call_index"] for call in build_reasoning_snapshot(records_a)["calls"]
    ] == [
        1,
        2,
    ]


def test_reasoning_trace_cursor_exposes_live_deltas_once(tmp_path: Path) -> None:
    store = ReasoningTraceStore(tmp_path, "run-live")
    call = store.begin_call(
        task_id="live-node",
        agent_id="ds-explorer-a",
        stage="route_prove",
        thinking_enabled=True,
        reasoning_effort="max",
    )
    _, cursor = read_reasoning_records(store.path, task_id="live-node")
    call.FLUSH_INTERVAL_SECONDS = 0
    call.append("live delta")

    updates, next_cursor = read_reasoning_records(
        store.path,
        task_id="live-node",
        offset=cursor,
    )
    repeated, repeated_cursor = read_reasoning_records(
        store.path,
        task_id="live-node",
        offset=next_cursor,
    )

    assert [record["type"] for record in updates] == ["delta"]
    assert updates[0]["text"] == "live delta"
    assert repeated == []
    assert repeated_cursor == next_cursor
    call.finish("completed")


@pytest.mark.asyncio
async def test_deepseek_streaming_trace_preserves_reasoning_chunk_order(
    tmp_path: Path,
) -> None:
    def handler(_: httpx.Request) -> httpx.Response:
        events = [
            {
                "id": "trace-stream",
                "model": "deepseek-v4-pro",
                "choices": [{"delta": {"reasoning_content": "first "}}],
            },
            {
                "id": "trace-stream",
                "model": "deepseek-v4-pro",
                "choices": [{"delta": {"reasoning_content": "second"}}],
            },
            {
                "id": "trace-stream",
                "model": "deepseek-v4-pro",
                "choices": [{"finish_reason": "stop", "delta": {"content": "{}"}}],
            },
            {
                "id": "trace-stream",
                "model": "deepseek-v4-pro",
                "choices": [],
                "usage": {"prompt_tokens": 3, "completion_tokens": 4},
            },
        ]
        body = "".join(f"data: {json.dumps(item)}\n\n" for item in events)
        body += "data: [DONE]\n\n"
        return httpx.Response(
            200,
            headers={"content-type": "text/event-stream"},
            content=body.encode(),
        )

    client = DeepSeekClient(api_key="test-secret", streaming=True)
    await client._client.aclose()
    client._client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    store = ReasoningTraceStore(tmp_path, "run-stream", secrets=["test-secret"])
    binding = ReasoningTraceBinding(
        store=store,
        task_id="agent-node",
        agent_id="ds-explorer-a",
        stage="route_prove",
    )
    try:
        with bind_reasoning_trace(binding):
            response = await client.complete(
                [{"role": "user", "content": "Return JSON."}],
                temperature=0.0,
                max_output_tokens=1024,
                json_mode=True,
            )
    finally:
        await client.aclose()

    records, _ = read_reasoning_records(store.path, task_id="agent-node")
    snapshot = build_reasoning_snapshot(records)
    assert response.text == "{}"
    assert snapshot["calls"][0]["text"] == "first second"
    assert snapshot["calls"][0]["status"] == "completed"
    assert snapshot["calls"][0]["thinking_enabled"] is True


@pytest.mark.asyncio
async def test_deepseek_non_streaming_and_failed_stream_trace_lifecycle(
    tmp_path: Path,
) -> None:
    def complete_handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "id": "trace-complete",
                "model": "deepseek-v4-pro",
                "choices": [
                    {
                        "finish_reason": "stop",
                        "message": {
                            "reasoning_content": "complete reasoning",
                            "content": "{}",
                        },
                    }
                ],
                "usage": {"prompt_tokens": 3, "completion_tokens": 4},
            },
        )

    store = ReasoningTraceStore(tmp_path, "run-lifecycle")
    binding = ReasoningTraceBinding(
        store=store,
        task_id="agent-node",
        agent_id="ds-planner",
        stage="strategy",
    )
    client = DeepSeekClient(api_key="test-secret")
    await client._client.aclose()
    client._client = httpx.AsyncClient(transport=httpx.MockTransport(complete_handler))
    try:
        with bind_reasoning_trace(binding):
            await client.complete(
                [{"role": "user", "content": "Return JSON."}],
                temperature=0.0,
                max_output_tokens=1024,
                json_mode=True,
            )
    finally:
        await client.aclose()

    def failed_handler(_: httpx.Request) -> httpx.Response:
        body = (
            'data: {"id":"trace-failed","model":"deepseek-v4-pro",'
            '"choices":[{"delta":{"reasoning_content":"partial reasoning"}}]}\n\n'
        )
        return httpx.Response(
            200,
            headers={"content-type": "text/event-stream"},
            content=body.encode(),
        )

    failed_client = DeepSeekClient(api_key="test-secret", streaming=True)
    await failed_client._client.aclose()
    failed_client._client = httpx.AsyncClient(
        transport=httpx.MockTransport(failed_handler)
    )
    try:
        with bind_reasoning_trace(binding):
            with pytest.raises(RuntimeError, match=r"ended before data: \[DONE\]"):
                await failed_client.complete(
                    [{"role": "user", "content": "Return JSON."}],
                    temperature=0.0,
                    max_output_tokens=1024,
                    json_mode=True,
                )
    finally:
        await failed_client.aclose()

    records, _ = read_reasoning_records(store.path, task_id="agent-node")
    calls = build_reasoning_snapshot(records)["calls"]
    assert calls[0]["text"] == "complete reasoning"
    assert calls[0]["status"] == "completed"
    assert calls[1]["text"] == "partial reasoning"
    assert calls[1]["status"] == "failed"
    assert calls[1]["error_type"] == "RuntimeError"
