from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from mathproofmesh.llm.base import Message
from mathproofmesh.mock_demo import demo_responder
from mathproofmesh.orchestrator import ProofMeshOrchestrator

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

    root = Path(result.run_directory)
    rounds = sorted((root / "inspiration").glob("round_*.json"))
    assert rounds
    payloads = [json.loads(path.read_text(encoding="utf-8")) for path in rounds]
    assert any(payload["triggers"] for payload in payloads)
    assert any(payload["proposals"] for payload in payloads)
    assert typed_inspiration_outputs

    checkpoint = json.loads(
        (root / "structured" / "inspiration_engine.json").read_text(encoding="utf-8")
    )
    assert checkpoint["proposals"]
    assert checkpoint["reviews"]
    assert checkpoint["materializations"]
