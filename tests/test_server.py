from __future__ import annotations

from pathlib import Path

import pytest
import yaml

from mathproofmesh.server import create_app

TestClient = pytest.importorskip("fastapi.testclient").TestClient


def _write_mock_config(path: Path, run_root: Path) -> None:
    payload = {
        "system_name": "server-test",
        "agents": [
            {
                "id": "mock-agent",
                "provider": "mock",
                "model": "mock",
                "roles": [
                    "planner",
                    "explorer",
                    "summarizer",
                    "structural_verifier",
                    "detailed_verifier",
                    "meta_reviewer",
                    "synthesizer",
                    "final_verifier",
                ],
            }
        ],
        "budget": {
            "max_total_calls": 8,
            "initial_paths": 1,
            "max_paths": 1,
            "strategies_to_generate": 1,
        },
        "continuation": {"enabled": True, "process_resume_enabled": True},
        "runtime": {"run_root": str(run_root), "activity_mode": "off"},
    }
    path.write_text(yaml.safe_dump(payload), encoding="utf-8")


def test_server_exposes_resume_routes_and_health(tmp_path: Path) -> None:
    config_path = tmp_path / "config.yaml"
    _write_mock_config(config_path, tmp_path / "runs")
    client = TestClient(create_app(str(config_path)))

    health = client.get("/health")
    assert health.status_code == 200
    body = health.json()
    assert body["resume_endpoint"] == "/resume"
    assert body["resume_stream"] == "/resume/stream"
    assert body["checkpoint_resume_enabled"] is True

    missing = client.post("/resume", json={"run_id": "does-not-exist"})
    assert missing.status_code == 400
    assert "cannot be resumed" in missing.json()["detail"]
