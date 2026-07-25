from __future__ import annotations

from mathproofmesh.proof_control.models import (
    CriticalAssumption,
    InferenceRiskRecord,
    InferenceRiskType,
    ProofRole,
)
from mathproofmesh.proof_control.proof_roles import core_proof_debt
from mathproofmesh.proof_graph.store import ProofGraphStore
from mathproofmesh.schemas import ClaimStatus, ObligationKind, ProofObligation

from v07_helpers import PROBLEM_HASH, make_fact


def _obligation(
    obligation_id: str,
    *,
    kind: ObligationKind,
    dependencies: list[str] | None = None,
    priority: float = 1.0,
) -> ProofObligation:
    return ProofObligation(
        obligation_id=obligation_id,
        problem_hash=PROBLEM_HASH,
        route_ids=["route-a"],
        kind=kind,
        statement=obligation_id,
        normalized_statement=obligation_id,
        dependency_ids=dependencies or [],
        priority=priority,
        centrality=0.5,
    )


def test_core_closure_excludes_unrelated_auxiliary_obligations() -> None:
    graph = ProofGraphStore(problem_hash=PROBLEM_HASH)
    graph.add_obligation(_obligation("core-bridge", kind=ObligationKind.LEMMA))
    graph.add_obligation(_obligation("aux", kind=ObligationKind.LEMMA))
    graph.add_obligation(
        _obligation(
            "main",
            kind=ObligationKind.MAIN_GOAL,
            dependencies=["core-bridge"],
        )
    )

    assert graph.main_goal_obligation_ids() == ["main"]
    assert graph.core_dependency_closure() == {"main", "core-bridge"}
    assert {item.obligation_id for item in graph.core_open_obligations()} == {
        "main",
        "core-bridge",
    }


def test_core_debt_rewards_core_closure_and_penalizes_open_control_risk() -> None:
    graph = ProofGraphStore(problem_hash=PROBLEM_HASH)
    graph.add_obligation(_obligation("core-bridge", kind=ObligationKind.LEMMA))
    graph.add_obligation(
        _obligation(
            "main",
            kind=ObligationKind.MAIN_GOAL,
            dependencies=["core-bridge"],
        )
    )
    risk = InferenceRiskRecord(
        risk_id="risk",
        route_id="route-a",
        subject_id="core-bridge",
        risk_type=InferenceRiskType.EVENTUAL_TO_GLOBAL,
        explanation="eventual does not imply all",
        confidence=1.0,
    )
    assumption = CriticalAssumption(
        assumption_id="assumption",
        normalized_statement="H",
        source_subject_ids=["core-bridge"],
        route_ids=["route-a"],
        verification_status=ClaimStatus.PROPOSED,
        necessity_by_route={"route-a": 1.0},
        common_mode_risk=1.0,
    )
    debt_before = core_proof_debt(
        graph,
        "route-a",
        proof_roles={"core-bridge": ProofRole.CORE_BRIDGE},
        inference_risks={"risk": risk},
        critical_assumptions={"assumption": assumption},
    )

    fact = make_fact(
        message_id="fact-core",
        route_id="route-a",
        agent_id="agent-a",
        statement="core-bridge",
    )
    graph.add_claim_node(fact)
    graph.close_obligation("core-bridge", fact.message_id, confidence=1.0)
    risk.status = "cleared"
    assumption.verification_status = ClaimStatus.VERIFIED
    debt_after = core_proof_debt(
        graph,
        "route-a",
        proof_roles={"core-bridge": ProofRole.CORE_BRIDGE},
        inference_risks={"risk": risk},
        critical_assumptions={"assumption": assumption},
    )

    assert debt_after < debt_before
    assert {item.obligation_id for item in graph.core_open_obligations()} == {"main"}
