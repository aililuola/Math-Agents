from __future__ import annotations

from mathproofmesh.proof_control.action_dispatcher import ControlActionDispatcher
from mathproofmesh.proof_control.controller import ProofControlLayer
from mathproofmesh.proof_control.models import (
    BlueprintRewriteRequest,
    ControlActionResult,
    ControlActionStatus,
    ControlActionType,
    GateVerdict,
    GoalRelation,
    MinimalBridgeProposal,
    RouteTargetBinding,
    ScopeRelation,
)
from mathproofmesh.schemas import ObligationKind, ProofObligation, StrategyCard

from v07_helpers import (
    PROBLEM_HASH,
    make_broker_runtime,
    make_proof_control_config,
    make_strategy,
)


async def test_dispatcher_executes_an_idempotent_action_once() -> None:
    calls: list[str] = []
    checkpoints: list[ControlActionStatus] = []
    dispatcher = ControlActionDispatcher(
        problem_hash=PROBLEM_HASH,
        source_exists=lambda value: value == "diagnostic-a",
        route_exists=lambda value: value == "route-a",
        obligation_exists=lambda value: value == "obligation-a",
        checkpoint_writer=lambda action: checkpoints.append(action.status),
    )

    async def bind_target(action):
        calls.append(action.action_id)
        return ControlActionResult(
            result_refs=["route-target-binding-a"],
            postcondition_met=True,
        )

    dispatcher.register_handler(
        ControlActionType.BIND_ROUTE_TARGET,
        bind_target,
        postcondition=lambda _action, result: (
            "route-target-binding-a" in result.result_refs
        ),
    )
    first = dispatcher.propose(
        ControlActionType.BIND_ROUTE_TARGET,
        source_record_ids=["diagnostic-a"],
        route_ids=["route-a"],
        target_obligation_ids=["obligation-a"],
        payload={"relation": "sufficient", "confidence": 0.9},
        current_round=2,
    )
    duplicate = dispatcher.propose(
        ControlActionType.BIND_ROUTE_TARGET,
        source_record_ids=["diagnostic-a"],
        route_ids=["route-a"],
        target_obligation_ids=["obligation-a"],
        payload={"confidence": 0.9, "relation": "sufficient"},
        current_round=3,
    )

    assert duplicate.action_id == first.action_id
    assert dispatcher.admit(first.action_id).status == ControlActionStatus.ADMITTED
    executed = await dispatcher.execute(first.action_id, current_round=3)
    repeated = await dispatcher.execute(first.action_id, current_round=4)

    assert executed.status == ControlActionStatus.EXECUTED
    assert repeated.action_id == executed.action_id
    assert calls == [first.action_id]
    assert checkpoints == [
        ControlActionStatus.PROPOSED,
        ControlActionStatus.ADMITTED,
        ControlActionStatus.EXECUTING,
        ControlActionStatus.EXECUTED,
    ]


async def test_resume_uses_postcondition_before_reexecuting_an_action() -> None:
    actions = {}
    first_dispatcher = ControlActionDispatcher(
        problem_hash=PROBLEM_HASH,
        actions=actions,
    )
    action = first_dispatcher.propose(
        ControlActionType.MATERIALIZE_BOTTLENECK_CLUSTER,
        payload={"cluster_id": "cluster-a"},
    )
    action.status = ControlActionStatus.EXECUTING
    action.result_refs = ["cluster-a"]

    calls = 0
    restored = ControlActionDispatcher(
        problem_hash=PROBLEM_HASH,
        actions=actions,
    )

    def materialize(_action):
        nonlocal calls
        calls += 1
        return "cluster-a"

    restored.register_handler(
        ControlActionType.MATERIALIZE_BOTTLENECK_CLUSTER,
        materialize,
        postcondition=lambda _action, result: "cluster-a" in result.result_refs,
    )

    resumed = await restored.resume_pending(current_round=5)

    assert [item.status for item in resumed] == [ControlActionStatus.EXECUTED]
    assert calls == 0


