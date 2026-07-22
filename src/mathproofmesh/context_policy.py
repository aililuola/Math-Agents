from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from enum import StrEnum
from typing import Any, Sequence

from .communication.broker import MessageBroker
from .config import SystemConfig
from .memory import LemmaMemory, TypedMemory
from .schemas import ClaimCard, EvidenceType, MessageEnvelope
from .store import ArtifactStore
from .topology import jaccard_similarity


class ContextPurpose(StrEnum):
    DELTA_VERIFICATION = "delta_verification"
    ATTEMPT_VERIFICATION = "attempt_verification"
    FINAL_VERIFICATION = "final_verification"
    SYNTHESIS = "synthesis"
    BLIND_REVIEW = "blind_review"
    FINAL_REVISION = "final_revision"


@dataclass(frozen=True, slots=True)
class ContextPurposePolicy:
    max_global_char_fraction: float
    relevance_weight: float
    evidence_weight: float
    confidence_weight: float
    centrality_weight: float
    include_raw_artifact_refs: bool
    include_review_provenance: bool
    include_normalization_confidence: bool


@dataclass(frozen=True, slots=True)
class FactContextSelection:
    packets: list[dict[str, Any]]
    selected_message_ids: list[str]
    missing_required_refs: list[str]
    used_chars: int
    max_chars: int
    truncated: bool

    @property
    def required_context_complete(self) -> bool:
        return not self.missing_required_refs


_PURPOSE_POLICIES: dict[ContextPurpose, ContextPurposePolicy] = {
    ContextPurpose.DELTA_VERIFICATION: ContextPurposePolicy(
        0.25, 0.45, 0.25, 0.20, 0.10, True, True, True
    ),
    ContextPurpose.ATTEMPT_VERIFICATION: ContextPurposePolicy(
        0.25, 0.45, 0.25, 0.20, 0.10, True, True, True
    ),
    ContextPurpose.FINAL_VERIFICATION: ContextPurposePolicy(
        0.35, 0.40, 0.30, 0.20, 0.10, True, True, True
    ),
    ContextPurpose.SYNTHESIS: ContextPurposePolicy(
        0.30, 0.40, 0.20, 0.10, 0.30, False, False, False
    ),
    ContextPurpose.BLIND_REVIEW: ContextPurposePolicy(
        0.45, 0.40, 0.30, 0.20, 0.10, False, True, True
    ),
    ContextPurpose.FINAL_REVISION: ContextPurposePolicy(
        0.30, 0.50, 0.25, 0.15, 0.10, False, True, True
    ),
}


_EVIDENCE_SCORE: dict[EvidenceType, float] = {
    EvidenceType.UNVERIFIED_IDEA: 0.05,
    EvidenceType.NUMERICAL_HEURISTIC: 0.15,
    EvidenceType.BOUNDED_EXPERIMENT: 0.35,
    EvidenceType.EXACT_SYMBOLIC_IDENTITY: 0.75,
    EvidenceType.COMPLETE_FINITE_ENUMERATION: 0.85,
    EvidenceType.SAT_SMT_CERTIFICATE: 0.90,
    EvidenceType.COUNTEREXAMPLE: 0.95,
    EvidenceType.NATURAL_PROOF_AUDITED: 0.90,
    EvidenceType.FORMAL_KERNEL_CERTIFICATE: 1.00,
}


_REPLAY_REQUIRED_EVIDENCE = {
    EvidenceType.BOUNDED_EXPERIMENT,
    EvidenceType.EXACT_SYMBOLIC_IDENTITY,
    EvidenceType.COMPLETE_FINITE_ENUMERATION,
    EvidenceType.SAT_SMT_CERTIFICATE,
    EvidenceType.FORMAL_KERNEL_CERTIFICATE,
}


def evidence_priority(evidence_type: EvidenceType) -> float:
    return _EVIDENCE_SCORE[evidence_type]


