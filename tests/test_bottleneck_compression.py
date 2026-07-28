from __future__ import annotations

from mathproofmesh.proof_control.bottleneck import BottleneckCompressor
from mathproofmesh.proof_control.models import (
    IndexScope,
    ScopeSignature,
)
from mathproofmesh.proof_graph.store import ProofGraphStore
from mathproofmesh.schemas import ObligationKind, ProofObligation

from v07_helpers import PROBLEM_HASH, make_fact


def _obligation(index: int) -> ProofObligation:
    return ProofObligation(
        obligation_id=f"obligation-{index}",
        problem_hash=PROBLEM_HASH,
        route_ids=[f"route-{index}"],
        kind=ObligationKind.LEMMA,
        statement="Establish the shared bridge B.",
        normalized_statement="establish the shared bridge b.",
        assumptions=["H"],
        priority=0.5 + index / 100,
        centrality=0.5 + index / 100,
        first_error_fingerprint="same-first-error",
    )


def test_semantic_compression_preserves_all_original_nodes() -> None:
    graph = ProofGraphStore(problem_hash=PROBLEM_HASH)
    for index in range(10):
        graph.add_obligation(_obligation(index))
    compressor = BottleneckCompressor()

    groups = compressor.deterministic_clusters(graph)
    clusters = compressor.materialize_clusters(graph, groups)

    assert len(graph.obligations) == 10
    assert len(graph.edges) == 0
    assert len(clusters) == 1
    assert len(clusters[0].member_obligation_ids) == 10
    assert clusters[0].canonical_obligation_id == "obligation-9"


def test_scope_mismatch_prevents_semantic_cluster() -> None:
    graph = ProofGraphStore(problem_hash=PROBLEM_HASH)
    left = graph.add_obligation(_obligation(1))
    right = graph.add_obligation(_obligation(2))
    scopes = {
        left.obligation_id: ScopeSignature(
            subject_id=left.obligation_id,
            index_scope=IndexScope.ALL,
            normalization_confidence=1.0,
        ),
        right.obligation_id: ScopeSignature(
            subject_id=right.obligation_id,
            index_scope=IndexScope.EVENTUAL,
            normalization_confidence=1.0,
        ),
    }

    assert (
        BottleneckCompressor().deterministic_clusters(graph, scope_signatures=scopes)
        == []
    )


def test_cluster_resolves_without_deleting_nodes() -> None:
    graph = ProofGraphStore(problem_hash=PROBLEM_HASH)
    for index in range(2):
        graph.add_obligation(_obligation(index))
    compressor = BottleneckCompressor()
    cluster = compressor.materialize_clusters(
        graph, compressor.deterministic_clusters(graph)
    )[0]
    fact = make_fact(
        message_id="fact-cluster",
        route_id="route-0",
        agent_id="agent-0",
        statement="Establish the shared bridge B.",
    )
    graph.add_claim_node(fact)
    for obligation in graph.obligations:
        graph.close_obligation(obligation.obligation_id, fact.message_id)

    compressor.refresh_cluster_status(graph, cluster)

    assert cluster.status == "resolved"
    assert len(graph.obligations) == 2
