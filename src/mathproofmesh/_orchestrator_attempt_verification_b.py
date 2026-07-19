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

class AttemptVerificationBOrchestratorMixin:

    async def _call_detailed_reviewers(
        self,
        problem: ProblemContract,
        target: ProofAttempt | FinalProof,
        structural_report: VerificationReport,
        reviewers: Sequence[AgentRuntime],
        runner: StructuredAgentRunner,
        prompts: PromptFactory,
        memory: LemmaMemory,
        tools: ToolBroker,
        store: ArtifactStore,
        *,
        stage: str,
    ) -> list[VerificationReport]:
        target_id = target.attempt_id if isinstance(target, ProofAttempt) else "final_proof"
        target_type = "attempt" if isinstance(target, ProofAttempt) else "final_proof"

        async def one(reviewer: AgentRuntime) -> VerificationReport:
            bundle = prompts.detailed_verify(
                problem,
                target.model_dump(mode="json"),
                structural_report,
                self._select_claim_context(
                    memory.verified(),
                    target.model_dump_json(),
                    max_chars=max(2000, self.config.topology.max_context_chars // 4),
                ),
                reviewer.id,
                stage=stage,
            )
            result = await self._safe_call(
                runner,
                "final_verifier" if stage == "final" else "detailed_verifier",
                bundle,
                fixed_agent=reviewer,
                budget_bucket="verification",
            )
            if result is None:
                return self._synthetic_verification_failure(
                    target_id,
                    target_type,
                    VerificationStage.FINAL if stage == "final" else VerificationStage.DETAILED,
                    f"Detailed verifier {reviewer.id} failed to return a valid report.",
                    uncertain=True,
                )
            report: VerificationReport = result.value
            self._normalize_report(
                report,
                target_id=target_id,
                target_type=target_type,
                agent_id=reviewer.id,
                stage=VerificationStage.FINAL if stage == "final" else VerificationStage.DETAILED,
                raw_ref=result.raw_ref,
                usage=result.usage,
            )
            self._apply_local_target_integrity_guard(problem, target, report)

            if report.tool_requests:
                tool_results = tools.execute_many(report.tool_requests)
                report.tool_results = tool_results
                self._apply_deterministic_tool_guard(report)
                # Let the same independent reviewer interpret its narrowly-scoped tool output.
                if runner.ledger.remaining_calls > 0:
                    follow_up = prompts.detailed_verify(
                        problem,
                        target.model_dump(mode="json"),
                        structural_report,
                        self._select_claim_context(
                            memory.verified(),
                            target.model_dump_json(),
                            max_chars=max(2000, self.config.topology.max_context_chars // 4),
                        ),
                        reviewer.id,
                        [t.model_dump(mode="json") for t in tool_results],
                        stage=stage,
                    )
                    follow_result = await self._safe_call(
                        runner,
                        "final_verifier" if stage == "final" else "detailed_verifier",
                        follow_up,
                        fixed_agent=reviewer,
                        budget_bucket="verification",
                    )
                    if follow_result is not None:
                        interpreted: VerificationReport = follow_result.value
                        self._normalize_report(
                            interpreted,
                            target_id=target_id,
                            target_type=target_type,
                            agent_id=reviewer.id,
                            stage=(
                                VerificationStage.FINAL
                                if stage == "final"
                                else VerificationStage.DETAILED
                            ),
                            raw_ref=follow_result.raw_ref,
                            usage=self._sum_usage([result.usage, follow_result.usage]),
                        )
                        interpreted.tool_requests = report.tool_requests
                        interpreted.tool_results = tool_results
                        self._apply_local_target_integrity_guard(problem, target, interpreted)
                        self._apply_deterministic_tool_guard(interpreted)
                        report = interpreted
            store.write_json("structured", f"report_{report.report_id}", report)
            return report

        results = await asyncio.gather(*(one(r) for r in reviewers), return_exceptions=True)
        reports: list[VerificationReport] = []
        for reviewer, result in zip(reviewers, results):
            if isinstance(result, Exception):
                reports.append(
                    self._synthetic_verification_failure(
                        target_id,
                        target_type,
                        VerificationStage.FINAL if stage == "final" else VerificationStage.DETAILED,
                        f"Detailed verifier {reviewer.id} raised: {result}",
                        uncertain=True,
                    )
                )
            else:
                reports.append(result)
        return reports

    async def _meta_review(
        self,
        problem: ProblemContract,
        attempts: Sequence[ProofAttempt],
        aggregate_reports: dict[str, VerificationReport],
        runner: StructuredAgentRunner,
        prompts: PromptFactory,
        store: ArtifactStore,
    ) -> MetaReview:
        ranked = self._rank_attempts(attempts)
        candidates = ranked[: max(1, min(5, len(ranked)))]
        reports = [
            aggregate_reports[a.attempt_id]
            for a in candidates
            if a.attempt_id in aggregate_reports
        ]
        result = await self._safe_call(
            runner,
            "meta_reviewer",
            prompts.meta_review(
                problem,
                self._fit_json_items(
                    [self._attempt_context_dict(a, full=False) for a in candidates],
                    max_chars=max(3000, int(self.config.topology.max_context_chars * 0.65)),
                ),
                self._fit_json_items(
                    [r.model_dump(mode="json") for r in reports],
                    max_chars=max(2000, int(self.config.topology.max_context_chars * 0.30)),
                ),
            ),
            budget_bucket="verification",
        )
        if result is not None:
            review: MetaReview = result.value
            valid_ids = {a.attempt_id for a in candidates}
            if review.selected_target_id not in valid_ids:
                review.selected_target_id = candidates[0].attempt_id if candidates else None
            store.write_json("structured", f"meta_review_{len(list(store.root.glob('structured/meta_review_*.json')))}", review)
            return review
        review = self._local_meta_review(candidates, aggregate_reports)
        store.write_json("structured", f"meta_review_fallback_{new_id('r')}", review)
        return review
