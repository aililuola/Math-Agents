from __future__ import annotations

import asyncio
import copy
import json
import logging
import os
import re
import time
from dataclasses import dataclass
from collections.abc import Callable
from typing import Any, Generic, TypeVar

from pydantic import BaseModel, ValidationError

from .activity import ActivityImportance, ActivityStatus, ActivityStream, stage_label
from .config import ExplorationTierPolicyConfig, SystemConfig
from .llm.pool import (
    AgentCallFailure,
    AgentPool,
    AgentRuntime,
    ProviderCircuitOpenError,
)
from .prompts import PromptBundle, _validated_model_example, assert_blind_prompt_safe
from .reasoning_trace import (
    ReasoningTraceBinding,
    ReasoningTraceStore,
    bind_reasoning_trace,
)
from .schemas import UsageRecord
from .store import ArtifactStore

logger = logging.getLogger(__name__)

T = TypeVar("T", bound=BaseModel)


def _reasoning_trace_secrets(config: SystemConfig) -> list[str]:
    secrets: list[str] = []
    for agent in config.agents:
        if agent.api_key is not None:
            secrets.append(agent.api_key.get_secret_value())
        elif agent.api_key_env:
            value = os.getenv(agent.api_key_env)
            if value:
                secrets.append(value)
        secrets.extend(str(value) for value in agent.extra_headers.values())
    return secrets


_TWO_PHASE_SCHEMA_MARKER = "JSON SCHEMA:"
_TWO_PHASE_FORMAT_STAGE_SUFFIX = "_format"
_TWO_PHASE_FORMAT_SYSTEM = (
    "You convert a completed mathematical write-up into the required JSON "
    "structure. Do not add, remove, strengthen or weaken any mathematics. "
    "Copy bookkeeping IDs exactly as supplied. Return exactly one JSON object "
    "conforming to the supplied JSON Schema, with no markdown fences or prose "
    "outside the JSON object. Inside JSON strings, escape every LaTeX "
    "backslash as `\\\\`."
)
_TWO_PHASE_FREEFORM_INSTRUCTION = (
    "OUTPUT FORMAT FOR THIS CALL (two-phase mode, phase 1 of 2):\n"
    "Do NOT return JSON in this call; ignore every earlier instruction about "
    "returning a JSON object or escaping for JSON. Write the complete "
    "mathematical work-up in free natural language instead: the goal you "
    "attack, every definition you introduce, every proof step with its full "
    "justification and explicit dependencies, every new reusable claim, "
    "remaining gaps or subgoals, and your working notes for the next segment. "
    "Keep every mathematical and bookkeeping requirement from the "
    "instructions above (including the stage, all IDs, and the output "
    "language); only the output format changes. Be explicit and "
    "self-contained: a separate formatting call will convert this write-up "
    "verbatim into the required JSON structure and cannot ask you questions."
)


def split_trailing_schema_block(user_prompt: str) -> tuple[str, str] | None:
    """Split a factory user prompt into (head, trailing "JSON SCHEMA:..." block)."""

    index = user_prompt.rfind(_TWO_PHASE_SCHEMA_MARKER)
    if index < 0:
        return None
    return user_prompt[:index].rstrip(), user_prompt[index:].strip()


def _two_phase_bookkeeping_block(head: str) -> str:
    """Extract the bookkeeping-ID lines the formatting call must copy verbatim."""

    marker = "AUTHORITATIVE IDS:"
    index = head.find(marker)
    if index >= 0:
        return head[index:].strip()
    prefixes = (
        "The response must retain",
        "REMAINING GLOBAL CALL BUDGET:",
        "OUTPUT LANGUAGE:",
    )
    lines = [
        line.strip() for line in head.splitlines() if line.strip().startswith(prefixes)
    ]
    return "\n".join(lines)


def _two_phase_format_user(
    stage: str, head: str, schema_block: str, phase1_text: str
) -> str:
    sections = [
        f"[STAGE:{stage}{_TWO_PHASE_FORMAT_STAGE_SUFFIX}]\n"
        "Convert the completed mathematical write-up below into exactly one "
        "JSON object conforming to the schema. Preserve the mathematics "
        "verbatim: do not add, remove, strengthen or weaken any claim, step, "
        "justification, or caveat, and never invent content that the write-up "
        "does not state. Copy the bookkeeping IDs below exactly as given."
    ]
    bookkeeping = _two_phase_bookkeeping_block(head)
    if bookkeeping:
        sections.append(f"BOOKKEEPING IDS AND SETTINGS (copy exactly):\n{bookkeeping}")
    sections.append(schema_block)
    sections.append(
        "COMPLETED MATHEMATICAL WRITE-UP (phase-1 output; convert faithfully, "
        "do not solve anything new):\n"
        f"{phase1_text}"
    )
    return "\n\n".join(sections)


class BudgetExhaustedError(RuntimeError):
    pass


class StructuredOutputError(RuntimeError):
    pass


class AgentProgressError(RuntimeError):
    def __init__(
        self,
        message: str,
        *,
        usage: UsageRecord | None = None,
        progress: dict[str, Any] | None = None,
    ) -> None:
        self.usage = usage or UsageRecord()
        self.progress = dict(progress or {})
        super().__init__(message)


class AgentCallWallTimeoutError(AgentProgressError):
    pass


class AgentStreamIdleTimeoutError(AgentProgressError):
    pass


class ReasoningOnlyStallError(AgentProgressError):
    pass


class ReasoningBudgetExhaustedError(AgentProgressError):
    pass


class AgentFailoverExhausted(RuntimeError):
    def __init__(
        self,
        role: str,
        tried_agents: list[str],
        errors: list[str],
        *,
        usage: UsageRecord | None = None,
        progress: dict[str, Any] | None = None,
    ) -> None:
        self.role = role
        self.tried_agents = tried_agents
        self.errors = errors
        self.usage = usage or UsageRecord()
        self.progress = dict(progress or {})
        super().__init__(
            f"all agents failed for role={role}; tried={tried_agents}; errors={errors}"
        )


@dataclass(slots=True)
class StructuredCallResult(Generic[T]):
    value: T
    agent: AgentRuntime
    raw_ref: str
    prompt_ref: str
    usage: UsageRecord


