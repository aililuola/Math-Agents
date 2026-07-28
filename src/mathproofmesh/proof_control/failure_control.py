from __future__ import annotations

from collections.abc import Mapping, Sequence

from ..config import FailureControlConfig
from ..schemas import (
    ActionKind,
    FailureLevel,
    RouteDescriptor,
    RouteStatus,
    VerificationReport,
    stable_hash,
)
from .models import (
    BlueprintRewriteRequest,
    ClaimGoalLink,
    FailureClassificationRecord,
    GoalRelation,
    InferenceRiskRecord,
    NearMissRecord,
    ProofFailureClass,
    ScopeRelation,
)


class FailureClassifier:
    ACTIONS = {
        ProofFailureClass.EXECUTION: ActionKind.REVISE,
        ProofFailureClass.BRIDGE: ActionKind.BRIDGE,
        ProofFailureClass.PLAN: ActionKind.META_REPLAN,
        ProofFailureClass.FRAMING: ActionKind.SWITCH_REPRESENTATION,
    }

    def __init__(self, config: FailureControlConfig | None = None) -> None:
        self.config = config or FailureControlConfig()

    def classify(
        self,
        report: VerificationReport,
        *,
        route_id: str,
        goal_link: ClaimGoalLink | None = None,
        risks: Sequence[InferenceRiskRecord] = (),
        near_miss: NearMissRecord | None = None,
    ) -> FailureClassificationRecord:
        open_risks = [item for item in risks if item.status == "open"]
        failure_class, reason, confidence = self._deterministic_class(
            report,
            goal_link=goal_link,
            open_risks=open_risks,
            near_miss=near_miss,
        )
        first_error = report.first_error_step or (
            report.issues[0].step_id if report.issues else None
        )
        fingerprint = (
            stable_hash(
                {
                    "target_id": report.target_id,
                    "first_error": first_error,
                    "issues": [item.description for item in report.issues],
                }
            )
            if first_error or report.issues
            else None
        )
        return FailureClassificationRecord(
            route_id=route_id,
            target_id=report.target_id,
            legacy_failure_level=report.failure_level,
            control_failure_class=failure_class,
            first_error_fingerprint=fingerprint,
            evidence=[
                reason,
                *[item.issue_id for item in report.issues],
                *[item.risk_id for item in open_risks],
            ],
            recommended_existing_action=self.ACTIONS[failure_class],
            recommended_control_subaction=reason,
            confidence=confidence,
        )

    def _deterministic_class(
        self,
        report: VerificationReport,
        *,
        goal_link: ClaimGoalLink | None,
        open_risks: Sequence[InferenceRiskRecord],
        near_miss: NearMissRecord | None,
    ) -> tuple[ProofFailureClass, str, float]:
        if not report.problem_integrity_ok:
            return (
                ProofFailureClass.FRAMING,
                "reanchor_to_immutable_problem",
                1.0,
            )
        if goal_link is not None and (
            goal_link.scope_relation == ScopeRelation.INCOMPARABLE
            or goal_link.relation
            in {GoalRelation.UNRELATED, GoalRelation.HEURISTIC_ONLY}
        ):
            return (
                ProofFailureClass.FRAMING,
                "replace_overstrong_or_scope_mismatched_target",
                0.98,
            )
        if any(
            item.risk_type.value == "wrong_direction"
            and item.confidence >= self.config.min_classification_confidence
            for item in open_risks
        ):
            return (
                ProofFailureClass.PLAN,
                "rewrite_blueprint_in_valid_implication_direction",
                0.98,
            )
        if any(
            item.risk_type.value
            in {
                "necessary_to_sufficient",
                "eventual_to_global",
                "projection_to_original",
                "quantifier_swap",
                "scope_mismatch",
                "ambiguous_semantic_leap",
                "partial_property_to_total_property",
                "nonempty_intersection_to_subset_containment",
                "exists_component_to_all_components",
                "some_witness_to_all_witnesses",
                "coverage_to_exhaustiveness",
                "at_least_one_to_only_from_set",
            }
            and item.confidence >= self.config.min_classification_confidence
            for item in open_risks
        ):
            return (
                ProofFailureClass.FRAMING,
                "reanchor_scope_or_representation",
                0.95,
            )
        if (
            goal_link is not None and goal_link.relation == GoalRelation.NECESSARY_ONLY
        ) or report.failure_level == FailureLevel.STRATEGY:
            return (
                ProofFailureClass.PLAN,
                "rewrite_plan_around_a_sufficient_target",
                0.95,
            )
        if report.failure_level == FailureLevel.PLAN and not (
            goal_link and goal_link.required_bridge_obligation_ids
        ):
            return ProofFailureClass.PLAN, "rewrite_proof_blueprint", 0.90
        if goal_link is not None and (
            goal_link.required_bridge_obligation_ids
            or (
                goal_link.relation == GoalRelation.UNKNOWN
                and goal_link.scope_relation
                in {ScopeRelation.SAME, ScopeRelation.CLAIM_STRONGER}
            )
        ):
            return (
                ProofFailureClass.BRIDGE,
                "open_minimal_bridge_obligation",
                0.88,
            )
        if report.failure_level == FailureLevel.PLAN:
            return ProofFailureClass.PLAN, "rewrite_proof_blueprint", 0.85
        if near_miss is not None or report.failure_level in {
            FailureLevel.EXECUTION,
            FailureLevel.NONE,
        }:
            return (
                ProofFailureClass.EXECUTION,
                "repair_first_local_error",
                0.90 if near_miss is not None else 0.80,
            )
        return ProofFailureClass.EXECUTION, "bounded_local_repair", 0.65


