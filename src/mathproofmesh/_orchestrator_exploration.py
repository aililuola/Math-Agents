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

class ExplorationOrchestratorMixin:
    async def _triage(
        self,
        problem: ProblemContract,
        runner: StructuredAgentRunner,
        prompts: PromptFactory,
        store: ArtifactStore,
    ) -> TriageResult:
        result = await self._safe_call(
            runner,
            "planner",
            prompts.triage(problem),
            budget_bucket="breadth",
        )
        if result is not None:
            triage = result.value
            store.write_json("structured", "triage", triage)
            return triage
        triage = TriageResult(
            problem_kind=ProblemKind.UNKNOWN,
            difficulty=Difficulty.HARD,
            key_risks=[
                "The solver may change the statement or omit cases.",
                "A plausible key step may remain unproved.",
            ],
            likely_tools=self._allowed_tools(),
            suggested_paths=self.config.budget.initial_paths,
            suggested_rounds=self.config.budget.max_rounds,
            proof_mode="hybrid",
            rationale="Deterministic fallback triage after planner failure.",
            confidence=0.25,
        )
        store.write_json("structured", "triage_fallback", triage)
        return triage

    async def _initial_strategies(
        self,
        problem: ProblemContract,
        triage: TriageResult,
        runner: StructuredAgentRunner,
        prompts: PromptFactory,
        router: SparseTopologyRouter,
        store: ArtifactStore,
    ) -> list[StrategyCard]:
        requested = min(
            self.config.budget.strategies_to_generate,
            max(self.config.budget.initial_paths, triage.suggested_paths),
        )
        result = await self._safe_call(
            runner,
            "planner",
            prompts.strategies(problem, triage, requested),
            budget_bucket="breadth",
        )
        strategy_set = result.value if result is not None else self._fallback_strategy_set(problem, requested)
        selected = router.select_diverse_strategies(
            strategy_set.strategies,
            min(self.config.budget.initial_paths, len(strategy_set.strategies)),
        )
        store.write_json("structured", "strategy_set", strategy_set)
        store.write_json("structured", "selected_strategies", selected)
        return selected

    async def _parallel_initial_exploration(
        self,
        problem: ProblemContract,
        assignments: list[tuple[StrategyCard, AgentRuntime]],
        runner: StructuredAgentRunner,
        prompts: PromptFactory,
        router: SparseTopologyRouter,
        memory: LemmaMemory,
        store: ArtifactStore,
    ) -> list[ProofAttempt]:
        async def one(strategy: StrategyCard, agent: AgentRuntime) -> ProofAttempt:
            return await self._explore_path(
                problem,
                strategy,
                agent,
                round_index=0,
                runner=runner,
                prompts=prompts,
                router=router,
                memory=memory,
                store=store,
                targeted_feedback=[],
                previous_attempt=None,
                budget_bucket="breadth",
            )

        results = await asyncio.gather(
            *(one(strategy, agent) for strategy, agent in assignments),
            return_exceptions=True,
        )
        attempts: list[ProofAttempt] = []
        for (strategy, agent), result in zip(assignments, results):
            if isinstance(result, Exception):
                store.append_event(
                    "exploration_failed",
                    {
                        "strategy_id": strategy.strategy_id,
                        "agent_id": agent.id,
                        "error": str(result),
                    },
                )
                attempts.append(self._failed_attempt(problem, strategy, agent.id, 0, result))
            else:
                attempts.append(result)
        return attempts

    async def _explore_path(
        self,
        problem: ProblemContract,
        strategy: StrategyCard,
        agent: AgentRuntime,
        *,
        round_index: int,
        runner: StructuredAgentRunner,
        prompts: PromptFactory,
        router: SparseTopologyRouter,
        memory: LemmaMemory,
        store: ArtifactStore,
        targeted_feedback: list[str],
        previous_attempt: ProofAttempt | None,
        budget_bucket: str,
    ) -> ProofAttempt:
        relevant = router.relevant_claims(memory.claims, strategy, targeted_feedback)
        bundle = prompts.explore(
            problem,
            strategy.model_dump(mode="json"),
            agent.id,
            round_index,
            [c.model_dump(mode="json") for c in relevant],
            targeted_feedback,
            self._attempt_context_dict(previous_attempt, full=False) if previous_attempt else None,
            runner.ledger.remaining_calls,
        )
        result = await self._safe_call(
            runner,
            "explorer",
            bundle,
            fixed_agent=agent,
            budget_bucket=budget_bucket,
        )
        if result is None:
            return self._failed_attempt(
                problem,
                strategy,
                agent.id,
                round_index,
                RuntimeError("explorer returned no valid structured result"),
            )
        attempt: ProofAttempt = result.value
        # Authoritative metadata is assigned by the orchestrator, not trusted from model text.
        attempt.problem_hash = problem.integrity_hash
        attempt.strategy_id = strategy.strategy_id
        attempt.agent_id = agent.id
        attempt.round_index = round_index
        attempt.raw_artifact_ref = result.raw_ref
        attempt.usage = result.usage
        for lemma in attempt.proposed_lemmas:
            lemma.source_attempt_id = attempt.attempt_id
            lemma.source_agent_id = agent.id
            if not any(e.artifact_ref == result.raw_ref for e in lemma.evidence_refs):
                lemma.evidence_refs.append(
                    EvidenceRef(
                        artifact_ref=result.raw_ref,
                        summary="Raw explorer response containing the proposed lemma.",
                    )
                )
        store.write_json("structured", f"attempt_{attempt.attempt_id}", attempt)
        store.append_event("attempt_completed", attempt)
        if runner.activity is not None:
            runner.activity.info(
                "proof_route_result",
                title=runner.activity.text("一条证明路线已返回", "A proof route returned"),
                detail=runner.activity.text(
                    f"{strategy.title}：{attempt.status.value}；步骤 {len(attempt.proof_steps)}；未解缺口 {len(attempt.unresolved_gaps)}",
                    f"{strategy.title}: {attempt.status.value}; {len(attempt.proof_steps)} steps; {len(attempt.unresolved_gaps)} unresolved gaps",
                ),
                stage="independent_exploration",
                agent_id=agent.id,
                importance=ActivityImportance.NORMAL,
                metrics={
                    "attempt_id": attempt.attempt_id,
                    "strategy_id": strategy.strategy_id,
                    "status": attempt.status.value,
                    "proof_step_count": len(attempt.proof_steps),
                    "unresolved_gap_count": len(attempt.unresolved_gaps),
                },
            )
        return attempt

    async def _extract_claims_many(
        self,
        problem: ProblemContract,
        attempts: Sequence[ProofAttempt],
        runner: StructuredAgentRunner,
        prompts: PromptFactory,
        memory: LemmaMemory,
        store: ArtifactStore,
        *,
        budget_bucket: str,
    ) -> None:
        async def one(attempt: ProofAttempt) -> ClaimBatch | None:
            if attempt.status == AttemptStatus.FAILED:
                return None
            exclude = {attempt.agent_id} if len(runner.pool.agents) > 1 else set()
            summarizer = runner.pool.select("summarizer", exclude=exclude)
            bundle = prompts.summarize_claims(
                problem,
                attempt,
                self._claim_dedup_index(memory.claims),
            )
            result = await self._safe_call(
                runner,
                "summarizer",
                bundle,
                fixed_agent=summarizer,
                budget_bucket=budget_bucket,
            )
            if result is None:
                # Explorer-supplied lemmas remain proposed; no silent loss.
                if attempt.proposed_lemmas:
                    self._normalize_claims(attempt.proposed_lemmas, attempt, attempt.raw_artifact_ref)
                    memory.add_many(attempt.proposed_lemmas)
                return None
            batch: ClaimBatch = result.value
            batch.attempt_id = attempt.attempt_id
            self._normalize_claims(batch.claims, attempt, attempt.raw_artifact_ref)
            memory.add_many(batch.claims)
            store.write_json("structured", f"claim_batch_{attempt.attempt_id}", batch)
            return batch

        results = await asyncio.gather(*(one(a) for a in attempts), return_exceptions=True)
        for attempt, result in zip(attempts, results):
            if isinstance(result, Exception):
                store.append_event(
                    "claim_extraction_failed",
                    {"attempt_id": attempt.attempt_id, "error": str(result)},
                )
                if attempt.proposed_lemmas:
                    self._normalize_claims(attempt.proposed_lemmas, attempt, attempt.raw_artifact_ref)
                    memory.add_many(attempt.proposed_lemmas)
    async def _deepen_path(
        self,
        problem: ProblemContract,
        strategy_id: str,
        round_index: int,
        state: SolveState,
        runner: StructuredAgentRunner,
        prompts: PromptFactory,
        router: SparseTopologyRouter,
        memory: LemmaMemory,
        store: ArtifactStore,
    ) -> ProofAttempt | None:
        strategy = next((s for s in state.strategies if s.strategy_id == strategy_id), None)
        if strategy is None:
            return None
        previous_candidates = [a for a in state.attempts if a.strategy_id == strategy_id]
        if not previous_candidates:
            return None
        previous = max(previous_candidates, key=lambda a: a.round_index)
        try:
            agent = runner.pool.get(previous.agent_id)
        except KeyError:
            agent = runner.pool.select("explorer", specialty_hints=strategy.tags)
        feedback = self._targeted_feedback(previous, state)
        router.add_edge(
            source="meta-reviewer",
            target=agent.id,
            stage="targeted_deepening",
            payload_type="ContextPack",
            reason="repair only the selected path using verified lemmas and focused first-error feedback",
        )
        return await self._explore_path(
            problem,
            strategy,
            agent,
            round_index=round_index,
            runner=runner,
            prompts=prompts,
            router=router,
            memory=memory,
            store=store,
            targeted_feedback=feedback,
            previous_attempt=previous,
            budget_bucket="depth",
        )

    async def _widen(
        self,
        problem: ProblemContract,
        triage: TriageResult | None,
        round_index: int,
        state: SolveState,
        runner: StructuredAgentRunner,
        prompts: PromptFactory,
        router: SparseTopologyRouter,
        memory: LemmaMemory,
        store: ArtifactStore,
    ) -> list[ProofAttempt]:
        if len(state.strategies) >= self.config.budget.max_paths:
            return []
        triage = triage or self._fallback_triage()
        feedback: list[str] = []
        if state.meta_reviews:
            feedback.extend(state.meta_reviews[-1].unresolved_conflicts)
            feedback.extend(state.meta_reviews[-1].required_actions)
        count = min(2, self.config.budget.max_paths - len(state.strategies))
        result = await self._safe_call(
            runner,
            "planner",
            prompts.strategies(
                problem,
                triage,
                count,
                prior_strategy_titles=[s.title for s in state.strategies],
                regulator_feedback=feedback,
            ),
            budget_bucket="breadth",
        )
        if result is None:
            candidates = self._fallback_strategy_set(problem, count).strategies
        else:
            candidates = result.value.strategies

        genuinely_new: list[StrategyCard] = []
        for candidate in candidates:
            max_similarity = max(
                (
                    jaccard_similarity(strategy_text(candidate), strategy_text(existing))
                    for existing in state.strategies
                ),
                default=0.0,
            )
            if max_similarity < self.config.topology.strategy_similarity_threshold:
                genuinely_new.append(candidate)
        if not genuinely_new:
            return []
        selected = router.select_diverse_strategies(genuinely_new, count)
        state.strategies.extend(selected)
        assignments = router.assign_explorers(selected)
        return await self._parallel_round_exploration(
            problem,
            assignments,
            round_index,
            runner,
            prompts,
            router,
            memory,
            store,
        )

    async def _parallel_round_exploration(
        self,
        problem: ProblemContract,
        assignments: list[tuple[StrategyCard, AgentRuntime]],
        round_index: int,
        runner: StructuredAgentRunner,
        prompts: PromptFactory,
        router: SparseTopologyRouter,
        memory: LemmaMemory,
        store: ArtifactStore,
    ) -> list[ProofAttempt]:
        results = await asyncio.gather(
            *(
                self._explore_path(
                    problem,
                    strategy,
                    agent,
                    round_index=round_index,
                    runner=runner,
                    prompts=prompts,
                    router=router,
                    memory=memory,
                    store=store,
                    targeted_feedback=[],
                    previous_attempt=None,
                    budget_bucket="breadth",
                )
                for strategy, agent in assignments
            ),
            return_exceptions=True,
        )
        attempts: list[ProofAttempt] = []
        for (strategy, agent), result in zip(assignments, results):
            if isinstance(result, Exception):
                attempts.append(self._failed_attempt(problem, strategy, agent.id, round_index, result))
            else:
                attempts.append(result)
        return attempts

    async def _synthesize(
        self,
        problem: ProblemContract,
        state: SolveState,
        runner: StructuredAgentRunner,
        prompts: PromptFactory,
        router: SparseTopologyRouter,
        memory: LemmaMemory,
        store: ArtifactStore,
    ) -> tuple[FinalProof | None, AgentRuntime | None]:
        selected = self._select_for_synthesis(state)
        if not selected:
            return None, None
        review = state.meta_reviews[-1] if state.meta_reviews else self._local_meta_review(
            selected,
            state.aggregate_reports,
        )
        exclude = {a.agent_id for a in selected} if len(runner.pool.agents) > 1 else set()
        synthesizer = runner.pool.select("synthesizer", exclude=exclude)
        for attempt in selected:
            router.add_edge(
                source=attempt.agent_id,
                target=synthesizer.id,
                stage="synthesis",
                payload_type="ProofAttempt+VerificationReport",
                reason="only selected, supported paths are sent to the synthesizer",
            )
        selected_contexts = self._fit_attempt_contexts(
            selected,
            max_chars=max(5000, int(self.config.topology.max_context_chars * 0.70)),
        )
        claim_query = " ".join(
            f"{attempt.final_answer or ''} "
            + " ".join(step.statement for step in attempt.proof_steps)
            for attempt in selected
        )
        claim_context = self._select_claim_context(
            memory.verified(),
            claim_query,
            max_chars=max(2000, int(self.config.topology.max_context_chars * 0.25)),
        )
        bundle = prompts.synthesize(
            problem,
            selected_contexts,
            claim_context,
            review,
            synthesizer.id,
        )
        result = await self._safe_call(
            runner,
            "synthesizer",
            bundle,
            fixed_agent=synthesizer,
            budget_bucket="synthesis",
        )
        if result is None:
            proof = self._fallback_final_from_attempt(problem, selected[0])
        else:
            proof: FinalProof = result.value
            proof.problem_hash = problem.integrity_hash
            proof.source_attempt_ids = [
                attempt_id
                for attempt_id in proof.source_attempt_ids
                if any(a.attempt_id == attempt_id for a in selected)
            ] or [a.attempt_id for a in selected]
        store.write_json("structured", "final_proof_draft", proof)
        return proof, synthesizer