class CallLedger:
    def __init__(self, config: SystemConfig, pool: AgentPool) -> None:
        self.config = config
        self.pool = pool
        self.calls_started = 0
        self.stage_calls: dict[str, int] = {}
        self.bucket_calls: dict[str, int] = {
            "breadth": 0,
            "depth": 0,
            "verification": 0,
            "synthesis": 0,
            "other": 0,
        }
        self.reservation_calls: dict[str, int] = {}
        self.capacity_reservations: dict[str, dict[str, int]] = {}

    def can_start(
        self, stage: str, *, capacity_reservation_id: str | None = None
    ) -> bool:
        budget = self.config.budget
        own = self.capacity_reservations.get(capacity_reservation_id or "", {})
        reserved_calls = sum(
            item["remaining_calls"] for item in self.capacity_reservations.values()
        ) - int(own.get("remaining_calls", 0))
        if self.calls_started + reserved_calls >= budget.max_total_calls:
            return False
        reserved_tokens = sum(
            item["remaining_tokens"] for item in self.capacity_reservations.values()
        ) - int(own.get("remaining_tokens", 0))
        if (
            budget.max_total_tokens is not None
            and self.pool.total_tokens() + reserved_tokens >= budget.max_total_tokens
        ):
            return False
        if (
            budget.max_cost_usd is not None
            and self.pool.total_cost_usd() >= budget.max_cost_usd
        ):
            return False
        return True

    def start(
        self,
        stage: str,
        bucket: str = "other",
        reservation_id: str | None = None,
        capacity_reservation_id: str | None = None,
    ) -> None:
        if not self.can_start(stage, capacity_reservation_id=capacity_reservation_id):
            raise BudgetExhaustedError(
                f"budget exhausted before stage={stage}: calls={self.calls_started}, "
                f"tokens={self.pool.total_tokens()}, cost=${self.pool.total_cost_usd():.4f}"
            )
        self.calls_started += 1
        self.stage_calls[stage] = self.stage_calls.get(stage, 0) + 1
        normalized_bucket = bucket if bucket in self.bucket_calls else "other"
        self.bucket_calls[normalized_bucket] += 1
        if capacity_reservation_id in self.capacity_reservations:
            capacity = self.capacity_reservations[capacity_reservation_id]
            capacity["remaining_calls"] = max(0, capacity["remaining_calls"] - 1)
        if reservation_id:
            self.reservation_calls[reservation_id] = (
                self.reservation_calls.get(reservation_id, 0) + 1
            )

    @property
    def remaining_calls(self) -> int:
        reserved = sum(
            item["remaining_calls"] for item in self.capacity_reservations.values()
        )
        return max(
            0, self.config.budget.max_total_calls - self.calls_started - reserved
        )

    def reserve_capacity(self, reservation_id: str, *, calls: int, tokens: int) -> bool:
        if reservation_id in self.capacity_reservations:
            return True
        reserved_calls = sum(
            item["remaining_calls"] for item in self.capacity_reservations.values()
        )
        if (
            self.calls_started + reserved_calls + calls
            > self.config.budget.max_total_calls
        ):
            return False
        reserved_tokens = sum(
            item["remaining_tokens"] for item in self.capacity_reservations.values()
        )
        maximum = self.config.budget.max_total_tokens
        if (
            maximum is not None
            and self.pool.total_tokens() + reserved_tokens + tokens > maximum
        ):
            return False
        self.capacity_reservations[reservation_id] = {
            "remaining_calls": max(0, int(calls)),
            "remaining_tokens": max(0, int(tokens)),
        }
        return True

    def settle_capacity_tokens(self, reservation_id: str, tokens: int) -> None:
        capacity = self.capacity_reservations.get(reservation_id)
        if capacity is None:
            return
        capacity["remaining_tokens"] = max(
            0, capacity["remaining_tokens"] - max(0, int(tokens))
        )

    def release_capacity(self, reservation_id: str | None) -> None:
        if reservation_id:
            self.capacity_reservations.pop(reservation_id, None)


