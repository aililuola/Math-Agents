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


async def test_active_rejected_inspiration_survives_blind_finalization(
    tmp_path,
) -> None:
    config = make_v07_config(tmp_path / "runs", inspiration_mode="active")
    config.continuation.segments_per_explore_call = 1
    config.budget.max_rounds = 3
    config.topology.inspiration.stagnation_rounds = 1
    rejected_reviews = 0

    def responder(
        schema_name: str | None,
        messages: list[Message],
        schema: dict[str, Any] | None,
    ) -> dict[str, Any]:
        nonlocal rejected_reviews
        payload = demo_responder(schema_name, messages, schema)
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
        elif schema_name == "InspirationReview":
            rejected_reviews += 1
            payload.update(
                {
                    "recommendation": "reject",
                    "internally_coherent": False,
                    "hidden_assumptions": ["the encoding may lose required state"],
                    "confidence": 0.95,
                }
            )
        return payload

    result = await ProofMeshOrchestrator(
        config,
        mock_responders={agent.id: responder for agent in config.agents},
    ).solve(
        "Prove that for every positive integer n, 1+3+...+(2n-1)=n^2.",
        run_id="active-inspiration-rejection",
    )

    assert rejected_reviews >= 1
    assert result.status == RunStatus.VERIFIED
    root = Path(result.run_directory)
    packet_path = root / "structured" / "blind_final_review_packet.json"
    assert packet_path.is_file()
    packet = json.loads(packet_path.read_text(encoding="utf-8"))
    assert packet["negative_evidence_packets"]
    assert any(
        item.get("proposal_kind") for item in packet["negative_evidence_packets"]
    )
    events = [
        json.loads(line)
        for line in (root / "events.jsonl").read_text(encoding="utf-8").splitlines()
    ]
    assert not any(
        item.get("event_type") in {"run_failed", "run_resume_failed"} for item in events
    )
