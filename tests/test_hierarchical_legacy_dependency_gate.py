from __future__ import annotations

import pytest

from mathproofmesh.config import SystemConfig
from mathproofmesh.memory import LemmaMemory, TypedMemory
from mathproofmesh.schemas import ClaimCard, ClaimStatus
from mathproofmesh.store import ArtifactStore

from v07_helpers import make_fact, make_v07_config


def _verified_legacy_memory(store: ArtifactStore) -> LemmaMemory:
    memory = LemmaMemory(store)
    memory.add_many(
        [
            ClaimCard(
                claim_id="legacy-claim",
                statement="A legacy-only lemma.",
                conclusion="A legacy-only lemma.",
                status=ClaimStatus.VERIFIED,
                verification_confidence=0.99,
            )
        ]
    )
    return memory


def test_hierarchical_typed_fact_cannot_depend_on_legacy_claim(tmp_path) -> None:
    hierarchical = make_v07_config(tmp_path / "hierarchical-runs")
    store = ArtifactStore(tmp_path / "hierarchical-runs", "dependency-gate")
    legacy = _verified_legacy_memory(store)
    typed = TypedMemory(store, hierarchical, lemma_memory=legacy)
    fact = make_fact(
        message_id="typed-dependent-fact",
        statement="A typed fact that improperly cites legacy memory.",
        dependencies=["legacy-claim"],
    )

    assert not typed.dependencies_resolved(["legacy-claim"])
    with pytest.raises(ValueError, match="dependencies are unresolved"):
        typed.add_fact(fact, referee_agent_id="independent-referee")


def test_legacy_typed_memory_keeps_legacy_dependency_compatibility(tmp_path) -> None:
    hierarchical = make_v07_config(tmp_path / "legacy-runs")
    payload = hierarchical.model_dump(mode="python")
    payload["topology"]["mode"] = "legacy_sparse"
    legacy_config = SystemConfig.model_validate(payload)
    store = ArtifactStore(tmp_path / "legacy-runs", "dependency-compatibility")
    legacy = _verified_legacy_memory(store)
    typed = TypedMemory(store, legacy_config, lemma_memory=legacy)

    assert typed.dependencies_resolved(["legacy-claim"])
