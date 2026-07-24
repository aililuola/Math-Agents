from __future__ import annotations

import json
import os
import time
from collections.abc import Iterable
from contextlib import contextmanager
from pathlib import Path
from typing import Any

from ..config import InspirationConfig
from ..schemas import (
    InspirationOutcome,
    NegativeAnalogyRecord,
    VerifiedExperienceRecord,
    stable_hash,
)


class CrossRunLearningStore:
    """Project-local, git-ignored learning store with deterministic admission."""

    def __init__(
        self,
        config: InspirationConfig,
        *,
        project_root: str | Path,
    ) -> None:
        self.config = config
        root = Path(project_root).resolve()
        configured = Path(config.cross_run_learning_path)
        self.root = (
            configured if configured.is_absolute() else root / configured
        ).resolve()
        self.diagnostics: list[str] = []
        if self.enabled and self.root != root and root not in self.root.parents:
            raise ValueError("cross-run learning path must stay inside project_root")

    @property
    def enabled(self) -> bool:
        return self.config.cross_run_learning_enabled

    def load_experiences(self) -> list[VerifiedExperienceRecord]:
        results: list[VerifiedExperienceRecord] = []
        for item in self._read("verified_experiences.json"):
            if not isinstance(item, dict) or item.get("verified") is not True:
                continue
            try:
                results.append(VerifiedExperienceRecord.model_validate(item))
            except (TypeError, ValueError) as exc:
                self.diagnostics.append(f"invalid verified experience skipped: {exc}")
        return results

    def load_negatives(self) -> list[NegativeAnalogyRecord]:
        results: list[NegativeAnalogyRecord] = []
        for item in self._read("negative_analogies.json"):
            if not isinstance(item, dict) or item.get("negative") is not True:
                continue
            try:
                results.append(NegativeAnalogyRecord.model_validate(item))
            except (TypeError, ValueError) as exc:
                self.diagnostics.append(f"invalid negative analogy skipped: {exc}")
        return results

    def load_outcomes(self) -> list[InspirationOutcome]:
        results: list[InspirationOutcome] = []
        for item in self._read("outcomes.json"):
            if not isinstance(item, dict) or not isinstance(item.get("outcome"), dict):
                continue
            try:
                results.append(InspirationOutcome.model_validate(item["outcome"]))
            except (TypeError, ValueError) as exc:
                self.diagnostics.append(f"invalid inspiration outcome skipped: {exc}")
        return results

    def persist(
        self,
        *,
        experiences: Iterable[VerifiedExperienceRecord],
        negatives: Iterable[NegativeAnalogyRecord],
        outcomes: Iterable[InspirationOutcome],
        run_verified: bool,
    ) -> dict[str, int]:
        if not self.enabled:
            return {"experiences": 0, "negatives": 0, "outcomes": 0}
        admitted_experiences = [
            item
            for item in experiences
            if run_verified
            and item.verified
            and (
                not self.config.cross_run_require_final_citation
                or item.cited_by_final_proof
            )
        ]
        admitted_negatives = list(negatives)
        admitted_outcomes = [
            item for item in outcomes if item.materialization_action is not None
        ]
        self.root.mkdir(parents=True, exist_ok=True)
        with self._lock():
            experience_count = self._merge_models(
                "verified_experiences.json",
                admitted_experiences,
                key=lambda item: item.record_id,
                limit=self.config.cross_run_max_experiences,
            )
            negative_count = self._merge_models(
                "negative_analogies.json",
                admitted_negatives,
                key=lambda item: item.record_id,
                limit=self.config.cross_run_max_negative_analogies,
            )
            outcome_count = self._merge_outcomes(admitted_outcomes)
        return {
            "experiences": experience_count,
            "negatives": negative_count,
            "outcomes": outcome_count,
        }

    def _merge_models(
        self,
        name: str,
        values: Iterable[Any],
        *,
        key: Any,
        limit: int,
    ) -> int:
        current = [item for item in self._read(name) if isinstance(item, dict)]
        by_id = {str(item.get("record_id", "")): item for item in current}
        before = len(by_id)
        for value in values:
            by_id[str(key(value))] = value.model_dump(mode="json")
        ordered = list(by_id.values())[-limit:] if limit else []
        self._write(name, ordered)
        return max(0, len(by_id) - before)

    def _merge_outcomes(self, values: Iterable[InspirationOutcome]) -> int:
        current = [
            item for item in self._read("outcomes.json") if isinstance(item, dict)
        ]
        by_id = {str(item.get("archive_id", "")): item for item in current}
        before = len(by_id)
        for outcome in values:
            archive_id = (
                "outcome_"
                + stable_hash((outcome.problem_hash, outcome.proposal_id))[:20]
            )
            by_id[archive_id] = {
                "archive_id": archive_id,
                "outcome": outcome.model_dump(mode="json"),
            }
        limit = self.config.cross_run_max_outcomes
        ordered = list(by_id.values())[-limit:] if limit else []
        self._write("outcomes.json", ordered)
        return max(0, len(by_id) - before)

    def _read(self, name: str) -> list[Any]:
        if not self.enabled:
            return []
        path = self.root / name
        if not path.exists():
            return []
        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError, UnicodeError) as exc:
            self.diagnostics.append(f"cross-run learning read failed for {name}: {exc}")
            return []
        return payload if isinstance(payload, list) else []

    def _write(self, name: str, payload: list[dict[str, Any]]) -> None:
        path = self.root / name
        temporary = path.with_name(f".{path.name}.{os.getpid()}.tmp")
        temporary.write_text(
            json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        os.replace(temporary, path)

    @contextmanager
    def _lock(self):
        lock_path = self.root / ".write.lock"
        descriptor: int | None = None
        deadline = time.monotonic() + 5.0
        while descriptor is None:
            try:
                descriptor = os.open(
                    lock_path,
                    os.O_CREAT | os.O_EXCL | os.O_WRONLY,
                )
            except FileExistsError:
                if time.monotonic() >= deadline:
                    raise TimeoutError("cross-run learning store is busy")
                time.sleep(0.05)
        try:
            os.write(descriptor, str(os.getpid()).encode("ascii"))
            yield
        finally:
            os.close(descriptor)
            lock_path.unlink(missing_ok=True)


__all__ = ["CrossRunLearningStore"]
