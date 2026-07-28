from __future__ import annotations

from pathlib import Path

import pytest
from pydantic import ValidationError

from mathproofmesh.config import SystemConfig, load_config

from v07_helpers import make_v07_config


def test_existing_yaml_defaults_proof_control_off() -> None:
    config = load_config(Path(__file__).parents[1] / "config.example.yaml")

    assert config.topology.proof_control.enabled is False
    assert config.topology.proof_control.mode == "off"


def test_shadow_requires_explicit_enablement(tmp_path: Path) -> None:
    payload = make_v07_config(tmp_path).model_dump(mode="python")
    payload["topology"]["proof_control"] = {
        "enabled": False,
        "mode": "shadow",
    }

    with pytest.raises(
        ValidationError, match="proof_control.mode requires proof_control.enabled=true"
    ):
        SystemConfig.model_validate(payload)


def test_active_requires_hierarchical_dependencies(tmp_path: Path) -> None:
    payload = make_v07_config(tmp_path).model_dump(mode="python")
    payload["topology"]["proof_control"] = {
        "enabled": True,
        "mode": "active",
    }
    active = SystemConfig.model_validate(payload)
    assert active.topology.proof_control.mode == "active"

    payload["topology"]["proof_graph"]["mode"] = "shadow"
    with pytest.raises(
        ValidationError, match="active proof control requires active proof graph"
    ):
        SystemConfig.model_validate(payload)


@pytest.mark.parametrize(
    ("field", "message"),
    [
        ("auto_fact_promotion", "must never auto-promote Fact"),
        ("allow_sandboxed_python", "cannot use sandboxed Python"),
    ],
)
def test_fast_lane_rejects_fact_or_sandbox_bypass(
    tmp_path: Path, field: str, message: str
) -> None:
    payload = make_v07_config(tmp_path).model_dump(mode="python")
    payload["topology"]["proof_control"] = {"falsification_fast_lane": {field: True}}

    with pytest.raises(ValidationError, match=message):
        SystemConfig.model_validate(payload)
