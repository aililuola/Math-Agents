from __future__ import annotations

import asyncio
import json
from pathlib import Path
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

from mathproofmesh.desktop.app import create_desktop_app
from mathproofmesh.desktop.configuration import DesktopConfigService
from mathproofmesh.desktop.manager import DesktopRunManager
from mathproofmesh.desktop.paths import DesktopPaths
from mathproofmesh.desktop.repository import RunRepository
from mathproofmesh.desktop.security import CredentialVault
from mathproofmesh.desktop.settings import (
    DesktopSettings,
    SettingsStore,
)
from mathproofmesh.reasoning_trace import ReasoningTraceStore
from mathproofmesh.schemas import (
    GoalClarificationDecision,
    GoalClarificationRequest,
    GoalNormalizationAssessment,
    LocalGoalPrecheck,
)


class ReversingProtector:
    def protect(self, value: bytes) -> bytes:
        return value[::-1]

    def unprotect(self, value: bytes) -> bytes:
        return value[::-1]


def test_settings_and_credentials_are_persisted_without_plaintext(
    tmp_path: Path,
) -> None:
    paths = DesktopPaths.discover(tmp_path / "desktop")
    settings_store = SettingsStore(paths.settings_file)
    settings_store.save(
        DesktopSettings(
            selected_profile="formal",
            sandbox_enabled=False,
            remember_credentials=True,
        )
    )
    assert settings_store.load().selected_profile == "formal"
    assert settings_store.load().sandbox_enabled is False

    vault = CredentialVault(
        paths.credentials_file,
        protector=ReversingProtector(),
    )
    vault.set("DEEPSEEK_AGENT_1_KEY", "top-secret-value", persist=True)
    assert "top-secret-value" not in paths.credentials_file.read_text(encoding="utf-8")

    reloaded = CredentialVault(
        paths.credentials_file,
        protector=ReversingProtector(),
    )
    assert reloaded.get("DEEPSEEK_AGENT_1_KEY") == "top-secret-value"
    assert reloaded.statuses()["DEEPSEEK_AGENT_1_KEY"] == "session"


def test_desktop_config_uses_user_writable_paths_and_injected_keys(
    tmp_path: Path,
) -> None:
    paths = DesktopPaths.discover(tmp_path / "desktop")
    vault = CredentialVault(
        paths.credentials_file,
        protector=ReversingProtector(),
    )
    for index in range(1, 6):
        vault.set(
            f"DEEPSEEK_AGENT_{index}_KEY",
            f"secret-{index}",
            persist=False,
        )
    service = DesktopConfigService(paths, vault)
    settings = DesktopSettings(sandbox_enabled=False)
    config = service.build("smoke", settings)

    assert config.runtime.project_root == str(paths.root)
    assert config.runtime.run_root == str(paths.runs)
    assert config.computation.sandboxed_python_enabled is False
    assert config.topology.inspiration.cross_run_learning_path == str(paths.learning)
    assert all(agent.api_key is not None for agent in config.agents if agent.enabled)


