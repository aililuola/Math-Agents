from __future__ import annotations

from mathproofmesh.proof_graph.contradictions import ContradictionBroker
from mathproofmesh.proof_graph.store import ProofGraphStore
from mathproofmesh.schemas import ClaimStatus, EvidenceType, MemoryTier, MessageType

from v07_helpers import PROBLEM_HASH, make_fact, make_message, make_v07_config


def test_different_scopes_are_not_misclassified_as_contradictory(tmp_path) -> None:
    config = make_v07_config(tmp_path / "runs")
    graph = ProofGraphStore(config, problem_hash=PROBLEM_HASH)
    left = make_fact(message_id="left", statement="x is positive")
    right = make_message(
        message_id="right",
        route_id="route-b",
        agent_id="author-b",
        statement="x is positive",
        conclusion="not (x is positive)",
        scope_limitations=["only when x=0"],
    )
    assert (
        ContradictionBroker(config, graph).detect([left, right], current_round=1) == []
    )


def test_exact_counterexample_resolves_conflict_without_a_majority(tmp_path) -> None:
    config = make_v07_config(tmp_path / "runs")
    graph = ProofGraphStore(config, problem_hash=PROBLEM_HASH)
    claim = make_fact(message_id="claim", statement="claim p")
    counterexample = make_message(
        message_id="counterexample",
        route_id="route-b",
        agent_id="author-b",
        statement="claim p",
        conclusion="witness n=2 refutes claim p",
        message_type=MessageType.COUNTEREXAMPLE,
        evidence_type=EvidenceType.COUNTEREXAMPLE,
        memory_tier=MemoryTier.NEGATIVE,
        status=ClaimStatus.REJECTED,
        confidence=1.0,
    )
    records = ContradictionBroker(config, graph).detect(
        [claim, counterexample], current_round=1
    )
    assert len(records) == 1
    assert records[0].status == "resolved"
    assert records[0].resolution_message_id == "counterexample"
