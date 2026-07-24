from __future__ import annotations

import json
import os
import re
import tempfile
from collections import OrderedDict
from datetime import UTC, datetime
from pathlib import Path
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict
from send2trash import send2trash

from ..reasoning_trace import (
    TRACE_FILE_NAME,
    build_reasoning_snapshot,
    read_reasoning_records,
)
from .paths import DesktopPaths
from .settings import DesktopProfile


RunLifecycle = Literal[
    "queued",
    "running",
    "awaiting_confirmation",
    "completed",
    "failed",
    "cancelled",
    "interrupted",
]
_RUN_ID_PATTERN = re.compile(r"[A-Za-z0-9_.-]{1,160}")
_TASK_ID_PATTERN = re.compile(r"[A-Za-z0-9_.:-]{1,240}")
_REQUEST_HASH_PATTERN = re.compile(r"[a-fA-F0-9]{64}")
_DISPLAY_SECRET_PATTERNS = (
    re.compile(r"\bsk-[A-Za-z0-9_-]{10,}\b"),
    re.compile(r"(?i)\bBearer\s+[A-Za-z0-9._~+/=-]{12,}"),
    re.compile(r"(?i)(api[_ -]?key\s*[:=]\s*)[^\s,;\"']+"),
)
_SENSITIVE_FIELD_PATTERN = re.compile(
    r"(?i)^(?:api[_-]?key|authorization|password|secret|"
    r"access[_-]?token|refresh[_-]?token)$"
)


class DesktopRunMetadata(BaseModel):
    model_config = ConfigDict(extra="ignore", validate_assignment=True)

    run_id: str
    profile: DesktopProfile
    lifecycle: RunLifecycle
    created_at: str
    updated_at: str
    mode: Literal["solve", "resume"] = "solve"
    error: str | None = None
    process_id: int | None = None


def utc_now() -> str:
    return datetime.now(UTC).isoformat()


def validate_run_id(run_id: str) -> str:
    normalized = run_id.strip()
    if _RUN_ID_PATTERN.fullmatch(normalized) is None:
        raise ValueError(
            "run_id 只能包含英文字母、数字、点、下划线和连字符，最长 160 个字符"
        )
    return normalized


def validate_task_id(task_id: str) -> str:
    normalized = task_id.strip()
    if _TASK_ID_PATTERN.fullmatch(normalized) is None:
        raise ValueError("invalid activity task id")
    return normalized


def redact_display_payload(
    value: Any,
    *,
    depth: int = 0,
    text_limit: int = 120_000,
) -> Any:
    """Redact credentials while preserving readable multiline execution records."""
    if depth >= 8:
        return "[TRUNCATED]"
    if isinstance(value, str):
        text = value
        for pattern in _DISPLAY_SECRET_PATTERNS:
            replacement = (
                r"\1[REDACTED]"
                if pattern.pattern.lower().startswith("(?i)(api")
                else "[REDACTED]"
            )
            text = pattern.sub(replacement, text)
        if len(text) > text_limit:
            return text[: max(0, text_limit - 14)].rstrip() + "\n[TRUNCATED]"
        return text
    if isinstance(value, dict):
        redacted: dict[str, Any] = {}
        for index, (key, item) in enumerate(value.items()):
            if index >= 300:
                redacted["_truncated"] = True
                break
            safe_key = str(key)[:240]
            redacted[safe_key] = (
                "[REDACTED]"
                if _SENSITIVE_FIELD_PATTERN.fullmatch(safe_key)
                else redact_display_payload(
                    item,
                    depth=depth + 1,
                    text_limit=text_limit,
                )
            )
        return redacted
    if isinstance(value, (list, tuple)):
        items = [
            redact_display_payload(
                item,
                depth=depth + 1,
                text_limit=text_limit,
            )
            for item in value[:300]
        ]
        if len(value) > 300:
            items.append("[TRUNCATED]")
        return items
    if value is None or isinstance(value, (bool, int, float)):
        return value
    return redact_display_payload(
        str(value),
        depth=depth + 1,
        text_limit=text_limit,
    )


