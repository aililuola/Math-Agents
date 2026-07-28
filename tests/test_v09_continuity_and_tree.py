from __future__ import annotations

from mathproofmesh.continuation import (
    local_delta_verification,
    make_genesis_checkpoint,
    merge_verified_delta,
)
from mathproofmesh.orchestrator import ProofMeshOrchestrator
from mathproofmesh.schemas import (
    ProblemContract,
    ProofDelta,
    ProofStep,
    StrategyCard,
    VerificationReport,
    VerificationStage,
    VerificationVerdict,
)
from mathproofmesh.store import ArtifactStore


def _problem() -> ProblemContract:
    return ProblemContract(
        exact_statement="Prove the target relation.",
        normalized_statement="prove the target relation.",
    )


def _strategy() -> StrategyCard:
    return StrategyCard(
        strategy_id="strategy-tree",
        title="Tree route",
        core_idea="Reduce to residue classes and stabilize.",
        independence_basis="Distinct mechanism.",
        expected_lemmas=["The residue classes stabilize.", "The period exists."],
        bottleneck="The stabilization argument.",
        falsification_test="check x in [0, 3]: x + 0 == x",
        estimated_success=0.6,
        tags=["tree"],
    )


def _pass_report(target_id: str) -> VerificationReport:
    return VerificationReport(
        target_id=target_id,
        target_type="proof_delta",
        agent_id="verifier-x",
        stage=VerificationStage.DETAILED,
        problem_integrity_ok=True,
        verdict=VerificationVerdict.PASS,
        checked_dependencies=["s1"],
        confidence=0.9,
        concise_feedback="ok",
    )


def _delta(
    checkpoint,
    problem,
    *,
    working_notes: str = "",
    active_assumptions=None,
    remaining_subgoals=None,
    completed_subgoal=None,
) -> ProofDelta:
    return ProofDelta(
        problem_hash=problem.integrity_hash,
        path_id=checkpoint.path_id,
        strategy_id=checkpoint.strategy_id,
        parent_checkpoint_id=checkpoint.checkpoint_id,
        agent_id="explorer-a",
        round_index=1,
        segment_index=checkpoint.segment_index + 1,
        new_steps=[
            ProofStep(
                step_id="s1",
                statement="First stabilization step.",
                justification="Direct argument.",
            )
        ],
        completed_subgoal=completed_subgoal,
        remaining_subgoals=(
            checkpoint.remaining_subgoals
            if remaining_subgoals is None
            else remaining_subgoals
        ),
        active_assumptions=active_assumptions,
        working_notes=working_notes,
    )


def test_working_notes_and_sketch_carry_across_segments() -> None:
    problem = _problem()
    checkpoint = make_genesis_checkpoint(
        problem, _strategy(), proof_sketch="Route map: stabilize, then pigeonhole."
    )
    delta = _delta(checkpoint, problem, working_notes="Next: bound the gaps.")
    merged = merge_verified_delta(checkpoint, delta, [_pass_report(delta.delta_id)])
    assert merged.proof_sketch.startswith("Route map")
    assert merged.working_notes == "Next: bound the gaps."

    second = _delta(merged, problem, working_notes="")
    second = second.model_copy(
        update={"new_steps": [second.new_steps[0].model_copy(update={"step_id": "s2"})]}
    )
    merged_again = merge_verified_delta(merged, second, [_pass_report(second.delta_id)])
    assert merged_again.working_notes == "Next: bound the gaps."


def test_empty_assumption_list_discharges_and_none_inherits() -> None:
    problem = _problem()
    checkpoint = make_genesis_checkpoint(problem, _strategy())
    checkpoint = checkpoint.model_copy(
        update={"active_assumptions": ["assume d greater than 1"]}
    )
    inherited = merge_verified_delta(
        checkpoint,
        _delta(checkpoint, problem, active_assumptions=None),
        [_pass_report("d1")],
    )
    assert inherited.active_assumptions == ["assume d greater than 1"]

    discharged = merge_verified_delta(
        checkpoint,
        _delta(checkpoint, problem, active_assumptions=[]),
        [_pass_report("d2")],
    )
    assert discharged.active_assumptions == []


def test_silent_subgoal_drop_is_flagged() -> None:
    problem = _problem()
    checkpoint = make_genesis_checkpoint(problem, _strategy())
    delta = _delta(
        checkpoint,
        problem,
        remaining_subgoals=[],
        completed_subgoal=None,
    )
    report = local_delta_verification(problem, checkpoint, delta)
    assert report.verdict == VerificationVerdict.FAIL
    assert any(
        "removed remaining subgoals" in issue.description for issue in report.issues
    )

    accounted = _delta(
        checkpoint,
        problem,
        remaining_subgoals=[checkpoint.remaining_subgoals[1]],
        completed_subgoal=checkpoint.remaining_subgoals[0],
    )
    report = local_delta_verification(problem, checkpoint, accounted)
    assert report.verdict == VerificationVerdict.PASS

    partially_accounted = _delta(
        checkpoint,
        problem,
        remaining_subgoals=[],
        completed_subgoal=checkpoint.remaining_subgoals[0],
    )
    report = local_delta_verification(problem, checkpoint, partially_accounted)
    assert report.verdict == VerificationVerdict.FAIL
    assert any(
        checkpoint.remaining_subgoals[1].casefold() in issue.description.casefold()
        for issue in report.issues
    )


