from __future__ import annotations

from mathproofmesh.context_policy import ContextPurpose, build_admissible_fact_context
from mathproofmesh.memory import LemmaMemory
from mathproofmesh.mock_demo import build_demo_config
from mathproofmesh.schemas import ClaimCard, ClaimStatus
from mathproofmesh.store import ArtifactStore

from v07_helpers import make_v07_config


MARKER = "REJECTED_ROUTE_LOCAL_CLAIM_DO_NOT_SHARE"


def _legacy_memory(store: ArtifactStore) -> LemmaMemory:
    memory = LemmaMemory(store)
    memory.add_many(
        [
            ClaimCard(
                claim_id="legacy-dependency",
                statement="A legacy dependency.",
                conclusion="A legacy dependency.",
                status=ClaimStatus.VERIFIED,
                verification_confidence=0.95,
            ),
            ClaimCard(
                claim_id="legacy-root",
                statement=MARKER,
                conclusion=MARKER,
                dependencies=["legacy-dependency"],
                status=ClaimStatus.VERIFIED,
                verification_confidence=0.99,
            ),
        ]
    )
    return memory


def test_legacy_sparse_keeps_verified_claim_context_and_dependency_closure(
    tmp_path,
) -> None:
    config = build_demo_config(str(tmp_path / "legacy-runs"))
    store = ArtifactStore(tmp_path / "legacy-runs", "legacy-context")
    legacy_memory = _legacy_memory(store)

    context = build_admissible_fact_context(
        config,
        legacy_memory=legacy_memory,
        typed_memory=None,
        message_broker=None,
        query=MARKER,
        max_chars=10000,
        max_items=10,
        purpose=ContextPurpose.SYNTHESIS,
    )

    assert [item["claim_id"] for item in context] == [
        "legacy-dependency",
        "legacy-root",
    ]
    assert MARKER in {item["statement"] for item in context}


def test_hierarchical_context_without_typed_runtime_fails_closed(tmp_path) -> None:
    config = make_v07_config(tmp_path / "hierarchical-runs")
    store = ArtifactStore(tmp_path / "hierarchical-runs", "closed-context")
    legacy_memory = _legacy_memory(store)

    context = build_admissible_fact_context(
        config,
        legacy_memory=legacy_memory,
        typed_memory=None,
        message_broker=None,
        query=MARKER,
        max_chars=10000,
        max_items=10,
        purpose=ContextPurpose.SYNTHESIS,
    )

    assert context == []
