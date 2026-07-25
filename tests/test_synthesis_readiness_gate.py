from __future__ import annotations

from mathproofmesh.config import SynthesisReadinessControlConfig
from mathproofmesh.proof_control.gates import SynthesisReadinessGate
from mathproofmesh.proof_control.models import (
    ClaimGoalLink,
    GateVerdict,
    GoalRelation,
    InferenceRiskRecord,
    InferenceRiskType,
    ScopeRelation,
)
from mathproofmesh.proof_graph.contradictions import ContradictionRecord
from mathproofmesh.proof_graph.store import ProofGraphStore
from mathproofmesh.schemas import ObligationKind, ProofObligation

from v07_helpers import PROBLEM_HASH


def _graph(*, open_dependency: bool) -> ProofGraphStore:
    graph = ProofGraphStore(problem_hash=PROBLEM_HASH)
    dependencies: list[str] = []
    if open_dependency:
        dependency = graph.add_obligation(
            ProofObligation(
                obligation_id="core-bridge",
                problem_hash=PROBLEM_HASH,
                route_ids=["route-a"],
                kind=ObligationKind.LEMMA,
                statement="Prove the core bridge.",
                normalized_statement="prove the core bridge.",
                centrality=0.9,
            )
        )
        dependencies.append(dependency.obligation_id)
    graph.add_obligation(
        ProofObligation(
            obligation_id="main-goal",
            problem_hash=PROBLEM_HASH,
            route_ids=["route-a"],
            kind=ObligationKind.MAIN_GOAL,
            statement="Prove the theorem.",
            normalized_statement="prove the theorem.",
            dependency_ids=dependencies,
        )
    )
    return graph


def test_active_readiness_blocks_open_core_scope_and_conflict() -> None:
    graph = _graph(open_dependency=True)
    risk = InferenceRiskRecord(
        risk_id="risk-scope",
        subject_id="claim-a",
        risk_type=InferenceRiskType.EVENTUAL_TO_GLOBAL,
        explanation="eventual scope cannot close a global obligation",
        confidence=1.0,
    )
    conflict = ContradictionRecord(
        contradiction_id="conflict-a",
        message_ids=["m1", "m2"],
        route_ids=["route-a"],
        normalized_statement="bridge claim",
        reason="incompatible conclusions",
        centrality=0.9,
    )
    record = SynthesisReadinessGate(
        SynthesisReadinessControlConfig(mode="active")
    ).evaluate(
        graph,
        inference_risks=[risk],
        conflicts=[conflict],
    )

    assert record.verdict == GateVerdict.BLOCK
    assert record.open_core_obligation_ids == ["core-bridge"]
    assert record.open_scope_risk_ids == ["risk-scope"]
    assert record.unresolved_conflict_ids == ["conflict-a"]


def test_necessary_only_link_and_unadmitted_fact_block_synthesis() -> None:
    graph = _graph(open_dependency=False)
    link = ClaimGoalLink(
        link_id="link-necessary",
        subject_id="claim-a",
        subject_kind="claim",
        target_obligation_id="main-goal",
        relation=GoalRelation.NECESSARY_ONLY,
        scope_relation=ScopeRelation.SAME,
        alignment_confidence=1.0,
    )
    record = SynthesisReadinessGate(
        SynthesisReadinessControlConfig(mode="active")
    ).evaluate(
        graph,
        goal_links=[link],
        candidate_fact_ids=["fact-not-admitted"],
        broker_admitted_fact_ids=[],
    )

    assert record.verdict == GateVerdict.BLOCK
    assert record.invalid_goal_link_ids == ["link-necessary"]
    assert any("not admitted" in item for item in record.reasons)


def test_shadow_records_and_ready_graph_passes_active() -> None:
    blocked_graph = _graph(open_dependency=True)
    shadow = SynthesisReadinessGate(
        SynthesisReadinessControlConfig(mode="shadow")
    ).evaluate(blocked_graph)
    ready = SynthesisReadinessGate(
        SynthesisReadinessControlConfig(mode="active")
    ).evaluate(_graph(open_dependency=False))

    assert shadow.verdict == GateVerdict.SHADOW_BLOCK
    assert SynthesisReadinessGate.blocks_synthesis(shadow) is False
    assert ready.verdict == GateVerdict.PASS
