from __future__ import annotations

import pytest

from mathproofmesh.proof_graph.store import ProofGraphStore
from mathproofmesh.schemas import (
    GraphEdgeType,
    ObligationKind,
    ProofGraphEdge,
    ProofObligation,
)

from v07_helpers import PROBLEM_HASH, make_fact, make_v07_config


def _obligation(identifier: str, route: str, *, dependency_ids=None) -> ProofObligation:
    return ProofObligation(
        obligation_id=identifier,
        problem_hash=PROBLEM_HASH,
        route_ids=[route],
        kind=ObligationKind.SUBGOAL,
        statement=identifier,
        normalized_statement=identifier,
        dependency_ids=dependency_ids or [],
        priority=0.8,
        centrality=0.7,
    )


def test_fact_closes_obligation_and_refutation_reopens_dependents(tmp_path) -> None:
    graph = ProofGraphStore(
        make_v07_config(tmp_path / "runs"), problem_hash=PROBLEM_HASH
    )
    base = graph.add_obligation(_obligation("base", "route-a"))
    dependent = graph.add_obligation(
        _obligation("dependent", "route-a", dependency_ids=[base.obligation_id])
    )
    fact = make_fact(message_id="proof", statement="base")
    graph.add_claim_node(fact)
    graph.close_obligation(base.obligation_id, fact.message_id, confidence=0.95)
    graph.close_obligation(dependent.obligation_id, fact.message_id, confidence=0.95)
    assert graph.proof_debt("route-a") == 0.0

    graph.refute_obligation(base.obligation_id)
    assert graph.get_obligation("base").status == "refuted"
    assert graph.get_obligation("dependent").status == "open"


def test_dependency_cycle_and_writes_after_freeze_are_rejected(tmp_path) -> None:
    graph = ProofGraphStore(
        make_v07_config(tmp_path / "runs"), problem_hash=PROBLEM_HASH
    )
    graph.add_obligation(_obligation("a", "route-a"))
    graph.add_obligation(_obligation("b", "route-a", dependency_ids=["a"]))
    with pytest.raises(ValueError, match="cycle"):
        graph.add_edge(
            ProofGraphEdge(
                source_id="a", target_id="b", edge_type=GraphEdgeType.DEPENDS_ON
            )
        )
    graph.freeze()
    with pytest.raises(RuntimeError, match="frozen"):
        graph.add_obligation(_obligation("c", "route-a"))
