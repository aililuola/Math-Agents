from __future__ import annotations

from mathproofmesh.proof_control.near_miss import NearMissLedger
from mathproofmesh.schemas import (
    FailureLevel,
    Severity,
    VerificationIssue,
    VerificationReport,
    VerificationStage,
    VerificationVerdict,
)


def _report(*, integrity_ok: bool = True) -> VerificationReport:
    return VerificationReport(
        report_id="report-a",
        target_id="delta-a",
        target_type="proof_delta",
        agent_id="referee",
        stage=VerificationStage.DETAILED,
        problem_integrity_ok=integrity_ok,
        verdict=VerificationVerdict.FAIL,
        first_error_step="step-3",
        issues=[
            VerificationIssue(
                phase="admissibility",
                severity=Severity.ERROR,
                step_id="step-3",
                description="the concrete candidate violates the boundary condition",
                repair_hint="replace_realizer_preserve_structure",
            )
        ],
        failure_level=FailureLevel.EXECUTION,
        confidence=0.9,
        concise_feedback="The representation is useful but this candidate is inadmissible.",
    )


def test_deterministic_near_miss_preserves_salvageable_structure() -> None:
    ledger = NearMissLedger()
    record = ledger.extract_deterministic(
        _report(),
        route_id="route-a",
        target_obligation_id="main",
        abstract_idea="descend through equivalence classes",
        concrete_candidate="choose an arbitrary representative",
        preserved_properties=["the quotient invariant is preserved"],
        salvageable_components=["equivalence-class descent"],
        suggested_repair_operators=["replace_realizer_preserve_structure"],
    )

    assert record is not None
    ledger.add(record)
    assert record.first_failure_type == "admissibility"
    assert "equivalence-class descent" in record.salvageable_components
    assert "replace_realizer_preserve_structure" in record.suggested_repair_operators
    assert ledger.relevant_for_route("route-a", target_obligation_ids=["main"]) == [
        record
    ]

    ledger.mark_repaired(record.near_miss_id)
    assert ledger.relevant_for_route("route-a") == []


def test_problem_integrity_failure_is_not_a_near_miss() -> None:
    record = NearMissLedger().extract_deterministic(
        _report(integrity_ok=False),
        route_id="route-a",
        abstract_idea="irrelevant",
        concrete_candidate="irrelevant",
        preserved_properties=["some local step"],
    )

    assert record is None
