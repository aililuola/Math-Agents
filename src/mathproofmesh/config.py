from __future__ import annotations

import os
import re
from pathlib import Path
from typing import Any, Literal

import yaml
from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    SecretStr,
    field_validator,
    model_validator,
)


class ConfigModel(BaseModel):
    model_config = ConfigDict(
        extra="forbid", validate_assignment=True, str_strip_whitespace=True
    )


ProviderName = Literal["openai_compatible", "deepseek", "anthropic", "gemini", "mock"]
ReasoningEffort = Literal["high", "max"]
RoleName = Literal[
    "planner",
    "explorer",
    "summarizer",
    "structural_verifier",
    "detailed_verifier",
    "meta_reviewer",
    "synthesizer",
    "final_verifier",
    "experimenter",
    "general",
]


class PricingConfig(ConfigModel):
    input_per_million: float = Field(default=0.0, ge=0.0)
    output_per_million: float = Field(default=0.0, ge=0.0)


class AgentConfig(ConfigModel):
    id: str
    provider: ProviderName
    model: str
    api_key_env: str | None = None
    api_key: SecretStr | None = Field(default=None, exclude=True)
    base_url: str | None = None
    roles: list[RoleName] = Field(default_factory=lambda: ["general"])
    specialties: list[str] = Field(default_factory=list)
    max_concurrency: int = Field(default=1, ge=1, le=32)
    requests_per_minute: int | None = Field(default=None, ge=1)
    temperature: float = Field(default=0.2, ge=0.0, le=2.0)
    max_output_tokens: int = Field(default=8192, ge=256, le=384000)
    timeout_seconds: float = Field(default=180.0, ge=5.0, le=3600.0)
    trust_prior: float = Field(default=0.5, ge=0.0, le=1.0)
    enabled: bool = True
    pricing: PricingConfig = Field(default_factory=PricingConfig)
    extra_headers: dict[str, str] = Field(default_factory=dict)
    mock_profile: str | None = None
    thinking_enabled: bool = False
    reasoning_effort: ReasoningEffort | None = None
    streaming: bool = False
    user_id: str | None = Field(default=None, min_length=1, max_length=512)

    @model_validator(mode="after")
    def key_source_is_valid(self) -> "AgentConfig":
        if self.provider != "mock" and not self.api_key_env and self.api_key is None:
            raise ValueError(f"agent {self.id}: api_key_env or api_key is required")
        if self.reasoning_effort is not None and not self.thinking_enabled:
            raise ValueError(
                f"agent {self.id}: reasoning_effort requires thinking_enabled=true"
            )
        if self.provider == "deepseek" and not self.model.startswith("deepseek-"):
            raise ValueError(
                f"agent {self.id}: DeepSeek provider requires a DeepSeek model identifier"
            )
        return self

    @field_validator("user_id")
    @classmethod
    def validate_user_id(cls, value: str | None) -> str | None:
        if value is None:
            return None
        allowed = set(
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"
        )
        if any(char not in allowed for char in value):
            raise ValueError("user_id may contain only letters, digits, '-' and '_'")
        return value

    def resolve_key(self) -> str:
        if self.api_key is not None:
            return self.api_key.get_secret_value()
        if self.api_key_env:
            value = os.getenv(self.api_key_env)
            if value:
                return value
            raise RuntimeError(
                f"Missing API key for agent '{self.id}'. Set environment variable {self.api_key_env}."
            )
        if self.provider == "mock":
            return "mock"
        raise RuntimeError(f"No API key configured for agent '{self.id}'")


class BudgetConfig(ConfigModel):
    max_total_calls: int = Field(default=48, ge=4, le=10000)
    max_rounds: int = Field(default=4, ge=1, le=64)
    initial_paths: int = Field(default=4, ge=1, le=32)
    max_paths: int = Field(default=8, ge=1, le=64)
    strategies_to_generate: int = Field(default=7, ge=1, le=64)
    candidates_to_verify: int = Field(default=3, ge=1, le=32)
    max_revisions: int = Field(default=3, ge=0, le=32)
    base_verifier_replicas: int = Field(default=1, ge=1, le=8)
    high_risk_verifier_replicas: int = Field(default=2, ge=1, le=8)
    high_risk_threshold: float = Field(default=0.45, ge=0.0, le=1.0)
    verification_pass_threshold: float = Field(default=0.78, ge=0.0, le=1.0)
    synthesis_threshold: float = Field(default=0.72, ge=0.0, le=1.0)
    max_total_tokens: int | None = Field(default=None, ge=1000)
    max_cost_usd: float | None = Field(default=None, ge=0.01)
    breadth_share: float = Field(default=0.30, ge=0.0, le=1.0)
    depth_share: float = Field(default=0.35, ge=0.0, le=1.0)
    verification_share: float = Field(default=0.25, ge=0.0, le=1.0)
    synthesis_share: float = Field(default=0.10, ge=0.0, le=1.0)

    @model_validator(mode="after")
    def validate_shares_and_paths(self) -> "BudgetConfig":
        total = (
            self.breadth_share
            + self.depth_share
            + self.verification_share
            + self.synthesis_share
        )
        if abs(total - 1.0) > 1e-6:
            raise ValueError(
                "breadth/depth/verification/synthesis shares must sum to 1.0"
            )
        if self.initial_paths > self.max_paths:
            raise ValueError("initial_paths cannot exceed max_paths")
        if self.strategies_to_generate < self.initial_paths:
            raise ValueError("strategies_to_generate must be >= initial_paths")
        return self


