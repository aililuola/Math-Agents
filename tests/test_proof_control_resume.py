from __future__ import annotations

import json
from pathlib import Path

from mathproofmesh.mock_demo import demo_responders
from mathproofmesh.orchestrator import ProofMeshOrchestrator
from mathproofmesh.proof_control.controller import ProofControlLayer
from mathproofmesh.resume_phase import export_hierarchical_checkpoint
from mathproofmesh.schemas import ObligationKind, ProblemContract, ProofObligation
from mathproofmesh.store import ArtifactStore

from v07_helpers import (
    PROBLEM_HASH,
    make_broker_runtime,
    make_proof_control_config,
    make_strategy,
)


def _add_main_goal(graph) -> ProofObligation:
    return graph.add_obligation(
        ProofObligation(
            obligation_id="main-goal",
            problem_hash=PROBLEM_HASH,
            route_ids=["route-a", "route-b", "route-c"],
            kind=ObligationKind.MAIN_GOAL,
            statement="Prove the target identity.",
            normalized_statement="prove the target identity.",
            priority=1.0,
            centrality=1.0,
        )
    )


def test_proof_control_sidecar_round_trips_without_changing_v07_state(
    tmp_path: Path,
) -> None:
    config = make_proof_control_config(tmp_path / "runs", mode="active")
    store, registry, memory, graph, broker = make_broker_runtime(
        config, tmp_path / "runtime"
    )
    main_goal = _add_main_goal(graph)
    graph_hash_before = main_goal.content_hash
    controller = ProofControlLayer(
        config,
        store,
        None,
        graph,
        memory,
        broker,
        registry,
    )
    controller.register_obligation(main_goal)
    controller.register_strategy(make_strategy(0))
    controller.allow_deepen(
        route_id="route-a",
        segment_index=1,
        report=None,
        core_obligation_closed=False,
        core_debt_reduced=False,
        verified_bridge_gain=False,
    )

    checkpoint = export_hierarchical_checkpoint(
        current_round=1,
        graph_frozen=False,
        final_repair_failed=False,
        proof_debt_history={},
        route_team_reviews={},
        capability_domain="algebra",
        route_registry=registry,
        typed_memory=memory,
        proof_graph=graph,
        message_broker=broker,
        bridge_broker=None,
        contradiction_broker=None,
        inspiration_engine=None,
        capability_profile=None,
        proof_control=controller,
    )
    legacy_shape = export_hierarchical_checkpoint(
        current_round=1,
        graph_frozen=False,
        final_repair_failed=False,
        proof_debt_history={},
        route_team_reviews={},
        capability_domain="algebra",
        route_registry=registry,
        typed_memory=memory,
        proof_graph=graph,
        message_broker=broker,
        bridge_broker=None,
        contradiction_broker=None,
        inspiration_engine=None,
        capability_profile=None,
        proof_control=None,
    )

    assert checkpoint["proof_control_state"]["schema_version"] == "0.8"
    assert "proof_control_state" not in legacy_shape
    before = controller.export_state()
    restored = ProofControlLayer.from_state(
        checkpoint["proof_control_state"],
        config=config,
        store=store,
        activity=None,
        proof_graph=graph,
        typed_memory=memory,
        message_broker=broker,
        route_registry=registry,
    )
    assert restored.export_state() == before
    assert (
        graph.get_obligation(main_goal.obligation_id).content_hash == graph_hash_before
    )


async def test_active_resume_migrates_a_v07_checkpoint_exactly_once(
    tmp_path: Path,
) -> None:
    config = make_proof_control_config(tmp_path / "runs", mode="active")
    config.budget.max_rounds = 2
    run_id = "proof-control-v07-migration"
    store = ArtifactStore(config.runtime.run_root, run_id)
    problem = ProblemContract(
        exact_statement=(
            "Prove that for every positive integer n, 1+3+...+(2n-1)=n^2."
        ),
        normalized_statement="the first n odd integers sum to n squared",
    )
    store.write_json("structured", "problem_contract", problem)
    store.checkpoint(
        "triage",
        {
            "schema_version": "0.7",
            "triage": None,
            "strategies": [],
            "attempts": [],
            "reports": [],
            "aggregate_reports": {},
            "meta_reviews": [],
            "claims": [],
            "calls_started": 0,
            "stage_calls": {},
            "bucket_calls": {},
            "agent_metrics": [],
        },
    )

    first = await ProofMeshOrchestrator(
        config,
        mock_responders=demo_responders(config),
    ).resume(run_id)
    assert Path(first.run_directory, "structured", "proof_control.json").exists()
    latest = ArtifactStore(config.runtime.run_root, run_id).latest_stage_checkpoint()
    assert latest is not None
    assert latest[1]["schema_version"] == "0.8"
    assert latest[1]["proof_control_state"]["schema_version"] == "0.8"

    second = await ProofMeshOrchestrator(
        config,
        mock_responders=demo_responders(config),
    ).resume(run_id)
    events = [
        json.loads(line)
        for line in Path(second.run_directory, "events.jsonl")
        .read_text(encoding="utf-8")
        .splitlines()
    ]
    assert (
        sum(
            event.get("event_type") == "checkpoint_migrated_to_v0_8" for event in events
        )
        == 1
    )