def test_dispatcher_rejects_missing_sources_and_targets() -> None:
    dispatcher = ControlActionDispatcher(
        problem_hash=PROBLEM_HASH,
        source_exists=lambda _value: False,
        route_exists=lambda _value: False,
        obligation_exists=lambda _value: False,
    )
    action = dispatcher.propose(
        ControlActionType.CREATE_COUNTERMODEL_TASK,
        source_record_ids=["missing-diagnostic"],
        route_ids=["missing-route"],
        target_obligation_ids=["missing-obligation"],
    )

    rejected = dispatcher.admit(action.action_id)

    assert rejected.status == ControlActionStatus.REJECTED
    assert "unknown source record" in rejected.failure_reason


async def test_failed_action_has_explicit_failure_reason() -> None:
    dispatcher = ControlActionDispatcher(problem_hash=PROBLEM_HASH)
    dispatcher.register_handler(
        ControlActionType.CREATE_MINIMAL_BRIDGE,
        lambda _action: ControlActionResult(
            postcondition_met=False,
            detail="bridge authority produced no auditable obligation",
        ),
        postcondition=lambda _action, _result: False,
    )
    action = dispatcher.propose(ControlActionType.CREATE_MINIMAL_BRIDGE)

    failed = await dispatcher.execute(action.action_id, current_round=1)

    assert failed.status == ControlActionStatus.FAILED
    assert failed.failure_reason == (
        "bridge authority produced no auditable obligation"
    )


async def test_handler_can_materialize_a_deferred_action() -> None:
    dispatcher = ControlActionDispatcher(problem_hash=PROBLEM_HASH)

    def defer_with_wake(action):
        dispatcher.defer(action.action_id, reason="waiting for an explicit wake")
        return ControlActionResult(
            result_refs=["wake-a"],
            postcondition_met=True,
        )

    dispatcher.register_handler(
        ControlActionType.EXECUTE_META_PIVOT,
        defer_with_wake,
        postcondition=lambda _action, _result: False,
    )
    action = dispatcher.propose(ControlActionType.EXECUTE_META_PIVOT)

    deferred = await dispatcher.execute(action.action_id, current_round=1)

    assert deferred.status == ControlActionStatus.DEFERRED
    assert deferred.result_refs == ["wake-a"]
    assert deferred.executed_round is None


