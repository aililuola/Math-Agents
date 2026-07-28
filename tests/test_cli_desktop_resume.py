from __future__ import annotations

from pathlib import Path

import pytest

from mathproofmesh.cli import _apply_desktop_resume_context
from mathproofmesh.config import load_config
from mathproofmesh.desktop.configuration import DesktopConfigService
from mathproofmesh.desktop.paths import DesktopPaths
from mathproofmesh.desktop.settings import DesktopSettings, SettingsStore


def test_cli_resume_can_reuse_desktop_paths_and_credentials(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    paths = DesktopPaths.discover(tmp_path / "desktop")
    SettingsStore(paths.settings_file).save(
        DesktopSettings(
            selected_profile="proof_control_active",
            sandbox_enabled=False,
        )
    )
    for index in range(1, 6):
        monkeypatch.setenv(f"DEEPSEEK_AGENT_{index}_KEY", f"secret-{index}")

    config = load_config(DesktopConfigService.profile_path("proof_control_active"))
    prepared = _apply_desktop_resume_context(config, paths.root)

    assert prepared.runtime.project_root == str(paths.root)
    assert prepared.runtime.run_root == str(paths.runs)
    assert prepared.computation.sandboxed_python_enabled is False
    assert prepared.topology.inspiration.cross_run_learning_path == str(paths.learning)
    assert {
        agent.api_key.get_secret_value()
        for agent in prepared.agents
        if agent.enabled and agent.api_key is not None
    } == {f"secret-{index}" for index in range(1, 6)}
