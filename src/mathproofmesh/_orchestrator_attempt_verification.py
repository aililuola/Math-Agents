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

class AttemptVerificationOrchestratorMixin:
    async def _verify_attempts_many(
        self,
        problem: ProblemContract,
        attempts: Sequence[ProofAttempt],
        runner: StructuredAgentRunner,
        prompts: PromptFactory,
        router: SparseTopologyRouter,
        memory: LemmaMemory,
        tools: ToolBroker,
        store: ArtifactStore,
    ) -> list[VerificationBundle]:
        results = await asyncio.gather(
            *(
                self._verify_attempt(
                    problem,
                    attempt,
                    runner,
                    prompts,
                    router,
                    memory,
                    tools,
                    store,
                )
                for attempt in attempts
            ),
            return_exceptions=True,
        )
        bundles: list[VerificationBundle] = []
        for attempt, result in zip(attempts, results):
            if isinstance(result, Exception):
                store.append_event(
                    "verification_pipeline_failed",
                    {"attempt_id": attempt.attempt_id, "error": str(result)},
                )
                aggregate = self._synthetic_verification_failure(
                    attempt.attempt_id,
                    "attempt",
                    VerificationStage.DETAILED,
                    f"Verification pipeline failed: {result}",
                )
                bundles.append(VerificationBundle(aggregate=aggregate, reports=[aggregate]))
            else:
                bundles.append(result)
        return bundles

    async def _verify_attempt(
        self,
        problem: ProblemContract,
        attempt: ProofAttempt,
        runner: StructuredAgentRunner,
        prompts: PromptFactory,
        router: SparseTopologyRouter,
        memory: LemmaMemory,
        tools: ToolBroker,
        store: ArtifactStore,
    ) -> VerificationBundle:
        reports: list[VerificationReport] = []

        structural_reviewers = router.select_reviewers(
            attempt,
            role="structural_verifier",
            count=1,
        )
        structural_reports = await self._call_structural_reviewers(
            problem,
            attempt,
            structural_reviewers,
            runner,
            prompts,
            store,
        )
        reports.extend(structural_reports)

        # A structural fail/uncertainty triggers one independent confirmation instead of
        # broadcasting to every reviewer.
        if (
            self.config.topology.conditional_cross_review
            and structural_reports
            and structural_reports[0].verdict != VerificationVerdict.PASS
            and runner.ledger.remaining_calls > 0
        ):
            extra = router.select_reviewers(
                attempt,
                role="structural_verifier",
                count=1,
                exclude_extra={r.agent_id for r in structural_reports},
            )
            extra_reports = await self._call_structural_reviewers(
                problem,
                attempt,
                extra,
                runner,
                prompts,
                store,
            )
            structural_reports.extend(extra_reports)
            reports.extend(extra_reports)

        structural_aggregate = self._aggregate_reports(
            attempt.attempt_id,
            "attempt",
            VerificationStage.STRUCTURAL,
            structural_reports,
        )
        reports.append(structural_aggregate)
        store.write_json(
            "structured",
            f"structural_aggregate_{attempt.attempt_id}",
            structural_aggregate,
        )

        detailed_reports: list[VerificationReport] = []
        may_detail = structural_aggregate.verdict == VerificationVerdict.PASS
        if not self.config.verification.detailed_only_after_structural_pass:
            may_detail = structural_aggregate.verdict != VerificationVerdict.FAIL

        if may_detail and runner.ledger.remaining_calls > 0:
            replica_count = router.verification_replicas(attempt, structural_reports)
            detailed_reviewers = router.select_reviewers(
                attempt,
                role="detailed_verifier",
                count=replica_count,
                exclude_extra={r.agent_id for r in structural_reports},
            )
            detailed_reports = await self._call_detailed_reviewers(
                problem,
                attempt,
                structural_aggregate,
                detailed_reviewers,
                runner,
                prompts,
                memory,
                tools,
                store,
                stage="detailed",
            )
            reports.extend(detailed_reports)

            disagreement = router.pairwise_disagreement(detailed_reports)
            need_extra = bool(detailed_reports) and (
                disagreement >= self.config.topology.disagreement_threshold
                or any(r.verdict != VerificationVerdict.PASS for r in detailed_reports)
                or min(r.confidence for r in detailed_reports)
                < self.config.budget.verification_pass_threshold
            )
            if (
                self.config.topology.conditional_cross_review
                and need_extra
                and len(detailed_reports) < self.config.budget.high_risk_verifier_replicas
                and runner.ledger.remaining_calls > 0
            ):
                extra = router.select_reviewers(
                    attempt,
                    role="detailed_verifier",
                    count=1,
                    exclude_extra={r.agent_id for r in reports},
                )
                extra_reports = await self._call_detailed_reviewers(
                    problem,
                    attempt,
                    structural_aggregate,
                    extra,
                    runner,
                    prompts,
                    memory,
                    tools,
                    store,
                    stage="detailed",
                )
                detailed_reports.extend(extra_reports)
                reports.extend(extra_reports)

        if structural_aggregate.verdict == VerificationVerdict.FAIL:
            aggregate = VerificationReport(
                target_id=attempt.attempt_id,
                target_type="attempt",
                agent_id="system-aggregate",
                stage=VerificationStage.DETAILED,
                problem_integrity_ok=structural_aggregate.problem_integrity_ok,
                verdict=VerificationVerdict.FAIL,
                first_error_step=structural_aggregate.first_error_step,
                issues=structural_aggregate.issues,
                checked_dependencies=structural_aggregate.checked_dependencies,
                failure_level=structural_aggregate.failure_level,
                confidence=structural_aggregate.confidence,
                concise_feedback="Detailed verification skipped because the structural gate failed. "
                + structural_aggregate.concise_feedback,
            )
        elif not detailed_reports:
            aggregate = VerificationReport(
                target_id=attempt.attempt_id,
                target_type="attempt",
                agent_id="system-aggregate",
                stage=VerificationStage.DETAILED,
                problem_integrity_ok=structural_aggregate.problem_integrity_ok,
                verdict=VerificationVerdict.UNCERTAIN,
                issues=structural_aggregate.issues,
                checked_dependencies=structural_aggregate.checked_dependencies,
                failure_level=structural_aggregate.failure_level,
                confidence=max(0.1, structural_aggregate.confidence * 0.5),
                concise_feedback="Structural gate did not lead to a completed detailed audit.",
            )
        else:
            aggregate = self._aggregate_reports(
                attempt.attempt_id,
                "attempt",
                VerificationStage.DETAILED,
                detailed_reports,
            )
            if structural_aggregate.verdict == VerificationVerdict.UNCERTAIN and aggregate.verdict == VerificationVerdict.PASS:
                aggregate.verdict = VerificationVerdict.UNCERTAIN
                aggregate.confidence = min(aggregate.confidence, structural_aggregate.confidence)
                aggregate.concise_feedback = (
                    "Detailed reviewers passed, but the structural gate remains uncertain. "
                    + aggregate.concise_feedback
                )

        reports.append(aggregate)
        store.write_json("structured", f"verification_aggregate_{attempt.attempt_id}", aggregate)
        memory.mark_attempt_verified(attempt.attempt_id, aggregate)
        self._update_agent_trust(attempt, reports, aggregate, runner.pool)
        if runner.activity is not None:
            runner.activity.info(
                "candidate_verification_result",
                title=runner.activity.text("候选证明审查完成", "Candidate proof audit completed"),
                detail=runner.activity.text(
                    f"路线 {attempt.strategy_id}：{aggregate.verdict.value}；置信度 {aggregate.confidence:.2f}",
                    f"Route {attempt.strategy_id}: {aggregate.verdict.value}; confidence {aggregate.confidence:.2f}",
                ),
                stage="detailed_verification",
                importance=ActivityImportance.NORMAL,
                metrics={
                    "attempt_id": attempt.attempt_id,
                    "strategy_id": attempt.strategy_id,
                    "verdict": aggregate.verdict.value,
                    "confidence": aggregate.confidence,
                    "failure_level": aggregate.failure_level.value,
                },
            )
        return VerificationBundle(aggregate=aggregate, reports=reports)

    async def _call_structural_reviewers(
        self,
        problem: ProblemContract,
        attempt: ProofAttempt,
        reviewers: Sequence[AgentRuntime],
        runner: StructuredAgentRunner,
        prompts: PromptFactory,
        store: ArtifactStore,
    ) -> list[VerificationReport]:
        async def one(reviewer: AgentRuntime) -> VerificationReport:
            bundle = prompts.structural_verify(
                problem,
                attempt.model_dump(mode="json"),
                reviewer.id,
            )
            result = await self._safe_call(
                runner,
                "structural_verifier",
                bundle,
                fixed_agent=reviewer,
                budget_bucket="verification",
            )
            if result is None:
                return self._synthetic_verification_failure(
                    attempt.attempt_id,
                    "attempt",
                    VerificationStage.STRUCTURAL,
                    f"Structural verifier {reviewer.id} failed to return a valid report.",
                    uncertain=True,
                )
            report: VerificationReport = result.value
            self._normalize_report(
                report,
                target_id=attempt.attempt_id,
                target_type="attempt",
                agent_id=reviewer.id,
                stage=VerificationStage.STRUCTURAL,
                raw_ref=result.raw_ref,
                usage=result.usage,
            )
            self._apply_local_attempt_integrity_guard(problem, attempt, report)
            store.write_json("structured", f"report_{report.report_id}", report)
            return report

        results = await asyncio.gather(*(one(r) for r in reviewers), return_exceptions=True)
        reports: list[VerificationReport] = []
        for reviewer, result in zip(reviewers, results):
            if isinstance(result, Exception):
                reports.append(
                    self._synthetic_verification_failure(
                        attempt.attempt_id,
                        "attempt",
                        VerificationStage.STRUCTURAL,
                        f"Structural verifier {reviewer.id} raised: {result}",
                        uncertain=True,
                    )
                )
            else:
                reports.append(result)
        return reports
