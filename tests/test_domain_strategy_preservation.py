from __future__ import annotations

from mathproofmesh.proof_control.models import StrategyRevisionReason
from mathproofmesh.proof_control.strategy_blueprint import OriginalStrategyArchive

from v082_helpers import make_domain_strategy


def test_original_domain_strategy_archived_before_admission() -> None:
    archive = OriginalStrategyArchive()
    strategy = make_domain_strategy(strategy_id="strategy-original")

    entry = archive.archive(
        strategy,
        first_seen_round=0,
        raw_artifact_ref="artifact://planner/strategy-set",
    )

    assert entry.strategy == strategy
    assert entry.domain_objects
    assert entry.critical_claims
    assert entry.expected_lemmas == strategy.expected_lemmas


def test_generic_fallback_cannot_replace_original_strategy() -> None:
    archive = OriginalStrategyArchive()
    original = make_domain_strategy(strategy_id="strategy-original")
    archive.archive(
        original,
        first_seen_round=0,
        raw_artifact_ref="artifact://planner/strategy-set",
    )
    fallback = make_domain_strategy(strategy_id="strategy-fallback").model_copy(
        update={
            "title": "Generic fallback",
            "core_idea": "Find an invariant strong enough to prove the theorem.",
            "expected_lemmas": ["Complete the argument."],
            "parent_strategy_ids": [original.strategy_id],
            "tags": ["generic-fallback"],
        }
    )

    assessment = archive.register_child(
        fallback,
        parent_strategy_id=original.strategy_id,
        reason=StrategyRevisionReason.ADMISSION_REWRITE,
        generic_fallback=True,
    )

    assert not assessment.selectable
    assert assessment.semantic_rejection_reason
    assert archive.entries[original.strategy_id].strategy == original
    assert archive.lineage[original.strategy_id].status == "original"


def test_rewrite_creates_child_lineage() -> None:
    archive = OriginalStrategyArchive()
    original = make_domain_strategy(strategy_id="strategy-original")
    child = make_domain_strategy(strategy_id="strategy-child").model_copy(
        update={"parent_strategy_ids": [original.strategy_id]}
    )
    archive.archive(
        original,
        first_seen_round=0,
        raw_artifact_ref="artifact://planner/strategy-set",
    )

    assessment = archive.register_child(
        child,
        parent_strategy_id=original.strategy_id,
        reason=StrategyRevisionReason.BRIDGE_INSERTION,
    )

    assert assessment.selectable
    assert archive.lineage[child.strategy_id].parent_strategy_id == original.strategy_id
    assert archive.lineage[child.strategy_id].root_strategy_id == original.strategy_id
    assert archive.lineage[original.strategy_id].status == "original"


def test_failed_child_does_not_delete_parent() -> None:
    archive = OriginalStrategyArchive()
    original = make_domain_strategy(strategy_id="strategy-original")
    archive.archive(
        original,
        first_seen_round=0,
        raw_artifact_ref="artifact://planner/strategy-set",
    )
    child = make_domain_strategy(strategy_id="strategy-child").model_copy(
        update={"parent_strategy_ids": [original.strategy_id]}
    )
    archive.register_child(
        child,
        parent_strategy_id=original.strategy_id,
        reason=StrategyRevisionReason.PLAN_FAILURE,
    )

    archive.reject_child(child.strategy_id, evidence_ids=["review-failed"])

    assert original.strategy_id in archive.entries
    assert archive.lineage[original.strategy_id].status == "original"
    assert archive.lineage[child.strategy_id].status == "rejected_with_evidence"


def test_domain_objects_preserved_in_revised_strategy() -> None:
    archive = OriginalStrategyArchive()
    original = make_domain_strategy(strategy_id="strategy-original")
    entry = archive.archive(
        original,
        first_seen_round=0,
        raw_artifact_ref="artifact://planner/strategy-set",
    )
    child = make_domain_strategy(strategy_id="strategy-child").model_copy(
        update={"parent_strategy_ids": [original.strategy_id]}
    )

    archive.register_child(
        child,
        parent_strategy_id=original.strategy_id,
        reason=StrategyRevisionReason.SCOPE_REPAIR,
    )

    lineage = archive.lineage[child.strategy_id]
    assert set(lineage.preserved_domain_objects) & set(entry.domain_objects)
    assert lineage.preserved_mechanism_tags
