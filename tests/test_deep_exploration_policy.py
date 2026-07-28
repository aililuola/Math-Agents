from __future__ import annotations

from mathproofmesh.config import (
    DeepExplorationPolicyConfig,
    ExplorationTierPolicyConfig,
    RuntimeConfig,
)
from mathproofmesh.deep_exploration import (
    DeepExplorationRegistry,
    ExplorationEvidence,
    ExplorationOutcome,
    ExplorationSignature,
)
from mathproofmesh.prompts import PromptFactory
from mathproofmesh.schemas import (
    ProblemContract,
    ProblemKind,
    ProofCheckpoint,
    UsageRecord,
)


def _signature(
    mechanism: str,
    *,
    checkpoint_hash: str = "checkpoint-hash",
    target: str = "prove the local divisibility lemma",
    route_id: str = "route-a",
    recovery_lineage_id: str | None = None,
) -> ExplorationSignature:
    payload = {
        "problem_hash": "problem-hash",
        "verified_checkpoint_id": "checkpoint-1",
        "verified_checkpoint_hash": checkpoint_hash,
        "target_obligation_id": "obligation-1",
        "target_statement": target,
        "mechanism_tags": [mechanism],
        "representation_tags": ["residue classes"],
        "construction_tags": [],
        "invariant_tags": [],
        "transformation_tags": ["factorization"],
        "assumptions": ["n is a positive integer"],
        "route_id": route_id,
    }
    if recovery_lineage_id is not None:
        payload["recovery_lineage_id"] = recovery_lineage_id
    return ExplorationSignature.model_validate(payload)


def _evidence(**updates: bool) -> ExplorationEvidence:
    values = {
        "has_verified_checkpoint": True,
        "explicit_critical_target": True,
        "meta_approved": True,
        "final_reserve_available": True,
        "novelty_review_passed": True,
        "referee_confirmed_mechanism_change": True,
    }
    values.update(updates)
    return ExplorationEvidence(**values)


def test_three_tiers_use_separate_recovery_budgets_and_transport_timeouts() -> None:
    config = DeepExplorationPolicyConfig()
    runtime = RuntimeConfig()

    assert [item.output_tokens for item in config.tiers] == [
        64000,
        96000,
        128000,
    ]
    assert all(not hasattr(item, "no_content_timeout_seconds") for item in config.tiers)
    assert all(not hasattr(item, "wall_timeout_seconds") for item in config.tiers)
    assert [item.artifact_recovery_tokens for item in config.tiers] == [
        8000,
        12000,
        16000,
    ]
    assert runtime.exploration_output_token_tiers == [64000, 96000, 128000]
    assert runtime.stream_idle_timeout_seconds == 300
    assert runtime.agent_call_wall_timeout_seconds == 7200


def test_legacy_elapsed_time_fields_load_but_are_discarded() -> None:
    tier = ExplorationTierPolicyConfig.model_validate(
        {
            "output_tokens": 64000,
            "answer_reserve_tokens": 8000,
            "no_content_timeout_seconds": 480,
            "wall_timeout_seconds": 720,
        }
    )
    runtime = RuntimeConfig.model_validate(
        {
            "reasoning_only_abort_seconds": 720,
            "reasoning_only_min_characters": 4096,
        }
    )

    assert not hasattr(tier, "no_content_timeout_seconds")
    assert not hasattr(tier, "wall_timeout_seconds")
    assert not hasattr(tier, "answer_reserve_tokens")
    assert tier.artifact_recovery_tokens == 8000
    assert not hasattr(runtime, "reasoning_only_abort_seconds")
    assert not hasattr(runtime, "reasoning_only_min_characters")


def test_checkpoint_segment_index_no_longer_selects_route_tier() -> None:
    problem = ProblemContract(
        exact_statement="Prove P.",
        normalized_statement="Prove P.",
        definitions=[],
        problem_kind=ProblemKind.PROOF,
    )
    checkpoint = ProofCheckpoint(
        problem_hash=problem.integrity_hash,
        path_id="path",
        strategy_id="strategy",
        segment_index=19,
        current_goal="P",
    )

    default_bundle = PromptFactory().route_prove(problem, checkpoint=checkpoint)
    admitted_bundle = PromptFactory().route_prove(
        problem,
        checkpoint=checkpoint,
        authorized_output_tier=1,
    )

    assert default_bundle.output_tier == 0
    assert admitted_bundle.output_tier == 1


def test_new_route_starts_at_96k_instead_of_the_bounded_repair_tier() -> None:
    registry = DeepExplorationRegistry(
        DeepExplorationPolicyConfig(), problem_hash="problem-hash"
    )

    admission = registry.admit(
        _signature("initial abstract route"),
        route_id="route-a",
        round_index=0,
        evidence=_evidence(
            has_verified_checkpoint=False,
            meta_approved=False,
            referee_confirmed_mechanism_change=False,
        ),
    )

    assert admission.allowed
    assert admission.granted_tier == 1
    assert admission.max_output_tokens == 96000
    assert admission.recovery_only is False


