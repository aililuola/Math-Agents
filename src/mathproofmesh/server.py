from __future__ import annotations

import asyncio
import hmac
import json
import os
from pathlib import Path
from typing import Any, AsyncIterator

from pydantic import BaseModel, Field

from .activity import ActivityEvent
from .config import SystemConfig, load_config
from .orchestrator import ProofMeshOrchestrator


class SolveRequest(BaseModel):
    problem: str = Field(min_length=1)
    run_id: str | None = None


class ResumeRequest(BaseModel):
    run_id: str = Field(min_length=1)


def _sse_message(event: str, data: dict[str, Any]) -> str:
    return f"event: {event}\ndata: {json.dumps(data, ensure_ascii=False, separators=(',', ':'))}\n\n"


def create_app(config_path: str | None = None):
    try:
        from fastapi import FastAPI, Header, HTTPException
        from fastapi.responses import StreamingResponse
    except ImportError as exc:  # pragma: no cover - optional dependency
        raise RuntimeError(
            "Install the server extra: pip install 'mathproofmesh[server]'"
        ) from exc

    resolved = config_path or os.getenv("MATHPROOFMESH_CONFIG")
    if not resolved:
        raise RuntimeError(
            "Set MATHPROOFMESH_CONFIG or pass config_path to create_app()."
        )
    config: SystemConfig = load_config(Path(resolved))
    max_runs = max(1, int(os.getenv("MATHPROOFMESH_MAX_CONCURRENT_RUNS", "1")))
    run_semaphore = asyncio.Semaphore(max_runs)
    server_token = os.getenv("MATHPROOFMESH_SERVER_TOKEN")

    app = FastAPI(title="MathProofMesh", version="0.6.0")

    def authorize(authorization: str | None) -> None:
        if not server_token:
            return
        expected = f"Bearer {server_token}"
        if authorization is None or not hmac.compare_digest(authorization, expected):
            raise HTTPException(status_code=401, detail="invalid bearer token")

    @app.get("/health")
    async def health() -> dict[str, object]:
        return {
            "ok": True,
            "system": config.system_name,
            "enabled_agents": len([a for a in config.agents if a.enabled]),
            "activity_stream": "/solve/stream",
            "resume_endpoint": "/resume",
            "resume_stream": "/resume/stream",
            "checkpoint_resume_enabled": config.continuation.process_resume_enabled,
        }

    @app.post("/solve")
    async def solve(
        request: SolveRequest,
        authorization: str | None = Header(default=None),
    ) -> dict[str, object]:
        authorize(authorization)
        try:
            async with run_semaphore:
                result = await ProofMeshOrchestrator(config).solve(
                    request.problem,
                    run_id=request.run_id,
                )
            return result.model_dump(mode="json")
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc

    @app.post("/resume")
    async def resume_run(
        request: ResumeRequest,
        authorization: str | None = Header(default=None),
    ) -> dict[str, object]:
        authorize(authorization)
        try:
            async with run_semaphore:
                result = await ProofMeshOrchestrator(config).resume(request.run_id)
            return result.model_dump(mode="json")
        except (ValueError, FileNotFoundError, RuntimeError) as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc

    @app.post("/solve/stream")
    async def solve_stream(
        request: SolveRequest,
        authorization: str | None = Header(default=None),
    ):
        """Stream concise progress events with Server-Sent Events, then emit the RunResult."""
        authorize(authorization)
        queue: asyncio.Queue[tuple[str, dict[str, Any]] | None] = asyncio.Queue()
        loop = asyncio.get_running_loop()

        def activity_listener(event: ActivityEvent) -> None:
            payload = event.model_dump(mode="json")
            # The callback is synchronous and may later be invoked from another thread.
            loop.call_soon_threadsafe(queue.put_nowait, ("activity", payload))

        async def run_job() -> None:
            try:
                async with run_semaphore:
                    result = await ProofMeshOrchestrator(
                        config,
                        activity_listener=activity_listener,
                    ).solve(request.problem, run_id=request.run_id)
                await queue.put(("result", result.model_dump(mode="json")))
            except asyncio.CancelledError:
                raise
            except Exception as exc:  # Defensive guard around service-level failures.
                await queue.put(
                    (
                        "error",
                        {
                            "error_type": type(exc).__name__,
                            "message": str(exc)[:400],
                        },
                    )
                )
            finally:
                await queue.put(None)

        worker = asyncio.create_task(run_job())

        async def event_source() -> AsyncIterator[str]:
            try:
                yield _sse_message(
                    "connected",
                    {
                        "ok": True,
                        "message": "Activity stream connected; private model reasoning is not exposed.",
                    },
                )
                while True:
                    item = await queue.get()
                    if item is None:
                        break
                    event_name, payload = item
                    yield _sse_message(event_name, payload)
            finally:
                if not worker.done():
                    worker.cancel()
                await asyncio.gather(worker, return_exceptions=True)

        return StreamingResponse(
            event_source(),
            media_type="text/event-stream",
            headers={
                "Cache-Control": "no-cache",
                "X-Accel-Buffering": "no",
                "Connection": "keep-alive",
            },
        )

    @app.post("/resume/stream")
    async def resume_stream(
        request: ResumeRequest,
        authorization: str | None = Header(default=None),
    ):
        """Stream activity while resuming from the latest committed proof state."""
        authorize(authorization)
        queue: asyncio.Queue[tuple[str, dict[str, Any]] | None] = asyncio.Queue()
        loop = asyncio.get_running_loop()

        def activity_listener(event: ActivityEvent) -> None:
            loop.call_soon_threadsafe(
                queue.put_nowait, ("activity", event.model_dump(mode="json"))
            )

        async def run_job() -> None:
            try:
                async with run_semaphore:
                    result = await ProofMeshOrchestrator(
                        config, activity_listener=activity_listener
                    ).resume(request.run_id)
                await queue.put(("result", result.model_dump(mode="json")))
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                await queue.put(
                    (
                        "error",
                        {
                            "error_type": type(exc).__name__,
                            "message": str(exc)[:400],
                        },
                    )
                )
            finally:
                await queue.put(None)

        worker = asyncio.create_task(run_job())

        async def event_source() -> AsyncIterator[str]:
            try:
                yield _sse_message(
                    "connected",
                    {
                        "ok": True,
                        "message": (
                            "Resume activity stream connected; continuation uses only "
                            "persisted verified proof state, not private model reasoning."
                        ),
                    },
                )
                while True:
                    item = await queue.get()
                    if item is None:
                        break
                    event_name, payload = item
                    yield _sse_message(event_name, payload)
            finally:
                if not worker.done():
                    worker.cancel()
                await asyncio.gather(worker, return_exceptions=True)

        return StreamingResponse(
            event_source(),
            media_type="text/event-stream",
            headers={
                "Cache-Control": "no-cache",
                "X-Accel-Buffering": "no",
                "Connection": "keep-alive",
            },
        )

    return app
