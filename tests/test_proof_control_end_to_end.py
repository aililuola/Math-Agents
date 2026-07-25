from __future__ import annotations

import json
from pathlib import Path

import pytest

from mathproofmesh.mock_demo import demo_responders
from mathproofmesh.orchestrator import ProofMeshOrchestrator
from mathproofmesh.proof_control.models import GateVerdict
from mathproofmesh.schemas import RunStatus
from mathproofmesh.store import ArtifactStore

from v07_helpers import make_proof_control_config


PROBLEM = "Prove that for every positive integer n, 1+3+...+(2n-1)=n^2."


@pytest.mark.parametrize("mode", ["shadow", "active"])
async def test_mock_proof_control_modes_are_auditable_and_offline(
    tmp_path: Path,
    mode: str,
) -> None:
    config = make_proof_control_config(tmp_path / mode / "runs", mode=mode)
    result = await ProofMeshOrchestrator(
        config,
        mock_responders=demo_responders(config),
    ).solve(PROBLEM, run_id=f"proof-control-{mode}")

    assert result.status == RunStatus.VERIFIED
    root = Path(result.run_directory)
    sidecar = json.loads(
        (root / "structured" / "proof_control.json").read_text(encoding="utf-8")
    )
    summary = json.loads(
        (root / "reports" / "proof_control_summary.json").read_text(encoding="utf-8")
    )
    checkpoint = ArtifactStore(
        config.runtime.run_root, f"proof-control-{mode}"
    ).latest_stage_checkpoint()

    assert sidecar["schema_version"] == "0.8"
    assert summary["schema_version"] == "0.8"
    assert summary["mode"] == mode
    assert sidecar["route_admissions"]
    assert sidecar["synthesis_readiness_records"]
    assert checkpoint is not None
    assert checkpoint[1]["schema_version"] == "0.8"
    assert checkpoint[1]["proof_control_state"] == sidecar
    assert all(
        item["verdict"]
        in {
            GateVerdict.PASS.value,
            (
                GateVerdict.SHADOW_BLOCK.value
                if mode == "shadow"
                else GateVerdict.BLOCK.value
            ),
        }
        for item in sidecar["synthesis_readiness_records"]
    )


async def test_active_synthesis_gate_is_evaluated_before_synthesizer(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    config = make_proof_control_config(tmp_path / "runs", mode="active")
    calls: list[str] = []
    original_gate = ProofMeshOrchestrator._proof_control_allows_synthesis
    original_synthesize = ProofMeshOrchestrator._synthesize

    def observed_gate(self, state):
        calls.append("readiness")
        return original_gate(self, state)

    async def observed_synthesize(self, *args, **kwargs):
        calls.append("synthesize")
        return await original_synthesize(self, *args, **kwargs)

    monkeypatch.setattr(
        ProofMeshOrchestrator,
        "_proof_control_allows_synthesis",
        observed_gate,
    )
    monkeypatch.setattr(
        ProofMeshOrchestrator,
        "_synthesize",
        observed_synthesize,
    )
    result = await ProofMeshOrchestrator(
        config,
        mock_responders=demo_responders(config),
    ).solve(PROBLEM, run_id="proof-control-synthesis-order")

    assert result.status == RunStatus.VERIFIED
    assert "synthesize" in calls
    synthesis_index = calls.index("synthesize")
    assert "readiness" in calls[:synthesis_index]