class SchedulerConfig(ConfigModel):
    """Adaptive breadth/depth policy and its budget-aware admission controls."""

    max_actions_per_round: int = Field(default=2, ge=1, le=16)
    widen_paths_per_action: int = Field(default=2, ge=1, le=32)
    force_widen_when_all_failed: bool = True
    max_execution_repairs_per_path: int = Field(default=1, ge=0, le=32)
    max_plan_repairs_per_path: int = Field(default=1, ge=0, le=32)
    max_unknown_failure_repairs_per_path: int = Field(default=1, ge=0, le=32)
    allow_strategy_failure_repair: bool = False
    failed_path_cooldown_rounds: int = Field(default=1, ge=0, le=32)
    meaningful_progress_threshold: float = Field(default=0.04, ge=0.0, le=1.0)
    unverified_progress_discount: float = Field(default=0.55, ge=0.0, le=1.0)
    uncertain_progress_discount: float = Field(default=0.40, ge=0.0, le=1.0)
    failed_progress_discount: float = Field(default=0.10, ge=0.0, le=1.0)
    structural_failure_progress_cap: float = Field(default=0.10, ge=0.0, le=1.0)
    execution_failure_progress_cap: float = Field(default=0.45, ge=0.0, le=1.0)
    plan_failure_progress_cap: float = Field(default=0.18, ge=0.0, le=1.0)
    strategy_failure_progress_cap: float = Field(default=0.05, ge=0.0, le=1.0)
    structural_failure_penalty: float = Field(default=0.30, ge=0.0, le=2.0)
    execution_failure_penalty: float = Field(default=0.12, ge=0.0, le=2.0)
    plan_failure_penalty: float = Field(default=0.30, ge=0.0, le=2.0)
    strategy_failure_penalty: float = Field(default=0.50, ge=0.0, le=2.0)
    repeated_failure_penalty: float = Field(default=0.15, ge=0.0, le=2.0)
    reserve_revision_cycles: int = Field(default=1, ge=0, le=32)
    include_post_action_verification_in_cost: bool = True
    include_meta_review_in_cost: bool = True
    verification_call_safety_margin: int = Field(default=0, ge=0, le=16)
    finish_transition_buffer_calls: int = Field(default=1, ge=0, le=64)
    diagnostics_enabled: bool = True
    diagnostic_candidate_limit: int = Field(default=12, ge=1, le=128)


class TopologyConfig(ConfigModel):
    neighbor_k: int = Field(default=2, ge=1, le=16)
    prefer_cross_provider_review: bool = True
    isolate_initial_exploration: bool = True
    conditional_cross_review: bool = True
    disagreement_threshold: float = Field(default=0.35, ge=0.0, le=1.0)
    strategy_similarity_threshold: float = Field(default=0.72, ge=0.0, le=1.0)
    max_context_chars: int = Field(default=90000, ge=4000, le=2_000_000)
    max_verified_claims_per_context: int = Field(default=24, ge=1, le=500)
    preserve_raw_evidence: bool = True


class VerificationConfig(ConfigModel):
    structural_first: bool = True
    detailed_only_after_structural_pass: bool = True
    verify_problem_integrity: bool = True
    require_first_error_step: bool = True
    require_key_step_tagging: bool = True
    enable_sympy_tools: bool = True
    enable_numeric_counterexamples: bool = True
    enable_lean: bool = False
    lean_command: str = "lake env lean"
    external_tool_timeout_seconds: float = Field(default=20.0, ge=1.0, le=600.0)


class ContinuationConfig(ConfigModel):
    """Proof-step checkpointing, reconnect, and cross-agent failover controls."""

    enabled: bool = False
    checkpoint_policy: Literal["verified_subgoal", "verified_delta"] = (
        "verified_subgoal"
    )
    max_new_steps_per_call: int = Field(default=3, ge=1, le=32)
    max_new_claims_per_call: int = Field(default=3, ge=0, le=32)
    max_output_tokens_per_segment: int = Field(default=12000, ge=512, le=128000)
    segments_per_explore_call: int = Field(default=1, ge=1, le=8)
    max_segments_per_path: int = Field(default=12, ge=1, le=128)
    verify_each_delta: bool = True
    delta_verifier_replicas: int = Field(default=1, ge=1, le=4)
    checkpoint_pass_threshold: float = Field(default=0.78, ge=0.0, le=1.0)
    resume_on_disconnect: bool = True
    allow_cross_agent_failover: bool = True
    max_failover_agents: int = Field(default=2, ge=0, le=16)
    process_resume_enabled: bool = True
    retain_rejected_deltas: bool = True


