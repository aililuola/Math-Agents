from __future__ import annotations

from ..proof_identity import normalize_text
from ..schemas import ObligationKind, ProofObligation
from .models import (
    AssumptionDomain,
    AssumptionDomainRecord,
    ObligationDomain,
    ObligationDomainRecord,
)


_PROTOCOL_MARKERS = (
    "do not change",
    "retain the goal hash",
    "retain goal hash",
    "distinguish proved",
    "output json",
    "output yaml",
    "response schema",
    "required format",
)
_PROCESS_MARKERS = (
    "checkpoint policy",
    "checkpoint format",
    "completed_subgoal",
    "resume policy",
    "workflow state",
    "round budget",
    "processing opportunity",
)
_TOOL_MARKERS = (
    "tool budget",
    "runtime limit",
    "memory limit",
    "execute the computation",
    "run the tool",
    "typed handler",
)
_SAFETY_MARKERS = (
    "api key",
    "network access",
    "sandbox policy",
    "safety policy",
    "secret value",
)
_VERIFICATION_MARKERS = (
    "independent reviewer",
    "ask a reviewer",
    "referee review",
    "verify the proof",
    "verification pass",
    "formalization review",
    "audit the proof",
)


def classify_assumption_domain(statement: str) -> AssumptionDomainRecord:
    normalized = normalize_text(statement).casefold()
    if any(marker in normalized for marker in _SAFETY_MARKERS):
        domain = AssumptionDomain.SAFETY
        source = "safety_marker"
        confidence = 1.0
    elif any(marker in normalized for marker in _PROTOCOL_MARKERS):
        domain = AssumptionDomain.PROTOCOL
        source = "protocol_marker"
        confidence = 1.0
    elif any(marker in normalized for marker in _PROCESS_MARKERS):
        domain = AssumptionDomain.PROCESS
        source = "process_marker"
        confidence = 1.0
    elif any(marker in normalized for marker in _TOOL_MARKERS):
        domain = AssumptionDomain.TOOL
        source = "tool_marker"
        confidence = 1.0
    else:
        domain = AssumptionDomain.MATHEMATICAL
        source = "mathematical_default"
        confidence = 0.8
    return AssumptionDomainRecord(
        assumption_key=normalized,
        domain=domain,
        inferred_from=source,
        confidence=confidence,
    )


def classify_obligation_domain(
    obligation: ProofObligation,
    *,
    source_kind: str | None = None,
) -> ObligationDomainRecord:
    normalized = normalize_text(obligation.normalized_statement).casefold()
    explicit_source = (source_kind or "").casefold()
    if obligation.kind == ObligationKind.MAIN_GOAL:
        domain = ObligationDomain.MATHEMATICAL
        source = "main_goal_kind"
        confidence = 1.0
    elif explicit_source in {"process", "checkpoint", "workflow"} or any(
        marker in normalized for marker in _PROCESS_MARKERS
    ):
        domain = ObligationDomain.PROCESS
        source = explicit_source or "process_marker"
        confidence = 1.0
    elif explicit_source in {"tool", "computation", "certificate"} or any(
        marker in normalized for marker in _TOOL_MARKERS
    ):
        domain = ObligationDomain.TOOL
        source = explicit_source or "tool_marker"
        confidence = 1.0
    elif explicit_source in {"verification", "review", "referee"} or any(
        marker in normalized for marker in _VERIFICATION_MARKERS
    ):
        domain = ObligationDomain.VERIFICATION
        source = explicit_source or "verification_marker"
        confidence = 1.0
    else:
        domain = ObligationDomain.MATHEMATICAL
        source = explicit_source or "mathematical_default"
        confidence = 0.8
    return ObligationDomainRecord(
        obligation_id=obligation.obligation_id,
        domain=domain,
        inferred_from=source,
        confidence=confidence,
    )