def collapse_activity_payloads(
    events: list[dict[str, object]],
) -> list[dict[str, object]]:
    """Return one topology-ready snapshot per logical task in first-seen order."""
    latest: OrderedDict[str, dict[str, object]] = OrderedDict()
    task_order: list[str] = []
    parents: dict[str, str | None] = {}
    started: dict[str, int] = {}
    initial_types: dict[str, str] = {}
    for index, event in enumerate(events):
        task_id = str(
            event.get("task_id") or f"sequence:{event.get('sequence', index)}:{index}"
        )
        is_new = task_id not in latest
        if is_new:
            task_order.append(task_id)
            initial_types[task_id] = str(
                event.get("initial_event_type") or event.get("event_type") or "activity"
            )
            started[task_id] = _activity_elapsed(
                event.get("started_elapsed_ms"),
                fallback=_activity_elapsed(event.get("elapsed_ms"), fallback=0),
            )
            explicit_parent = str(event.get("parent_task_id") or "").strip() or None
            parents[task_id] = explicit_parent or _active_activity_parent(
                task_id,
                task_order,
                latest,
                initial_types,
            )

        payload = dict(event)
        payload["task_id"] = task_id
        payload["parent_task_id"] = str(
            payload.get("parent_task_id") or ""
        ).strip() or parents.get(task_id)
        payload["started_elapsed_ms"] = started[task_id]
        payload["initial_event_type"] = initial_types[task_id]
        latest[task_id] = payload
    return list(latest.values())


def _activity_elapsed(value: object, *, fallback: int) -> int:
    if isinstance(value, bool):
        return fallback
    if isinstance(value, (int, float)):
        return max(0, int(value))
    return fallback


def _active_activity_parent(
    task_id: str,
    task_order: list[str],
    latest: OrderedDict[str, dict[str, object]],
    initial_types: dict[str, str],
) -> str | None:
    for candidate_id in reversed(task_order):
        if candidate_id == task_id:
            continue
        candidate = latest.get(candidate_id)
        if candidate is None or str(candidate.get("status")) != "running":
            continue
        if initial_types.get(candidate_id) == "agent_call":
            continue
        return candidate_id
    return None


