from __future__ import annotations

import json
import re
import threading
import time
from collections import OrderedDict
from enum import StrEnum
from typing import Any, Callable, Literal

from pydantic import BaseModel, ConfigDict, Field
from rich.console import Console, ConsoleOptions, Group, RenderResult
from rich.live import Live
from rich.panel import Panel
from rich.table import Table
from rich.text import Text

from .schemas import new_id, utc_now_iso
from .store import ArtifactStore


class ActivityStatus(StrEnum):
    RUNNING = "running"
    COMPLETED = "completed"
    INFO = "info"
    WARNING = "warning"
    FAILED = "failed"


class ActivityImportance(StrEnum):
    MAJOR = "major"
    NORMAL = "normal"
    DETAIL = "detail"


class ActivityEvent(BaseModel):
    """A concise, user-facing progress event; never a model chain of thought."""

    model_config = ConfigDict(
        extra="forbid", validate_assignment=True, str_strip_whitespace=True
    )

    sequence: int = Field(ge=1)
    timestamp: str
    elapsed_ms: int = Field(ge=0)
    event_type: str
    status: ActivityStatus
    importance: ActivityImportance = ActivityImportance.NORMAL
    stage: str | None = None
    task_id: str
    parent_task_id: str | None = None
    title: str
    detail: str = ""
    agent_id: str | None = None
    progress: float | None = Field(default=None, ge=0.0, le=1.0)
    metrics: dict[str, Any] = Field(default_factory=dict)


ActivityListener = Callable[[ActivityEvent], None]
ActivityMode = Literal["off", "compact", "detailed"]

_SECRET_PATTERNS = (
    re.compile(r"\bsk-[A-Za-z0-9_-]{10,}\b"),
    re.compile(r"(?i)\bBearer\s+[A-Za-z0-9._~+/=-]{12,}"),
    re.compile(r"(?i)(api[_ -]?key\s*[:=]\s*)[^\s,;]+"),
)

_STAGE_LABELS_ZH = {
    "triage": "分析题目类型与主要风险",
    "strategy_generation": "生成互相独立的证明路线",
    "independent_exploration": "沿指定路线独立推演",
    "claim_extraction": "提取可复用的引理与结论",
    "structural_verification": "检查证明结构与题意一致性",
    "detailed_verification": "逐步核查关键推导",
    "final_verification": "逐步核查最终证明",
    "meta_review": "汇总审查结果并决定下一步",
    "synthesis": "综合已验证路线形成最终证明",
    "final_revision": "按审查意见定向修订最终证明",
    "targeted_deepening": "针对当前缺口继续深挖",
    "final_structural_verification": "执行最终结构审查",
    "final_detailed_verification": "执行最终逐步审查",
    "proof_continuation": "从已验证检查点继续证明",
    "checkpoint_verification": "验证并提交证明检查点",
    "agent_failover": "切换备用 Agent 继续当前任务",
    "run_resume": "恢复中断的多 Agent 运行",
    "message_broker": "路由强类型数学消息",
    "route_team": "执行路线局部协作与独立审查",
    "proof_graph": "更新证明义务图",
    "inspiration": "执行表示、类比、构造与策略灵感机制",
}

_STAGE_LABELS_EN = {
    "triage": "Analyze the problem type and main risks",
    "strategy_generation": "Generate independent proof strategies",
    "independent_exploration": "Explore the assigned route independently",
    "claim_extraction": "Extract reusable lemmas and claims",
    "structural_verification": "Check structure and theorem integrity",
    "detailed_verification": "Audit the key derivation step by step",
    "final_verification": "Audit the final proof step by step",
    "meta_review": "Aggregate reviews and choose the next action",
    "synthesis": "Synthesize supported routes into a final proof",
    "final_revision": "Revise the final proof from targeted feedback",
    "targeted_deepening": "Deepen the current route around its main gap",
    "final_structural_verification": "Run the final structural audit",
    "final_detailed_verification": "Run the final step-level audit",
    "proof_continuation": "Continue from a verified proof checkpoint",
    "checkpoint_verification": "Verify and commit a proof checkpoint",
    "agent_failover": "Fail over to a backup agent",
    "run_resume": "Resume an interrupted multi-agent run",
    "message_broker": "Route typed mathematical messages",
    "route_team": "Run route-local collaboration and independent review",
    "proof_graph": "Update the proof-obligation graph",
    "inspiration": "Run representation, analogy, construction, and meta inspiration",
}


