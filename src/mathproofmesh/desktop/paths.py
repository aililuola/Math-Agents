from __future__ import annotations

import os
import sys
from dataclasses import dataclass
from pathlib import Path


APP_DIRECTORY_NAME = "MathProofMesh"


@dataclass(frozen=True)
class DesktopPaths:
    root: Path
    runs: Path
    config: Path
    logs: Path
    learning: Path

    @classmethod
    def discover(cls, root: str | Path | None = None) -> "DesktopPaths":
        if root is None:
            override = os.getenv("MATHPROOFMESH_DESKTOP_HOME")
            if override:
                root_path = Path(override)
            else:
                local_app_data = os.getenv("LOCALAPPDATA")
                base = (
                    Path(local_app_data)
                    if local_app_data
                    else Path.home() / "AppData" / "Local"
                )
                root_path = base / APP_DIRECTORY_NAME
        else:
            root_path = Path(root)

        resolved = root_path.expanduser().resolve()
        paths = cls(
            root=resolved,
            runs=resolved / "runs",
            config=resolved / "config",
            logs=resolved / "logs",
            learning=resolved / "learning",
        )
        paths.ensure()
        return paths

    def ensure(self) -> None:
        for path in (self.root, self.runs, self.config, self.logs, self.learning):
            path.mkdir(parents=True, exist_ok=True)

    @property
    def settings_file(self) -> Path:
        return self.config / "desktop-settings.json"

    @property
    def credentials_file(self) -> Path:
        return self.config / "credentials.dpapi.json"

    @property
    def log_file(self) -> Path:
        return self.logs / "mathproofmesh-desktop.log"


def bundle_root() -> Path:
    frozen_root = getattr(sys, "_MEIPASS", None)
    if frozen_root:
        return Path(frozen_root).resolve()
    return Path(__file__).resolve().parents[3]


def resource_path(relative: str | Path) -> Path:
    path = (bundle_root() / Path(relative)).resolve()
    if not path.exists():
        raise FileNotFoundError(f"Desktop resource is missing: {path}")
    return path


def web_root() -> Path:
    source_path = Path(__file__).resolve().parent / "web"
    if source_path.exists():
        return source_path
    return resource_path("desktop-web")
