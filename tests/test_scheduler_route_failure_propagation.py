from __future__ import annotations

from mathproofmesh.communication.route_registry import RouteRegistry
from mathproofmesh.orchestrator import ProofMeshOrchestrator, SolveState
from mathproofmesh.schemas import (
    ActionKind,
    AttemptStatus,
    CandidateAssessment,
    FailureLevel,
    MetaReview,
    ProofAttempt,
    ProofDelta,
    ProofStep,
    RouteStatus,
    Severity,
    StrategyCard,
    VerificationIssue,
    VerificationReport,
    VerificationStage,
    VerificationVerdict,
)


def _strategy() -> StrategyCard:
    return StrategyCard(
        strategy_id="strategy-a",
        title="Route A",
        core_idea="Establish the target through a local implication.",
        independence_basis="Uses a distinct local implication.",
        expected_lemmas=["local implication"],
        bottleneck="justify the implication",
        falsification_test="check the implication independently",
        estimated_success=0.5,
        tags=["local-implication"],
    )


def _state(config, strategy: StrategyCard) -> SolveState:
    attempt = ProofAttempt(
        attempt_id="attempt-a",
        problem_hash="problem-hash",
        strategy_id=strategy.strategy_id,
        agent_id="prover-a",
        round_index=1,
        status=AttemptStatus.PARTIAL,
        unresolved_gaps=["justify the implication"],
    )
    registry = RouteRegistry(config, problem_hash="problem-hash")
    registry.register_route(strategy, route_id="route-a")
    return SolveState(
        triage=None,
        strategies=[strategy],
        attempts=[attempt],
        reports=[],
        aggregate_reports={},
        meta_reviews=[],
        checkpoints=[],
        route_registry=registry,
        route_team_reviews={},
    )


def test_checkpoint_rejection_is_persisted_at_route_scope(
    demo_config,
    artifact_store,
) -> None:
    strategy = _strategy()
    state = _state(demo_config, strategy)
    delta = ProofDelta(
        delta_id="delta-a",
        problem_hash="problem-hash",
        path_id="path-a",
        strategy_id=strategy.strategy_id,
        parent_checkpoint_id="checkpoint-a",
        agent_id="prover-a",
        round_index=1,
        segment_index=1,
        new_steps=[
            ProofStep(
                step_id="step-a",
                statement="The local implication holds.",
                justification="A proposed but invalid argument.",
            )
        ],
    )
    report = VerificationReport(
        target_id=delta.delta_id,
        target_type="proof_delta",
        agent_id="reviewer-a",
        stage=VerificationStage.DETAILED,
        verdict=VerificationVerdict.FAIL,
        first_error_step="step-a",
        issues=[
            VerificationIssue(
                phase="detailed",
                severity=Severity.ERROR,
                step_id="step-a",
                description="The implication does not follow.",
            )
        ],
        failure_level=FailureLevel.PLAN,
        confidence=0.97,
        concise_feedback="Replace the rejected implication.",
    )

    ProofMeshOrchestrator(demo_config)._record_route_checkpoint_outcome(
        state,
        attempt_id="attempt-a",
        delta=delta,
        reports=[report],
        accepted=False,
        feedback=report.concise_feedback,
        store=artifact_store,
    )

    summaries = state.route_team_reviews["attempt-a"]
    assert len(summaries) == 1
    assert summaries[0]["route_id"] == "route-a"
    assert summaries[0]["delta_id"] == "delta-a"
    assert summaries[0]["checkpoint_status"] == "rejected"
    assert summaries[0]["checkpoint_failure_level"] == "plan"
    assert summaries[0]["checkpoint_first_error_step"] == "step-a"


def test_authoritative_meta_stop_cools_route_and_requires_revision(
    demo_config,
    artifact_store,
) -> None:
    strategy = _strategy()
    state = _state(demo_config, strategy)
    review = MetaReview(
        selected_target_id=None,
        assessments=[
            CandidateAssessment(
                target_id="attempt-a",
                score=0.05,
                weaknesses=["The route depends on a rejected implication."],
                recommended_action=ActionKind.STOP,
            )
        ],
        failure_level=FailureLevel.PLAN,
        can_synthesize=False,
        confidence=0.99,
        summary="Stop this mechanism and revise before reuse.",
    )

    ProofMeshOrchestrator(demo_config)._apply_meta_route_controls(
        state,
        review,
        current_round=3,
        store=artifact_store,
    )

    route = state.route_registry.get("route-a")
    assert route.status == RouteStatus.COOLING
    assert route.requires_revision is True
    assert route.cooldown_until_round > 3


def test_low_confidence_meta_stop_does_not_mutate_route_control(
    demo_config,
    artifact_store,
) -> None:
    strategy = _strategy()
    state = _state(demo_config, strategy)
    review = MetaReview(
        assessments=[
            CandidateAssessment(
                target_id="attempt-a",
                score=0.4,
                weaknesses=["The evidence is still ambiguous."],
                recommended_action=ActionKind.STOP,
            )
        ],
        failure_level=FailureLevel.EXECUTION,
        can_synthesize=False,
        confidence=0.2,
        summary="Tentative stop recommendation.",
    )

    ProofMeshOrchestrator(demo_config)._apply_meta_route_controls(
        state,
        review,
        current_round=3,
        store=artifact_store,
    )

    route = state.route_registry.get("route-a")
    assert route.status == RouteStatus.ACTIVE
    assert route.requires_revision is False