def redact_activity_text(value: str, *, limit: int = 800) -> str:
    """Redact common credential forms and keep progress messages compact."""
    text = " ".join(str(value).split())
    for pattern in _SECRET_PATTERNS:
        if pattern.pattern.lower().startswith("(?i)(api"):
            text = pattern.sub(r"\1[REDACTED]", text)
        else:
            text = pattern.sub("[REDACTED]", text)
    if len(text) > limit:
        return text[: max(0, limit - 1)].rstrip() + "…"
    return text


def sanitize_activity_value(value: Any, *, depth: int = 0) -> Any:
    """Recursively make activity metrics safe, compact, and JSON serializable.

    Metrics are user-visible telemetry, not a back door for raw prompts, provider
    payloads, credentials, or arbitrary Python objects. Nested containers are
    bounded to prevent a progress event from becoming a large transcript.
    """
    if depth >= 5:
        return redact_activity_text(str(value), limit=240)
    if value is None or isinstance(value, (bool, int)):
        return value
    if isinstance(value, float):
        # JSON has no portable representation for NaN or infinities.
        return (
            value
            if value == value and value not in {float("inf"), float("-inf")}
            else str(value)
        )
    if isinstance(value, str):
        return redact_activity_text(value, limit=400)
    if isinstance(value, dict):
        result: dict[str, Any] = {}
        for index, (key, item) in enumerate(value.items()):
            if index >= 50:
                result["_truncated"] = True
                break
            safe_key = redact_activity_text(str(key), limit=100)
            result[safe_key] = sanitize_activity_value(item, depth=depth + 1)
        return result
    if isinstance(value, (list, tuple, set, frozenset)):
        items = list(value)
        result = [sanitize_activity_value(item, depth=depth + 1) for item in items[:50]]
        if len(items) > 50:
            result.append("[TRUNCATED]")
        return result
    return redact_activity_text(str(value), limit=400)


def redact_activity_value(value: Any, *, depth: int = 0) -> Any:
    """Recursively redact small metadata payloads before persistence or streaming."""
    if depth > 5:
        return "[TRUNCATED]"
    if isinstance(value, str):
        return redact_activity_text(value, limit=400)
    if isinstance(value, dict):
        items = list(value.items())[:64]
        return {
            redact_activity_text(str(key), limit=120): redact_activity_value(
                item, depth=depth + 1
            )
            for key, item in items
        }
    if isinstance(value, (list, tuple, set)):
        return [
            redact_activity_value(item, depth=depth + 1) for item in list(value)[:64]
        ]
    if isinstance(value, (int, float, bool)) or value is None:
        return value
    return redact_activity_text(str(value), limit=400)


def stage_label(stage: str, language: str = "zh-CN") -> str:
    normalized = stage
    normalized = re.sub(r"_json_repair_\d+$", "", normalized)
    if normalized.endswith("_verification") and normalized not in {
        "structural_verification",
        "detailed_verification",
        "final_verification",
    }:
        normalized = (
            "final_verification"
            if normalized.startswith("final")
            else "detailed_verification"
        )
    labels = _STAGE_LABELS_ZH if language.lower().startswith("zh") else _STAGE_LABELS_EN
    return labels.get(normalized, normalized.replace("_", " ").strip().capitalize())


def format_elapsed(seconds: float) -> str:
    total = max(0, int(seconds))
    hours, remainder = divmod(total, 3600)
    minutes, secs = divmod(remainder, 60)
    if hours:
        return f"{hours:d}:{minutes:02d}:{secs:02d}"
    return f"{minutes:02d}:{secs:02d}"


