from __future__ import annotations

import json
import os
import re
import tempfile
from pathlib import Path
from typing import Any

from pydantic import BaseModel

from .schemas import (
    CheckpointStatus,
    EvidenceRef,
    ProofCheckpoint,
    stable_hash,
    utc_now_iso,
)


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
        for subdir in [
            "raw",
            "structured",
            "reports",
            "checkpoints",
            "tools",
            "prompts",
            "deltas",
        ]:
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
        content = json.dumps(
            _to_jsonable(value), ensure_ascii=False, indent=2, sort_keys=True
        )
        self._atomic_write(path, content)
        return self.ref(path)

    def write_text(
        self, subdir: str, name: str, content: str, suffix: str = ".txt"
    ) -> str:
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
            serialized = json.dumps(
                content, ensure_ascii=False, indent=2, sort_keys=True
            )
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

    def commit_proof_checkpoint(self, checkpoint: ProofCheckpoint) -> str:
        """Persist one verified checkpoint and atomically advance the path pointer.

        The latest pointer is monotone: a new checkpoint must extend the current
        checkpoint for the same immutable problem/path/strategy by exactly one segment.
        Recommitting the same checkpoint is idempotent.
        """
        if checkpoint.status not in {
            CheckpointStatus.VERIFIED,
            CheckpointStatus.COMMITTED,
        }:
            raise ValueError(
                "only verified or committed proof checkpoints may become resume points"
            )
        path_dir = self.root / "checkpoints" / "proof" / _safe_name(checkpoint.path_id)
        path_dir.mkdir(parents=True, exist_ok=True)
        latest_path = path_dir / "latest.json"
        checkpoint_path = (
            path_dir
            / f"{checkpoint.segment_index:04d}_{_safe_name(checkpoint.checkpoint_id)}.json"
        )

        if latest_path.exists():
            with latest_path.open("r", encoding="utf-8") as handle:
                latest = ProofCheckpoint.model_validate(json.load(handle))
            if latest.checkpoint_id == checkpoint.checkpoint_id:
                return self.ref(checkpoint_path)
            if checkpoint.parent_checkpoint_id != latest.checkpoint_id:
                raise ValueError(
                    "proof checkpoint does not extend the current latest checkpoint"
                )
            if checkpoint.segment_index != latest.segment_index + 1:
                raise ValueError(
                    "proof checkpoint segment index must advance exactly by one"
                )
            if (
                checkpoint.problem_hash != latest.problem_hash
                or checkpoint.strategy_id != latest.strategy_id
                or checkpoint.path_id != latest.path_id
            ):
                raise ValueError("proof checkpoint changed immutable path identity")
        elif (
            checkpoint.segment_index != 0 or checkpoint.parent_checkpoint_id is not None
        ):
            raise ValueError(
                "the first proof checkpoint for a path must be a genesis checkpoint"
            )

        content = json.dumps(
            _to_jsonable(checkpoint), ensure_ascii=False, indent=2, sort_keys=True
        )
        self._atomic_write(checkpoint_path, content)
        self._atomic_write(latest_path, content)
        self.append_event(
            "proof_checkpoint_committed",
            {
                "checkpoint_id": checkpoint.checkpoint_id,
                "parent_checkpoint_id": checkpoint.parent_checkpoint_id,
                "path_id": checkpoint.path_id,
                "strategy_id": checkpoint.strategy_id,
                "segment_index": checkpoint.segment_index,
                "source_agent_id": checkpoint.source_agent_id,
                "proof_complete": checkpoint.proof_complete,
                "content_hash": checkpoint.content_hash,
            },
        )
        return self.ref(checkpoint_path)

    def load_latest_proof_checkpoint(self, path_id: str) -> ProofCheckpoint | None:
        latest_path = (
            self.root / "checkpoints" / "proof" / _safe_name(path_id) / "latest.json"
        )
        if not latest_path.exists():
            return None
        with latest_path.open("r", encoding="utf-8") as f:
            return ProofCheckpoint.model_validate(json.load(f))

    def list_proof_checkpoints(
        self, path_id: str | None = None
    ) -> list[ProofCheckpoint]:
        proof_root = self.root / "checkpoints" / "proof"
        if not proof_root.exists():
            return []
        roots = (
            [proof_root / _safe_name(path_id)]
            if path_id
            else [p for p in proof_root.iterdir() if p.is_dir()]
        )
        checkpoints: list[ProofCheckpoint] = []
        for root in roots:
            if not root.exists():
                continue
            for path in sorted(root.glob("[0-9][0-9][0-9][0-9]_*.json")):
                try:
                    with path.open("r", encoding="utf-8") as f:
                        checkpoints.append(ProofCheckpoint.model_validate(json.load(f)))
                except (OSError, ValueError, json.JSONDecodeError):
                    continue
        return sorted(
            checkpoints,
            key=lambda item: (item.path_id, item.segment_index, item.created_at),
        )

    def save_proof_delta(
        self, delta_id: str, value: Any, *, rejected: bool = False
    ) -> str:
        suffix = "rejected" if rejected else "candidate"
        return self.write_json("deltas", f"{suffix}_{delta_id}", value)

    def latest_stage_checkpoint(self) -> tuple[str, dict[str, Any]] | None:
        candidates = [
            path
            for path in (self.root / "checkpoints").glob("*_latest.json")
            if path.is_file()
        ]
        if not candidates:
            return None
        latest = max(candidates, key=lambda path: path.stat().st_mtime_ns)
        with latest.open("r", encoding="utf-8") as f:
            payload = json.load(f)
        stage = latest.name.removesuffix("_latest.json")
        if not isinstance(payload, dict):
            raise ValueError(f"stage checkpoint {latest} must contain a JSON object")
        return stage, payload

    def read_named_json(self, subdir: str, name: str) -> Any:
        path = self.root / _safe_name(subdir) / f"{_safe_name(name)}.json"
        with path.open("r", encoding="utf-8") as f:
            return json.load(f)

    def has_named_json(self, subdir: str, name: str) -> bool:
        return (self.root / _safe_name(subdir) / f"{_safe_name(name)}.json").exists()

    def save_prompt(self, stage: str, agent_id: str, system: str, user: str) -> str:
        """Persist an immutable prompt artifact instead of overwriting repeated stages."""
        content = f"# SYSTEM\n{system}\n\n# USER\n{user}\n"
        evidence = self.write_content_addressed(
            "prompts",
            content,
            suffix=".txt",
            summary=f"Prompt for {stage} sent to {agent_id}",
        )
        return evidence.artifact_ref

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
