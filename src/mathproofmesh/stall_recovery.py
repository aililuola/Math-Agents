from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Literal

from .agents import (
    AgentFailoverExhausted,
    BudgetExhaustedError,
    ReasoningBudgetExhaustedError,
    ReasoningOnlyStallError,
    StructuredAgentRunner,
)
from .budget import SoftBudgetAllocator
from .config import SystemConfig
from .llm.pool import ProviderCircuitOpenError
from .prompts import PromptFactory
from .schemas import (
    PostFailureBottleneckDiagnostic,
    ProblemContract,
    ProofCheckpoint,
    StrategyCard,
    WorkingProofCheckpoint,
    stable_hash,
)
from .store import ArtifactStore

NoArtifactFailureType = Literal["reasoning_budget_exhausted", "reasoning_only_stall"]


@dataclass(frozen=True, slots=True)
class BottleneckExtractionResult:
    diagnostic: PostFailureBottleneckDiagnostic
    artifact_ref: str
    reused: bool = False


def classify_no_artifact_failure(error: Exception) -> NoArtifactFailureType | None:
    """Return only failures that prove no structured artifact was produced."""

    if isinstance(error, ReasoningBudgetExhaustedError):
        return "reasoning_budget_exhausted"
    if isinstance(error, ReasoningOnlyStallError):
        return "reasoning_only_stall"
    if not isinstance(error, AgentFailoverExhausted):
        return None
    joined = "\n".join(error.errors)
    if "ReasoningBudgetExhaustedError" in joined:
        return "reasoning_budget_exhausted"
    if "ReasoningOnlyStallError" in joined:
        return "reasoning_only_stall"
    return None


