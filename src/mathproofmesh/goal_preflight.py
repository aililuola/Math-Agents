from __future__ import annotations

import re
from collections.abc import Awaitable, Callable

from .schemas import (
    GoalClarificationDecision,
    GoalClarificationRequest,
    GoalNormalizationAssessment,
    LocalGoalPrecheck,
)

ClarificationResolver = Callable[
    [GoalClarificationRequest], Awaitable[GoalClarificationDecision]
]


class GoalNormalizationError(RuntimeError):
    """Raised when an unclear goal cannot be normalized safely."""


class GoalClarificationRequired(GoalNormalizationError):
    def __init__(self, request: GoalClarificationRequest) -> None:
        self.request = request
        super().__init__(
            "the submitted problem is ambiguous and requires explicit confirmation"
        )


_CONGRUENCE_ZH = re.compile(r"同余")
_CONGRUENCE_EN = re.compile(r"\bcongruent\b", re.IGNORECASE)
_MODULUS_MARKER = re.compile(
    r"(?:模\s*[^，。；;,.!?？\s]+|\\pmod\b|\\bmod\b|\bmod(?:ulo)?\b)",
    re.IGNORECASE,
)
_INCOMPLETE_PLACEHOLDER = re.compile(
    r"(?:\[\s*\?\s*\]|<\s*(?:missing|unknown|\?+)\s*>|待补充|待定|"
    r"\b(?:TBD|TODO)\b)",
    re.IGNORECASE,
)
_MISSING_EXTERNAL_CONTEXT = re.compile(
    r"(?:如图|见图|下图|上图|附件|上述定理|上述条件|as shown|"
    r"the figure below|the attached (?:figure|file))",
    re.IGNORECASE,
)
_INCOMPLETE_QUANTIFIER = re.compile(
    r"(?:对(?:任意|所有)\s*[，,。.]|存在\s*(?:使得|满足)|"
    r"\bfor\s+(?:all|every)\s*[,.:;])",
    re.IGNORECASE,
)
_INCOMPLETE_RESIDUE = re.compile(
    r"(?:模\s*[^\s，。；;,.!?？]+\s*余\s*(?:$|[，。；;,.!?？])|"
    r"模\s*余\s*[^\s，。；;,.!?？]+)"
)


def deterministic_goal_precheck(statement: str) -> LocalGoalPrecheck:
    """Find high-precision signs that a goal needs semantic review.

    This is deliberately conservative. A clear result makes no API call, while a
    finding only requests a small structured review and never rewrites the goal.
    """

    text = statement.strip()
    findings: list[tuple[str, str]] = []

    has_congruence = bool(_CONGRUENCE_ZH.search(text) or _CONGRUENCE_EN.search(text))
    if has_congruence and _MODULUS_MARKER.search(text) is None:
        findings.append(
            (
                "congruence_missing_modulus",
                "同余关系没有明确给出模数，可能对应多个不同的数学命题。",
            )
        )

    if _INCOMPLETE_PLACEHOLDER.search(text):
        findings.append(
            (
                "unresolved_placeholder",
                "题目中仍有待补充的占位内容，无法冻结为完整数学目标。",
            )
        )

    if _MISSING_EXTERNAL_CONTEXT.search(text):
        findings.append(
            (
                "missing_external_context",
                "题目引用了图形、附件或前文，但当前提交内容中没有对应上下文。",
            )
        )

    if _INCOMPLETE_QUANTIFIER.search(text):
        findings.append(
            (
                "quantifier_missing_variable",
                "量词没有明确约束对象，变量范围可能缺失。",
            )
        )

    if _INCOMPLETE_RESIDUE.search(text):
        findings.append(
            (
                "residue_missing_parameter",
                "模数或余数没有完整给出，目标命题尚未确定。",
            )
        )

    if not findings:
        return LocalGoalPrecheck(status="clear")
    return LocalGoalPrecheck(
        status="model_review_required",
        rule_ids=[rule_id for rule_id, _ in findings],
        reasons=[reason for _, reason in findings],
    )


def candidate_statements(
    request: GoalClarificationRequest,
) -> list[str]:
    return [
        request.assessment.recommended_statement,
        *(
            candidate.statement
            for candidate in request.assessment.alternative_interpretations
        ),
    ]


def validate_clarification_decision(
    request: GoalClarificationRequest,
    decision: GoalClarificationDecision,
) -> GoalClarificationDecision:
    if decision.request_id != request.request_id:
        raise ValueError("clarification decision does not match the pending request")

    candidates = candidate_statements(request)
    if decision.selected_candidate_index is not None:
        index = decision.selected_candidate_index
        if index >= len(candidates):
            raise ValueError("selected goal interpretation does not exist")
        if decision.canonical_statement != candidates[index]:
            raise ValueError(
                "canonical statement does not match the selected interpretation"
            )
    if decision.source == "auto_assumed":
        if decision.selected_candidate_index != 0:
            raise ValueError("automatic interpretation may only use the recommendation")
        if decision.canonical_statement != request.assessment.recommended_statement:
            raise ValueError("automatic interpretation must use the recommendation")
    return decision


def decision_confidence(
    request: GoalClarificationRequest,
    decision: GoalClarificationDecision,
) -> float:
    index = decision.selected_candidate_index
    if index is None:
        return 1.0 if decision.source == "user_confirmed" else 0.0
    if index == 0:
        return request.assessment.recommendation_confidence
    return request.assessment.alternative_interpretations[index - 1].confidence


def requires_confirmation(assessment: GoalNormalizationAssessment) -> bool:
    return (
        assessment.has_ambiguity
        or not assessment.is_well_formed
        or assessment.changes_mathematical_meaning
    )
