# ruff: noqa: F401
from __future__ import annotations

import asyncio
import json
import logging
import re
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Iterable, Sequence, TypeVar

from pydantic import BaseModel

from .activity import (
    ActivityImportance,
    ActivityListener,
    ActivityStatus,
    ActivityStream,
)
from .agents import (
    BudgetExhaustedError,
    StructuredAgentRunner,
    StructuredCallResult,
    StructuredOutputError,
)
from .budget import AdaptiveBudgetManager, SoftBudgetAllocator
from .config import SystemConfig
from .llm.mock import MockResponder
from .llm.pool import AgentPool, AgentRuntime
from .memory import LemmaMemory
from .prompts import PromptBundle, PromptFactory
from .report import write_run_report
from .schemas import (
    ActionKind,
    AgentMetric,
    AttemptStatus,
    CandidateAssessment,
    ClaimBatch,
    ClaimCard,
    ClaimStatus,
    Difficulty,
    EvidenceRef,
    FailureLevel,
    FinalProof,
    MetaReview,
    ProblemContract,
    ProblemKind,
    ProofAttempt,
    RunResult,
    RunStatus,
    Severity,
    StrategyCard,
    StrategySet,
    TriageResult,
    UsageRecord,
    VerificationIssue,
    VerificationReport,
    VerificationStage,
    VerificationVerdict,
    new_id,
    stable_hash,
)
from .store import ArtifactStore
from .tools import ToolBroker
from .topology import SparseTopologyRouter, jaccard_similarity, strategy_text

from ._orchestrator_types import SolveState, VerificationBundle

logger = logging.getLogger(__name__)

class OrchestratorHelpersCMixin:

    def _select_for_synthesis(self, state: SolveState) -> list[ProofAttempt]:
        ranked = self._rank_attempts(state.attempts)
        passed = [
            attempt
            for attempt in ranked
            if state.aggregate_reports.get(attempt.attempt_id)
            and state.aggregate_reports[attempt.attempt_id].verdict == VerificationVerdict.PASS
        ]
        uncertain_complete = [
            attempt
            for attempt in ranked
            if attempt.status == AttemptStatus.COMPLETE
            and attempt not in passed
            and (
                state.aggregate_reports.get(attempt.attempt_id) is None
                or state.aggregate_reports[attempt.attempt_id].verdict != VerificationVerdict.FAIL
            )
        ]
        partial = [
            attempt
            for attempt in ranked
            if attempt.status == AttemptStatus.PARTIAL
            and (
                state.aggregate_reports.get(attempt.attempt_id) is None
                or state.aggregate_reports[attempt.attempt_id].verdict != VerificationVerdict.FAIL
            )
        ]
        selected: list[ProofAttempt] = []
        for group in (passed, uncertain_complete, partial):
            for attempt in group:
                if attempt in selected:
                    continue
                selected.append(attempt)
                if len(selected) >= 3:
                    return selected
        return selected

    def _rank_attempts(self, attempts: Sequence[ProofAttempt]) -> list[ProofAttempt]:
        return sorted(
            attempts,
            key=lambda a: (self._attempt_local_quality(a), a.round_index, -a.usage.total_tokens),
            reverse=True,
        )

    @staticmethod
    def _attempt_local_quality(attempt: ProofAttempt) -> float:
        status_score = {
            AttemptStatus.COMPLETE: 0.36,
            AttemptStatus.PARTIAL: 0.18,
            AttemptStatus.FAILED: 0.0,
        }[attempt.status]
        step_score = min(0.24, 0.025 * len(attempt.proof_steps))
        key_steps = sum(1 for step in attempt.proof_steps if step.is_key_step)
        key_score = min(0.08, 0.02 * key_steps)
        lemma_score = min(0.08, 0.025 * len(attempt.proposed_lemmas))
        confidence_score = 0.20 * attempt.self_confidence
        gap_penalty = min(0.25, 0.05 * len(attempt.unresolved_gaps))
        dead_end_penalty = min(0.12, 0.03 * len(attempt.dead_ends))
        return max(
            0.0,
            min(
                1.0,
                status_score
                + step_score
                + key_score
                + lemma_score
                + confidence_score
                - gap_penalty
                - dead_end_penalty,
            ),
        )

    def _targeted_feedback(self, attempt: ProofAttempt, state: SolveState) -> list[str]:
        feedback: list[str] = []
        report = state.aggregate_reports.get(attempt.attempt_id)
        if report is not None:
            if report.first_error_step:
                feedback.append(f"First disputed step: {report.first_error_step}")
            feedback.append(report.concise_feedback)
            feedback.extend(
                f"{issue.step_id or issue.claim_id or 'global'}: {issue.description}"
                for issue in report.issues[:8]
            )
        feedback.extend(f"Unresolved gap: {gap}" for gap in attempt.unresolved_gaps[:8])
        if state.meta_reviews:
            feedback.extend(state.meta_reviews[-1].required_actions[:6])
        return self._deduplicate_strings(feedback)

    def _local_meta_review(
        self,
        attempts: Sequence[ProofAttempt],
        reports: dict[str, VerificationReport],
    ) -> MetaReview:
        assessments: list[CandidateAssessment] = []
        for attempt in attempts:
            report = reports.get(attempt.attempt_id)
            verification_component = 0.0
            if report:
                verification_component = {
                    VerificationVerdict.PASS: 0.3 * report.confidence,
                    VerificationVerdict.UNCERTAIN: 0.1 * report.confidence,
                    VerificationVerdict.FAIL: -0.25 * report.confidence,
                    VerificationVerdict.SKIPPED: 0.0,
                }[report.verdict]
            score = max(0.0, min(1.0, self._attempt_local_quality(attempt) + verification_component))
            if report and report.verdict == VerificationVerdict.PASS and attempt.status == AttemptStatus.COMPLETE:
                action = ActionKind.SYNTHESIZE
            elif report and report.verdict == VerificationVerdict.FAIL and report.failure_level == FailureLevel.STRATEGY:
                action = ActionKind.WIDEN
            else:
                action = ActionKind.DEEPEN
            assessments.append(
                CandidateAssessment(
                    target_id=attempt.attempt_id,
                    score=score,
                    strengths=[f"{len(attempt.proof_steps)} explicit proof steps"],
                    weaknesses=list(attempt.unresolved_gaps[:4]),
                    recommended_action=action,
                )
            )
        assessments.sort(key=lambda x: x.score, reverse=True)
        selected = assessments[0].target_id if assessments else None
        can_synthesize = bool(
            selected
            and any(
                a.attempt_id == selected and a.status == AttemptStatus.COMPLETE
                for a in attempts
            )
            and reports.get(selected)
            and reports[selected].verdict == VerificationVerdict.PASS
        )
        return MetaReview(
            selected_target_id=selected,
            assessments=assessments,
            shared_agreements=[],
            unresolved_conflicts=[],
            required_actions=[],
            failure_level=(
                reports[selected].failure_level
                if selected and selected in reports
                else FailureLevel.NONE
            ),
            can_synthesize=can_synthesize,
            confidence=assessments[0].score if assessments else 0.0,
            summary="Deterministic evidence-weighted fallback meta-review.",
        )