def test_rollback_restores_parent_checkpoint(tmp_path) -> None:
    problem = _problem()
    store = ArtifactStore(tmp_path / "runs", "rollback-test")
    genesis = make_genesis_checkpoint(problem, _strategy())
    store.commit_proof_checkpoint(genesis)
    delta = _delta(genesis, problem)
    child = merge_verified_delta(genesis, delta, [_pass_report(delta.delta_id)])
    store.commit_proof_checkpoint(child)

    restored = store.rollback_proof_checkpoint(
        genesis.path_id, reason="author reported a contradiction"
    )
    assert restored is not None
    assert restored.checkpoint_id == genesis.checkpoint_id
    latest = store.load_latest_proof_checkpoint(genesis.path_id)
    assert latest is not None
    assert latest.checkpoint_id == genesis.checkpoint_id
    assert store.list_proof_checkpoints(genesis.path_id) == [genesis]
    assert store.list_proof_checkpoints(genesis.path_id, include_abandoned=True) == [
        genesis,
        child,
    ]

    at_genesis = store.rollback_proof_checkpoint(
        genesis.path_id, reason="cannot roll back genesis"
    )
    assert at_genesis is None


def test_checkpoint_frontier_supports_arbitrary_branch_activation(tmp_path) -> None:
    problem = _problem()
    store = ArtifactStore(tmp_path / "runs", "frontier-test")
    genesis = make_genesis_checkpoint(problem, _strategy())
    store.commit_proof_checkpoint(genesis)

    delta_a = _delta(genesis, problem, working_notes="branch a")
    child_a = merge_verified_delta(genesis, delta_a, [_pass_report(delta_a.delta_id)])
    store.commit_proof_checkpoint(child_a)
    store.rollback_proof_checkpoint(genesis.path_id, reason="try second branch")

    delta_b = _delta(
        genesis,
        problem,
        working_notes="branch b",
        completed_subgoal=genesis.remaining_subgoals[0],
        remaining_subgoals=[genesis.remaining_subgoals[1]],
    )
    delta_b = delta_b.model_copy(
        update={
            "new_steps": [
                delta_b.new_steps[0].model_copy(update={"step_id": "s-branch-b"})
            ]
        }
    )
    child_b = merge_verified_delta(genesis, delta_b, [_pass_report(delta_b.delta_id)])
    store.commit_proof_checkpoint(child_b)

    frontier = store.list_proof_checkpoint_frontier(genesis.path_id)
    assert {item.checkpoint_id for item in frontier} == {
        child_a.checkpoint_id,
        child_b.checkpoint_id,
    }
    assert (
        store.select_best_proof_checkpoint(genesis.path_id).checkpoint_id
        == child_b.checkpoint_id
    )

    activated = store.activate_proof_checkpoint(
        genesis.path_id,
        child_a.checkpoint_id,
        reason="inspect alternate verified branch",
    )
    assert activated.checkpoint_id == child_a.checkpoint_id
    assert (
        store.load_latest_proof_checkpoint(genesis.path_id).checkpoint_id
        == child_a.checkpoint_id
    )
    assert {item.checkpoint_id for item in store.list_proof_checkpoints()} == {
        genesis.checkpoint_id,
        child_a.checkpoint_id,
    }


def test_checkpoint_rollback_requires_independent_step_confirmation() -> None:
    problem = _problem()
    genesis = make_genesis_checkpoint(problem, _strategy())
    delta = _delta(genesis, problem)
    child = merge_verified_delta(genesis, delta, [_pass_report(delta.delta_id)])

    missing_step = _pass_report("conflict-delta")
    missing_step.first_error_step = None
    author_only = _pass_report("conflict-delta")
    author_only.agent_id = "explorer-a"
    author_only.first_error_step = "s1"
    wrong_step = _pass_report("conflict-delta")
    wrong_step.first_error_step = "genesis-step"
    confirmed = _pass_report("conflict-delta")
    confirmed.first_error_step = "s1"

    assert not ProofMeshOrchestrator._checkpoint_rollback_confirmed(
        child,
        genesis,
        author_id="explorer-a",
        reports=[],
        confidence_threshold=0.8,
    )
    assert not ProofMeshOrchestrator._checkpoint_rollback_confirmed(
        child,
        genesis,
        author_id="explorer-a",
        reports=[author_only],
        confidence_threshold=0.8,
    )
    assert not ProofMeshOrchestrator._checkpoint_rollback_confirmed(
        child,
        genesis,
        author_id="explorer-a",
        reports=[missing_step],
        confidence_threshold=0.8,
    )
    assert not ProofMeshOrchestrator._checkpoint_rollback_confirmed(
        child,
        genesis,
        author_id="explorer-a",
        reports=[wrong_step],
        confidence_threshold=0.8,
    )
    assert ProofMeshOrchestrator._checkpoint_rollback_confirmed(
        child,
        genesis,
        author_id="explorer-a",
        reports=[confirmed],
        confidence_threshold=0.8,
    )


def test_proof_step_scope_typing_defaults() -> None:
    step = ProofStep(
        step_id="s1",
        statement="Assume for contradiction that d is unbounded.",
        justification="Contradiction scaffold.",
        step_type="assumption_intro",
        branch_label="contradiction",
    )
    assert step.step_type == "assumption_intro"
    default_step = ProofStep(
        step_id="s2", statement="Then d is bounded.", justification="Follows."
    )
    assert default_step.step_type == "derivation"