def test_distinct_high_tier_signatures_run_in_parallel() -> None:
    registry = DeepExplorationRegistry(
        DeepExplorationPolicyConfig(), problem_hash="problem-hash"
    )

    admissions = [
        registry.admit(
            _signature(mechanism, route_id=f"route-{index}"),
            route_id=f"route-{index}",
            round_index=2,
            evidence=_evidence(),
            requested_tier=1,
        )
        for index, mechanism in enumerate(
            ["cyclotomic factorization", "graph parity", "descent invariant"]
        )
    ]

    assert all(item.allowed for item in admissions)
    assert all(item.max_output_tokens == 96000 for item in admissions)
    assert len(registry.running_by_signature) == 3


def test_same_signature_has_one_atomic_running_lease() -> None:
    registry = DeepExplorationRegistry(
        DeepExplorationPolicyConfig(), problem_hash="problem-hash"
    )
    signature = _signature("p-adic descent")

    first = registry.admit(
        signature,
        route_id="route-a",
        round_index=1,
        evidence=_evidence(),
        requested_tier=1,
    )
    second = registry.admit(
        signature,
        route_id="route-a",
        round_index=1,
        evidence=_evidence(),
        requested_tier=1,
    )

    assert first.allowed
    assert not second.allowed
    assert "running lease" in second.reason


def test_high_tier_no_progress_locks_only_that_mathematical_signature() -> None:
    registry = DeepExplorationRegistry(
        DeepExplorationPolicyConfig(), problem_hash="problem-hash"
    )
    stuck = _signature("same descent mechanism")
    first = registry.admit(
        stuck,
        route_id="route-a",
        round_index=3,
        evidence=_evidence(),
        requested_tier=1,
    )
    assert first.lease_id is not None
    registry.finish(
        first.lease_id,
        ExplorationOutcome.NO_ARTIFACT,
        usage=UsageRecord(output_tokens=84000, total_tokens=84000),
    )

    repair = registry.admit(
        stuck,
        route_id="route-a",
        round_index=4,
        evidence=_evidence(),
        requested_tier=2,
    )
    assert repair.allowed
    assert repair.recovery_only
    assert repair.max_output_tokens == 64000
    assert repair.lease_id is not None
    registry.finish(
        repair.lease_id,
        ExplorationOutcome.NO_VERIFIED_PROGRESS,
    )
    exhausted = registry.admit(
        stuck,
        route_id="route-a",
        round_index=5,
        evidence=_evidence(),
        requested_tier=2,
    )
    distinct = registry.admit(
        _signature("local generating-function pivot"),
        route_id="route-a",
        round_index=5,
        evidence=_evidence(),
        requested_tier=1,
    )

    assert not exhausted.allowed
    assert distinct.allowed
    assert distinct.max_output_tokens == 96000


def test_post_failure_lineage_gets_one_bounded_repair_across_reworded_targets() -> None:
    config = DeepExplorationPolicyConfig()
    registry = DeepExplorationRegistry(config, problem_hash="problem-hash")
    lineage = "post-failure:checkpoint-1:failure-a"
    first = _signature(
        "same stalled mechanism",
        target="Diagnose the missing local bridge.",
        recovery_lineage_id=lineage,
    )

    repair = registry.admit(
        first,
        route_id="route-a",
        round_index=4,
        evidence=_evidence(),
        requested_tier=1,
    )

    assert repair.allowed
    assert repair.recovery_only is True
    assert repair.max_output_tokens == config.partial_repair_max_output_tokens
    assert repair.lease_id is not None
    registry.finish(repair.lease_id, ExplorationOutcome.NO_ARTIFACT)
    registry = DeepExplorationRegistry.from_state(
        registry.export_state(),
        config,
        problem_hash="problem-hash",
    )

    reworded = _signature(
        "same stalled mechanism",
        target="Repair the unresolved local bridge using the public checkpoint.",
        recovery_lineage_id=lineage,
    )
    exhausted = registry.admit(
        reworded,
        route_id="route-a",
        round_index=5,
        evidence=_evidence(),
        requested_tier=1,
    )

    assert exhausted.allowed is False
    assert "recovery lineage" in exhausted.reason


