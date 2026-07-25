from __future__ import annotations

from pathlib import Path
from typing import Any

from mathproofmesh.config import load_config


ROOT = Path(__file__).parents[1]
BASELINE = ROOT / "config.deepseek-v4-pro.topology-active.yaml"
PROOF_CONTROL_CONFIGS = (
    ROOT / "config.deepseek-v4-pro.proof-control-shadow.yaml",
    ROOT / "config.deepseek-v4-pro.proof-control-active.yaml",
)


def _without_proof_control(config_path: Path) -> dict[str, Any]:
    payload = load_config(config_path).model_dump(mode="json")
    payload.pop("system_name", None)
    payload["topology"].pop("proof_control", None)
    return payload


def _frozen_reasoning_limits(config_path: Path) -> dict[str, Any]:
    payload = load_config(config_path).model_dump(mode="json")
    frozen: dict[str, Any] = {}

    def visit(value: Any, path: tuple[str, ...] = ()) -> None:
        if isinstance(value, dict):
            for key, item in value.items():
                child = (*path, str(key))
                if "output_token" in str(key) or key in {
                    "segments_per_explore_call",
                    "tiers",
                    "high_tier_threshold_tokens",
                    "partial_repair_max_output_tokens",
                }:
                    frozen[".".join(child)] = item
                else:
                    visit(item, child)
        elif isinstance(value, list):
            for index, item in enumerate(value):
                visit(item, (*path, str(index)))

    visit(payload)
    return frozen


def test_proof_control_profiles_only_change_control_configuration() -> None:
    baseline = _without_proof_control(BASELINE)

    for config_path in PROOF_CONTROL_CONFIGS:
        assert _without_proof_control(config_path) == baseline


def test_all_reasoning_and_deep_exploration_limits_are_frozen() -> None:
    baseline = _frozen_reasoning_limits(BASELINE)
    assert baseline

    for config_path in PROOF_CONTROL_CONFIGS:
        assert _frozen_reasoning_limits(config_path) == baseline
