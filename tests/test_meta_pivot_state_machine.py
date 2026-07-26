from __future__ import annotations

import asyncio

from mathproofmesh.orchestrator import ProofMeshOrchestrator, SolveState
from mathproofmesh.proof_control.controller import ProofControlLayer
from mathproofmesh.proof_control.models import (
    ControlActionStatus,
    MetaPivotStatus,
)

from v07_helpers import make_broker_runtime, make_proof_control_config


def _runtime(tmp_path):
    config = make_proof_control_config(tmp_path / "runs", mode="active")
    store, registry, memory, graph, broker = make_broker_runtime(
        config, tmp_path / "runtime"
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
    orchestrator = ProofMeshOrchestrator(config)
    state = SolveState(
        triage=None,
        strategies=[],
        attempts=[],
        reports=[],
        aggregate_reports={},
        meta_reviews=[],
        checkpoints=[],
        route_registry=registry,
        typed_memory=memory,
        proof_graph=graph,
        message_broker=broker,
        proof_control=control,
    )
    return config, store, control, orchestrator, state


def test_pending_meta_pivot_blocks_hard_stagnation_stop(tmp_path) -> None:
    _config, store, control, orchestrator, state = _runtime(tmp_path)
    state.last_progress_signature = orchestrator._global_progress_signature(state)

    assert not orchestrator._apply_global_progress_gate(
        state, round_index=1, store=store
    )
    assert not orchestrator._apply_global_progress_gate(
        state, round_index=2, store=store
    )
    assert not orchestrator._apply_global_progress_gate(
        state, round_index=3, store=store
    )
    assert control.state.meta_pivot_state is not None
    assert control.state.meta_pivot_state.status == MetaPivotStatus.REQUESTED


def test_meta_pivot_executes_before_evaluation(tmp_path) -> None:
    _config, _store, control, _orchestrator, _state = _runtime(tmp_path)
    pivot = control.request_meta_pivot(
        source_stagnation_signature="plateau-a",
        trigger_round=2,
        requested_mechanisms=["representation_switch"],
    )

    async def execute(_pivot):
        return {
            "created_route_ids": ["route-pivot"],
            "result_fact_ids": [],
            "result_obligation_ids": ["obligation-pivot"],
        }

    executed = asyncio.run(
        control.execute_pending_meta_pivot(
            current_round=3,
            executor=execute,
        )
    )

    assert executed.pivot_id == pivot.pivot_id
    assert executed.status == MetaPivotStatus.EXECUTED
    assert executed.no_progress_after_pivot is None
    evaluated = control.evaluate_meta_pivot(
        progress_signature="plateau-b",
        current_round=3,
    )
    assert evaluated.status == MetaPivotStatus.EVALUATED
    assert evaluated.no_progress_after_pivot is False


def test_meta_pivot_resume_exactly_once(tmp_path) -> None:
    config, store, control, _orchestrator, _state = _runtime(tmp_path)
    control.request_meta_pivot(
        source_stagnation_signature="plateau-resume",
        trigger_round=2,
        requested_mechanisms=["meta_replan"],
    )
    calls = 0

    async def execute(_pivot):
        nonlocal calls
        calls += 1
        return {
            "created_route_ids": ["route-created-once"],
            "result_fact_ids": [],
            "result_obligation_ids": [],
        }

    asyncio.run(
        control.execute_pending_meta_pivot(
            current_round=3,
            executor=execute,
        )
    )
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
        restored.execute_pending_meta_pivot(
            current_round=4,
            executor=execute,
        )
    )

    assert calls == 1
    assert restored.state.meta_pivot_state is not None
    assert restored.state.meta_pivot_state.created_route_ids == ["route-created-once"]


def test_failed_meta_pivot_has_explicit_reason(tmp_path) -> None:
    _config, _store, control, _orchestrator, _state = _runtime(tmp_path)
    pivot = control.request_meta_pivot(
        source_stagnation_signature="plateau-failed",
        trigger_round=2,
        requested_mechanisms=["meta_replan"],
    )

    async def fail(_pivot):
        raise RuntimeError("no independent pivot agent is available")

    failed = asyncio.run(
        control.execute_pending_meta_pivot(
            current_round=3,
            executor=fail,
        )
    )

    assert failed.status == MetaPivotStatus.FAILED
    assert "no independent pivot agent" in failed.failure_reason
    action = control.state.control_actions[pivot.action_id]
    assert action.status == ControlActionStatus.FAILED


def test_stop_allowed_after_empty_pivot(tmp_path) -> None:
    config, store, control, orchestrator, state = _runtime(tmp_path)
    signature = orchestrator._global_progress_signature(state)
    control.request_meta_pivot(
        source_stagnation_signature=signature,
        trigger_round=2,
        requested_mechanisms=["representation_switch"],
    )

    async def execute(_pivot):
        return {
            "created_route_ids": [],
            "result_fact_ids": [],
            "result_obligation_ids": [],
        }

    pivot = asyncio.run(
        control.execute_pending_meta_pivot(
            current_round=3,
            executor=execute,
        )
    )
    assert pivot.status == MetaPivotStatus.FAILED
    assert control.meta_pivot_allows_stagnation_stop(progress_signature=signature)
    state.last_progress_signature = signature
    state.global_no_progress_rounds = (
        config.scheduler.global_no_progress_rounds_before_stop - 1
    )

    assert orchestrator._apply_global_progress_gate(
        state,
        round_index=4,
        store=store,
    )
