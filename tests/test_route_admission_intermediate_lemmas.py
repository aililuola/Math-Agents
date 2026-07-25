from __future__ import annotations

from mathproofmesh.config import RouteAdmissionControlConfig
from mathproofmesh.proof_control.controller import ProofControlLayer
from mathproofmesh.proof_control.gates import RouteAdmissionGate
from mathproofmesh.proof_control.models import (
    ClaimGoalLink,
    GateVerdict,
    GoalRelation,
    ScopeRelation,
)
from mathproofmesh.schemas import ObligationKind, ProofObligation, StrategyCard

from v07_helpers import PROBLEM_HASH, make_broker_runtime, make_proof_control_config


def test_intermediate_lemma_weaker_than_final_goal_is_admitted(tmp_path) -> None:
    config = make_proof_control_config(tmp_path / "runs", mode="active")
    store, registry, memory, graph, broker = make_broker_runtime(
        config, tmp_path / "runtime"
    )
    subgoal = graph.add_obligation(
        ProofObligation(
            obligation_id="subgoal-s",
            problem_hash=PROBLEM_HASH,
            route_ids=[],
            kind=ObligationKind.SUBGOAL,
            statement="Establish the reusable bridge condition.",
            normalized_statement="establish the reusable bridge condition",
            priority=0.9,
            centrality=0.8,
        )
    )
    graph.add_obligation(
        ProofObligation(
            obligation_id="main-goal-g",
            problem_hash=PROBLEM_HASH,
            route_ids=[],
            kind=ObligationKind.MAIN_GOAL,
            statement="Derive the final conclusion.",
            normalized_statement="derive the final conclusion",
            dependency_ids=[subgoal.obligation_id],
            priority=1.0,
            centrality=1.0,
        )
    )
    strategy = StrategyCard(
        strategy_id="strategy-intermediate",
        title="Prove the bridge first",
        core_idea="Close the reusable bridge condition before the final implication.",
        independence_basis="The route isolates the direct bridge obligation.",
        expected_lemmas=[subgoal.normalized_statement],
        bottleneck=subgoal.normalized_statement,
        falsification_test="Check a boundary instance of the bridge condition.",
        estimated_success=0.7,
        tags=["direct-bridge"],
    )
    control = ProofControlLayer(
        config,
        store,
        None,
        graph,
        memory,
        broker,
        registry,
    )

    admitted, records = control.admit_routes([strategy])

    assert admitted == [strategy]
    assert records[0].verdict == GateVerdict.PASS
    assert records[0].target_obligation_ids == [subgoal.obligation_id]


def test_necessary_only_without_bridge_is_blocked() -> None:
    target = ProofObligation(
        obligation_id="target-a",
        problem_hash=PROBLEM_HASH,
        route_ids=[],
        kind=ObligationKind.MAIN_GOAL,
        statement="Derive conclusion G.",
        normalized_statement="derive conclusion g",
    )
    strategy = StrategyCard(
        strategy_id="strategy-necessary",
        title="Necessary condition only",
        core_idea="Derive condition N.",
        independence_basis="A separate necessary-condition calculation.",
        expected_lemmas=["N"],
        bottleneck="Derive condition N.",
        falsification_test="Check whether N is sufficient for G.",
        estimated_success=0.4,
    )
    link = ClaimGoalLink(
        subject_id=strategy.strategy_id,
        subject_kind="strategy",
        target_obligation_id=target.obligation_id,
        relation=GoalRelation.NECESSARY_ONLY,
        scope_relation=ScopeRelation.SAME,
        implication_outline=[target.obligation_id, strategy.strategy_id],
        alignment_confidence=1.0,
    )

    record = RouteAdmissionGate(
        RouteAdmissionControlConfig(mode="active", min_goal_alignment=0.0)
    ).evaluate(
        strategy,
        goal_link=link,
        target_obligations=[target],
        core_obligation_ids=[target.obligation_id],
    )

    assert record.verdict == GateVerdict.BLOCK


def test_rewrite_verdict_always_has_request() -> None:
    target = ProofObligation(
        obligation_id="target-rewrite",
        problem_hash=PROBLEM_HASH,
        route_ids=[],
        kind=ObligationKind.MAIN_GOAL,
        statement="Derive conclusion G.",
        normalized_statement="derive conclusion g",
    )
    strategy = StrategyCard(
        strategy_id="strategy-rewrite",
        title="Incomplete plan",
        core_idea="Try a plausible transformation.",
        independence_basis="A distinct transformation.",
        expected_lemmas=["An intermediate relation"],
        bottleneck="Connect the relation to G.",
        falsification_test="Test the missing implication.",
        estimated_success=0.5,
    )
    link = ClaimGoalLink(
        subject_id=strategy.strategy_id,
        subject_kind="strategy",
        target_obligation_id=target.obligation_id,
        relation=GoalRelation.UNKNOWN,
        scope_relation=ScopeRelation.UNKNOWN,
        alignment_confidence=0.5,
    )

    record = RouteAdmissionGate(
        RouteAdmissionControlConfig(mode="active", min_goal_alignment=0.8)
    ).evaluate(
        strategy,
        goal_link=link,
        target_obligations=[target],
        core_obligation_ids=[target.obligation_id],
    )

    assert record.verdict == GateVerdict.REWRITE
    assert record.rewrite_request_id
