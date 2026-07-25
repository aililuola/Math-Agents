from __future__ import annotations

from mathproofmesh.orchestrator import ProofMeshOrchestrator, SolveState
from mathproofmesh.proof_control.controller import ProofControlLayer

from v07_helpers import make_broker_runtime, make_proof_control_config


def test_pending_meta_pivot_blocks_hard_stagnation_stop(tmp_path) -> None:
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
