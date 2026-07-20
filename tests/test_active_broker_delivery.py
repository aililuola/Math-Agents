from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any

from mathproofmesh.llm.base import Message
from mathproofmesh.mock_demo import demo_responder
from mathproofmesh.orchestrator import ProofMeshOrchestrator

from v07_helpers import make_v07_config


def _context(messages: list[Message]) -> dict[str, Any]:
    text = "\n".join(item["content"] for item in messages if item["role"] == "user")
    if "SANITIZED CONTEXT:\n" not in text:
        return {}
    payload = text.split("SANITIZED CONTEXT:\n", 1)[1]
    payload = payload.split("\n\nOUTPUT LANGUAGE:", 1)[0]
    return json.loads(payload)


async def test_active_broker_delivery_enters_target_prompt_and_is_acknowledged(
    tmp_path,
) -> None:
    config = make_v07_config(tmp_path / "runs", inspiration_mode="off")
    config.continuation.enabled = True
    config.continuation.segments_per_explore_call = 1
    config.budget.max_rounds = 3
    seen: list[tuple[str, list[str]]] = []

    def responder(
        schema_name: str | None,
        messages: list[Message],
        schema: dict[str, Any] | None,
    ) -> dict[str, Any]:
        payload = demo_responder(schema_name, messages, schema)
        text = "\n".join(item["content"] for item in messages if item["role"] == "user")
        if schema_name == "ContinuationTurn":
            context = _context(messages)
            route_id = str(context.get("route_id", ""))
            message_ids = [
                str(item["message_id"]) for item in context.get("broker_messages", [])
            ]
            seen.append((route_id, message_ids))
            segment = int(context.get("authoritative_ids", {}).get("segment_index", 1))
            if segment == 1:
                payload["action"] = "submit_delta"
                payload["delta"].update(
                    {
                        "completed_subgoal": "Establish one route-local identity.",
                        "remaining_subgoals": [
                            "Connect the identity to the final goal."
                        ],
                        "current_goal": "Use admitted cross-route facts to finish.",
                        "candidate_final_answer": None,
                        "proof_complete": False,
                    }
                )
        elif schema_name == "ClaimBatch":
            strategy_match = re.search(r'"strategy_id"\s*:\s*"([^"]+)"', text)
            strategy_id = strategy_match.group(1) if strategy_match else "unknown"
            statement = f"verified route-local identity for {strategy_id}"
            payload["claims"][0]["statement"] = statement
            payload["claims"][0]["conclusion"] = statement
        elif schema_name == "MetaReview":
            payload["can_synthesize"] = False
            payload["summary"] = (
                "Continue one route so queued broker facts are consumed."
            )
        return payload

    responders = {agent.id: responder for agent in config.agents}
    result = await ProofMeshOrchestrator(
        config,
        mock_responders=responders,
    ).solve(
        "Prove that for every positive integer n, 1+3+...+(2n-1)=n^2.",
        run_id="active-broker-delivery",
    )

    root = Path(result.run_directory)
    broker = json.loads(
        (root / "structured" / "message_broker.json").read_text(encoding="utf-8")
    )
    consumed = [
        item for item in broker["deliveries"].values() if item["prompt_consumed"]
    ]
    assert consumed
    assert any(message_ids for _, message_ids in seen)
    assert broker["receipts"]
    assert all(item["status"] == "accepted" for item in broker["receipts"].values())

    events = [
        json.loads(line)["event_type"]
        for line in (root / "events.jsonl").read_text(encoding="utf-8").splitlines()
    ]
    assert events.index("message_delivered") < events.index("message_acknowledged")
