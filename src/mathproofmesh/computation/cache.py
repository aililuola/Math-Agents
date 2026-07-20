from __future__ import annotations

import threading
from typing import Any

from ..schemas import (
    ComputationDecision,
    ExperimentProgram,
    ExperimentResult,
    ExperimentSpec,
)
from ..store import ArtifactStore


class ExperimentCache:
    def __init__(self, store: ArtifactStore) -> None:
        self.store = store

    def get(self, request_hash: str) -> ExperimentResult | None:
        if not self.store.has_experiment_artifact(request_hash, "result"):
            return None
        result = ExperimentResult.model_validate(
            self.store.read_experiment_artifact(request_hash, "result")
        )
        result.cached = True
        return result

    def has_result(self, request_hash: str) -> bool:
        return self.store.has_experiment_artifact(request_hash, "result")

    def save_spec(self, spec: ExperimentSpec) -> str:
        return self.store.write_experiment_artifact(spec.request_hash, "spec", spec)

    def load_spec(self, request_hash: str) -> ExperimentSpec:
        return ExperimentSpec.model_validate(
            self.store.read_experiment_artifact(request_hash, "spec")
        )

    def save_decision(self, decision: ComputationDecision) -> str:
        return self.store.write_experiment_artifact(
            decision.request_hash, "decision", decision
        )

    def save_program(
        self, request_hash: str, program: ExperimentProgram
    ) -> tuple[str, str]:
        json_ref = self.store.write_experiment_artifact(
            request_hash, "program", program
        )
        source_ref = self.store.write_experiment_artifact(
            request_hash, "program.py", program.source, text=True
        )
        return json_ref, source_ref

    def load_program(self, request_hash: str) -> ExperimentProgram | None:
        if not self.store.has_experiment_artifact(request_hash, "program"):
            return None
        program = ExperimentProgram.model_validate(
            self.store.read_experiment_artifact(request_hash, "program")
        )
        source = self.store.read_experiment_text_artifact(request_hash, "program.py")
        if source != program.source:
            raise ValueError(
                "program.py does not match the hashed ExperimentProgram source"
            )
        return program

    def save_execution(self, request_hash: str, payload: dict[str, Any]) -> str:
        return self.store.write_experiment_artifact(request_hash, "execution", payload)

    def load_execution(self, request_hash: str) -> dict[str, Any]:
        payload = self.store.read_experiment_artifact(request_hash, "execution")
        if not isinstance(payload, dict):
            raise ValueError("execution artifact must contain a JSON object")
        return payload

    def save_result(self, result: ExperimentResult) -> str:
        return self.store.write_experiment_artifact(
            result.request_hash, "result", result
        )

    def save_evidence(self, result: ExperimentResult) -> str:
        return self.store.write_experiment_artifact(
            result.request_hash,
            "evidence",
            {
                "request_hash": result.request_hash,
                "result_hash": result.result_hash,
                "evidence_strength": result.evidence_strength,
                "outcome": result.outcome,
                "independently_verified": result.independently_verified,
                "artifact_refs": result.artifact_refs,
            },
        )

    def load_evidence(self, request_hash: str) -> dict[str, Any]:
        payload = self.store.read_experiment_artifact(request_hash, "evidence")
        if not isinstance(payload, dict):
            raise ValueError("evidence artifact must contain a JSON object")
        return payload


class ComputationLedger:
    """Durable, lock-protected experiment quotas shared by concurrent paths."""

    def __init__(self, store: ArtifactStore) -> None:
        self.store = store
        self._lock = threading.RLock()
        self.path_counts: dict[str, int] = {}
        self.path_request_hashes: dict[str, list[str]] = {}
        self.total_cpu_seconds = 0.0
        self.request_hashes: list[str] = []
        if store.has_named_json("experiments", "ledger"):
            payload = store.read_named_json("experiments", "ledger")
            self.path_counts = {
                str(key): int(value)
                for key, value in payload.get("path_counts", {}).items()
            }
            self.path_request_hashes = {
                str(key): [str(item) for item in value]
                for key, value in payload.get("path_request_hashes", {}).items()
                if isinstance(value, list)
            }
            self.total_cpu_seconds = float(payload.get("total_cpu_seconds", 0.0))
            self.request_hashes = [
                str(value) for value in payload.get("request_hashes", [])
            ]

    def count_for_path(self, path_id: str) -> int:
        with self._lock:
            return self.path_counts.get(path_id, 0)

    def hashes_for_path(self, path_id: str) -> list[str]:
        with self._lock:
            return list(self.path_request_hashes.get(path_id, []))

    def _record_path_use(self, path_id: str, request_hash: str) -> bool:
        hashes = self.path_request_hashes.setdefault(path_id, [])
        if request_hash in hashes:
            return False
        hashes.append(request_hash)
        self.path_counts[path_id] = self.path_counts.get(path_id, 0) + 1
        return True

    def record_result(self, path_id: str, result: ExperimentResult) -> None:
        with self._lock:
            changed = self._record_path_use(path_id, result.request_hash)
            if result.request_hash not in self.request_hashes:
                self.request_hashes.append(result.request_hash)
                self.total_cpu_seconds += result.runtime_seconds
                changed = True
            if changed:
                self._persist()

    def record_cache_use(self, path_id: str, result: ExperimentResult) -> None:
        with self._lock:
            if self._record_path_use(path_id, result.request_hash):
                self._persist()

    def _persist(self) -> None:
        self.store.write_json(
            "experiments",
            "ledger",
            {
                "path_counts": self.path_counts,
                "path_request_hashes": self.path_request_hashes,
                "total_cpu_seconds": self.total_cpu_seconds,
                "request_hashes": self.request_hashes,
            },
        )