class ComputationConfig(ConfigModel):
    """Reasoning-first experiment policy, budgets, and sandbox controls."""

    enabled: bool = False
    policy: Literal["reasoning_first"] = "reasoning_first"
    typed_tools_enabled: bool = True
    sandboxed_python_enabled: bool = False
    execute_planner_hints_immediately: bool = False
    targeted_falsification_fast_path: bool = True
    soft_experiments_per_path: int = Field(default=2, ge=0, le=100)
    hard_experiments_per_path: int = Field(default=6, ge=1, le=100)
    max_compute_cycles_per_segment: int = Field(default=1, ge=0, le=8)
    max_total_cpu_seconds: float = Field(default=120.0, ge=0.1, le=86_400.0)
    max_cases_per_experiment: int = Field(default=1_000_000, ge=1, le=100_000_000)
    max_output_chars: int = Field(default=20_000, ge=256, le=2_000_000)
    broad_search_after_stalled_rounds: int = Field(default=1, ge=0, le=64)
    broad_search_requires_meta_review: bool = True
    cache_results: bool = True
    sandbox_image: str | None = None
    sandbox_timeout_seconds: float = Field(default=20.0, ge=0.1, le=600.0)
    sandbox_memory_mb: int = Field(default=256, ge=32, le=8192)
    sandbox_cpus: float = Field(default=1.0, ge=0.1, le=16.0)
    sandbox_pids_limit: int = Field(default=32, ge=4, le=1024)

    @model_validator(mode="after")
    def validate_computation_policy(self) -> "ComputationConfig":
        if self.soft_experiments_per_path > self.hard_experiments_per_path:
            raise ValueError(
                "soft_experiments_per_path cannot exceed hard_experiments_per_path"
            )
        if self.execute_planner_hints_immediately:
            raise ValueError(
                "planner computation hints are non-executable under reasoning_first policy"
            )
        if self.sandboxed_python_enabled:
            image = self.sandbox_image or ""
            if re.fullmatch(r"[^\s@]+@sha256:[0-9a-fA-F]{64}", image) is None:
                raise ValueError(
                    "sandboxed Python requires sandbox_image pinned with @sha256:<64 hex>"
                )
        return self


class RuntimeConfig(ConfigModel):
    run_root: str = "runs"
    output_language: str = "zh-CN"
    max_parallel_calls: int = Field(default=8, ge=1, le=128)
    parse_retries: int = Field(default=1, ge=0, le=5)
    request_retries: int = Field(default=3, ge=0, le=10)
    checkpoint_every_stage: bool = True
    save_raw_provider_responses: bool = True
    redact_prompts_in_console: bool = True
    activity_mode: Literal["off", "compact", "detailed"] = "compact"
    activity_max_visible: int = Field(default=18, ge=4, le=200)
    activity_persist: bool = True
    activity_include_agent_calls: bool = True
    activity_heartbeat_seconds: float = Field(default=20.0, ge=0.0, le=600.0)
    log_level: Literal["DEBUG", "INFO", "WARNING", "ERROR"] = "INFO"
    random_seed: int = 20260719


class SystemConfig(ConfigModel):
    system_name: str = "MathProofMesh"
    agents: list[AgentConfig]
    budget: BudgetConfig = Field(default_factory=BudgetConfig)
    scheduler: SchedulerConfig = Field(default_factory=SchedulerConfig)
    topology: TopologyConfig = Field(default_factory=TopologyConfig)
    verification: VerificationConfig = Field(default_factory=VerificationConfig)
    continuation: ContinuationConfig = Field(default_factory=ContinuationConfig)
    computation: ComputationConfig = Field(default_factory=ComputationConfig)
    runtime: RuntimeConfig = Field(default_factory=RuntimeConfig)

    @field_validator("agents")
    @classmethod
    def validate_agents(cls, agents: list[AgentConfig]) -> list[AgentConfig]:
        enabled = [a for a in agents if a.enabled]
        if not enabled:
            raise ValueError("at least one enabled agent is required")
        ids = [a.id for a in agents]
        if len(ids) != len(set(ids)):
            raise ValueError("agent ids must be unique")
        return agents

    def redacted_dict(self) -> dict[str, Any]:
        data = self.model_dump(
            mode="json", exclude={"agents": {"__all__": {"api_key"}}}
        )
        for agent in data.get("agents", []):
            if agent.get("api_key_env"):
                agent["key_status"] = "configured-via-env"
            else:
                agent["key_status"] = "inline-secret-redacted"
        return data


def load_config(path: str | Path) -> SystemConfig:
    config_path = Path(path)
    if not config_path.exists():
        raise FileNotFoundError(config_path)
    with config_path.open("r", encoding="utf-8") as f:
        raw = yaml.safe_load(f)
    if not isinstance(raw, dict):
        raise ValueError("configuration root must be a mapping")
    config = SystemConfig.model_validate(raw)
    return config
