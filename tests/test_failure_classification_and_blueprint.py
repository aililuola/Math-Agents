from __future__ import annotations

from mathproofmesh.proof_control.failure_control import (
    BlueprintRewriter,
    FailureClassifier,
)
from mathproofmesh.proof_control.models import (
    ClaimGoalLink,
    GoalRelation,
    ProofFailureClass,
    ScopeRelation,
)
from mathproofmesh.schemas import (
    ActionKind,
    FailureLevel,
    RouteDescriptor,
    RouteStatus,
    Severity,
    VerificationIssue,
    VerificationReport,
    VerificationStage,
    VerificationVerdict,
)


def _report(
    failure_level: FailureLevel = FailureLevel.EXECUTION,
    *,
    integrity_ok: bool = True,
) -> VerificationReport:
    return VerificationReport(
        report_id=f"report-{failure_level.value}",
        target_id="attempt-a",
        target_type="attempt",
        agent_id="referee",
        stage=VerificationStage.DETAILED,
        problem_integrity_ok=integrity_ok,
        verdict=VerificationVerdict.FAIL,
        first_error_step="step-2",
        issues=[
            VerificationIssue(
                phase="local calculation",
                severity=Severity.ERROR,
                step_id="step-2",
                description="a sign was copied incorrectly",
                repair_hint="recompute this line",
            )
        ],
        failure_level=failure_level,
        confidence=0.95,
        concise_feedback="The first error is explicit.",
    )


def _link(
    relation: GoalRelation,
    *,
    scope: ScopeRelation = ScopeRelation.SAME,
    bridges: list[str] | None = None,
) -> ClaimGoalLink:
    return ClaimGoalLink(
        subject_id="claim-a",
        subject_kind="claim",
        target_obligation_id="main",
        relation=relation,
        scope_relation=scope,
        required_bridge_obligation_ids=bridges or [],
        alignment_confidence=0.9,
    )


def test_four_failure_classes_map_to_existing_actions() -> None:
    classifier = FailureClassifier()
    execution = classifier.classify(_report(), route_id="route-a")
    bridge = classifier.classify(
        _report(FailureLevel.PLAN),
        route_id="route-a",
        goal_link=_link(GoalRelation.UNKNOWN, bridges=["bridge-1"]),
    )
    plan = classifier.classify(
        _report(FailureLevel.EXECUTION),
        route_id="route-a",
        goal_link=_link(GoalRelation.NECESSARY_ONLY),
    )
    framing = classifier.classify(
        _report(FailureLevel.EXECUTION),
        route_id="route-a",
        goal_link=_link(
            GoalRelation.UNKNOWN,
            scope=ScopeRelation.CLAIM_WEAKER,
        ),
    )

    assert execution.control_failure_class == ProofFailureClass.EXECUTION
    assert execution.recommended_existing_action == ActionKind.REVISE
    assert bridge.control_failure_class == ProofFailureClass.BRIDGE
    assert bridge.recommended_existing_action == ActionKind.BRIDGE
    assert plan.control_failure_class == ProofFailureClass.PLAN
    assert plan.recommended_existing_action == ActionKind.META_REPLAN
    assert framing.control_failure_class == ProofFailureClass.FRAMING
    assert framing.recommended_existing_action == ActionKind.SWITCH_REPRESENTATION


def test_problem_integrity_failure_forces_framing_reanchor() -> None:
    record = FailureClassifier().classify(
        _report(integrity_ok=False),
        route_id="route-a",
    )

    assert record.control_failure_class == ProofFailureClass.FRAMING
    assert record.confidence == 1.0


def test_blueprint_rewrite_preserves_verified_artifacts_and_route_history() -> None:
    rewriter = BlueprintRewriter()
    request = rewriter.build_request(
        route_id="route-a",
        failure_record_id="failure-a",
        preserved_fact_ids=["fact-1"],
        preserved_step_ids=["step-1"],
        invalidated_plan_elements=["claim-a implies main"],
        current_overstrong_targets=["prove a stronger classification"],
        proposed_weaker_targets=["prove the minimal bridge"],
        proposed_bridge_obligation_ids=["bridge-1"],
        representation_change_required=False,
    )
    route = RouteDescriptor(
        route_id="route-a",
        strategy_id="strategy-a",
        mechanism_signature=["mechanism-a"],
    )

    rewriter.apply_reviewed_rewrite(
        request,
        approved=True,
        route=route,
        review_evidence={"review_id": "meta-review-a"},
    )

    assert request.status == "executed"
    assert request.preserved_fact_ids == ["fact-1"]
    assert request.preserved_step_ids == ["step-1"]
    assert route.requires_revision is True
    assert route.status == RouteStatus.REPAIR_ONCE
    assert route.route_id == "route-a"
