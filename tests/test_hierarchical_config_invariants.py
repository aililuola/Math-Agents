from __future__ import annotations

import pytest
from pydantic import ValidationError

from mathproofmesh.config import SystemConfig

from v07_helpers import make_v07_config


def test_active_hierarchical_topology_requires_continuation(tmp_path) -> None:
    config = make_v07_config(tmp_path / "runs")
    payload = config.model_dump(mode="python")
    payload["continuation"]["enabled"] = False
    with pytest.raises(ValidationError, match="requires continuation.enabled=true"):
        SystemConfig.model_validate(payload)
