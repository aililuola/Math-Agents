from __future__ import annotations

import pytest

from mathproofmesh.proof_graph.bridges import BridgeBroker
from mathproofmesh.proof_graph.store import ProofGraphStore
from mathproofmesh.schemas import ObligationKind, ProofObligation

from v07_helpers import PROBLEM_HASH, make_fact, make_message, make_v07_config


def test_shared_obligation_creates_one_bounded_bridge_task(tmp_path) -> None:
    config = make_v07_config(tmp_path / "runs")
    graph = ProofGraphStore(config, problem_hash=PROBLEM_HASH)
    for identifier, route in (("a", "route-a"), ("b", "route-b")):
        graph.add_obligation(
            ProofObligation(
                obligation_id=identifier,
                problem_hash=PROBLEM_HASH,
                route_ids=[route],
                kind=ObligationKind.LEMMA,
                statement="common bridge lemma",
                normalized_statement="common bridge lemma",
                priority=0.8,
                centrality=0.8,
            )
        )
    broker = BridgeBroker(config, graph)
    tasks = broker.detect(current_round=1)
    assert len(tasks) == 1
    assert set(tasks[0].route_ids) == {"route-a", "route-b"}
    assert broker.detect(current_round=1) == []

    with pytest.raises(ValueError, match="verification"):
        broker.accept_verified_result(
            tasks[0].task_id,
            make_message(
                message_id="unverified",
                route_id="route-a",
                agent_id="author-a",
                statement="common bridge lemma",
            ),
        )
    closed = broker.accept_verified_result(
        tasks[0].task_id,
        make_fact(message_id="bridge-proof", statement="common bridge lemma"),
    )
    assert set(closed) == {"a", "b"}
