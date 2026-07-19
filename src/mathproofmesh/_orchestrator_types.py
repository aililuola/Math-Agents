from __future__ import annotations

from dataclasses import dataclass

from .schemas import (
    FinalProof,
    MetaReview,
    ProofAttempt,
    StrategyCard,
    TriageResult,
    VerificationReport,
)


@dataclass(slots=True)
class VerificationBundle:
    aggregate: VerificationReport
    reports: list[VerificationReport]


@dataclass(slots=True)
class SolveState:
    triage: TriageResult | None
    strategies: list[StrategyCard]
    attempts: list[ProofAttempt]
    reports: list[VerificationReport]
    aggregate_reports: dict[str, VerificationReport]
    meta_reviews: list[MetaReview]
    final_proof: FinalProof | None = None
    final_verification: VerificationReport | None = None
    budget_exhausted: bool = False
