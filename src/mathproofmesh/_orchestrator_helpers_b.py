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

class OrchestratorHelpersBMixin:
    def _fallback_final_from_attempt(
        self,
        problem: ProblemContract,
        attempt: ProofAttempt,
    ) -> FinalProof:
        answer = attempt.final_answer or "No complete proof was established; the following is the strongest partial derivation."
        caveats = list(attempt.unresolved_gaps)
        if attempt.status != AttemptStatus.COMPLETE:
            caveats.insert(0, "Source attempt is partial and has not established the full requested conclusion.")
        return FinalProof(
            problem_hash=problem.integrity_hash,
            answer=answer,
            proof_steps=attempt.proof_steps,
            dependencies=[claim.claim_id for claim in attempt.proposed_lemmas],
            caveats=self._deduplicate_strings(caveats),
            source_attempt_ids=[attempt.attempt_id],
            confidence=min(0.45, attempt.self_confidence),
        )

    def _fallback_strategy_set(self, problem: ProblemContract, count: int) -> StrategySet:
        templates = [
            (
                "Direct invariant or monotonicity route",
                "Identify a quantity preserved or monotonically changed by the hypotheses, then derive the target directly.",
                "Invariant/ordering mechanism",
                ["State the candidate invariant", "Prove preservation", "Connect it to the conclusion"],
                "Finding an invariant strong enough to force the conclusion",
                "Test the proposed invariant on small and extremal instances.",
                ["invariant", "direct"],
            ),
            (
                "Extremal-minimal counterexample route",
                "Assume failure and choose a minimal or extremal counterexample; use its extremality to force a contradiction.",
                "Minimal-counterexample mechanism",
                ["Existence of an extremal counterexample", "Reduction preserving hypotheses"],
                "Constructing a strictly smaller valid counterexample",
                "Check whether the reduction truly preserves every hypothesis.",
                ["extremal", "contradiction"],
            ),
            (
                "Structural decomposition route",
                "Decompose the object into independently checkable lemmas and solve the dependency DAG bottom-up.",
                "Lemma-DAG decomposition",
                ["Base structural lemma", "Compatibility lemma", "Assembly lemma"],
                "Avoiding circular dependencies between sublemmas",
                "Topologically sort dependencies and check each interface assumption.",
                ["decomposition", "dag"],
            ),
            (
                "Algebraic or analytic transformation route",
                "Transform the statement into an equivalent algebraic, generating-function, inequality, or analytic form.",
                "Representation-change mechanism",
                ["Prove equivalence of formulations", "Solve transformed statement"],
                "The transformation may lose boundary cases or introduce extra assumptions",
                "Verify both directions of equivalence and all domain restrictions.",
                ["algebra", "transformation"],
            ),
            (
                "Constructive algorithmic route",
                "Build the requested object or sequence explicitly and prove termination, feasibility, and optimality.",
                "Construction/algorithm mechanism",
                ["Construction", "Invariant", "Termination", "Optimality"],
                "Showing the construction handles every allowed input",
                "Run the construction on boundary cases and verify termination measure decreases.",
                ["construction", "algorithm"],
            ),
            (
                "Dual or complementary formulation route",
                "Pass to a complement, dual object, contrapositive, or equivalent game/value formulation where the obstruction is simpler.",
                "Duality/complement mechanism",
                ["Equivalence lemma", "Dual bound", "Transfer back"],
                "Proving exact equivalence rather than a one-way implication",
                "Check a small instance in both formulations and verify inverse mapping.",
                ["duality", "contrapositive"],
            ),
        ]
        strategies: list[StrategyCard] = []
        for index, template in enumerate(templates[: max(1, count)]):
            title, core, basis, lemmas, bottleneck, falsification, tags = template
            strategies.append(
                StrategyCard(
                    title=title,
                    core_idea=core,
                    independence_basis=basis,
                    expected_lemmas=lemmas,
                    bottleneck=bottleneck,
                    key_original_step=lemmas[-1] if lemmas else None,
                    falsification_test=falsification,
                    estimated_success=max(0.2, 0.55 - 0.04 * index),
                    estimated_cost=min(0.9, 0.45 + 0.05 * index),
                    tags=tags,
                )
            )
        return StrategySet(
            strategies=strategies,
            coverage_notes="Deterministic fallback spans invariant, extremal, decomposition, transformation, construction, and duality mechanisms.",
            omitted_directions=[],
        )

    def _fallback_triage(self) -> TriageResult:
        return TriageResult(
            problem_kind=ProblemKind.UNKNOWN,
            difficulty=Difficulty.HARD,
            key_risks=["unproved key step"],
            likely_tools=self._allowed_tools(),
            suggested_paths=self.config.budget.initial_paths,
            suggested_rounds=self.config.budget.max_rounds,
            proof_mode="hybrid",
            rationale="Fallback triage",
            confidence=0.2,
        )

    def _failed_attempt(
        self,
        problem: ProblemContract,
        strategy: StrategyCard,
        agent_id: str,
        round_index: int,
        error: Exception,
    ) -> ProofAttempt:
        return ProofAttempt(
            problem_hash=problem.integrity_hash,
            strategy_id=strategy.strategy_id,
            agent_id=agent_id,
            round_index=round_index,
            status=AttemptStatus.FAILED,
            dead_ends=[f"Agent execution failed: {type(error).__name__}: {error}"],
            unresolved_gaps=["No valid structured proof attempt was produced."],
            self_confidence=0.0,
        )

    def _synthetic_verification_failure(
        self,
        target_id: str,
        target_type: str,
        stage: VerificationStage,
        message: str,
        *,
        uncertain: bool = False,
    ) -> VerificationReport:
        verdict = VerificationVerdict.UNCERTAIN if uncertain else VerificationVerdict.FAIL
        issues = []
        if verdict == VerificationVerdict.FAIL:
            issues.append(
                VerificationIssue(
                    phase="orchestration",
                    severity=Severity.ERROR,
                    description=message,
                )
            )
        return VerificationReport(
            target_id=target_id,
            target_type=target_type,  # type: ignore[arg-type]
            agent_id="system-fallback",
            stage=stage,
            verdict=verdict,
            issues=issues,
            failure_level=FailureLevel.EXECUTION,
            confidence=0.25 if uncertain else 0.65,
            concise_feedback=message,
        )

    def _normalize_claims(
        self,
        claims: Sequence[ClaimCard],
        attempt: ProofAttempt,
        raw_ref: str | None,
    ) -> None:
        for claim in claims:
            claim.source_attempt_id = attempt.attempt_id
            claim.source_agent_id = attempt.agent_id
            # Extracted claims always enter as proposed. Only an aggregate verifier may upgrade them.
            claim.status = ClaimStatus.PROPOSED
            if raw_ref and not any(e.artifact_ref == raw_ref for e in claim.evidence_refs):
                claim.evidence_refs.append(
                    EvidenceRef(
                        artifact_ref=raw_ref,
                        summary="Immutable raw source response for this claim.",
                    )
                )

    @staticmethod
    def _fit_json_items(
        items: Sequence[dict[str, Any]],
        *,
        max_chars: int,
        preserve_first: bool = True,
    ) -> list[dict[str, Any]]:
        """Keep whole typed packets under a soft character budget; never truncate JSON fields."""
        selected: list[dict[str, Any]] = []
        used = 0
        for index, item in enumerate(items):
            encoded = json.dumps(item, ensure_ascii=False, separators=(",", ":"))
            size = len(encoded)
            mandatory = preserve_first and index == 0
            if not mandatory and used + size > max_chars:
                continue
            selected.append(item)
            used += size
        return selected

    def _fit_attempt_contexts(
        self,
        attempts: Sequence[ProofAttempt],
        *,
        max_chars: int,
    ) -> list[dict[str, Any]]:
        """Retain the best path in full; add other paths as compact packets while budget permits."""
        if not attempts:
            return []
        packets = [self._attempt_context_dict(attempts[0], full=True)]
        packets.extend(self._attempt_context_dict(a, full=False) for a in attempts[1:])
        return self._fit_json_items(packets, max_chars=max_chars, preserve_first=True)

    def _select_claim_context(
        self,
        claims: Sequence[ClaimCard],
        query: str,
        *,
        max_chars: int,
    ) -> list[dict[str, Any]]:
        """Rank verified claims by relevance and include dependency closures without field truncation."""
        if not claims:
            return []
        by_id = {claim.claim_id: claim for claim in claims}
        ranked = sorted(
            claims,
            key=lambda claim: (
                jaccard_similarity(
                    query,
                    f"{claim.statement} {claim.conclusion} {' '.join(claim.tags)}",
                ),
                claim.verification_confidence or 0.0,
                claim.self_confidence,
            ),
            reverse=True,
        )
        selected: list[ClaimCard] = []
        selected_ids: set[str] = set()
        used = 0

        def closure(claim: ClaimCard, visiting: set[str] | None = None) -> list[ClaimCard]:
            visiting = set(visiting or set())
            if claim.claim_id in visiting:
                return []
            visiting.add(claim.claim_id)
            ordered: list[ClaimCard] = []
            for dep_id in claim.dependencies:
                dep = by_id.get(dep_id)
                if dep is not None:
                    ordered.extend(closure(dep, visiting))
            ordered.append(claim)
            deduped: list[ClaimCard] = []
            seen: set[str] = set()
            for item in ordered:
                if item.claim_id not in seen:
                    deduped.append(item)
                    seen.add(item.claim_id)
            return deduped

        for claim in ranked:
            packet = [item for item in closure(claim) if item.claim_id not in selected_ids]
            if not packet:
                continue
            encoded = [
                json.dumps(item.model_dump(mode="json"), ensure_ascii=False, separators=(",", ":"))
                for item in packet
            ]
            packet_size = sum(len(value) for value in encoded)
            if selected and used + packet_size > max_chars:
                continue
            for item in packet:
                if item.claim_id not in selected_ids:
                    selected.append(item)
                    selected_ids.add(item.claim_id)
            used += packet_size
            if len(selected) >= self.config.topology.max_verified_claims_per_context:
                break
        return [claim.model_dump(mode="json") for claim in selected]
