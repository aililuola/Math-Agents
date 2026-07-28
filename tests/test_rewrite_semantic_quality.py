from __future__ import annotations

from mathproofmesh.proof_control.models import (
    BlueprintEdge,
    BlueprintNode,
    BlueprintNodeKind,
    RewriteSemanticVerdict,
    StrategyBlueprint,
)
from mathproofmesh.proof_control.strategy_blueprint import RewriteSemanticGate

from v082_helpers import make_domain_strategy, make_main_goal


def _self_implication_candidate():
    strategy = make_domain_strategy(strategy_id="strategy-revised")
    goal = make_main_goal()
    goal_node = BlueprintNode(
        node_id="node-g",
        strategy_id=strategy.strategy_id,
        kind=BlueprintNodeKind.TARGET,
        statement=goal.statement,
        normalized_statement=goal.normalized_statement,
        source_field="main_goal",
        semantic_quality_score=1.0,
    )
    edge = BlueprintEdge(
        edge_id="edge-g-g",
        source_node_id=goal_node.node_id,
        target_node_id=goal_node.node_id,
        relation="implies",
        implication_outline=["Assume G.", "Conclude G."],
    )
    blueprint = StrategyBlueprint(
        blueprint_id="blueprint-self",
        strategy_id=strategy.strategy_id,
        problem_hash=goal.problem_hash,
        node_ids=[goal_node.node_id],
        edge_ids=[edge.edge_id],
        main_goal_node_id=goal_node.node_id,
        direct_target_node_ids=[goal_node.node_id],
        root_entry_node_ids=[goal_node.node_id],
        open_gap_node_ids=[],
        preserves_mechanism_signature=False,
        complete_path_to_main_goal=True,
        compilation_confidence=0.9,
        status="compiled",
    )
    return strategy, goal, blueprint, [goal_node], [edge]


def test_self_implication_rewrite_is_rejected() -> None:
    strategy, goal, blueprint, nodes, edges = _self_implication_candidate()

    assessment = RewriteSemanticGate().assess(
        rewrite_request_id="rewrite-self",
        candidate_strategy=strategy,
        candidate_blueprint=blueprint,
        nodes=nodes,
        edges=edges,
        original_strategy=make_domain_strategy(strategy_id="strategy-original"),
        main_goal=goal,
    )

    assert assessment.verdict == RewriteSemanticVerdict.TAUTOLOGICAL
    assert assessment.is_self_implication
    assert not assessment.has_nontrivial_graph_change


def test_placeholder_rewrite_is_rejected() -> None:
    strategy, goal, blueprint, nodes, _edges = _self_implication_candidate()
    placeholder = nodes[0].model_copy(
        update={
            "node_id": "node-placeholder",
            "statement": "Prove the theorem.",
            "normalized_statement": "prove the theorem",
            "kind": BlueprintNodeKind.LEMMA,
            "source_field": "generated_bridge",
        }
    )
    blueprint = blueprint.model_copy(
        update={
            "node_ids": [placeholder.node_id, nodes[0].node_id],
            "direct_target_node_ids": [placeholder.node_id],
        }
    )

    assessment = RewriteSemanticGate().assess(
        rewrite_request_id="rewrite-placeholder",
        candidate_strategy=strategy,
        candidate_blueprint=blueprint,
        nodes=[placeholder, nodes[0]],
        edges=[],
        original_strategy=make_domain_strategy(strategy_id="strategy-original"),
        main_goal=goal,
    )

    assert assessment.verdict == RewriteSemanticVerdict.PLACEHOLDER


def test_rewrite_requires_child_lineage_and_preserves_domain_mechanism() -> None:
    original = make_domain_strategy(strategy_id="strategy-original")
    revised = make_domain_strategy(strategy_id="strategy-revised").model_copy(
        update={"parent_strategy_ids": [original.strategy_id]}
    )
    gate = RewriteSemanticGate()

    result = gate.revise_from_claims(
        rewrite_request_id="rewrite-valid",
        original_strategy=original,
        candidate_strategy=revised,
        main_goal=make_main_goal(),
        retained_claim_statements=[original.expected_lemmas[0]],
        added_claim_statements=[
            "The compatible decomposition transfers the target relation."
        ],
    )

    assert result.revised_strategy.strategy_id != original.strategy_id
    assert result.lineage.parent_strategy_id == original.strategy_id
    assert result.semantic_assessment.verdict == RewriteSemanticVerdict.VALID
    assert result.semantic_assessment.domain_mechanism_preserved
    assert result.first_executable_obligation_id
