from __future__ import annotations

import json
from pathlib import Path

from mathproofmesh.mock_demo import demo_responders
from mathproofmesh.orchestrator import ProofMeshOrchestrator
from mathproofmesh.schemas import RunStatus

from v07_helpers import make_v07_config


def _events(root: Path) -> list[dict[str, object]]:
    return [
        json.loads(line)
        for line in (root / "events.jsonl").read_text(encoding="utf-8").splitlines()
    ]


async def test_active_route_uses_real_prover_skeptic_referee_pipeline(
    tmp_path,
) -> None:
    config = make_v07_config(tmp_path / "runs", inspiration_mode="off")
    config.continuation.enabled = True
    config.continuation.segments_per_explore_call = 1
    result = await ProofMeshOrchestrator(
        config,
        mock_responders=demo_responders(config),
    ).solve(
        "Prove that for every positive integer n, 1+3+...+(2n-1)=n^2.",
        run_id="active-route-team",
    )

    assert result.status == RunStatus.VERIFIED
    events = _events(Path(result.run_directory))
    names = [str(item["event_type"]) for item in events]
    assert "route_team_started" in names
    assert "route_skeptic_completed" in names
    assert "route_referee_completed" in names
    assert "route_local_review_completed" in names
    assert names.index("route_team_started") < names.index("route_referee_completed")
    reviews = [
        item["payload"]
        for item in events
        if item["event_type"] == "route_local_review_completed"
    ]
    assert reviews
    assert all(review["referee_agent_id"] for review in reviews)
    assert all(review["global_share_allowed"] is True for review in reviews)
