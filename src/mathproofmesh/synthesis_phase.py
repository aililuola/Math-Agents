from __future__ import annotations

import json
from typing import Any

from .communication.broker import MessageBroker
from .memory import LemmaMemory, TypedMemory
from .schemas import (
    BlindReviewPacket,
    FinalProof,
    InspirationProposal,
    MessageEnvelope,
    ProblemContract,
)


def _typed_fact_packet(
    message: MessageEnvelope,
    review_provenance: dict[str, Any],
) -> dict[str, Any]:
    """Build identity-free mathematical provenance for a blind final judge."""
    return {
        "message_id": message.message_id,
        "statement": message.statement,
        "normalized_statement": message.normalized_statement,
        "assumptions": message.assumptions,
        "conclusion": message.conclusion,
        "quantifiers": [item.model_dump(mode="json") for item in message.quantifiers],
        "variable_bindings": [
            item.model_dump(mode="json") for item in message.variable_bindings
        ],
        "dependencies": message.dependencies,
        "scope_limitations": message.scope_limitations,
        "evidence_type": message.evidence_type.value,
        "verification_status": message.verification_status.value,
        "verification_confidence": message.verification_confidence,
        "normalization_confidence": message.normalization_confidence,
        "artifact_refs": message.artifact_refs,
        "content_hash": message.content_hash,
        "review_provenance": review_provenance,
    }


def message_negative_packet(item: MessageEnvelope) -> dict[str, Any]:
    return {
        "item_id": item.message_id,
        "statement": item.statement,
        "normalized_statement": item.normalized_statement,
        "assumptions": item.assumptions,
        "conclusion": item.conclusion,
        "quantifiers": [value.model_dump(mode="json") for value in item.quantifiers],
        "variable_bindings": [
            value.model_dump(mode="json") for value in item.variable_bindings
        ],
        "scope_limitations": item.scope_limitations,
        "evidence_type": item.evidence_type.value,
        "artifact_refs": item.artifact_refs,
        "content_hash": item.content_hash,
    }


def inspiration_proposal_to_blind_negative_packet(
    item: InspirationProposal,
) -> dict[str, Any]:
    payload = item.model_dump(mode="json")
    novelty = dict(payload["novelty_signature"])
    return {
        "item_id": payload["proposal_id"],
        "proposal_kind": payload["mechanism"],
        "statement": payload["statement"],
        "rationale_summary": payload["rationale_summary"],
        "generated_obligations": list(payload["generated_obligations"]),
        "expected_information_gain": payload["expected_information_gain"],
        "estimated_cost": payload["estimated_cost"],
        "evidence_type": payload["evidence_type"],
        "novelty_score": payload["novelty_score"],
        "novelty_hash": novelty["normalized_hash"],
        "representation": payload.get("representation"),
        "analogy": payload.get("analogy"),
        "construction": payload.get("construction"),
        "invariant": payload.get("invariant"),
        "reverse_goal": payload.get("reverse_goal"),
    }


def _negative_packet(
    item: MessageEnvelope | InspirationProposal,
) -> dict[str, Any]:
    if isinstance(item, MessageEnvelope):
        return message_negative_packet(item)
    if isinstance(item, InspirationProposal):
        return inspiration_proposal_to_blind_negative_packet(item)
    raise TypeError(f"unsupported negative evidence type: {type(item)!r}")


def _negative_statement(packet: dict[str, Any]) -> str:
    return str(
        packet.get("statement")
        or packet.get("conclusion")
        or packet.get("rationale_summary")
        or ""
    )


def build_blind_review_packet(
    problem: ProblemContract,
    proof: FinalProof,
    legacy_memory: LemmaMemory,
    *,
    typed_memory: TypedMemory | None = None,
    message_broker: MessageBroker | None = None,
) -> BlindReviewPacket:
    proof_text = json.dumps(
        {
            "answer": proof.answer,
            "proof_steps": [
                step.model_dump(mode="json", exclude={"confidence"})
                for step in proof.proof_steps
            ],
            "dependencies": proof.dependencies,
            "caveats": proof.caveats,
        },
        ensure_ascii=False,
        sort_keys=True,
    )
    legacy_facts = [
        {
            "claim_id": claim.claim_id,
            "statement": claim.statement,
            "assumptions": claim.assumptions,
            "conclusion": claim.conclusion,
            "dependencies": claim.dependencies,
            "scope_limitations": claim.scope_limitations,
            "content_hash": claim.content_hash,
            "evidence_source": "legacy_migration_store",
        }
        for claim in legacy_memory.verified()
    ]
    typed_facts = (
        [
            _typed_fact_packet(
                message,
                (
                    message_broker.blind_review_provenance(message.message_id)
                    if message_broker is not None
                    else {
                        "independent_referee_recorded": False,
                        "reviewer_count": 0,
                    }
                ),
            )
            for message in typed_memory.facts
        ]
        if typed_memory is not None
        else []
    )
    negative_packets = (
        [_negative_packet(item) for item in typed_memory.negatives]
        if typed_memory is not None
        else []
    )
    forbidden = [claim.statement for claim in legacy_memory.rejected()]
    forbidden.extend(_negative_statement(packet) for packet in negative_packets)
    return BlindReviewPacket(
        problem=problem,
        final_proof_text=proof_text,
        cited_fact_packets=[*typed_facts, *legacy_facts],
        negative_evidence_packets=negative_packets,
        forbidden_claims=list(dict.fromkeys(item for item in forbidden if item)),
    )
