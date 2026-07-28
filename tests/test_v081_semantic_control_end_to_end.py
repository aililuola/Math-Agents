from __future__ import annotations

import ast
import json
from pathlib import Path

import pytest

from mathproofmesh.mock_demo import demo_responders
from mathproofmesh.orchestrator import ProofMeshOrchestrator
from mathproofmesh.schemas import RunStatus

from v07_helpers import make_proof_control_config


PROBLEM = "Prove that the sum of the first n odd integers is n squared."


@pytest.mark.parametrize(
    ("mode", "inspiration_mode"),
    [
        ("off", "off"),
        ("shadow", "off"),
        ("active", "off"),
        ("active", "active"),
    ],
)
async def test_v081_control_modes_complete_offline_end_to_end(
    tmp_path: Path,
    mode: str,
    inspiration_mode: str,
) -> None:
    config = make_proof_control_config(
        tmp_path / f"{mode}-{inspiration_mode}" / "runs",
        mode=mode,
        inspiration_mode=inspiration_mode,
    )
    result = await ProofMeshOrchestrator(
        config,
        mock_responders=demo_responders(config),
    ).solve(
        PROBLEM,
        run_id=f"v081-{mode}-{inspiration_mode}",
    )

    assert result.status == RunStatus.VERIFIED
    root = Path(result.run_directory)
    sidecar_path = root / "structured" / "proof_control.json"
    summary_path = root / "reports" / "proof_control_summary.json"
    if mode == "off":
        assert not sidecar_path.exists()
        assert not summary_path.exists()
        return

    sidecar = json.loads(sidecar_path.read_text(encoding="utf-8"))
    summary = json.loads(summary_path.read_text(encoding="utf-8"))
    assert sidecar["schema_version"] == "0.8.2"
    assert summary["mode"] == mode
    assert "meta_pivot_state" in sidecar
    assert "meta_pivot" in summary
    assert {
        "control_actions",
        "route_target_bindings",
        "claim_verification_ledger",
        "obligation_domains",
        "route_update_tasks",
        "inspiration_review_deferrals",
    } <= sidecar.keys()


def test_proof_control_conditionals_are_problem_agnostic() -> None:
    package = Path(__file__).parents[1] / "src" / "mathproofmesh" / "proof_control"
    forbidden_literals = {
        "prime",
        "gcd",
        "a_n",
        "remove_large_prime",
    }
    violations: list[str] = []
    for path in package.rglob("*.py"):
        source = path.read_text(encoding="utf-8")
        tree = ast.parse(source, filename=str(path))
        for node in ast.walk(tree):
            if not isinstance(node, (ast.If, ast.IfExp, ast.While)):
                continue
            condition = ast.unparse(node.test).casefold()
            if any(value in condition for value in forbidden_literals):
                violations.append(f"{path.name}:{node.lineno}:{condition}")
            if (
                "problem_hash" in condition
                and "==" in condition
                and ("integrity_hash" not in condition)
            ):
                violations.append(f"{path.name}:{node.lineno}:{condition}")

    assert violations == []
