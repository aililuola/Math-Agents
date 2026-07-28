from __future__ import annotations

from mathproofmesh.memory import LemmaMemory
from mathproofmesh.schemas import (
    ClaimCard,
    ClaimStatus,
    ProofStep,
    Severity,
    VerificationIssue,
    VerificationReport,
    VerificationStage,
    VerificationVerdict,
)


def _pass_report(target_id: str) -> VerificationReport:
    return VerificationReport(
        target_id=target_id,
        target_type="claim",
        agent_id="verifier",
        stage=VerificationStage.LEMMA,
        verdict=VerificationVerdict.PASS,
        confidence=0.95,
        concise_feedback="checked",
    )


def test_missing_dependency_never_silently_becomes_verified(artifact_store) -> None:
    memory = LemmaMemory(artifact_store)
    claim = ClaimCard(
        claim_id="claim-a",
        statement="A implies C",
        conclusion="C",
        dependencies=["claim-missing"],
        status=ClaimStatus.PROPOSED,
    )
    memory.add_many([claim])
    memory.apply_claim_report(_pass_report("claim-a"))

    assert claim.status == ClaimStatus.UNCERTAIN
    assert not memory.verified()
    assert "missing dependency: claim-missing" in claim.scope_limitations


def test_verified_dependency_chain_is_reusable(artifact_store) -> None:
    memory = LemmaMemory(artifact_store)
    base = ClaimCard(claim_id="base", statement="A", conclusion="A")
    derived = ClaimCard(
        claim_id="derived",
        statement="A implies B",
        conclusion="B",
        dependencies=["base"],
    )
    memory.add_many([base, derived])
    memory.apply_claim_report(_pass_report("base"))
    memory.apply_claim_report(_pass_report("derived"))

    assert {c.claim_id for c in memory.verified()} == {"base", "derived"}


def test_attempt_failure_preserves_independently_verified_local_claim(
    artifact_store,
) -> None:
    memory = LemmaMemory(artifact_store)
    claim = ClaimCard(
        claim_id="verified-local-lemma",
        statement="If a_n is a power of p, then p divides every later term.",
        conclusion="p divides every later term",
        proof_steps=[
            ProofStep(
                step_id="local-lemma-step",
                statement="Apply the pairwise gcd condition to a_n and a_m.",
                justification="The only prime divisor of a_n is p.",
            )
        ],
        status=ClaimStatus.VERIFIED,
        source_attempt_id="partial-attempt",
        source_delta_id="accepted-delta",
        verification_confidence=0.97,
    )
    memory.add_many([claim])
    report = VerificationReport(
        target_id="partial-attempt",
        target_type="attempt",
        agent_id="final-verifier",
        stage=VerificationStage.DETAILED,
        verdict=VerificationVerdict.FAIL,
        first_error_step="later-unfinished-step",
        issues=[
            VerificationIssue(
                phase="completeness",
                severity=Severity.ERROR,
                step_id="later-unfinished-step",
                description="The route has not completed the remaining subgoal.",
            )
        ],
        confidence=1.0,
        concise_feedback="The overall proof is incomplete.",
    )

    memory.mark_attempt_verified("partial-attempt", report)

    assert claim.status == ClaimStatus.VERIFIED
    assert claim.verification_confidence == 0.97
    assert {item.claim_id for item in memory.verified()} == {"verified-local-lemma"}


def test_attempt_failure_only_rejects_explicitly_targeted_child_claim(
    artifact_store,
) -> None:
    memory = LemmaMemory(artifact_store)
    rejected = ClaimCard(
        claim_id="bad-child",
        statement="A false child claim.",
        conclusion="False",
        status=ClaimStatus.VERIFIED,
        source_attempt_id="attempt",
        verification_confidence=0.9,
    )
    unresolved = ClaimCard(
        claim_id="unresolved-child",
        statement="An unrelated candidate claim.",
        conclusion="Unknown",
        status=ClaimStatus.PROPOSED,
        source_attempt_id="attempt",
    )
    memory.add_many([rejected, unresolved])
    report = VerificationReport(
        target_id="attempt",
        target_type="attempt",
        agent_id="verifier",
        stage=VerificationStage.DETAILED,
        verdict=VerificationVerdict.FAIL,
        issues=[
            VerificationIssue(
                phase="claim",
                severity=Severity.ERROR,
                claim_id="bad-child",
                description="This exact child claim has a counterexample.",
                counterexample="A concrete counterexample.",
            )
        ],
        confidence=0.99,
        concise_feedback="One child claim is false; the other was not audited.",
    )

    memory.mark_attempt_verified("attempt", report)

    assert rejected.status == ClaimStatus.REJECTED
    assert unresolved.status == ClaimStatus.PROPOSED
    assert memory.rejected() == [rejected]
