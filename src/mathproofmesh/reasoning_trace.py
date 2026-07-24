from __future__ import annotations

import hashlib
import json
import threading
import time
import uuid
from collections import OrderedDict
from collections.abc import Iterable
from contextlib import contextmanager
from contextvars import ContextVar, Token
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any, Literal


ReasoningTraceStatus = Literal["completed", "failed", "cancelled"]
TRACE_FILE_NAME = "reasoning_traces.txt"
TRACE_FORMAT_VERSION = 1


def _utc_now() -> str:
    return datetime.now(UTC).isoformat()


@dataclass(frozen=True, slots=True)
class ReasoningTraceBinding:
    store: "ReasoningTraceStore"
    task_id: str
    agent_id: str
    stage: str


_CURRENT_TRACE: ContextVar[ReasoningTraceBinding | None] = ContextVar(
    "mathproofmesh_reasoning_trace",
    default=None,
)


@contextmanager
def bind_reasoning_trace(binding: ReasoningTraceBinding):
    token: Token[ReasoningTraceBinding | None] = _CURRENT_TRACE.set(binding)
    try:
        yield
    finally:
        _CURRENT_TRACE.reset(token)


def current_reasoning_trace() -> ReasoningTraceBinding | None:
    return _CURRENT_TRACE.get()


class ReasoningTraceStore:
    """Append-only, run-scoped storage for provider-emitted reasoning text."""

    def __init__(
        self,
        run_directory: str | Path,
        run_id: str,
        *,
        secrets: Iterable[str] = (),
    ) -> None:
        self.run_id = str(run_id)
        self.path = Path(run_directory) / "reports" / TRACE_FILE_NAME
        self._lock = threading.Lock()
        self._call_counts: dict[str, int] = {}
        normalized = {
            str(secret) for secret in secrets if secret and len(str(secret)) >= 8
        }
        self._secrets = tuple(sorted(normalized, key=len, reverse=True))
        self.secret_tail_characters = min(
            512,
            max((len(secret) for secret in self._secrets), default=0),
        )
        self._load_call_counts()

    def begin_call(
        self,
        *,
        task_id: str,
        agent_id: str,
        stage: str,
        thinking_enabled: bool,
        reasoning_effort: str | None,
    ) -> "ReasoningTraceCall":
        safe_task_id = str(task_id)[:240]
        with self._lock:
            call_index = self._call_counts.get(safe_task_id, 0) + 1
            self._call_counts[safe_task_id] = call_index
        call = ReasoningTraceCall(
            store=self,
            task_id=safe_task_id,
            call_id=f"reasoning_{uuid.uuid4().hex}",
            call_index=call_index,
        )
        call.start(
            agent_id=str(agent_id)[:160],
            stage=str(stage)[:160],
            thinking_enabled=thinking_enabled,
            reasoning_effort=reasoning_effort,
        )
        return call

    def append_record(self, record: dict[str, Any]) -> None:
        serialized = json.dumps(
            record,
            ensure_ascii=False,
            separators=(",", ":"),
            sort_keys=True,
        )
        with self._lock:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            with self.path.open("a", encoding="utf-8", newline="\n") as handle:
                handle.write(serialized)
                handle.write("\n")

    def redact(self, value: str) -> tuple[str, bool]:
        result = value
        redacted = False
        for secret in self._secrets:
            if secret in result:
                result = result.replace(secret, "[REDACTED]")
                redacted = True
        return result, redacted

    def flush_cutoff(self, value: str) -> int:
        """Return a prefix boundary that cannot split a configured secret."""

        if not value or not self._secrets:
            return len(value)
        cutoff = max(0, len(value) - self.secret_tail_characters)
        for secret in self._secrets:
            search_from = max(0, cutoff - len(secret))
            start = value.find(secret, search_from)
            while start >= 0:
                end = start + len(secret)
                if start < cutoff < end:
                    cutoff = start
                    break
                start = value.find(secret, start + 1)
        return cutoff

    def _load_call_counts(self) -> None:
        records, _ = read_reasoning_records(self.path)
        for record in records:
            if record.get("type") != "start":
                continue
            task_id = str(record.get("task_id") or "")
            call_index = _nonnegative_int(record.get("call_index"))
            if task_id and call_index:
                self._call_counts[task_id] = max(
                    call_index,
                    self._call_counts.get(task_id, 0),
                )


