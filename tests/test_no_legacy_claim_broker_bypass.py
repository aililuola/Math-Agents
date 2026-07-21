from __future__ import annotations

import json
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


async def test_hierarchical_route_prompt_never_receives_legacy_global_claims(
    tmp_path,
) -> None:
    config = make_v07_config(tmp_path / "runs", inspiration_mode="off")
    config.budget.max_rounds = 2
    contexts: list[dict[str, Any]] = []

    def responder(
        schema_name: str | None,
        messages: list[Message],
        schema: dict[str, Any] | None,
    ) -> dict[str, Any]:
        payload = demo_responder(schema_name, messages, schema)
        if schema_name == "ContinuationTurn":
            contexts.append(_context(messages))
        # Force route artifacts to remain local. Even if legacy LemmaMemory later
        # marks a normalized Claim as verified, another route must not see it.
        if schema_name == "BrokerDecision":
            payload["accepted"] = False
            payload["rejection_reason"] = "forced independent referee rejection"
        return payload

    await ProofMeshOrchestrator(
        config,
        mock_responders={agent.id: responder for agent in config.agents},
    ).solve("Prove the sum of the first n odd integers is n squared.")

    assert contexts
    assert all(context.get("verified_legacy_claims") == [] for context in contexts)
