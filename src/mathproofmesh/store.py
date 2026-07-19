from __future__ import annotations

import json
import os
import re
import tempfile
from pathlib import Path
from typing import Any

from pydantic import BaseModel

from .schemas import EvidenceRef, stable_hash, utc_now_iso


def _safe_name(value: str) -> str:
    value = re.sub(r"[^A-Za-z0-9_.-]+", "_", value.strip())
    return value[:160] or "artifact"


def _to_jsonable(value: Any) -> Any:
    if isinstance(value, BaseModel):
        return _to_jsonable(value.model_dump(mode="json"))
    if isinstance(value, Path):
        return str(value)
    if isinstance(value, dict):
        return {str(key): _to_jsonable(item) for key, item in value.items()}
    if isinstance(value, (list, tuple, set)):
        return [_to_jsonable(item) for item in value]
    return value


class ArtifactStore:
    """Run-scoped, append-friendly artifact storage with content hashes and atomic writes."""

    def __init__(self, run_root: str | Path, run_id: str) -> None:
        self.run_id = _safe_name(run_id)
        self.root = Path(run_root).expanduser().resolve() / self.run_id
        for subdir in ["raw", "structured", "reports", "checkpoints", "tools", "prompts"]:
            (self.root / subdir).mkdir(parents=True, exist_ok=True)
        self.events_path = self.root / "events.jsonl"

    def _atomic_write(self, path: Path, content: str) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        fd, tmp_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=str(path.parent))
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as f:
                f.write(content)
                f.flush()
                os.fsync(f.fileno())
            os.replace(tmp_name, path)
        finally:
            if os.path.exists(tmp_name):
                os.unlink(tmp_name)

    def write_json(self, subdir: str, name: str, value: Any) -> str:
        path = self.root / _safe_name(subdir) / f"{_safe_name(name)}.json"
        content = json.dumps(_to_jsonable(value), ensure_ascii=False, indent=2, sort_keys=True)
        self._atomic_write(path, content)
        return self.ref(path)

    def write_text(self, subdir: str, name: str, content: str, suffix: str = ".txt") -> str:
        suffix = suffix if suffix.startswith(".") else f".{suffix}"
        path = self.root / _safe_name(subdir) / f"{_safe_name(name)}{suffix}"
        self._atomic_write(path, content)
        return self.ref(path)

    def write_content_addressed(
        self,
        subdir: str,
        content: str | dict[str, Any] | list[Any],
        *,
        suffix: str = ".json",
        summary: str = "",
        section: str | None = None,
    ) -> EvidenceRef:
        if isinstance(content, str):
            serialized = content
        else:
            serialized = json.dumps(content, ensure_ascii=False, indent=2, sort_keys=True)
        digest = stable_hash(serialized)
        suffix = suffix if suffix.startswith(".") else f".{suffix}"
        path = self.root / _safe_name(subdir) / f"{digest}{suffix}"
        if not path.exists():
            self._atomic_write(path, serialized)
        return EvidenceRef(
            artifact_ref=self.ref(path),
            section=section,
            content_hash=digest,
            summary=summary,
        )

    def append_event(self, event_type: str, payload: Any) -> None:
        event = {
            "timestamp": utc_now_iso(),
            "run_id": self.run_id,
            "event_type": event_type,
            "payload": _to_jsonable(payload),
        }
        with self.events_path.open("a", encoding="utf-8") as f:
            f.write(json.dumps(event, ensure_ascii=False, sort_keys=True) + "\n")

    def checkpoint(self, stage: str, state: Any) -> str:
        return self.write_json("checkpoints", f"{stage}_latest", state)

    def save_prompt(self, stage: str, agent_id: str, system: str, user: str) -> str:
        content = f"# SYSTEM\n{system}\n\n# USER\n{user}\n"
        return self.write_text("prompts", f"{stage}_{agent_id}", content, suffix=".txt")

    def ref(self, path: str | Path) -> str:
        path = Path(path).resolve()
        relative = path.relative_to(self.root)
        return f"artifact://{relative.as_posix()}"

    def resolve(self, ref: str) -> Path:
        if not ref.startswith("artifact://"):
            raise ValueError(f"not an artifact ref: {ref}")
        relative = ref.removeprefix("artifact://")
        path = (self.root / relative).resolve()
        path.relative_to(self.root)  # path traversal guard
        return path

    def read_json(self, ref: str) -> Any:
        with self.resolve(ref).open("r", encoding="utf-8") as f:
            return json.load(f)
