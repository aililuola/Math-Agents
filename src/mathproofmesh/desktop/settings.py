from __future__ import annotations

import json
import os
import tempfile
from pathlib import Path
from typing import Literal

from pydantic import BaseModel, ConfigDict


DesktopProfile = Literal["smoke", "formal", "active"]


class DesktopSettings(BaseModel):
    model_config = ConfigDict(extra="forbid", validate_assignment=True)

    selected_profile: DesktopProfile = "smoke"
    sandbox_enabled: bool = True
    remember_credentials: bool = True


class SettingsStore:
    def __init__(self, path: str | Path) -> None:
        self.path = Path(path)

    def load(self) -> DesktopSettings:
        if not self.path.exists():
            return DesktopSettings()
        try:
            return DesktopSettings.model_validate_json(
                self.path.read_text(encoding="utf-8")
            )
        except (OSError, ValueError):
            return DesktopSettings()

    def save(self, settings: DesktopSettings) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        content = json.dumps(
            settings.model_dump(mode="json"),
            ensure_ascii=False,
            indent=2,
            sort_keys=True,
        )
        fd, temp_name = tempfile.mkstemp(
            prefix=f".{self.path.name}.", dir=str(self.path.parent)
        )
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as handle:
                handle.write(content)
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(temp_name, self.path)
        finally:
            if os.path.exists(temp_name):
                os.unlink(temp_name)
