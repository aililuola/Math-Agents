from __future__ import annotations

import asyncio
import hashlib
import logging
import os
from collections import deque
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any

from .. import __version__
from ..activity import ActivityEvent
from ..schemas import GoalClarificationDecision, GoalClarificationRequest
from ..llm.deepseek import DeepSeekClient
from ..llm.pool import AgentPool
from ..orchestrator import ProofMeshOrchestrator
from .configuration import DesktopConfigService
from .paths import DesktopPaths
from .repository import (
    DesktopRunMetadata,
    RunRepository,
    utc_now,
    validate_run_id,
)
from .security import CredentialVault
from .settings import DesktopProfile, DesktopSettings, SettingsStore


LOGGER = logging.getLogger(__name__)


@dataclass
class LiveRun:
    metadata: DesktopRunMetadata
    problem: str
    task: asyncio.Task[None] | None = None
    events: deque[dict[str, Any]] = field(default_factory=lambda: deque(maxlen=1500))
    subscribers: set[asyncio.Queue[dict[str, Any]]] = field(default_factory=set)
    pending_clarification: GoalClarificationRequest | None = None
    clarification_future: asyncio.Future[GoalClarificationDecision] | None = None

    def snapshot(self) -> dict[str, object]:
        title = next(
            (line.strip() for line in self.problem.splitlines() if line.strip()),
            self.metadata.run_id,
        )
        if len(title) > 90:
            title = title[:89].rstrip() + "…"
        return {
            "run_id": self.metadata.run_id,
            "title": title,
            "profile": self.metadata.profile,
            "lifecycle": self.metadata.lifecycle,
            "mode": self.metadata.mode,
            "created_at": self.metadata.created_at,
            "updated_at": self.metadata.updated_at,
            "error": self.metadata.error,
            "clarification_pending": self.pending_clarification is not None,
        }


