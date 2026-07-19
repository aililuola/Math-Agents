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

class FinalVerificationOrchestratorMixin:
    async def _verify_final(
        self,
        problem: ProblemContract,
        proof: FinalProof,
        synthesizer: AgentRuntime | None,
        runner: StructuredAgentRunner,
        prompts: PromptFactory,
        router: SparseTopologyRouter,
        memory: LemmaMemory,
        tools: ToolBroker,
        store: ArtifactStore,
    ) -> VerificationBundle:
        reports: list[VerificationReport] = []
        exclude = {synthesizer.id} if synthesizer is not None else set()
        structural = runner.pool.select(
            "structural_verifier",
            exclude=exclude,
            prefer_provider_not=synthesizer.provider if synthesizer else None,
        )
        router.add_edge(
            source=synthesizer.id if synthesizer else "synthesizer",
            target=structural.id,
            stage="final_structural_verification",
            payload_type="FinalProof",
            reason="independent final theorem-integrity and dependency gate",
        )
        result = await self._safe_call(
            runner,
            "structural_verifier",
            prompts.structural_verify(
                problem,
                proof.model_dump(mode="json"),
                structural.id,
            ),
            fixed_agent=structural,
            budget_bucket="verification",
        )
        if result is None:
            structural_report = self._synthetic_verification_failure(
                "final_proof",
                "final_proof",
                VerificationStage.STRUCTURAL,
                "Final structural verifier failed to return a valid report.",
                uncertain=True,
            )
        else:
            structural_report = result.value
            self._normalize_report(
                structural_report,
                target_id="final_proof",
                target_type="final_proof",
                agent_id=structural.id,
                stage=VerificationStage.STRUCTURAL,
                raw_ref=result.raw_ref,
                usage=result.usage,
            )
            self._apply_local_target_integrity_guard(problem, proof, structural_report)
        reports.append(structural_report)

        if structural_report.verdict != VerificationVerdict.PASS:
            aggregate = VerificationReport(
                target_id="final_proof",
                target_type="final_proof",
                agent_id="system-aggregate",
                stage=VerificationStage.FINAL,
                problem_integrity_ok=structural_report.problem_integrity_ok,
                verdict=structural_report.verdict,
                first_error_step=structural_report.first_error_step,
                issues=structural_report.issues,
                checked_dependencies=structural_report.checked_dependencies,
                failure_level=structural_report.failure_level,
                confidence=structural_report.confidence,
                concise_feedback="Final detailed audit was blocked by the structural gate. "
                + structural_report.concise_feedback,
            )
            reports.append(aggregate)
            store.write_json("structured", f"final_verification_{new_id('v')}", aggregate)
            return VerificationBundle(aggregate=aggregate, reports=reports)

        final_reviewers = runner.pool.select_many(
            "final_verifier",
            self.config.budget.base_verifier_replicas,
            exclude=exclude | {structural.id},
        )
        if not final_reviewers:
            final_reviewers = [runner.pool.select("final_verifier", exclude=exclude)]
        for reviewer in final_reviewers:
            router.add_edge(
                source=synthesizer.id if synthesizer else "synthesizer",
                target=reviewer.id,
                stage="final_detailed_verification",
                payload_type="FinalProof",
                reason="independent first-error, step-level final audit",
            )
        detailed = await self._call_detailed_reviewers(
            problem,
            proof,
            structural_report,
            final_reviewers,
            runner,
            prompts,
            memory,
            tools,
            store,
            stage="final",
        )
        reports.extend(detailed)

        if (
            self.config.topology.conditional_cross_review
            and detailed
            and (
                any(r.verdict != VerificationVerdict.PASS for r in detailed)
                or min(r.confidence for r in detailed)
                < self.config.budget.verification_pass_threshold
            )
            and len(detailed) < self.config.budget.high_risk_verifier_replicas
            and runner.ledger.remaining_calls > 0
        ):
            extra = runner.pool.select_many(
                "final_verifier",
                1,
                exclude=exclude | {structural.id} | {r.agent_id for r in detailed},
            )
            extra_reports = await self._call_detailed_reviewers(
                problem,
                proof,
                structural_report,
                extra,
                runner,
                prompts,
                memory,
                tools,
                store,
                stage="final",
            )
            detailed.extend(extra_reports)
            reports.extend(extra_reports)

        aggregate = self._aggregate_reports(
            "final_proof",
            "final_proof",
            VerificationStage.FINAL,
            detailed,
        )
        # Final PASS has a configured minimum confidence floor.
        if (
            aggregate.verdict == VerificationVerdict.PASS
            and aggregate.confidence < self.config.budget.verification_pass_threshold
        ):
            aggregate.verdict = VerificationVerdict.UNCERTAIN
            aggregate.concise_feedback = (
                "All available reviewers passed, but aggregate confidence is below the configured threshold. "
                + aggregate.concise_feedback
            )
        reports.append(aggregate)
        store.write_json("structured", f"final_verification_{new_id('v')}", aggregate)
        return VerificationBundle(aggregate=aggregate, reports=reports)

    async def _revise_final(
        self,
        problem: ProblemContract,
        proof: FinalProof,
        verification: VerificationReport,
        synthesizer: AgentRuntime | None,
        runner: StructuredAgentRunner,
        prompts: PromptFactory,
        memory: LemmaMemory,
        store: ArtifactStore,
        revision_index: int,
    ) -> FinalProof | None:
        reviser = synthesizer or runner.pool.select("synthesizer")
        result = await self._safe_call(
            runner,
            "synthesizer",
            prompts.revise_final(
                problem,
                proof,
                verification,
                self._select_claim_context(
                    memory.verified(),
                    proof.model_dump_json(),
                    max_chars=max(2000, self.config.topology.max_context_chars // 4),
                ),
                reviser.id,
            ),
            fixed_agent=reviser,
            budget_bucket="synthesis",
        )
        if result is None:
            return None
        revised: FinalProof = result.value
        revised.problem_hash = problem.integrity_hash
        store.write_json("structured", f"final_proof_revision_{revision_index}", revised)
        return revised

    async def _safe_call(
        self,
        runner: StructuredAgentRunner,
        role: str,
        bundle: PromptBundle,
        *,
        fixed_agent: AgentRuntime | None = None,
        exclude: set[str] | None = None,
        specialty_hints: list[str] | None = None,
        prefer_provider_not: str | None = None,
        budget_bucket: str,
    ) -> StructuredCallResult[Any] | None:
        try:
            return await runner.call(
                role,
                bundle,
                fixed_agent=fixed_agent,
                exclude=exclude,
                specialty_hints=specialty_hints,
                prefer_provider_not=prefer_provider_not,
                budget_bucket=budget_bucket,
            )
        except BudgetExhaustedError:
            raise
        except (StructuredOutputError, RuntimeError, ValueError) as exc:
            logger.warning("Agent call failed at %s (%s): %s", bundle.stage, role, exc)
            runner.store.append_event(
                "agent_stage_failed",
                {
                    "stage": bundle.stage,
                    "role": role,
                    "agent_id": fixed_agent.id if fixed_agent else None,
                    "error": str(exc),
                },
            )
            return None
