from __future__ import annotations

from mathproofmesh.orchestrator import ProofMeshOrchestrator, SolveState
from mathproofmesh.schemas import (
    ActionKind,
    AttemptStatus,
    CandidateAssessment,
    ClaimCard,
    ClaimStatus,
    FailureLevel,
    MetaReview,
    ProblemContract,
    ProofAttempt,
    ProofStep,
    Severity,
    ToolResult,
    VerificationIssue,
    VerificationReport,
    VerificationStage,
    VerificationVerdict,
)


def _failed_synthesis_state(
    failure_level: FailureLevel,
    *,
    can_synthesize: bool = True,
    recommended_action: ActionKind = ActionKind.SYNTHESIZE,
) -> tuple[ProofAttempt, SolveState]:
    attempt = ProofAttempt(
        attempt_id="repairable-attempt",
        problem_hash="problem-hash",
        strategy_id="strategy",
        agent_id="explorer",
        round_index=0,
        status=AttemptStatus.PARTIAL,
        proof_steps=[
            ProofStep(
                step_id="verified-step",
                statement="A verified intermediate result.",
                justification="Established at the committed checkpoint.",
            )
        ],
        unresolved_gaps=["State the final implication explicitly."],
    )
    report = VerificationReport(
        target_id=attempt.attempt_id,
        target_type="attempt",
        agent_id="system-aggregate",
        stage=VerificationStage.DETAILED,
        verdict=VerificationVerdict.FAIL,
        issues=[
            VerificationIssue(
                phase="completeness",
                severity=Severity.ERROR,
                description="The final implication is not stated.",
            )
        ],
        failure_level=failure_level,
        confidence=0.95,
        concise_feedback="The supported route needs a local final repair.",
    )
    review = MetaReview(
        selected_target_id=attempt.attempt_id,
        assessments=[
            CandidateAssessment(
                target_id=attempt.attempt_id,
                score=0.9,
                recommended_action=recommended_action,
            )
        ],
        can_synthesize=can_synthesize,
        confidence=0.95,
        summary="The verified intermediate steps can support a final audited repair.",
    )
    state = SolveState(
        triage=None,
        strategies=[],
        attempts=[attempt],
        reports=[],
        aggregate_reports={attempt.attempt_id: report},
        meta_reviews=[review],
        checkpoints=[],
    )
    return attempt, state


def test_problem_hash_guard_overrides_a_model_pass(demo_config) -> None:
    orchestrator = ProofMeshOrchestrator(demo_config)
    problem = ProblemContract(
        exact_statement="Prove P.", normalized_statement="Prove P."
    )
    attempt = ProofAttempt(
        problem_hash="wrong-hash",
        strategy_id="s",
        agent_id="a",
        round_index=0,
        status=AttemptStatus.COMPLETE,
        final_answer="P",
    )
    report = VerificationReport(
        target_id=attempt.attempt_id,
        target_type="attempt",
        agent_id="v",
        stage=VerificationStage.STRUCTURAL,
        verdict=VerificationVerdict.PASS,
        confidence=0.99,
        concise_feedback="looks good",
    )
    orchestrator._apply_local_attempt_integrity_guard(problem, attempt, report)
    assert report.verdict == VerificationVerdict.FAIL
    assert report.problem_integrity_ok is False
    assert report.issues


def test_deterministic_counterexample_overrides_a_model_pass(demo_config) -> None:
    orchestrator = ProofMeshOrchestrator(demo_config)
    report = VerificationReport(
        target_id="x",
        target_type="attempt",
        agent_id="v",
        stage=VerificationStage.DETAILED,
        verdict=VerificationVerdict.PASS,
        confidence=0.99,
        concise_feedback="passed",
        tool_results=[
            ToolResult(
                request_id="r",
                kind="numeric_counterexample",
                ok=True,
                result={"counterexample_found": True, "assignment": {"x": "2"}},
            )
        ],
    )
    orchestrator._apply_deterministic_tool_guard(report)
    assert report.verdict == VerificationVerdict.FAIL
    assert report.first_error_step == "deterministic_tool_check"


def test_claim_context_keeps_dependency_closure(demo_config) -> None:
    orchestrator = ProofMeshOrchestrator(demo_config)
    base = ClaimCard(
        claim_id="base",
        statement="base algebra fact",
        conclusion="A",
        status=ClaimStatus.VERIFIED,
        verification_confidence=0.9,
    )
    derived = ClaimCard(
        claim_id="derived",
        statement="target theorem from A",
        conclusion="B",
        dependencies=["base"],
        status=ClaimStatus.VERIFIED,
        verification_confidence=0.95,
    )
    packet = orchestrator._select_claim_context(
        [base, derived], "target theorem", max_chars=10000
    )
    assert [item["claim_id"] for item in packet] == ["base", "derived"]


def test_meta_selected_execution_failure_can_enter_final_audited_repair(
    demo_config,
) -> None:
    orchestrator = ProofMeshOrchestrator(demo_config)
    attempt, state = _failed_synthesis_state(FailureLevel.EXECUTION)

    assert orchestrator._select_for_synthesis(state) == [attempt]


def test_strategy_failure_cannot_enter_synthesis_even_when_meta_requests_it(
    demo_config,
) -> None:
    orchestrator = ProofMeshOrchestrator(demo_config)
    _, state = _failed_synthesis_state(FailureLevel.STRATEGY)

    assert orchestrator._select_for_synthesis(state) == []


def test_execution_failure_requires_explicit_meta_synthesis_approval(
    demo_config,
) -> None:
    orchestrator = ProofMeshOrchestrator(demo_config)
    _, state = _failed_synthesis_state(
        FailureLevel.EXECUTION,
        recommended_action=ActionKind.DEEPEN,
    )

    assert orchestrator._select_for_synthesis(state) == []


def test_execution_failure_with_a_broken_step_cannot_enter_synthesis(
    demo_config,
) -> None:
    orchestrator = ProofMeshOrchestrator(demo_config)
    _, state = _failed_synthesis_state(FailureLevel.EXECUTION)
    report = state.aggregate_reports["repairable-attempt"]
    report.first_error_step = "verified-step"
    report.issues[0].phase = "deduction"

    assert orchestrator._select_for_synthesis(state) == []
