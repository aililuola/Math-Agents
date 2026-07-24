from __future__ import annotations

import threading
from typing import Any

from ..schemas import (
    ComputationDecision,
    ComputationMethod,
    ExperimentOutcome,
    ExperimentProgram,
    ExperimentResult,
    ExperimentSpec,
)
from ..store import ArtifactStore


class ExperimentCache:
    def __init__(self, store: ArtifactStore) -> None:
        self.store = store
        self._identity_lock = threading.RLock()
        self._alias_to_request_hashes: dict[str, list[str]] = {}
        self._execution_results: dict[str, str] = {}
        self._load_identity_index()

    def _load_identity_index(self) -> None:
        if self.store.has_named_json("experiments", "computation_identity_index"):
            payload = self.store.read_named_json(
                "experiments", "computation_identity_index"
            )
            self._alias_to_request_hashes = {
                str(alias): [str(item) for item in request_hashes]
                for alias, request_hashes in payload.get(
                    "alias_to_request_hashes", {}
                ).items()
                if isinstance(request_hashes, list)
            }
            self._execution_results = {
                str(execution_hash): str(request_hash)
                for execution_hash, request_hash in payload.get(
                    "execution_results", {}
                ).items()
            }
            return

        changed = False
        for raw_result in self.store.list_experiment_results():
            try:
                result = ExperimentResult.model_validate(raw_result)
                spec = self.load_spec(result.request_hash)
            except (FileNotFoundError, OSError, ValueError):
                continue
            self._register_aliases_in_memory(
                result.request_hash,
                result.request_hash,
                result.experiment_id,
                spec.experiment_id,
            )
            if self._result_is_reusable(result):
                self._execution_results[spec.execution_hash] = result.request_hash
            changed = True
        if changed:
            self._persist_identity_index()

    def _persist_identity_index(self) -> None:
        self.store.write_json(
            "experiments",
            "computation_identity_index",
            {
                "alias_to_request_hashes": self._alias_to_request_hashes,
                "execution_results": self._execution_results,
            },
        )

    def _register_aliases_in_memory(
        self, request_hash: str, *aliases: str | None
    ) -> bool:
        changed = False
        for raw_alias in aliases:
            alias = str(raw_alias or "").strip()
            if not alias:
                continue
            hashes = self._alias_to_request_hashes.setdefault(alias, [])
            if request_hash not in hashes:
                hashes.append(request_hash)
                changed = True
        return changed

    def register_aliases(self, request_hash: str, *aliases: str | None) -> None:
        """Bind model IDs, tool request IDs, and hashes to one durable result."""

        with self._identity_lock:
            if self._register_aliases_in_memory(request_hash, request_hash, *aliases):
                self._persist_identity_index()

    def resolve_identifier(self, identifier: str) -> str | None:
        """Resolve an evidence alias only when it identifies one computation."""

        with self._identity_lock:
            hashes = self._alias_to_request_hashes.get(str(identifier), [])
            if len(hashes) == 1:
                return hashes[0]
            if self.has_result(str(identifier)):
                return str(identifier)
            completed = [item for item in hashes if self.has_result(item)]
            return completed[0] if len(completed) == 1 else None

    def aliases_for(self, request_hash: str) -> set[str]:
        with self._identity_lock:
            return {
                alias
                for alias, hashes in self._alias_to_request_hashes.items()
                if request_hash in hashes
            }

    def canonical_request_hash(self, spec: ExperimentSpec) -> str | None:
        if spec.method == ComputationMethod.SANDBOXED_PYTHON:
            return None
        with self._identity_lock:
            request_hash = self._execution_results.get(spec.execution_hash)
        if request_hash and self.has_result(request_hash):
            return request_hash
        return None

    def get_equivalent(self, spec: ExperimentSpec) -> ExperimentResult | None:
        request_hash = self.canonical_request_hash(spec)
        if request_hash is None:
            return None
        result = self.get(request_hash)
        if result is None or not self._result_is_reusable(result):
            return None
        return result

    @staticmethod
    def _result_is_reusable(result: ExperimentResult) -> bool:
        return not result.error and result.outcome not in {
            ExperimentOutcome.ERROR,
            ExperimentOutcome.INCONCLUSIVE,
        }

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
        ref = self.store.write_experiment_artifact(spec.request_hash, "spec", spec)
        self.register_aliases(spec.request_hash, spec.experiment_id)
        return ref

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
        ref = self.store.write_experiment_artifact(
            result.request_hash, "result", result
        )
        with self._identity_lock:
            changed = self._register_aliases_in_memory(
                result.request_hash,
                result.request_hash,
                result.experiment_id,
            )
            try:
                spec = self.load_spec(result.request_hash)
            except (FileNotFoundError, OSError, ValueError):
                spec = None
            if (
                spec is not None
                and self._result_is_reusable(result)
                and self._execution_results.get(spec.execution_hash)
                != result.request_hash
            ):
                self._execution_results[spec.execution_hash] = result.request_hash
                changed = True
            if changed:
                self._persist_identity_index()
        return ref

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
