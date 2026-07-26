from __future__ import annotations

from types import SimpleNamespace

import pytest

from mathproofmesh.computation.broker import ToolBroker
from mathproofmesh.orchestrator import ProofMeshOrchestrator, SolveState
from mathproofmesh.proof_control.controller import ProofControlLayer
from mathproofmesh.proof_control.models import (
    ClaimVerificationState,
    ControlActionStatus,
    ControlActionType,
)
from mathproofmesh.schemas import (
    ClaimCard,
    ClaimStatus,
    ComputationMethod,
    EvidenceStrength,
    ExperimentOutcome,
    ExperimentResult,
    ObligationKind,
    ProofObligation,
)

from v07_helpers import (
    PROBLEM_HASH,
    make_broker_runtime,
    make_proof_control_config,
    make_strategy,
)


def _runtime(tmp_path):
    config = make_proof_control_config(tmp_path / "runs", mode="active")
    store, registry, memory, graph, broker = make_broker_runtime(
        config, tmp_path / "runtime"
    )
    strategy = make_strategy(71, tag="bounded-falsification").model_copy(
        update={
            "falsification_test": "check x in [-2, 2]: x*x >= 0",
        }
    )
    route = registry.register_route(strategy, route_id="route-falsification")
    target = graph.add_obligation(
        ProofObligation(
            obligation_id="target-falsification",
            problem_hash=PROBLEM_HASH,
            route_ids=[route.route_id],
            kind=ObligationKind.MAIN_GOAL,
            statement="x*x >= 0",
            normalized_statement="x*x >= 0",
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
    return control, strategy, route, target


def test_strategy_falsification_materializes_fast_lane_task(tmp_path) -> None:
    control, strategy, route, target = _runtime(tmp_path)

    action = control.materialize_strategy_falsification(
        strategy,
        target_obligation_id=target.obligation_id,
        route_id=route.route_id,
        current_round=1,
    )

    assert action.status == ControlActionStatus.EXECUTED
    assert action.action_type == ControlActionType.MATERIALIZE_FALSIFICATION_TASK
    task = next(iter(control.state.falsification_tasks.values()))
    assert task.status == "admitted"
    assert task.experiment_spec is not None
    assert task.experiment_spec.method == ComputationMethod.BOUNDED_INTEGER_SEARCH
    assert task.experiment_spec.broad_search is False
    assert task.experiment_spec.exact_arithmetic is True
    assert task.target_obligation_id == target.obligation_id


def test_fast_lane_counterexample_refutes_claim(tmp_path) -> None:
    control, strategy, route, target = _runtime(tmp_path)
    claim = ClaimCard(
        claim_id="claim-falsified",
        statement="x*x < 0 throughout the declared finite interval.",
        assumptions=[],
        conclusion="x*x < 0",
        source_attempt_id="attempt-a",
        status=ClaimStatus.PROPOSED,
    )
    control.typed_memory.lemma_memory.add_many([claim])
    control.claim_lifecycle.register_claim(claim)
    action = control.materialize_strategy_falsification(
        strategy.model_copy(
            update={"falsification_test": "check x in [-2, 2]: x*x < 0"}
        ),
        target_obligation_id=target.obligation_id,
        target_claim_id=claim.claim_id,
        route_id=route.route_id,
        current_round=1,
    )
    task = next(
        control.state.falsification_tasks[result_ref]
        for result_ref in action.result_refs
        if result_ref in control.state.falsification_tasks
    )
    spec = task.experiment_spec
    assert spec is not None
    result = ExperimentResult(
        experiment_id=spec.experiment_id,
        request_hash=spec.request_hash,
        target_claim=spec.target_claim,
        method=spec.method,
        outcome=ExperimentOutcome.COUNTEREXAMPLE_FOUND,
        evidence_strength=EvidenceStrength.COUNTEREXAMPLE,
        counterexample={"assignment": {"x": 0}},
        exact_arithmetic=True,
        cases_checked=1,
        tool_name="bounded_integer_search",
        tool_version="test",
        independently_verified=True,
        verification_notes=["replayed exactly"],
    )

    control.record_falsification_result(result)

    entry = control.state.claim_verification_ledger[claim.claim_id]
    assert entry.state == ClaimVerificationState.REJECTED
    assert result.experiment_id in entry.invalidating_evidence_ids
    assert task.status == "counterexample_found"


def test_no_counterexample_does_not_verify_universal_claim(tmp_path) -> None:
    control, strategy, route, target = _runtime(tmp_path)
    claim = ClaimCard(
        claim_id="claim-bounded-only",
        statement="x*x >= 0 for every integer x.",
        assumptions=[],
        conclusion="x*x >= 0",
        source_attempt_id="attempt-b",
        status=ClaimStatus.PROPOSED,
    )
    control.typed_memory.lemma_memory.add_many([claim])
    control.claim_lifecycle.register_claim(claim)
    action = control.materialize_strategy_falsification(
        strategy,
        target_obligation_id=target.obligation_id,
        target_claim_id=claim.claim_id,
        route_id=route.route_id,
        current_round=1,
    )
    task = next(
        control.state.falsification_tasks[result_ref]
        for result_ref in action.result_refs
        if result_ref in control.state.falsification_tasks
    )
    spec = task.experiment_spec
    assert spec is not None
    result = ExperimentResult(
        experiment_id=spec.experiment_id,
        request_hash=spec.request_hash,
        target_claim=spec.target_claim,
        method=spec.method,
        outcome=ExperimentOutcome.NOT_REFUTED,
        evidence_strength=EvidenceStrength.BOUNDED_EVIDENCE,
        exact_arithmetic=True,
        cases_checked=5,
        tool_name="bounded_integer_search",
        tool_version="test",
    )

    control.record_falsification_result(result)

    entry = control.state.claim_verification_ledger[claim.claim_id]
    assert entry.state == ClaimVerificationState.PROPOSED
    assert task.status == "not_refuted"


def test_unsupported_falsification_is_deferred_with_reason(tmp_path) -> None:
    control, strategy, route, target = _runtime(tmp_path)
    unsupported = strategy.model_copy(
        update={
            "falsification_test": (
                "Search broadly for an unexpected geometric configuration."
            )
        }
    )

    action = control.materialize_strategy_falsification(
        unsupported,
        target_obligation_id=target.obligation_id,
        route_id=route.route_id,
        current_round=1,
    )

    assert action.status == ControlActionStatus.DEFERRED
    assert action.admission_reason
    task = next(iter(control.state.falsification_tasks.values()))
    assert task.status == "deferred"
    assert task.deferred_reason
    assert task.experiment_spec is None


@pytest.mark.asyncio
async def test_materialized_falsification_runs_before_route_turn(tmp_path) -> None:
    control, strategy, _route, _target = _runtime(tmp_path)
    control.config.computation.enabled = True
    control.register_strategy(strategy)
    orchestrator = ProofMeshOrchestrator(control.config)
    tools = ToolBroker(control.config, control.store)
    state = SolveState(
        triage=None,
        strategies=[strategy],
        attempts=[],
        reports=[],
        aggregate_reports={},
        meta_reviews=[],
        checkpoints=[],
        route_registry=control.route_registry,
        proof_graph=control.proof_graph,
        proof_control=control,
    )

    feedback, results = await orchestrator._run_materialized_falsification_tasks(
        SimpleNamespace(integrity_hash=PROBLEM_HASH),
        strategy,
        SimpleNamespace(id="route-agent"),
        state=state,
        round_index=1,
        path_id="path-fast-lane",
        parent_checkpoint_id=None,
        meta_review_approved=False,
        runner=SimpleNamespace(ledger=SimpleNamespace(remaining_calls=8)),
        prompts=SimpleNamespace(),
        tools=tools,
        budget_bucket="route",
    )

    task = next(iter(control.state.falsification_tasks.values()))
    assert feedback[0]["rule_id"] == "fast_path.proof_control_falsification"
    assert results[0]["experiment_id"] == task.experiment_spec.experiment_id
    assert task.status == "not_refuted"
    assert task.result_experiment_id == task.experiment_spec.experiment_id
    assert tools.results_for_path("path-fast-lane")
