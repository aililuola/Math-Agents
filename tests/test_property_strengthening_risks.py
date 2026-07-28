from __future__ import annotations

from mathproofmesh.proof_control.controller import ProofControlLayer
from mathproofmesh.proof_control.inference_risk import InferenceRiskScanner
from mathproofmesh.proof_control.models import (
    ControlActionStatus,
    ControlActionType,
    InferenceRiskType,
    PropertyStrength,
    RelationSignature,
    SetRelationKind,
)
from mathproofmesh.schemas import ObligationKind, ProofObligation

from v07_helpers import PROBLEM_HASH, make_broker_runtime, make_proof_control_config


def _risk_types(
    premise: RelationSignature,
    conclusion: RelationSignature,
) -> set[InferenceRiskType]:
    return {
        item.risk_type
        for item in InferenceRiskScanner().deterministic_risks(
            subject_id="generic-inference",
            premise_relation_signatures=[premise],
            conclusion_relation_signature=conclusion,
        )
    }


def test_nonempty_intersection_does_not_imply_subset() -> None:
    risks = _risk_types(
        RelationSignature(
            set_relation=SetRelationKind.NONEMPTY_INTERSECTION,
            property_strength=PropertyStrength.EXISTENTIAL,
        ),
        RelationSignature(
            set_relation=SetRelationKind.SUBSET,
            property_strength=PropertyStrength.UNIVERSAL,
        ),
    )

    assert InferenceRiskType.NONEMPTY_INTERSECTION_TO_SUBSET_CONTAINMENT in risks


def test_exists_component_does_not_imply_all_components() -> None:
    risks = _risk_types(
        RelationSignature(
            semantic_role="component",
            property_strength=PropertyStrength.EXISTENTIAL,
        ),
        RelationSignature(
            semantic_role="component",
            property_strength=PropertyStrength.UNIVERSAL,
        ),
    )

    assert InferenceRiskType.EXISTS_COMPONENT_TO_ALL_COMPONENTS in risks


def test_partial_property_does_not_imply_total_property() -> None:
    risks = _risk_types(
        RelationSignature(
            semantic_role="property",
            property_strength=PropertyStrength.PARTIAL,
        ),
        RelationSignature(
            semantic_role="property",
            property_strength=PropertyStrength.UNIVERSAL,
        ),
    )

    assert InferenceRiskType.PARTIAL_PROPERTY_TO_TOTAL_PROPERTY in risks


def test_coverage_does_not_imply_exhaustiveness() -> None:
    risks = _risk_types(
        RelationSignature(
            semantic_role="coverage",
            set_relation=SetRelationKind.COVER,
            property_strength=PropertyStrength.UNIVERSAL,
        ),
        RelationSignature(
            semantic_role="coverage",
            set_relation=SetRelationKind.PARTITION,
            property_strength=PropertyStrength.EXHAUSTIVE,
        ),
    )

    assert InferenceRiskType.COVERAGE_TO_EXHAUSTIVENESS in risks


def test_high_confidence_strengthening_risk_materializes_countermodel(
    tmp_path,
) -> None:
    config = make_proof_control_config(tmp_path / "runs", mode="active")
    store, registry, memory, graph, broker = make_broker_runtime(
        config, tmp_path / "runtime"
    )
    graph.add_obligation(
        ProofObligation(
            obligation_id="main-goal",
            problem_hash=PROBLEM_HASH,
            route_ids=["route-a"],
            kind=ObligationKind.MAIN_GOAL,
            statement="Establish the total property.",
            normalized_statement="establish the total property",
            priority=1.0,
            centrality=1.0,
        )
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
    [risk] = InferenceRiskScanner().deterministic_risks(
        subject_id="claim-risk",
        route_id="route-a",
        premise_relation_signatures=[
            RelationSignature(
                semantic_role="property",
                property_strength=PropertyStrength.PARTIAL,
            )
        ],
        conclusion_relation_signature=RelationSignature(
            semantic_role="property",
            property_strength=PropertyStrength.UNIVERSAL,
        ),
    )

    control._register_risks([risk])

    actions = [
        action
        for action in control.state.control_actions.values()
        if action.action_type == ControlActionType.CREATE_COUNTERMODEL_TASK
    ]
    assert len(actions) == 1
    assert actions[0].status == ControlActionStatus.EXECUTED
    assert risk.countermodel_task_id in control.state.countermodel_tasks
    assert len(control.state.negative_patterns) == 1


def test_explicit_bridge_can_clear_strengthening_risk(tmp_path) -> None:
    config = make_proof_control_config(tmp_path / "runs", mode="active")
    store, registry, memory, graph, broker = make_broker_runtime(
        config, tmp_path / "runtime"
    )
    graph.add_obligation(
        ProofObligation(
            obligation_id="main-goal",
            problem_hash=PROBLEM_HASH,
            route_ids=["route-a"],
            kind=ObligationKind.MAIN_GOAL,
            statement="Establish the total property.",
            normalized_statement="establish the total property",
        )
    )
    bridge = graph.add_obligation(
        ProofObligation(
            obligation_id="verified-bridge",
            problem_hash=PROBLEM_HASH,
            route_ids=["route-a"],
            kind=ObligationKind.LEMMA,
            statement="Partial validity extends to the total object.",
            normalized_statement="partial validity extends to the total object",
            status="closed",
            evidence_message_ids=["verified-bridge-message"],
        )
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
    [risk] = InferenceRiskScanner().deterministic_risks(
        subject_id="claim-risk",
        premise_relation_signatures=[
            RelationSignature(
                semantic_role="property",
                property_strength=PropertyStrength.PARTIAL,
            )
        ],
        conclusion_relation_signature=RelationSignature(
            semantic_role="property",
            property_strength=PropertyStrength.UNIVERSAL,
        ),
    )
    control._register_risks([risk])

    resolved = control.resolve_inference_risk_with_bridges(
        risk.risk_id,
        bridge_obligation_ids=[bridge.obligation_id],
    )

    assert resolved.status == "accepted_with_bridge"