def test_desktop_app_requires_session_cookie_and_serves_workbench(
    tmp_path: Path,
) -> None:
    paths = DesktopPaths.discover(tmp_path / "desktop")
    client = TestClient(create_desktop_app(paths=paths, token="test-token"))

    assert client.get("/api/bootstrap").status_code == 401
    response = client.get("/?token=test-token")
    assert response.status_code == 200
    assert "MathProofMesh" in response.text
    assert 'id="topology-view"' in response.text
    assert 'id="reasoning-dock"' in response.text
    assert 'id="reasoning-authority"' in response.text
    assert 'id="topology-context-menu"' in response.text
    assert 'id="show-computation-menu-button"' in response.text
    assert 'id="clarification-dialog"' in response.text
    topology_script = client.get("/assets/topology.js")
    assert topology_script.status_code == 200
    assert "MathProofMeshTopology" in topology_script.text

    bootstrap = client.get("/api/bootstrap")
    assert bootstrap.status_code == 200
    body = bootstrap.json()
    assert body["version"] == "0.8.2"
    assert {profile["id"]: profile["filename"] for profile in body["profiles"]} == {
        "smoke": "config.deepseek-v4-pro.smoke.yaml",
        "formal": "config.deepseek-v4-pro.yaml",
        "active": "config.deepseek-v4-pro.topology-active.yaml",
        "proof_control_shadow": ("config.deepseek-v4-pro.proof-control-shadow.yaml"),
        "proof_control_active": ("config.deepseek-v4-pro.proof-control-active.yaml"),
    }
    assert body["credential_status"]["DEEPSEEK_AGENT_1_KEY"] == "missing"

    settings = client.put(
        "/api/settings",
        json={
            "selected_profile": "formal",
            "sandbox_enabled": False,
            "remember_credentials": False,
        },
    )
    assert settings.status_code == 200
    assert settings.json()["settings"]["selected_profile"] == "formal"

    credentials = client.put(
        "/api/credentials",
        json={
            "values": {"DEEPSEEK_AGENT_1_KEY": "session-secret"},
            "clear": [],
            "persist": False,
        },
    )
    assert credentials.status_code == 200
    assert credentials.json()["credential_status"]["DEEPSEEK_AGENT_1_KEY"] == "session"


def test_run_activity_returns_one_latest_snapshot_per_logical_task(
    tmp_path: Path,
) -> None:
    paths = DesktopPaths.discover(tmp_path / "desktop")
    run_directory = paths.runs / "logical-steps"
    run_directory.mkdir()
    events = [
        {
            "sequence": 1,
            "task_id": "step-1",
            "status": "running",
            "detail": "已收 1,000 chunks",
        },
        {
            "sequence": 2,
            "task_id": "step-1",
            "status": "running",
            "detail": "已收 2,000 chunks",
        },
        {
            "sequence": 3,
            "task_id": "step-2",
            "status": "running",
            "detail": "并行路线 B",
        },
        {
            "sequence": 4,
            "task_id": "step-1",
            "status": "completed",
            "detail": "路线 A 完成",
        },
    ]
    (run_directory / "activity.jsonl").write_text(
        "\n".join(json.dumps(event, ensure_ascii=False) for event in events) + "\n",
        encoding="utf-8",
    )

    activity = RunRepository(paths).read_activity("logical-steps", limit=250)

    assert [event["task_id"] for event in activity] == ["step-1", "step-2"]
    assert activity[0]["sequence"] == 4
    assert activity[0]["detail"] == "路线 A 完成"


def test_run_activity_reconstructs_topology_metadata(
    tmp_path: Path,
) -> None:
    paths = DesktopPaths.discover(tmp_path / "desktop")
    run_directory = paths.runs / "topology"
    run_directory.mkdir()
    events = [
        {
            "sequence": 1,
            "elapsed_ms": 0,
            "event_type": "run",
            "task_id": "run",
            "status": "running",
        },
        {
            "sequence": 2,
            "elapsed_ms": 10,
            "event_type": "stage",
            "task_id": "stage",
            "parent_task_id": "run",
            "status": "running",
        },
        {
            "sequence": 3,
            "elapsed_ms": 20,
            "event_type": "agent_call",
            "task_id": "agent",
            "status": "running",
        },
        {
            "sequence": 4,
            "elapsed_ms": 40,
            "event_type": "agent_call_heartbeat",
            "task_id": "agent",
            "status": "running",
        },
        {
            "sequence": 5,
            "elapsed_ms": 80,
            "event_type": "agent_call_completed",
            "task_id": "agent",
            "status": "completed",
        },
        {
            "sequence": 6,
            "elapsed_ms": 90,
            "event_type": "task_completed",
            "task_id": "stage",
            "parent_task_id": "run",
            "status": "completed",
        },
    ]
    (run_directory / "activity.jsonl").write_text(
        "\n".join(json.dumps(event) for event in events) + "\n",
        encoding="utf-8",
    )

    activity = RunRepository(paths).read_activity("topology", limit=None)

    assert [event["task_id"] for event in activity] == ["run", "stage", "agent"]
    agent = activity[-1]
    assert agent["parent_task_id"] == "stage"
    assert agent["started_elapsed_ms"] == 20
    assert agent["initial_event_type"] == "agent_call"


