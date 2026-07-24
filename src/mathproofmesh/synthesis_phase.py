from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any

from .communication.broker import MessageBroker
from .context_policy import (
    ContextPurpose,
    FactContextSelection,
    blind_artifact_evidence,
    evidence_priority,
    explicit_dependency_refs,
    purpose_context_limits,
    select_typed_fact_context_with_diagnostics,
)
from .memory import LemmaMemory, TypedMemory
from .schemas import (
    BlindReviewPacket,
    EvidenceType,
    FailureLevel,
    FinalProof,
    InspirationProposal,
    MessageEnvelope,
    ProblemContract,
    Severity,
    VerificationIssue,
    VerificationReport,
    VerificationVerdict,
)
from .store import ArtifactStore
from .topology import jaccard_similarity


@dataclass(frozen=True, slots=True)
class BlindNegativeSelection:
    packets: list[dict[str, Any]]
    total_count: int
    omitted_count: int
    omitted_mandatory_count: int
    used_chars: int
    max_chars: int

    @property
    def truncated(self) -> bool:
        return self.omitted_count > 0

    @property
    def mandatory_context_complete(self) -> bool:
        return self.omitted_mandatory_count == 0


def apply_blind_context_integrity_guard(
    packet: BlindReviewPacket,
    report: VerificationReport,
) -> None:
    """Deterministically prevent PASS with missing mandatory blind context."""

    defects: list[str] = []
    if not packet.fact_context_complete:
        detail = ", ".join(packet.fact_context_failure_reasons) or (
            "required global Fact context is incomplete"
        )
        if packet.missing_cited_fact_refs:
            detail += f"; missing refs={packet.missing_cited_fact_refs}"
        defects.append(detail)
    if not packet.negative_context_complete:
        defects.append(
            f"{packet.negative_mandatory_omitted_count} mandatory negative "
            "evidence packet(s) could not fit the blind context budget"
        )
    if not defects:
        return
    report.issues.append(
        VerificationIssue(
            phase="blind_context_integrity_guard",
            severity=Severity.CRITICAL,
            description="; ".join(defects),
            repair_hint=(
                "Admit every cited Fact through Broker/TypedMemory and provide all "
                "mandatory counterexamples or direct conflicts before final review."
            ),
        )
    )
    failure_rank = {
        FailureLevel.NONE: 0,
        FailureLevel.EXECUTION: 1,
        FailureLevel.PLAN: 2,
        FailureLevel.STRATEGY: 3,
    }
    report.failure_level = max(
        report.failure_level,
        FailureLevel.PLAN,
        key=failure_rank.__getitem__,
    )
    report.verdict = VerificationVerdict.FAIL
    report.confidence = 1.0
    report.concise_feedback = (
        "Deterministic blind-context integrity validation failed. "
        + report.concise_feedback
    )


def message_negative_packet(
    item: MessageEnvelope,
    *,
    artifact_store: ArtifactStore | None = None,
) -> dict[str, Any]:
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
        "artifact_evidence": blind_artifact_evidence(
            item.artifact_refs,
            store=artifact_store,
            evidence_type=item.evidence_type,
        ),
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
    *,
    artifact_store: ArtifactStore | None = None,
) -> dict[str, Any]:
    if isinstance(item, MessageEnvelope):
        return message_negative_packet(item, artifact_store=artifact_store)
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


def _negative_identity(item: MessageEnvelope | InspirationProposal) -> str:
    if isinstance(item, MessageEnvelope):
        return item.content_hash or item.message_id
    return item.novelty_signature.normalized_hash or item.proposal_id


def _negative_item_id(item: MessageEnvelope | InspirationProposal) -> str:
    return item.message_id if isinstance(item, MessageEnvelope) else item.proposal_id


def _negative_search_text(item: MessageEnvelope | InspirationProposal) -> str:
    if isinstance(item, MessageEnvelope):
        return " ".join(
            [
                item.statement,
                item.normalized_statement,
                item.conclusion,
                *item.assumptions,
                *item.scope_limitations,
            ]
        )
    return " ".join(
        [
            item.statement,
            item.rationale_summary,
            *item.generated_obligations,
        ]
    )