class ActivityStream:
    """Publishes concise timeline events to disk and optional live listeners."""

    def __init__(
        self,
        store: ArtifactStore,
        *,
        language: str = "zh-CN",
        listener: ActivityListener | None = None,
        persist: bool = True,
    ) -> None:
        self.store = store
        self.language = language
        self.listener = listener
        self.persist = persist
        self.path = self.store.root / "activity.jsonl"
        self.events: list[ActivityEvent] = []
        self._sequence = 0
        self._lock = threading.Lock()
        self._finalized = False
        elapsed_offset_ms = self._load_existing_events() if self.persist else 0
        self.started_monotonic = time.monotonic() - elapsed_offset_ms / 1000.0

    def _load_existing_events(self) -> int:
        """Continue one persisted timeline across process restarts."""
        if not self.path.exists():
            return 0
        max_elapsed = 0
        try:
            with self.path.open("r", encoding="utf-8") as handle:
                for line in handle:
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        event = ActivityEvent.model_validate_json(line)
                    except (ValueError, json.JSONDecodeError):
                        continue
                    self.events.append(event)
                    self._sequence = max(self._sequence, event.sequence)
                    max_elapsed = max(max_elapsed, event.elapsed_ms)
        except OSError:
            return 0
        return max_elapsed

    @property
    def is_zh(self) -> bool:
        return self.language.lower().startswith("zh")

    def text(self, zh: str, en: str) -> str:
        return zh if self.is_zh else en

    def emit(
        self,
        event_type: str,
        *,
        status: ActivityStatus,
        title: str,
        detail: str = "",
        stage: str | None = None,
        task_id: str | None = None,
        parent_task_id: str | None = None,
        agent_id: str | None = None,
        importance: ActivityImportance = ActivityImportance.NORMAL,
        progress: float | None = None,
        metrics: dict[str, Any] | None = None,
    ) -> ActivityEvent:
        with self._lock:
            self._sequence += 1
            event = ActivityEvent(
                sequence=self._sequence,
                timestamp=utc_now_iso(),
                elapsed_ms=max(
                    0, int((time.monotonic() - self.started_monotonic) * 1000)
                ),
                event_type=event_type,
                status=status,
                importance=importance,
                stage=stage,
                task_id=task_id or new_id("activity"),
                parent_task_id=parent_task_id,
                title=redact_activity_text(title, limit=240),
                detail=redact_activity_text(detail, limit=800),
                agent_id=redact_activity_text(agent_id, limit=120)
                if agent_id
                else None,
                progress=progress,
                metrics=sanitize_activity_value(metrics or {}),
            )
            self.events.append(event)
            if self.persist:
                with self.path.open("a", encoding="utf-8") as handle:
                    handle.write(
                        json.dumps(
                            event.model_dump(mode="json"),
                            ensure_ascii=False,
                            sort_keys=True,
                        )
                        + "\n"
                    )
        if self.listener is not None:
            try:
                self.listener(event)
            except Exception:
                # Progress rendering must never interrupt mathematical work.
                pass
        return event

    def start_task(
        self,
        event_type: str,
        *,
        title: str,
        detail: str = "",
        stage: str | None = None,
        task_id: str | None = None,
        parent_task_id: str | None = None,
        agent_id: str | None = None,
        importance: ActivityImportance = ActivityImportance.NORMAL,
        metrics: dict[str, Any] | None = None,
    ) -> str:
        event = self.emit(
            event_type,
            status=ActivityStatus.RUNNING,
            title=title,
            detail=detail,
            stage=stage,
            task_id=task_id,
            parent_task_id=parent_task_id,
            agent_id=agent_id,
            importance=importance,
            metrics=metrics,
        )
        return event.task_id

    def update_task(
        self,
        task_id: str,
        *,
        title: str,
        detail: str = "",
        status: ActivityStatus = ActivityStatus.RUNNING,
        event_type: str = "task_updated",
        stage: str | None = None,
        parent_task_id: str | None = None,
        agent_id: str | None = None,
        importance: ActivityImportance = ActivityImportance.NORMAL,
        progress: float | None = None,
        metrics: dict[str, Any] | None = None,
    ) -> ActivityEvent:
        return self.emit(
            event_type,
            status=status,
            title=title,
            detail=detail,
            stage=stage,
            task_id=task_id,
            parent_task_id=parent_task_id,
            agent_id=agent_id,
            importance=importance,
            progress=progress,
            metrics=metrics,
        )

    def info(
        self,
        event_type: str,
        *,
        title: str,
        detail: str = "",
        stage: str | None = None,
        task_id: str | None = None,
        parent_task_id: str | None = None,
        agent_id: str | None = None,
        importance: ActivityImportance = ActivityImportance.NORMAL,
        metrics: dict[str, Any] | None = None,
    ) -> ActivityEvent:
        return self.emit(
            event_type,
            status=ActivityStatus.INFO,
            title=title,
            detail=detail,
            stage=stage,
            task_id=task_id,
            parent_task_id=parent_task_id,
            agent_id=agent_id,
            importance=importance,
            metrics=metrics,
        )

    def complete_task(
        self,
        task_id: str,
        *,
        title: str,
        detail: str = "",
        event_type: str = "task_completed",
        stage: str | None = None,
        parent_task_id: str | None = None,
        agent_id: str | None = None,
        importance: ActivityImportance = ActivityImportance.NORMAL,
        metrics: dict[str, Any] | None = None,
    ) -> ActivityEvent:
        return self.update_task(
            task_id,
            title=title,
            detail=detail,
            status=ActivityStatus.COMPLETED,
            event_type=event_type,
            stage=stage,
            parent_task_id=parent_task_id,
            agent_id=agent_id,
            importance=importance,
            progress=1.0,
            metrics=metrics,
        )

    def warn_task(
        self,
        task_id: str,
        *,
        title: str,
        detail: str = "",
        event_type: str = "task_warning",
        stage: str | None = None,
        parent_task_id: str | None = None,
        agent_id: str | None = None,
        importance: ActivityImportance = ActivityImportance.NORMAL,
        metrics: dict[str, Any] | None = None,
    ) -> ActivityEvent:
        return self.update_task(
            task_id,
            title=title,
            detail=detail,
            status=ActivityStatus.WARNING,
            event_type=event_type,
            stage=stage,
            parent_task_id=parent_task_id,
            agent_id=agent_id,
            importance=importance,
            metrics=metrics,
        )

    def fail_task(
        self,
        task_id: str,
        *,
        title: str,
        detail: str = "",
        event_type: str = "task_failed",
        stage: str | None = None,
        parent_task_id: str | None = None,
        agent_id: str | None = None,
        importance: ActivityImportance = ActivityImportance.NORMAL,
        metrics: dict[str, Any] | None = None,
    ) -> ActivityEvent:
        return self.update_task(
            task_id,
            title=title,
            detail=detail,
            status=ActivityStatus.FAILED,
            event_type=event_type,
            stage=stage,
            parent_task_id=parent_task_id,
            agent_id=agent_id,
            importance=importance,
            metrics=metrics,
        )

    def close_open_tasks(
        self,
        *,
        status: ActivityStatus,
        detail: str,
        exclude_task_ids: set[str] | None = None,
    ) -> None:
        """Close tasks left running by an interrupted or budget-limited run."""
        excluded = exclude_task_ids or set()
        latest: dict[str, ActivityEvent] = {}
        for event in self.events:
            latest[event.task_id] = event
        for task_id, event in list(latest.items()):
            if task_id in excluded or event.status != ActivityStatus.RUNNING:
                continue
            self.update_task(
                task_id,
                title=event.title,
                detail=detail,
                status=status,
                event_type="task_closed_by_run",
                stage=event.stage,
                parent_task_id=event.parent_task_id,
                agent_id=event.agent_id,
                importance=event.importance,
                metrics={"closed_by_run": True},
            )

    def finalize(self) -> tuple[str | None, str | None]:
        if self._finalized:
            return None, None
        self._finalized = True
        if not self.persist:
            return None, None
        json_ref = self.store.write_json(
            "reports",
            "activity_timeline",
            [event.model_dump(mode="json") for event in self.events],
        )
        markdown_ref = self.store.write_text(
            "reports",
            "activity_timeline",
            self._markdown(),
            suffix=".md",
        )
        return json_ref, markdown_ref

    def _markdown(self) -> str:
        zh = self.is_zh
        title = "# 运行时间线" if zh else "# Run activity timeline"
        note = (
            "> 这里只记录可审计的阶段状态和结构化结果摘要，不包含任何模型的原始私有思考链。"
            if zh
            else "> This records auditable stage status and structured-result summaries, not private model chain of thought."
        )
        lines = [title, "", note, ""]
        for event in self.events:
            elapsed = format_elapsed(event.elapsed_ms / 1000)
            marker = {
                ActivityStatus.RUNNING: "▶",
                ActivityStatus.COMPLETED: "✓",
                ActivityStatus.INFO: "•",
                ActivityStatus.WARNING: "!",
                ActivityStatus.FAILED: "×",
            }[event.status]
            agent = f" · `{event.agent_id}`" if event.agent_id else ""
            lines.append(f"- `{elapsed}` {marker} **{event.title}**{agent}")
            if event.detail:
                lines.append(f"  - {event.detail}")
        lines.append("")
        return "\n".join(lines)


