from __future__ import annotations

import json
from pathlib import Path

from mathproofmesh.mock_demo import demo_responders
from mathproofmesh.orchestrator import ProofMeshOrchestrator
from mathproofmesh.schemas import RunStatus

from v07_helpers import make_v07_config


async def test_hierarchical_shadow_mock_run_is_auditable_and_verified(tmp_path) -> None:
    config = make_v07_config(
        tmp_path / "runs", graph_mode="shadow", inspiration_mode="shadow"
    )
    result = await ProofMeshOrchestrator(
        config, mock_responders=demo_responders(config)
    ).solve(
        "Prove that for every positive integer n, 1+3+...+(2n-1)=n^2.",
        run_id="v07-hierarchical-shadow",
    )
    assert result.status == RunStatus.VERIFIED
    root = Path(result.run_directory)
    for relative in (
        "structured/blind_final_review_packet.json",
        "structured/route_registry.json",
        "structured/message_broker.json",
        "structured/proof_graph.json",
        "structured/typed_memory.json",
        "reports/communication_topology.json",
        "reports/proof_graph.json",
        "reports/message_diagnostics.md",
        "reports/hierarchical_metrics.json",
    ):
        assert (root / relative).exists(), relative
    metrics = json.loads(
        (root / "reports/hierarchical_metrics.json").read_text(encoding="utf-8")
    )
    assert metrics["route_count"] == 3
    assert metrics["inspiration_mode"] == "shadow"
    assert metrics["graph_mode"] == "shadow"
