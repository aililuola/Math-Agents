from __future__ import annotations

import asyncio
import hmac
import json
import os
import secrets
from contextlib import asynccontextmanager
from typing import Any, AsyncIterator, Literal

from fastapi import Depends, FastAPI, HTTPException, Request
from fastapi.responses import FileResponse, RedirectResponse, StreamingResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, ConfigDict, Field

from ..schemas import GoalClarificationDecision
from .configuration import DesktopConfigService
from .manager import DesktopRunManager
from .paths import DesktopPaths, web_root
from .repository import RunRepository
from .security import CredentialVault
from .settings import DesktopProfile, DesktopSettings, SettingsStore


class _ApiModel(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)


class StartRunRequest(_ApiModel):
    problem: str = Field(min_length=1, max_length=2_000_000)
    profile: DesktopProfile
    run_id: str | None = Field(default=None, max_length=160)


RESUME_MODES = ("normal", "reopen_with_pivot", "reset_stagnation", "replay_stage")


class ResumeRunRequest(_ApiModel):
    profile: DesktopProfile
    resume_mode: str = Field(default="normal", max_length=40)


class ClarificationDecisionRequest(_ApiModel):
    request_id: str = Field(min_length=1, max_length=160)
    canonical_statement: str = Field(min_length=1, max_length=2_000_000)
    selected_candidate_index: int | None = Field(default=None, ge=0)


class CredentialsRequest(_ApiModel):
    values: dict[str, str] = Field(default_factory=dict)
    clear: list[str] = Field(default_factory=list)
    persist: bool = True


class OpenPathRequest(_ApiModel):
    kind: Literal["data", "runs", "logs", "run"]
    run_id: str | None = None


def _sse(event: str, data: dict[str, object]) -> str:
    encoded = json.dumps(data, ensure_ascii=False, separators=(",", ":"))
    return f"event: {event}\ndata: {encoded}\n\n"


def build_desktop_services(
    paths: DesktopPaths,
) -> tuple[
    SettingsStore,
    CredentialVault,
    DesktopConfigService,
    RunRepository,
    DesktopRunManager,
]:
    settings_store = SettingsStore(paths.settings_file)
    credentials = CredentialVault(paths.credentials_file)
    config_service = DesktopConfigService(paths, credentials)
    repository = RunRepository(paths)
    manager = DesktopRunManager(
        paths,
        settings_store,
        credentials,
        config_service,
        repository,
    )
    return settings_store, credentials, config_service, repository, manager


