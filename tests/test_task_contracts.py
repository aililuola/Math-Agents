from __future__ import annotations

from types import SimpleNamespace

from mathproofmesh.schemas import (
    AttemptStatus,
    CandidateConjecture,
    ProblemContract,
    ProofAttempt,
    TaskRequirement,
    TaskStatus,
)
from mathproofmesh.task_contracts import (
    apply_task_contract,
    assess_task_deliverables,
    infer_task_requirements,
)


def test_compute_then_conjecture_does_not_silently_require_a_proof() -> None:
    statement = "先定向计算前12项，再据此提出一个必须另行证明的候选规律。"

    requirements = infer_task_requirements(statement)

    assert requirements == [
        TaskRequirement.COMPUTATION,
        TaskRequirement.CONJECTURE,
    ]


def test_explicit_proof_and_solution_requests_keep_strict_verification() -> None:
    assert infer_task_requirements("求方程的所有解，并证明没有遗漏。") == [
        TaskRequirement.SOLUTION,
        TaskRequirement.PROOF,
    ]
    assert infer_task_requirements("证明该命题成立。") == [TaskRequirement.PROOF]


def test_scoped_conjecture_can_complete_the_task_without_becoming_verified() -> None:
    statement = "先定向计算前12项，再据此提出一个必须另行证明的候选规律。"
    problem = ProblemContract(
        exact_statement=statement,
        normalized_statement=statement,
    )
    apply_task_contract(problem)
    candidate = CandidateConjecture(
        statement="a_n=2n+4",
        rationale="The first twelve exact values have this form.",
        supporting_experiment_ids=["experiment-prefix"],
        scope_limitations=["A finite prefix is not an infinite proof."],
        proof_obligations=["Prove the formula for every positive integer n."],
    )
    attempt = ProofAttempt(
        problem_hash=problem.integrity_hash,
        strategy_id="prefix",
        agent_id="explorer-a",
        round_index=0,
        status=AttemptStatus.PARTIAL,
        candidate_conjectures=[candidate],
    )
    state = SimpleNamespace(
        final_verification=None,
        final_proof=None,
        attempts=[attempt],
        research_progress_report=None,
    )
    experiment = {
        "experiment_id": "experiment-prefix",
        "request_hash": "request-prefix",
        "outcome": "not_refuted",
        "error": None,
        "independently_verified": False,
    }

    task_status, assessments = assess_task_deliverables(
        problem,
        state,
        [experiment],
        verification_threshold=0.9,
    )

    assert task_status == TaskStatus.COMPLETED
    assert [item.status.value for item in assessments] == [
        "completed",
        "completed",
    ]
