from __future__ import annotations

from mathproofmesh.orchestrator import ProofMeshOrchestrator
from mathproofmesh.schemas import (
    AttemptStatus,
    ClaimCard,
    ClaimStatus,
    ProblemContract,
    ProofAttempt,
    ToolResult,
    VerificationReport,
    VerificationStage,
    VerificationVerdict,
)


def test_problem_hash_guard_overrides_a_model_pass(demo_config) -> None:
    orchestrator = ProofMeshOrchestrator(demo_config)
    problem = ProblemContract(exact_statement="Prove P.", normalized_statement="Prove P.")
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