def test_desktop_reasoning_snapshot_and_completed_sse_replay(
    tmp_path: Path,
) -> None:
    paths = DesktopPaths.discover(tmp_path / "desktop")
    run_directory = paths.runs / "reasoning-run"
    run_directory.mkdir()
    events = [
        {
            "sequence": 1,
            "elapsed_ms": 10,
            "event_type": "agent_call",
            "task_id": "agent-node",
            "status": "running",
            "title": "Route prove",
            "agent_id": "ds-explorer-a",
            "stage": "route_prove",
        },
        {
            "sequence": 2,
            "elapsed_ms": 50,
            "event_type": "agent_call_completed",
            "task_id": "agent-node",
            "status": "completed",
            "title": "Route prove",
            "agent_id": "ds-explorer-a",
            "stage": "route_prove",
        },
        {
            "sequence": 3,
            "elapsed_ms": 60,
            "event_type": "agent_call",
            "task_id": "legacy-node",
            "status": "completed",
            "title": "Old call",
        },
    ]
    (run_directory / "activity.jsonl").write_text(
        "\n".join(json.dumps(event) for event in events) + "\n",
        encoding="utf-8",
    )
    trace_store = ReasoningTraceStore(run_directory, "reasoning-run")
    trace = trace_store.begin_call(
        task_id="agent-node",
        agent_id="ds-explorer-a",
        stage="route_prove",
        thinking_enabled=True,
        reasoning_effort="max",
    )
    trace.append("first line\nsecond line")
    trace.finish("completed")

    client = TestClient(create_desktop_app(paths=paths, token="test-token"))
    assert client.get("/?token=test-token").status_code == 200

    snapshot = client.get("/api/runs/reasoning-run/nodes/agent-node/reasoning")
    assert snapshot.status_code == 200
    body = snapshot.json()
    assert body["trace_state"] == "completed"
    assert body["calls"][0]["text"] == "first line\nsecond line"
    assert body["archive"] == "reports/reasoning_traces.txt"
    assert body["reasoning_authority"]["status"] == "unverified"
    assert body["reasoning_authority"]["premise_eligible"] is False

    stream = client.get(
        "/api/runs/reasoning-run/nodes/agent-node/reasoning/events?after=0"
    )
    assert stream.status_code == 200
    assert "event: reasoning" in stream.text
    assert "first line" in stream.text
    assert "event: terminal" in stream.text

    legacy = client.get("/api/runs/reasoning-run/nodes/legacy-node/reasoning")
    assert legacy.status_code == 200
    assert legacy.json()["trace_state"] == "legacy_unavailable"