def _negative_score(
    item: MessageEnvelope | InspirationProposal,
    *,
    proof_text: str,
) -> float:
    relevance = jaccard_similarity(proof_text, _negative_search_text(item))
    if isinstance(item, MessageEnvelope):
        evidence = evidence_priority(item.evidence_type)
        centrality = min(1.0, len(item.target_route_ids) / 4)
        confidence = item.verification_confidence
        novelty = 0.0
    else:
        evidence = evidence_priority(item.evidence_type)
        centrality = min(1.0, len(item.target_route_ids) / 4)
        confidence = 0.0
        novelty = item.novelty_score
    return (
        0.50 * relevance
        + 0.25 * evidence
        + 0.10 * centrality
        + 0.10 * confidence
        + 0.05 * novelty
    )


def _mandatory_negative(
    item: MessageEnvelope | InspirationProposal,
    *,
    proof_text: str,
    required_refs: set[str],
) -> bool:
    if (
        _negative_item_id(item) in required_refs
        or _negative_identity(item) in required_refs
    ):
        return True
    if (
        isinstance(item, MessageEnvelope)
        and item.evidence_type == EvidenceType.COUNTEREXAMPLE
    ):
        return True
    statement = " ".join(item.statement.casefold().split())
    normalized_proof = " ".join(proof_text.casefold().split())
    return bool(statement and statement in normalized_proof)


def _compact_negative_packet(packet: dict[str, Any]) -> dict[str, Any]:
    keep = {
        "item_id",
        "proposal_kind",
        "statement",
        "normalized_statement",
        "assumptions",
        "conclusion",
        "scope_limitations",
        "rationale_summary",
        "generated_obligations",
        "evidence_type",
        "content_hash",
        "novelty_hash",
        "artifact_evidence",
    }
    return {key: value for key, value in packet.items() if key in keep}


def select_blind_negative_context(
    negatives: list[MessageEnvelope | InspirationProposal],
    *,
    proof_text: str,
    required_refs: list[str],
    max_items: int,
    max_chars: int,
    artifact_store: ArtifactStore | None,
) -> BlindNegativeSelection:
    """Keep all safety-critical negatives first, then fill by ranked utility."""

    deduplicated: list[MessageEnvelope | InspirationProposal] = []
    seen: set[str] = set()
    for item in negatives:
        identity = _negative_identity(item)
        if identity in seen:
            continue
        seen.add(identity)
        deduplicated.append(item)

    required = set(required_refs)
    insertion_order = {id(item): index for index, item in enumerate(deduplicated)}
    ranked = sorted(
        deduplicated,
        key=lambda item: (
            not _mandatory_negative(
                item,
                proof_text=proof_text,
                required_refs=required,
            ),
            -_negative_score(item, proof_text=proof_text),
            insertion_order[id(item)],
        ),
    )
    packets: list[dict[str, Any]] = []
    used_chars = 0
    omitted_mandatory = 0
    for item in ranked:
        mandatory = _mandatory_negative(
            item,
            proof_text=proof_text,
            required_refs=required,
        )
        if len(packets) >= max_items:
            if mandatory:
                omitted_mandatory += 1
            continue
        packet = _negative_packet(item, artifact_store=artifact_store)
        encoded = json.dumps(packet, ensure_ascii=False, separators=(",", ":"))
        if used_chars + len(encoded) > max_chars and mandatory:
            packet = _compact_negative_packet(packet)
            encoded = json.dumps(packet, ensure_ascii=False, separators=(",", ":"))
        if used_chars + len(encoded) > max_chars:
            if mandatory:
                omitted_mandatory += 1
            continue
        packets.append(packet)
        used_chars += len(encoded)

    return BlindNegativeSelection(
        packets=packets,
        total_count=len(deduplicated),
        omitted_count=max(0, len(deduplicated) - len(packets)),
        omitted_mandatory_count=omitted_mandatory,
        used_chars=used_chars,
        max_chars=max_chars,
    )


