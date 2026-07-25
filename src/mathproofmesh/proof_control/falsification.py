from __future__ import annotations

from collections.abc import Collection
from dataclasses import dataclass
from typing import Protocol

from ..config import SystemConfig
from ..schemas import (
    ComputationMethod,
    EvidenceType,
    ExperimentOutcome,
    ExperimentResult,
    ExperimentSpec,
    MemoryTier,
    MessageType,
)


class FastLaneContext(Protocol):
    proof_control_fast_lane: bool
    target_obligation_id: str | None
    target_claim_id: str | None
    fast_lane_tasks_this_round: int
    requested_runtime_seconds: float | None
    requested_memory_mb: int | None


@dataclass(frozen=True, slots=True)
class FastLaneEligibility:
    eligible: bool
    reason: str


@dataclass(frozen=True, slots=True)
class FalsificationDisposition:
    event_type: str
    memory_tier: MemoryTier | None
    message_type: MessageType | None
    evidence_type: EvidenceType | None
    conclusive_refutation: bool
    claim_status_changed: bool
    reason: str


def evaluate_fast_lane_eligibility(
    spec: ExperimentSpec,
    context: FastLaneContext,
    config: SystemConfig,
    *,
    registered_methods: Collection[ComputationMethod],
) -> FastLaneEligibility:
    """Check the narrow proof-control lane without weakening broker hard limits."""

    control = config.topology.proof_control
    lane = control.falsification_fast_lane
    checks: tuple[tuple[bool, str], ...] = (
        (
            control.enabled and control.mode == "active",
            "proof control is not active",
        ),
        (lane.enabled, "the proof-control falsification lane is disabled"),
        (
            context.proof_control_fast_lane,
            "the request was not explicitly marked for the proof-control lane",
        ),
        (
            spec.purpose in lane.allowed_purposes,
            "the computation purpose is not eligible for falsification fast lane",
        ),
        (
            spec.method in registered_methods,
            "the method is not a registered typed computation",
        ),
        (
            spec.method != ComputationMethod.SANDBOXED_PYTHON,
            "sandboxed Python is forbidden in the falsification fast lane",
        ),
        (
            not lane.exact_arithmetic_only or spec.exact_arithmetic,
            "the falsification fast lane requires exact arithmetic",
        ),
        (not spec.broad_search, "broad search cannot use the falsification fast lane"),
        (
            spec.max_cases <= lane.max_cases,
            "the request exceeds the proof-control case limit",
        ),
        (
            context.fast_lane_tasks_this_round < lane.max_tasks_per_round,
            "the proof-control per-round fast-lane limit is exhausted",
        ),
        (
            context.requested_runtime_seconds is None
            or context.requested_runtime_seconds <= lane.max_runtime_seconds,
            "the request exceeds the proof-control runtime limit",
        ),
        (
            context.requested_memory_mb is None
            or context.requested_memory_mb <= lane.max_memory_mb,
            "the request exceeds the proof-control memory limit",
        ),
        (
            bool(context.target_obligation_id or context.target_claim_id),
            "the request does not identify a claim or obligation",
        ),
        (
            len(spec.decision_if_refuted.strip()) >= 12,
            "the request does not state a concrete action if refuted",
        ),
        (
            not lane.auto_fact_promotion,
            "fast-lane results may not be promoted automatically to Fact",
        ),
    )
    for passed, reason in checks:
        if not passed:
            return FastLaneEligibility(False, reason)
    return FastLaneEligibility(
        True,
        (
            "Admitted as an exact, bounded falsification task. A negative search "
            "remains bounded evidence and never becomes a Fact automatically."
        ),
    )


def classify_falsification_result(
    result: ExperimentResult,
) -> FalsificationDisposition:
    """Describe the only permitted side effects of a fast-lane result."""

    if (
        result.outcome == ExperimentOutcome.COUNTEREXAMPLE_FOUND
        and result.counterexample is not None
        and result.exact_arithmetic
        and result.independently_verified
    ):
        return FalsificationDisposition(
            event_type="falsification_fast_lane_counterexample",
            memory_tier=MemoryTier.NEGATIVE,
            message_type=MessageType.COUNTEREXAMPLE,
            evidence_type=EvidenceType.COUNTEREXAMPLE,
            conclusive_refutation=True,
            claim_status_changed=False,
            reason=(
                "An exact independently reproduced counterexample is eligible for "
                "NegativeMemory and a Counterexample message after normal broker review."
            ),
        )
    if result.outcome in {
        ExperimentOutcome.NOT_REFUTED,
        ExperimentOutcome.CERTIFIED,
    }:
        return FalsificationDisposition(
            event_type="falsification_fast_lane_inconclusive",
            memory_tier=MemoryTier.INSIGHT,
            message_type=MessageType.COMPUTATION_CERTIFICATE,
            evidence_type=EvidenceType.BOUNDED_EXPERIMENT,
            conclusive_refutation=False,
            claim_status_changed=False,
            reason=(
                "No exact counterexample was found in the declared bounded scope; "
                "the result remains Insight and does not verify the target claim."
            ),
        )
    return FalsificationDisposition(
        event_type="falsification_fast_lane_inconclusive",
        memory_tier=None,
        message_type=None,
        evidence_type=None,
        conclusive_refutation=False,
        claim_status_changed=False,
        reason="The tool was inconclusive or failed, so no claim or memory state changes.",
    )