def create_desktop_app(
    *,
    paths: DesktopPaths | None = None,
    token: str | None = None,
) -> FastAPI:
    resolved_paths = paths or DesktopPaths.discover()
    session_token = token or secrets.token_urlsafe(32)
    (
        settings_store,
        credentials,
        config_service,
        repository,
        manager,
    ) = build_desktop_services(resolved_paths)

    @asynccontextmanager
    async def lifespan(_: FastAPI):
        yield
        await manager.shutdown()

    app = FastAPI(
        title="MathProofMesh Desktop",
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
        lifespan=lifespan,
    )
    app.state.desktop_token = session_token
    app.state.desktop_paths = resolved_paths
    app.state.desktop_manager = manager
    app.state.desktop_repository = repository
    app.state.desktop_credentials = credentials
    app.state.desktop_settings_store = settings_store
    app.state.desktop_config_service = config_service

    assets = web_root() / "assets"
    app.mount("/assets", StaticFiles(directory=str(assets)), name="desktop-assets")

    @app.middleware("http")
    async def security_headers(request: Request, call_next):
        response = await call_next(request)
        response.headers["Cache-Control"] = (
            "no-store" if request.url.path.startswith("/api/") else "no-cache"
        )
        response.headers["Content-Security-Policy"] = (
            "default-src 'self'; "
            "img-src 'self' data:; "
            "style-src 'self'; "
            "script-src 'self'; "
            "connect-src 'self'; "
            "frame-ancestors 'none'; "
            "base-uri 'none'; "
            "form-action 'self'"
        )
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["Referrer-Policy"] = "no-referrer"
        return response

    def require_session(request: Request) -> None:
        supplied = request.cookies.get("mpm_desktop_session", "")
        if not hmac.compare_digest(supplied, session_token):
            raise HTTPException(status_code=401, detail="desktop session required")

    @app.get("/")
    async def index(request: Request, token: str | None = None):
        cookie = request.cookies.get("mpm_desktop_session", "")
        if token is not None:
            if not hmac.compare_digest(token, session_token):
                raise HTTPException(status_code=401, detail="invalid desktop token")
            response = RedirectResponse(url="/", status_code=303)
            response.set_cookie(
                "mpm_desktop_session",
                session_token,
                httponly=True,
                samesite="strict",
                secure=False,
            )
            return response
        if not hmac.compare_digest(cookie, session_token):
            raise HTTPException(status_code=401, detail="desktop session required")
        return FileResponse(web_root() / "index.html")

    @app.get("/api/bootstrap", dependencies=[Depends(require_session)])
    async def bootstrap() -> dict[str, object]:
        return manager.bootstrap()

    @app.put("/api/settings", dependencies=[Depends(require_session)])
    async def update_settings(payload: DesktopSettings) -> dict[str, object]:
        settings = manager.update_settings(payload)
        return {
            "settings": settings.model_dump(mode="json"),
            "profiles": config_service.profile_summaries(settings),
        }

    @app.put("/api/credentials", dependencies=[Depends(require_session)])
    async def update_credentials(payload: CredentialsRequest) -> dict[str, object]:
        try:
            for name in payload.clear:
                credentials.clear(name)
            for name, value in payload.values.items():
                if value.strip():
                    credentials.set(name, value, persist=payload.persist)
        except (RuntimeError, ValueError) as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc
        return {"credential_status": credentials.statuses()}

    @app.delete("/api/credentials", dependencies=[Depends(require_session)])
    async def clear_credentials() -> dict[str, object]:
        credentials.clear_all()
        return {"credential_status": credentials.statuses()}

    @app.post("/api/probe", dependencies=[Depends(require_session)])
    async def probe_credentials() -> dict[str, object]:
        try:
            results = await manager.probe_credentials()
        except (RuntimeError, ValueError) as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc
        return {"results": results}

    @app.get("/api/runs", dependencies=[Depends(require_session)])
    async def list_runs() -> dict[str, object]:
        return {"runs": repository.list_runs()}

    @app.post("/api/runs", dependencies=[Depends(require_session)])
    async def start_run(payload: StartRunRequest) -> dict[str, object]:
        try:
            session = await manager.start(
                problem=payload.problem,
                profile=payload.profile,
                run_id=payload.run_id,
            )
        except RuntimeError as exc:
            raise HTTPException(status_code=409, detail=str(exc)) from exc
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc
        return {"run": session}

    @app.get("/api/runs/{run_id}", dependencies=[Depends(require_session)])
    async def run_detail(run_id: str) -> dict[str, object]:
        try:
            return repository.detail(run_id)
        except (FileNotFoundError, ValueError) as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc

    @app.get(
        "/api/runs/{run_id}/nodes/{task_id}/reasoning",
        dependencies=[Depends(require_session)],
    )
    async def node_reasoning(run_id: str, task_id: str) -> dict[str, Any]:
        try:
            return repository.reasoning_snapshot(run_id, task_id)
        except (FileNotFoundError, ValueError) as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc

    @app.get(
        "/api/runs/{run_id}/nodes/{task_id}/computation",
        dependencies=[Depends(require_session)],
    )
    async def node_computation(run_id: str, task_id: str) -> dict[str, Any]:
        try:
            return repository.computation_snapshot(run_id, task_id)
        except (FileNotFoundError, ValueError) as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc

    @app.get(
        "/api/runs/{run_id}/nodes/{task_id}/reasoning/events",
        dependencies=[Depends(require_session)],
    )
    async def node_reasoning_events(
        run_id: str,
        task_id: str,
        after: int = 0,
    ):
        try:
            repository.reasoning_snapshot(run_id, task_id)
        except (FileNotFoundError, ValueError) as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc

        async def stream_reasoning() -> AsyncIterator[str]:
            cursor = max(0, after)
            heartbeat_at = asyncio.get_running_loop().time()
            yield _sse(
                "connected",
                {
                    "run_id": run_id,
                    "task_id": task_id,
                    "cursor": cursor,
                },
            )
            while True:
                records, cursor = repository.read_reasoning_updates(
                    run_id,
                    task_id,
                    offset=cursor,
                )
                for record in records:
                    yield _sse(
                        "reasoning",
                        {
                            "record": record,
                            "cursor": cursor,
                        },
                    )
                session = manager.session(run_id)
                active = bool(
                    session is not None
                    and session.task is not None
                    and not session.task.done()
                )
                if not active and not records:
                    yield _sse(
                        "terminal",
                        {
                            "run_id": run_id,
                            "task_id": task_id,
                            "cursor": cursor,
                        },
                    )
                    break
                now = asyncio.get_running_loop().time()
                if now - heartbeat_at >= 10.0:
                    heartbeat_at = now
                    yield _sse(
                        "heartbeat",
                        {
                            "run_id": run_id,
                            "task_id": task_id,
                            "cursor": cursor,
                        },
                    )
                await asyncio.sleep(0.2)

        return StreamingResponse(
            stream_reasoning(),
            media_type="text/event-stream",
            headers={
                "Cache-Control": "no-cache",
                "X-Accel-Buffering": "no",
                "Connection": "keep-alive",
            },
        )

    @app.delete("/api/runs/{run_id}", dependencies=[Depends(require_session)])
    async def delete_run(run_id: str) -> dict[str, object]:
        try:
            deleted_run_id = await manager.delete(run_id)
        except RuntimeError as exc:
            raise HTTPException(status_code=409, detail=str(exc)) from exc
        except (FileNotFoundError, ValueError) as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc
        except OSError as exc:
            raise HTTPException(status_code=500, detail=str(exc)) from exc
        return {
            "deleted_run_id": deleted_run_id,
            "runs": repository.list_runs(),
        }

    @app.post(
        "/api/runs/{run_id}/resume",
        dependencies=[Depends(require_session)],
    )
    async def resume_run(
        run_id: str,
        payload: ResumeRunRequest,
    ) -> dict[str, object]:
        if payload.resume_mode not in RESUME_MODES:
            raise HTTPException(
                status_code=400,
                detail="resume_mode must be one of: " + ", ".join(RESUME_MODES),
            )
        try:
            session = await manager.resume(
                run_id=run_id,
                profile=payload.profile,
                resume_mode=payload.resume_mode,
            )
        except RuntimeError as exc:
            raise HTTPException(status_code=409, detail=str(exc)) from exc
        except FileNotFoundError as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc
        return {"run": session}

    @app.post(
        "/api/runs/{run_id}/clarification",
        dependencies=[Depends(require_session)],
    )
    async def confirm_clarification(
        run_id: str,
        payload: ClarificationDecisionRequest,
    ) -> dict[str, object]:
        try:
            session = await manager.confirm_clarification(
                run_id,
                GoalClarificationDecision(
                    request_id=payload.request_id,
                    canonical_statement=payload.canonical_statement,
                    source="user_confirmed",
                    selected_candidate_index=payload.selected_candidate_index,
                ),
            )
        except RuntimeError as exc:
            raise HTTPException(status_code=409, detail=str(exc)) from exc
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc
        return {"run": session}

    @app.post(
        "/api/runs/{run_id}/cancel",
        dependencies=[Depends(require_session)],
    )
    async def cancel_run(run_id: str) -> dict[str, object]:
        try:
            session = await manager.cancel(run_id)
        except (FileNotFoundError, ValueError) as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc
        return {"run": session}

    @app.get(
        "/api/runs/{run_id}/events",
        dependencies=[Depends(require_session)],
    )
    async def run_events(run_id: str):
        try:
            session, queue, replay = manager.subscribe(run_id)
        except (FileNotFoundError, ValueError) as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc

        async def stream() -> AsyncIterator[str]:
            terminal_seen = False
            try:
                yield _sse("connected", {"run_id": run_id})
                for item in replay:
                    event = str(item["event"])
                    data = item["data"]
                    yield _sse(event, data)
                    if event == "terminal":
                        terminal_seen = True
                while not terminal_seen:
                    try:
                        item = await asyncio.wait_for(queue.get(), timeout=15.0)
                    except TimeoutError:
                        yield _sse("heartbeat", {"run_id": run_id})
                        continue
                    event = str(item["event"])
                    data = item["data"]
                    yield _sse(event, data)
                    if event == "terminal":
                        terminal_seen = True
            finally:
                manager.unsubscribe(session, queue)

        return StreamingResponse(
            stream(),
            media_type="text/event-stream",
            headers={
                "Cache-Control": "no-cache",
                "X-Accel-Buffering": "no",
                "Connection": "keep-alive",
            },
        )

    @app.post("/api/open-path", dependencies=[Depends(require_session)])
    async def open_path(payload: OpenPathRequest) -> dict[str, object]:
        if payload.kind == "data":
            path = resolved_paths.root
        elif payload.kind == "runs":
            path = resolved_paths.runs
        elif payload.kind == "logs":
            path = resolved_paths.logs
        else:
            if not payload.run_id:
                raise HTTPException(status_code=400, detail="run_id is required")
            try:
                path = repository.run_directory(payload.run_id)
            except ValueError as exc:
                raise HTTPException(status_code=400, detail=str(exc)) from exc
        path.mkdir(parents=True, exist_ok=True)
        try:
            if os.name != "nt":
                raise RuntimeError("打开目录功能仅在 Windows 桌面版可用")
            os.startfile(str(path))  # type: ignore[attr-defined]
        except OSError as exc:
            raise HTTPException(status_code=500, detail=str(exc)) from exc
        return {"opened": str(path)}

    return app