class DesktopRunManager:
    def __init__(
        self,
        paths: DesktopPaths,
        settings_store: SettingsStore,
        credentials: CredentialVault,
        config_service: DesktopConfigService,
        repository: RunRepository,
    ) -> None:
        self.paths = paths
        self.settings_store = settings_store
        self.credentials = credentials
        self.config_service = config_service
        self.repository = repository
        self._sessions: dict[str, LiveRun] = {}
        self._lock = asyncio.Lock()

    @property
    def settings(self) -> DesktopSettings:
        return self.settings_store.load()

    def update_settings(self, settings: DesktopSettings) -> DesktopSettings:
        self.settings_store.save(settings)
        return settings

    def bootstrap(self) -> dict[str, object]:
        settings = self.settings
        active = next(
            (
                session.snapshot()
                for session in self._sessions.values()
                if session.metadata.lifecycle
                in {"queued", "running", "awaiting_confirmation"}
            ),
            None,
        )
        return {
            "version": __version__,
            "data_root": str(self.paths.root),
            "settings": settings.model_dump(mode="json"),
            "profiles": self.config_service.profile_summaries(settings),
            "credential_status": self.credentials.statuses(),
            "docker_available": self.config_service.docker_available(),
            "runs": self.repository.list_runs(),
            "active_run": active,
        }

    async def start(
        self,
        *,
        problem: str,
        profile: DesktopProfile,
        run_id: str | None,
    ) -> dict[str, object]:
        normalized_problem = problem.strip()
        if not normalized_problem:
            raise ValueError("题目不能为空")
        async with self._lock:
            self._ensure_idle()
            settings = self.settings
            config = self.config_service.build(profile, settings)
            resolved_run_id = (
                validate_run_id(run_id)
                if run_id and run_id.strip()
                else self._make_run_id(normalized_problem)
            )
            if self.repository.run_directory(resolved_run_id).exists():
                raise ValueError(f"运行 ID 已存在：{resolved_run_id}")

            now = utc_now()
            metadata = DesktopRunMetadata(
                run_id=resolved_run_id,
                profile=profile,
                lifecycle="queued",
                created_at=now,
                updated_at=now,
                mode="solve",
                process_id=os.getpid(),
            )
            session = LiveRun(metadata=metadata, problem=normalized_problem)
            self._sessions[resolved_run_id] = session
            self.repository.write_metadata(metadata)
            loop = asyncio.get_running_loop()

            def activity_listener(event: ActivityEvent) -> None:
                loop.call_soon_threadsafe(
                    self._publish,
                    session,
                    "activity",
                    event.model_dump(mode="json"),
                )

            async def clarification_resolver(
                request: GoalClarificationRequest,
            ) -> GoalClarificationDecision:
                return await self._request_clarification(session, request)

            orchestrator = ProofMeshOrchestrator(
                config,
                activity_listener=activity_listener,
                clarification_resolver=clarification_resolver,
            )
            session.task = asyncio.create_task(
                self._execute(session, orchestrator, resume=False),
                name=f"mathproofmesh-solve-{resolved_run_id}",
            )
            self._publish(session, "state", session.snapshot())
            return session.snapshot()

    async def resume(
        self,
        *,
        run_id: str,
        profile: DesktopProfile,
    ) -> dict[str, object]:
        safe_id = validate_run_id(run_id)
        async with self._lock:
            self._ensure_idle()
            if not self.repository.run_directory(safe_id).exists():
                raise FileNotFoundError(safe_id)
            config = self.config_service.build(profile, self.settings)
            prior = self.repository.read_metadata(safe_id)
            now = utc_now()
            metadata = DesktopRunMetadata(
                run_id=safe_id,
                profile=profile,
                lifecycle="queued",
                created_at=prior.created_at if prior else now,
                updated_at=now,
                mode="resume",
                process_id=os.getpid(),
            )
            session = LiveRun(metadata=metadata, problem="")
            self._sessions[safe_id] = session
            self.repository.write_metadata(metadata)
            loop = asyncio.get_running_loop()

            def activity_listener(event: ActivityEvent) -> None:
                loop.call_soon_threadsafe(
                    self._publish,
                    session,
                    "activity",
                    event.model_dump(mode="json"),
                )

            orchestrator = ProofMeshOrchestrator(
                config,
                activity_listener=activity_listener,
            )
            session.task = asyncio.create_task(
                self._execute(session, orchestrator, resume=True),
                name=f"mathproofmesh-resume-{safe_id}",
            )
            self._publish(session, "state", session.snapshot())
            return session.snapshot()

    async def cancel(self, run_id: str) -> dict[str, object]:
        safe_id = validate_run_id(run_id)
        session = self._sessions.get(safe_id)
        if session is None or session.task is None:
            raise FileNotFoundError(safe_id)
        if session.task.done():
            return session.snapshot()
        session.metadata.lifecycle = "cancelled"
        session.metadata.updated_at = utc_now()
        self.repository.write_metadata(session.metadata)
        self._publish(session, "state", session.snapshot())
        session.task.cancel()
        return session.snapshot()

    async def confirm_clarification(
        self,
        run_id: str,
        decision: GoalClarificationDecision,
    ) -> dict[str, object]:
        safe_id = validate_run_id(run_id)
        session = self._sessions.get(safe_id)
        if (
            session is None
            or session.pending_clarification is None
            or session.clarification_future is None
            or session.clarification_future.done()
        ):
            raise RuntimeError("当前运行没有等待确认的题意解释")
        if decision.request_id != session.pending_clarification.request_id:
            raise ValueError("题意确认请求已过期，请按当前候选重新确认")
        session.clarification_future.set_result(decision)
        return session.snapshot()

    async def delete(self, run_id: str) -> str:
        safe_id = validate_run_id(run_id)
        async with self._lock:
            session = self._sessions.get(safe_id)
            if (
                session is not None
                and session.task is not None
                and not session.task.done()
            ):
                raise RuntimeError("正在运行的任务不能删除，请先停止任务")
            await asyncio.to_thread(
                self.repository.move_to_recycle_bin,
                safe_id,
            )
            self._sessions.pop(safe_id, None)
        return safe_id

    def session(self, run_id: str) -> LiveRun | None:
        return self._sessions.get(validate_run_id(run_id))

    def subscribe(
        self,
        run_id: str,
    ) -> tuple[LiveRun, asyncio.Queue[dict[str, Any]], list[dict[str, Any]]]:
        session = self.session(run_id)
        if session is None:
            raise FileNotFoundError(run_id)
        queue: asyncio.Queue[dict[str, Any]] = asyncio.Queue(maxsize=256)
        session.subscribers.add(queue)
        return session, queue, list(session.events)

    @staticmethod
    def unsubscribe(session: LiveRun, queue: asyncio.Queue[dict[str, Any]]) -> None:
        session.subscribers.discard(queue)

    async def probe_credentials(self) -> list[dict[str, object]]:
        config = self.config_service.build(
            self.settings.selected_profile,
            self.settings,
        )
        pool = AgentPool(config)

        async def inspect(agent: object) -> dict[str, object]:
            runtime = agent
            entry: dict[str, object] = {
                "agent": runtime.id,
                "provider": runtime.provider,
                "model": runtime.config.model,
            }
            try:
                if isinstance(runtime.client, DeepSeekClient):
                    models = await runtime.client.list_models()
                    entry["credential_ok"] = True
                    entry["model_visible"] = runtime.config.model in models
                else:
                    entry["credential_ok"] = None
                    entry["model_visible"] = None
            except Exception as exc:
                entry["credential_ok"] = False
                entry["error_type"] = type(exc).__name__
                entry["error"] = str(exc)[:240]
            return entry

        try:
            return await asyncio.gather(*(inspect(agent) for agent in pool.agents))
        finally:
            await pool.aclose()

    async def shutdown(self) -> None:
        tasks = [
            session.task
            for session in self._sessions.values()
            if session.task is not None and not session.task.done()
        ]
        for task in tasks:
            task.cancel()
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)

    async def _execute(
        self,
        session: LiveRun,
        orchestrator: ProofMeshOrchestrator,
        *,
        resume: bool,
    ) -> None:
        session.metadata.lifecycle = "running"
        session.metadata.updated_at = utc_now()
        self.repository.write_metadata(session.metadata)
        self._publish(session, "state", session.snapshot())
        try:
            result = (
                await orchestrator.resume(session.metadata.run_id)
                if resume
                else await orchestrator.solve(
                    session.problem,
                    run_id=session.metadata.run_id,
                )
            )
        except asyncio.CancelledError:
            session.metadata.lifecycle = "cancelled"
            session.metadata.updated_at = utc_now()
            self.repository.write_metadata(session.metadata)
            self._publish(session, "state", session.snapshot())
            self._publish(
                session,
                "terminal",
                {"run_id": session.metadata.run_id, "reason": "cancelled"},
            )
            raise
        except Exception as exc:
            LOGGER.exception("Desktop run %s failed", session.metadata.run_id)
            session.metadata.lifecycle = "failed"
            session.metadata.error = f"{type(exc).__name__}: {str(exc)[:400]}"
            session.metadata.updated_at = utc_now()
            self.repository.write_metadata(session.metadata)
            self._publish(session, "state", session.snapshot())
            self._publish(
                session,
                "error",
                {
                    "error_type": type(exc).__name__,
                    "message": str(exc)[:400],
                },
            )
            self._publish(
                session,
                "terminal",
                {"run_id": session.metadata.run_id, "reason": "failed"},
            )
            return

        if result.execution_status.value == "failed":
            session.metadata.lifecycle = "failed"
        elif result.execution_status.value == "network_interrupted":
            session.metadata.lifecycle = "interrupted"
        else:
            session.metadata.lifecycle = "completed"
        session.metadata.error = None
        session.metadata.updated_at = utc_now()
        self.repository.write_metadata(session.metadata)
        self._publish(session, "state", session.snapshot())
        self._publish(
            session,
            "result",
            {
                "run_id": result.run_id,
                "status": result.status.value,
                "task_status": getattr(result, "task_status", result.status).value,
                "math_status": result.math_status.value,
                "execution_status": result.execution_status.value,
                "total_calls": result.total_calls,
                "total_tokens": result.total_usage.total_tokens,
                "estimated_cost_usd": result.total_usage.estimated_cost_usd,
            },
        )
        self._publish(
            session,
            "terminal",
            {"run_id": session.metadata.run_id, "reason": "completed"},
        )

    async def _request_clarification(
        self,
        session: LiveRun,
        request: GoalClarificationRequest,
    ) -> GoalClarificationDecision:
        loop = asyncio.get_running_loop()
        future: asyncio.Future[GoalClarificationDecision] = loop.create_future()
        session.pending_clarification = request
        session.clarification_future = future
        session.metadata.lifecycle = "awaiting_confirmation"
        session.metadata.updated_at = utc_now()
        self.repository.write_metadata(session.metadata)
        self._publish(session, "state", session.snapshot())
        self._publish(
            session,
            "clarification",
            request.model_dump(mode="json"),
        )
        try:
            return await future
        finally:
            session.pending_clarification = None
            session.clarification_future = None
            if session.metadata.lifecycle == "awaiting_confirmation":
                session.metadata.lifecycle = "running"
                session.metadata.updated_at = utc_now()
                self.repository.write_metadata(session.metadata)
                self._publish(session, "state", session.snapshot())

    def _ensure_idle(self) -> None:
        active = next(
            (
                session
                for session in self._sessions.values()
                if session.task is not None and not session.task.done()
            ),
            None,
        )
        if active is not None:
            raise RuntimeError(f"当前已有任务正在运行：{active.metadata.run_id}")

    @staticmethod
    def _publish(
        session: LiveRun,
        event: str,
        data: dict[str, Any],
    ) -> None:
        item = {"event": event, "data": data}
        session.events.append(item)
        stale: list[asyncio.Queue[dict[str, Any]]] = []
        for queue in session.subscribers:
            try:
                queue.put_nowait(item)
            except asyncio.QueueFull:
                stale.append(queue)
        for queue in stale:
            session.subscribers.discard(queue)

    @staticmethod
    def _make_run_id(problem: str) -> str:
        timestamp = datetime.now().strftime("%Y%m%d-%H%M%S")
        digest = hashlib.sha256(problem.encode("utf-8")).hexdigest()[:6]
        return f"desktop-{timestamp}-{digest}"
