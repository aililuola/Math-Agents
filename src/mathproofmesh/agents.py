from __future__ import annotations

import asyncio
import json
import logging
import re
import time
from dataclasses import dataclass
from collections.abc import Callable
from typing import Any, Generic, TypeVar

from pydantic import BaseModel, ValidationError

from .activity import ActivityImportance, ActivityStatus, ActivityStream, stage_label
from .config import SystemConfig
from .llm.pool import AgentCallFailure, AgentPool, AgentRuntime
from .prompts import PromptBundle, assert_blind_prompt_safe
from .schemas import UsageRecord
from .store import ArtifactStore

logger = logging.getLogger(__name__)

T = TypeVar("T", bound=BaseModel)


class BudgetExhaustedError(RuntimeError):
    pass


class StructuredOutputError(RuntimeError):
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
                if parse_attempt > 0:
                    repair_system = (
                        "You repair malformed structured output. Return only one JSON object matching the schema. "
                        "Do not change mathematical content except where needed to satisfy field types and required fields."
                    )
                    repair_user = (
                        f"[STAGE:{bundle.stage}_json_repair]\n"
                        f"JSON SCHEMA:\n{json.dumps(schema, ensure_ascii=False, indent=2)}\n\n"
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
                    max_output_tokens=bundle.max_output_tokens,
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
                try:
                    payload = extract_json_object(response.text)
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
                result = await self.call(
                    role,
                    bundle_factory(agent),
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
            except BudgetExhaustedError:
                raise
            except (
                AgentCallFailure,
                StructuredOutputError,
                RuntimeError,
                ValueError,
            ) as exc:
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
        if self.activity is None or activity_task is None or interval <= 0:
            return await task

        started = time.monotonic()
        try:
            while True:
                done, _ = await asyncio.wait({task}, timeout=interval)
                if task in done:
                    return task.result()
                elapsed = max(0, int(time.monotonic() - started))
                minutes, seconds = divmod(elapsed, 60)
                elapsed_text = f"{minutes:02d}:{seconds:02d}"
                self.activity.update_task(
                    activity_task,
                    title=stage_label(stage, self.config.runtime.output_language),
                    detail=(
                        f"{agent.id} 仍在处理当前任务（本次调用已运行 {elapsed_text}）"
                        if self.activity.is_zh
                        else f"{agent.id} is still working ({elapsed_text} elapsed for this call)"
                    ),
                    status=ActivityStatus.RUNNING,
                    event_type="agent_call_heartbeat",
                    stage=stage,
                    agent_id=agent.id,
                    importance=ActivityImportance.DETAIL,
                    metrics={"elapsed_seconds": elapsed},
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
