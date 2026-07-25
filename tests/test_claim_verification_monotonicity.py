from __future__ import annotations

from mathproofmesh.memory import LemmaMemory
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

