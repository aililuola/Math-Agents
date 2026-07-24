from __future__ import annotations

import re
from typing import Any, Iterable, Sequence

from .schemas import (
    DeliverableAssessment,
    DeliverableStatus,
    ExperimentOutcome,
    ProblemContract,
    ProblemKind,
    TaskRequirement,
    TaskStatus,
    TriageResult,
    VerificationVerdict,
)


_PROOF_MARKERS = (
    "证明",
    "求证",
    "prove",
    "show that",
    "demonstrate that",
)
_DEFERRED_PROOF_MARKERS = (
    "另行证明",
    "无需证明",
    "不要求证明",
    "只提出猜想",
    "尚不证明",
    "must be proved separately",
    "separate proof obligation",
    "without proving",
    "no proof required",
)
_SOLUTION_MARKERS = (
    "求解",
    "解方程",
    "解不等式",
    "所有解",
    "全部解",
    "solve",
    "find all solutions",
    "determine all solutions",
)
_COMPUTATION_MARKERS = (
    "计算",
    "求值",
    "数值近似",
    "列出",
    "前几项",
    "定向计算",
    "compute",
    "calculate",
    "evaluate",
    "numerical approximation",
    "first terms",
)
_CONJECTURE_MARKERS = (
    "猜想",
    "候选规律",
    "提出规律",
    "conjecture",
    "hypothesis",
    "candidate pattern",
)
_COUNTEREXAMPLE_MARKERS = (
    "反例",
    "证伪",
    "counterexample",
    "falsify",
    "disprove by example",
)
_CLASSIFICATION_MARKERS = (
    "分类",
    "分情况列出",
    "classify",
    "classification",
)
_OPTIMIZATION_MARKERS = (
    "最大值",
    "最小值",
    "极值",
    "最优",
    "maximum",
    "minimum",
    "extremum",
    "optimal",
    "optimize",
)
_CONSTRUCTION_MARKERS = (
    "构造",
    "给出一个例子",
    "construct",
    "construction",
    "exhibit",
)


def _contains_any(text: str, markers: Iterable[str]) -> bool:
    return any(marker in text for marker in markers)


def infer_task_requirements(
    statement: str,
    problem_kind: ProblemKind = ProblemKind.UNKNOWN,
) -> list[TaskRequirement]:
    """Infer requested artifacts, without turning every math task into a proof."""

    text = re.sub(r"\s+", " ", statement.casefold()).strip()
    requirements: list[TaskRequirement] = []

    def add(requirement: TaskRequirement) -> None:
        if requirement not in requirements:
            requirements.append(requirement)

    proof_deferred = _contains_any(text, _DEFERRED_PROOF_MARKERS)
    if _contains_any(text, _SOLUTION_MARKERS):
        add(TaskRequirement.SOLUTION)
    if _contains_any(text, _COMPUTATION_MARKERS) or re.search(
        r"(?:前|first)\s*\d+\s*(?:项|terms?)", text
    ):
        add(TaskRequirement.COMPUTATION)
    if _contains_any(text, _CONJECTURE_MARKERS):
        add(TaskRequirement.CONJECTURE)
    if _contains_any(text, _COUNTEREXAMPLE_MARKERS):
        add(TaskRequirement.COUNTEREXAMPLE)
    if _contains_any(text, _CLASSIFICATION_MARKERS):
        add(TaskRequirement.CLASSIFICATION)
    if _contains_any(text, _OPTIMIZATION_MARKERS):
        add(TaskRequirement.OPTIMIZATION)
    if _contains_any(text, _CONSTRUCTION_MARKERS):
        add(TaskRequirement.CONSTRUCTION)
    if _contains_any(text, _PROOF_MARKERS) and not proof_deferred:
        add(TaskRequirement.PROOF)

    if requirements:
        return requirements

    fallback = {
        ProblemKind.PROOF: TaskRequirement.PROOF,
        ProblemKind.CALCULATION: TaskRequirement.COMPUTATION,
        ProblemKind.OPTIMIZATION: TaskRequirement.OPTIMIZATION,
        ProblemKind.CONSTRUCTION: TaskRequirement.CONSTRUCTION,
        ProblemKind.RESEARCH: TaskRequirement.RESEARCH_PROGRESS,
        ProblemKind.LOGIC: TaskRequirement.PROOF,
        ProblemKind.MIXED: TaskRequirement.PROOF,
        ProblemKind.UNKNOWN: TaskRequirement.PROOF,
    }
    return [fallback[problem_kind]]


def deliverable_instructions(
    requirements: Sequence[TaskRequirement],
) -> list[str]:
    instructions = {
        TaskRequirement.PROOF: (
            "Give a complete auditable proof of the frozen mathematical claim."
        ),
        TaskRequirement.SOLUTION: (
            "Give the requested solution set and justify validity, completeness, "
            "and absence of extraneous solutions."
        ),
        TaskRequirement.COMPUTATION: (
            "Return the requested computed values with an auditable deterministic "
            "calculation record and the exact finite scope."
        ),
        TaskRequirement.CONJECTURE: (
            "State a concrete falsifiable conjecture, its bounded evidence, scope "
            "limitations, and a separate proof obligation; do not claim it is proved."
        ),
        TaskRequirement.COUNTEREXAMPLE: (
            "Return an independently checked counterexample that satisfies the "
            "hypotheses and violates the target conclusion."
        ),
        TaskRequirement.CLASSIFICATION: (
            "Return the complete requested classification with coverage and "
            "mutual-exclusion justification."
        ),
        TaskRequirement.OPTIMIZATION: (
            "Return the optimum, attainment conditions, and a global optimality "
            "argument."
        ),
        TaskRequirement.CONSTRUCTION: (
            "Return a construction and verify every requested property."
        ),
        TaskRequirement.RESEARCH_PROGRESS: (
            "Return an auditable research-progress report that separates established "
            "facts, candidates, refutations, and open obligations."
        ),
    }
    return [instructions[item] for item in requirements]


