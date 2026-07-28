from __future__ import annotations

from mathproofmesh.proof_control.models import (
    IndexScope,
    ObjectScope,
    ScopeRelation,
    ScopeSignature,
    UniformityScope,
)
from mathproofmesh.proof_control.scope_guard import ScopeGuard
from mathproofmesh.schemas import QuantifierSpec


def _scope(
    subject_id: str,
    *,
    index: IndexScope = IndexScope.UNKNOWN,
    uniformity: UniformityScope = UniformityScope.UNKNOWN,
    object_scope: ObjectScope = ObjectScope.UNKNOWN,
    quantifiers: list[QuantifierSpec] | None = None,
    confidence: float = 1.0,
) -> ScopeSignature:
    return ScopeSignature(
        subject_id=subject_id,
        index_scope=index,
        uniformity=uniformity,
        object_scope=object_scope,
        quantifiers=quantifiers or [],
        normalization_confidence=confidence,
    )


def test_eventual_or_bounded_scope_cannot_close_all_scope() -> None:
    guard = ScopeGuard()
    target = _scope("target", index=IndexScope.ALL)

    for premise_scope in (
        IndexScope.EVENTUAL,
        IndexScope.FINITE_PREFIX,
        IndexScope.BOUNDED_RANGE,
    ):
        premise = _scope("premise", index=premise_scope)
        assert guard.compare(premise, target) == ScopeRelation.CLAIM_WEAKER
        assert guard.can_close_obligation(premise, target) is False


def test_pointwise_and_projection_cannot_close_uniform_full_object() -> None:
    guard = ScopeGuard()
    target = _scope(
        "target",
        uniformity=UniformityScope.UNIFORM,
        object_scope=ObjectScope.FULL_OBJECT,
    )
    premise = _scope(
        "premise",
        uniformity=UniformityScope.POINTWISE,
        object_scope=ObjectScope.PROJECTION,
    )

    assert guard.compare(premise, target) == ScopeRelation.CLAIM_WEAKER
    assert guard.can_close_obligation(premise, target) is False


def test_quantifier_order_mismatch_is_incomparable() -> None:
    guard = ScopeGuard()
    forall = QuantifierSpec(
        order=0,
        kind="forall",
        variable_id="x",
        display_name="x",
        domain="X",
    )
    exists = QuantifierSpec(
        order=1,
        kind="exists",
        variable_id="y",
        display_name="y",
        domain="Y",
    )
    premise = _scope("premise", quantifiers=[forall, exists])
    conclusion = _scope(
        "target",
        quantifiers=[
            exists.model_copy(update={"order": 0}),
            forall.model_copy(update={"order": 1}),
        ],
    )

    assert guard.compare(premise, conclusion) == ScopeRelation.INCOMPARABLE
    assert guard.can_close_obligation(premise, conclusion) is False


def test_low_confidence_unstructured_claim_cannot_promote_fact() -> None:
    assert (
        ScopeGuard().can_promote_fact(
            _scope("claim", index=IndexScope.ALL, confidence=0.3)
        )
        is False
    )
