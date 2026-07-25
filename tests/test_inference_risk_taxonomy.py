from __future__ import annotations

import pytest

from mathproofmesh.proof_control.inference_risk import InferenceRiskScanner
from mathproofmesh.proof_control.models import (
    ClaimGoalLink,
    GoalRelation,
    IndexScope,
    InferenceRiskType,
    ObjectScope,
    ScopeRelation,
    ScopeSignature,
    UniformityScope,
)
from mathproofmesh.schemas import EvidenceType


def _scope(
    subject_id: str,
    *,
    index: IndexScope = IndexScope.UNKNOWN,
    uniformity: UniformityScope = UniformityScope.UNKNOWN,
    object_scope: ObjectScope = ObjectScope.UNKNOWN,
) -> ScopeSignature:
    return ScopeSignature(
        subject_id=subject_id,
        index_scope=index,
        uniformity=uniformity,
        object_scope=object_scope,
        normalization_confidence=1.0,
    )


@pytest.mark.parametrize(
    ("expected", "kwargs"),
    [
        (
            InferenceRiskType.EVENTUAL_TO_GLOBAL,
            {
                "premise_scopes": [_scope("p", index=IndexScope.EVENTUAL)],
                "conclusion_scope": _scope("c", index=IndexScope.ALL),
            },
        ),
        (
            InferenceRiskType.POINTWISE_TO_UNIFORM,
            {
                "premise_scopes": [_scope("p", uniformity=UniformityScope.POINTWISE)],
                "conclusion_scope": _scope("c", uniformity=UniformityScope.UNIFORM),
            },
        ),
        (
            InferenceRiskType.FINITE_RANGE_TO_FINITE_STATE,
            {
                "premise_texts": ["the bounded differences have finite range"],
                "conclusion_text": "therefore the sequence is periodic",
            },
        ),
        (
            InferenceRiskType.IMAGE_INCLUSION_TO_SURJECTIVITY,
            {
                "premise_texts": ["image inclusion holds"],
                "conclusion_text": "the map is surjective",
            },
        ),
        (
            InferenceRiskType.PROJECTION_TO_ORIGINAL,
            {
                "premise_scopes": [_scope("p", object_scope=ObjectScope.PROJECTION)],
                "conclusion_scope": _scope("c", object_scope=ObjectScope.FULL_OBJECT),
            },
        ),
        (
            InferenceRiskType.LOCAL_TO_GLOBAL,
            {
                "premise_scopes": [_scope("p", object_scope=ObjectScope.SUBSTRUCTURE)],
                "conclusion_scope": _scope("c", object_scope=ObjectScope.FULL_OBJECT),
            },
        ),
        (
            InferenceRiskType.EXISTENCE_TO_UNIFORM_EXISTENCE,
            {
                "premise_scopes": [
                    _scope(
                        "p",
                        uniformity=UniformityScope.EXISTS_PER_INSTANCE,
                    )
                ],
                "conclusion_scope": _scope("c", uniformity=UniformityScope.UNIFORM),
            },
        ),
        (
            InferenceRiskType.PAIRWISE_TO_COMMON_WITNESS,
            {
                "premise_texts": ["each pair has a witness"],
                "conclusion_text": "there is one common witness",
            },
        ),
        (
            InferenceRiskType.EMPIRICAL_TO_UNIVERSAL,
            {
                "evidence_type": EvidenceType.BOUNDED_EXPERIMENT,
                "conclusion_scope": _scope("c", index=IndexScope.ALL),
            },
        ),
    ],
)
def test_deterministic_risk_taxonomy(
    expected: InferenceRiskType, kwargs: dict[str, object]
) -> None:
    risks = InferenceRiskScanner().deterministic_risks(
        subject_id="subject",
        **kwargs,
    )

    assert expected in {item.risk_type for item in risks}


def test_necessary_only_used_as_sufficient_is_risk() -> None:
    link = ClaimGoalLink(
        subject_id="claim",
        subject_kind="claim",
        target_obligation_id="main",
        relation=GoalRelation.NECESSARY_ONLY,
        scope_relation=ScopeRelation.SAME,
        alignment_confidence=1.0,
    )

    risks = InferenceRiskScanner().scan_goal_link(link)

    assert [item.risk_type for item in risks] == [
        InferenceRiskType.NECESSARY_TO_SUFFICIENT
    ]


def test_textual_risk_is_not_a_direct_verification_rejection() -> None:
    risk = InferenceRiskScanner().deterministic_risks(
        subject_id="step",
        premise_texts=["image inclusion"],
        conclusion_text="surjectivity",
    )[0]

    assert risk.status == "open"
    assert risk.confidence < 1.0