class BlueprintRewriter:
    def __init__(self, config: FailureControlConfig | None = None) -> None:
        self.config = config or FailureControlConfig()
        self.requests: dict[str, BlueprintRewriteRequest] = {}

    def build_request(
        self,
        *,
        route_id: str,
        failure_record_id: str,
        preserved_fact_ids: Sequence[str],
        preserved_step_ids: Sequence[str],
        invalidated_plan_elements: Sequence[str],
        current_overstrong_targets: Sequence[str],
        proposed_weaker_targets: Sequence[str],
        proposed_bridge_obligation_ids: Sequence[str],
        representation_change_required: bool,
    ) -> BlueprintRewriteRequest:
        existing = [
            item
            for item in self.requests.values()
            if item.route_id == route_id and item.status != "rejected"
        ]
        if len(existing) >= self.config.max_blueprint_rewrites_per_route:
            raise ValueError("blueprint rewrite budget exhausted for route")
        request = BlueprintRewriteRequest(
            route_id=route_id,
            failure_record_id=failure_record_id,
            preserved_fact_ids=list(dict.fromkeys(preserved_fact_ids)),
            preserved_step_ids=list(dict.fromkeys(preserved_step_ids)),
            invalidated_plan_elements=list(dict.fromkeys(invalidated_plan_elements)),
            current_overstrong_targets=list(dict.fromkeys(current_overstrong_targets)),
            proposed_weaker_targets=list(dict.fromkeys(proposed_weaker_targets)),
            proposed_bridge_obligation_ids=list(
                dict.fromkeys(proposed_bridge_obligation_ids)
            ),
            representation_change_required=representation_change_required,
        )
        self.requests[request.request_id] = request
        return request

    def apply_reviewed_rewrite(
        self,
        request: BlueprintRewriteRequest,
        *,
        approved: bool,
        route: RouteDescriptor | None = None,
        review_evidence: Mapping[str, object] | None = None,
    ) -> BlueprintRewriteRequest:
        if not approved:
            request.status = "rejected"
            return request
        if not review_evidence:
            raise ValueError("approved blueprint rewrite requires review evidence")
        request.status = "executed"
        if route is not None:
            if route.route_id != request.route_id:
                raise ValueError("blueprint rewrite route mismatch")
            route.requires_revision = True
            route.revision_summary = (
                "Blueprint rewrite preserves verified artifacts and replaces "
                "only invalid plan elements."
            )
            if route.status == RouteStatus.ACTIVE:
                route.status = RouteStatus.REPAIR_ONCE
        return request
