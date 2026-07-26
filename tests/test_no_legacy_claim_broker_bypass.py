from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from mathproofmesh.llm.base import Message
from mathproofmesh.mock_demo import demo_responder
from mathproofmesh.orchestrator import ProofMeshOrchestrator
from mathproofmesh.schemas import RunStatus

from v07_helpers import make_v07_config


MARKER = "REJECTED_ROUTE_LOCAL_CLAIM_DO_NOT_SHARE"


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


async def test_rejected_route_local_claim_never_enters_any_global_stage(
    tmp_path,
) -> None:
    config = make_v07_config(
        tmp_path / "runs",
        graph_mode="active",
        inspiration_mode="off",
    )
    config.budget.max_rounds = 2
    config.budget.max_revisions = 1
    global_prompts: list[str] = []
    route_referee_rejections = 0
    revision_prompt_count = 0
    blind_call_count = 0

    def responder(
        schema_name: str | None,
        messages: list[Message],
        schema: dict[str, Any] | None,
    ) -> dict[str, Any]:
        nonlocal route_referee_rejections, revision_prompt_count, blind_call_count
        payload = demo_responder(schema_name, messages, schema)
        text = "\n".join(item["content"] for item in messages)
        if schema_name == "ClaimBatch":
            for index, claim in enumerate(payload["claims"]):
                claim.update(
                    {
                        "claim_id": f"claim-marker-{index}",
                        "statement": MARKER,
                        "conclusion": MARKER,
                        "dependencies": [],
                        "scope_limitations": ["route-local only"],
                    }
                )
        elif schema_name == "BrokerDecision":
            route_referee_rejections += 1
            payload.update(
                {
                    "accepted": False,
                    "rejection_reason": "independent route referee rejected the claim",
                    "selected_targets": [],
                }
            )
        elif schema_name == "BlindVerificationReport":
            global_prompts.append(text)
            blind_call_count += 1
            if blind_call_count == 1:
                payload.update(
                    {
                        "verdict": "fail",
                        "first_error_step": "f1",
                        "issues": [
                            {
                                "phase": "scope",
                                "severity": "error",
                                "step_id": "f1",
                                "description": "The first proof draft omitted an explicit scope check.",
                                "repair_hint": "State the exact scope in the repaired proof.",
                            }
                        ],
                        "failure_level": "execution",
                        "confidence": 0.95,
                        "concise_feedback": "Repair the explicit scope omission.",
                    }
                )
        elif schema_name == "FinalProof":
            global_prompts.append(text)
            if "[STAGE:final_revision]" in text:
                revision_prompt_count += 1
        elif schema_name == "VerificationReport" and any(
            marker in text
            for marker in (
                "[STAGE:checkpoint_verification]",
                "[STAGE:detailed_verification]",
                "[STAGE:final_verification]",
                "[STAGE:final_cross_provider_verification]",
            )
        ):
            global_prompts.append(text)
            # An attempt-level PASS no longer promotes claims wholesale; the
            # reviewer must explicitly vouch for each claim it checked.
            if "[STAGE:detailed_verification]" in text:
                payload["checked_dependencies"] = sorted(
                    {
                        *payload.get("checked_dependencies", []),
                        *[f"claim-marker-{index}" for index in range(6)],
                    }
                )
        elif schema_name == "ContinuationTurn":
            context = _context(messages)
            assert context.get("verified_legacy_claims") == []
        return payload

    result = await ProofMeshOrchestrator(
        config,
        mock_responders={agent.id: responder for agent in config.agents},
    ).solve(
        "Prove that for every positive integer n, 1+3+...+(2n-1)=n^2.",
        run_id="rejected-route-local-global-gate",
    )

    assert result.status == RunStatus.VERIFIED
    assert route_referee_rejections >= 1
    assert revision_prompt_count >= 1
    assert MARKER in {
        claim.statement for claim in result.claims if claim.status.value == "verified"
    }
    assert global_prompts
    assert all(MARKER not in prompt for prompt in global_prompts)
    root = Path(result.run_directory)
    blind_packet = (root / "structured" / "blind_final_review_packet.json").read_text(
        encoding="utf-8"
    )
    assert MARKER not in blind_packet
    typed_state = json.loads(
        (root / "structured" / "typed_memory.json").read_text(encoding="utf-8")
    )
    fact_statements = {
        message["statement"]
        for message_id, message in typed_state.get("messages", {}).items()
        if typed_state.get("tiers", {}).get(message_id) == "fact"
    }
    assert MARKER not in fact_statements
