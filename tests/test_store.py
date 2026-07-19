from __future__ import annotations

from pathlib import Path

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
