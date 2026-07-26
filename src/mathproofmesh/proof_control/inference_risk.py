from __future__ import annotations

from collections.abc import Iterable, Sequence
from typing import Any

from ..schemas import ClaimCard, EvidenceType, ProofStep, stable_hash
from .models import (
    ClaimGoalLink,
    GoalRelation,
    IndexScope,
    InferenceRiskRecord,
    InferenceRiskType,
    ObjectScope,
    PropertyStrength,
    RelationSignature,
    ScopeSignature,
    SetRelationKind,
    StructuredVerifierIssue,
    UniformityScope,
    VerifierIssueCode,
)


class InferenceRiskScanner:
    """Open sidecar risks; textual signals never directly reject a proof step."""

    _ISSUE_RISK_TYPES = {
        VerifierIssueCode.UNSUPPORTED_IMPLICATION: (
            InferenceRiskType.AMBIGUOUS_SEMANTIC_LEAP
        ),
        VerifierIssueCode.WRONG_DIRECTION: InferenceRiskType.WRONG_DIRECTION,
        VerifierIssueCode.FINITE_TO_UNIVERSAL: (
            InferenceRiskType.EMPIRICAL_TO_UNIVERSAL
        ),
        VerifierIssueCode.EVENTUAL_TO_GLOBAL: InferenceRiskType.EVENTUAL_TO_GLOBAL,
        VerifierIssueCode.MISSING_UNIFORMITY: InferenceRiskType.POINTWISE_TO_UNIFORM,
        VerifierIssueCode.QUANTIFIER_SWAP: InferenceRiskType.QUANTIFIER_SWAP,
        VerifierIssueCode.PROPERTY_STRENGTHENING: (
            InferenceRiskType.PARTIAL_PROPERTY_TO_TOTAL_PROPERTY
        ),
        VerifierIssueCode.UNVERIFIED_CANDIDATE_BOUND: (
            InferenceRiskType.EMPIRICAL_TO_UNIVERSAL
        ),
        VerifierIssueCode.DEPENDENCY_MISSING: InferenceRiskType.DEPENDENCY_MISSING,
        VerifierIssueCode.SCOPE_MISMATCH: InferenceRiskType.SCOPE_MISMATCH,
        VerifierIssueCode.OTHER: InferenceRiskType.AMBIGUOUS_SEMANTIC_LEAP,
    }

    _ISSUE_ACTIONS = {
        VerifierIssueCode.WRONG_DIRECTION: "rewrite_blueprint",
        VerifierIssueCode.DEPENDENCY_MISSING: "normalize_dependency",
        VerifierIssueCode.SCOPE_MISMATCH: "create_minimal_bridge",
        VerifierIssueCode.EVENTUAL_TO_GLOBAL: "create_minimal_bridge",
        VerifierIssueCode.MISSING_UNIFORMITY: "create_minimal_bridge",
    }

    _ISSUE_TEXT_MARKERS = (
        (
            VerifierIssueCode.WRONG_DIRECTION,
            ("wrong direction", "reverse implication", "unproved converse"),
        ),
        (
            VerifierIssueCode.FINITE_TO_UNIVERSAL,
            (
                "finite evidence does not prove",
                "bounded search does not prove",
                "tested cases do not prove",
                "finite to universal",
            ),
        ),
        (
            VerifierIssueCode.EVENTUAL_TO_GLOBAL,
            ("eventual to global", "eventually does not imply every"),
        ),
        (
            VerifierIssueCode.MISSING_UNIFORMITY,
            ("missing uniformity", "pointwise does not imply uniform"),
        ),
        (
            VerifierIssueCode.QUANTIFIER_SWAP,
            ("quantifier swap", "quantifier order", "interchanged quantifiers"),
        ),
        (
            VerifierIssueCode.PROPERTY_STRENGTHENING,
            (
                "property strengthening",
                "stronger conclusion",
                "strengthened without proof",
            ),
        ),
        (
            VerifierIssueCode.UNVERIFIED_CANDIDATE_BOUND,
            ("unverified candidate bound", "candidate bound is not proved"),
        ),
        (
            VerifierIssueCode.DEPENDENCY_MISSING,
            ("missing dependency", "dependency is not established"),
        ),
        (
            VerifierIssueCode.SCOPE_MISMATCH,
            ("scope mismatch", "outside the established scope"),
        ),
        (
            VerifierIssueCode.UNSUPPORTED_IMPLICATION,
            (
                "unsupported implication",
                "does not follow from",
                "does not imply",
            ),
        ),
    )

    @classmethod
    def infer_issue_code(cls, text: str) -> VerifierIssueCode | None:
        normalized = " ".join(text.casefold().split())
        return next(
            (
                code
                for code, markers in cls._ISSUE_TEXT_MARKERS
                if any(marker in normalized for marker in markers)
            ),
            None,
        )

    def map_verifier_issue(
        self,
        issue: StructuredVerifierIssue,
        *,
        route_id: str | None = None,
    ) -> list[InferenceRiskRecord]:
        risk_type = issue.suggested_risk_type or self._ISSUE_RISK_TYPES[issue.code]
        subject_id = issue.step_id or issue.target_id
        risk_id = (
            "risk_verifier_"
            + stable_hash(
                {
                    "issue_id": issue.issue_id,
                    "report_id": issue.report_id,
                    "subject_id": subject_id,
                    "risk_type": risk_type.value,
                }
            )[:20]
        )
        explanation = (
            f"Verifier issue {issue.code.value}: {issue.premise_summary} "
            f"does not yet justify {issue.conclusion_summary}"
        ).strip()
        return [
            InferenceRiskRecord(
                risk_id=risk_id,
                route_id=route_id,
                subject_id=subject_id,
                premise_ids=[issue.target_id],
                conclusion_id=issue.step_id or issue.target_id,
                risk_type=risk_type,
                deterministic_rule_id=f"verifier.{issue.code.value}",
                explanation=explanation,
                confidence=issue.confidence,
                source_issue_ids=[issue.issue_id],
                recommended_control_action=self._ISSUE_ACTIONS.get(
                    issue.code,
                    "create_countermodel",
                ),
            )
        ]

    def critical_step_semantic_scan(
        self,
        *,
        subject_id: str,
        scope_known: bool,
        centrality: float,
        referenced_by_count: int,
        preparing_fact_promotion: bool,
        route_id: str | None = None,
    ) -> list[InferenceRiskRecord]:
        high_impact = (
            centrality >= 0.75 or referenced_by_count >= 2 or preparing_fact_promotion
        )
        if scope_known or not high_impact:
            return []
        risk_id = (
            "risk_ambiguous_"
            + stable_hash(
                {
                    "subject_id": subject_id,
                    "route_id": route_id,
                    "centrality": round(centrality, 6),
                    "referenced_by_count": referenced_by_count,
                    "preparing_fact_promotion": preparing_fact_promotion,
                }
            )[:20]
        )
        return [
            InferenceRiskRecord(
                risk_id=risk_id,
                route_id=route_id,
                subject_id=subject_id,
                risk_type=InferenceRiskType.AMBIGUOUS_SEMANTIC_LEAP,
                deterministic_rule_id="critical_step.unknown_scope",
                explanation=(
                    "A high-impact Claim or Step has unresolved semantic scope and "
                    "requires independent review before reuse."
                ),
                confidence=max(0.75, min(1.0, centrality)),
                recommended_control_action="independent_semantic_review",
            )
        ]

    def scan_step(
        self,
        step: ProofStep,
        *,
        premise_scopes: Sequence[ScopeSignature] = (),
        conclusion_scope: ScopeSignature | None = None,
        premise_texts: Sequence[str] = (),
        premise_relation_signatures: Sequence[RelationSignature] = (),
        conclusion_relation_signature: RelationSignature | None = None,
        route_id: str | None = None,
    ) -> list[InferenceRiskRecord]:
        return self.deterministic_risks(
            subject_id=step.step_id,
            premise_scopes=premise_scopes,
            conclusion_scope=conclusion_scope,
            premise_texts=premise_texts,
            conclusion_text=f"{step.statement}\n{step.justification}",
            premise_relation_signatures=premise_relation_signatures,
            conclusion_relation_signature=conclusion_relation_signature,
            route_id=route_id,
        )

    def scan_claim(
        self,
        claim: ClaimCard,
        *,
        premise_scopes: Sequence[ScopeSignature] = (),
        conclusion_scope: ScopeSignature | None = None,
        evidence_type: EvidenceType | None = None,
        premise_relation_signatures: Sequence[RelationSignature] = (),
        conclusion_relation_signature: RelationSignature | None = None,
        route_id: str | None = None,
    ) -> list[InferenceRiskRecord]:
        return self.deterministic_risks(
            subject_id=claim.claim_id,
            premise_scopes=premise_scopes,
            conclusion_scope=conclusion_scope,
            evidence_type=evidence_type,
            premise_texts=claim.assumptions,
            conclusion_text=f"{claim.statement}\n{claim.conclusion}",
            premise_relation_signatures=premise_relation_signatures,
            conclusion_relation_signature=conclusion_relation_signature,
            route_id=route_id,
        )

    def scan_goal_link(
        self, link: ClaimGoalLink, *, route_id: str | None = None
    ) -> list[InferenceRiskRecord]:
        return self.deterministic_risks(
            subject_id=link.subject_id,
            goal_link=link,
            route_id=route_id,
        )

    def deterministic_risks(
        self,
        *,
        subject_id: str,
        premise_scopes: Sequence[ScopeSignature] = (),
        conclusion_scope: ScopeSignature | None = None,
        evidence_type: EvidenceType | None = None,
        goal_link: ClaimGoalLink | None = None,
        premise_texts: Iterable[str] = (),
        conclusion_text: str = "",
        premise_relation_signatures: Sequence[RelationSignature] = (),
        conclusion_relation_signature: RelationSignature | None = None,
        route_id: str | None = None,
    ) -> list[InferenceRiskRecord]:
        found: dict[InferenceRiskType, tuple[str, str, float]] = {}

        def add(
            risk_type: InferenceRiskType,
            rule_id: str,
            explanation: str,
            confidence: float,
        ) -> None:
            previous = found.get(risk_type)
            if previous is None or confidence > previous[2]:
                found[risk_type] = (rule_id, explanation, confidence)

        if goal_link is not None and goal_link.relation == GoalRelation.NECESSARY_ONLY:
            add(
                InferenceRiskType.NECESSARY_TO_SUFFICIENT,
                "goal_link.necessary_used_as_sufficient",
                "A necessary-only claim cannot close a sufficient target.",
                1.0,
            )

        for premise in premise_scopes:
            if conclusion_scope is None:
                break
            if (
                premise.index_scope == IndexScope.EVENTUAL
                and conclusion_scope.index_scope == IndexScope.ALL
            ):
                add(
                    InferenceRiskType.EVENTUAL_TO_GLOBAL,
                    "scope.eventual_to_all",
                    "An eventual premise does not establish every index.",
                    1.0,
                )
            if (
                premise.uniformity
                in {
                    UniformityScope.POINTWISE,
                    UniformityScope.EXISTS_PER_INSTANCE,
                }
                and conclusion_scope.uniformity == UniformityScope.UNIFORM
            ):
                add(
                    InferenceRiskType.POINTWISE_TO_UNIFORM,
                    "scope.pointwise_to_uniform",
                    "Pointwise witnesses do not provide one uniform witness.",
                    1.0,
                )
            if (
                premise.uniformity == UniformityScope.EXISTS_PER_INSTANCE
                and conclusion_scope.uniformity == UniformityScope.UNIFORM
            ):
                add(
                    InferenceRiskType.EXISTENCE_TO_UNIFORM_EXISTENCE,
                    "scope.exists_per_instance_to_uniform",
                    "Instance-dependent existence does not establish uniform existence.",
                    1.0,
                )
            if (
                premise.object_scope in {ObjectScope.PROJECTION, ObjectScope.QUOTIENT}
                and conclusion_scope.object_scope == ObjectScope.FULL_OBJECT
            ):
                add(
                    InferenceRiskType.PROJECTION_TO_ORIGINAL,
                    "scope.projection_to_original",
                    "Information about a projection or quotient does not determine the original object.",
                    1.0,
                )
            if (
                premise.object_scope == ObjectScope.SUBSTRUCTURE
                and conclusion_scope.object_scope == ObjectScope.FULL_OBJECT
            ):
                add(
                    InferenceRiskType.LOCAL_TO_GLOBAL,
                    "scope.substructure_to_full_object",
                    "A substructure statement needs a bridge to the full object.",
                    1.0,
                )

        if (
            evidence_type
            in {EvidenceType.BOUNDED_EXPERIMENT, EvidenceType.NUMERICAL_HEURISTIC}
            and conclusion_scope is not None
            and conclusion_scope.index_scope == IndexScope.ALL
        ):
            add(
                InferenceRiskType.EMPIRICAL_TO_UNIVERSAL,
                "evidence.bounded_to_universal",
                "Bounded or empirical evidence cannot establish a universal conclusion.",
                1.0,
            )

        if conclusion_relation_signature is not None:
            for premise_relation in premise_relation_signatures:
                self._add_relation_strengthening_risks(
                    premise_relation,
                    conclusion_relation_signature,
                    add=add,
                )

        premise_text = " ".join(premise_texts).casefold()
        conclusion = conclusion_text.casefold()
        joined = f"{premise_text}\n{conclusion}"
        if any(
            marker in premise_text
            for marker in ("finite range", "finitely many gaps", "bounded differences")
        ) and any(
            marker in conclusion
            for marker in ("finite state", "periodic", "eventually periodic")
        ):
            add(
                InferenceRiskType.FINITE_RANGE_TO_FINITE_STATE,
                "text.finite_range_to_finite_state",
                "A finite range of local values does not make the evolving state finite.",
                0.90,
            )
        if any(
            marker in premise_text
            for marker in ("image inclusion", "image is contained", "image subset")
        ) and any(
            marker in conclusion for marker in ("surjective", "onto", "surjectivity")
        ):
            add(
                InferenceRiskType.IMAGE_INCLUSION_TO_SURJECTIVITY,
                "text.image_inclusion_to_surjectivity",
                "Image inclusion does not show that every target has a preimage.",
                0.90,
            )
        if any(
            marker in premise_text for marker in ("pairwise", "each pair", "every pair")
        ) and any(
            marker in conclusion
            for marker in ("common witness", "single witness", "one witness")
        ):
            add(
                InferenceRiskType.PAIRWISE_TO_COMMON_WITNESS,
                "text.pairwise_to_common_witness",
                "Pairwise witnesses need not be one common witness.",
                0.90,
            )
        if any(
            marker in premise_text
            for marker in (
                "there exists for each",
                "for each there exists",
                "depends on",
            )
        ) and any(
            marker in conclusion
            for marker in ("uniform", "single choice", "one choice")
        ):
            add(
                InferenceRiskType.EXISTENCE_TO_UNIFORM_EXISTENCE,
                "text.instance_exists_to_uniform",
                "The existential choice may depend on the instance.",
                0.85,
            )
        if any(
            marker in premise_text
            for marker in ("local", "neighborhood", "finite prefix")
        ) and any(
            marker in conclusion for marker in ("global", "everywhere", "all indices")
        ):
            add(
                InferenceRiskType.LOCAL_TO_GLOBAL,
                "text.local_to_global",
                "A local statement needs an explicit globalization argument.",
                0.80,
            )
        if any(
            marker in joined
            for marker in ("tested cases", "numerical evidence", "sampled")
        ) and any(
            marker in conclusion for marker in ("for all", "always", "universal")
        ):
            add(
                InferenceRiskType.EMPIRICAL_TO_UNIVERSAL,
                "text.empirical_to_universal",
                "Finite tests cannot alone prove a universal assertion.",
                0.85,
            )
        if any(
            marker in premise_text
            for marker in ("nonempty intersection", "intersects nontrivially")
        ) and any(
            marker in conclusion
            for marker in ("is a subset", "is contained in", "equals the set")
        ):
            add(
                InferenceRiskType.NONEMPTY_INTERSECTION_TO_SUBSET_CONTAINMENT,
                "relation.nonempty_intersection_to_containment",
                "Nonempty intersection does not establish set containment.",
                0.95,
            )
        if any(
            marker in premise_text
            for marker in ("some component", "there exists a component")
        ) and any(
            marker in conclusion for marker in ("all components", "every component")
        ):
            add(
                InferenceRiskType.EXISTS_COMPONENT_TO_ALL_COMPONENTS,
                "property.exists_component_to_all",
                "A property of one component does not establish it for all components.",
                0.95,
            )
        if any(
            marker in premise_text for marker in ("some witness", "a witness exists")
        ) and any(
            marker in conclusion for marker in ("all witnesses", "every witness")
        ):
            add(
                InferenceRiskType.SOME_WITNESS_TO_ALL_WITNESSES,
                "property.some_witness_to_all",
                "One witness does not establish a property of every witness.",
                0.95,
            )
        if "cover" in premise_text and any(
            marker in conclusion
            for marker in ("exhaustive classification", "partition", "only cases")
        ):
            add(
                InferenceRiskType.COVERAGE_TO_EXHAUSTIVENESS,
                "relation.cover_to_exhaustive",
                "Coverage alone does not establish an exclusive exhaustive classification.",
                0.95,
            )
        if "at least one" in premise_text and any(
            marker in conclusion for marker in ("only from", "exactly the set")
        ):
            add(
                InferenceRiskType.AT_LEAST_ONE_TO_ONLY_FROM_SET,
                "property.at_least_one_to_only_from_set",
                "Existence of one member does not characterize all possible members.",
                0.95,
            )

        return [
            InferenceRiskRecord(
                route_id=route_id,
                subject_id=subject_id,
                risk_type=risk_type,
                deterministic_rule_id=details[0],
                explanation=details[1],
                confidence=details[2],
                premise_relation_signatures=list(premise_relation_signatures),
                conclusion_relation_signature=conclusion_relation_signature,
            )
            for risk_type, details in sorted(
                found.items(), key=lambda item: item[0].value
            )
        ]

    @staticmethod
    def _add_relation_strengthening_risks(
        premise: RelationSignature,
        conclusion: RelationSignature,
        *,
        add: Any,
    ) -> None:
        if (
            premise.property_strength == PropertyStrength.PARTIAL
            and conclusion.property_strength
            in {PropertyStrength.UNIVERSAL, PropertyStrength.EXHAUSTIVE}
        ):
            add(
                InferenceRiskType.PARTIAL_PROPERTY_TO_TOTAL_PROPERTY,
                "property.partial_to_total",
                "A partial property does not establish the property for the total object.",
                1.0,
            )
        if (
            premise.set_relation == SetRelationKind.NONEMPTY_INTERSECTION
            and conclusion.set_relation
            in {
                SetRelationKind.SUBSET,
                SetRelationKind.SUPERSET,
                SetRelationKind.EQUALITY,
            }
        ):
            add(
                InferenceRiskType.NONEMPTY_INTERSECTION_TO_SUBSET_CONTAINMENT,
                "relation.nonempty_intersection_to_containment",
                "Nonempty intersection does not establish set containment.",
                1.0,
            )
        if (
            premise.semantic_role == "component"
            and premise.property_strength == PropertyStrength.EXISTENTIAL
            and conclusion.property_strength
            in {PropertyStrength.UNIVERSAL, PropertyStrength.EXHAUSTIVE}
        ):
            add(
                InferenceRiskType.EXISTS_COMPONENT_TO_ALL_COMPONENTS,
                "property.exists_component_to_all",
                "A property of one component does not establish it for all components.",
                1.0,
            )
        if (
            premise.semantic_role == "witness"
            and premise.property_strength == PropertyStrength.EXISTENTIAL
            and conclusion.property_strength
            in {PropertyStrength.UNIVERSAL, PropertyStrength.EXHAUSTIVE}
        ):
            add(
                InferenceRiskType.SOME_WITNESS_TO_ALL_WITNESSES,
                "property.some_witness_to_all",
                "One witness does not establish a property of every witness.",
                1.0,
            )
        if (
            premise.semantic_role == "coverage"
            or premise.set_relation == SetRelationKind.COVER
        ) and (
            conclusion.property_strength == PropertyStrength.EXHAUSTIVE
            or conclusion.set_relation == SetRelationKind.PARTITION
        ):
            add(
                InferenceRiskType.COVERAGE_TO_EXHAUSTIVENESS,
                "relation.cover_to_exhaustive",
                "Coverage does not by itself prove an exclusive exhaustive classification.",
                1.0,
            )
        if (
            premise.semantic_role == "membership"
            and premise.property_strength == PropertyStrength.EXISTENTIAL
            and conclusion.property_strength == PropertyStrength.EXHAUSTIVE
        ):
            add(
                InferenceRiskType.AT_LEAST_ONE_TO_ONLY_FROM_SET,
                "property.at_least_one_to_only_from_set",
                "At least one admissible member does not identify the only possible members.",
                1.0,
            )

    async def review_ambiguous(
        self,
        runner: Any,
        prompt: Any,
        *,
        role: str = "structural_verifier",
    ) -> InferenceRiskRecord | None:
        result = await runner.call(role, prompt)
        artifact = getattr(result, "artifact", result)
        return artifact if isinstance(artifact, InferenceRiskRecord) else None
