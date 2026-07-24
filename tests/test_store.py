from __future__ import annotations

import json
import os
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import pytest

import mathproofmesh.store as store_module
from mathproofmesh.store import ArtifactStore


def test_repeated_stage_prompts_are_immutable_content_addressed_artifacts(
    tmp_path: Path,
) -> None:
    store = ArtifactStore(tmp_path / "runs", "prompt-artifacts")

    first = store.save_prompt("proof_continuation", "agent-a", "system", "first")
    second = store.save_prompt("proof_continuation", "agent-a", "system", "second")
    duplicate = store.save_prompt("proof_continuation", "agent-a", "system", "first")

    assert first != second
    assert duplicate == first
    assert store.resolve(first).read_text(encoding="utf-8").endswith("# USER\nfirst\n")
    assert (
        store.resolve(second).read_text(encoding="utf-8").endswith("# USER\nsecond\n")
    )


def test_atomic_write_retries_transient_windows_sharing_violation(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    store = ArtifactStore(tmp_path / "runs", "replace-retry")
    real_replace = os.replace
    attempts = 0

    def transient_replace(source: str, destination: Path) -> None:
        nonlocal attempts
        attempts += 1
        if attempts < 3:
            raise PermissionError(13, "transient sharing violation")
        real_replace(source, destination)

    monkeypatch.setattr(store_module.os, "replace", transient_replace)
    monkeypatch.setattr(store_module.time, "sleep", lambda _delay: None)

    ref = store.write_json("structured", "typed_memory", {"messages": ["m1"]})

    assert attempts == 3
    assert store.read_json(ref) == {"messages": ["m1"]}
    assert list((store.root / "structured").glob(".typed_memory.json.*")) == []


def test_atomic_write_preserves_previous_snapshot_after_permanent_denial(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    store = ArtifactStore(tmp_path / "runs", "replace-denied")
    ref = store.write_json("structured", "typed_memory", {"generation": 1})

    def denied_replace(_source: str, _destination: Path) -> None:
        raise PermissionError(13, "persistent denial")

    monkeypatch.setattr(store_module.os, "replace", denied_replace)
    monkeypatch.setattr(store_module.time, "sleep", lambda _delay: None)

    with pytest.raises(PermissionError):
        store.write_json("structured", "typed_memory", {"generation": 2})

    assert store.read_json(ref) == {"generation": 1}
    assert list((store.root / "structured").glob(".typed_memory.json.*")) == []


def test_atomic_writes_are_serialized_within_one_run_store(tmp_path: Path) -> None:
    store = ArtifactStore(tmp_path / "runs", "concurrent-writes")

    def write_snapshot(index: int) -> None:
        store.write_json("structured", "typed_memory", {"generation": index})

    with ThreadPoolExecutor(max_workers=8) as executor:
        list(executor.map(write_snapshot, range(32)))

    path = store.root / "structured" / "typed_memory.json"
    payload = json.loads(path.read_text(encoding="utf-8"))
    assert payload["generation"] in range(32)
    assert list(path.parent.glob(".typed_memory.json.*")) == []
