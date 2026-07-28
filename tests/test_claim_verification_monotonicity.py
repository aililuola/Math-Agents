from __future__ import annotations

from mathproofmesh.memory import LemmaMemory
from mathproofmesh.proof_control.claim_lifecycle import ClaimLifecycleController
from mathproofmesh.proof_control.models import (
    ClaimVerificationLedgerEntry,
    ClaimVerificationState,
)
from mathproofmesh.schemas import (
    ClaimCard,
    ClaimStatus,
    VerificationIssue,
    VerificationReport,
    VerificationStage,
    VerificationVerdict,
)
from mathproofmesh.store import ArtifactStore


def test_incomplete_attempt_does_not_change_child_claim_state(tmp_path) -> None:
    store = ArtifactStore(tmp_path / "runs", "claim-lifecycle")
    memory = LemmaMemory(store)
    claim = ClaimCard(
        claim_id="claim-local",
        statement="Under hypothesis H, conclusion C follows.",
        assumptions=["H"],
        conclusion="C",
        source_attempt_id="attempt-partial",
        status=ClaimStatus.PROPOSED,
    )
    memory.add_many([claim])
    report = VerificationReport(
        report_id="report-attempt-partial",
        target_id="attempt-partial",
        target_type="attempt",
        agent_id="independent-reviewer",
        stage=VerificationStage.DETAILED,
        verdict=VerificationVerdict.FAIL,
        issues=[
            VerificationIssue(
                phase="completeness",
                severity="error",
                step_id="unresolved-final-step",
                description="The final implication remains unproved.",
            )
        ],
        first_error_step="unresolved-final-step",
        confidence=1.0,
        concise_feedback="The whole attempt is incomplete.",
    )

    memory.mark_attempt_verified("attempt-partial", report)

    assert claim.status == ClaimStatus.PROPOSED


def _claim_report(
    claim_id: str,
    *,
    verdict: VerificationVerdict,
    report_id: str,
    counterexample: str | None = None,
) -> VerificationReport:
    return VerificationReport(
        report_id=report_id,
        target_id=claim_id,
        target_type="claim",
        agent_id="independent-reviewer",
        stage=VerificationStage.LEMMA,
        verdict=verdict,
        issues=(
            [
                VerificationIssue(
                    phase="claim",
                    severity="error",
                    claim_id=claim_id,
                    description="The exact Claim fails.",
                    counterexample=counterexample,
                )
            ]
            if verdict == VerificationVerdict.FAIL
            else []
        ),
        confidence=1.0,
        concise_feedback="Independent Claim review.",
    )


def test_claim_level_counterexample_invalidates_claim() -> None:
    claim = ClaimCard(
        claim_id="claim-refuted",
        statement="Every object in the domain has property P.",
        conclusion="Property P is universal.",
        status=ClaimStatus.VERIFIED,
        source_agent_id="author-a",
        source_attempt_id="attempt-a",
    )
    controller = ClaimLifecycleController({claim.claim_id: claim})

    entry = controller.apply_claim_report(
        _claim_report(
            claim.claim_id,
            verdict=VerificationVerdict.FAIL,
            report_id="claim-report-fail",
            counterexample="A typed domain object without property P.",
        )
    )

    assert entry is not None
    assert entry.state == ClaimVerificationState.REJECTED
    assert entry.invalidation_reason == "claim_level_fail"
    assert claim.status == ClaimStatus.REJECTED


def test_dependency_invalidation_demotes_fact() -> None:
    base = ClaimCard(
        claim_id="claim-base",
        statement="Base relation B holds.",
        conclusion="B",
        source_agent_id="author-a",
        source_attempt_id="attempt-a",
    )
    derived = ClaimCard(
        claim_id="claim-derived",
        statement="B implies D.",
        conclusion="D",
        dependencies=[base.claim_id],
        source_agent_id="author-a",
        source_attempt_id="attempt-a",
    )
    controller = ClaimLifecycleController(
        {base.claim_id: base, derived.claim_id: derived}
    )
    for claim in (base, derived):
        controller.apply_claim_report(
            _claim_report(
                claim.claim_id,
                verdict=VerificationVerdict.PASS,
                report_id=f"pass-{claim.claim_id}",
            )
        )
        controller.promote_fact_candidate(
            claim.claim_id,
            referee_review_id=f"referee-{claim.claim_id}",
        )
        controller.mark_fact(
            claim.claim_id,
            evidence_ids=[f"fact-message-{claim.claim_id}"],
        )

    controller.invalidate_claim(
        base.claim_id,
        reason="exact_counterexample",
        evidence_ids=["counterexample-a"],
    )
    invalidated = controller.invalidate_dependents(
        base.claim_id,
        evidence_ids=["counterexample-a"],
    )

    assert invalidated == [derived.claim_id]
    assert (
        controller.ledger[derived.claim_id].state == ClaimVerificationState.INVALIDATED
    )
    assert derived.status == ClaimStatus.UNCERTAIN


def test_claim_lifecycle_resume_is_monotonic() -> None:
    claim = ClaimCard(
        claim_id="claim-resume",
        statement="A reusable local relation holds.",
        conclusion="Reusable relation",
        source_agent_id="author-a",
        source_attempt_id="attempt-resume",
    )
    original = ClaimLifecycleController({claim.claim_id: claim})
    original.apply_claim_report(
        _claim_report(
            claim.claim_id,
            verdict=VerificationVerdict.PASS,
            report_id="independent-pass",
        )
    )
    restored_ledger = {
        claim_id: ClaimVerificationLedgerEntry.model_validate(payload)
        for claim_id, payload in original.export_state().items()
    }
    restored = ClaimLifecycleController(
        {claim.claim_id: claim},
        restored_ledger,
    )
    incomplete_attempt = VerificationReport(
        report_id="attempt-resume-fail",
        target_id="attempt-resume",
        target_type="attempt",
        agent_id="attempt-reviewer",
        stage=VerificationStage.DETAILED,
        verdict=VerificationVerdict.FAIL,
        issues=[
            VerificationIssue(
                phase="completeness",
                severity="error",
                step_id="remaining-step",
                description="A later route step remains unproved.",
            )
        ],
        confidence=1.0,
        concise_feedback="The route remains incomplete.",
    )

    restored.apply_attempt_report("attempt-resume", incomplete_attempt)

    assert (
        restored.ledger[claim.claim_id].state
        == ClaimVerificationState.INDEPENDENTLY_VERIFIED
    )
    assert restored.ledger[claim.claim_id].source_attempt_incomplete