class RunRepository:
    def __init__(self, paths: DesktopPaths) -> None:
        self.paths = paths

    def run_directory(self, run_id: str) -> Path:
        safe_id = validate_run_id(run_id)
        path = (self.paths.runs / safe_id).resolve()
        path.relative_to(self.paths.runs)
        return path

    def metadata_path(self, run_id: str) -> Path:
        return self.run_directory(run_id) / "desktop_run.json"

    def move_to_recycle_bin(self, run_id: str) -> Path:
        directory = self.run_directory(run_id)
        if not directory.exists():
            raise FileNotFoundError(run_id)
        directory.relative_to(self.paths.runs.resolve())
        send2trash(str(directory))
        if directory.exists():
            raise OSError(f"运行目录未能移入回收站：{run_id}")
        return directory

    def write_metadata(self, metadata: DesktopRunMetadata) -> None:
        path = self.metadata_path(metadata.run_id)
        path.parent.mkdir(parents=True, exist_ok=True)
        content = json.dumps(
            metadata.model_dump(mode="json"),
            ensure_ascii=False,
            indent=2,
            sort_keys=True,
        )
        fd, temp_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=str(path.parent))
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as handle:
                handle.write(content)
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(temp_name, path)
        finally:
            if os.path.exists(temp_name):
                os.unlink(temp_name)

    def read_metadata(self, run_id: str) -> DesktopRunMetadata | None:
        path = self.metadata_path(run_id)
        if not path.exists():
            return None
        try:
            return DesktopRunMetadata.model_validate_json(
                path.read_text(encoding="utf-8")
            )
        except (OSError, ValueError):
            return None

    def list_runs(self) -> list[dict[str, object]]:
        entries: list[dict[str, object]] = []
        if not self.paths.runs.exists():
            return entries
        for directory in self.paths.runs.iterdir():
            if (
                not directory.is_dir()
                or _RUN_ID_PATTERN.fullmatch(directory.name) is None
            ):
                continue
            entry = self.summary(directory.name)
            if entry is not None:
                entries.append(entry)
        entries.sort(
            key=lambda item: str(item.get("updated_at") or ""),
            reverse=True,
        )
        return entries

    def summary(self, run_id: str) -> dict[str, object] | None:
        directory = self.run_directory(run_id)
        if not directory.exists():
            return None
        metadata = self.read_metadata(run_id)
        problem = self._read_json(directory / "structured" / "problem_contract.json")
        result = self._read_json(directory / "structured" / "run_result.json")

        lifecycle: RunLifecycle = metadata.lifecycle if metadata else "interrupted"
        if result is not None:
            execution_status = str(result.get("execution_status", ""))
            result_status = str(result.get("status", ""))
            if execution_status == "failed":
                lifecycle = "failed"
            elif (
                execution_status == "network_interrupted"
                or result_status == "paused_external_failure"
            ):
                lifecycle = "interrupted"
            else:
                lifecycle = "completed"
        elif lifecycle in {"queued", "running", "awaiting_confirmation"} and (
            metadata is None or metadata.process_id != os.getpid()
        ):
            lifecycle = "interrupted"

        statement = ""
        if problem:
            statement = str(
                problem.get("original_statement")
                or problem.get("exact_statement")
                or ""
            )
        title = next(
            (line.strip() for line in statement.splitlines() if line.strip()), ""
        )
        if not title:
            title = run_id
        if len(title) > 90:
            title = title[:89].rstrip() + "…"

        total_usage = result.get("total_usage", {}) if result else {}
        updated = (
            metadata.updated_at
            if metadata
            else datetime.fromtimestamp(directory.stat().st_mtime, UTC).isoformat()
        )
        return {
            "run_id": run_id,
            "title": title,
            "profile": metadata.profile if metadata else "formal",
            "lifecycle": lifecycle,
            "mode": metadata.mode if metadata else "solve",
            "created_at": (
                metadata.created_at
                if metadata
                else datetime.fromtimestamp(directory.stat().st_ctime, UTC).isoformat()
            ),
            "updated_at": updated,
            "status": result.get("status") if result else None,
            "task_status": result.get("task_status") if result else None,
            "math_status": result.get("math_status") if result else None,
            "execution_status": result.get("execution_status") if result else None,
            "total_calls": int(result.get("total_calls", 0)) if result else 0,
            "total_tokens": int(total_usage.get("total_tokens", 0)),
            "estimated_cost_usd": float(total_usage.get("estimated_cost_usd", 0.0)),
            "resumable": problem is not None
            and lifecycle
            in {
                "interrupted",
                "cancelled",
                "failed",
            },
        }

    def detail(self, run_id: str) -> dict[str, object]:
        directory = self.run_directory(run_id)
        if not directory.exists():
            raise FileNotFoundError(run_id)
        summary = self.summary(run_id)
        problem = (
            self._read_json(directory / "structured" / "problem_contract.json") or {}
        )
        result = self._read_json(directory / "structured" / "run_result.json") or {}
        final_proof = result.get("final_proof") or {}
        progress_report = result.get("research_progress_report") or {}
        report_path = directory / "reports" / "run_report.md"
        report = (
            report_path.read_text(encoding="utf-8", errors="replace")
            if report_path.exists()
            else ""
        )
        return {
            "summary": summary,
            "problem": str(
                problem.get("original_statement")
                or problem.get("exact_statement")
                or ""
            ),
            "canonical_problem": str(
                problem.get("canonical_statement")
                or problem.get("exact_statement")
                or ""
            ),
            "interpretation": {
                "source": problem.get("interpretation_source", "original"),
                "confidence": problem.get("interpretation_confidence", 1.0),
                "reasons": problem.get("interpretation_reasons", []),
                "goal_hash": problem.get("goal_hash") or problem.get("integrity_hash"),
            },
            "result": {
                "status": result.get("status"),
                "task_status": result.get("task_status"),
                "deliverable_assessments": result.get("deliverable_assessments", []),
                "math_status": result.get("math_status"),
                "execution_status": result.get("execution_status"),
                "summary": result.get("summary"),
                "answer": final_proof.get("answer"),
                "confidence": final_proof.get("confidence"),
                "research_progress": progress_report.get("summary"),
                "total_calls": result.get("total_calls", 0),
                "total_usage": result.get("total_usage", {}),
                "resumed": result.get("resumed", False),
                "resumed_from_checkpoint_id": result.get("resumed_from_checkpoint_id"),
            },
            "report": report,
            "activity": self.read_activity(run_id, limit=None),
        }

    def read_activity(
        self,
        run_id: str,
        *,
        limit: int | None,
    ) -> list[dict[str, object]]:
        path = self.run_directory(run_id) / "activity.jsonl"
        if not path.exists():
            return []
        events: list[dict[str, object]] = []
        try:
            with path.open("r", encoding="utf-8", errors="replace") as handle:
                for line in handle:
                    try:
                        item = json.loads(line)
                    except ValueError:
                        continue
                    if isinstance(item, dict):
                        events.append(item)
        except OSError:
            return []
        logical_events = collapse_activity_payloads(events)
        return logical_events[-limit:] if limit is not None else logical_events

    def computation_snapshot(
        self,
        run_id: str,
        task_id: str,
    ) -> dict[str, Any]:
        """Build a bounded, redacted execution view for one computation node."""
        safe_task_id = validate_task_id(task_id)
        directory = self.run_directory(run_id)
        if not directory.exists():
            raise FileNotFoundError(run_id)
        activity = next(
            (
                event
                for event in self.read_activity(run_id, limit=None)
                if str(event.get("task_id") or "") == safe_task_id
            ),
            None,
        )
        if activity is None or not self._is_computation_activity(activity):
            raise FileNotFoundError(safe_task_id)

        metrics = activity.get("metrics")
        metrics = metrics if isinstance(metrics, dict) else {}
        request_hash = str(metrics.get("request_hash") or "").strip()
        if _REQUEST_HASH_PATTERN.fullmatch(request_hash) is None:
            request_hash = ""
        experiment_dir = (
            directory / "experiments" / request_hash if request_hash else None
        )
        if experiment_dir is not None:
            experiment_dir = experiment_dir.resolve()
            experiment_dir.relative_to((directory / "experiments").resolve())

        spec = self._read_json(experiment_dir / "spec.json") if experiment_dir else None
        artifact_decision = (
            self._read_json(experiment_dir / "decision.json")
            if experiment_dir
            else None
        )
        decision = artifact_decision
        if metrics.get("decision"):
            artifact_decision = (
                artifact_decision if isinstance(artifact_decision, dict) else {}
            )
            decision = {
                "experiment_id": metrics.get("experiment_id")
                or artifact_decision.get("experiment_id"),
                "request_hash": request_hash or None,
                "decision": metrics.get("decision"),
                "reason": metrics.get("decision_reason")
                or artifact_decision.get("reason"),
                "rule_id": metrics.get("rule_id") or artifact_decision.get("rule_id"),
                "cache_hit": metrics.get(
                    "cache_hit", artifact_decision.get("cache_hit", False)
                ),
                "contract_repair_status": metrics.get("contract_repair_status")
                or artifact_decision.get("contract_repair_status"),
                "original_request_hash": metrics.get("original_request_hash")
                or artifact_decision.get("original_request_hash"),
                "contract_repair_reason": metrics.get("contract_repair_reason")
                or artifact_decision.get("contract_repair_reason"),
            }
        contract_repair = (
            self._read_json(experiment_dir / "contract_repair.json")
            if experiment_dir
            else None
        )
        program = (
            self._read_json(experiment_dir / "program.json") if experiment_dir else None
        )
        execution = (
            self._read_json(experiment_dir / "execution.json")
            if experiment_dir
            else None
        )
        result = (
            self._read_json(experiment_dir / "result.json") if experiment_dir else None
        )
        certificate = (
            self._read_json(experiment_dir / "computation_certificate.json")
            if experiment_dir
            else None
        )
        phase = str(metrics.get("phase") or activity.get("status") or "waiting")
        process = (
            execution.get("output", {}).get("process")
            if isinstance(execution, dict) and isinstance(execution.get("output"), dict)
            else None
        )
        runtime = {
            "status": activity.get("status"),
            "phase": phase,
            "runtime_seconds": (
                execution.get("runtime_seconds")
                if isinstance(execution, dict)
                else metrics.get("runtime_seconds")
            ),
            "outcome": (
                result.get("outcome")
                if isinstance(result, dict)
                else metrics.get("outcome")
            ),
            "evidence_strength": (
                result.get("evidence_strength")
                if isinstance(result, dict)
                else metrics.get("evidence_strength")
            ),
            "error": (
                execution.get("error")
                if isinstance(execution, dict)
                else metrics.get("error")
            ),
            "tool_name": execution.get("tool_name")
            if isinstance(execution, dict)
            else None,
            "tool_version": execution.get("tool_version")
            if isinstance(execution, dict)
            else None,
            "process": process,
        }
        audit = {
            "request_hash": request_hash or None,
            "program_hash": (
                execution.get("program_hash")
                if isinstance(execution, dict)
                else metrics.get("program_hash")
            ),
            "input_hash": execution.get("input_hash")
            if isinstance(execution, dict)
            else None,
            "output_hash": execution.get("output_hash")
            if isinstance(execution, dict)
            else None,
            "environment_hash": execution.get("environment_hash")
            if isinstance(execution, dict)
            else None,
            "result_hash": (
                execution.get("result_hash")
                if isinstance(execution, dict)
                else metrics.get("result_hash")
            ),
        }
        payload = {
            "task_id": safe_task_id,
            "activity": activity,
            "running": str(activity.get("status") or "") == "running",
            "phase": phase,
            "request_hash": request_hash or None,
            "experiment_id": (
                spec.get("experiment_id")
                if isinstance(spec, dict)
                else metrics.get("experiment_id")
            ),
            "method": (
                spec.get("method") if isinstance(spec, dict) else metrics.get("method")
            ),
            "target_claim": spec.get("target_claim")
            if isinstance(spec, dict)
            else None,
            "decision": decision,
            "contract_repair": contract_repair,
            "program": program,
            "input": (
                execution.get("input")
                if isinstance(execution, dict)
                else spec.get("arguments")
                if isinstance(spec, dict)
                else None
            ),
            "output": execution.get("output") if isinstance(execution, dict) else None,
            "runtime": runtime,
            "environment": execution.get("environment")
            if isinstance(execution, dict)
            else None,
            "certificate": certificate,
            "audit": audit,
        }
        return redact_display_payload(payload)

    @staticmethod
    def _is_computation_activity(activity: dict[str, object]) -> bool:
        initial_type = str(
            activity.get("initial_event_type") or activity.get("event_type") or ""
        )
        task_id = str(activity.get("task_id") or "")
        return task_id.startswith("computation:") or initial_type in {
            "python_experiment",
            "computation_experiment",
            "computation_decision",
            "experiment_completed",
        }

    def reasoning_path(self, run_id: str) -> Path:
        return self.run_directory(run_id) / "reports" / TRACE_FILE_NAME

    def reasoning_snapshot(
        self,
        run_id: str,
        task_id: str,
    ) -> dict[str, Any]:
        safe_task_id = validate_task_id(task_id)
        directory = self.run_directory(run_id)
        if not directory.exists():
            raise FileNotFoundError(run_id)
        activity = next(
            (
                event
                for event in self.read_activity(run_id, limit=None)
                if str(event.get("task_id") or "") == safe_task_id
            ),
            None,
        )
        records, cursor = read_reasoning_records(
            self.reasoning_path(run_id),
            task_id=safe_task_id,
        )
        if activity is None and not records:
            raise FileNotFoundError(safe_task_id)
        snapshot = build_reasoning_snapshot(records)
        summary = self.summary(run_id) or {}
        lifecycle = str(summary.get("lifecycle") or "interrupted")
        run_active = lifecycle in {"queued", "running"}
        event_type = str(
            (activity or {}).get("initial_event_type")
            or (activity or {}).get("event_type")
            or ""
        )
        recordable = event_type == "agent_call" or bool(records)
        node_running = str((activity or {}).get("status") or "") == "running"
        calls = snapshot["calls"]
        latest_call_status = str(calls[-1].get("status") or "") if calls else ""
        if snapshot["running"]:
            trace_state = "running"
        elif recordable and run_active and node_running and not snapshot["has_records"]:
            trace_state = "waiting"
        elif snapshot["has_reasoning"]:
            trace_state = (
                latest_call_status
                if latest_call_status in {"failed", "cancelled"}
                else "completed"
            )
        elif snapshot["has_records"]:
            trace_state = (
                latest_call_status
                if latest_call_status in {"failed", "cancelled"}
                else "no_reasoning"
            )
        elif recordable and not run_active:
            trace_state = "legacy_unavailable"
        else:
            trace_state = "unavailable"
        return {
            "task_id": safe_task_id,
            "activity": activity or {},
            "recordable": recordable,
            "node_running": node_running,
            "run_active": run_active,
            "run_lifecycle": lifecycle,
            "trace_state": trace_state,
            "reasoning_authority": {
                "status": "unverified",
                "label": "未验证推理",
                "premise_eligible": False,
                "description": (
                    "这是模型原始推理记录，不是检查点、Broker Fact 或独立验证结论。"
                ),
            },
            "archive": f"reports/{TRACE_FILE_NAME}",
            "cursor": cursor,
            **snapshot,
        }

    def read_reasoning_updates(
        self,
        run_id: str,
        task_id: str,
        *,
        offset: int,
    ) -> tuple[list[dict[str, Any]], int]:
        safe_task_id = validate_task_id(task_id)
        directory = self.run_directory(run_id)
        if not directory.exists():
            raise FileNotFoundError(run_id)
        return read_reasoning_records(
            self.reasoning_path(run_id),
            task_id=safe_task_id,
            offset=offset,
        )

    @staticmethod
    def _read_json(path: Path) -> dict[str, object] | None:
        if not path.exists():
            return None
        try:
            value = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return None
        return value if isinstance(value, dict) else None
