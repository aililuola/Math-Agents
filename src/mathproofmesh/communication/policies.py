from __future__ import annotations

from dataclasses import dataclass
from pathlib import PurePosixPath
from typing import Iterable

from ..config import SystemConfig
from ..schemas import (
    ClaimStatus,
    EvidenceType,
    MemoryTier,
    MessageEnvelope,
    MessageType,
)


FACT_EVIDENCE = frozenset(
    {
        EvidenceType.NATURAL_PROOF_AUDITED,
        EvidenceType.EXACT_SYMBOLIC_IDENTITY,
        EvidenceType.COMPLETE_FINITE_ENUMERATION,
        EvidenceType.SAT_SMT_CERTIFICATE,
        EvidenceType.FORMAL_KERNEL_CERTIFICATE,
    }
)


@dataclass(frozen=True, slots=True)
class GateResult:
    accepted: bool
    reason: str = ""


def validate_evidence_tier(
    message: MessageEnvelope,
    config: SystemConfig,
    *,
    referee_agent_id: str | None,
    dependencies_resolved: bool,
    dependency_cycle: bool,
    known_counterexample: bool,
) -> GateResult:
    """Apply the irreversible Fact/Insight/Negative evidence boundary."""
    if message.evidence_type == EvidenceType.COUNTEREXAMPLE:
        if message.memory_tier != MemoryTier.NEGATIVE:
            return GateResult(False, "counterexamples must enter NegativeMemory")
        if message.verification_status != ClaimStatus.REJECTED:
            return GateResult(False, "counterexamples must reject their target claim")
        if referee_agent_id is None or referee_agent_id == message.source_agent_id:
            return GateResult(False, "counterexamples require independent replay")
        return GateResult(True)

    if message.memory_tier == MemoryTier.NEGATIVE:
        if message.verification_status != ClaimStatus.REJECTED:
            return GateResult(False, "negative messages require rejected status")
        return GateResult(True)

    if message.memory_tier == MemoryTier.INSIGHT:
        if message.evidence_type in FACT_EVIDENCE and (
            message.verification_status == ClaimStatus.VERIFIED
        ):
            # Keeping strong evidence as an insight is conservative and valid.
            return GateResult(True)
        return GateResult(True)

    typed = config.topology.typed_memory
    if message.verification_status != ClaimStatus.VERIFIED:
        return GateResult(False, "FactMemory requires verified status")
    if message.verification_confidence < typed.fact_pass_threshold:
        return GateResult(False, "verification confidence is below the fact gate")
    if message.evidence_type not in FACT_EVIDENCE:
        return GateResult(False, "evidence type cannot establish a reusable fact")
    if referee_agent_id is None:
        return GateResult(False, "FactMemory requires an independent route referee")
    if referee_agent_id == message.source_agent_id:
        return GateResult(False, "the author cannot referee its own fact")
    if not dependencies_resolved:
        return GateResult(False, "fact dependencies are unresolved")
    if dependency_cycle:
        return GateResult(False, "fact dependency cycle detected")
    if known_counterexample:
        return GateResult(False, "a known counterexample blocks fact promotion")
    normalized = f"{message.normalized_statement} {message.conclusion}".casefold()
    has_quantifier_marker = any(
        marker in normalized
        for marker in (
            "for all",
            "for every",
            "there exists",
            "forall",
            "exists",
            "\u2200",
            "\u2203",
        )
    )
    if has_quantifier_marker and not message.quantifiers:
        return GateResult(False, "global fact has an unparsed quantifier scope")
    if message.normalization_confidence < typed.fact_pass_threshold:
        return GateResult(False, "quantifier/scope normalization is incomplete")
    return GateResult(True)


def cross_route_share_allowed(message: MessageEnvelope, config: SystemConfig) -> bool:
    policy = config.topology.cross_route
    if message.evidence_type == EvidenceType.COUNTEREXAMPLE:
        return policy.share_counterexamples
    if message.message_type == MessageType.PROOF_OBLIGATION:
        return policy.share_open_obligations
    if message.message_type == MessageType.FAILURE_RECORD:
        return policy.share_failure_records
    if message.memory_tier == MemoryTier.FACT:
        return policy.share_verified_facts
    if message.memory_tier == MemoryTier.INSIGHT:
        return policy.share_unverified_insights
    return False


def validate_artifact_refs(refs: Iterable[str]) -> GateResult:
    """Reject external paths and path traversal before touching the store."""
    for ref in refs:
        if not ref.startswith("artifact://"):
            return GateResult(False, "artifact references must be run-scoped")
        relative = ref.removeprefix("artifact://")
        path = PurePosixPath(relative)
        if path.is_absolute() or ".." in path.parts:
            return GateResult(False, "artifact reference escapes the run root")
    return GateResult(True)