def apply_task_contract(
    problem: ProblemContract,
    triage: TriageResult | None = None,
) -> list[TaskRequirement]:
    """Freeze the requested outputs after triage while preserving explicit wording."""

    kind = triage.problem_kind if triage is not None else problem.problem_kind
    inferred = infer_task_requirements(problem.exact_statement, kind)
    if (
        inferred == [TaskRequirement.PROOF]
        and triage is not None
        and triage.task_requirements
        and not _contains_any(problem.exact_statement.casefold(), _PROOF_MARKERS)
    ):
        inferred = list(dict.fromkeys(triage.task_requirements))
    problem.task_requirements = inferred
    problem.deliverables = deliverable_instructions(inferred)
    return inferred


def assess_task_deliverables(
    problem: ProblemContract,
    state: Any,
    experiment_payloads: Sequence[dict[str, Any]],
    *,
    verification_threshold: float,
) -> tuple[TaskStatus, list[DeliverableAssessment]]:
    verification = state.final_verification
    proof_verified = bool(
        verification is not None
        and verification.verdict == VerificationVerdict.PASS
        and verification.confidence >= verification_threshold
    )
    candidates = [
        candidate
        for attempt in state.attempts
        for candidate in attempt.candidate_conjectures
    ]
    submitted_answers = [
        attempt.final_answer for attempt in state.attempts if attempt.final_answer
    ]
    successful_experiments = [
        payload
        for payload in experiment_payloads
        if not payload.get("error")
        and payload.get("outcome")
        in {
            ExperimentOutcome.NOT_REFUTED.value,
            ExperimentOutcome.CERTIFIED.value,
            ExperimentOutcome.COUNTEREXAMPLE_FOUND.value,
        }
    ]
    checked_counterexamples = [
        payload
        for payload in successful_experiments
        if payload.get("outcome") == ExperimentOutcome.COUNTEREXAMPLE_FOUND.value
        and bool(payload.get("independently_verified"))
    ]

    assessments: list[DeliverableAssessment] = []
    proof_backed = {
        TaskRequirement.PROOF,
        TaskRequirement.SOLUTION,
        TaskRequirement.CLASSIFICATION,
        TaskRequirement.OPTIMIZATION,
        TaskRequirement.CONSTRUCTION,
    }
    for requirement in problem.task_requirements:
        if requirement in proof_backed:
            complete = proof_verified
            evidence_ids = (
                list(state.final_proof.source_attempt_ids)
                if complete and state.final_proof is not None
                else []
            )
            summary = (
                "The requested result passed the configured independent verification."
                if complete
                else "The requested result still requires a passing final audit."
            )
        elif requirement == TaskRequirement.COMPUTATION:
            computation_interpreted = bool(submitted_answers) or (
                TaskRequirement.CONJECTURE in problem.task_requirements
                and bool(candidates)
            )
            complete = (
                bool(successful_experiments) and computation_interpreted
            ) or proof_verified
            evidence_ids = [
                str(item.get("request_hash") or item.get("experiment_id"))
                for item in successful_experiments
            ]
            summary = (
                "The requested bounded computation completed with auditable evidence."
                if complete
                else "No successful auditable computation result was produced."
            )
        elif requirement == TaskRequirement.CONJECTURE:
            complete = bool(candidates)
            evidence_ids = [item.conjecture_id for item in candidates]
            summary = (
                "A scoped candidate conjecture and separate proof obligation were produced."
                if complete
                else "No auditable candidate conjecture was produced."
            )
        elif requirement == TaskRequirement.COUNTEREXAMPLE:
            complete = bool(checked_counterexamples) or proof_verified
            evidence_ids = [
                str(item.get("request_hash") or item.get("experiment_id"))
                for item in checked_counterexamples
            ]
            summary = (
                "An independently checked counterexample was produced."
                if complete
                else "No independently checked counterexample was produced."
            )
        else:
            complete = state.research_progress_report is not None
            evidence_ids = []
            summary = (
                "An auditable research-progress report was produced."
                if complete
                else "No research-progress report was produced."
            )
        assessments.append(
            DeliverableAssessment(
                requirement=requirement,
                status=(
                    DeliverableStatus.COMPLETED
                    if complete
                    else DeliverableStatus.MISSING
                ),
                summary=summary,
                evidence_ids=evidence_ids,
            )
        )

    completed = sum(item.status == DeliverableStatus.COMPLETED for item in assessments)
    if assessments and completed == len(assessments):
        task_status = TaskStatus.COMPLETED
    elif completed:
        task_status = TaskStatus.PARTIAL
    else:
        task_status = TaskStatus.INCOMPLETE
    return task_status, assessments