def _legacy_blind_fact_packets(legacy_memory: LemmaMemory) -> list[dict[str, Any]]:
    return [
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


def _legacy_forbidden_claims(legacy_memory: LemmaMemory) -> list[str]:
    return [claim.statement for claim in legacy_memory.rejected()]


def build_blind_review_packet(
    problem: ProblemContract,
    proof: FinalProof,
    legacy_memory: LemmaMemory,
    *,
    topology_mode: str = "legacy_sparse",
    typed_memory: TypedMemory | None = None,
    message_broker: MessageBroker | None = None,
    artifact_store: ArtifactStore | None = None,
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
    include_legacy_memory = topology_mode == "legacy_sparse"
    legacy_facts = (
        _legacy_blind_fact_packets(legacy_memory) if include_legacy_memory else []
    )
    typed_fact_ids = (
        {message.message_id for message in typed_memory.facts}
        if typed_memory is not None
        else set()
    )
    admitted_typed_facts = (
        [
            message
            for message in message_broker.admitted_facts()
            if message.message_id in typed_fact_ids
        ]
        if typed_memory is not None and message_broker is not None
        else []
    )
    required_refs = explicit_dependency_refs(proof)
    if typed_memory is not None:
        for fact in typed_memory.facts:
            for ref in (fact.message_id, fact.content_hash):
                if ref and ref in proof_text and ref not in required_refs:
                    required_refs.append(ref)
    config = (
        message_broker.config
        if message_broker is not None
        else typed_memory.config
        if typed_memory is not None
        else None
    )
    artifact_store = artifact_store or (
        typed_memory.store if typed_memory is not None else None
    )
    if config is not None:
        fact_max_chars, fact_max_items = purpose_context_limits(
            config,
            purpose=ContextPurpose.BLIND_REVIEW,
            requested_max_chars=config.topology.max_context_chars,
            requested_max_items=config.topology.max_verified_claims_per_context,
        )
    else:
        fact_max_chars, fact_max_items = 40000, 24
    fact_selection = (
        select_typed_fact_context_with_diagnostics(
            admitted_typed_facts,
            broker=message_broker,
            query=proof_text,
            max_chars=fact_max_chars,
            max_items=fact_max_items,
            purpose=ContextPurpose.BLIND_REVIEW,
            required_refs=required_refs if not include_legacy_memory else [],
            artifact_store=artifact_store,
        )
        if message_broker is not None
        else FactContextSelection(
            packets=[],
            selected_message_ids=[],
            missing_required_refs=(required_refs if not include_legacy_memory else []),
            used_chars=0,
            max_chars=fact_max_chars,
            truncated=bool(required_refs and not include_legacy_memory),
        )
    )
    if config is not None:
        negative_max_items = config.topology.typed_memory.max_negative_context
        negative_max_chars = max(1, int(config.topology.max_context_chars * 0.30))
    else:
        negative_max_items = 16
        negative_max_chars = 27000
    negative_selection = select_blind_negative_context(
        typed_memory.negatives if typed_memory is not None else [],
        proof_text=proof_text,
        required_refs=required_refs,
        max_items=negative_max_items,
        max_chars=negative_max_chars,
        artifact_store=artifact_store,
    )
    forbidden = _legacy_forbidden_claims(legacy_memory) if include_legacy_memory else []
    forbidden.extend(
        _negative_statement(packet) for packet in negative_selection.packets
    )
    fact_context_failure_reasons: list[str] = []
    if not include_legacy_memory and (typed_memory is None or message_broker is None):
        fact_context_failure_reasons.append(
            "typed memory or message broker is unavailable"
        )
    if fact_selection.missing_required_refs:
        fact_context_failure_reasons.append(
            "one or more explicitly cited Fact references were not globally admitted"
        )
    return BlindReviewPacket(
        problem=problem,
        final_proof_text=proof_text,
        cited_fact_packets=[*fact_selection.packets, *legacy_facts],
        negative_evidence_packets=negative_selection.packets,
        forbidden_claims=list(dict.fromkeys(item for item in forbidden if item)),
        fact_context_complete=not fact_context_failure_reasons,
        missing_cited_fact_refs=fact_selection.missing_required_refs,
        fact_context_failure_reasons=fact_context_failure_reasons,
        negative_context_complete=negative_selection.mandatory_context_complete,
        negative_context_truncated=negative_selection.truncated,
        negative_evidence_total_count=negative_selection.total_count,
        negative_evidence_omitted_count=negative_selection.omitted_count,
        negative_mandatory_omitted_count=negative_selection.omitted_mandatory_count,
        negative_context_chars_used=negative_selection.used_chars,
        negative_context_char_budget=negative_selection.max_chars,
    )