def _control_runtime(tmp_path):
    config = make_proof_control_config(tmp_path / "runs", mode="active")
    store, registry, memory, graph, broker = make_broker_runtime(
        config, tmp_path / "runtime"
    )
    strategy = make_strategy(41, tag="bridge-repair")
    route = registry.register_route(strategy, route_id="route-rewrite")
    direct = graph.add_obligation(
        ProofObligation(
            obligation_id="direct-subgoal",
            problem_hash=PROBLEM_HASH,
            route_ids=[route.route_id],
            kind=ObligationKind.SUBGOAL,
            statement="Establish the local reduction.",
            normalized_statement="establish the local reduction",
        )
    )
    main = graph.add_obligation(
        ProofObligation(
            obligation_id="main-goal",
            problem_hash=PROBLEM_HASH,
            route_ids=[route.route_id],
            kind=ObligationKind.MAIN_GOAL,
            statement="Derive the final conclusion.",
            normalized_statement="derive the final conclusion",
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
    control.archive_strategy(strategy)
    binding = RouteTargetBinding(
        binding_id="binding-rewrite",
        strategy_id=strategy.strategy_id,
        route_id=route.route_id,
        direct_target_obligation_id=direct.obligation_id,
        ancestor_obligation_ids=[],
        main_goal_obligation_id=main.obligation_id,
        direct_claim_ids=[],
        bridge_obligation_ids=[],
        relation_to_direct_target=GoalRelation.SUFFICIENT,
        relation_to_main_goal=GoalRelation.NECESSARY_ONLY,
        scope_relation_to_direct_target=ScopeRelation.SAME,
        blueprint_path_complete=False,
        binding_confidence=0.9,
    )
    control.state.route_target_bindings[binding.binding_id] = binding
    return control, route, strategy, binding


def test_every_rewrite_request_materializes_action_and_bridge(tmp_path) -> None:
    control, route, strategy, binding = _control_runtime(tmp_path)
    request = BlueprintRewriteRequest(
        request_id="rewrite-request-a",
        route_id=route.route_id,
        failure_record_id=binding.binding_id,
        preserved_fact_ids=[],
        preserved_step_ids=[],
        invalidated_plan_elements=["missing implication"],
        current_overstrong_targets=[],
        proposed_weaker_targets=[binding.direct_target_obligation_id],
        proposed_bridge_obligation_ids=[],
        representation_change_required=False,
    )
    control.state.blueprint_rewrites[request.request_id] = request

    action = control.dispatch_blueprint_rewrite(
        request.request_id,
        strategy_id=strategy.strategy_id,
        binding_id=binding.binding_id,
        current_round=2,
    )

    assert action.status == ControlActionStatus.EXECUTED
    assert request.status == "executed"
    bridge_actions = [
        item
        for item in control.state.control_actions.values()
        if item.action_type == ControlActionType.CREATE_MINIMAL_BRIDGE
    ]
    assert len(bridge_actions) == 1
    assert bridge_actions[0].status == ControlActionStatus.EXECUTED
    updated = control.state.route_target_bindings[binding.binding_id]
    assert updated.blueprint_path_complete
    assert updated.bridge_obligation_ids


def test_blueprint_prevents_unnecessary_rewrite_verdict(tmp_path) -> None:
    config = make_proof_control_config(tmp_path / "runs", mode="active")
    store, registry, memory, graph, broker = make_broker_runtime(
        config, tmp_path / "runtime"
    )
    strategy = StrategyCard(
        strategy_id="strategy-rewrite-verdict",
        title="Local reduction route",
        core_idea="Establish the local reduction, then supply the missing implication.",
        independence_basis="The route isolates one auditable local condition.",
        expected_lemmas=["establish the local reduction"],
        bottleneck="establish the local reduction",
        falsification_test="Check a finite boundary instance.",
        estimated_success=0.6,
        tags=["local-reduction"],
    )
    registry.register_route(strategy, route_id="route-rewrite-verdict")
    graph.add_obligation(
        ProofObligation(
            obligation_id="subgoal-without-bridge",
            problem_hash=PROBLEM_HASH,
            route_ids=["route-rewrite-verdict"],
            kind=ObligationKind.SUBGOAL,
            statement="Establish the local reduction.",
            normalized_statement="establish the local reduction",
        )
    )
    graph.add_obligation(
        ProofObligation(
            obligation_id="main-without-bridge",
            problem_hash=PROBLEM_HASH,
            route_ids=["route-rewrite-verdict"],
            kind=ObligationKind.MAIN_GOAL,
            statement="Derive the final conclusion.",
            normalized_statement="derive the final conclusion",
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

    admitted, records = control.admit_routes([strategy])

    assert admitted == [strategy]
    assert records[0].verdict == GateVerdict.PASS
    assert records[0].rewrite_request_id is None
    actions = [
        item
        for item in control.state.control_actions.values()
        if item.action_type == ControlActionType.REWRITE_BLUEPRINT
    ]
    assert actions == []


def test_minimal_bridge_record_materializes_obligation(tmp_path) -> None:
    control, route, _strategy, binding = _control_runtime(tmp_path)
    proposal = MinimalBridgeProposal(
        proposal_id="minimal-bridge-a",
        overstrong_subject_id=binding.strategy_id,
        target_obligation_id=binding.main_goal_obligation_id,
        candidate_statement=(
            "Show that the local reduction implies the final conclusion."
        ),
        relation_to_original="strictly_weaker",
        implication_outline=[
            binding.direct_target_obligation_id,
            binding.main_goal_obligation_id,
        ],
        required_bridge_obligation_ids=[binding.direct_target_obligation_id],
    )

    action = control.materialize_minimal_bridge(
        proposal,
        route_id=route.route_id,
        binding_id=binding.binding_id,
        current_round=2,
    )

    assert action.status == ControlActionStatus.EXECUTED
    assert proposal.status == "accepted"
    bridge_id = next(
        item for item in action.result_refs if item.startswith("obl_minimal_bridge_")
    )
    bridge = control.proof_graph.get_obligation(bridge_id)
    assert bridge.status == "open"
    assert bridge.evidence_message_ids == []