class StructuredAgentRunner:
    def __init__(
        self,
        config: SystemConfig,
        pool: AgentPool,
        store: ArtifactStore,
        *,
        activity: ActivityStream | None = None,
    ) -> None:
        self.config = config
        self.pool = pool
        self.store = store
        self.activity = activity
        self.ledger = CallLedger(config, pool)
        self.reasoning_traces = ReasoningTraceStore(
            store.root,
            store.run_id,
            secrets=_reasoning_trace_secrets(config),
        )

    def persist_runtime_state(self) -> None:
        """Atomically persist budget and usage state for process-level resume."""
        self.store.write_json(
            "checkpoints",
            "runtime_ledger",
            {
                "calls_started": self.ledger.calls_started,
                "stage_calls": self.ledger.stage_calls,
                "bucket_calls": self.ledger.bucket_calls,
                "reservation_calls": self.ledger.reservation_calls,
                "capacity_reservations": self.ledger.capacity_reservations,
                "agent_metrics": self.pool.metrics(),
                "provider_circuit": self.pool.provider_circuit_state(),
            },
        )

    async def call(
        self,
        role: str,
        bundle: PromptBundle,
        *,
        fixed_agent: AgentRuntime | None = None,
        exclude: set[str] | None = None,
        specialty_hints: list[str] | None = None,
        prefer_provider_not: str | None = None,
        budget_bucket: str = "other",
        budget_reservation_id: str | None = None,
        capacity_reservation_id: str | None = None,
        reserve_artifact_recovery: bool = False,
    ) -> StructuredCallResult[Any]:
        assert_blind_prompt_safe(bundle)
        if self._two_phase_applies(bundle):
            return await self._call_two_phase(
                role,
                bundle,
                fixed_agent=fixed_agent,
                exclude=exclude,
                specialty_hints=specialty_hints,
                prefer_provider_not=prefer_provider_not,
                budget_bucket=budget_bucket,
                budget_reservation_id=budget_reservation_id,
                capacity_reservation_id=capacity_reservation_id,
            )
        agent = fixed_agent or self.pool.select(
            role,
            exclude=exclude,
            specialty_hints=specialty_hints,
            prefer_provider_not=prefer_provider_not,
        )
        schema = bundle.response_model.model_json_schema()
        prompt_ref = self.store.save_prompt(
            bundle.stage, agent.id, bundle.system, bundle.user
        )
        messages = [
            {"role": "system", "content": bundle.system},
            {"role": "user", "content": bundle.user},
        ]

        activity_task: str | None = None
        if (
            self.activity is not None
            and self.config.runtime.activity_include_agent_calls
        ):
            zh = self.activity.is_zh
            activity_task = self.activity.start_task(
                "agent_call",
                title=stage_label(bundle.stage, self.config.runtime.output_language),
                detail=(
                    f"{agent.id} 正在处理结构化任务"
                    if zh
                    else f"{agent.id} is processing a structured task"
                ),
                stage=bundle.stage,
                agent_id=agent.id,
                importance=ActivityImportance.NORMAL,
                metrics={
                    "role": role,
                    "response_model": bundle.response_model.__name__,
                    "budget_bucket": budget_bucket,
                },
            )

        last_error: Exception | None = None
        response_text = ""
        raw_ref = ""
        total_usage = UsageRecord()
        recovery_reservation_id = capacity_reservation_id
        recovery_capacity_settled = False
        try:
            max_parse_retries = self.config.runtime.parse_retries
            if bundle.response_model.__name__ in {
                "ContinuationTurn",
                "ProofDelta",
                "GoalNormalizationAssessment",
            }:
                max_parse_retries = min(max_parse_retries, 1)
            for parse_attempt in range(max_parse_retries + 1):
                effective_max_output_tokens = self._effective_output_limit(
                    bundle,
                    agent,
                    repair=parse_attempt > 0,
                )
                tier_policy = (
                    self._tier_policy_for_bundle(bundle, effective_max_output_tokens)
                    if parse_attempt == 0
                    else None
                )
                if (
                    reserve_artifact_recovery
                    and tier_policy is not None
                    and recovery_reservation_id is None
                ):
                    recovery_reservation_id = (
                        f"artifact-recovery:{agent.id}:{time.time_ns()}"
                    )
                    recovery_tokens = tier_policy.artifact_recovery_tokens
                    if not self.ledger.reserve_capacity(
                        recovery_reservation_id,
                        calls=2,
                        tokens=effective_max_output_tokens + recovery_tokens,
                    ):
                        recovery_reservation_id = None
                        raise BudgetExhaustedError(
                            "budget cannot reserve both the deep route call and its "
                            f"{recovery_tokens}-token artifact recovery"
                        )
                    recovery_capacity_settled = False
                self.ledger.start(
                    bundle.stage,
                    budget_bucket,
                    reservation_id=budget_reservation_id,
                    capacity_reservation_id=recovery_reservation_id,
                )
                self.persist_runtime_state()
                thinking_enabled, reasoning_effort = self._thinking_policy(
                    bundle,
                    effective_max_output_tokens=effective_max_output_tokens,
                    repair=parse_attempt > 0,
                )
                if parse_attempt > 0:
                    repair_system = (
                        "You repair malformed structured output. Return only one JSON object matching the schema. "
                        "Do not change mathematical content except where needed to satisfy field types and required fields. "
                        "A ClaimCard.proof_steps entry must be a complete ProofStep object, not a string ID. "
                        "Never invent a missing final answer: use proof_complete=false and submit_delta when only a "
                        "reviewable proof prefix exists. "
                        "If the malformed output looks truncated (unbalanced braces or a trailing incomplete token), "
                        "return the honest partial structure rather than inventing the missing content."
                    )
                    repair_user = (
                        f"[STAGE:{bundle.stage}_json_repair]\n"
                        f"JSON SCHEMA:\n{json.dumps(schema, ensure_ascii=False, indent=2)}\n\n"
                        "MINIMAL JSON SHAPE EXAMPLE:\n"
                        f"{json.dumps(_validated_model_example(bundle.response_model, schema), ensure_ascii=False, indent=2)}\n\n"
                        f"MALFORMED OUTPUT:\n{response_text}\n\n"
                        f"VALIDATION ERROR:\n{last_error}\n\n"
                        "ORIGINAL TASK CONTEXT (immutable excerpt for reference only; "
                        "do not answer it, only preserve its mathematical content):\n"
                        f"[STAGE:{bundle.stage}]\n"
                        f"{bundle.user[:1200]}"
                    )
                    messages = [
                        {"role": "system", "content": repair_system},
                        {"role": "user", "content": repair_user},
                    ]
                    prompt_ref = self.store.save_prompt(
                        f"{bundle.stage}_json_repair_{parse_attempt}",
                        agent.id,
                        repair_system,
                        repair_user,
                    )
                    if activity_task and self.activity is not None:
                        self.activity.update_task(
                            activity_task,
                            title=stage_label(
                                bundle.stage, self.config.runtime.output_language
                            ),
                            detail=(
                                f"{agent.id} 正在修复第 {parse_attempt} 次结构化输出"
                                if self.activity.is_zh
                                else f"{agent.id} is repairing structured output (attempt {parse_attempt})"
                            ),
                            status=ActivityStatus.RUNNING,
                            event_type="agent_call_retry",
                            stage=bundle.stage,
                            agent_id=agent.id,
                            importance=ActivityImportance.DETAIL,
                            metrics={"parse_attempt": parse_attempt},
                        )

                response = await self._call_with_activity_heartbeat(
                    agent,
                    messages,
                    temperature=bundle.temperature,
                    max_output_tokens=effective_max_output_tokens,
                    json_mode=True,
                    schema_name=bundle.response_model.__name__,
                    schema=schema,
                    thinking_enabled=thinking_enabled,
                    reasoning_effort=reasoning_effort,
                    activity_task=activity_task,
                    stage=bundle.stage,
                    tier_policy=tier_policy,
                )
                if recovery_reservation_id and not recovery_capacity_settled:
                    self.ledger.settle_capacity_tokens(
                        recovery_reservation_id, effective_max_output_tokens
                    )
                    recovery_capacity_settled = True
                response_text = response.text
                raw_evidence = self.store.write_content_addressed(
                    "raw",
                    {
                        "agent_id": agent.id,
                        "provider": response.provider,
                        "model": response.model,
                        "request_id": response.request_id,
                        "stage": bundle.stage,
                        "text": response.text,
                        "usage": {
                            "input_tokens": response.input_tokens,
                            "output_tokens": response.output_tokens,
                            "latency_ms": response.latency_ms,
                        },
                        "provider_metadata": (
                            response.raw
                            if self.config.runtime.save_raw_provider_responses
                            else {}
                        ),
                        "request_policy": {
                            "thinking_enabled": thinking_enabled,
                            "reasoning_effort": reasoning_effort,
                            "max_output_tokens": effective_max_output_tokens,
                        },
                    },
                    summary=f"Raw response for {bundle.stage} from {agent.id}",
                )
                raw_ref = raw_evidence.artifact_ref
                response_cost = (
                    response.input_tokens
                    / 1_000_000
                    * agent.config.pricing.input_per_million
                    + response.output_tokens
                    / 1_000_000
                    * agent.config.pricing.output_per_million
                )
                total_usage = self._sum_usage(
                    total_usage,
                    UsageRecord(
                        input_tokens=response.input_tokens,
                        output_tokens=response.output_tokens,
                        total_tokens=response.total_tokens,
                        estimated_cost_usd=response_cost,
                        latency_ms=response.latency_ms,
                    ),
                )
                self.persist_runtime_state()
                finish_reason = str(response.raw.get("finish_reason") or "")
                if finish_reason == "length" and not response.text.strip():
                    recovery_tokens = (
                        tier_policy.artifact_recovery_tokens
                        if tier_policy is not None
                        else self.config.continuation.post_failure_bottleneck_max_output_tokens
                    )
                    self._record_runner_failure(agent, "reasoning_budget_exhausted")
                    self.store.append_event(
                        "reasoning_budget_exhausted",
                        {
                            "stage": bundle.stage,
                            "agent_id": agent.id,
                            "raw_ref": raw_ref,
                            "output_tokens": response.output_tokens,
                            "max_output_tokens": effective_max_output_tokens,
                            "finish_reason": finish_reason,
                            "artifact_recovery_tokens": recovery_tokens,
                            "capacity_reservation_id": recovery_reservation_id,
                            "recovery": "bounded_non_thinking_diagnostic_from_external_checkpoint",
                        },
                    )
                    raise ReasoningBudgetExhaustedError(
                        f"{agent.id} exhausted {effective_max_output_tokens} output "
                        "tokens without returning a structured artifact",
                        usage=self._copy_usage(total_usage),
                        progress={
                            "approx_output_tokens": response.output_tokens,
                            "content_characters": 0,
                            "finish_reason": finish_reason,
                            "artifact_recovery_tokens": recovery_tokens,
                            "capacity_reservation_id": recovery_reservation_id,
                        },
                    )
                try:
                    payload = extract_json_object(response.text)
                    self._strip_server_owned_hashes(payload)
                    normalization_actions = self._normalize_continuation_payload(
                        payload,
                        response_model=bundle.response_model,
                    )
                    value = bundle.response_model.model_validate(payload)
                    self._attach_metadata(value, raw_ref, total_usage)
                    if normalization_actions:
                        self.store.append_event(
                            "structured_output_normalized",
                            {
                                "stage": bundle.stage,
                                "agent_id": agent.id,
                                "raw_ref": raw_ref,
                                "response_model": bundle.response_model.__name__,
                                "actions": normalization_actions,
                            },
                        )
                    self.store.append_event(
                        "agent_call_completed",
                        {
                            "stage": bundle.stage,
                            "agent_id": agent.id,
                            "raw_ref": raw_ref,
                            "prompt_ref": prompt_ref,
                            "response_model": bundle.response_model.__name__,
                            "usage": total_usage,
                        },
                    )
                    if activity_task and self.activity is not None:
                        self.activity.complete_task(
                            activity_task,
                            title=stage_label(
                                bundle.stage, self.config.runtime.output_language
                            ),
                            detail=(
                                f"{agent.id} 完成；{total_usage.total_tokens:,} tokens，"
                                f"模型耗时 {total_usage.latency_ms / 1000:.1f} 秒"
                                if self.activity.is_zh
                                else (
                                    f"{agent.id} completed; {total_usage.total_tokens:,} tokens, "
                                    f"{total_usage.latency_ms / 1000:.1f}s model latency"
                                )
                            ),
                            event_type="agent_call_completed",
                            stage=bundle.stage,
                            agent_id=agent.id,
                            importance=ActivityImportance.NORMAL,
                            metrics={
                                "input_tokens": total_usage.input_tokens,
                                "output_tokens": total_usage.output_tokens,
                                "total_tokens": total_usage.total_tokens,
                                "latency_ms": total_usage.latency_ms,
                            },
                        )
                    self.ledger.release_capacity(recovery_reservation_id)
                    self.persist_runtime_state()
                    return StructuredCallResult(
                        value=value,
                        agent=agent,
                        raw_ref=raw_ref,
                        prompt_ref=prompt_ref,
                        usage=total_usage,
                    )
                except (json.JSONDecodeError, ValidationError, ValueError) as exc:
                    if finish_reason == "length":
                        # A truncated artifact must never reach JSON repair: the
                        # repair model cannot recover the missing tail and would
                        # fabricate it. Route to the same bounded recovery as an
                        # empty budget-exhausted response.
                        recovery_tokens = (
                            tier_policy.artifact_recovery_tokens
                            if tier_policy is not None
                            else self.config.continuation.post_failure_bottleneck_max_output_tokens
                        )
                        self._record_runner_failure(agent, "reasoning_budget_exhausted")
                        self.store.append_event(
                            "reasoning_budget_exhausted",
                            {
                                "stage": bundle.stage,
                                "agent_id": agent.id,
                                "raw_ref": raw_ref,
                                "output_tokens": response.output_tokens,
                                "max_output_tokens": effective_max_output_tokens,
                                "finish_reason": finish_reason,
                                "truncated": True,
                                "parse_error": str(exc),
                                "artifact_recovery_tokens": recovery_tokens,
                                "capacity_reservation_id": recovery_reservation_id,
                                "recovery": "bounded_non_thinking_diagnostic_from_external_checkpoint",
                            },
                        )
                        raise ReasoningBudgetExhaustedError(
                            f"{agent.id} exhausted {effective_max_output_tokens} "
                            "output tokens; the truncated artifact failed to parse "
                            "and was not sent to JSON repair",
                            usage=self._copy_usage(total_usage),
                            progress={
                                "approx_output_tokens": response.output_tokens,
                                "content_characters": len(response.text),
                                "finish_reason": finish_reason,
                                "truncated": True,
                                "artifact_recovery_tokens": recovery_tokens,
                                "capacity_reservation_id": recovery_reservation_id,
                            },
                        ) from exc
                    self.ledger.release_capacity(recovery_reservation_id)
                    recovery_reservation_id = None
                    self._record_runner_failure(agent, "schema")
                    last_error = exc
                    logger.warning(
                        "Structured output validation failed at stage=%s agent=%s: %s",
                        bundle.stage,
                        agent.id,
                        exc,
                    )
                    self.store.append_event(
                        "structured_output_error",
                        {
                            "stage": bundle.stage,
                            "agent_id": agent.id,
                            "raw_ref": raw_ref,
                            "error": str(exc),
                            "parse_attempt": parse_attempt,
                        },
                    )
                    if activity_task and self.activity is not None:
                        self.activity.warn_task(
                            activity_task,
                            title=stage_label(
                                bundle.stage, self.config.runtime.output_language
                            ),
                            detail=(
                                f"{agent.id} 的结构化输出未通过校验，准备定向修复"
                                if self.activity.is_zh
                                else f"{agent.id} returned invalid structured output; preparing a repair"
                            ),
                            event_type="structured_output_warning",
                            stage=bundle.stage,
                            agent_id=agent.id,
                            importance=ActivityImportance.DETAIL,
                            metrics={"parse_attempt": parse_attempt},
                        )
                    if parse_attempt >= max_parse_retries:
                        break
            raise StructuredOutputError(
                f"agent {agent.id} could not produce valid "
                f"{bundle.response_model.__name__}: {last_error}"
            ) from last_error
        except Exception as exc:
            retained_for_diagnostic = isinstance(
                exc, (ReasoningBudgetExhaustedError, ReasoningOnlyStallError)
            )
            if retained_for_diagnostic and recovery_reservation_id:
                progress = getattr(exc, "progress", None)
                if isinstance(progress, dict):
                    progress.setdefault(
                        "capacity_reservation_id", recovery_reservation_id
                    )
            else:
                self.ledger.release_capacity(recovery_reservation_id)
            failure_usage = getattr(exc, "usage", None)
            if isinstance(failure_usage, UsageRecord):
                # Streaming failures carry only the interrupted request. A failure
                # after a complete provider response already carries total_usage.
                progress = getattr(exc, "progress", {})
                if total_usage.total_tokens and not progress.get("finish_reason"):
                    setattr(
                        exc,
                        "usage",
                        self._sum_usage(total_usage, failure_usage),
                    )
            self.persist_runtime_state()
            if activity_task and self.activity is not None:
                self.activity.fail_task(
                    activity_task,
                    title=stage_label(
                        bundle.stage, self.config.runtime.output_language
                    ),
                    detail=(
                        f"{agent.id} 未完成当前任务：{type(exc).__name__}"
                        if self.activity.is_zh
                        else f"{agent.id} did not complete the task: {type(exc).__name__}"
                    ),
                    event_type="agent_call_failed",
                    stage=bundle.stage,
                    agent_id=agent.id,
                    importance=ActivityImportance.NORMAL,
                    metrics={"error_type": type(exc).__name__},
                )
            raise

    async def call_with_failover(
        self,
        role: str,
        bundle_factory: Callable[[AgentRuntime], PromptBundle],
        *,
        primary_agent: AgentRuntime,
        specialty_hints: list[str] | None = None,
        budget_bucket: str = "other",
        max_failover_agents: int = 0,
        allow_failover: bool = True,
        failover_only_on_retryable: bool = True,
        exclude_agent_ids: set[str] | None = None,
    ) -> tuple[StructuredCallResult[Any], list[str]]:
        """Try one key with its normal retries, then move the same task to backup keys."""
        tried: list[str] = []
        errors: list[str] = []
        failed_usage = UsageRecord()
        failure_progress: dict[str, Any] = {}
        excluded = set(exclude_agent_ids or set())
        if primary_agent.id in excluded:
            raise ValueError(
                f"primary agent {primary_agent.id!r} is excluded from role={role}"
            )
        candidates = [primary_agent]
        if allow_failover and max_failover_agents > 0:
            candidates.extend(
                self.pool.failover_candidates(
                    role,
                    exclude={primary_agent.id, *excluded},
                    specialty_hints=specialty_hints,
                    prefer_provider_not=primary_agent.provider,
                    limit=max_failover_agents,
                )
            )

        for index, agent in enumerate(candidates):
            tried.append(agent.id)
            if index > 0:
                self.store.append_event(
                    "agent_failover_started",
                    {
                        "role": role,
                        "from_agent_id": tried[-2],
                        "to_agent_id": agent.id,
                        "attempt_index": index,
                    },
                )
                if self.activity is not None:
                    self.activity.info(
                        "agent_failover",
                        title=self.activity.text(
                            "原 API 重试耗尽，切换备用 Agent",
                            "Primary API retries exhausted; switching to a backup agent",
                        ),
                        detail=self.activity.text(
                            f"{tried[-2]} → {agent.id}；从同一已验证检查点继续",
                            f"{tried[-2]} → {agent.id}; continuing from the same verified checkpoint",
                        ),
                        stage="agent_failover",
                        agent_id=agent.id,
                        importance=ActivityImportance.MAJOR,
                        metrics={"tried_agents": list(tried)},
                    )
            try:
                bundle = bundle_factory(agent)
                result = await self.call(
                    role,
                    bundle,
                    fixed_agent=agent,
                    budget_bucket=budget_bucket,
                    reserve_artifact_recovery=True,
                )
                if index > 0:
                    self.store.append_event(
                        "agent_failover_succeeded",
                        {
                            "role": role,
                            "agent_id": agent.id,
                            "tried_agents": list(tried),
                        },
                    )
                if failed_usage.total_tokens:
                    result.usage = self._sum_usage(failed_usage, result.usage)
                    if hasattr(result.value, "usage"):
                        result.value.usage = self._copy_usage(result.usage)
                return result, tried
            except (BudgetExhaustedError, ProviderCircuitOpenError):
                raise
            except (
                AgentCallFailure,
                StructuredOutputError,
                RuntimeError,
                ValueError,
            ) as exc:
                exc_usage = getattr(exc, "usage", None)
                if isinstance(exc_usage, UsageRecord):
                    failed_usage = self._sum_usage(failed_usage, exc_usage)
                exc_progress = getattr(exc, "progress", None)
                if isinstance(exc_progress, dict):
                    failure_progress = dict(exc_progress)
                errors.append(f"{agent.id}:{type(exc).__name__}:{exc}")
                self.store.append_event(
                    "agent_failover_candidate_failed",
                    {
                        "role": role,
                        "agent_id": agent.id,
                        "error_type": type(exc).__name__,
                        "error": str(exc),
                        "retryable": getattr(exc, "retryable", None),
                    },
                )
                if (
                    failover_only_on_retryable
                    and isinstance(exc, AgentCallFailure)
                    and not exc.retryable
                    and exc.status_code not in {401, 403}
                ):
                    break
                if isinstance(
                    exc,
                    (ReasoningBudgetExhaustedError, ReasoningOnlyStallError),
                ):
                    # Another full Explorer call cannot recover private reasoning and
                    # would merely repeat the same expensive semantic failure. The
                    # orchestrator gets one small non-thinking public-state diagnosis.
                    self.store.append_event(
                        "semantic_failover_suppressed",
                        {
                            "role": role,
                            "agent_id": agent.id,
                            "error_type": type(exc).__name__,
                            "artifact_recovery_tokens": failure_progress.get(
                                "artifact_recovery_tokens"
                            ),
                        },
                    )
                    break
                # Authentication/authorization failures are not retried on the
                # same key, but a different configured key may still be valid.
                continue

        raise AgentFailoverExhausted(
            role,
            tried,
            errors,
            usage=failed_usage,
            progress=failure_progress,
        )

    def _two_phase_applies(self, bundle: PromptBundle) -> bool:
        runtime = self.config.runtime
        if not runtime.two_phase_output:
            return False
        if bundle.stage.endswith(_TWO_PHASE_FORMAT_STAGE_SUFFIX):
            # The internally generated formatting stage must never recurse.
            return False
        if bundle.stage not in runtime.two_phase_stages:
            return False
        return _TWO_PHASE_SCHEMA_MARKER in bundle.user

    async def _call_two_phase(
        self,
        role: str,
        bundle: PromptBundle,
        *,
        fixed_agent: AgentRuntime | None,
        exclude: set[str] | None,
        specialty_hints: list[str] | None,
        prefer_provider_not: str | None,
        budget_bucket: str,
        budget_reservation_id: str | None,
        capacity_reservation_id: str | None,
    ) -> StructuredCallResult[Any]:
        """Phase 1 writes free-form mathematics; phase 2 formats it as JSON.

        Both phases are separate ledger entries in the same budget bucket. The
        deep-route artifact-recovery capacity reservation is intentionally not
        made here: free text truncates gracefully, so a truncated phase-1
        write-up still proceeds to formatting instead of a bounded diagnostic.
        """

        split = split_trailing_schema_block(bundle.user)
        if split is None:  # pragma: no cover - guarded by _two_phase_applies
            raise StructuredOutputError(
                f"stage {bundle.stage} has no trailing JSON schema block"
            )
        head, schema_block = split
        agent = fixed_agent or self.pool.select(
            role,
            exclude=exclude,
            specialty_hints=specialty_hints,
            prefer_provider_not=prefer_provider_not,
        )
        phase1_user = f"{head}\n\n{_TWO_PHASE_FREEFORM_INSTRUCTION}"
        phase1_prompt_ref = self.store.save_prompt(
            f"{bundle.stage}_two_phase_freeform", agent.id, bundle.system, phase1_user
        )
        messages = [
            {"role": "system", "content": bundle.system},
            {"role": "user", "content": phase1_user},
        ]
        effective_max_output_tokens = self._effective_output_limit(
            bundle, agent, repair=False
        )
        tier_policy = self._tier_policy_for_bundle(bundle, effective_max_output_tokens)
        thinking_enabled, reasoning_effort = self._thinking_policy(
            bundle,
            effective_max_output_tokens=effective_max_output_tokens,
            repair=False,
        )
        activity_task: str | None = None
        if (
            self.activity is not None
            and self.config.runtime.activity_include_agent_calls
        ):
            activity_task = self.activity.start_task(
                "agent_call",
                title=stage_label(bundle.stage, self.config.runtime.output_language),
                detail=(
                    f"{agent.id} 正在自由撰写数学推理（两阶段输出：第 1/2 步）"
                    if self.activity.is_zh
                    else (
                        f"{agent.id} is writing free-form mathematics "
                        "(two-phase output, phase 1/2)"
                    )
                ),
                stage=bundle.stage,
                agent_id=agent.id,
                importance=ActivityImportance.NORMAL,
                metrics={
                    "role": role,
                    "response_model": bundle.response_model.__name__,
                    "budget_bucket": budget_bucket,
                    "two_phase": True,
                },
            )
        self.ledger.start(
            bundle.stage,
            budget_bucket,
            reservation_id=budget_reservation_id,
            capacity_reservation_id=capacity_reservation_id,
        )
        self.persist_runtime_state()
        try:
            response = await self._call_with_activity_heartbeat(
                agent,
                messages,
                temperature=bundle.temperature,
                max_output_tokens=effective_max_output_tokens,
                json_mode=False,
                schema_name=bundle.response_model.__name__,
                schema=None,
                thinking_enabled=thinking_enabled,
                reasoning_effort=reasoning_effort,
                activity_task=activity_task,
                stage=bundle.stage,
                tier_policy=tier_policy,
            )
            phase1_cost = (
                response.input_tokens
                / 1_000_000
                * agent.config.pricing.input_per_million
                + response.output_tokens
                / 1_000_000
                * agent.config.pricing.output_per_million
            )
            phase1_usage = UsageRecord(
                input_tokens=response.input_tokens,
                output_tokens=response.output_tokens,
                total_tokens=response.total_tokens,
                estimated_cost_usd=phase1_cost,
                latency_ms=response.latency_ms,
            )
            phase1_raw = self.store.write_content_addressed(
                "raw",
                {
                    "agent_id": agent.id,
                    "provider": response.provider,
                    "model": response.model,
                    "request_id": response.request_id,
                    "stage": bundle.stage,
                    "two_phase": "freeform",
                    "text": response.text,
                    "usage": {
                        "input_tokens": response.input_tokens,
                        "output_tokens": response.output_tokens,
                        "latency_ms": response.latency_ms,
                    },
                    "provider_metadata": (
                        response.raw
                        if self.config.runtime.save_raw_provider_responses
                        else {}
                    ),
                    "request_policy": {
                        "thinking_enabled": thinking_enabled,
                        "reasoning_effort": reasoning_effort,
                        "max_output_tokens": effective_max_output_tokens,
                    },
                },
                summary=(
                    f"Raw phase-1 free-form response for {bundle.stage} from {agent.id}"
                ),
            )
            self.persist_runtime_state()
            phase1_text = response.text.strip()
            finish_reason = str(response.raw.get("finish_reason") or "")
            if not phase1_text:
                recovery_tokens = (
                    tier_policy.artifact_recovery_tokens
                    if tier_policy is not None
                    else self.config.continuation.post_failure_bottleneck_max_output_tokens
                )
                self._record_runner_failure(agent, "reasoning_budget_exhausted")
                self.store.append_event(
                    "reasoning_budget_exhausted",
                    {
                        "stage": bundle.stage,
                        "agent_id": agent.id,
                        "raw_ref": phase1_raw.artifact_ref,
                        "output_tokens": response.output_tokens,
                        "max_output_tokens": effective_max_output_tokens,
                        "finish_reason": finish_reason,
                        "two_phase": "freeform",
                        "artifact_recovery_tokens": recovery_tokens,
                        "recovery": "bounded_non_thinking_diagnostic_from_external_checkpoint",
                    },
                )
                raise ReasoningBudgetExhaustedError(
                    f"{agent.id} exhausted {effective_max_output_tokens} output "
                    "tokens without returning a phase-1 mathematical write-up",
                    usage=self._copy_usage(phase1_usage),
                    progress={
                        "approx_output_tokens": response.output_tokens,
                        "content_characters": 0,
                        "finish_reason": finish_reason,
                        "artifact_recovery_tokens": recovery_tokens,
                    },
                )
            self.store.append_event(
                "two_phase_freeform_completed",
                {
                    "stage": bundle.stage,
                    "agent_id": agent.id,
                    "raw_ref": phase1_raw.artifact_ref,
                    "prompt_ref": phase1_prompt_ref,
                    "finish_reason": finish_reason,
                    "usage": phase1_usage,
                },
            )
            if activity_task and self.activity is not None:
                self.activity.complete_task(
                    activity_task,
                    title=stage_label(
                        bundle.stage, self.config.runtime.output_language
                    ),
                    detail=(
                        f"{agent.id} 完成自由推理，准备第 2 步结构化转换"
                        if self.activity.is_zh
                        else (
                            f"{agent.id} finished the free-form write-up; "
                            "formatting phase follows"
                        )
                    ),
                    event_type="agent_call_completed",
                    stage=bundle.stage,
                    agent_id=agent.id,
                    importance=ActivityImportance.NORMAL,
                    metrics={
                        "input_tokens": phase1_usage.input_tokens,
                        "output_tokens": phase1_usage.output_tokens,
                        "total_tokens": phase1_usage.total_tokens,
                        "latency_ms": phase1_usage.latency_ms,
                        "two_phase": True,
                    },
                )
        except Exception as exc:
            self.persist_runtime_state()
            if activity_task and self.activity is not None:
                self.activity.fail_task(
                    activity_task,
                    title=stage_label(
                        bundle.stage, self.config.runtime.output_language
                    ),
                    detail=(
                        f"{agent.id} 未完成自由推理阶段：{type(exc).__name__}"
                        if self.activity.is_zh
                        else (
                            f"{agent.id} did not complete the free-form phase: "
                            f"{type(exc).__name__}"
                        )
                    ),
                    event_type="agent_call_failed",
                    stage=bundle.stage,
                    agent_id=agent.id,
                    importance=ActivityImportance.NORMAL,
                    metrics={"error_type": type(exc).__name__},
                )
            raise

        format_bundle = PromptBundle(
            stage=f"{bundle.stage}{_TWO_PHASE_FORMAT_STAGE_SUFFIX}",
            system=_TWO_PHASE_FORMAT_SYSTEM,
            user=_two_phase_format_user(bundle.stage, head, schema_block, phase1_text),
            response_model=bundle.response_model,
            temperature=0.0,
            max_output_tokens=bundle.max_output_tokens,
            output_tier=bundle.output_tier,
        )
        result = await self.call(
            role,
            format_bundle,
            fixed_agent=agent,
            budget_bucket=budget_bucket,
            budget_reservation_id=budget_reservation_id,
            capacity_reservation_id=capacity_reservation_id,
        )
        result.usage = self._sum_usage(phase1_usage, result.usage)
        if hasattr(result.value, "usage"):
            result.value.usage = self._copy_usage(result.usage)
        self.store.append_event(
            "two_phase_completed",
            {
                "stage": bundle.stage,
                "agent_id": agent.id,
                "phase1_raw_ref": phase1_raw.artifact_ref,
                "phase1_prompt_ref": phase1_prompt_ref,
                "phase2_raw_ref": result.raw_ref,
                "phase2_prompt_ref": result.prompt_ref,
                "usage": result.usage,
            },
        )
        return result

    def _effective_output_limit(
        self,
        bundle: PromptBundle,
        agent: AgentRuntime,
        *,
        repair: bool,
    ) -> int:
        limits = [
            agent.config.max_output_tokens,
            agent.config.provider_max_output_tokens,
        ]
        if bundle.max_output_tokens is not None:
            limits.append(bundle.max_output_tokens)
        stage_limit = self.config.runtime.stage_output_token_limits.get(bundle.stage)
        if stage_limit is not None:
            limits.append(stage_limit)
        if bundle.output_tier is not None:
            tiers = self.config.runtime.exploration_output_token_tiers
            limits.append(tiers[min(bundle.output_tier, len(tiers) - 1)])
        if repair:
            limits.append(self.config.runtime.json_repair_max_output_tokens)
        return max(256, min(limits))

    def _thinking_policy(
        self,
        bundle: PromptBundle,
        *,
        effective_max_output_tokens: int,
        repair: bool,
    ) -> tuple[bool | None, str | None]:
        if repair:
            return False, None
        mode = self.config.runtime.stage_thinking_modes.get(
            bundle.stage, "agent_default"
        )
        if mode == "agent_default":
            return None, None
        if mode == "disabled":
            return False, None
        if mode == "tiered":
            effort = (
                "max"
                if effective_max_output_tokens
                >= self.config.deep_exploration_policy.high_tier_threshold_tokens
                else "high"
            )
            return True, effort
        return True, mode

    def _tier_policy_for_bundle(
        self, bundle: PromptBundle, effective_max_output_tokens: int
    ) -> ExplorationTierPolicyConfig | None:
        if not self.config.deep_exploration_policy.enabled or bundle.stage not in {
            "independent_exploration",
            "route_prove",
            "proof_continuation",
        }:
            return None
        return self.config.deep_exploration_policy.tier_for_limit(
            effective_max_output_tokens
        )

    @staticmethod
    def _record_runner_failure(agent: AgentRuntime, category: str) -> None:
        agent.failed_attempts += 1
        agent.failure_categories[category] = (
            agent.failure_categories.get(category, 0) + 1
        )

    @classmethod
    def _strip_server_owned_hashes(cls, value: Any) -> None:
        """Discard model-authored fields that only the server may attest."""

        if isinstance(value, dict):
            value.pop("kind_migration", None)
            for key in (
                "content_hash",
                "normalized_hash",
                "request_hash",
                "code_hash",
                "result_hash",
                "semantic_hash",
            ):
                if key in value:
                    value[key] = ""
            for item in value.values():
                cls._strip_server_owned_hashes(item)
        elif isinstance(value, list):
            for item in value:
                cls._strip_server_owned_hashes(item)

    @staticmethod
    def _normalize_continuation_payload(
        payload: dict[str, Any],
        *,
        response_model: type[BaseModel],
    ) -> list[str]:
        """Apply only lossless or conservative repairs before schema validation."""

        model_name = response_model.__name__
        actions = StructuredAgentRunner._normalize_dependency_ref_kinds(payload)
        if model_name == "MetaStrategyDecision":
            aliases = {
                "invent_auxiliary_construction": "auxiliary_construction",
                "search_analogy": "structural_analogy",
                "switch_representation": "representation_switch",
                "rewrite_plan": "meta_replan",
            }
            selected = payload.get("selected_mechanism")
            action = payload.get("action")
            canonical = aliases.get(str(selected))
            if canonical is not None and selected == action:
                payload["selected_mechanism"] = canonical
                actions.append(
                    f"canonicalized selected_mechanism {selected} to {canonical}"
                )
            return actions

        if model_name in {"ContinuationTurn", "InitialExplorationTurn"} and (
            payload.get("action") == "request_computation"
        ):
            if payload.get("experiment_impact") is not None:
                payload["experiment_impact"] = None
                actions.append(
                    "cleared premature experiment_impact for request_computation"
                )
            experiment_spec = payload.get("experiment_spec")
            if (
                isinstance(experiment_spec, dict)
                and experiment_spec.get("purpose") == "discover_pattern"
                and experiment_spec.get("broad_search") is not True
            ):
                experiment_spec["broad_search"] = True
                actions.append("marked discover_pattern computation as broad_search")

        if model_name not in {"ContinuationTurn", "ProofDelta"}:
            return actions

        is_turn = model_name == "ContinuationTurn"
        raw_delta = payload.get("delta") if is_turn else payload
        if not isinstance(raw_delta, dict):
            return actions

        step_by_id: dict[str, dict[str, Any]] = {}
        raw_steps = raw_delta.get("new_steps")
        if isinstance(raw_steps, list):
            for step in raw_steps:
                if not isinstance(step, dict):
                    continue
                step_id = step.get("step_id")
                if isinstance(step_id, str):
                    step_by_id[step_id] = step

        raw_claims = raw_delta.get("new_claims")
        if isinstance(raw_claims, list):
            for claim_index, claim in enumerate(raw_claims):
                if not isinstance(claim, dict):
                    continue
                proof_steps = claim.get("proof_steps")
                if not isinstance(proof_steps, list):
                    continue
                normalized_steps: list[Any] = []
                for proof_step in proof_steps:
                    if isinstance(proof_step, str) and proof_step in step_by_id:
                        normalized_steps.append(copy.deepcopy(step_by_id[proof_step]))
                        actions.append(
                            "expanded "
                            f"new_claims[{claim_index}].proof_steps reference "
                            f"{proof_step!r}"
                        )
                    else:
                        # Unknown IDs remain untouched so strict validation rejects
                        # them rather than guessing which mathematical step was meant.
                        normalized_steps.append(proof_step)
                claim["proof_steps"] = normalized_steps

        candidate_answer = raw_delta.get("candidate_final_answer")
        missing_final_answer = not (
            isinstance(candidate_answer, str) and candidate_answer.strip()
        )
        if raw_delta.get("proof_complete") is True and missing_final_answer:
            raw_delta["proof_complete"] = False
            if is_turn and payload.get("action") == "complete":
                payload["action"] = "submit_delta"

            recovery_goal = (
                "Assemble a self-contained candidate final answer from the "
                "independently reviewed proof prefix."
            )
            remaining = raw_delta.get("remaining_subgoals")
            if remaining is None:
                raw_delta["remaining_subgoals"] = [recovery_goal]
            elif isinstance(remaining, list) and not remaining:
                remaining.append(recovery_goal)
            if not raw_delta.get("current_goal"):
                raw_delta["current_goal"] = recovery_goal

            completed_subgoal = raw_delta.get("completed_subgoal")
            if not completed_subgoal and isinstance(raw_steps, list):
                for step in reversed(raw_steps):
                    if not isinstance(step, dict):
                        continue
                    statement = step.get("statement")
                    if isinstance(statement, str) and statement.strip():
                        raw_delta["completed_subgoal"] = statement
                        break

            # The recovered prefix is not committed here. Marking it ready merely
            # routes it through local integrity, Skeptic, Referee, and checkpoint
            # verification instead of discarding potentially valid mathematics.
            if raw_steps or raw_delta.get("detected_conflicts"):
                raw_delta["ready_for_verification"] = True
            notes = raw_delta.get("normalization_notes")
            if not isinstance(notes, list):
                notes = []
                raw_delta["normalization_notes"] = notes
            note = "missing_final_answer_downgraded_to_partial"
            if note not in notes:
                notes.append(note)
            actions.append(note)

        return actions

    @staticmethod
    def _normalize_dependency_ref_kinds(payload: dict[str, Any]) -> list[str]:
        """Canonicalize the exact legacy dependency alias using payload scope."""

        local_step_ids: set[str] = set()
        local_claim_ids: set[str] = set()

        def collect_ids(value: Any) -> None:
            if isinstance(value, dict):
                step_id = value.get("step_id")
                claim_id = value.get("claim_id")
                if isinstance(step_id, str) and step_id:
                    local_step_ids.add(step_id)
                if isinstance(claim_id, str) and claim_id:
                    local_claim_ids.add(claim_id)
                for nested in value.values():
                    collect_ids(nested)
            elif isinstance(value, list):
                for nested in value:
                    collect_ids(nested)

        collect_ids(payload)
        actions: list[str] = []

        def normalize(value: Any, path: str) -> None:
            if isinstance(value, dict):
                refs = value.get("dependency_refs")
                if isinstance(refs, list):
                    for index, raw_ref in enumerate(refs):
                        if not (
                            isinstance(raw_ref, dict)
                            and raw_ref.get("kind") == "external"
                        ):
                            continue
                        target_id = raw_ref.get("target_id")
                        is_local_step = (
                            isinstance(target_id, str) and target_id in local_step_ids
                        )
                        is_local_claim = (
                            isinstance(target_id, str) and target_id in local_claim_ids
                        )
                        if is_local_step and not is_local_claim:
                            canonical = "local_step"
                        elif is_local_claim and not is_local_step:
                            canonical = "local_claim"
                        else:
                            # No unique local namespace is safe to bind. The
                            # external-result resolver will keep it unresolved
                            # unless an exact artifact is actually registered.
                            canonical = "external_result"
                        migration = f"legacy_external_to_{canonical}"
                        raw_ref["kind"] = canonical
                        raw_ref["kind_migration"] = migration
                        ref_path = f"{path}.dependency_refs[{index}].kind"
                        actions.append(
                            f"canonicalized {ref_path} external to {canonical}"
                        )
                for key, nested in value.items():
                    normalize(nested, f"{path}.{key}")
            elif isinstance(value, list):
                for index, nested in enumerate(value):
                    normalize(nested, f"{path}[{index}]")

        normalize(payload, "$")
        return actions

    async def _call_with_activity_heartbeat(
        self,
        agent: AgentRuntime,
        messages: list[dict[str, str]],
        *,
        temperature: float | None,
        max_output_tokens: int | None,
        json_mode: bool,
        schema_name: str,
        schema: dict[str, Any],
        activity_task: str | None,
        stage: str,
        tier_policy: ExplorationTierPolicyConfig | None = None,
        thinking_enabled: bool | None = None,
        reasoning_effort: str | None = None,
    ):
        """Await one provider call while emitting low-frequency, content-free heartbeats."""

        interval = self.config.runtime.activity_heartbeat_seconds
        poll_interval = max(0.01, min(interval if interval > 0 else 5.0, 5.0))
        wall_timeout = self.config.runtime.agent_call_wall_timeout_seconds
        first_chunk_timeout = self.config.runtime.stream_first_chunk_timeout_seconds
        stream_idle_timeout = self.config.runtime.stream_idle_timeout_seconds

        async def invoke_agent():
            if activity_task is None:
                return await agent.call(
                    messages,
                    temperature=temperature,
                    max_output_tokens=max_output_tokens,
                    json_mode=json_mode,
                    schema_name=schema_name,
                    schema=schema,
                    thinking_enabled=thinking_enabled,
                    reasoning_effort=reasoning_effort,
                )
            binding = ReasoningTraceBinding(
                store=self.reasoning_traces,
                task_id=activity_task,
                agent_id=agent.id,
                stage=stage,
            )
            with bind_reasoning_trace(binding):
                return await agent.call(
                    messages,
                    temperature=temperature,
                    max_output_tokens=max_output_tokens,
                    json_mode=json_mode,
                    schema_name=schema_name,
                    schema=schema,
                    thinking_enabled=thinking_enabled,
                    reasoning_effort=reasoning_effort,
                )

        task = asyncio.create_task(invoke_agent())
        started = time.monotonic()
        last_activity_update = started - max(0.0, interval)
        try:
            while True:
                done, _ = await asyncio.wait({task}, timeout=poll_interval)
                if task in done:
                    return task.result()
                now = time.monotonic()
                elapsed_float = max(0.0, now - started)
                elapsed = int(elapsed_float)
                client = getattr(agent, "client", None)
                progress = (
                    client.progress_snapshot_for(task)
                    if client is not None and hasattr(client, "progress_snapshot_for")
                    else {}
                )
                approx_tokens = int(progress.get("approx_output_tokens", 0) or 0)
                last_data_age_value = progress.get("last_data_age_seconds")
                last_data_age = (
                    float(last_data_age_value)
                    if last_data_age_value is not None
                    else None
                )
                chunks = int(progress.get("chunks", 0) or 0)
                if elapsed_float >= wall_timeout:
                    usage = self._interrupted_usage(
                        agent,
                        output_tokens=approx_tokens,
                        latency_seconds=elapsed_float,
                    )
                    self._record_runner_failure(agent, "wall_timeout")
                    self.store.append_event(
                        "agent_call_wall_timeout",
                        {
                            "stage": stage,
                            "agent_id": agent.id,
                            "elapsed_seconds": elapsed_float,
                            "progress": progress,
                            "tier_output_tokens": (
                                tier_policy.output_tokens if tier_policy else None
                            ),
                        },
                    )
                    raise AgentCallWallTimeoutError(
                        f"{agent.id} exceeded the {wall_timeout:.0f}s whole-call limit",
                        usage=usage,
                        progress=progress,
                    )
                first_chunk_stalled = (
                    bool(progress.get("streaming"))
                    and chunks == 0
                    and last_data_age is not None
                    and last_data_age >= first_chunk_timeout
                )
                active_stream_stalled = (
                    bool(progress.get("streaming"))
                    and chunks > 0
                    and last_data_age is not None
                    and last_data_age >= stream_idle_timeout
                )
                if first_chunk_stalled or active_stream_stalled:
                    usage = self._interrupted_usage(
                        agent,
                        output_tokens=approx_tokens,
                        latency_seconds=elapsed_float,
                    )
                    self._record_runner_failure(agent, "stream_idle_timeout")
                    timeout_kind = (
                        "first_chunk_timeout" if first_chunk_stalled else "idle_timeout"
                    )
                    timeout_limit = (
                        first_chunk_timeout
                        if first_chunk_stalled
                        else stream_idle_timeout
                    )
                    self.store.append_event(
                        f"agent_stream_{timeout_kind}",
                        {
                            "stage": stage,
                            "agent_id": agent.id,
                            "elapsed_seconds": elapsed_float,
                            "idle_seconds": last_data_age,
                            "idle_timeout_seconds": timeout_limit,
                            "chunks": chunks,
                            "progress": progress,
                            "tier_output_tokens": (
                                tier_policy.output_tokens if tier_policy else None
                            ),
                            "recovery": "transport_failover_from_external_checkpoint",
                        },
                    )
                    error = AgentStreamIdleTimeoutError(
                        (
                            f"{agent.id} received no first SSE chunk for "
                            f"{last_data_age:.0f}s"
                            if first_chunk_stalled
                            else (
                                f"{agent.id} received no SSE data for "
                                f"{last_data_age:.0f}s"
                            )
                        ),
                        usage=usage,
                        progress=progress,
                    )
                    agent.provider_circuit.record_transport_failure(
                        agent.provider_scope,
                        agent.id,
                        error,
                    )
                    raise error
                if (
                    self.activity is None
                    or activity_task is None
                    or interval <= 0
                    or now - last_activity_update < interval
                ):
                    continue
                last_activity_update = now
                minutes, seconds = divmod(elapsed, 60)
                elapsed_text = f"{minutes:02d}:{seconds:02d}"
                queued = (
                    client is not None
                    and hasattr(client, "progress_snapshot_for")
                    and not progress
                )
                heartbeat_last_data_age = last_data_age
                if queued:
                    detail = (
                        f"{agent.id} 正在等待 Agent/API 并发槽位（{elapsed_text}）"
                        if self.activity.is_zh
                        else (
                            f"{agent.id} is waiting for an Agent/API concurrency "
                            f"slot ({elapsed_text})"
                        )
                    )
                else:
                    heartbeat_last_data_age = (
                        last_data_age if last_data_age is not None else elapsed_float
                    )
                    detail = (
                        f"{agent.id} 仍在处理（{elapsed_text}；已收 {chunks} chunks；"
                        f"约 {approx_tokens:,} tokens；最后数据 "
                        f"{heartbeat_last_data_age:.1f} 秒前）"
                        if self.activity.is_zh
                        else (
                            f"{agent.id} is still working ({elapsed_text}; "
                            f"{chunks} chunks; ~{approx_tokens:,} tokens; last data "
                            f"{heartbeat_last_data_age:.1f}s ago)"
                        )
                    )
                self.activity.update_task(
                    activity_task,
                    title=stage_label(stage, self.config.runtime.output_language),
                    detail=detail,
                    status=ActivityStatus.RUNNING,
                    event_type=(
                        "agent_call_queued" if queued else "agent_call_heartbeat"
                    ),
                    stage=stage,
                    agent_id=agent.id,
                    importance=ActivityImportance.DETAIL,
                    metrics={
                        "elapsed_seconds": elapsed,
                        "chunks": chunks,
                        "approx_output_tokens": approx_tokens,
                        "reasoning_characters": int(
                            progress.get("reasoning_characters", 0) or 0
                        ),
                        "content_characters": int(
                            progress.get("content_characters", 0) or 0
                        ),
                        "last_data_age_seconds": heartbeat_last_data_age,
                        "queueing": queued,
                        "tier_output_tokens": (
                            tier_policy.output_tokens if tier_policy else None
                        ),
                        "artifact_recovery_tokens": (
                            tier_policy.artifact_recovery_tokens
                            if tier_policy
                            else None
                        ),
                        "thinking_enabled": thinking_enabled,
                        "reasoning_effort": reasoning_effort,
                    },
                )
        except BaseException:
            if not task.done():
                task.cancel()
                await asyncio.gather(task, return_exceptions=True)
            raise
        finally:
            client = getattr(agent, "client", None)
            if client is not None and hasattr(client, "clear_progress_for"):
                client.clear_progress_for(task)

    @staticmethod
    def _copy_usage(usage: UsageRecord) -> UsageRecord:
        return UsageRecord.model_validate(usage.model_dump(mode="json"))

    @staticmethod
    def _sum_usage(left: UsageRecord, right: UsageRecord) -> UsageRecord:
        return UsageRecord(
            input_tokens=left.input_tokens + right.input_tokens,
            output_tokens=left.output_tokens + right.output_tokens,
            total_tokens=left.total_tokens + right.total_tokens,
            estimated_cost_usd=(left.estimated_cost_usd + right.estimated_cost_usd),
            latency_ms=left.latency_ms + right.latency_ms,
        )

    @staticmethod
    def _interrupted_usage(
        agent: AgentRuntime,
        *,
        output_tokens: int,
        latency_seconds: float,
    ) -> UsageRecord:
        tokens = max(0, int(output_tokens))
        latency_ms = max(0.0, latency_seconds * 1000.0)
        cost = tokens / 1_000_000 * agent.config.pricing.output_per_million
        agent.output_tokens += tokens
        agent.estimated_cost_usd += cost
        agent.total_latency_ms += latency_ms
        agent.failures += 1
        agent.consecutive_failures += 1
        return UsageRecord(
            output_tokens=tokens,
            total_tokens=tokens,
            estimated_cost_usd=cost,
            latency_ms=latency_ms,
        )

    @staticmethod
    def _attach_metadata(value: BaseModel, raw_ref: str, usage: UsageRecord) -> None:
        fields = value.__class__.model_fields
        if "raw_artifact_ref" in fields:
            setattr(value, "raw_artifact_ref", raw_ref)
        if "usage" in fields:
            setattr(value, "usage", usage)


def extract_json_object(text: str) -> dict[str, Any]:
    """Extract the first balanced JSON object, tolerating markdown fences and surrounding prose."""
    cleaned = text.strip()
    cleaned = re.sub(r"^```(?:json)?\s*", "", cleaned, flags=re.IGNORECASE)
    cleaned = re.sub(r"\s*```$", "", cleaned)
    try:
        value = json.loads(cleaned)
        if not isinstance(value, dict):
            raise ValueError("structured output must be a JSON object")
        return value
    except json.JSONDecodeError:
        pass

    start = cleaned.find("{")
    if start < 0:
        raise ValueError("no JSON object found")
    depth = 0
    in_string = False
    escape = False
    for index in range(start, len(cleaned)):
        char = cleaned[index]
        if in_string:
            if escape:
                escape = False
            elif char == "\\":
                escape = True
            elif char == '"':
                in_string = False
            continue
        if char == '"':
            in_string = True
        elif char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                candidate = cleaned[start : index + 1]
                value = json.loads(candidate)
                if not isinstance(value, dict):
                    raise ValueError("structured output must be a JSON object")
                return value
    raise ValueError("unterminated JSON object")
