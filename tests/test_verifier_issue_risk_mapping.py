from __future__ import annotations

from mathproofmesh.proof_control.inference_risk import InferenceRiskScanner
from mathproofmesh.proof_control.models import (
    InferenceRiskType,
    StructuredVerifierIssue,
    VerifierIssueCode,
)
from mathproofmesh.schemas import (
    FailureLevel,
    Severity,
    VerificationIssue,
    VerificationReport,
    VerificationStage,
    VerificationVerdict,
)

from v082_helpers import make_control_runtime


def _issue(code: VerifierIssueCode) -> StructuredVerifierIssue:
    return StructuredVerifierIssue(
        issue_id="issue-a",
        report_id="report-a",
        target_id="claim-a",
        step_id="step-a",
        code=code,
        premise_summary="A bounded relation was established on a restricted scope.",
        conclusion_summary="The unrestricted relation was asserted.",
        confidence=0.98,
    )


def test_verifier_issue_maps_to_inference_risk() -> None:
    risks = InferenceRiskScanner().map_verifier_issue(
        _issue(VerifierIssueCode.UNSUPPORTED_IMPLICATION),
        route_id="route-a",
    )

    assert len(risks) == 1
    assert risks[0].subject_id == "step-a"
    assert risks[0].status == "open"
    assert risks[0].confidence == 0.98


def test_wrong_direction_issue_classifies_plan_failure() -> None:
    risks = InferenceRiskScanner().map_verifier_issue(
        _issue(VerifierIssueCode.WRONG_DIRECTION),
        route_id="route-a",
    )

    assert risks[0].risk_type == InferenceRiskType.WRONG_DIRECTION
    assert risks[0].recommended_control_action == "rewrite_blueprint"


def test_unknown_scope_high_centrality_opens_ambiguous_risk() -> None:
    risks = InferenceRiskScanner().critical_step_semantic_scan(
        subject_id="claim-central",
        scope_known=False,
        centrality=0.95,
        referenced_by_count=4,
        preparing_fact_promotion=True,
        route_id="route-a",
    )

    assert len(risks) == 1
    assert risks[0].risk_type == InferenceRiskType.AMBIGUOUS_SEMANTIC_LEAP
    assert risks[0].status == "open"


def test_risk_blocks_fact_promotion() -> None:
    risk = InferenceRiskScanner().map_verifier_issue(
        _issue(VerifierIssueCode.UNSUPPORTED_IMPLICATION),
        route_id="route-a",
    )[0]

    assert risk.blocks_fact_promotion


def test_cleared_risk_allows_promotion() -> None:
    risk = InferenceRiskScanner().map_verifier_issue(
        _issue(VerifierIssueCode.PROPERTY_STRENGTHENING),
        route_id="route-a",
    )[0]

    assert risk.blocks_fact_promotion
    cleared = risk.model_copy(update={"status": "cleared"})
    assert not cleared.blocks_fact_promotion


def test_verification_report_issue_enters_control_state(tmp_path) -> None:
    *_runtime, control, _goal = make_control_runtime(tmp_path)
    report = VerificationReport(
        report_id="report-integrated",
        target_id="claim-integrated",
        target_type="claim",
        agent_id="verifier-a",
        stage=VerificationStage.DETAILED,
        verdict=VerificationVerdict.FAIL,
        issues=[
            VerificationIssue(
                issue_id="issue-integrated",
                phase="semantic",
                severity=Severity.ERROR,
                claim_id="claim-integrated",
                description="The implication is used in the wrong direction.",
                issue_code=VerifierIssueCode.WRONG_DIRECTION.value,
                premise_summary="The reverse implication was established.",
                conclusion_summary="The forward implication was asserted.",
            )
        ],
        failure_level=FailureLevel.PLAN,
        confidence=0.98,
        concise_feedback="Rewrite the implication in the proved direction.",
    )

    control.process_verification_report(report)

    risks = list(control.state.inference_risks.values())
    assert len(risks) == 1
    assert risks[0].risk_type == InferenceRiskType.WRONG_DIRECTION
    assert risks[0].source_issue_ids == ["issue-integrated"]