def purpose_context_limits(
    config: SystemConfig,
    *,
    purpose: ContextPurpose,
    requested_max_chars: int,
    requested_max_items: int,
) -> tuple[int, int]:
    """Clamp a caller request to the field and budget policy for its stage."""

    policy = _PURPOSE_POLICIES[purpose]
    purpose_char_cap = max(
        1,
        int(config.topology.max_context_chars * policy.max_global_char_fraction),
    )
    return (
        min(requested_max_chars, purpose_char_cap),
        min(requested_max_items, config.topology.typed_memory.max_fact_context),
    )


def explicit_dependency_refs(value: Any) -> list[str]:
    """Extract non-local dependency IDs/hashes while preserving first-use order."""

    if hasattr(value, "model_dump"):
        value = value.model_dump(mode="json")

    local_ids: set[str] = set()

    def collect_local_ids(item: Any) -> None:
        if isinstance(item, dict):
            for key, nested in item.items():
                if key in {"step_id", "claim_id"} and isinstance(nested, str):
                    local_ids.add(nested)
                collect_local_ids(nested)
        elif isinstance(item, (list, tuple)):
            for nested in item:
                collect_local_ids(nested)

    collect_local_ids(value)
    refs: list[str] = []

    def collect_dependencies(item: Any) -> None:
        if isinstance(item, dict):
            for key, nested in item.items():
                if key == "dependencies" and isinstance(nested, list):
                    for dependency in nested:
                        if not isinstance(dependency, str):
                            continue
                        dependency = dependency.strip()
                        if (
                            dependency
                            and dependency not in local_ids
                            and not dependency.startswith("external:")
                            and dependency not in refs
                        ):
                            refs.append(dependency)
                else:
                    collect_dependencies(nested)
        elif isinstance(item, (list, tuple)):
            for nested in item:
                collect_dependencies(nested)

    collect_dependencies(value)
    return refs


def _artifact_sha256(store: ArtifactStore | None, ref: str) -> str | None:
    if store is None:
        return None
    try:
        path = store.resolve(ref)
        if not path.is_file():
            return None
        digest = hashlib.sha256()
        with path.open("rb") as handle:
            for chunk in iter(lambda: handle.read(1024 * 1024), b""):
                digest.update(chunk)
        return digest.hexdigest()
    except (OSError, ValueError):
        return None


def blind_artifact_evidence(
    refs: Sequence[str],
    *,
    store: ArtifactStore | None,
    evidence_type: EvidenceType,
) -> list[dict[str, str | None]]:
    """Replace identity-bearing run paths with content-addressed blind metadata."""

    descriptors: list[dict[str, str | None]] = []
    seen: set[tuple[str | None, str, str]] = set()
    for ref in refs:
        content_hash = _artifact_sha256(store, ref)
        if content_hash is None:
            replay_status = "unavailable"
        elif evidence_type in _REPLAY_REQUIRED_EVIDENCE:
            replay_status = "available_not_replayed_in_packet"
        else:
            replay_status = "not_applicable"
        key = (content_hash, evidence_type.value, replay_status)
        if key in seen:
            continue
        seen.add(key)
        descriptors.append(
            {
                "artifact_content_hash": content_hash,
                "certificate_type": evidence_type.value,
                "replay_status": replay_status,
            }
        )
    return descriptors


def typed_fact_context_packet(
    message: MessageEnvelope,
    *,
    broker: MessageBroker,
    purpose: ContextPurpose = ContextPurpose.ATTEMPT_VERIFICATION,
    artifact_store: ArtifactStore | None = None,
) -> dict[str, Any]:
    """Build an identity-free packet for a globally admitted typed fact."""

    policy = _PURPOSE_POLICIES[purpose]
    packet: dict[str, Any] = {
        "context_purpose": purpose.value,
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
        "content_hash": message.content_hash,
    }
    if policy.include_normalization_confidence:
        packet["normalization_confidence"] = message.normalization_confidence
    if policy.include_review_provenance:
        packet["review_provenance"] = broker.blind_review_provenance(message.message_id)
    if policy.include_raw_artifact_refs:
        packet["artifact_refs"] = list(message.artifact_refs)
    elif purpose == ContextPurpose.BLIND_REVIEW:
        packet["artifact_evidence"] = blind_artifact_evidence(
            message.artifact_refs,
            store=artifact_store,
            evidence_type=message.evidence_type,
        )
    return packet


