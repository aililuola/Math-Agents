from __future__ import annotations

from mathproofmesh.memory import LemmaMemory
from mathproofmesh.schemas import (
    ClaimCard,
    ClaimStatus,
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