class ConsoleActivityView:
    """Rich live timeline for CLI runs, styled like a compact activity panel."""

    def __init__(
        self,
        *,
        language: str = "zh-CN",
        mode: ActivityMode = "compact",
        max_items: int = 18,
        console: Console | None = None,
    ) -> None:
        self.language = language
        self.mode = mode
        self.max_items = max(4, max_items)
        self.console = console or Console(stderr=True)
        self.started_monotonic = time.monotonic()
        self._latest: OrderedDict[str, ActivityEvent] = OrderedDict()
        self._live: Live | None = None
        self._interactive = bool(self.console.is_terminal)
        self._closed = False

    @property
    def is_zh(self) -> bool:
        return self.language.lower().startswith("zh")

    def __enter__(self) -> "ConsoleActivityView":
        if self.mode != "off" and self._interactive:
            self._live = Live(
                self,
                console=self.console,
                refresh_per_second=6,
                transient=False,
                vertical_overflow="visible",
            )
            self._live.start(refresh=True)
        return self

    def __exit__(self, exc_type, exc, traceback) -> None:  # type: ignore[no-untyped-def]
        self.close()

    def handle(self, event: ActivityEvent) -> None:
        if self.mode == "off" or self._closed:
            return
        is_new = event.task_id not in self._latest
        self._latest[event.task_id] = event
        if is_new:
            self._latest.move_to_end(event.task_id)
        if self._interactive:
            if self._live is not None:
                self._live.refresh()
            return
        # Redirected logs get concise milestone lines rather than ANSI redraws.
        if not self._show_in_current_mode(event):
            return
        icon = self._icon(event.status)
        elapsed = format_elapsed(event.elapsed_ms / 1000)
        detail = f" — {event.detail}" if event.detail else ""
        agent = f" [{event.agent_id}]" if event.agent_id else ""
        self.console.print(f"{icon} {elapsed} {event.title}{agent}{detail}")

    def close(self) -> None:
        if self._closed:
            return
        self._closed = True
        if self._live is not None:
            self._live.refresh()
            self._live.stop()
            self._live = None

    def __rich_console__(
        self, console: Console, options: ConsoleOptions
    ) -> RenderResult:
        yield self._render()

    def _render(self) -> Panel:
        elapsed = format_elapsed(time.monotonic() - self.started_monotonic)
        title = f"Activity · {elapsed}"
        events = self._visible_events()
        if not events:
            waiting = "等待任务启动…" if self.is_zh else "Waiting for the run to start…"
            return Panel(Text(waiting, style="dim"), title=title, border_style="dim")

        table = Table.grid(padding=(0, 1))
        table.add_column(width=2, no_wrap=True)
        table.add_column(width=7, no_wrap=True, style="dim")
        table.add_column(ratio=1)
        for event in events:
            icon = Text(self._icon(event.status), style=self._icon_style(event.status))
            elapsed_text = Text(format_elapsed(event.elapsed_ms / 1000), style="dim")
            headline = Text(
                event.title,
                style="bold" if event.importance == ActivityImportance.MAJOR else "",
            )
            if event.agent_id:
                headline.append(f"  {event.agent_id}", style="dim")
            renderables: list[Any] = [headline]
            if event.detail:
                limit = 150 if self.mode == "compact" else 360
                detail = redact_activity_text(event.detail, limit=limit)
                renderables.append(Text(detail, style="dim"))
            table.add_row(icon, elapsed_text, Group(*renderables))
        note = (
            "仅显示阶段摘要，不显示模型原始思考链"
            if self.is_zh
            else "Stage summaries only; private model reasoning is not shown"
        )
        return Panel(
            Group(table, Text(note, style="dim italic")),
            title=title,
            border_style="dim",
            padding=(0, 1),
        )

    def _visible_events(self) -> list[ActivityEvent]:
        events = [
            event
            for event in self._latest.values()
            if self._show_in_current_mode(event)
        ]
        if len(events) <= self.max_items:
            return events
        running = [event for event in events if event.status == ActivityStatus.RUNNING]
        tail = events[-self.max_items :]
        merged: OrderedDict[str, ActivityEvent] = OrderedDict(
            (event.task_id, event) for event in tail
        )
        for event in running:
            merged[event.task_id] = event
        return list(merged.values())[-self.max_items :]

    def _show_in_current_mode(self, event: ActivityEvent) -> bool:
        if self.mode == "detailed":
            return True
        if self.mode == "off":
            return False
        if event.importance == ActivityImportance.MAJOR:
            return True
        if event.status in {ActivityStatus.WARNING, ActivityStatus.FAILED}:
            return True
        # In an interactive compact panel, keep only currently active Agent calls.
        # Their completed rows disappear once the containing major stage is summarized.
        # Redirected logs retain only milestones so CI output remains short.
        return (
            self._interactive
            and event.event_type
            in {
                "agent_call",
                "agent_call_heartbeat",
                "agent_call_retry",
            }
            and event.status == ActivityStatus.RUNNING
        )

    @staticmethod
    def _icon(status: ActivityStatus) -> str:
        return {
            ActivityStatus.RUNNING: "◐",
            ActivityStatus.COMPLETED: "✓",
            ActivityStatus.INFO: "•",
            ActivityStatus.WARNING: "!",
            ActivityStatus.FAILED: "×",
        }[status]

    @staticmethod
    def _icon_style(status: ActivityStatus) -> str:
        return {
            ActivityStatus.RUNNING: "cyan",
            ActivityStatus.COMPLETED: "green",
            ActivityStatus.INFO: "blue",
            ActivityStatus.WARNING: "yellow",
            ActivityStatus.FAILED: "red",
        }[status]
