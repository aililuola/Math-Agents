from __future__ import annotations

import json
from pathlib import Path

from mathproofmesh.report import write_hierarchical_reports
from mathproofmesh.schemas import (
    ProofCheckpoint,
    ProofDelta,
    ProofStep,
    WorkingProofCheckpoint,
)
from mathproofmesh.store import ArtifactStore


def test_working_checkpoint_never_advances_verified_resume_pointer(
    tmp_path: Path,
) -> None:
    store = ArtifactStore(tmp_path / "runs", "working-isolation")
    verified = ProofCheckpoint(
        checkpoint_id="verified-0",
        problem_hash="p" * 64,
        path_id="route-a",
        strategy_id="strategy-a",
        segment_index=0,
        remaining_subgoals=["prove the induction step"],
        current_goal="prove the induction step",
    )
    store.commit_proof_checkpoint(verified)
    delta = ProofDelta(
        problem_hash=verified.problem_hash,
        path_id=verified.path_id,
        strategy_id=verified.strategy_id,
        parent_checkpoint_id=verified.checkpoint_id,
        agent_id="explorer-a",
        round_index=1,
        segment_index=1,
        new_steps=[
            ProofStep(
                step_id="candidate-step",
                statement="A useful but not yet reviewed intermediate identity.",
                justification="Route-local derivation awaiting independent review.",
            )
        ],
        remaining_subgoals=["independently verify the identity"],
        current_goal="independently verify the identity",
    )
    working = WorkingProofCheckpoint(
        parent_verified_checkpoint_id=verified.checkpoint_id,
        problem_hash=verified.problem_hash,
        path_id=verified.path_id,
        strategy_id=verified.strategy_id,
        source_agent_id="explorer-a",
        segment_index=1,
        delta=delta,
    )

    store.save_working_checkpoint(working)

    restored_verified = store.load_latest_proof_checkpoint(verified.path_id)
    assert restored_verified is not None
    assert restored_verified.checkpoint_id == verified.checkpoint_id
    assert store.load_latest_working_checkpoint(verified.path_id) == working


def test_hierarchical_metrics_distinguish_delivery_consumption_and_use(
    tmp_path: Path,
) -> None:
    store = ArtifactStore(tmp_path / "runs", "metric-semantics")
    write_hierarchical_reports(
        store,
        route_registry={"routes": []},
        message_broker={
            "messages": {"m1": {}, "m2": {}},
            "decisions": [
                {"message_id": "m1", "accepted": True},
                {"message_id": "m1-copy", "accepted": True, "duplicate_of": "m1"},
                {"message_id": "m2", "accepted": True},
            ],
            "deliveries": {
                "d1": {"message_id": "m1", "prompt_consumed": True},
                "d2": {"message_id": "m2", "prompt_consumed": True},
                "d3": {"message_id": "m2", "prompt_consumed": False},
            },
            "receipts": {
                "r1": {"status": "accepted"},
                "r2": {"status": "rejected"},
            },
            "utility_records": {"u1": {"message_id": "m1"}},
        },
        proof_graph={"obligations": {}, "edges": {}},
        typed_memory={"tiers": {}},
        bridge_broker={"tasks": []},
        contradiction_broker={"records": []},
        inspiration_engine={
            "materializations": {
                "p1": {"action": "stored_insight"},
                "p2": {"action": "stored_insight"},
                "p3": {"action": "rejected"},
                "p4": {"action": "route_created"},
            }
        },
    )

    metrics = json.loads(
        (store.root / "reports" / "hierarchical_metrics.json").read_text(
            encoding="utf-8"
        )
    )
    assert metrics["message_publication_attempts"] == 3
    assert metrics["messages_published_unique"] == 2
    assert metrics["delivery_records"] == 3
    assert metrics["messages_consumed"] == 2
    assert metrics["messages_semantically_accepted"] == 1
    assert metrics["messages_mathematically_used"] == 1
    assert metrics["inspiration_materialization_actions"] == {
        "rejected": 1,
        "route_created": 1,
        "stored_insight": 2,
    }
