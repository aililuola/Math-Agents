from __future__ import annotations

import shutil
from pathlib import Path

from pydantic import SecretStr

from ..config import SystemConfig, load_config
from .paths import DesktopPaths, resource_path
from .security import CredentialVault
from .settings import DesktopProfile, DesktopSettings


PROFILE_FILES: dict[DesktopProfile, str] = {
    "smoke": "config.deepseek-v4-pro.smoke.yaml",
    "formal": "config.deepseek-v4-pro.yaml",
    "active": "config.deepseek-v4-pro.topology-active.yaml",
    "proof_control_shadow": "config.deepseek-v4-pro.proof-control-shadow.yaml",
    "proof_control_active": "config.deepseek-v4-pro.proof-control-active.yaml",
}

PROFILE_LABELS: dict[DesktopProfile, str] = {
    "smoke": "冒烟验证",
    "formal": "正式求解",
    "active": "分层拓扑 Active",
    "proof_control_shadow": "证明控制 Shadow",
    "proof_control_active": "证明控制 Active（专项）",
}


class DesktopConfigService:
    def __init__(
        self,
        paths: DesktopPaths,
        credentials: CredentialVault,
    ) -> None:
        self.paths = paths
        self.credentials = credentials

    def build(
        self,
        profile: DesktopProfile,
        settings: DesktopSettings,
    ) -> SystemConfig:
        config = load_config(self.profile_path(profile))
        config.runtime.project_root = str(self.paths.root)
        config.runtime.run_root = str(self.paths.runs)
        config.computation.sandboxed_python_enabled = (
            config.computation.sandboxed_python_enabled and settings.sandbox_enabled
        )

        inspiration = config.topology.inspiration
        inspiration.cross_run_learning_path = str(self.paths.learning)
        try:
            analogy_library = resource_path("benchmarks/analogy_library.jsonl")
        except FileNotFoundError:
            analogy_library = None
        if analogy_library is not None:
            inspiration.analogy_library_path = str(analogy_library)

        missing: list[str] = []
        for agent in config.agents:
            if not agent.enabled or not agent.api_key_env:
                continue
            value = self.credentials.get(agent.api_key_env)
            if not value:
                missing.append(agent.api_key_env)
                continue
            agent.api_key = SecretStr(value)
        if missing:
            names = ", ".join(sorted(set(missing)))
            raise RuntimeError(f"缺少 API Key：{names}")
        return config

    @staticmethod
    def profile_path(profile: DesktopProfile) -> Path:
        return resource_path(PROFILE_FILES[profile])

    def profile_summaries(
        self,
        settings: DesktopSettings,
    ) -> list[dict[str, object]]:
        summaries: list[dict[str, object]] = []
        for profile, filename in PROFILE_FILES.items():
            config = load_config(resource_path(filename))
            summaries.append(
                {
                    "id": profile,
                    "label": PROFILE_LABELS[profile],
                    "filename": filename,
                    "agents": len([agent for agent in config.agents if agent.enabled]),
                    "max_calls": config.budget.max_total_calls,
                    "max_tokens": config.budget.max_total_tokens,
                    "max_cost_usd": config.budget.max_cost_usd,
                    "sandbox_configured": config.computation.sandboxed_python_enabled,
                    "sandbox_effective": (
                        config.computation.sandboxed_python_enabled
                        and settings.sandbox_enabled
                    ),
                    "selected": profile == settings.selected_profile,
                }
            )
        return summaries

    @staticmethod
    def docker_available() -> bool:
        if shutil.which("docker"):
            return True
        return any(
            candidate.exists()
            for candidate in (
                Path.home()
                / "AppData"
                / "Local"
                / "Docker"
                / "wsl"
                / "bin"
                / "docker.exe",
                Path("C:/Program Files/Docker/Docker/resources/bin/docker.exe"),
            )
        )