def test_same_domain_subdirections_and_local_pivot_are_allowed() -> None:
    registry = DeepExplorationRegistry(
        DeepExplorationPolicyConfig(), problem_hash="problem-hash"
    )
    old = _signature("number theory via quadratic residues")
    old_admission = registry.admit(
        old,
        route_id="route-a",
        round_index=1,
        evidence=_evidence(),
        requested_tier=1,
    )
    assert old_admission.lease_id is not None
    registry.finish(old_admission.lease_id, ExplorationOutcome.NO_ARTIFACT)

    pivot_signature = _signature("number theory via infinite descent")
    pivot = registry.register_pivot(
        route_id="route-a",
        parent_signature_hash=old.signature_hash,
        new_signature=pivot_signature,
        referee_confirmed=True,
    )
    pivot_admission = registry.admit(
        pivot_signature,
        route_id="route-a",
        round_index=2,
        evidence=_evidence(),
        requested_tier=1,
    )

    assert pivot is not None
    assert pivot.checkpoint_hash == old.verified_checkpoint_hash
    assert pivot_admission.allowed
    assert pivot_admission.max_output_tokens == 96000


def test_128k_requires_96k_verified_progress_meta_approval_and_reserve() -> None:
    registry = DeepExplorationRegistry(
        DeepExplorationPolicyConfig(), problem_hash="problem-hash"
    )
    first = registry.admit(
        _signature("one continuing mechanism"),
        route_id="route-a",
        round_index=1,
        evidence=_evidence(),
        requested_tier=2,
    )
    assert first.max_output_tokens == 96000
    assert first.lease_id is not None
    registry.finish(
        first.lease_id,
        ExplorationOutcome.VERIFIED_PROGRESS,
        checkpoint_after_hash="checkpoint-2",
    )

    no_meta = registry.admit(
        _signature("one continuing mechanism", checkpoint_hash="checkpoint-2"),
        route_id="route-a",
        round_index=2,
        evidence=_evidence(meta_approved=False),
        requested_tier=2,
    )
    assert no_meta.max_output_tokens == 96000
    assert no_meta.lease_id is not None
    registry.finish(no_meta.lease_id, ExplorationOutcome.EXTERNAL_FAILURE)

    admitted = registry.admit(
        _signature("one continuing mechanism", checkpoint_hash="checkpoint-2"),
        route_id="route-a",
        round_index=2,
        evidence=_evidence(),
        requested_tier=2,
    )
    assert admitted.max_output_tokens == 128000


def test_new_mechanism_does_not_inherit_old_128k_progression() -> None:
    registry = DeepExplorationRegistry(
        DeepExplorationPolicyConfig(), problem_hash="problem-hash"
    )
    old_signature = _signature("old analytic mechanism")
    old = registry.admit(
        old_signature,
        route_id="route-a",
        round_index=1,
        evidence=_evidence(),
        requested_tier=1,
    )
    assert old.lease_id is not None
    registry.finish(
        old.lease_id,
        ExplorationOutcome.VERIFIED_PROGRESS,
        checkpoint_after_hash="checkpoint-2",
    )

    pivot_signature = _signature(
        "new combinatorial mechanism", checkpoint_hash="checkpoint-2"
    )
    pivot = registry.admit(
        pivot_signature,
        route_id="route-a",
        round_index=2,
        evidence=_evidence(),
    )

    assert pivot.max_output_tokens == 96000
    assert pivot.lease_id is not None
    registry.finish(
        pivot.lease_id,
        ExplorationOutcome.VERIFIED_MECHANISM_CHANGE,
        checkpoint_after_hash="checkpoint-3",
    )
    continued = registry.admit(
        _signature("new combinatorial mechanism", checkpoint_hash="checkpoint-3"),
        route_id="route-a",
        round_index=3,
        evidence=_evidence(),
    )
    assert continued.max_output_tokens == 128000


def test_resume_persists_strikes_pivots_and_route_usage() -> None:
    config = DeepExplorationPolicyConfig()
    registry = DeepExplorationRegistry(config, problem_hash="problem-hash")
    signature = _signature("stalled mechanism")
    admission = registry.admit(
        signature,
        route_id="route-a",
        round_index=1,
        evidence=_evidence(),
        requested_tier=1,
    )
    assert admission.lease_id is not None
    registry.finish(
        admission.lease_id,
        ExplorationOutcome.NO_ARTIFACT,
        usage=UsageRecord(output_tokens=84000, total_tokens=84000),
    )
    pivot_signature = _signature("verified alternate mechanism")
    registry.register_pivot(
        route_id="route-a",
        parent_signature_hash=signature.signature_hash,
        new_signature=pivot_signature,
        referee_confirmed=True,
    )

    restored = DeepExplorationRegistry.from_state(
        registry.export_state(),
        config,
        problem_hash="problem-hash",
    )

    assert restored.strikes[signature.signature_hash] == 1
    assert restored.locked_signatures[signature.signature_hash] == "no_artifact"
    assert restored.route_usage["route-a"].output_tokens == 84000
    assert len(restored.pivots) == 1
