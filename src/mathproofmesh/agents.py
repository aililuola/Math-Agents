from __future__ import annotations

import asyncio
import json
import logging
import re
import time
from dataclasses import dataclass, replace
from collections.abc import Callable
from typing import Any, Generic, TypeVar

from pydantic import BaseModel, ValidationError

from .activity import ActivityImportance, ActivityStatus, ActivityStream, stage_label
from .config import SystemConfig
from .llm.pool import (
    AgentCallFailure,
    AgentPool,
    AgentRuntime,
    ProviderCircuitOpenError,
)
from .prompts import PromptBundle, _validated_model_example, assert_blind_prompt_safe
from .schemas import UsageRecord
from .store import ArtifactStore

logger = logging.getLogger(__name__)

T = TypeVar("T", bound=BaseModel)


class BudgetExhaustedError(RuntimeError):
    pass


class StructuredOutputError(RuntimeError):
    pass


class AgentCallWallTimeoutError(RuntimeError):
    pass


class ReasoningOnlyStallError(RuntimeError):
    pass


class ReasoningBudgetExhaustedError(RuntimeError):
    pass


class AgentFailoverExhausted(RuntimeError):
    def __init__(self, role: str, tried_agents: list[str], errors: list[str]) -> None:
        self.role = role
        self.tried_agents = tried_agents
        self.errors = errors
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

    def can_start(self, stage: str) -> bool:
        budget = self.config.budget
        if self.calls_started >= budget.max_total_calls:
            return False
        if (
            budget.max_total_tokens is not None
            and self.pool.total_tokens() >= budget.max_total_tokens
        ):
            return False
        if (
            budget.max_cost_usd is not None
            and self.pool.total_cost_usd() >= budget.max_cost_usd
        ):
            return False
        return True

    def start(self, stage: str, bucket: str = "other") -> None:
        if not self.can_start(stage):
            raise BudgetExhaustedError(
                f"budget exhausted before stage={stage}: calls={self.calls_started}, "
                f"tokens={self.pool.total_tokens()}, cost=${self.pool.total_cost_usd():.4f}"
            )
        self.calls_started += 1
        self.stage_calls[stage] = self.stage_calls.get(stage, 0) + 1
        normalized_bucket = bucket if bucket in self.bucket_calls else "other"
        self.bucket_calls[normalized_bucket] += 1

    @property
    def remaining_calls(self) -> int:
        return max(0, self.config.budget.max_total_calls - self.calls_started)


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

    def persist_runtime_state(self) -> None:
        """Atomically persist budget and usage state for process-level resume."""
        self.store.write_json(
            "checkpoints",
            "runtime_ledger",
            {
                "calls_started": self.ledger.calls_started,
                "stage_calls": self.ledger.stage_calls,
                "bucket_calls": self.ledger.bucket_calls,
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
    ) -> StructuredCallResult[Any]:
        assert_blind_prompt_safe(bundle)
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
        try:
            for parse_attempt in range(self.config.runtime.parse_retries + 1):
                self.ledger.start(bundle.stage, budget_bucket)
                self.persist_runtime_state()
                effective_max_output_tokens = self._effective_output_limit(
                    bundle,
                    agent,
                    repair=parse_attempt > 0,
                )
                if parse_attempt > 0:
                    repair_system = (
                        "You repair malformed structured output. Return only one JSON object matching the schema. "
                        "Do not change mathematical content except where needed to satisfy field types and required fields."
                    )
                    repair_user = (
                        f"[STAGE:{bundle.stage}_json_repair]\n"
                        f"JSON SCHEMA:\n{json.dumps(schema, ensure_ascii=False, indent=2)}\n\n"
                        "MINIMAL JSON SHAPE EXAMPLE:\n"
                        f"{json.dumps(_validated_model_example(bundle.response_model, schema), ensure_ascii=False, indent=2)}\n\n"
                        f"MALFORMED OUTPUT:\n{response_text}\n\n"
                        f"VALIDATION ERROR:\n{last_error}"
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
                    activity_task=activity_task,
                    stage=bundle.stage,
                )
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
                    },
                    summary=f"Raw response for {bundle.stage} from {agent.id}",
                )
                raw_ref = raw_evidence.artifact_ref
                total_usage.input_tokens += response.input_tokens
                total_usage.output_tokens += response.output_tokens
                total_usage.total_tokens += response.total_tokens
                total_usage.estimated_cost_usd += (
                    response.input_tokens
                    / 1_000_000
                    * agent.config.pricing.input_per_million
                    + response.output_tokens
                    / 1_000_000
                    * agent.config.pricing.output_per_million
                )
                total_usage.latency_ms += response.latency_ms
                self.persist_runtime_state()
                finish_reason = str(response.raw.get("finish_reason") or "")
                if finish_reason == "length" and not response.text.strip():
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
                            "recovery": "restart_from_external_checkpoint",
                        },
                    )
                    raise ReasoningBudgetExhaustedError(
                        f"{agent.id} exhausted {effective_max_output_tokens} output "
                        "tokens without returning a structured artifact"
                    )
                try:
                    payload = extract_json_object(response.text)
                    self._strip_server_owned_hashes(payload)
                    value = bundle.response_model.model_validate(payload)
                    self._attach_metadata(value, raw_ref, total_usage)
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
                    self.persist_runtime_state()
                    return StructuredCallResult(
                        value=value,
                        agent=agent,
                        raw_ref=raw_ref,
                        prompt_ref=prompt_ref,
                        usage=total_usage,
                    )
                except (json.JSONDecodeError, ValidationError, ValueError) as exc:
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
                    if parse_attempt >= self.config.runtime.parse_retries:
                        break
            raise StructuredOutputError(
                f"agent {agent.id} could not produce valid "
                f"{bundle.response_model.__name__}: {last_error}"
            ) from last_error
        except Exception as exc:
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
        last_exception: Exception | None = None
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
                if isinstance(
                    last_exception,
                    (ReasoningBudgetExhaustedError, ReasoningOnlyStallError),
                ):
                    bundle = self._recovery_bundle(bundle, last_exception)
                result = await self.call(
                    role,
                    bundle,
                    fixed_agent=agent,
                    budget_bucket=budget_bucket,
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
                return result, tried
            except (BudgetExhaustedError, ProviderCircuitOpenError):
                raise
            except (
                AgentCallFailure,
                StructuredOutputError,
                RuntimeError,
                ValueError,
            ) as exc:
                last_exception = exc
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
                # Authentication/authorization failures are not retried on the
                # same key, but a different configured key may still be valid.
                continue

        raise AgentFailoverExhausted(role, tried, errors)

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

    def _recovery_bundle(self, bundle: PromptBundle, error: Exception) -> PromptBundle:
        """Restart from external state; never pretend to resume private reasoning."""

        instruction = (
            "\n\nRECOVERY MODE: The previous provider call produced no usable artifact "
            f"({type(error).__name__}). Do not continue or reconstruct its private "
            "reasoning. Restart only from the verified checkpoint and typed context in "
            "this prompt. Address the smallest current obligation and emit a valid, "
            "bounded JSON artifact immediately; leave unresolved work explicit."
        )
        first_tier = self.config.runtime.exploration_output_token_tiers[0]
        explicit = (
            min(bundle.max_output_tokens, first_tier)
            if bundle.max_output_tokens is not None
            else first_tier
        )
        return replace(
            bundle,
            user=f"{bundle.user}{instruction}",
            max_output_tokens=explicit,
            output_tier=0,
        )

    @staticmethod
    def _record_runner_failure(agent: AgentRuntime, category: str) -> None:
        agent.failed_attempts += 1
        agent.failure_categories[category] = (
            agent.failure_categories.get(category, 0) + 1
        )

    @classmethod
    def _strip_server_owned_hashes(cls, value: Any) -> None:
        """Ignore hashes invented by a model; validators recompute canonical values."""

        if isinstance(value, dict):
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
    ):
        """Await one provider call while emitting low-frequency, content-free heartbeats."""

        interval = self.config.runtime.activity_heartbeat_seconds
        poll_interval = max(0.01, min(interval if interval > 0 else 5.0, 5.0))
        wall_timeout = self.config.runtime.agent_call_wall_timeout_seconds
        reasoning_timeout = self.config.runtime.reasoning_only_abort_seconds
        minimum_reasoning = self.config.runtime.reasoning_only_min_characters
        task = asyncio.create_task(
            agent.call(
                messages,
                temperature=temperature,
                max_output_tokens=max_output_tokens,
                json_mode=json_mode,
                schema_name=schema_name,
                schema=schema,
            )
        )
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
                    client.progress_snapshot()
                    if client is not None and hasattr(client, "progress_snapshot")
                    else {}
                )
                if elapsed_float >= wall_timeout:
                    self._record_runner_failure(agent, "wall_timeout")
                    self.store.append_event(
                        "agent_call_wall_timeout",
                        {
                            "stage": stage,
                            "agent_id": agent.id,
                            "elapsed_seconds": elapsed_float,
                            "progress": progress,
                        },
                    )
                    raise AgentCallWallTimeoutError(
                        f"{agent.id} exceeded the {wall_timeout:.0f}s whole-call limit"
                    )
                if (
                    elapsed_float >= reasoning_timeout
                    and int(progress.get("reasoning_characters", 0) or 0)
                    >= minimum_reasoning
                    and int(progress.get("content_characters", 0) or 0) == 0
                ):
                    self._record_runner_failure(agent, "reasoning_only_stall")
                    self.store.append_event(
                        "reasoning_only_stream_aborted",
                        {
                            "stage": stage,
                            "agent_id": agent.id,
                            "elapsed_seconds": elapsed_float,
                            "progress": progress,
                            "recovery": "restart_from_external_checkpoint",
                        },
                    )
                    raise ReasoningOnlyStallError(
                        f"{agent.id} streamed private reasoning for {elapsed_float:.0f}s "
                        "without beginning the requested artifact"
                    )
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
                chunks = int(progress.get("chunks", 0) or 0)
                approx_tokens = int(progress.get("approx_output_tokens", 0) or 0)
                last_data_age = float(progress.get("last_data_age_seconds", 0.0) or 0.0)
                self.activity.update_task(
                    activity_task,
                    title=stage_label(stage, self.config.runtime.output_language),
                    detail=(
                        f"{agent.id} 仍在处理（{elapsed_text}；已收 {chunks} chunks；"
                        f"约 {approx_tokens:,} tokens；最后数据 {last_data_age:.1f} 秒前）"
                        if self.activity.is_zh
                        else (
                            f"{agent.id} is still working ({elapsed_text}; {chunks} chunks; "
                            f"~{approx_tokens:,} tokens; last data {last_data_age:.1f}s ago)"
                        )
                    ),
                    status=ActivityStatus.RUNNING,
                    event_type="agent_call_heartbeat",
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
                        "last_data_age_seconds": last_data_age,
                    },
                )
        except BaseException:
            if not task.done():
                task.cancel()
                await asyncio.gather(task, return_exceptions=True)
            raise

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
