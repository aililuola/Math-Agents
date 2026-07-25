from __future__ import annotations

from mathproofmesh.proof_control.controller import ProofControlLayer
from mathproofmesh.proof_control.models import GateVerdict
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

