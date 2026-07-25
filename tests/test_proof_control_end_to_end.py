from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest

from mathproofmesh.llm.base import Message
from mathproofmesh.mock_demo import demo_responder, demo_responders
from mathproofmesh.orchestrator import ProofMeshOrchestrator
from mathproofmesh.proof_control.models import GateVerdict
from mathproofmesh.schemas import RunStatus
from mathproofmesh.store import ArtifactStore

from v07_helpers import make_proof_control_config


PROBLEM = "Prove that for every positive integer n, 1+3+...+(2n-1)=n^2."


def _sanitized_context(messages: list[Message]) -> dict[str, Any]:
    text = "\n".join(item["content"] for item in messages if item["role"] == "user")
    marker = "SANITIZED CONTEXT:\n"
    if marker not in text:
        return {}
    payload = text.split(marker, 1)[1].split("\n\nOUTPUT LANGUAGE:", 1)[0]
    return json.loads(payload)


@pytest.mark.parametrize("mode", ["shadow", "active"])
async def test_mock_proof_control_modes_are_auditable_and_offline(
    tmp_path: Path,
    mode: str,
) -> None:
    config = make_proof_control_config(tmp_path / mode / "runs", mode=mode)
    result = await ProofMeshOrchestrator(
        config,
        mock_responders=demo_responders(config),
    ).solve(PROBLEM, run_id=f"proof-control-{mode}")

    assert result.status == RunStatus.VERIFIED
    root = Path(result.run_directory)
    sidecar = json.loads(
        (root / "structured" / "proof_control.json").read_text(encoding="utf-8")
    )
    summary = json.loads(
        (root / "reports" / "proof_control_summary.json").read_text(encoding="utf-8")
    )
    checkpoint = ArtifactStore(
        config.runtime.run_root, f"proof-control-{mode}"
    ).latest_stage_checkpoint()

    assert sidecar["schema_version"] == "0.8"
    assert summary["schema_version"] == "0.8"
    assert summary["mode"] == mode
    assert {
        "goal_alignment",
        "fact_roles",
        "core_proof_debt_auc",
        "scope_risk_count",
        "countermodel_task_count",
        "bottleneck_compression_ratio",
        "message_delivery",
        "near_miss_repair_success_rate",
        "fast_lane_counterexample_hit_rate",
        "route_admission_rejection_rewrite_rate",
        "continue_gate_block_rate",
        "synthesis_readiness_block_rate",
    } <= summary.keys()
    assert sidecar["route_admissions"]
    assert sidecar["synthesis_readiness_records"]
    assert checkpoint is not None
    assert checkpoint[1]["schema_version"] == "0.8"
    assert checkpoint[1]["proof_control_state"] == sidecar
    assert all(
        item["verdict"]
        in {
            GateVerdict.PASS.value,
            (
                GateVerdict.SHADOW_BLOCK.value
                if mode == "shadow"
                else GateVerdict.BLOCK.value
            ),
        }
        for item in sidecar["synthesis_readiness_records"]
    )


async def test_active_synthesis_gate_is_evaluated_before_synthesizer(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    config = make_proof_control_config(tmp_path / "runs", mode="active")
    calls: list[str] = []
    original_gate = ProofMeshOrchestrator._proof_control_allows_synthesis
    original_synthesize = ProofMeshOrchestrator._synthesize

    def observed_gate(self, state):
        calls.append("readiness")
        return original_gate(self, state)

    async def observed_synthesize(self, *args, **kwargs):
        calls.append("synthesize")
        return await original_synthesize(self, *args, **kwargs)

    monkeypatch.setattr(
        ProofMeshOrchestrator,
        "_proof_control_allows_synthesis",
        observed_gate,
    )
    monkeypatch.setattr(
        ProofMeshOrchestrator,
        "_synthesize",
        observed_synthesize,
    )
    result = await ProofMeshOrchestrator(
        config,
        mock_responders=demo_responders(config),
    ).solve(PROBLEM, run_id="proof-control-synthesis-order")

    assert result.status == RunStatus.VERIFIED
    assert "synthesize" in calls
    synthesis_index = calls.index("synthesize")
    assert "readiness" in calls[:synthesis_index]


async def test_active_proof_control_runs_with_active_inspiration(
    tmp_path: Path,
) -> None:
    config = make_proof_control_config(
        tmp_path / "runs",
        mode="active",
        inspiration_mode="active",
    )
    config.budget.max_rounds = 3
    config.topology.inspiration.stagnation_rounds = 1
    inspiration_calls: list[str] = []
    continuation_calls = 0

    def responder(
        schema_name: str | None,
        messages: list[Message],
        schema: dict[str, Any] | None,
    ) -> dict[str, Any]:
        nonlocal continuation_calls
        payload = demo_responder(schema_name, messages, schema)
        if schema_name in {
            "RepresentationCandidate",
            "AnalogyMapping",
            "ConstructionProposal",
            "InvariantHypothesis",
            "ReverseGoalPlan",
            "MetaStrategyDecision",
            "InspirationProposal",
            "InspirationReview",
        }:
            inspiration_calls.append(str(schema_name))
        if schema_name == "ContinuationTurn":
            continuation_calls += 1
            context = _sanitized_context(messages)
            segment = int(context.get("authoritative_ids", {}).get("segment_index", 1))
            if segment == 1 and continuation_calls <= 2:
                payload["action"] = "submit_delta"
                payload["delta"].update(
                    {
                        "completed_subgoal": "Establish one local identity.",
                        "remaining_subgoals": ["Find a mechanism-level bridge."],
                        "current_goal": "Find a mechanism-level bridge.",
                        "candidate_final_answer": None,
                        "proof_complete": False,
                    }
                )
        elif schema_name == "MetaReview":
            payload["can_synthesize"] = False
            payload["summary"] = "The portfolio is stalled and needs inspiration."
        return payload

    responders = {agent.id: responder for agent in config.agents}
    result = await ProofMeshOrchestrator(
        config,
        mock_responders=responders,
    ).solve(PROBLEM, run_id="proof-control-active-inspiration")

    root = Path(result.run_directory)
    summary = json.loads(
        (root / "reports" / "proof_control_summary.json").read_text(encoding="utf-8")
    )
    inspiration = json.loads(
        (root / "structured" / "inspiration_engine.json").read_text(encoding="utf-8")
    )
    sidecar = json.loads(
        (root / "structured" / "proof_control.json").read_text(encoding="utf-8")
    )
    graph = json.loads(
        (root / "reports" / "proof_graph.json").read_text(encoding="utf-8")
    )
    open_obligations = {
        key: value["statement"]
        for key, value in graph["obligations"].items()
        if value["status"] == "open"
    }
    if result.status != RunStatus.VERIFIED:
        pytest.fail(
            json.dumps(
                {
                    "status": result.status,
                    "result_summary": result.summary,
                    "open_obligations": open_obligations,
                    "synthesis": sidecar["synthesis_readiness_records"],
                    "continuation": sidecar["continue_gate_records"],
                    "route_admission": sidecar["route_admissions"],
                    "events": sidecar["events"][-20:],
                },
                ensure_ascii=False,
                indent=2,
            ),
            pytrace=False,
        )
    assert summary["mode"] == "active"
    assert inspiration_calls
    assert inspiration["proposals"]
