from __future__ import annotations

from mathproofmesh.proof_control.dependencies import (
    DependencyResolver,
    migrate_legacy_dependencies,
)
from mathproofmesh.proof_control.models import DependencyKind, DependencyRef
from mathproofmesh.schemas import ClaimCard, ClaimStatus, ProofStep


def test_local_step_dependency_resolves_inside_delta() -> None:
    step = ProofStep(
        step_id="step-local",
        statement="The local construction has the required relation.",
        justification="Directly from the construction.",
    )
    resolver = DependencyResolver(
        local_steps={step.step_id: step},
        structurally_verified_step_ids={step.step_id},
    )

    result = resolver.resolve_all(
        [
            DependencyRef(
                kind=DependencyKind.LOCAL_STEP,
                target_id=step.step_id,
                source_delta_id="delta-a",
            )
        ]
    )

    assert result.resolved
    assert result.resolved_refs[0].kind == DependencyKind.LOCAL_STEP


def test_local_claim_dependency_resolves_inside_attempt() -> None:
    claim = ClaimCard(
        claim_id="claim-local",
        statement="A local auxiliary statement.",
        conclusion="A local auxiliary statement.",
        status=ClaimStatus.VERIFIED,
        source_attempt_id="attempt-a",
    )
    resolver = DependencyResolver(
        local_claims={claim.claim_id: claim},
        verified_local_claim_ids={claim.claim_id},
    )

    result = resolver.resolve_all(
        [
            DependencyRef(
                kind=DependencyKind.LOCAL_CLAIM,
                target_id=claim.claim_id,
                source_attempt_id="attempt-a",
            )
        ]
    )

    assert result.resolved


def test_global_fact_requires_broker_admission() -> None:
    resolver = DependencyResolver(broker_fact_ids={"fact-admitted"})

    missing = resolver.resolve_all(
        [DependencyRef(kind=DependencyKind.GLOBAL_FACT, target_id="fact-local-only")]
    )
    admitted = resolver.resolve_all(
        [DependencyRef(kind=DependencyKind.GLOBAL_FACT, target_id="fact-admitted")]
    )

    assert not missing.resolved
    assert missing.missing_refs
    assert admitted.resolved


def test_ambiguous_legacy_dependency_does_not_auto_invalidate() -> None:
    migration = migrate_legacy_dependencies(
        ["legacy-unknown"],
        source_attempt_id="attempt-a",
        source_delta_id="delta-a",
        local_step_ids=set(),
        local_claim_ids=set(),
        broker_fact_ids=set(),
    )

    assert migration.migration_status == "ambiguous"
    assert migration.normalization_task is not None
    assert not migration.invalidates_claim


def test_prefixed_dependency_migration_is_stable() -> None:
    migration = migrate_legacy_dependencies(
        ["step:s-1", "claim:c-1", "fact:f-1", "obl:o-1"],
        source_attempt_id="attempt-a",
        source_delta_id="delta-a",
    )

    assert [item.kind for item in migration.dependency_refs] == [
        DependencyKind.LOCAL_STEP,
        DependencyKind.LOCAL_CLAIM,
        DependencyKind.GLOBAL_FACT,
        DependencyKind.OBLIGATION,
    ]
    assert migration.migration_status == "complete"
