from __future__ import annotations

from pathlib import Path

import pytest

from mathproofmesh.config import TopologyConfig
from mathproofmesh.mock_demo import build_demo_config
from mathproofmesh.store import ArtifactStore


@pytest.fixture
def demo_config(tmp_path: Path):
    config = build_demo_config(str(tmp_path / "runs"))
    config.topology = TopologyConfig(
        neighbor_k=2,
        max_context_chars=8000,
        max_verified_claims_per_context=8,
    )
    return config


@pytest.fixture
def artifact_store(tmp_path: Path) -> ArtifactStore:
    return ArtifactStore(tmp_path / "runs", "test-run")
