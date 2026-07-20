from __future__ import annotations

import pytest

from mathproofmesh.memory import TypedMemory
from mathproofmesh.schemas import (
    ClaimStatus,
    EvidenceType,
    MemoryTier,
    MessageType,
)

from v07_helpers import make_fact, make_message, make_v07_config


def test_memory_keeps_numerical_evidence_out_of_fact_tier(tmp_path) -> None:
    memory = TypedMemory(None, make_v07_config(tmp_path / "runs"))
    heuristic = make_message(
        message_id="heuristic",
        route_id="route-a",
        agent_id="author-a",
        evidence_type=EvidenceType.NUMERICAL_HEURISTIC,
        memory_tier=MemoryTier.INSIGHT,
        status=ClaimStatus.UNCERTAIN,
        confidence=0.9,
    )
    memory.add_insight(heuristic)
    with pytest.raises(ValueError, match="cannot be promoted"):
        memory.promote(heuristic.message_id, referee_agent_id="independent-referee")
    assert memory.facts == []
    assert memory.insights == [heuristic]


def test_counterexample_invalidates_fact_and_transitive_dependents(tmp_path) -> None:
    memory = TypedMemory(None, make_v07_config(tmp_path / "runs"))
    base = make_fact(message_id="base", statement="claim p")
    memory.add_fact(base, referee_agent_id="referee-a")
    dependent = make_fact(
        message_id="dependent",
        route_id="route-b",
        agent_id="author-b",
        statement="claim q",
        dependencies=[base.message_id],
    )
    memory.add_fact(dependent, referee_agent_id="referee-b")
    counterexample = make_message(
        message_id="counterexample",
        route_id="route-c",
        agent_id="author-c",
        statement="claim p",
        conclusion="a concrete witness refutes claim p",
        message_type=MessageType.COUNTEREXAMPLE,
        evidence_type=EvidenceType.COUNTEREXAMPLE,
        memory_tier=MemoryTier.NEGATIVE,
        status=ClaimStatus.REJECTED,
        confidence=1.0,
    )
    memory.add_negative(counterexample)
    invalidated = memory.apply_counterexample(counterexample)
    assert set(invalidated) == {"base", "dependent"}
    assert memory.facts == []
    assert {item.message_id for item in memory.negatives} >= {"base", "dependent"}


def test_duplicate_provenance_does_not_upgrade_an_insight(tmp_path) -> None:
    memory = TypedMemory(None, make_v07_config(tmp_path / "runs"))
    first = make_message(message_id="first", route_id="route-a", agent_id="author-a")
    second = make_message(message_id="second", route_id="route-a", agent_id="author-b")
    memory.add_message(first)
    merged = memory.add_message(second)
    assert merged.message_id == "first"
    assert memory.export_state()["provenance"]["first"] == ["author-a", "author-b"]
    assert memory.export_state()["tiers"]["first"] == "insight"
