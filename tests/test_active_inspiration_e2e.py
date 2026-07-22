from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from mathproofmesh.llm.base import Message
from mathproofmesh.mock_demo import demo_responder
from mathproofmesh.orchestrator import ProofMeshOrchestrator
from mathproofmesh.schemas import RunStatus

from v07_helpers import make_v07_config


def _context(messages: list[Message]) -> dict[str, Any]:
    text = "\n".join(item["content"] for item in messages if item["role"] == "user")
    marker = "SANITIZED CONTEXT:\n"
    if marker not in text:
        return {}
    payload = text.split(marker, 1)[1].split("\n\nOUTPUT LANGUAGE:", 1)[0]
    return json.loads(payload)


async def test_active_inspiration_builds_llm_prompts_and_materializes_proposals(
    tmp_path,
) -> None:
    config = make_v07_config(tmp_path / "runs", inspiration_mode="active")
    config.continuation.enabled = True
    config.continuation.segments_per_explore_call = 1
    config.budget.max_rounds = 3
    config.topology.inspiration.stagnation_rounds = 1
    typed_inspiration_outputs: list[str] = []
    generation_contracts: list[dict[str, Any]] = []

    def responder(
        schema_name: str | None,
        messages: list[Message],
        schema: dict[str, Any] | None,
    ) -> dict[str, Any]:
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
            typed_inspiration_outputs.append(str(schema_name))
        if schema_name in {
            "RepresentationCandidate",
            "AnalogyMapping",
            "ConstructionProposal",
            "InvariantHypothesis",
            "ReverseGoalPlan",
            "MetaStrategyDecision",
            "InspirationProposal",
        }:
            contract = _context(messages).get("generation_contract")
            if isinstance(contract, dict):
                generation_contracts.append(contract)
        if schema_name == "ContinuationTurn":
            context = _context(messages)
            segment = int(context.get("authoritative_ids", {}).get("segment_index", 1))
            if segment == 1:
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
    ).solve(
        "Prove that for every positive integer n, 1+3+...+(2n-1)=n^2.",
        run_id="active-inspiration",
    )

    assert result.status == RunStatus.VERIFIED
    root = Path(result.run_directory)
    rounds = sorted((root / "inspiration").glob("round_*.json"))
    assert rounds
    payloads = [json.loads(path.read_text(encoding="utf-8")) for path in rounds]
    assert any(payload["triggers"] for payload in payloads)
    assert any(payload["proposals"] for payload in payloads)
    assert typed_inspiration_outputs
    assert len(generation_contracts) >= 3
    assert {item["proposal_slot"] for item in generation_contracts} >= {0, 1, 2}
    assert {item["context_mode"] for item in generation_contracts} >= {
        "warm",
        "cold",
    }

    checkpoint = json.loads(
        (root / "structured" / "inspiration_engine.json").read_text(encoding="utf-8")
    )
    assert checkpoint["proposals"]
    assert checkpoint["reviews"]
    assert checkpoint["materializations"]
    decisions = list(checkpoint["candidate_decisions"].values())
    assert decisions
    selected_by_task: dict[str, int] = {}
    for decision in decisions:
        if decision["selected_for_review"]:
            task_id = str(decision["task_id"])
            selected_by_task[task_id] = selected_by_task.get(task_id, 0) + 1
    assert selected_by_task
    assert max(selected_by_task.values()) <= 2
    assert all(
        item["status"] in {"completed", "interrupted"}
        for item in checkpoint["call_reservations"].values()
    )
    route_creations_by_trigger: dict[str, int] = {}
    for proposal_id, materialization in checkpoint["materializations"].items():
        if materialization["action"] != "route_created":
            continue
        trigger_id = checkpoint["proposals"][proposal_id]["trigger_id"]
        route_creations_by_trigger[trigger_id] = (
            route_creations_by_trigger.get(trigger_id, 0) + 1
        )
    assert all(value <= 1 for value in route_creations_by_trigger.values())
    metrics = json.loads(
        (root / "reports" / "hierarchical_metrics.json").read_text(encoding="utf-8")
    )
    assert metrics["inspiration_proposal_context_modes"]["warm"] >= 1
    assert metrics["inspiration_proposal_context_modes"]["cold"] >= 1
    assert metrics["inspiration_candidates_selected_for_review"] >= 1
    assert metrics["inspiration_call_budget_reserved"] >= 10
    assert metrics["inspiration_call_budget_consumed"] >= 1
