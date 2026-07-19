from __future__ import annotations

from pathlib import Path

import pytest

from mathproofmesh.mock_demo import build_demo_config, demo_responders
from mathproofmesh.orchestrator import ProofMeshOrchestrator
from mathproofmesh.schemas import RunStatus, VerificationVerdict


@pytest.mark.asyncio
async def test_mock_end_to_end_generates_auditable_run(tmp_path: Path) -> None:
    config = build_demo_config(str(tmp_path / "runs"))
    result = await ProofMeshOrchestrator(
        config,
        mock_responders=demo_responders(config),
    ).solve(
        "Prove that for every positive integer n, 1+3+...+(2n-1)=n^2.",
        run_id="e2e",
    )

    assert result.status == RunStatus.VERIFIED
    assert result.final_verification is not None
    assert result.final_verification.verdict == VerificationVerdict.PASS
    assert result.total_calls > 0
    root = Path(result.run_directory)
    assert (root / "structured" / "run_result.json").exists()
    assert (root / "reports" / "communication_graph.json").exists()
    assert (root / "reports" / "run_report.md").exists()
    assert (root / "activity.jsonl").exists()
    assert (root / "reports" / "activity_timeline.md").exists()
    assert (root / "reports" / "activity_timeline.json").exists()
    timeline = (root / "reports" / "activity_timeline.md").read_text(encoding="utf-8")
    assert "多 Agent 求解结束" in timeline
    assert "原始私有思考链" in timeline
    assert list((root / "raw").glob("*.json"))
