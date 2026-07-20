from __future__ import annotations

import io
import json
from pathlib import Path

from rich.console import Console

from mathproofmesh.activity import (
    ActivityImportance,
    ActivityStatus,
    ActivityStream,
    ConsoleActivityView,
)
from mathproofmesh.store import ArtifactStore


def test_activity_stream_redacts_and_persists(tmp_path: Path) -> None:
    store = ArtifactStore(tmp_path / "runs", "activity-test")
    received = []
    stream = ActivityStream(
        store, language="zh-CN", listener=received.append, persist=True
    )
    leaked = "sk-" + "abcdefghijklmnopqrstuvwxyz123456"

    task_id = stream.start_task(
        "stage",
        title="开始验证",
        detail=f"credential={leaked}",
        stage="verification",
        importance=ActivityImportance.MAJOR,
        metrics={"credential": leaked},
    )
    stream.update_task(
        task_id,
        title="正在验证",
        detail=f"api_key: {leaked}",
        status=ActivityStatus.RUNNING,
        stage="verification",
        metrics={
            "nested": {"authorization": f"Bearer {leaked}"},
            "path": tmp_path,
        },
    )
    stream.complete_task(
        task_id,
        title="验证完成",
        detail="结论 pass",
        stage="verification",
    )
    json_ref, markdown_ref = stream.finalize()

    assert json_ref == "artifact://reports/activity_timeline.json"
    assert markdown_ref == "artifact://reports/activity_timeline.md"
    assert len(received) == 3
    assert {event.task_id for event in received} == {task_id}

    raw_jsonl = (store.root / "activity.jsonl").read_text(encoding="utf-8")
    timeline_json = (store.root / "reports" / "activity_timeline.json").read_text(
        encoding="utf-8"
    )
    timeline_md = (store.root / "reports" / "activity_timeline.md").read_text(
        encoding="utf-8"
    )
    combined = raw_jsonl + timeline_json + timeline_md
    assert leaked not in combined
    assert "[REDACTED]" in combined
    assert "不包含任何模型的原始私有思考链" in timeline_md

    payload = json.loads(timeline_json)
    assert [event["status"] for event in payload] == ["running", "running", "completed"]
    assert payload[1]["metrics"]["path"] == str(tmp_path)


def test_compact_console_view_prints_progress_without_ansi(tmp_path: Path) -> None:
    store = ArtifactStore(tmp_path / "runs", "console-test")
    stream = ActivityStream(store, persist=False)
    output = io.StringIO()
    view = ConsoleActivityView(
        language="zh-CN",
        mode="compact",
        console=Console(file=output, force_terminal=False, color_system=None),
    )
    stream.listener = view.handle

    with view:
        task_id = stream.start_task(
            "stage",
            title="并行探索不同证明方向",
            importance=ActivityImportance.MAJOR,
        )
        stream.complete_task(
            task_id,
            title="首轮并行探索完成",
            importance=ActivityImportance.MAJOR,
        )

    rendered = output.getvalue()
    assert "并行探索不同证明方向" in rendered
    assert "首轮并行探索完成" in rendered


def test_long_agent_call_emits_content_free_heartbeat(tmp_path: Path) -> None:
    import asyncio

    from mathproofmesh.agents import StructuredAgentRunner
    from mathproofmesh.llm.base import LLMResponse
    from mathproofmesh.llm.pool import AgentPool
    from mathproofmesh.mock_demo import build_demo_config

    class SlowAgent:
        id = "slow-agent"

        async def call(self, messages, **kwargs):  # type: ignore[no-untyped-def]
            await asyncio.sleep(0.035)
            return LLMResponse(text="{}", model="mock", provider="mock")

    async def exercise() -> list[str]:
        config = build_demo_config(str(tmp_path / "runs"))
        config.runtime.activity_heartbeat_seconds = 0.01
        store = ArtifactStore(tmp_path / "runs", "heartbeat-test")
        stream = ActivityStream(store, persist=False)
        pool = AgentPool(config)
        runner = StructuredAgentRunner(config, pool, store, activity=stream)
        task_id = stream.start_task("agent_call", title="慢调用")
        try:
            await runner._call_with_activity_heartbeat(
                SlowAgent(),  # type: ignore[arg-type]
                [{"role": "user", "content": "secret prompt must not be logged"}],
                temperature=0.0,
                max_output_tokens=32,
                json_mode=True,
                schema_name="Probe",
                schema={"type": "object"},
                activity_task=task_id,
                stage="independent_exploration",
            )
        finally:
            await pool.aclose()
        return [event.event_type for event in stream.events]

    event_types = asyncio.run(exercise())
    assert "agent_call_heartbeat" in event_types


def test_activity_stream_continues_existing_timeline_after_restart(
    tmp_path: Path,
) -> None:
    store = ArtifactStore(tmp_path / "runs", "activity-resume")
    first = ActivityStream(store, persist=True)
    first.info("before_restart", title="已提交检查点")
    first.finalize()

    resumed = ActivityStream(store, persist=True)
    event = resumed.info("after_restart", title="从检查点继续")
    resumed.finalize()

    assert event.sequence == 2
    assert event.elapsed_ms >= first.events[-1].elapsed_ms
    payload = json.loads(
        (store.root / "reports" / "activity_timeline.json").read_text(encoding="utf-8")
    )
    assert [item["event_type"] for item in payload] == [
        "before_restart",
        "after_restart",
    ]
    assert [item["sequence"] for item in payload] == [1, 2]
