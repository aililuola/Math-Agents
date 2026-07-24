from __future__ import annotations

import pytest
from pydantic import ValidationError

from mathproofmesh.schemas import (
    CandidateConjecture,
    ClaimCard,
    ProblemContract,
    UsageRecord,
    stable_hash,
)


def test_usage_and_contract_hashes_are_inferred_without_recursion() -> None:
    usage = UsageRecord(input_tokens=7, output_tokens=11)
    assert usage.total_tokens == 18
    usage.input_tokens += 3
    usage.output_tokens += 2
    usage.total_tokens += 23
    assert usage.total_tokens == 23
    assert (
        UsageRecord(input_tokens=7, output_tokens=11, total_tokens=99).total_tokens
        == 18
    )

    problem = ProblemContract(
        exact_statement="Prove x=x.", normalized_statement="Prove x=x."
    )
    assert problem.integrity_hash == stable_hash("Prove x=x.")


def test_claim_hash_is_content_addressed_and_tamper_evident() -> None:
    claim = ClaimCard(statement="A", conclusion="B")
    assert claim.content_hash == stable_hash(
        {"statement": "A", "assumptions": [], "conclusion": "B", "dependencies": []}
    )

    with pytest.raises(ValidationError):
        ClaimCard(statement="A", conclusion="B", content_hash="not-the-right-hash")


def test_candidate_conjecture_requires_scope_and_separate_proof_obligation() -> None:
    candidate = CandidateConjecture(
        statement="a_n = 2n + 4",
        rationale="The exact finite prefix increases by two.",
        supporting_experiment_ids=["exp-prefix"],
        scope_limitations=["A finite prefix does not prove a universal recurrence."],
        proof_obligations=["Prove the recurrence from the least-candidate rule."],
    )
    assert candidate.status == "candidate"
    assert candidate.content_hash

    with pytest.raises(ValidationError, match="scope"):
        CandidateConjecture(
            statement="a_n = 2n + 4",
            rationale="The exact finite prefix increases by two.",
            supporting_experiment_ids=["exp-prefix"],
            scope_limitations=[],
            proof_obligations=["Prove the recurrence."],
        )
