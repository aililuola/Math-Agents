from __future__ import annotations

import json
from enum import StrEnum
from typing import Any, Sequence

from .communication.broker import MessageBroker
from .config import SystemConfig
from .memory import LemmaMemory, TypedMemory
from .schemas import ClaimCard, MessageEnvelope
from .topology import jaccard_similarity


class ContextPurpose(StrEnum):
    DELTA_VERIFICATION = "delta_verification"
    ATTEMPT_VERIFICATION = "attempt_verification"
    FINAL_VERIFICATION = "final_verification"
    SYNTHESIS = "synthesis"
    BLIND_REVIEW = "blind_review"
    FINAL_REVISION = "final_revision"


def typed_fact_context_packet(
    message: MessageEnvelope,
    *,
    broker: MessageBroker,
) -> dict[str, Any]:
    """Build an identity-free packet for a globally admitted typed fact."""
    return {
        "message_id": message.message_id,
        "statement": message.statement,
        "normalized_statement": message.normalized_statement,
        "assumptions": list(message.assumptions),
        "conclusion": message.conclusion,
        "quantifiers": [value.model_dump(mode="json") for value in message.quantifiers],
        "variable_bindings": [
            value.model_dump(mode="json") for value in message.variable_bindings
        ],
        "dependencies": list(message.dependencies),
        "scope_limitations": list(message.scope_limitations),
        "evidence_type": message.evidence_type.value,
        "verification_status": message.verification_status.value,
        "verification_confidence": message.verification_confidence,
        "normalization_confidence": message.normalization_confidence,
        "artifact_refs": list(message.artifact_refs),
        "content_hash": message.content_hash,
        "review_provenance": broker.blind_review_provenance(message.message_id),
    }


def select_typed_fact_context(
    messages: Sequence[MessageEnvelope],
    *,
    broker: MessageBroker,
    query: str,
    max_chars: int,
    max_items: int,
) -> list[dict[str, Any]]:
    """Select whole admitted fact packets together with their dependency closure."""
    admitted = [
        message
        for message in messages
        if broker.is_globally_admitted_fact(message.message_id)
    ]
    if not admitted or max_chars <= 0 or max_items <= 0:
        return []

    by_id = {message.message_id: message for message in admitted}
    by_hash = {message.content_hash: message for message in admitted}
    ranked = sorted(
        admitted,
        key=lambda message: (
            jaccard_similarity(
                query,
                " ".join(
                    (
                        message.statement,
                        message.conclusion,
                        message.normalized_statement,
                    )
                ),
            ),
            message.verification_confidence,
            message.normalization_confidence,
        ),
        reverse=True,
    )
    selected: list[MessageEnvelope] = []
    selected_ids: set[str] = set()
    used_chars = 0

    def dependency_closure(
        message: MessageEnvelope,
        visiting: set[str] | None = None,
    ) -> list[MessageEnvelope] | None:
        active = set(visiting or set())
        if message.message_id in active:
            return None
        active.add(message.message_id)
        ordered: list[MessageEnvelope] = []
        for dependency in message.dependencies:
            if dependency.startswith("external:"):
                continue
            resolved = by_id.get(dependency) or by_hash.get(dependency)
            if resolved is None:
                return None
            nested = dependency_closure(resolved, active)
            if nested is None:
                return None
            ordered.extend(nested)
        ordered.append(message)
        deduplicated: list[MessageEnvelope] = []
        seen: set[str] = set()
        for item in ordered:
            if item.message_id in seen:
                continue
            deduplicated.append(item)
            seen.add(item.message_id)
        return deduplicated

    for message in ranked:
        closure = dependency_closure(message)
        if closure is None:
            continue
        additions = [item for item in closure if item.message_id not in selected_ids]
        if not additions:
            continue
        if len(selected) + len(additions) > max_items:
            continue
        packets = [typed_fact_context_packet(item, broker=broker) for item in additions]
        packet_size = sum(
            len(json.dumps(packet, ensure_ascii=False, separators=(",", ":")))
            for packet in packets
        )
        if used_chars + packet_size > max_chars:
            continue
        selected.extend(additions)
        selected_ids.update(item.message_id for item in additions)
        used_chars += packet_size
        if len(selected) >= max_items:
            break

    return [typed_fact_context_packet(item, broker=broker) for item in selected]


def select_legacy_claim_context(
    claims: Sequence[ClaimCard],
    *,
    query: str,
    max_chars: int,
    max_items: int,
) -> list[dict[str, Any]]:
    """Preserve the legacy sparse verified-claim selection behavior."""
    if not claims:
        return []
    by_id = {claim.claim_id: claim for claim in claims}
    ranked = sorted(
        claims,
        key=lambda claim: (
            jaccard_similarity(
                query,
                f"{claim.statement} {claim.conclusion} {' '.join(claim.tags)}",
            ),
            claim.verification_confidence or 0.0,
            claim.self_confidence,
        ),
        reverse=True,
    )
    selected: list[ClaimCard] = []
    selected_ids: set[str] = set()
    used_chars = 0

    def dependency_closure(
        claim: ClaimCard,
        visiting: set[str] | None = None,
    ) -> list[ClaimCard]:
        active = set(visiting or set())
        if claim.claim_id in active:
            return []
        active.add(claim.claim_id)
        ordered: list[ClaimCard] = []
        for dependency in claim.dependencies:
            resolved = by_id.get(dependency)
            if resolved is not None:
                ordered.extend(dependency_closure(resolved, active))
        ordered.append(claim)
        deduplicated: list[ClaimCard] = []
        seen: set[str] = set()
        for item in ordered:
            if item.claim_id in seen:
                continue
            deduplicated.append(item)
            seen.add(item.claim_id)
        return deduplicated

    for claim in ranked:
        additions = [
            item
            for item in dependency_closure(claim)
            if item.claim_id not in selected_ids
        ]
        if not additions:
            continue
        encoded = [
            json.dumps(
                item.model_dump(mode="json"),
                ensure_ascii=False,
                separators=(",", ":"),
            )
            for item in additions
        ]
        packet_size = sum(len(value) for value in encoded)
        if selected and used_chars + packet_size > max_chars:
            continue
        for item in additions:
            if item.claim_id not in selected_ids:
                selected.append(item)
                selected_ids.add(item.claim_id)
        used_chars += packet_size
        if len(selected) >= max_items:
            break
    return [claim.model_dump(mode="json") for claim in selected]


def build_admissible_fact_context(
    config: SystemConfig,
    *,
    legacy_memory: LemmaMemory,
    typed_memory: TypedMemory | None,
    message_broker: MessageBroker | None,
    query: str,
    max_chars: int,
    max_items: int,
    purpose: ContextPurpose,
) -> list[dict[str, Any]]:
    """Return the only fact context admissible for a global proof stage."""
    del purpose
    if config.topology.mode == "legacy_sparse":
        return select_legacy_claim_context(
            legacy_memory.verified(),
            query=query,
            max_chars=max_chars,
            max_items=max_items,
        )
    if typed_memory is None or message_broker is None:
        return []
    typed_fact_ids = {fact.message_id for fact in typed_memory.facts}
    admitted = [
        message
        for message in message_broker.admitted_facts()
        if message.message_id in typed_fact_ids
    ]
    return select_typed_fact_context(
        admitted,
        broker=message_broker,
        query=query,
        max_chars=max_chars,
        max_items=max_items,
    )