def _fact_search_text(message: MessageEnvelope, purpose: ContextPurpose) -> str:
    values = [
        message.statement,
        message.conclusion,
        message.normalized_statement,
    ]
    if purpose != ContextPurpose.SYNTHESIS:
        values.extend(message.assumptions)
        values.extend(message.scope_limitations)
        values.extend(item.domain for item in message.quantifiers)
        values.extend(item.domain for item in message.variable_bindings)
    return " ".join(values)


def _fact_centrality(messages: Sequence[MessageEnvelope]) -> dict[str, float]:
    by_id = {message.message_id: message for message in messages}
    by_hash = {message.content_hash: message for message in messages}
    incoming = {message.message_id: 0 for message in messages}
    for message in messages:
        for dependency in message.dependencies:
            resolved = by_id.get(dependency) or by_hash.get(dependency)
            if resolved is not None:
                incoming[resolved.message_id] += 1
    maximum = max(incoming.values(), default=0)
    return {
        message.message_id: min(
            1.0,
            (incoming[message.message_id] / maximum if maximum else 0.0)
            + min(len(message.target_route_ids), 4) * 0.05,
        )
        for message in messages
    }


def _rank_facts(
    messages: Sequence[MessageEnvelope],
    *,
    query: str,
    purpose: ContextPurpose,
) -> list[MessageEnvelope]:
    policy = _PURPOSE_POLICIES[purpose]
    centrality = _fact_centrality(messages)
    insertion_order = {
        message.message_id: index for index, message in enumerate(messages)
    }

    def score(message: MessageEnvelope) -> float:
        relevance = jaccard_similarity(query, _fact_search_text(message, purpose))
        confidence = (
            message.verification_confidence + message.normalization_confidence
        ) / 2
        return (
            policy.relevance_weight * relevance
            + policy.evidence_weight * _EVIDENCE_SCORE[message.evidence_type]
            + policy.confidence_weight * confidence
            + policy.centrality_weight * centrality[message.message_id]
        )

    return sorted(
        messages,
        key=lambda message: (
            -score(message),
            insertion_order[message.message_id],
        ),
    )


