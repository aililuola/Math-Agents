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

class OrchestratorHelpersAMixin:
    def _aggregate_reports(
        self,
        target_id: str,
        target_type: str,
        stage: VerificationStage,
        reports: Sequence[VerificationReport],
    ) -> VerificationReport:
        if not reports:
            return self._synthetic_verification_failure(
                target_id,
                target_type,
                stage,
                "No independent verification report was available.",
                uncertain=True,
            )
        if any(not report.problem_integrity_ok for report in reports):
            verdict = VerificationVerdict.FAIL
        else:
            verdicts = [r.verdict for r in reports]
            deterministic_fail = any(self._has_deterministic_refutation(r) for r in reports)
            if deterministic_fail:
                verdict = VerificationVerdict.FAIL
            elif all(v == VerificationVerdict.PASS for v in verdicts):
                verdict = VerificationVerdict.PASS
            elif all(v == VerificationVerdict.FAIL for v in verdicts):
                verdict = VerificationVerdict.FAIL
            else:
                verdict = VerificationVerdict.UNCERTAIN

        issues: list[VerificationIssue] = []
        seen_issue_keys: set[tuple[str | None, str]] = set()
        for report in reports:
            for issue in report.issues:
                key = (issue.step_id or issue.claim_id, issue.description)
                if key not in seen_issue_keys:
                    issues.append(issue)
                    seen_issue_keys.add(key)

        failure_level = max(
            (r.failure_level for r in reports),
            key=self._failure_rank,
            default=FailureLevel.NONE,
        )
        if verdict == VerificationVerdict.PASS:
            confidence = min(r.confidence for r in reports)
        elif verdict == VerificationVerdict.FAIL:
            fail_confidences = [r.confidence for r in reports if r.verdict == VerificationVerdict.FAIL]
            confidence = max(fail_confidences or [r.confidence for r in reports])
        else:
            # Uncertainty confidence is the confidence that more checking is needed, not a pass probability.
            spread = max(r.confidence for r in reports) - min(r.confidence for r in reports)
            confidence = min(0.95, 0.55 + 0.25 * spread + 0.05 * len(reports))

        feedback_parts = []
        for report in reports:
            text = report.concise_feedback.strip()
            if text and text not in feedback_parts:
                feedback_parts.append(text)
        first_error = next((r.first_error_step for r in reports if r.first_error_step), None)
        return VerificationReport(
            target_id=target_id,
            target_type=target_type,  # type: ignore[arg-type]
            agent_id="system-aggregate",
            stage=stage,
            problem_integrity_ok=all(r.problem_integrity_ok for r in reports),
            verdict=verdict,
            first_error_step=first_error,
            issues=issues,
            checked_dependencies=sorted(
                {dependency for report in reports for dependency in report.checked_dependencies}
            ),
            tool_requests=[request for report in reports for request in report.tool_requests],
            tool_results=[result for report in reports for result in report.tool_results],
            failure_level=failure_level,
            confidence=max(0.0, min(1.0, confidence)),
            concise_feedback=" | ".join(feedback_parts)[:12000]
            or "Independent reports were aggregated conservatively.",
        )

    def _apply_local_attempt_integrity_guard(
        self,
        problem: ProblemContract,
        attempt: ProofAttempt,
        report: VerificationReport,
    ) -> None:
        self._apply_local_target_integrity_guard(problem, attempt, report)
        step_ids = {step.step_id for step in attempt.proof_steps}
        claim_ids = {claim.claim_id for claim in attempt.proposed_lemmas}
        known = step_ids | claim_ids
        missing: set[str] = set()
        for step in attempt.proof_steps:
            for dep in step.dependencies:
                if dep not in known and not dep.startswith("external:"):
                    missing.add(dep)
        if missing:
            report.issues.append(
                VerificationIssue(
                    phase="local_dependency_guard",
                    severity=Severity.ERROR,
                    description=f"Missing dependency IDs: {sorted(missing)}",
                    repair_hint="Add the missing derivation or replace the dependency with a verified claim ID.",
                )
            )
            report.failure_level = max(
                report.failure_level,
                FailureLevel.PLAN,
                key=self._failure_rank,
            )
            report.verdict = VerificationVerdict.FAIL
            report.concise_feedback = "Deterministic dependency validation found missing IDs. " + report.concise_feedback
        if attempt.status == AttemptStatus.COMPLETE and not attempt.final_answer:
            report.issues.append(
                VerificationIssue(
                    phase="local_completeness_guard",
                    severity=Severity.ERROR,
                    description="Attempt is marked complete but has no final_answer.",
                )
            )
            report.verdict = VerificationVerdict.FAIL

    def _apply_local_target_integrity_guard(
        self,
        problem: ProblemContract,
        target: ProofAttempt | FinalProof,
        report: VerificationReport,
    ) -> None:
        target_hash = target.problem_hash
        if target_hash != problem.integrity_hash:
            report.problem_integrity_ok = False
            report.failure_level = FailureLevel.STRATEGY
            report.issues.append(
                VerificationIssue(
                    phase="problem_integrity_guard",
                    severity=Severity.CRITICAL,
                    description=(
                        f"Target problem_hash={target_hash} does not match immutable hash="
                        f"{problem.integrity_hash}."
                    ),
                    repair_hint="Re-solve the exact original problem without modifying its statement.",
                )
            )
            report.verdict = VerificationVerdict.FAIL
            report.concise_feedback = "Problem-integrity hash mismatch. " + report.concise_feedback

    def _apply_deterministic_tool_guard(self, report: VerificationReport) -> None:
        for result in report.tool_results:
            if not result.ok or not isinstance(result.result, dict):
                continue
            payload = result.result
            refuted = result.kind == "numeric_counterexample" and payload.get("counterexample_found") is True
            lean_rejected = result.kind == "lean_check" and payload.get("accepted") is False
            if refuted or lean_rejected:
                report.issues.append(
                    VerificationIssue(
                        phase="deterministic_tool_guard",
                        severity=Severity.CRITICAL if refuted else Severity.ERROR,
                        description=(
                            "Verifier-requested numeric formalization produced a counterexample."
                            if refuted
                            else "Submitted Lean fragment was rejected by the configured checker."
                        ),
                        counterexample=str(payload.get("assignment")) if refuted else None,
                        repair_hint="Check the formalization mapping, then repair or remove the refuted inference.",
                    )
                )
                report.failure_level = max(
                    report.failure_level,
                    FailureLevel.EXECUTION,
                    key=self._failure_rank,
                )
                report.verdict = VerificationVerdict.FAIL
                if report.first_error_step is None:
                    report.first_error_step = "deterministic_tool_check"
                report.concise_feedback = "A deterministic check refuted a requested subclaim. " + report.concise_feedback

    @staticmethod
    def _has_deterministic_refutation(report: VerificationReport) -> bool:
        return any(
            result.ok
            and isinstance(result.result, dict)
            and (
                (result.kind == "numeric_counterexample" and result.result.get("counterexample_found") is True)
                or (result.kind == "lean_check" and result.result.get("accepted") is False)
            )
            for result in report.tool_results
        )

    def _normalize_report(
        self,
        report: VerificationReport,
        *,
        target_id: str,
        target_type: str,
        agent_id: str,
        stage: VerificationStage,
        raw_ref: str,
        usage: UsageRecord,
    ) -> None:
        report.target_id = target_id
        report.target_type = target_type  # type: ignore[assignment]
        report.agent_id = agent_id
        report.stage = stage
        report.raw_artifact_ref = raw_ref
        report.usage = usage
        if report.verdict == VerificationVerdict.FAIL and not report.issues:
            report.issues.append(
                VerificationIssue(
                    phase="normalization",
                    severity=Severity.ERROR,
                    description="Verifier returned FAIL without a concrete issue.",
                )
            )
        if (
            self.config.verification.require_first_error_step
            and report.verdict == VerificationVerdict.FAIL
            and report.first_error_step is None
        ):
            report.issues.append(
                VerificationIssue(
                    phase="verification_protocol",
                    severity=Severity.WARNING,
                    description="The verifier did not identify the first erroneous step.",
                )
            )

    def _record_verification_bundles(
        self,
        state: SolveState,
        bundles: Iterable[VerificationBundle],
    ) -> None:
        for bundle in bundles:
            state.reports.extend(bundle.reports)
            state.aggregate_reports[bundle.aggregate.target_id] = bundle.aggregate
