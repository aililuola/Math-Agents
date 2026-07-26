from __future__ import annotations

from collections.abc import Mapping
from typing import Any

from ..schemas import (
    ClaimCard,
    ClaimStatus,
    VerificationReport,
    VerificationVerdict,
)
from .models import (
    ClaimRefereeDisposition,
    ClaimRefereeRecord,
    ClaimVerificationLedgerEntry,
    ClaimVerificationState,
    DependencyRef,
)


class ClaimLifecycleController:
    """Own all Claim-level verification transitions independently of attempts."""

    INVALIDATION_REASONS = {
        "claim_level_fail",
        "exact_counterexample",
        "dependency_invalidated",
        "scope_mismatch",
        "quantifier_mismatch",
        "content_hash_mismatch",
    }

    def __init__(
        self,
        claims: dict[str, ClaimCard],
        ledger: dict[str, ClaimVerificationLedgerEntry] | None = None,
    ) -> None:
        self.claims = claims
        self.ledger = ledger if ledger is not None else {}
        for claim in claims.values():
            self.register_claim(claim)

    def register_claim(self, claim: ClaimCard) -> ClaimVerificationLedgerEntry:
        self.claims.setdefault(claim.claim_id, claim)
        existing = self.ledger.get(claim.claim_id)
        if existing is not None:
            if existing.source_delta_id is None and claim.source_delta_id is not None:
                existing.source_delta_id = claim.source_delta_id
            existing.dependency_ids = list(claim.dependencies)
            existing.dependency_refs = self._dependency_refs(claim.dependency_refs)
            return existing
        initial_state = (
            ClaimVerificationState.REJECTED
            if claim.status == ClaimStatus.REJECTED
            else ClaimVerificationState.LOCALLY_VERIFIED
            if claim.status == ClaimStatus.VERIFIED
            else ClaimVerificationState.PROPOSED
        )
        entry = ClaimVerificationLedgerEntry(
            claim_id=claim.claim_id,
            source_attempt_id=claim.source_attempt_id or "",
            source_delta_id=claim.source_delta_id,
            state=initial_state,
            dependency_ids=list(claim.dependencies),
            dependency_refs=self._dependency_refs(claim.dependency_refs),
            transition_history=[
                {
                    "from": None,
                    "to": initial_state.value,
                    "reason": "claim_registered",
                }
            ],
        )
        self.ledger[claim.claim_id] = entry
        return entry

    def apply_referee_record(
        self,
        record: ClaimRefereeRecord,
    ) -> ClaimVerificationLedgerEntry:
        entry = self._entry(record.claim_id)
        already_recorded = record.review_id in entry.referee_review_ids
        if not already_recorded:
            entry.referee_review_ids.append(record.review_id)
        if record.referee_agent_id not in entry.referee_agent_ids:
            entry.referee_agent_ids.append(record.referee_agent_id)
        if entry.source_delta_id is None:
            entry.source_delta_id = record.source_delta_id
        if not entry.source_attempt_id:
            entry.source_attempt_id = record.source_attempt_id

        if record.disposition == ClaimRefereeDisposition.REJECT:
            if entry.state not in {
                ClaimVerificationState.REJECTED,
                ClaimVerificationState.INVALIDATED,
            }:
                return self.invalidate_claim(
                    record.claim_id,
                    reason="claim_level_fail",
                    evidence_ids=[record.review_id],
                )
            return entry

        validity_flags = (
            record.dependencies_valid,
            record.scope_valid,
            record.quantifiers_valid,
            record.evidence_type_valid,
        )
        if record.disposition == ClaimRefereeDisposition.ACCEPT and all(validity_flags):
            if entry.state in {
                ClaimVerificationState.INDEPENDENTLY_VERIFIED,
                ClaimVerificationState.REFEREE_ACCEPTED,
                ClaimVerificationState.FACT_CANDIDATE,
                ClaimVerificationState.FACT,
            }:
                if not already_recorded or entry.state == (
                    ClaimVerificationState.INDEPENDENTLY_VERIFIED
                ):
                    self._advance(
                        entry,
                        ClaimVerificationState.REFEREE_ACCEPTED,
                        reason="claim_referee_accepted",
                        report_id=record.review_id,
                    )
                return entry
            if not already_recorded:
                self._record_same_state(
                    entry,
                    reason="claim_referee_acceptance_waiting_for_independent_review",
                    report_id=record.review_id,
                )
            return entry

        if not already_recorded:
            reason = (
                "claim_referee_validity_gate_failed"
                if record.disposition == ClaimRefereeDisposition.ACCEPT
                else f"claim_referee_{record.disposition.value}"
            )
            self._record_same_state(
                entry,
                reason=reason,
                report_id=record.review_id,
            )
        return entry

    def apply_claim_report(
        self, report: VerificationReport
    ) -> ClaimVerificationLedgerEntry | None:
        claim = self.claims.get(report.target_id)
        if claim is None:
            return None
        entry = self.register_claim(claim)
        if report.verdict == VerificationVerdict.PASS:
            independent = (
                claim.source_agent_id is None
                or report.agent_id != claim.source_agent_id
            )
            if independent:
                if report.report_id not in entry.independent_report_ids:
                    entry.independent_report_ids.append(report.report_id)
                self._advance(
                    entry,
                    ClaimVerificationState.LOCALLY_VERIFIED,
                    reason="claim_report_passed",
                    report_id=report.report_id,
                )
                self._advance(
                    entry,
                    ClaimVerificationState.INDEPENDENTLY_VERIFIED,
                    reason="independent_claim_report_passed",
                    report_id=report.report_id,
                )
            else:
                if report.report_id not in entry.local_report_ids:
                    entry.local_report_ids.append(report.report_id)
                self._advance(
                    entry,
                    ClaimVerificationState.LOCALLY_VERIFIED,
                    reason="local_claim_report_passed",
                    report_id=report.report_id,
                )
            claim.status = ClaimStatus.VERIFIED
            claim.verification_confidence = report.confidence
        elif report.verdict == VerificationVerdict.FAIL:
            self.invalidate_claim(
                claim.claim_id,
                reason="claim_level_fail",
                evidence_ids=[report.report_id],
            )
            claim.verification_confidence = report.confidence
        else:
            self._record_same_state(
                entry,
                reason="claim_report_inconclusive",
                report_id=report.report_id,
            )
            if claim.status != ClaimStatus.VERIFIED:
                claim.status = ClaimStatus.UNCERTAIN
                claim.verification_confidence = report.confidence
        return entry

    def apply_attempt_report(
        self,
        attempt_id: str,
        report: VerificationReport,
    ) -> list[ClaimCard]:
        related = [
            claim
            for claim in self.claims.values()
            if claim.source_attempt_id == attempt_id
        ]
        explicit_counterexamples = {
            issue.claim_id
            for issue in report.issues
            if issue.claim_id is not None and bool(issue.counterexample)
        }
        for claim in related:
            entry = self.register_claim(claim)
            if report.verdict != VerificationVerdict.PASS:
                entry.source_attempt_incomplete = True
            self._record_same_state(
                entry,
                reason=(
                    "source_attempt_incomplete"
                    if report.verdict != VerificationVerdict.PASS
                    else "source_attempt_reviewed"
                ),
                report_id=report.report_id,
            )
            if report.verdict == VerificationVerdict.PASS:
                self.record_checkpoint_verification(
                    claim.claim_id,
                    report_ids=[report.report_id],
                    confidence=report.confidence,
                    independent=False,
                )
            if claim.claim_id in explicit_counterexamples:
                self.invalidate_claim(
                    claim.claim_id,
                    reason="exact_counterexample",
                    evidence_ids=[report.report_id],
                )
        return related

    def promote_fact_candidate(
        self,
        claim_id: str,
        *,
        referee_review_id: str | None = None,
    ) -> ClaimVerificationLedgerEntry:
        entry = self._entry(claim_id)
        if entry.state in {
            ClaimVerificationState.INVALIDATED,
            ClaimVerificationState.REJECTED,
        }:
            raise ValueError("claim was rejected by a referee or invalidated")
        if entry.state not in {
            ClaimVerificationState.INDEPENDENTLY_VERIFIED,
            ClaimVerificationState.REFEREE_ACCEPTED,
            ClaimVerificationState.FACT_CANDIDATE,
            ClaimVerificationState.FACT,
        }:
            raise ValueError("claim lacks independent verification")
        if referee_review_id is not None:
            if referee_review_id not in entry.referee_review_ids:
                entry.referee_review_ids.append(referee_review_id)
            self._advance(
                entry,
                ClaimVerificationState.REFEREE_ACCEPTED,
                reason="referee_accepted",
                report_id=referee_review_id,
            )
        if not entry.referee_review_ids:
            raise ValueError("claim lacks a recorded referee review")
        if entry.state == ClaimVerificationState.INDEPENDENTLY_VERIFIED:
            raise ValueError("claim lacks referee acceptance")
        self._advance(
            entry,
            ClaimVerificationState.FACT_CANDIDATE,
            reason="fact_candidate_admitted",
        )
        return entry

    def record_checkpoint_verification(
        self,
        claim_id: str,
        *,
        report_ids: list[str],
        confidence: float,
        independent: bool,
    ) -> ClaimVerificationLedgerEntry:
        claim = self.claims[claim_id]
        entry = self.register_claim(claim)
        unique_ids = sorted(set(report_ids))
        if independent:
            entry.independent_report_ids = sorted(
                set([*entry.independent_report_ids, *unique_ids])
            )
            self._advance(
                entry,
                ClaimVerificationState.LOCALLY_VERIFIED,
                reason="checkpoint_claim_verified",
            )
            self._advance(
                entry,
                ClaimVerificationState.INDEPENDENTLY_VERIFIED,
                reason="independent_checkpoint_claim_verified",
                evidence_ids=unique_ids,
            )
        else:
            entry.local_report_ids = sorted(set([*entry.local_report_ids, *unique_ids]))
            self._advance(
                entry,
                ClaimVerificationState.LOCALLY_VERIFIED,
                reason="local_checkpoint_claim_verified",
                evidence_ids=unique_ids,
            )
        claim.status = ClaimStatus.VERIFIED
        claim.verification_confidence = confidence
        return entry

    def mark_fact(
        self,
        claim_id: str,
        *,
        evidence_ids: list[str],
    ) -> ClaimVerificationLedgerEntry:
        entry = self._entry(claim_id)
        if entry.state != ClaimVerificationState.FACT_CANDIDATE:
            raise ValueError("only a fact candidate may become a Fact")
        self._advance(
            entry,
            ClaimVerificationState.FACT,
            reason="broker_admitted_fact",
            evidence_ids=evidence_ids,
        )
        return entry

    def invalidate_claim(
        self,
        claim_id: str,
        *,
        reason: str,
        evidence_ids: list[str] | None = None,
    ) -> ClaimVerificationLedgerEntry:
        if reason not in self.INVALIDATION_REASONS:
            raise ValueError(f"unsupported claim invalidation reason: {reason}")
        claim = self.claims[claim_id]
        entry = self.register_claim(claim)
        target = (
            ClaimVerificationState.REJECTED
            if reason in {"claim_level_fail", "exact_counterexample"}
            else ClaimVerificationState.INVALIDATED
        )
        entry.invalidation_reason = reason
        entry.invalidating_evidence_ids = sorted(
            set([*entry.invalidating_evidence_ids, *(evidence_ids or [])])
        )
        self._transition(
            entry,
            target,
            reason=reason,
            evidence_ids=evidence_ids or [],
        )
        claim.status = (
            ClaimStatus.REJECTED
            if target == ClaimVerificationState.REJECTED
            else ClaimStatus.UNCERTAIN
        )
        return entry

    def invalidate_dependents(
        self,
        invalid_claim_id: str,
        *,
        evidence_ids: list[str] | None = None,
    ) -> list[str]:
        invalidated: list[str] = []
        pending = [invalid_claim_id]
        seen: set[str] = set()
        while pending:
            dependency_id = pending.pop()
            if dependency_id in seen:
                continue
            seen.add(dependency_id)
            for claim in self.claims.values():
                if dependency_id not in claim.dependencies:
                    continue
                self.invalidate_claim(
                    claim.claim_id,
                    reason="dependency_invalidated",
                    evidence_ids=evidence_ids or [invalid_claim_id],
                )
                invalidated.append(claim.claim_id)
                pending.append(claim.claim_id)
        return invalidated

    def export_state(self) -> dict[str, Any]:
        return {
            claim_id: self.ledger[claim_id].model_dump(mode="json")
            for claim_id in sorted(self.ledger)
        }

    def merge_ledger(
        self,
        ledger: Mapping[str, ClaimVerificationLedgerEntry],
    ) -> None:
        for claim_id, entry in ledger.items():
            self.ledger[claim_id] = entry
        for claim in self.claims.values():
            self.register_claim(claim)

    @staticmethod
    def _dependency_refs(values: list[Any]) -> list[DependencyRef]:
        refs: list[DependencyRef] = []
        for value in values:
            refs.append(
                value
                if isinstance(value, DependencyRef)
                else DependencyRef.model_validate(value)
            )
        return refs

    def _entry(self, claim_id: str) -> ClaimVerificationLedgerEntry:
        try:
            claim = self.claims[claim_id]
        except KeyError as exc:
            raise KeyError(f"unknown claim: {claim_id}") from exc
        return self.register_claim(claim)

    def _advance(
        self,
        entry: ClaimVerificationLedgerEntry,
        target: ClaimVerificationState,
        *,
        reason: str,
        report_id: str | None = None,
        evidence_ids: list[str] | None = None,
    ) -> None:
        order = {
            ClaimVerificationState.PROPOSED: 0,
            ClaimVerificationState.LOCALLY_VERIFIED: 1,
            ClaimVerificationState.INDEPENDENTLY_VERIFIED: 2,
            ClaimVerificationState.REFEREE_ACCEPTED: 3,
            ClaimVerificationState.FACT_CANDIDATE: 4,
            ClaimVerificationState.FACT: 5,
        }
        if entry.state in {
            ClaimVerificationState.INVALIDATED,
            ClaimVerificationState.REJECTED,
        }:
            raise ValueError("an invalidated or rejected claim cannot be promoted")
        if order[target] <= order[entry.state]:
            self._record_same_state(entry, reason=reason, report_id=report_id)
            return
        self._transition(
            entry,
            target,
            reason=reason,
            report_id=report_id,
            evidence_ids=evidence_ids or [],
        )

    @staticmethod
    def _transition(
        entry: ClaimVerificationLedgerEntry,
        target: ClaimVerificationState,
        *,
        reason: str,
        report_id: str | None = None,
        evidence_ids: list[str] | None = None,
    ) -> None:
        prior = entry.state
        entry.state = target
        event: dict[str, Any] = {
            "from": prior.value,
            "to": target.value,
            "reason": reason,
        }
        if report_id is not None:
            event["report_id"] = report_id
        if evidence_ids:
            event["evidence_ids"] = sorted(set(evidence_ids))
        entry.transition_history.append(event)

    @staticmethod
    def _record_same_state(
        entry: ClaimVerificationLedgerEntry,
        *,
        reason: str,
        report_id: str | None = None,
    ) -> None:
        event: dict[str, Any] = {
            "from": entry.state.value,
            "to": entry.state.value,
            "reason": reason,
        }
        if report_id is not None:
            event["report_id"] = report_id
        entry.transition_history.append(event)