def test_desktop_computation_snapshot_is_complete_and_redacted(
    tmp_path: Path,
) -> None:
    paths = DesktopPaths.discover(tmp_path / "desktop")
    run_directory = paths.runs / "computation-run"
    experiment_hash = "a" * 64
    experiment_directory = run_directory / "experiments" / experiment_hash
    experiment_directory.mkdir(parents=True)
    activity = {
        "sequence": 4,
        "elapsed_ms": 2500,
        "started_elapsed_ms": 100,
        "event_type": "computation_completed",
        "initial_event_type": "python_experiment",
        "task_id": "computation:experiment-a",
        "status": "completed",
        "title": "Python 沙箱实验",
        "stage": "computation",
        "metrics": {
            "phase": "completed",
            "method": "sandboxed_python",
            "experiment_id": "experiment-a",
            "request_hash": experiment_hash,
            "result_hash": "b" * 64,
            "decision": "allow",
            "rule_id": "test",
        },
    }
    (run_directory / "activity.jsonl").write_text(
        json.dumps(activity, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    artifacts = {
        "spec.json": {
            "experiment_id": "experiment-a",
            "request_hash": experiment_hash,
            "method": "sandboxed_python",
            "target_claim": "Check a finite predicate.",
            "arguments": {"input": {"n": 7}},
        },
        "decision.json": {
            "decision": "allow",
            "reason": "Targeted check admitted.",
            "rule_id": "test",
        },
        "program.json": {
            "experiment_id": "experiment-a",
            "source": (
                "def run(data):\n"
                "    marker = 'sk-this-must-never-be-shown'\n"
                "    return {'outcome': 'not_refuted'}\n"
            ),
            "input_schema": {"type": "object"},
            "output_schema": {"type": "object"},
            "dependencies": [],
            "code_hash": "c" * 64,
        },
        "execution.json": {
            "request_hash": experiment_hash,
            "method": "sandboxed_python",
            "runtime_seconds": 2.4,
            "outcome": "not_refuted",
            "error": None,
            "tool_name": "sandboxed_python",
            "tool_version": "test",
            "program_hash": "d" * 64,
            "input": {"sandbox_input": {"n": 7, "seed": 1}},
            "input_hash": "e" * 64,
            "output": {
                "handler_output": {"outcome": "not_refuted"},
                "process": {
                    "exit_code": 0,
                    "stdout": '{"outcome":"not_refuted"}',
                    "stderr": "",
                },
            },
            "output_hash": "f" * 64,
            "result_hash": "b" * 64,
            "environment": {
                "sandbox": {
                    "network": "none",
                    "host_environment_forwarded": False,
                },
                "api_key": "plain-secret",
            },
            "environment_hash": "1" * 64,
        },
        "result.json": {
            "outcome": "not_refuted",
            "evidence_strength": "bounded_evidence",
        },
        "computation_certificate.json": {
            "request_hash": experiment_hash,
            "result_hash": "b" * 64,
        },
    }
    for name, payload in artifacts.items():
        (experiment_directory / name).write_text(
            json.dumps(payload, ensure_ascii=False),
            encoding="utf-8",
        )

    repository = RunRepository(paths)
    snapshot = repository.computation_snapshot(
        "computation-run",
        "computation:experiment-a",
    )

    assert snapshot["phase"] == "completed"
    assert snapshot["program"]["source"].count("[REDACTED]") == 1
    assert snapshot["environment"]["api_key"] == "[REDACTED]"
    assert snapshot["runtime"]["process"]["exit_code"] == 0
    assert snapshot["audit"]["result_hash"] == "b" * 64
    assert snapshot["decision"]["reason"] == "Targeted check admitted."

    client = TestClient(create_desktop_app(paths=paths, token="test-token"))
    assert client.get("/?token=test-token").status_code == 200
    response = client.get(
        "/api/runs/computation-run/nodes/computation%3Aexperiment-a/computation"
    )
    assert response.status_code == 200
    assert response.json()["input"]["sandbox_input"]["n"] == 7


def test_desktop_delete_moves_run_directory_to_recycle_bin(
    tmp_path: Path,
    monkeypatch,
) -> None:
    paths = DesktopPaths.discover(tmp_path / "desktop")
    run_directory = paths.runs / "delete-me"
    run_directory.mkdir()
    (run_directory / "proof.txt").write_text("proof", encoding="utf-8")
    recycled = tmp_path / "recycled"
    recycled.mkdir()

    def fake_send2trash(value: str) -> None:
        Path(value).rename(recycled / Path(value).name)

    monkeypatch.setattr(
        "mathproofmesh.desktop.repository.send2trash",
        fake_send2trash,
    )
    client = TestClient(create_desktop_app(paths=paths, token="test-token"))
    assert client.get("/?token=test-token").status_code == 200

    response = client.delete("/api/runs/delete-me")

    assert response.status_code == 200
    assert response.json()["deleted_run_id"] == "delete-me"
    assert not run_directory.exists()
    assert (recycled / "delete-me" / "proof.txt").read_text(encoding="utf-8") == "proof"


def test_desktop_run_manager_publishes_terminal_state(
    tmp_path: Path,
    monkeypatch,
) -> None:
    paths = DesktopPaths.discover(tmp_path / "desktop")
    settings_store = SettingsStore(paths.settings_file)
    credentials = CredentialVault(
        paths.credentials_file,
        protector=ReversingProtector(),
    )
    repository = RunRepository(paths)

    class FakeConfigService:
        def build(self, profile, settings):
            return object()

    class FakeOrchestrator:
        def __init__(
            self,
            config,
            *,
            activity_listener=None,
            clarification_resolver=None,
        ):
            self.activity_listener = activity_listener

        async def solve(self, problem, *, run_id):
            await asyncio.sleep(0)
            return SimpleNamespace(
                run_id=run_id,
                status=SimpleNamespace(value="verified"),
                math_status=SimpleNamespace(value="verified"),
                execution_status=SimpleNamespace(value="completed"),
                total_calls=3,
                total_usage=SimpleNamespace(
                    total_tokens=1200,
                    estimated_cost_usd=0.02,
                ),
            )

    monkeypatch.setattr(
        "mathproofmesh.desktop.manager.ProofMeshOrchestrator",
        FakeOrchestrator,
    )
    manager = DesktopRunManager(
        paths,
        settings_store,
        credentials,
        FakeConfigService(),  # type: ignore[arg-type]
        repository,
    )

    async def exercise() -> None:
        snapshot = await manager.start(
            problem="Prove that 1 + 1 = 2.",
            profile="smoke",
            run_id="desktop-test",
        )
        session = manager.session(str(snapshot["run_id"]))
        assert session is not None and session.task is not None
        with pytest.raises(RuntimeError, match="正在运行的任务不能删除"):
            await manager.delete(str(snapshot["run_id"]))
        await session.task
        assert session.metadata.lifecycle == "completed"
        assert any(item["event"] == "result" for item in session.events)
        assert session.events[-1]["event"] == "terminal"

    asyncio.run(exercise())


def test_desktop_resume_endpoint_defaults_to_normal(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    paths = DesktopPaths.discover(tmp_path / "desktop")
    app = create_desktop_app(paths=paths, token="test-token")
    client = TestClient(app)
    assert client.get("/?token=test-token").status_code == 200

    received: list[tuple[str, str, str]] = []

    async def fake_resume(
        *,
        run_id: str,
        profile: str,
        resume_mode: str,
    ) -> dict[str, object]:
        received.append((run_id, profile, resume_mode))
        return {"run_id": run_id, "lifecycle": "queued"}

    monkeypatch.setattr(app.state.desktop_manager, "resume", fake_resume)

    default = client.post("/api/runs/stalled-run/resume", json={"profile": "smoke"})
    assert default.status_code == 200
    assert received == [("stalled-run", "smoke", "normal")]

    explicit = client.post(
        "/api/runs/stalled-run/resume",
        json={"profile": "smoke", "resume_mode": "replay_stage"},
    )
    assert explicit.status_code == 200
    assert received[-1] == ("stalled-run", "smoke", "replay_stage")

    invalid = client.post(
        "/api/runs/stalled-run/resume",
        json={"profile": "smoke", "resume_mode": "bogus"},
    )
    assert invalid.status_code == 400
    assert "resume_mode" in invalid.json()["detail"]
    assert len(received) == 2


def test_desktop_run_manager_resume_threads_intervention_to_orchestrator(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    paths = DesktopPaths.discover(tmp_path / "desktop")
    settings_store = SettingsStore(paths.settings_file)
    credentials = CredentialVault(
        paths.credentials_file,
        protector=ReversingProtector(),
    )
    repository = RunRepository(paths)
    interventions: list[tuple[str, str | None]] = []

    class FakeConfigService:
        def build(self, profile, settings):
            return object()

    class FakeOrchestrator:
        def __init__(
            self,
            config,
            *,
            activity_listener=None,
            clarification_resolver=None,
        ):
            pass

        async def resume(self, run_id, *, intervention=None):
            interventions.append((run_id, intervention))
            return SimpleNamespace(
                run_id=run_id,
                status=SimpleNamespace(value="verified"),
                math_status=SimpleNamespace(value="verified"),
                execution_status=SimpleNamespace(value="completed"),
                total_calls=1,
                total_usage=SimpleNamespace(
                    total_tokens=100,
                    estimated_cost_usd=0.01,
                ),
            )

    monkeypatch.setattr(
        "mathproofmesh.desktop.manager.ProofMeshOrchestrator",
        FakeOrchestrator,
    )
    manager = DesktopRunManager(
        paths,
        settings_store,
        credentials,
        FakeConfigService(),  # type: ignore[arg-type]
        repository,
    )
    (paths.runs / "resume-test").mkdir(parents=True, exist_ok=True)

    async def exercise() -> None:
        first = await manager.resume(run_id="resume-test", profile="smoke")
        session = manager.session(str(first["run_id"]))
        assert session is not None and session.task is not None
        await session.task
        assert interventions == [("resume-test", None)]
        assert session.metadata.lifecycle == "completed"

        interventions.clear()
        second = await manager.resume(
            run_id="resume-test",
            profile="smoke",
            resume_mode="reset_stagnation",
        )
        session = manager.session(str(second["run_id"]))
        assert session is not None and session.task is not None
        await session.task
        assert interventions == [("resume-test", "reset_stagnation")]

    asyncio.run(exercise())


def test_desktop_run_manager_pauses_until_goal_is_confirmed(
    tmp_path: Path,
    monkeypatch,
) -> None:
    paths = DesktopPaths.discover(tmp_path / "desktop")
    settings_store = SettingsStore(paths.settings_file)
    credentials = CredentialVault(
        paths.credentials_file,
        protector=ReversingProtector(),
    )
    repository = RunRepository(paths)
    decisions: list[GoalClarificationDecision] = []

    class FakeConfigService:
        def build(self, profile, settings):
            return object()

    class FakeOrchestrator:
        def __init__(
            self,
            config,
            *,
            activity_listener=None,
            clarification_resolver=None,
        ):
            self.clarification_resolver = clarification_resolver

        async def solve(self, problem, *, run_id):
            request = GoalClarificationRequest(
                original_statement=problem,
                local_precheck=LocalGoalPrecheck(
                    status="model_review_required",
                    rule_ids=["congruence_missing_modulus"],
                    reasons=["同余关系缺少模数。"],
                ),
                assessment=GoalNormalizationAssessment(
                    has_ambiguity=True,
                    is_well_formed=False,
                    ambiguity_reasons=["需要补充模数。"],
                    recommended_statement="证明存在无穷多个模4余1的素数。",
                    recommendation_confidence=0.9,
                    changes_mathematical_meaning=True,
                    clarification_question="确认采用模4余1吗？",
                ),
            )
            decision = await self.clarification_resolver(request)
            decisions.append(decision)
            return SimpleNamespace(
                run_id=run_id,
                status=SimpleNamespace(value="verified"),
                math_status=SimpleNamespace(value="verified"),
                execution_status=SimpleNamespace(value="completed"),
                total_calls=1,
                total_usage=SimpleNamespace(
                    total_tokens=100,
                    estimated_cost_usd=0.01,
                ),
            )

    monkeypatch.setattr(
        "mathproofmesh.desktop.manager.ProofMeshOrchestrator",
        FakeOrchestrator,
    )
    manager = DesktopRunManager(
        paths,
        settings_store,
        credentials,
        FakeConfigService(),  # type: ignore[arg-type]
        repository,
    )

    async def exercise() -> None:
        snapshot = await manager.start(
            problem="证明存在无穷多个与4同余的素数。",
            profile="smoke",
            run_id="clarification-test",
        )
        session = manager.session(str(snapshot["run_id"]))
        assert session is not None and session.task is not None
        for _ in range(20):
            if session.pending_clarification is not None:
                break
            await asyncio.sleep(0)
        request = session.pending_clarification
        assert request is not None
        assert session.metadata.lifecycle == "awaiting_confirmation"
        assert not session.task.done()

        await manager.confirm_clarification(
            session.metadata.run_id,
            GoalClarificationDecision(
                request_id=request.request_id,
                canonical_statement="证明存在无穷多个模4余1的素数。",
                selected_candidate_index=0,
            ),
        )
        await session.task

        assert decisions[0].source == "user_confirmed"
        assert session.metadata.lifecycle == "completed"
        assert any(item["event"] == "clarification" for item in session.events)

    asyncio.run(exercise())