class PostFailureBottleneckExtractor:
    """Diagnose a stalled route from public state, never from hidden reasoning."""

    def __init__(
        self,
        config: SystemConfig,
        runner: StructuredAgentRunner,
        prompts: PromptFactory,
        store: ArtifactStore,
    ) -> None:
        self.config = config
        self.runner = runner
        self.prompts = prompts
        self.store = store

    async def extract(
        self,
        error: Exception,
        *,
        problem: ProblemContract,
        strategy: StrategyCard,
        checkpoint: ProofCheckpoint,
        route_id: str | None,
        previous_working_checkpoint: WorkingProofCheckpoint | None,
        typed_public_context: dict[str, Any],
        has_candidate: bool,
    ) -> BottleneckExtractionResult | None:
        cfg = self.config.continuation
        failure_type = classify_no_artifact_failure(error)
        capacity_reservation_id = self._capacity_reservation_id(error)
        if not cfg.post_failure_bottleneck_enabled or failure_type is None:
            self.runner.ledger.release_capacity(capacity_reservation_id)
            return None

        recovery_key = stable_hash(
            {
                "problem_hash": problem.integrity_hash,
                "path_id": checkpoint.path_id,
                "strategy_id": strategy.strategy_id,
                "checkpoint_id": checkpoint.checkpoint_id,
                "checkpoint_hash": checkpoint.content_hash,
            }
        )
        diagnostic_name = f"post_failure_bottleneck_{recovery_key}"
        attempt_name = f"post_failure_bottleneck_attempt_{recovery_key}"

        reused = self._load_completed(diagnostic_name)
        if reused is not None:
            self.runner.ledger.release_capacity(capacity_reservation_id)
            self.store.append_event(
                "post_failure_bottleneck_reused",
                {
                    "diagnostic_id": reused.diagnostic_id,
                    "checkpoint_id": checkpoint.checkpoint_id,
                    "route_id": route_id,
                },
            )
            return BottleneckExtractionResult(
                diagnostic=reused,
                artifact_ref=self._artifact_ref("structured", diagnostic_name),
                reused=True,
            )

        if (
            cfg.post_failure_bottleneck_once_per_checkpoint
            and self.store.has_named_json("structured", attempt_name)
        ):
            self.runner.ledger.release_capacity(capacity_reservation_id)
            self.store.append_event(
                "post_failure_bottleneck_duplicate_suppressed",
                {
                    "checkpoint_id": checkpoint.checkpoint_id,
                    "route_id": route_id,
                    "recovery_key": recovery_key,
                },
            )
            return None

        blocked_reason = None
        if capacity_reservation_id is None:
            allocator = SoftBudgetAllocator(self.config, self.runner.ledger)
            blocked_reason = allocator.spend_block_reason(
                "depth",
                1,
                protect_finish=True,
                has_candidate=has_candidate,
            )
        if blocked_reason is not None:
            self.runner.ledger.release_capacity(capacity_reservation_id)
            self.store.write_json(
                "structured",
                attempt_name,
                {
                    "status": "blocked",
                    "reason": blocked_reason,
                    "checkpoint_id": checkpoint.checkpoint_id,
                    "route_id": route_id,
                    "failure_type": failure_type,
                },
            )
            self.store.append_event(
                "post_failure_bottleneck_blocked",
                {
                    "checkpoint_id": checkpoint.checkpoint_id,
                    "route_id": route_id,
                    "reason": blocked_reason,
                },
            )
            return None

        self.store.write_json(
            "structured",
            attempt_name,
            {
                "status": "started",
                "checkpoint_id": checkpoint.checkpoint_id,
                "route_id": route_id,
                "failure_type": failure_type,
                "recovery_key": recovery_key,
            },
        )
        self.store.append_event(
            "post_failure_bottleneck_started",
            {
                "checkpoint_id": checkpoint.checkpoint_id,
                "route_id": route_id,
                "failure_type": failure_type,
            },
        )
        if self.runner.activity is not None:
            self.runner.activity.info(
                "post_failure_bottleneck_started",
                title="Diagnosing a stalled proof step from the verified checkpoint",
                detail=(
                    f"{strategy.title}: the failed call returned no usable artifact; "
                    "private reasoning will not be reconstructed"
                ),
                stage="post_failure_bottleneck",
                metrics={
                    "checkpoint_id": checkpoint.checkpoint_id,
                    "route_id": route_id,
                    "failure_type": failure_type,
                },
            )

        failure_fingerprint = stable_hash(
            {
                "recovery_key": recovery_key,
                "failure_type": failure_type,
            }
        )
        recovery_output_tokens = self._recovery_output_tokens(error)
        try:
            bundle = self.prompts.post_failure_bottleneck(
                problem,
                max_output_tokens=recovery_output_tokens,
                strategy=strategy,
                verified_checkpoint=checkpoint,
                non_authoritative_working_checkpoint=previous_working_checkpoint,
                typed_public_context=self._diagnostic_context(typed_public_context),
                failure={
                    "type": failure_type,
                    "statement": "the provider returned no usable structured artifact",
                    "failure_fingerprint": failure_fingerprint,
                    "artifact_recovery_tokens": recovery_output_tokens,
                },
            )
            result = await self.runner.call(
                "meta_strategist",
                bundle,
                specialty_hints=["failure_diagnosis", *strategy.tags],
                budget_bucket="depth",
                capacity_reservation_id=capacity_reservation_id,
            )
            diagnostic = result.value
            diagnostic.diagnostic_id = f"bottleneck_{recovery_key[:12]}"
            diagnostic.problem_hash = problem.integrity_hash
            diagnostic.path_id = checkpoint.path_id
            diagnostic.route_id = route_id
            diagnostic.strategy_id = strategy.strategy_id
            diagnostic.checkpoint_id = checkpoint.checkpoint_id
            diagnostic.failure_type = failure_type
            diagnostic.failure_fingerprint = failure_fingerprint
            diagnostic.exact_failed_internal_step_known = False
            diagnostic.private_reasoning_recovered = False
            diagnostic.related_obligation_ids = self._filter_ids(
                diagnostic.related_obligation_ids,
                typed_public_context.get("open_obligations", []),
                "obligation_id",
            )
            diagnostic.preserved_verified_step_ids = self._filter_ids(
                diagnostic.preserved_verified_step_ids,
                checkpoint.verified_steps,
                "step_id",
            )
            diagnostic.preserved_fact_message_ids = self._filter_ids(
                diagnostic.preserved_fact_message_ids,
                typed_public_context.get("fact_inbox", []),
                "message_id",
            )
            artifact_ref = self.store.write_json(
                "structured", diagnostic_name, diagnostic
            )
            self.store.write_json(
                "structured",
                attempt_name,
                {
                    "status": "completed",
                    "diagnostic_id": diagnostic.diagnostic_id,
                    "artifact_ref": artifact_ref,
                    "checkpoint_id": checkpoint.checkpoint_id,
                    "route_id": route_id,
                    "failure_type": failure_type,
                },
            )
            self.store.append_event(
                "post_failure_bottleneck_extracted",
                {
                    "diagnostic_id": diagnostic.diagnostic_id,
                    "checkpoint_id": checkpoint.checkpoint_id,
                    "route_id": route_id,
                    "smallest_blocked_claim": diagnostic.smallest_blocked_claim,
                    "requires_inspiration": diagnostic.requires_inspiration,
                    "artifact_ref": artifact_ref,
                },
            )
            if self.runner.activity is not None:
                self.runner.activity.info(
                    "post_failure_bottleneck_extracted",
                    title="A route-local proof bottleneck was isolated",
                    detail=diagnostic.smallest_blocked_claim,
                    stage="post_failure_bottleneck",
                    agent_id=result.agent.id,
                    metrics={
                        "diagnostic_id": diagnostic.diagnostic_id,
                        "checkpoint_id": checkpoint.checkpoint_id,
                        "route_id": route_id,
                        "requires_inspiration": diagnostic.requires_inspiration,
                    },
                )
            return BottleneckExtractionResult(
                diagnostic=diagnostic,
                artifact_ref=artifact_ref,
            )
        except (BudgetExhaustedError, ProviderCircuitOpenError) as exc:
            self.runner.ledger.release_capacity(capacity_reservation_id)
            self._record_failure(attempt_name, checkpoint, route_id, failure_type, exc)
            return None
        except Exception as exc:
            self.runner.ledger.release_capacity(capacity_reservation_id)
            self._record_failure(attempt_name, checkpoint, route_id, failure_type, exc)
            return None

    def _load_completed(
        self, diagnostic_name: str
    ) -> PostFailureBottleneckDiagnostic | None:
        if not self.store.has_named_json("structured", diagnostic_name):
            return None
        try:
            return PostFailureBottleneckDiagnostic.model_validate(
                self.store.read_named_json("structured", diagnostic_name)
            )
        except (OSError, ValueError):
            return None

    def _record_failure(
        self,
        attempt_name: str,
        checkpoint: ProofCheckpoint,
        route_id: str | None,
        failure_type: NoArtifactFailureType,
        error: Exception,
    ) -> None:
        self.store.write_json(
            "structured",
            attempt_name,
            {
                "status": "failed",
                "checkpoint_id": checkpoint.checkpoint_id,
                "route_id": route_id,
                "failure_type": failure_type,
                "error_type": type(error).__name__,
                "error": str(error),
            },
        )
        self.store.append_event(
            "post_failure_bottleneck_failed",
            {
                "checkpoint_id": checkpoint.checkpoint_id,
                "route_id": route_id,
                "error_type": type(error).__name__,
                "error": str(error),
            },
        )

    def _artifact_ref(self, subdir: str, name: str) -> str:
        return self.store.ref(self.store.root / subdir / f"{name}.json")

    def _recovery_output_tokens(self, error: Exception) -> int:
        progress = getattr(error, "progress", None)
        configured = self.config.continuation.post_failure_bottleneck_max_output_tokens
        if not isinstance(progress, dict):
            return configured
        proposed = progress.get("artifact_recovery_tokens")
        if proposed is None:
            return configured
        try:
            tokens = int(proposed)
        except (TypeError, ValueError):
            return configured
        return max(512, min(tokens, 32000))

    @staticmethod
    def _capacity_reservation_id(error: Exception) -> str | None:
        progress = getattr(error, "progress", None)
        if not isinstance(progress, dict):
            return None
        value = progress.get("capacity_reservation_id")
        return str(value) if value else None

    @staticmethod
    def _diagnostic_context(context: dict[str, Any]) -> dict[str, Any]:
        return {
            key: context.get(key, [])
            for key in (
                "fact_inbox",
                "insight_hints",
                "negative_memory",
                "open_obligations",
            )
        }

    @staticmethod
    def _filter_ids(proposed: list[str], records: Any, id_field: str) -> list[str]:
        valid: set[str] = set()
        for record in records or []:
            if isinstance(record, dict):
                value = record.get(id_field)
            else:
                value = getattr(record, id_field, None)
            if value:
                valid.add(str(value))
        return [item for item in dict.fromkeys(proposed) if item in valid]