def select_typed_fact_context_with_diagnostics(
    messages: Sequence[MessageEnvelope],
    *,
    broker: MessageBroker,
    query: str,
    max_chars: int,
    max_items: int,
    purpose: ContextPurpose = ContextPurpose.ATTEMPT_VERIFICATION,
    required_refs: Sequence[str] = (),
    artifact_store: ArtifactStore | None = None,
) -> FactContextSelection:
    """Select admitted facts with required references and dependency closure first."""

    admitted: list[MessageEnvelope] = []
    seen_admitted: set[str] = set()
    for message in messages:
        if message.message_id not in seen_admitted and broker.is_globally_admitted_fact(
            message.message_id
        ):
            admitted.append(message)
            seen_admitted.add(message.message_id)
    if not admitted or max_chars <= 0 or max_items <= 0:
        missing = [
            ref
            for ref in dict.fromkeys(required_refs)
            if ref and not ref.startswith("external:")
        ]
        return FactContextSelection(
            packets=[],
            selected_message_ids=[],
            missing_required_refs=missing,
            used_chars=0,
            max_chars=max_chars,
            truncated=bool(admitted or missing),
        )

    by_id = {message.message_id: message for message in admitted}
    by_hash = {message.content_hash: message for message in admitted}
    ranked = _rank_facts(admitted, query=query, purpose=purpose)
    selected: list[MessageEnvelope] = []
    selected_packets: list[dict[str, Any]] = []
    selected_ids: set[str] = set()
    used_chars = 0

    def dependency_closure(
        message: MessageEnvelope,
        visiting: set[str] | None = None,
    ) -> tuple[list[MessageEnvelope] | None, list[str]]:
        active = set(visiting or set())
        if message.message_id in active:
            return None, [message.message_id]
        active.add(message.message_id)
        ordered: list[MessageEnvelope] = []
        missing_dependencies: list[str] = []
        for dependency in message.dependencies:
            if dependency.startswith("external:"):
                continue
            resolved = by_id.get(dependency) or by_hash.get(dependency)
            if resolved is None:
                missing_dependencies.append(dependency)
                continue
            nested, nested_missing = dependency_closure(resolved, active)
            if nested is None:
                missing_dependencies.extend(nested_missing)
                continue
            ordered.extend(nested)
            missing_dependencies.extend(nested_missing)
        if missing_dependencies:
            return None, list(dict.fromkeys(missing_dependencies))
        ordered.append(message)
        deduplicated: list[MessageEnvelope] = []
        seen: set[str] = set()
        for item in ordered:
            if item.message_id in seen:
                continue
            deduplicated.append(item)
            seen.add(item.message_id)
        return deduplicated, []

    def add_closure(closure: Sequence[MessageEnvelope]) -> bool:
        nonlocal used_chars
        additions = [item for item in closure if item.message_id not in selected_ids]
        if not additions:
            return True
        if len(selected) + len(additions) > max_items:
            return False
        packets = [
            typed_fact_context_packet(
                item,
                broker=broker,
                purpose=purpose,
                artifact_store=artifact_store,
            )
            for item in additions
        ]
        packet_size = sum(
            len(json.dumps(packet, ensure_ascii=False, separators=(",", ":")))
            for packet in packets
        )
        if used_chars + packet_size > max_chars:
            return False
        selected.extend(additions)
        selected_packets.extend(packets)
        selected_ids.update(item.message_id for item in additions)
        used_chars += packet_size
        return True

    missing_required: list[str] = []
    normalized_required = [
        ref
        for ref in dict.fromkeys(required_refs)
        if ref and not ref.startswith("external:")
    ]
    for ref in normalized_required:
        required = by_id.get(ref) or by_hash.get(ref)
        if required is None:
            missing_required.append(ref)
            continue
        closure, missing_dependencies = dependency_closure(required)
        if closure is None:
            missing_required.extend([ref, *missing_dependencies])
            continue
        if not add_closure(closure):
            missing_required.append(ref)

    for message in ranked:
        if message.message_id in selected_ids:
            continue
        closure, _missing_dependencies = dependency_closure(message)
        if closure is None:
            continue
        add_closure(closure)
        if len(selected) >= max_items:
            break

    missing_required = list(dict.fromkeys(missing_required))
    return FactContextSelection(
        packets=selected_packets,
        selected_message_ids=[message.message_id for message in selected],
        missing_required_refs=missing_required,
        used_chars=used_chars,
        max_chars=max_chars,
        truncated=len(selected_ids) < len(admitted) or bool(missing_required),
    )


def select_typed_fact_context(
    messages: Sequence[MessageEnvelope],
    *,
    broker: MessageBroker,
    query: str,
    max_chars: int,
    max_items: int,
    purpose: ContextPurpose = ContextPurpose.ATTEMPT_VERIFICATION,
    required_refs: Sequence[str] = (),
    artifact_store: ArtifactStore | None = None,
) -> list[dict[str, Any]]:
    """Select whole admitted fact packets together with their dependency closure."""
    return select_typed_fact_context_with_diagnostics(
        messages,
        broker=broker,
        query=query,
        max_chars=max_chars,
        max_items=max_items,
        purpose=purpose,
        required_refs=required_refs,
        artifact_store=artifact_store,
    ).packets


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
    required_refs: Sequence[str] = (),
) -> list[dict[str, Any]]:
    """Return the only fact context admissible for a global proof stage."""
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
    max_chars, max_items = purpose_context_limits(
        config,
        purpose=purpose,
        requested_max_chars=max_chars,
        requested_max_items=max_items,
    )
    return select_typed_fact_context(
        admitted,
        broker=message_broker,
        query=query,
        max_chars=max_chars,
        max_items=max_items,
        purpose=purpose,
        required_refs=required_refs,
        artifact_store=typed_memory.store,
    )
