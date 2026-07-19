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

class OrchestratorHelpersDMixin:

    def _claim_dedup_index(self, claims: Sequence[ClaimCard]) -> list[dict[str, Any]]:
        """Minimal lossless-for-dedup index instead of rebroadcasting every full proof packet."""
        compact = [
            {
                "claim_id": claim.claim_id,
                "content_hash": claim.content_hash,
                "statement": claim.statement,
                "conclusion": claim.conclusion,
                "status": claim.status.value,
                "source_attempt_id": claim.source_attempt_id,
            }
            for claim in claims
        ]
        return self._fit_json_items(
            compact,
            max_chars=max(2000, self.config.topology.max_context_chars // 4),
            preserve_first=False,
        )

    def _attempt_context_dict(self, attempt: ProofAttempt, *, full: bool) -> dict[str, Any]:
        data = attempt.model_dump(mode="json")
        if full:
            return data
        return {
            "attempt_id": data["attempt_id"],
            "problem_hash": data["problem_hash"],
            "strategy_id": data["strategy_id"],
            "agent_id": data["agent_id"],
            "round_index": data["round_index"],
            "status": data["status"],
            "final_answer": data["final_answer"],
            "proof_steps": [
                {
                    "step_id": step["step_id"],
                    "statement": step["statement"],
                    "justification": step["justification"],
                    "dependencies": step["dependencies"],
                    "is_key_step": step["is_key_step"],
                    "confidence": step["confidence"],
                }
                for step in data["proof_steps"]
            ],
            "unresolved_gaps": data["unresolved_gaps"],
            "dead_ends": data["dead_ends"],
            "raw_artifact_ref": data["raw_artifact_ref"],
        }

    def _update_agent_trust(
        self,
        attempt: ProofAttempt,
        reports: Sequence[VerificationReport],
        aggregate: VerificationReport,
        pool: AgentPool,
    ) -> None:
        try:
            prover = pool.get(attempt.agent_id)
            if aggregate.verdict == VerificationVerdict.PASS:
                prover.update_trust(0.03)
            elif aggregate.verdict == VerificationVerdict.FAIL:
                prover.update_trust(-0.03)
        except KeyError:
            pass
        for report in reports:
            if report.agent_id.startswith("system-"):
                continue
            try:
                reviewer = pool.get(report.agent_id)
            except KeyError:
                continue
            if aggregate.verdict == VerificationVerdict.UNCERTAIN:
                continue
            reviewer.update_trust(0.01 if report.verdict == aggregate.verdict else -0.015)

    def _has_synthesis_ready_candidate(self, state: SolveState) -> bool:
        return any(
            attempt.status == AttemptStatus.COMPLETE
            and state.aggregate_reports.get(attempt.attempt_id) is not None
            and state.aggregate_reports[attempt.attempt_id].verdict == VerificationVerdict.PASS
            and state.aggregate_reports[attempt.attempt_id].confidence
            >= self.config.budget.synthesis_threshold
            for attempt in state.attempts
        )

    def _run_status(self, state: SolveState) -> RunStatus:
        if (
            state.final_verification is not None
            and state.final_verification.verdict == VerificationVerdict.PASS
            and state.final_verification.confidence
            >= self.config.budget.verification_pass_threshold
        ):
            return RunStatus.VERIFIED
        if state.budget_exhausted:
            return RunStatus.BUDGET_EXHAUSTED
        if state.final_proof is not None:
            return RunStatus.UNVERIFIED
        return RunStatus.FAILED

    def _build_result(
        self,
        run_id: str,
        status: RunStatus,
        problem: ProblemContract,
        state: SolveState,
        metrics: list[AgentMetric],
        runner: StructuredAgentRunner,
        pool: AgentPool,
        store: ArtifactStore,
        memory: LemmaMemory,
        *,
        summary_override: str | None = None,
    ) -> RunResult:
        total_usage = self._sum_usage([metric.usage for metric in metrics])
        summary = summary_override or self._result_summary(status, state)
        return RunResult(
            run_id=run_id,
            status=status,
            problem=problem,
            final_proof=state.final_proof,
            final_verification=state.final_verification,
            attempts=state.attempts,
            claims=memory.claims,
            verification_reports=state.reports,
            meta_reviews=state.meta_reviews,
            agent_metrics=metrics,
            total_calls=runner.ledger.calls_started,
            total_usage=total_usage,
            run_directory=str(store.root),
            summary=summary,
        )

    @staticmethod
    def _result_summary(status: RunStatus, state: SolveState) -> str:
        if status == RunStatus.VERIFIED:
            return "A final proof passed independent structural and step-level verification under the configured threshold."
        if status == RunStatus.UNVERIFIED:
            verdict = state.final_verification.verdict.value if state.final_verification else "missing"
            return f"A final answer/proof draft was produced, but final verification is {verdict}; inspect caveats and reports."
        if status == RunStatus.BUDGET_EXHAUSTED:
            return "The configured inference budget was exhausted; all partial attempts and evidence were preserved."
        return "No final proof could be formed; inspect failed paths and structured artifacts."

    def _checkpoint(
        self,
        store: ArtifactStore,
        stage: str,
        state: SolveState,
        memory: LemmaMemory,
        runner: StructuredAgentRunner,
    ) -> None:
        if not self.config.runtime.checkpoint_every_stage:
            return
        store.checkpoint(
            stage,
            {
                "triage": state.triage,
                "strategies": state.strategies,
                "attempts": state.attempts,
                "reports": state.reports,
                "aggregate_reports": state.aggregate_reports,
                "meta_reviews": state.meta_reviews,
                "claims": memory.claims,
                "calls_started": runner.ledger.calls_started,
                "bucket_calls": runner.ledger.bucket_calls,
            },
        )

    def _allowed_tools(self) -> list[str]:
        tools: list[str] = []
        if self.config.verification.enable_sympy_tools:
            tools.extend(["sympy_simplify", "sympy_equivalent", "polynomial_factor"])
        if self.config.verification.enable_numeric_counterexamples:
            tools.append("numeric_counterexample")
        if self.config.verification.enable_lean:
            tools.append("lean_check")
        return tools

    @staticmethod
    def _normalize_statement(text: str) -> str:
        return re.sub(r"\s+", " ", text).strip()

    @staticmethod
    def _make_run_id(problem_text: str) -> str:
        timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        return f"run_{timestamp}_{stable_hash(problem_text)[:10]}"

    @staticmethod
    def _failure_rank(level: FailureLevel) -> int:
        return {
            FailureLevel.NONE: 0,
            FailureLevel.EXECUTION: 1,
            FailureLevel.PLAN: 2,
            FailureLevel.STRATEGY: 3,
        }[level]

    @staticmethod
    def _sum_usage(usages: Iterable[UsageRecord]) -> UsageRecord:
        usage_list = list(usages)
        return UsageRecord(
            input_tokens=sum(u.input_tokens for u in usage_list),
            output_tokens=sum(u.output_tokens for u in usage_list),
            total_tokens=sum(u.total_tokens for u in usage_list),
            estimated_cost_usd=sum(u.estimated_cost_usd for u in usage_list),
            latency_ms=sum(u.latency_ms for u in usage_list),
        )

    @staticmethod
    def _deduplicate_strings(values: Iterable[str]) -> list[str]:
        seen: set[str] = set()
        result: list[str] = []
        for value in values:
            normalized = value.strip()
            if normalized and normalized not in seen:
                seen.add(normalized)
                result.append(normalized)
        return result
