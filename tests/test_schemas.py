from __future__ import annotations

import pytest
from pydantic import ValidationError

from mathproofmesh.schemas import ClaimCard, ProblemContract, UsageRecord, stable_hash


def test_usage_and_contract_hashes_are_inferred_without_recursion() -> None:
    usage = UsageRecord(input_tokens=7, output_tokens=11)
    assert usage.total_tokens == 18

    problem = ProblemContract(exact_statement="Prove x=x.", normalized_statement="Prove x=x.")
    assert problem.integrity_hash == stable_hash("Prove x=x.")


def test_claim_hash_is_content_addressed_and_tamper_evident() -> None:
    claim = ClaimCard(statement="A", conclusion="B")
    assert claim.content_hash == stable_hash(
        {"statement": "A", "assumptions": [], "conclusion": "B", "dependencies": []}
    )

    with pytest.raises(ValidationError):
        ClaimCard(statement="A", conclusion="B", content_hash="not-the-right-hash")
