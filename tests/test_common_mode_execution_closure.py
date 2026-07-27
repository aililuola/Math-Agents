from __future__ import annotations

import asyncio
from types import SimpleNamespace

from mathproofmesh.orchestrator import ProofMeshOrchestrator, SolveState
from mathproofmesh.proof_control.controller import ProofControlLayer
from mathproofmesh.proof_control.models import (
    AssumptionChallengeAction,
    AssumptionChallengeProposal,
    AssumptionChallengeResult,
    AssumptionChallengeOutcome,
    AssumptionChallengeReview,
    ControlActionStatus,
    ControlActionType,
    DependencyKind,
    DependencyRef,
    TaskStatus,
)
from mathproofmesh.schemas import (
    AttemptStatus,
    ObligationKind,
    ProofAttempt,
    ProofObligation,
    ProofStep,
    RouteDescriptor,
    Severity,
    VerificationIssue,
    VerificationReport,
    VerificationStage,
    VerificationVerdict,
)

from v07_helpers import (
    PROBLEM_HASH,
    make_broker_runtime,
    make_proof_control_config,
    make_strategy,
)


def _runtime(tmp_path, *, mode: str = "active"):
    config = make_proof_control_config(tmp_path / "runs", mode=mode)
    config.topology.proof_control.common_mode.min_routes = 2
    store, registry, memory, graph, broker = make_broker_runtime(
        config,
        tmp_path / "runtime",
        route_count=0,
    )
    strategies = [
        make_strategy(301, tag="direct-construction"),
        make_strategy(302, tag="extremal-reduction"),
    ]
    routes = [
        RouteDescriptor(
            route_id="route-left",
            strategy_id=strategies[0].strategy_id,
            mechanism_signature=["direct", "construction"],
        ),
        RouteDescriptor(
            route_id="route-right",
            strategy_id=strategies[1].strategy_id,
            mechanism_signature=["extremal", "reduction"],
        ),
    ]
    for route, strategy in zip(routes, strategies):
        registry.register_route(strategy, route_id=route.route_id)
    graph.add_obligation(
        ProofObligation(
            obligation_id="main-goal",
            problem_hash=PROBLEM_HASH,
            route_ids=[route.route_id for route in routes],
            kind=ObligationKind.MAIN_GOAL,
            statement="Establish the requested conclusion.",
            normalized_statement="establish the requested conclusion",
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
    return config, store, registry, memory, graph, control, routes, strategies


def _attempt(
    *,
    attempt_id: str,
    strategy_id: str,
    agent_id: str,
    route_id: str,
    dependency_id: str | None = None,
) -> ProofAttempt:
    steps = []
    if dependency_id is not None:
        steps.append(
            ProofStep(
                step_id=f"step-{attempt_id}",
                statement=f"Advance route {route_id} from the shared bridge.",
                justification="This step uses the explicitly referenced bridge.",
                dependency_refs=[
                    DependencyRef(
                        kind=DependencyKind.OBLIGATION,
                        target_id=dependency_id,
                        source_route_id=route_id,
                    )
                ],
                is_key_step=True,
            )
        )
    return ProofAttempt(
        attempt_id=attempt_id,
        problem_hash=PROBLEM_HASH,
        strategy_id=strategy_id,
        agent_id=agent_id,
        round_index=1,
        status=AttemptStatus.PARTIAL,
        proof_steps=steps,
        unresolved_gaps=[],
    )


def _materialize_shared_dependency(tmp_path, *, mode: str = "active"):
    runtime = _runtime(tmp_path, mode=mode)
    _config, _store, _registry, _memory, graph, control, routes, strategies = runtime
    graph.add_obligation(
        ProofObligation(
            obligation_id="shared-bridge",
            problem_hash=PROBLEM_HASH,
            route_ids=[route.route_id for route in routes],
            kind=ObligationKind.LEMMA,
            statement="Every admissible transition preserves the invariant.",
            normalized_statement="every admissible transition preserves the invariant",
            status="open",
            centrality=1.0,
        )
    )
    attempts = [
        _attempt(
            attempt_id="attempt-left",
            strategy_id=strategies[0].strategy_id,
            agent_id="agent-left",
            route_id=routes[0].route_id,
            dependency_id="shared-bridge",
        ),
        _attempt(
            attempt_id="attempt-right",
            strategy_id=strategies[1].strategy_id,
            agent_id="agent-right",
            route_id=routes[1].route_id,
            dependency_id="shared-bridge",
        ),
    ]
    control.update_after_round(
        strategies=strategies,
        attempts=attempts,
        current_round=1,
    )
    return (*runtime, attempts)


def test_shared_typed_dependency_clusters_different_route_mechanisms(tmp_path) -> None:
    (
        _config,
        _store,
        _registry,
        _memory,
        _graph,
        control,
        routes,
        _strategies,
        _attempts,
    ) = _materialize_shared_dependency(tmp_path)

    families = control.common_mode.risk_families()
    assert len(families) == 1
    family = families[0]
    assert family.route_ids == ["route-left", "route-right"]
    assert family.typed_dependency_ids == ["obligation:shared-bridge"]
    assert family.is_dependency_cutset is True
    assert set(family.dependency_atom_ids) == set(control.state.dependency_atoms)

    task = next(iter(control.state.assumption_challenger_tasks.values()))
    assert task.executable_task_id is not None
    executable = control.state.executable_tasks[task.executable_task_id]
    assert executable.task_kind == "assumption_challenger"
    assert executable.status == TaskStatus.READY
    assert executable.registered_handler == "assumption_challenger_dispatcher"
    assert all(
        not control.deepening_currently_allowed(route.route_id) for route in routes
    )


def test_similar_theme_with_distinct_dependency_closures_is_not_merged(
    tmp_path,
) -> None:
    (
        _config,
        _store,
        _registry,
        _memory,
        graph,
        control,
        routes,
        strategies,
    ) = _runtime(tmp_path)
    for obligation_id, route_id, statement in (
        (
            "order-bridge",
            routes[0].route_id,
            "The transformation preserves the selected relation.",
        ),
        (
            "parity-bridge",
            routes[1].route_id,
            "The transformation preserves the selected relation.",
        ),
    ):
        graph.add_obligation(
            ProofObligation(
                obligation_id=obligation_id,
                problem_hash=PROBLEM_HASH,
                route_ids=[route_id],
                kind=ObligationKind.LEMMA,
                statement=statement,
                normalized_statement=statement.casefold().rstrip("."),
                status="open",
            )
        )
    attempts = [
        _attempt(
            attempt_id="attempt-order",
            strategy_id=strategies[0].strategy_id,
            agent_id="agent-order",
            route_id=routes[0].route_id,
            dependency_id="order-bridge",
        ),
        _attempt(
            attempt_id="attempt-parity",
            strategy_id=strategies[1].strategy_id,
            agent_id="agent-parity",
            route_id=routes[1].route_id,
            dependency_id="parity-bridge",
        ),
    ]

    control.update_after_round(
        strategies=strategies,
        attempts=attempts,
        current_round=1,
    )

    assert not any(
        set(family.route_ids) == {"route-left", "route-right"}
        for family in control.state.assumption_families.values()
    )
    assert control.common_mode.risk_families() == []
    assert control.state.assumption_challenger_tasks == {}


def test_verifier_premise_summary_enters_common_mode_detection(tmp_path) -> None:
    (
        _config,
        _store,
        _registry,
        _memory,
        _graph,
        control,
        routes,
        strategies,
    ) = _runtime(tmp_path)
    attempts = [
        _attempt(
            attempt_id=f"attempt-{index}",
            strategy_id=strategy.strategy_id,
            agent_id=f"agent-{index}",
            route_id=route.route_id,
        )
        for index, (route, strategy) in enumerate(zip(routes, strategies))
    ]
    reports = [
        VerificationReport(
            report_id=f"report-{index}",
            target_id=attempt.attempt_id,
            target_type="attempt",
            agent_id=f"verifier-{index}",
            stage=VerificationStage.DETAILED,
            verdict=VerificationVerdict.FAIL,
            issues=[
                VerificationIssue(
                    issue_id=f"issue-{index}",
                    phase="dependency",
                    severity=Severity.CRITICAL,
                    step_id=None,
                    description="The route uses an unproved load-bearing premise.",
                    premise_summary=(
                        "Every admissible transition preserves the invariant."
                    ),
                )
            ],
            failure_level="strategy",
            confidence=0.95,
            concise_feedback="The shared premise is not established.",
        )
        for index, attempt in enumerate(attempts)
    ]

    control.update_after_round(
        strategies=strategies,
        attempts=attempts,
        reports=reports,
        current_round=1,
    )

    family = control.common_mode.risk_families()[0]
    assert family.route_ids == ["route-left", "route-right"]
    assert any(
        atom.source_kind == "verifier_critical_issue"
        for atom in control.state.dependency_atoms.values()
    )


def test_transitive_dependency_closure_finds_a_shared_load_bearing_predecessor(
    tmp_path,
) -> None:
    (
        _config,
        _store,
        _registry,
        _memory,
        graph,
        control,
        routes,
        strategies,
    ) = _runtime(tmp_path)
    graph.add_obligation(
        ProofObligation(
            obligation_id="shared-core",
            problem_hash=PROBLEM_HASH,
            route_ids=[],
            kind=ObligationKind.LEMMA,
            statement="Every admissible transition preserves the invariant.",
            normalized_statement="every admissible transition preserves the invariant",
            status="open",
        )
    )
    for obligation_id, route, statement, typed_dependency in (
        (
            "left-bridge",
            routes[0],
            "Every direct construction preserves its local bridge relation.",
            False,
        ),
        (
            "right-bridge",
            routes[1],
            "Every extremal reduction preserves its local bridge relation.",
            True,
        ),
    ):
        graph.add_obligation(
            ProofObligation(
                obligation_id=obligation_id,
                problem_hash=PROBLEM_HASH,
                route_ids=[route.route_id],
                kind=ObligationKind.LEMMA,
                statement=statement,
                normalized_statement=statement.casefold().rstrip("."),
                dependency_ids=[] if typed_dependency else ["shared-core"],
                dependency_refs=(
                    [
                        DependencyRef(
                            kind=DependencyKind.OBLIGATION,
                            target_id="shared-core",
                        )
                    ]
                    if typed_dependency
                    else []
                ),
                status="open",
            )
        )
    attempts = [
        _attempt(
            attempt_id="attempt-left-closure",
            strategy_id=strategies[0].strategy_id,
            agent_id="agent-left",
            route_id=routes[0].route_id,
            dependency_id="left-bridge",
        ),
        _attempt(
            attempt_id="attempt-right-closure",
            strategy_id=strategies[1].strategy_id,
            agent_id="agent-right",
            route_id=routes[1].route_id,
            dependency_id="right-bridge",
        ),
    ]

    control.update_after_round(
        strategies=strategies,
        attempts=attempts,
        current_round=1,
    )

    matching = [
        family
        for family in control.common_mode.risk_families()
        if family.typed_dependency_ids == ["obligation:shared-core"]
    ]
    assert matching, {
        "families": [
            (
                family.typed_dependency_ids,
                family.route_ids,
                family.common_mode_risk,
            )
            for family in control.state.assumption_families.values()
        ],
        "atoms": [
            (
                atom.typed_dependency_ids,
                atom.route_id,
                atom.normalized_statement,
            )
            for atom in control.state.dependency_atoms.values()
        ],
    }
    shared_family = matching[0]
    assert shared_family.route_ids == ["route-left", "route-right"]
    assert shared_family.is_dependency_cutset is True


def test_challenger_executes_once_survives_resume_and_never_closes_goal(
    tmp_path,
) -> None:
    (
        config,
        store,
        _registry,
        _memory,
        graph,
        control,
        _routes,
        _strategies,
        _attempts,
    ) = _materialize_shared_dependency(tmp_path)
    task = next(iter(control.state.assumption_challenger_tasks.values()))
    goal = graph.get_obligation("main-goal")
    original_goal_hash = goal.content_hash
    calls = 0

    async def execute(current_task):
        nonlocal calls
        calls += 1
        return AssumptionChallengeResult(
            result_id="challenge-result-inconclusive",
            task_id=current_task.task_id,
            family_id=current_task.family_id,
            action="refute",
            outcome=AssumptionChallengeOutcome.INCONCLUSIVE,
            challenger_agent_id="challenger-agent",
            reviewer_agent_id="independent-reviewer",
            evidence_refs=["artifact:challenge-proposal"],
            independent_review_refs=["artifact:challenge-review"],
            detail="The challenge produced no independently confirmed resolution.",
            completed_round=2,
        )

    completed = asyncio.run(
        control.execute_pending_assumption_challengers(
            current_round=2,
            executor=execute,
        )
    )

    assert calls == 1
    assert completed == [task]
    assert task.status == "inconclusive"
    assert task.result_id == "challenge-result-inconclusive"
    executable = control.state.executable_tasks[task.executable_task_id]
    assert executable.status == TaskStatus.INCONCLUSIVE
    execution_action = control.state.control_actions[task.execution_action_id]
    assert execution_action.action_type == (
        ControlActionType.EXECUTE_ASSUMPTION_CHALLENGER
    )
    assert execution_action.status == ControlActionStatus.EXECUTED
    assert goal.status == "open"
    assert goal.content_hash == original_goal_hash
    assert control.typed_memory.facts == []

    restored = ProofControlLayer.from_state(
        control.export_state(),
        config=config,
        store=store,
        activity=None,
        proof_graph=control.proof_graph,
        typed_memory=control.typed_memory,
        message_broker=control.message_broker,
        route_registry=control.route_registry,
    )
    asyncio.run(
        restored.execute_pending_assumption_challengers(
            current_round=3,
            executor=execute,
        )
    )
    assert calls == 1
    assert (
        restored.state.assumption_challenge_results[
            "challenge-result-inconclusive"
        ].outcome
        == AssumptionChallengeOutcome.INCONCLUSIVE
    )


def test_pending_challenger_blocks_hard_stop_until_it_reaches_a_terminal_state(
    tmp_path,
) -> None:
    (
        _config,
        _store,
        _registry,
        _memory,
        _graph,
        control,
        _routes,
        _strategies,
        _attempts,
    ) = _materialize_shared_dependency(tmp_path)

    assert control.common_mode_blocks_stagnation_stop()
    classification = control.prepare_routes_for_hard_stop(
        progress_signature="plateau",
        current_round=2,
    )
    assert classification["ready_task_ids"]
    assert set(classification["ready_task_ids"]) == {
        task.executable_task_id
        for task in control.state.assumption_challenger_tasks.values()
    }


def test_unreviewed_resolution_cannot_bypass_the_challenge_contract(tmp_path) -> None:
    (
        _config,
        _store,
        _registry,
        _memory,
        graph,
        control,
        _routes,
        _strategies,
        _attempts,
    ) = _materialize_shared_dependency(tmp_path)
    task = next(iter(control.state.assumption_challenger_tasks.values()))

    async def execute(current_task):
        return AssumptionChallengeResult(
            result_id="unreviewed-result",
            task_id=current_task.task_id,
            family_id=current_task.family_id,
            action=AssumptionChallengeAction.PROVE,
            outcome=AssumptionChallengeOutcome.VERIFIED,
            detail="This deliberately omits independent review evidence.",
            completed_round=2,
        )

    asyncio.run(
        control.execute_pending_assumption_challengers(
            current_round=2,
            executor=execute,
        )
    )

    assert task.status == "blocked"
    assert task.result_id is None
    assert control.state.assumption_challenge_results == {}
    assert control.state.executable_tasks[task.executable_task_id].status == (
        TaskStatus.FAILED
    )
    assert control.state.control_actions[task.execution_action_id].status == (
        ControlActionStatus.FAILED
    )
    assert control.typed_memory.facts == []
    assert graph.get_obligation("main-goal").status == "open"


def test_reworded_shared_dependency_is_not_admitted_as_an_independent_route(
    tmp_path,
) -> None:
    (
        _config,
        _store,
        _registry,
        _memory,
        _graph,
        control,
        _routes,
        _strategies,
        _attempts,
    ) = _materialize_shared_dependency(tmp_path)
    candidate = make_strategy(303, tag="renamed-route").model_copy(
        update={
            "prerequisites": [
                "The invariant is preserved under every admissible transition."
            ]
        }
    )

    assert not control.strategy_is_independent_from_common_mode(candidate)
    selected, records = control.admit_routes([candidate])
    assert selected == []
    assert any(
        "common-mode assumption" in reason
        for record in records
        for reason in record.reasons
    )


def test_orchestrator_dispatches_challenger_and_independent_review_without_api(
    tmp_path,
    monkeypatch,
) -> None:
    (
        config,
        store,
        registry,
        memory,
        graph,
        control,
        _routes,
        strategies,
        attempts,
    ) = _materialize_shared_dependency(tmp_path)
    orchestrator = ProofMeshOrchestrator(config)
    state = SolveState(
        triage=None,
        strategies=strategies,
        attempts=attempts,
        reports=[],
        aggregate_reports={},
        meta_reviews=[],
        checkpoints=[],
        route_registry=registry,
        typed_memory=memory,
        proof_graph=graph,
        message_broker=control.message_broker,
        proof_control=control,
        current_round=2,
    )
    challenger = SimpleNamespace(id="challenger-agent", provider="provider-a")
    reviewer = SimpleNamespace(id="reviewer-agent", provider="provider-b")

    class FakePool:
        def select(self, role, **_kwargs):
            return challenger if role == "counterexample_hunter" else reviewer

    runner = SimpleNamespace(
        ledger=SimpleNamespace(remaining_calls=8),
        pool=FakePool(),
    )
    calls: list[str] = []

    async def fake_safe_call(_runner, _role, bundle, **_kwargs):
        calls.append(bundle.stage)
        if bundle.stage == "proof_control_assumption_challenge":
            value = AssumptionChallengeProposal(
                proposal_id="proposal-proof",
                action=AssumptionChallengeAction.PROVE,
                target_statement=(
                    "Every admissible transition preserves the invariant."
                ),
                concise_argument="A direct dependency-explicit proof candidate.",
                proof_steps=[
                    ProofStep(
                        step_id="challenge-step",
                        statement=(
                            "Every admissible transition preserves the invariant."
                        ),
                        justification="Derived from the stated transition axioms.",
                    )
                ],
            )
            return SimpleNamespace(
                value=value,
                agent=challenger,
                raw_ref="raw:challenge-proposal",
            )
        value = AssumptionChallengeReview(
            proposal_id="proposal-proof",
            verdict="pass",
            action_supported=True,
            proof_complete=True,
            checked_step_ids=["challenge-step"],
            concise_feedback="The scoped proof candidate passed independent review.",
        )
        return SimpleNamespace(
            value=value,
            agent=reviewer,
            raw_ref="raw:challenge-review",
        )

    monkeypatch.setattr(orchestrator, "_safe_call", fake_safe_call)
    attempted, performed = asyncio.run(
        orchestrator._execute_pending_assumption_challengers(
            state,
            problem=SimpleNamespace(),
            store=store,
            runner=runner,
            prompts=SimpleNamespace(
                challenge_critical_assumption=lambda **_kwargs: SimpleNamespace(
                    stage="proof_control_assumption_challenge"
                ),
                review_critical_assumption_challenge=lambda **_kwargs: SimpleNamespace(
                    stage="proof_control_assumption_challenge_review"
                ),
            ),
            allocator=SimpleNamespace(minimum_finish_reserve=0),
            router=SimpleNamespace(),
            memory=SimpleNamespace(),
            tools=SimpleNamespace(),
        )
    )

    assert attempted is True
    assert performed is True
    assert calls == [
        "proof_control_assumption_challenge",
        "proof_control_assumption_challenge_review",
    ]
    task = next(iter(control.state.assumption_challenger_tasks.values()))
    assert task.status == "verified"
    assert control.state.executable_tasks[task.executable_task_id].status == (
        TaskStatus.COMPLETED
    )
    assert control.common_mode.risk_families() == []
    assert all(
        control.deepening_currently_allowed(route_id)
        for route_id in ("route-left", "route-right")
    )
    assert control.typed_memory.facts == []
    assert graph.get_obligation("main-goal").status == "open"

    asyncio.run(
        orchestrator._execute_pending_assumption_challengers(
            state,
            problem=SimpleNamespace(),
            store=store,
            runner=runner,
            prompts=SimpleNamespace(),
            allocator=SimpleNamespace(minimum_finish_reserve=0),
            router=SimpleNamespace(),
            memory=SimpleNamespace(),
            tools=SimpleNamespace(),
        )
    )
    assert len(calls) == 2

    restored = ProofControlLayer.from_state(
        control.export_state(),
        config=config,
        store=store,
        activity=None,
        proof_graph=graph,
        typed_memory=memory,
        message_broker=control.message_broker,
        route_registry=registry,
    )
    restored.update_after_round(
        strategies=strategies,
        attempts=attempts,
        current_round=3,
    )
    assert restored.common_mode.risk_families() == []
    assert all(
        restored.deepening_currently_allowed(route_id)
        for route_id in ("route-left", "route-right")
    )


def test_shadow_mode_records_detection_without_materializing_executable_work(
    tmp_path,
) -> None:
    (
        _config,
        _store,
        _registry,
        _memory,
        _graph,
        control,
        _routes,
        _strategies,
        _attempts,
    ) = _materialize_shared_dependency(tmp_path, mode="shadow")

    actions = [
        action
        for action in control.state.control_actions.values()
        if action.action_type == ControlActionType.CREATE_ASSUMPTION_CHALLENGER
    ]
    assert len(actions) == 1
    assert actions[0].status == ControlActionStatus.ADMITTED
    assert control.state.assumption_challenger_tasks == {}
    assert control.state.executable_tasks == {}
