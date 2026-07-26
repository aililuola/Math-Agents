from __future__ import annotations

from mathproofmesh.continuation import local_delta_verification
from mathproofmesh.memory import LemmaMemory
from mathproofmesh.orchestrator import ProofMeshOrchestrator
from mathproofmesh.schemas import (
    ClaimCard,
    ClaimStatus,
    ProblemContract,
    ProofCheckpoint,
    ProofDelta,
    ProofStep,
    VerificationVerdict,
)
from mathproofmesh.store import ArtifactStore


def _problem() -> ProblemContract:
    return ProblemContract(
        exact_statement="Prove the target relation.",
        normalized_statement="prove the target relation.",
    )


def _claim(
    claim_id: str,
    statement: str,
    *,
    status: ClaimStatus = ClaimStatus.PROPOSED,
    verification_confidence: float | None = None,
    dependencies: list[str] | None = None,
    source_attempt_id: str | None = None,
) -> ClaimCard:
    return ClaimCard(
        claim_id=claim_id,
        statement=statement,
        conclusion=statement,
        dependencies=dependencies or [],
        status=status,
        verification_confidence=verification_confidence,
        source_attempt_id=source_attempt_id,
    )


def test_delta_guard_accepts_prompt_shared_dependencies() -> None:
    problem = _problem()
    checkpoint = ProofCheckpoint(
        checkpoint_id="ckpt-1",
        problem_hash=problem.integrity_hash,
        path_id="path-a",
        strategy_id="strategy-a",
        segment_index=0,
    )
    delta = ProofDelta(
        problem_hash=problem.integrity_hash,
        path_id="path-a",
        strategy_id="strategy-a",
        parent_checkpoint_id="ckpt-1",
        agent_id="explorer-a",
        round_index=1,
        segment_index=1,
        new_steps=[
            ProofStep(
                step_id="s1",
                statement="Apply the shared lemma.",
                justification="Uses a cross-route verified lemma.",
                dependencies=["claim-shared-1"],
            )
        ],
    )

    rejected = local_delta_verification(problem, checkpoint, delta)
    assert rejected.verdict == VerificationVerdict.FAIL

    accepted = local_delta_verification(
        problem,
        checkpoint,
        delta,
        shared_dependency_ids={"claim-shared-1"},
    )
    assert accepted.verdict == VerificationVerdict.PASS


def test_add_many_aliases_duplicates_and_upgrades_with_evidence(tmp_path) -> None:
    store = ArtifactStore(tmp_path / "runs", "alias-upgrade")
    memory = LemmaMemory(store)
    first = _claim("claim-original", "Shared statement.")
    memory.add_many([first])

    duplicate = _claim(
        "claim-duplicate",
        "Shared statement.",
        status=ClaimStatus.VERIFIED,
        verification_confidence=0.9,
    )
    memory.add_many([duplicate])

    assert memory.resolve_claim_id("claim-duplicate") == "claim-original"
    canonical = next(
        claim for claim in memory.claims if claim.claim_id == "claim-original"
    )
    assert canonical.status == ClaimStatus.VERIFIED
    assert canonical.verification_confidence == 0.9


def test_mark_claim_checkpoint_verified_unknown_id_is_recorded_noop(
    tmp_path,
) -> None:
    store = ArtifactStore(tmp_path / "runs", "unknown-claim")
    memory = LemmaMemory(store)
    result = memory.mark_claim_checkpoint_verified(
        "claim-never-registered",
        report_ids=["report-1"],
        confidence=0.9,
        independent=True,
    )
    assert result is None


def test_rejected_claim_is_not_resurrected_by_checkpoint_bookkeeping(
    tmp_path,
) -> None:
    store = ArtifactStore(tmp_path / "runs", "no-resurrection")
    memory = LemmaMemory(store)
    claim = _claim("claim-refuted", "A false statement.")
    memory.add_many([claim])
    lifecycle = memory._claim_lifecycle
    lifecycle.invalidate_claim(
        "claim-refuted",
        reason="exact_counterexample",
        evidence_ids=["evidence-1"],
    )

    survived = memory.mark_claim_checkpoint_verified(
        "claim-refuted",
        report_ids=["report-2"],
        confidence=0.95,
        independent=True,
    )
    assert survived is not None
    assert survived.status != ClaimStatus.VERIFIED


def test_committed_step_ids_keep_cross_segment_claims_valid(tmp_path) -> None:
    store = ArtifactStore(tmp_path / "runs", "committed-steps")
    memory = LemmaMemory(store)
    claim = _claim(
        "claim-cross-segment",
        "Uses an earlier committed step.",
        status=ClaimStatus.VERIFIED,
        verification_confidence=0.9,
        dependencies=["step:committed-step-7"],
    )
    memory.register_committed_step_ids(["committed-step-7"])
    memory.add_many([claim])
    memory.mark_claim_checkpoint_verified(
        "claim-cross-segment",
        report_ids=["report-3"],
        confidence=0.9,
        independent=True,
    )
    assert any(item.claim_id == "claim-cross-segment" for item in memory.verified())


def test_claim_alias_and_committed_step_registry_round_trip(tmp_path) -> None:
    store = ArtifactStore(tmp_path / "runs", "memory-runtime-round-trip")
    memory = LemmaMemory(store)
    memory.add_many(
        [
            _claim("claim-original", "Shared persisted statement."),
            _claim("claim-alias", "Shared persisted statement."),
        ]
    )
    memory.register_committed_step_ids(["committed-step-9"])
    memory.add_many(
        [
            _claim(
                "claim-cross-segment",
                "Persisted claim using an earlier step.",
                status=ClaimStatus.VERIFIED,
                verification_confidence=0.9,
                dependencies=["step:committed-step-9"],
            )
        ]
    )

    runtime_state = store.read_named_json("structured", "lemma_memory_runtime")
    restored = LemmaMemory(store)
    restored.add_many(
        ClaimCard.model_validate(item)
        for item in store.read_named_json("structured", "lemma_memory")
    )
    restored.restore_runtime_state(runtime_state)

    assert restored.resolve_claim_id("claim-alias") == "claim-original"
    assert any(item.claim_id == "claim-cross-segment" for item in restored.verified())


def test_hard_constraint_violation_detection() -> None:
    problem = ProblemContract(
        exact_statement="Prove the periodicity claim.",
        normalized_statement="prove the periodicity claim.",
        hard_constraints=["不得引用狄利克雷定理，必须给出初等证明。"],
    )
    hits = ProofMeshOrchestrator._hard_constraint_violations(
        problem,
        ["external:狄利克雷定理 (Dirichlet)"],
    )
    assert hits
    clean = ProofMeshOrchestrator._hard_constraint_violations(
        problem,
        ["external:中国剩余定理"],
    )
    assert not clean
