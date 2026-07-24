from __future__ import annotations

from mathproofmesh.proof_graph.store import ProofGraphStore
from mathproofmesh.schemas import FormalCertificateRef, ObligationKind, ProofObligation
from mathproofmesh.verification.formal_microcert import (
    CompilerFeedbackInterpreter,
    FormalizationCandidateSelector,
)

from v07_helpers import PROBLEM_HASH, make_v07_config


def test_formal_selector_chooses_shared_high_centrality_obligation(tmp_path) -> None:
    high = ProofObligation(
        obligation_id="high",
        problem_hash=PROBLEM_HASH,
        route_ids=["a", "b", "c"],
        kind=ObligationKind.LEMMA,
        statement="shared bottleneck",
        normalized_statement="shared bottleneck",
        centrality=0.95,
    )
    low = ProofObligation(
        obligation_id="low",
        problem_hash=PROBLEM_HASH,
        route_ids=["a"],
        kind=ObligationKind.SUBGOAL,
        statement="routine local step",
        normalized_statement="routine local step",
        centrality=0.05,
    )
    assert FormalizationCandidateSelector().select([low, high]) == [high]


def test_formal_failure_creates_task_without_refuting_natural_claim(tmp_path) -> None:
    config = make_v07_config(tmp_path / "runs")
    graph = ProofGraphStore(config, problem_hash=PROBLEM_HASH)
    source = graph.add_obligation(
        ProofObligation(
            obligation_id="source",
            problem_hash=PROBLEM_HASH,
            route_ids=["a", "b"],
            kind=ObligationKind.LEMMA,
            statement="shared statement",
            normalized_statement="shared statement",
            centrality=0.9,
        )
    )
    selector = FormalizationCandidateSelector()
    packet = selector.packet(source)
    interpreter = CompilerFeedbackInterpreter()
    pending = interpreter.unavailable(packet, backend_name="lean")
    assert pending.status == "pending"
    task = interpreter.apply_failure(
        packet,
        FormalCertificateRef(
            packet_id=packet.packet_id,
            backend="lean",
            status="failed",
            statement_hash="bad-encoding",
            diagnostics=["type mismatch"],
        ),
        graph,
    )
    assert task is not None and task.kind == ObligationKind.FORMALIZATION_TASK
    assert graph.get_obligation("source").status == "open"