class ReasoningTraceCall:
    FLUSH_CHARACTERS = 4096
    FLUSH_INTERVAL_SECONDS = 0.15

    def __init__(
        self,
        *,
        store: ReasoningTraceStore,
        task_id: str,
        call_id: str,
        call_index: int,
    ) -> None:
        self.store = store
        self.task_id = task_id
        self.call_id = call_id
        self.call_index = call_index
        self._lock = threading.Lock()
        self._sequence = 0
        self._pending = ""
        self._started = False
        self._finished = False
        self._last_flush = time.monotonic()
        self._source_hash = hashlib.sha256()
        self._stored_hash = hashlib.sha256()
        self._source_characters = 0
        self._stored_characters = 0
        self._redacted = False

    def start(
        self,
        *,
        agent_id: str,
        stage: str,
        thinking_enabled: bool,
        reasoning_effort: str | None,
    ) -> None:
        with self._lock:
            if self._started:
                return
            self._started = True
            self.store.append_record(
                self._base_record(
                    "start",
                    sequence=0,
                    format_version=TRACE_FORMAT_VERSION,
                    agent_id=agent_id,
                    stage=stage,
                    thinking_enabled=bool(thinking_enabled),
                    reasoning_effort=reasoning_effort,
                )
            )

    def append(self, value: str) -> None:
        if not value:
            return
        with self._lock:
            if self._finished:
                return
            self._source_hash.update(value.encode("utf-8"))
            self._source_characters += len(value)
            self._pending += value
            if (
                len(self._pending) >= self.FLUSH_CHARACTERS
                or time.monotonic() - self._last_flush >= self.FLUSH_INTERVAL_SECONDS
            ):
                self._flush_locked(force=False)

    def flush_due(self) -> None:
        with self._lock:
            if (
                not self._finished
                and self._pending
                and time.monotonic() - self._last_flush >= self.FLUSH_INTERVAL_SECONDS
            ):
                self._flush_locked(force=False)

    def finish(
        self,
        status: ReasoningTraceStatus,
        *,
        error_type: str | None = None,
    ) -> None:
        with self._lock:
            if self._finished:
                return
            self._flush_locked(force=True)
            self._sequence += 1
            safe_error, error_redacted = self.store.redact(str(error_type or "")[:160])
            self._redacted = self._redacted or error_redacted
            self.store.append_record(
                self._base_record(
                    "end",
                    sequence=self._sequence,
                    status=status,
                    error_type=safe_error or None,
                    source_characters=self._source_characters,
                    characters=self._stored_characters,
                    source_sha256=self._source_hash.hexdigest(),
                    sha256=self._stored_hash.hexdigest(),
                    redacted=self._redacted,
                )
            )
            self._finished = True

    def _flush_locked(self, *, force: bool) -> None:
        if not self._pending:
            self._last_flush = time.monotonic()
            return
        cutoff = len(self._pending) if force else self.store.flush_cutoff(self._pending)
        if cutoff <= 0:
            return
        raw_text = self._pending[:cutoff]
        self._pending = self._pending[cutoff:]
        text, redacted = self.store.redact(raw_text)
        self._redacted = self._redacted or redacted
        if text:
            self._sequence += 1
            self._stored_hash.update(text.encode("utf-8"))
            self._stored_characters += len(text)
            self.store.append_record(
                self._base_record(
                    "delta",
                    sequence=self._sequence,
                    text=text,
                )
            )
        self._last_flush = time.monotonic()

    def _base_record(self, record_type: str, **values: Any) -> dict[str, Any]:
        return {
            "timestamp": _utc_now(),
            "run_id": self.store.run_id,
            "task_id": self.task_id,
            "call_id": self.call_id,
            "call_index": self.call_index,
            "type": record_type,
            **values,
        }


def read_reasoning_records(
    path: str | Path,
    *,
    task_id: str | None = None,
    offset: int = 0,
) -> tuple[list[dict[str, Any]], int]:
    """Read complete append-log records and return the next byte cursor."""

    trace_path = Path(path)
    if not trace_path.exists():
        return [], 0
    records: list[dict[str, Any]] = []
    cursor = max(0, int(offset))
    try:
        size = trace_path.stat().st_size
        if cursor > size:
            cursor = 0
        with trace_path.open("rb") as handle:
            handle.seek(cursor)
            while True:
                line_start = handle.tell()
                line = handle.readline()
                if not line:
                    cursor = handle.tell()
                    break
                if not line.endswith(b"\n"):
                    cursor = line_start
                    break
                cursor = handle.tell()
                try:
                    item = json.loads(line.decode("utf-8"))
                except (UnicodeDecodeError, ValueError):
                    continue
                if not isinstance(item, dict):
                    continue
                if task_id is not None and str(item.get("task_id") or "") != task_id:
                    continue
                records.append(item)
    except OSError:
        return [], max(0, int(offset))
    return records, cursor


def build_reasoning_snapshot(
    records: Iterable[dict[str, Any]],
) -> dict[str, Any]:
    calls: OrderedDict[str, dict[str, Any]] = OrderedDict()
    for record in records:
        call_id = str(record.get("call_id") or "")
        if not call_id:
            continue
        call = calls.setdefault(
            call_id,
            {
                "call_id": call_id,
                "call_index": _nonnegative_int(record.get("call_index")),
                "agent_id": "",
                "stage": "",
                "thinking_enabled": None,
                "reasoning_effort": None,
                "status": "running",
                "started_at": None,
                "finished_at": None,
                "text": "",
                "characters": 0,
                "sha256": None,
                "redacted": False,
                "_parts": [],
            },
        )
        record_type = str(record.get("type") or "")
        if record_type == "start":
            call["agent_id"] = str(record.get("agent_id") or "")
            call["stage"] = str(record.get("stage") or "")
            call["thinking_enabled"] = record.get("thinking_enabled")
            call["reasoning_effort"] = record.get("reasoning_effort")
            call["started_at"] = record.get("timestamp")
        elif record_type == "delta":
            text = record.get("text")
            if isinstance(text, str):
                call["_parts"].append(text)
        elif record_type == "end":
            call["status"] = str(record.get("status") or "completed")
            call["finished_at"] = record.get("timestamp")
            call["characters"] = _nonnegative_int(record.get("characters"))
            call["sha256"] = record.get("sha256")
            call["redacted"] = bool(record.get("redacted"))
            call["error_type"] = record.get("error_type")

    result: list[dict[str, Any]] = []
    for call in calls.values():
        parts = call.pop("_parts")
        call["text"] = "".join(parts)
        if not call["characters"]:
            call["characters"] = len(call["text"])
        result.append(call)
    return {
        "calls": result,
        "has_records": bool(result),
        "has_reasoning": any(bool(call["text"]) for call in result),
        "running": any(call["status"] == "running" for call in result),
        "characters": sum(int(call["characters"]) for call in result),
    }


def _nonnegative_int(value: Any) -> int:
    if isinstance(value, bool):
        return 0
    try:
        return max(0, int(value))
    except (TypeError, ValueError):
        return 0
