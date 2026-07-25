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

from .schemas import ComputationPurpose


class ConfigModel(BaseModel):
    model_config = ConfigDict(
        extra="forbid", validate_assignment=True, str_strip_whitespace=True
    )


ProviderName = Literal["openai_compatible", "deepseek", "anthropic", "gemini", "mock"]
ReasoningEffort = Literal["high", "max"]
StageThinkingMode = Literal["agent_default", "disabled", "high", "max", "tiered"]
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
    "route_prover",
    "route_skeptic",
    "tool_specialist",
    "route_referee",
    "bridge_prover",
    "conflict_resolver",
    "counterexample_hunter",
    "representation_switchboard",
    "analogy_agent",
    "construction_inventor",
    "invariant_hypothesis_agent",
    "reverse_goal_analyzer",
    "meta_strategist",
    "inspiration_referee",
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
    provider_max_output_tokens: int = Field(default=384000, ge=256, le=384000)
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
        if self.max_output_tokens > self.provider_max_output_tokens:
            raise ValueError(
                f"agent {self.id}: max_output_tokens cannot exceed provider_max_output_tokens"
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
    hard_stagnation_enabled: bool = True
    max_normal_attempts_per_signature: int = Field(default=1, ge=1, le=4)
    max_repair_attempts_per_signature: int = Field(default=1, ge=0, le=4)
    global_no_progress_rounds_before_meta_pivot: int = Field(default=2, ge=1, le=32)
    global_no_progress_rounds_before_stop: int = Field(default=3, ge=2, le=64)

    # Hierarchical topology and proof-debt signals. Defaults preserve legacy
    # scheduling because they are only consumed in active graph/topology mode.
    proof_debt_reduction_weight: float = Field(default=0.22, ge=0.0, le=4.0)
    verified_fact_gain_weight: float = Field(default=0.24, ge=0.0, le=4.0)
    shared_bottleneck_weight: float = Field(default=0.18, ge=0.0, le=4.0)
    message_utility_weight: float = Field(default=0.10, ge=0.0, le=4.0)
    route_redundancy_penalty: float = Field(default=0.24, ge=0.0, le=4.0)
    contradiction_priority_weight: float = Field(default=0.36, ge=0.0, le=4.0)
    counterexample_priority_weight: float = Field(default=0.42, ge=0.0, le=4.0)
    bridge_priority_weight: float = Field(default=0.28, ge=0.0, le=4.0)
    inspiration_novelty_weight: float = Field(default=0.20, ge=0.0, le=4.0)
    representation_switch_weight: float = Field(default=0.16, ge=0.0, le=4.0)
    analogy_relevance_weight: float = Field(default=0.12, ge=0.0, le=4.0)
    construction_relevance_weight: float = Field(default=0.14, ge=0.0, le=4.0)
    surprise_exploration_weight: float = Field(default=0.10, ge=0.0, le=4.0)
    meta_replan_weight: float = Field(default=0.15, ge=0.0, le=4.0)
    core_proof_debt_reduction_weight: float = Field(default=0.32, ge=0.0, le=4.0)
    core_verified_bridge_gain_weight: float = Field(default=0.25, ge=0.0, le=4.0)
    auxiliary_fact_gain_weight: float = Field(default=0.08, ge=0.0, le=4.0)
    goal_alignment_weight: float = Field(default=0.18, ge=0.0, le=4.0)
    common_mode_risk_penalty: float = Field(default=0.30, ge=0.0, le=4.0)

    # Proof-obligation weights used by ProofGraphStore.proof_debt().
    obligation_base_weight: float = Field(default=1.0, ge=0.0, le=100.0)
    obligation_main_goal_weight: float = Field(default=2.0, ge=0.0, le=100.0)
    obligation_centrality_weight: float = Field(default=1.0, ge=0.0, le=100.0)
    obligation_dependency_weight: float = Field(default=0.25, ge=0.0, le=100.0)
    obligation_shared_route_weight: float = Field(default=0.40, ge=0.0, le=100.0)
    obligation_failure_weight: float = Field(default=0.30, ge=0.0, le=100.0)
    obligation_conflict_weight: float = Field(default=0.75, ge=0.0, le=100.0)

    @model_validator(mode="after")
    def validate_hard_stagnation_limits(self) -> "SchedulerConfig":
        if (
            self.global_no_progress_rounds_before_stop
            <= self.global_no_progress_rounds_before_meta_pivot
        ):
            raise ValueError(
                "global no-progress stop must occur after the meta-pivot round"
            )
        return self


class TypedCommunicationConfig(ConfigModel):
    enabled: bool = False
    schema_version: str = "1"
    require_problem_hash: bool = True
    require_content_hash: bool = True
    require_receipt: bool = True
    exactly_once_delivery: bool = True
    max_message_chars: int = Field(default=24000, ge=1000, le=500000)
    max_assumptions: int = Field(default=24, ge=0, le=256)
    max_dependencies: int = Field(default=64, ge=0, le=1024)


class RouteTeamConfig(ConfigModel):
    enabled: bool = False
    max_members_per_route: int = Field(default=3, ge=1, le=8)
    local_review_rounds: int = Field(default=1, ge=0, le=8)
    skeptic_on_high_risk_only: bool = True
    skeptic_risk_threshold: float = Field(default=0.55, ge=0.0, le=1.0)
    tool_agent_on_demand: bool = True
    referee_required_before_global_share: bool = True
    referee_must_differ_from_author: bool = True
    allow_role_reuse_after_stage: bool = True


class CrossRouteConfig(ConfigModel):
    enabled: bool = False
    initial_isolation_rounds: int = Field(default=1, ge=0, le=16)
    max_neighbors_per_route: int = Field(default=2, ge=0, le=32)
    max_messages_per_route_per_round: int = Field(default=6, ge=0, le=128)
    max_global_messages_per_round: int = Field(default=24, ge=1, le=1024)
    share_verified_facts: bool = True
    share_counterexamples: bool = True
    share_open_obligations: bool = True
    share_unverified_insights: bool = False
    share_failure_records: bool = True
    message_ttl_rounds: int = Field(default=2, ge=1, le=32)


class BrokerConfig(ConfigModel):
    contradiction_detection: bool = True
    bridge_detection: bool = True
    duplicate_route_detection: bool = True
    contradiction_similarity_threshold: float = Field(default=0.82, ge=0.0, le=1.0)
    bridge_similarity_threshold: float = Field(default=0.78, ge=0.0, le=1.0)
    duplicate_strategy_threshold: float = Field(default=0.84, ge=0.0, le=1.0)
    min_routes_for_bridge: int = Field(default=2, ge=2, le=32)
    max_bridge_tasks_per_round: int = Field(default=2, ge=0, le=32)
    max_conflict_tasks_per_round: int = Field(default=2, ge=0, le=32)
    deterministic_matching_first: bool = True
    use_llm_matching_on_ambiguous: bool = True
    ambiguous_match_low: float = Field(default=0.55, ge=0.0, le=1.0)
    ambiguous_match_high: float = Field(default=0.82, ge=0.0, le=1.0)

    @model_validator(mode="after")
    def validate_match_band(self) -> "BrokerConfig":
        if self.ambiguous_match_low > self.ambiguous_match_high:
            raise ValueError("ambiguous_match_low cannot exceed ambiguous_match_high")
        return self


class ProofGraphConfig(ConfigModel):
    enabled: bool = False
    mode: Literal["off", "shadow", "active"] = "shadow"
    max_nodes: int = Field(default=5000, ge=100, le=100000)
    max_edges: int = Field(default=20000, ge=100, le=500000)
    allow_cycles: bool = False
    close_obligation_threshold: float = Field(default=0.80, ge=0.0, le=1.0)
    shared_bottleneck_min_routes: int = Field(default=2, ge=2, le=32)
    freeze_before_synthesis: bool = True


class TypedMemoryConfig(ConfigModel):
    enabled: bool = False
    strict_fact_gate: bool = True
    fact_pass_threshold: float = Field(default=0.80, ge=0.0, le=1.0)
    counterexample_is_global_negative: bool = True
    retain_rejected_claims: bool = True
    retain_expired_insights: bool = True
    max_fact_context: int = Field(default=32, ge=1, le=512)
    max_insight_context: int = Field(default=16, ge=0, le=512)
    max_negative_context: int = Field(default=16, ge=0, le=512)


class InspirationConfig(ConfigModel):
    enabled: bool = False
    mode: Literal["off", "shadow", "active"] = "shadow"

    stagnation_rounds: int = Field(default=2, ge=1, le=32)
    minimum_verified_gain: int = Field(default=1, ge=0, le=128)
    proof_debt_min_reduction: float = Field(default=0.03, ge=0.0, le=1.0)
    repeated_error_threshold: int = Field(default=2, ge=1, le=32)
    route_redundancy_trigger: float = Field(default=0.80, ge=0.0, le=1.0)
    route_budget_share_trigger: float = Field(default=0.50, ge=0.0, le=1.0)
    all_routes_failed_trigger: bool = True
    shared_bottleneck_trigger: bool = True
    final_repair_failure_trigger: bool = True

    representation_switchboard: bool = True
    analogy_agent: bool = True
    auxiliary_construction_inventor: bool = True
    invariant_hypothesis_agent: bool = True
    reverse_goal_analysis: bool = True
    bridge_lemma_generator: bool = True
    persistent_meta_strategist: bool = True
    surprise_exploration: bool = True
    domain_operator_plugins_enabled: bool = True
    bidirectional_frontier_enabled: bool = True
    inspiration_composer_enabled: bool = True
    controlled_surprise_mutation_enabled: bool = True

    surprise_budget_fraction: float = Field(default=0.08, ge=0.0, le=0.50)
    surprise_budget_min_calls: int = Field(default=10, ge=0, le=64)
    surprise_budget_max_calls: int = Field(default=32, ge=0, le=128)
    max_inspiration_tasks_per_round: int = Field(default=2, ge=0, le=32)
    max_proposals_per_task: int = Field(default=3, ge=1, le=16)
    active_proposals_per_task: int = Field(default=3, ge=1, le=16)
    proposer_generalist_roles: list[RoleName] = Field(
        default_factory=lambda: ["explorer", "route_prover"]
    )
    max_single_agent_proposals_per_task: int = Field(default=2, ge=1, le=16)
    max_reviewed_proposals_per_task: int = Field(default=2, ge=1, le=16)
    max_materialized_proposals_per_trigger: int = Field(default=1, ge=0, le=16)
    cold_context_proposals_per_task: int = Field(default=1, ge=0, le=16)
    warm_context_max_facts: int = Field(default=5, ge=0, le=64)
    warm_context_max_negatives: int = Field(default=5, ge=0, le=64)
    inspiration_context_max_chars: int = Field(default=24000, ge=1000, le=500000)
    max_new_routes_per_trigger: int = Field(default=2, ge=0, le=16)
    protect_finalization_reserve: bool = True
    max_consecutive_surprise_rejections: int = Field(default=2, ge=1, le=32)
    surprise_cooldown_rounds: int = Field(default=2, ge=0, le=32)
    domain_operator_max_prompt_items: int = Field(default=8, ge=1, le=64)
    frontier_max_forward_claims: int = Field(default=8, ge=1, le=64)
    frontier_max_backward_claims: int = Field(default=8, ge=1, le=64)
    frontier_max_bridge_candidates: int = Field(default=3, ge=1, le=32)
    frontier_min_candidate_overlap: float = Field(default=0.35, ge=0.0, le=1.0)
    composer_max_candidates_per_round: int = Field(default=2, ge=0, le=16)
    composer_max_sources: int = Field(default=2, ge=2, le=4)
    composer_max_combined_cost: int = Field(default=4, ge=1, le=32)
    composer_require_quick_falsification: bool = True

    novelty_threshold: float = Field(default=0.65, ge=0.0, le=1.0)
    mechanism_duplicate_threshold: float = Field(default=0.86, ge=0.0, le=1.0)
    require_inspiration_referee: bool = True
    proposals_enter_fact_memory: bool = False
    novelty_representation_weight: float = Field(default=0.24, ge=0.0, le=1.0)
    novelty_mechanism_weight: float = Field(default=0.24, ge=0.0, le=1.0)
    novelty_object_weight: float = Field(default=0.16, ge=0.0, le=1.0)
    novelty_transformation_weight: float = Field(default=0.16, ge=0.0, le=1.0)
    novelty_principle_weight: float = Field(default=0.10, ge=0.0, le=1.0)
    novelty_obligation_weight: float = Field(default=0.10, ge=0.0, le=1.0)

    analogy_library_enabled: bool = True
    analogy_library_path: str = "benchmarks/analogy_library.jsonl"
    analogy_top_k: int = Field(default=6, ge=1, le=64)
    allow_external_retrieval: bool = False

    meta_directives_enabled: bool = True
    meta_directive_expiry_rounds: int = Field(default=2, ge=0, le=32)
    meta_directive_cooldown_rounds: int = Field(default=2, ge=1, le=32)
    meta_directive_max_estimated_calls: int = Field(default=8, ge=0, le=64)
    meta_allow_route_abandon: bool = True

    adaptive_mechanism_selection: bool = True
    adaptive_min_observations: int = Field(default=1, ge=0, le=64)
    adaptive_min_exploration_rate: float = Field(default=0.15, ge=0.0, le=1.0)
    adaptive_ucb_weight: float = Field(default=0.75, ge=0.0, le=8.0)
    adaptive_reward_fact_weight: float = Field(default=4.0, ge=0.0, le=32.0)
    adaptive_reward_debt_weight: float = Field(default=1.0, ge=0.0, le=32.0)
    adaptive_reward_obligation_weight: float = Field(default=1.0, ge=0.0, le=32.0)
    adaptive_reward_final_citation_weight: float = Field(default=3.0, ge=0.0, le=32.0)
    adaptive_reward_call_cost: float = Field(default=0.10, ge=0.0, le=8.0)
    adaptive_reward_token_cost_per_100k: float = Field(default=0.10, ge=0.0, le=8.0)
    adaptive_reward_refutation_cost: float = Field(default=1.0, ge=0.0, le=32.0)

    experience_distillation_enabled: bool = True
    negative_analogy_library_enabled: bool = True
    max_distilled_experiences: int = Field(default=256, ge=0, le=10000)
    max_negative_analogy_records: int = Field(default=256, ge=0, le=10000)
    cross_run_learning_enabled: bool = False
    cross_run_learning_path: str = ".mathproofmesh/learning"
    cross_run_require_final_citation: bool = True
    cross_run_max_experiences: int = Field(default=2000, ge=0, le=100000)
    cross_run_max_negative_analogies: int = Field(default=2000, ge=0, le=100000)
    cross_run_max_outcomes: int = Field(default=10000, ge=0, le=500000)

    @model_validator(mode="after")
    def validate_inspiration(self) -> "InspirationConfig":
        if self.surprise_budget_min_calls > self.surprise_budget_max_calls:
            raise ValueError(
                "surprise_budget_min_calls cannot exceed surprise_budget_max_calls"
            )
        if self.max_reviewed_proposals_per_task > self.active_proposals_per_task:
            raise ValueError(
                "max_reviewed_proposals_per_task cannot exceed "
                "active_proposals_per_task"
            )
        if self.cold_context_proposals_per_task > self.active_proposals_per_task:
            raise ValueError(
                "cold_context_proposals_per_task cannot exceed "
                "active_proposals_per_task"
            )
        if len(set(self.proposer_generalist_roles)) != len(
            self.proposer_generalist_roles
        ):
            raise ValueError("proposer_generalist_roles cannot contain duplicates")
        if self.composer_max_sources > self.max_reviewed_proposals_per_task:
            raise ValueError(
                "composer_max_sources cannot exceed max_reviewed_proposals_per_task"
            )
        if self.proposals_enter_fact_memory:
            raise ValueError("inspiration proposals must enter InsightMemory first")
        novelty_total = (
            self.novelty_representation_weight
            + self.novelty_mechanism_weight
            + self.novelty_object_weight
            + self.novelty_transformation_weight
            + self.novelty_principle_weight
            + self.novelty_obligation_weight
        )
        if novelty_total <= 0:
            raise ValueError("at least one novelty weight must be positive")
        return self


class ValidationEscalationConfig(ConfigModel):
    enabled: bool = True
    deterministic_checks_first: bool = True
    blind_same_model_review: bool = True
    adversarial_prompt_review: bool = True
    cross_provider_review: bool = False
    tool_or_formal_check_on_high_risk: bool = True
    high_risk_threshold: float = Field(default=0.75, ge=0.0, le=1.0)
    escalate_on_reviewer_disagreement: bool = True
    escalate_before_fact_promotion: bool = True
    escalate_final_proof: bool = True


class AgentCapabilityConfig(ConfigModel):
    enabled: bool = True
    min_observations_before_trust_update: int = Field(default=5, ge=1, le=10000)
    recency_decay: float = Field(default=0.98, ge=0.0, le=1.0)
    mutation_benchmark_weight: float = Field(default=0.30, ge=0.0, le=1.0)
    tool_agreement_weight: float = Field(default=0.25, ge=0.0, le=1.0)
    first_error_accuracy_weight: float = Field(default=0.25, ge=0.0, le=1.0)
    overturn_rate_penalty: float = Field(default=0.20, ge=0.0, le=1.0)
    use_self_reported_confidence: bool = False

    @model_validator(mode="after")
    def reject_self_reported_confidence(self) -> "AgentCapabilityConfig":
        if self.use_self_reported_confidence:
            raise ValueError("self-reported confidence cannot update capability")
        return self


class FinalTopologyConfig(ConfigModel):
    freeze_graph_before_synthesis: bool = True
    blind_structural_review: bool = True
    blind_detailed_review: bool = True
    remove_agent_identity: bool = True
    remove_route_ranking: bool = True
    remove_self_confidence: bool = True


class GoalAlignmentControlConfig(ConfigModel):
    enabled: bool = True
    min_alignment_confidence: float = Field(default=0.72, ge=0.0, le=1.0)
    overstrength_penalty: float = Field(default=0.25, ge=0.0, le=2.0)
    require_target_obligation: bool = True
    require_implication_outline: bool = True
    run_countermodel_on_unknown_relation: bool = True
    max_strategy_rewrite_attempts: int = Field(default=1, ge=0, le=8)


class ScopeGuardControlConfig(ConfigModel):
    enabled: bool = True
    deterministic_first: bool = True
    ambiguous_model_review: bool = True
    risk_confidence_threshold: float = Field(default=0.70, ge=0.0, le=1.0)
    max_countermodel_tasks_per_round: int = Field(default=2, ge=0, le=16)
    block_fact_promotion_on_open_scope_risk: bool = True
    block_obligation_close_on_scope_mismatch: bool = True


class CoreDebtControlConfig(ConfigModel):
    enabled: bool = True
    main_goal_weight: float = Field(default=4.0, ge=0.0, le=100.0)
    core_bridge_weight: float = Field(default=2.5, ge=0.0, le=100.0)
    auxiliary_weight: float = Field(default=0.35, ge=0.0, le=100.0)
    necessary_only_weight: float = Field(default=0.15, ge=0.0, le=100.0)
    unresolved_scope_risk_weight: float = Field(default=1.5, ge=0.0, le=100.0)
    common_mode_weight: float = Field(default=1.5, ge=0.0, le=100.0)
    core_stagnation_rounds: int = Field(default=2, ge=1, le=32)


class RealizerControlConfig(ConfigModel):
    enabled: bool = True
    max_realizer_repairs_per_structure: int = Field(default=2, ge=0, le=16)
    require_explicit_admissibility: bool = True
    require_well_founded_descent: bool = True
    require_falsification_test: bool = True
    preserve_abstract_proposal_after_candidate_failure: bool = True


class InductionControlConfig(ConfigModel):
    enabled: bool = True
    max_candidates_per_trigger: int = Field(default=3, ge=1, le=16)
    trigger_on_first_occurrence_barrier: bool = True
    trigger_on_recursive_same_type_dependency: bool = True
    trigger_on_repeated_feature: bool = True
    require_well_foundedness_statement: bool = True


class FailureControlConfig(ConfigModel):
    enabled: bool = True
    ambiguous_model_review: bool = True
    min_classification_confidence: float = Field(default=0.70, ge=0.0, le=1.0)
    max_blueprint_rewrites_per_route: int = Field(default=1, ge=0, le=8)
    framing_failure_forces_reanchor: bool = True
    plan_failure_blocks_local_deepen: bool = True
    bridge_failure_prefers_bridge_action: bool = True


class BottleneckControlConfig(ConfigModel):
    enabled: bool = True
    mode: Literal["off", "shadow", "active"] = "shadow"
    compression_interval_rounds: int = Field(default=1, ge=1, le=16)
    equivalence_threshold: float = Field(default=0.84, ge=0.0, le=1.0)
    dominance_threshold: float = Field(default=0.90, ge=0.0, le=1.0)
    max_cluster_members: int = Field(default=64, ge=2, le=1000)
    preserve_original_nodes: bool = True
    min_open_growth_for_forced_compression: int = Field(default=8, ge=1, le=1000)


class CommonModeControlConfig(ConfigModel):
    enabled: bool = True
    min_routes: int = Field(default=3, ge=2, le=32)
    risk_threshold: float = Field(default=0.60, ge=0.0, le=1.0)
    max_challengers_per_round: int = Field(default=1, ge=0, le=8)
    include_strategy_prerequisites: bool = True
    include_critical_claims: bool = True
    include_unverified_dependencies: bool = True


class MessageUtilityControlConfig(ConfigModel):
    enabled: bool = True
    require_utility_contract_for_cross_route: bool = True
    max_target_obligations: int = Field(default=8, ge=1, le=64)
    utility_credit_horizon_rounds: int = Field(default=3, ge=1, le=32)
    no_use_cooldown_threshold: int = Field(default=3, ge=1, le=32)
    final_citation_credit: float = Field(default=1.0, ge=0.0, le=4.0)
    obligation_close_credit: float = Field(default=0.8, ge=0.0, le=4.0)
    verified_step_credit: float = Field(default=0.5, ge=0.0, le=4.0)
    refutation_credit: float = Field(default=0.8, ge=0.0, le=4.0)
    blueprint_rewrite_credit: float = Field(default=0.4, ge=0.0, le=4.0)


class NearMissControlConfig(ConfigModel):
    enabled: bool = True
    min_verifier_confidence: float = Field(default=0.65, ge=0.0, le=1.0)
    max_records: int = Field(default=512, ge=0, le=10000)
    max_route_context_items: int = Field(default=6, ge=0, le=64)
    extraction_requires_salvageable_component: bool = True
    allow_model_extraction_on_ambiguous: bool = True


class FalsificationFastLaneControlConfig(ConfigModel):
    enabled: bool = True
    exact_arithmetic_only: bool = True
    max_runtime_seconds: float = Field(default=10.0, ge=0.1, le=120.0)
    max_memory_mb: int = Field(default=256, ge=32, le=4096)
    max_cases: int = Field(default=100_000, ge=1, le=10_000_000)
    max_tasks_per_round: int = Field(default=2, ge=0, le=16)
    allow_sandboxed_python: bool = False
    auto_fact_promotion: bool = False
    allowed_purposes: list[ComputationPurpose] = Field(
        default_factory=lambda: [
            ComputationPurpose.FALSIFY_CLAIM,
            ComputationPurpose.TEST_BOUNDARY_CASES,
        ]
    )


class RouteAdmissionControlConfig(ConfigModel):
    enabled: bool = True
    mode: Literal["off", "shadow", "active"] = "shadow"
    min_goal_alignment: float = Field(default=0.65, ge=0.0, le=1.0)
    reject_necessary_only_as_main_route: bool = True
    reject_heuristic_only_as_main_route: bool = True
    require_falsification_test: bool = True
    require_mechanism_novelty: bool = True
    max_regeneration_attempts: int = Field(default=1, ge=0, le=4)
    fail_closed_if_all_rejected: bool = True


class ContinueGateControlConfig(ConfigModel):
    enabled: bool = True
    mode: Literal["off", "shadow", "active"] = "shadow"
    no_core_progress_segments: int = Field(default=2, ge=1, le=16)
    require_any_core_signal: bool = True
    allow_on_core_obligation_closed: bool = True
    allow_on_core_debt_reduced: bool = True
    allow_on_first_error_changed: bool = True
    allow_on_verified_bridge_gain: bool = True
    force_blueprint_rewrite_after_block: bool = True


class SynthesisReadinessControlConfig(ConfigModel):
    enabled: bool = True
    mode: Literal["off", "shadow", "active"] = "shadow"
    require_core_dependency_closure: bool = True
    require_no_open_scope_risks: bool = True
    require_no_unresolved_high_centrality_conflicts: bool = True
    require_no_necessary_only_bridge_as_sufficient: bool = True
    max_open_auxiliary_obligations: int = Field(default=16, ge=0, le=10000)
    high_centrality_threshold: float = Field(default=0.70, ge=0.0, le=1.0)
    produce_progress_report_when_blocked: bool = True


class ProofControlConfig(ConfigModel):
    enabled: bool = False
    mode: Literal["off", "shadow", "active"] = "off"
    strict_fail_closed: bool = True
    goal_alignment: GoalAlignmentControlConfig = Field(
        default_factory=GoalAlignmentControlConfig
    )
    scope_guard: ScopeGuardControlConfig = Field(
        default_factory=ScopeGuardControlConfig
    )
    core_debt: CoreDebtControlConfig = Field(default_factory=CoreDebtControlConfig)
    realizer: RealizerControlConfig = Field(default_factory=RealizerControlConfig)
    induction: InductionControlConfig = Field(default_factory=InductionControlConfig)
    failure: FailureControlConfig = Field(default_factory=FailureControlConfig)
    bottleneck: BottleneckControlConfig = Field(default_factory=BottleneckControlConfig)
    common_mode: CommonModeControlConfig = Field(
        default_factory=CommonModeControlConfig
    )
    message_utility: MessageUtilityControlConfig = Field(
        default_factory=MessageUtilityControlConfig
    )
    near_miss: NearMissControlConfig = Field(default_factory=NearMissControlConfig)
    falsification_fast_lane: FalsificationFastLaneControlConfig = Field(
        default_factory=FalsificationFastLaneControlConfig
    )
    route_admission: RouteAdmissionControlConfig = Field(
        default_factory=RouteAdmissionControlConfig
    )
    continue_gate: ContinueGateControlConfig = Field(
        default_factory=ContinueGateControlConfig
    )
    synthesis_readiness: SynthesisReadinessControlConfig = Field(
        default_factory=SynthesisReadinessControlConfig
    )


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
    mode: Literal["legacy_sparse", "hierarchical_sparse"] = "legacy_sparse"
    typed_communication: TypedCommunicationConfig = Field(
        default_factory=TypedCommunicationConfig
    )
    route_teams: RouteTeamConfig = Field(default_factory=RouteTeamConfig)
    cross_route: CrossRouteConfig = Field(default_factory=CrossRouteConfig)
    broker: BrokerConfig = Field(default_factory=BrokerConfig)
    proof_graph: ProofGraphConfig = Field(default_factory=ProofGraphConfig)
    typed_memory: TypedMemoryConfig = Field(default_factory=TypedMemoryConfig)
    final_stage: FinalTopologyConfig = Field(default_factory=FinalTopologyConfig)
    inspiration: InspirationConfig = Field(default_factory=InspirationConfig)
    validation_escalation: ValidationEscalationConfig = Field(
        default_factory=ValidationEscalationConfig
    )
    agent_capability: AgentCapabilityConfig = Field(
        default_factory=AgentCapabilityConfig
    )
    proof_control: ProofControlConfig = Field(default_factory=ProofControlConfig)

    @model_validator(mode="after")
    def validate_hierarchical_topology(self) -> "TopologyConfig":
        if self.mode == "legacy_sparse":
            return self
        if self.proof_graph.mode != "off" and not self.proof_graph.enabled:
            raise ValueError("proof_graph.mode requires proof_graph.enabled=true")
        if self.route_teams.enabled and not self.typed_communication.enabled:
            raise ValueError("route teams require typed communication")
        if self.cross_route.enabled and not self.typed_communication.enabled:
            raise ValueError("cross-route routing requires typed communication")
        if self.inspiration.mode != "off" and not self.inspiration.enabled:
            raise ValueError("inspiration.mode requires inspiration.enabled=true")
        return self

    @model_validator(mode="after")
    def validate_proof_control(self) -> "TopologyConfig":
        control = self.proof_control
        if control.mode != "off" and not control.enabled:
            raise ValueError("proof_control.mode requires proof_control.enabled=true")
        if control.mode == "active":
            if self.mode != "hierarchical_sparse":
                raise ValueError("active proof control requires hierarchical_sparse")
            if not self.proof_graph.enabled or self.proof_graph.mode != "active":
                raise ValueError("active proof control requires active proof graph")
            if not self.typed_memory.enabled:
                raise ValueError("active proof control requires typed memory")
            if not self.typed_communication.enabled:
                raise ValueError("active proof control requires typed communication")
        if control.falsification_fast_lane.auto_fact_promotion:
            raise ValueError("fast-lane computation must never auto-promote Fact")
        if control.falsification_fast_lane.allow_sandboxed_python:
            raise ValueError(
                "automatic falsification fast lane cannot use sandboxed Python"
            )
        return self


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
    max_output_tokens_per_segment: int = Field(default=12000, ge=512, le=384000)
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
    post_failure_bottleneck_enabled: bool = True
    post_failure_bottleneck_max_output_tokens: int = Field(
        default=16000, ge=512, le=32000
    )
    post_failure_bottleneck_once_per_checkpoint: bool = True
    post_failure_trigger_inspiration: bool = True


class ExplorationTierPolicyConfig(ConfigModel):
    """One server-owned deep-exploration operating tier."""

    output_tokens: int = Field(ge=512, le=384000)
    artifact_recovery_tokens: int = Field(ge=256, le=128000)

    @model_validator(mode="before")
    @classmethod
    def discard_legacy_elapsed_time_limits(cls, value: Any) -> Any:
        if not isinstance(value, dict):
            return value
        normalized = dict(value)
        normalized.pop("no_content_timeout_seconds", None)
        normalized.pop("wall_timeout_seconds", None)
        legacy_reserve = normalized.pop("answer_reserve_tokens", None)
        if legacy_reserve is not None and "artifact_recovery_tokens" not in normalized:
            normalized["artifact_recovery_tokens"] = legacy_reserve
        return normalized

    @model_validator(mode="after")
    def validate_tier(self) -> "ExplorationTierPolicyConfig":
        if self.artifact_recovery_tokens >= self.output_tokens:
            raise ValueError(
                "artifact_recovery_tokens must be lower than output_tokens"
            )
        return self


class DeepExplorationPolicyConfig(ConfigModel):
    """Evidence-gated long reasoning without a global high-tier concurrency cap."""

    enabled: bool = True
    tiers: list[ExplorationTierPolicyConfig] = Field(
        default_factory=lambda: [
            ExplorationTierPolicyConfig(
                output_tokens=64000,
                artifact_recovery_tokens=8000,
            ),
            ExplorationTierPolicyConfig(
                output_tokens=96000,
                artifact_recovery_tokens=12000,
            ),
            ExplorationTierPolicyConfig(
                output_tokens=128000,
                artifact_recovery_tokens=16000,
            ),
        ],
        min_length=1,
        max_length=8,
    )
    high_tier_threshold_tokens: int = Field(default=96000, ge=512, le=384000)
    partial_repair_max_output_tokens: int = Field(default=64000, ge=512, le=384000)
    max_partial_repairs_per_signature: int = Field(default=1, ge=0, le=4)
    max_running_per_signature: int = Field(default=1, ge=1, le=4)
    no_progress_high_tier_limit_per_signature: int = Field(default=1, ge=1, le=8)
    semantic_duplicate_threshold: float = Field(default=0.86, ge=0.5, le=1.0)
    allow_parallel_distinct_signatures: bool = True
    allow_local_bottleneck_pivot: bool = True
    require_novelty_review_for_pivot: bool = True
    require_meta_approval_for_128k: bool = True
    min_remaining_calls_for_128k: int = Field(default=8, ge=1, le=1000)
    min_remaining_tokens_for_128k: int = Field(default=256000, ge=128000, le=1000000000)
    preserve_parent_verified_checkpoint: bool = True
    reset_on_verified_checkpoint: bool = True
    reset_on_referee_confirmed_mechanism_change: bool = True
    persist_across_resume: bool = True

    @model_validator(mode="after")
    def validate_policy(self) -> "DeepExplorationPolicyConfig":
        values = [item.output_tokens for item in self.tiers]
        if values != sorted(set(values)):
            raise ValueError("deep exploration tiers must be strictly increasing")
        if self.partial_repair_max_output_tokens > values[-1]:
            raise ValueError(
                "partial_repair_max_output_tokens cannot exceed the highest tier"
            )
        if not self.allow_parallel_distinct_signatures:
            raise ValueError(
                "distinct deep-exploration signatures must remain parallelizable"
            )
        return self

    def tier_for_limit(self, output_tokens: int | None) -> ExplorationTierPolicyConfig:
        requested = output_tokens or self.tiers[0].output_tokens
        eligible = [item for item in self.tiers if item.output_tokens <= requested]
        return eligible[-1] if eligible else self.tiers[0]

    def tier_index_for_limit(self, output_tokens: int | None) -> int:
        selected = self.tier_for_limit(output_tokens)
        return next(
            index
            for index, item in enumerate(self.tiers)
            if item.output_tokens == selected.output_tokens
        )


class ComputationConfig(ConfigModel):
    """Reasoning-first experiment policy, budgets, and sandbox controls."""

    enabled: bool = False
    policy: Literal["reasoning_first"] = "reasoning_first"
    typed_tools_enabled: bool = True
    sandboxed_python_enabled: bool = False
    execute_planner_hints_immediately: bool = False
    critical_calculation_gate_enabled: bool = True
    critical_calculation_require_declarations: bool = True
    critical_calculation_max_checks_per_artifact: int = Field(default=8, ge=1, le=64)
    targeted_falsification_fast_path: bool = True
    bounded_typed_probe_fast_path: bool = True
    bounded_typed_probe_max_cases: int = Field(default=25_000, ge=1, le=1_000_000)
    soft_experiments_per_path: int = Field(default=2, ge=0, le=100)
    hard_experiments_per_path: int = Field(default=6, ge=1, le=100)
    max_compute_cycles_per_segment: int = Field(default=1, ge=0, le=8)
    max_contract_repairs_per_segment: int = Field(default=1, ge=0, le=2)
    contract_repair_max_output_tokens: int = Field(default=8192, ge=256, le=32000)
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
    project_root: str = "."
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
    # Token budgets control normal reasoning. Time only detects a dead SSE stream
    # or provides one common emergency ceiling for a broken provider call.
    stream_first_chunk_timeout_seconds: float = Field(default=90.0, ge=5.0, le=3600.0)
    stream_idle_timeout_seconds: float = Field(default=300.0, ge=5.0, le=3600.0)
    agent_call_wall_timeout_seconds: float = Field(default=7200.0, ge=5.0, le=7200.0)
    json_repair_max_output_tokens: int = Field(default=8192, ge=256, le=384000)
    # The configured agent limit remains the hard ceiling. Routine stages get a
    # smaller soft ceiling so 96K/128K are reserved for admitted deep route work.
    stage_output_token_limits: dict[str, int] = Field(
        default_factory=lambda: {
            "goal_normalization": 4096,
            "triage": 12000,
            "strategy_generation": 24000,
            "claim_extraction": 12000,
            "structural_verification": 16000,
            "checkpoint_verification": 24000,
            "detailed_verification": 32000,
            "final_verification": 32000,
            "blind_structural_verification": 16000,
            "blind_detailed_verification": 32000,
            "meta_review": 16000,
            "route_skeptic": 24000,
            "route_referee": 16000,
            "route_tool_audit": 16000,
            "representation_switchboard": 24000,
            "structural_analogy_search": 24000,
            "invent_auxiliary_construction": 24000,
            "hypothesize_invariant": 24000,
            "reverse_goal_analysis": 24000,
            "persistent_meta_strategy": 24000,
            "surprise_exploration": 24000,
            "inspiration_referee": 16000,
            "post_failure_bottleneck": 16000,
            "synthesis": 64000,
            "final_revision": 64000,
            "experiment_codegen": 12000,
            "computation_contract_repair": 8192,
            "pattern_conjecture_completion": 4096,
        }
    )
    # DeepSeek does not expose an enforceable reasoning-token budget separate
    # from max_tokens. Routine artifact stages therefore override the agent's
    # default thinking policy, while admitted route tiers retain deep reasoning.
    stage_thinking_modes: dict[str, StageThinkingMode] = Field(
        default_factory=lambda: {
            "goal_normalization": "disabled",
            "triage": "disabled",
            "claim_extraction": "disabled",
            "post_failure_bottleneck": "disabled",
            "computation_contract_repair": "disabled",
            "pattern_conjecture_completion": "disabled",
            "strategy_generation": "high",
            "independent_exploration": "tiered",
            "proof_continuation": "tiered",
            "route_prove": "tiered",
            "structural_verification": "high",
            "checkpoint_verification": "high",
            "detailed_verification": "high",
            "final_verification": "high",
            "blind_structural_verification": "high",
            "blind_detailed_verification": "high",
            "meta_review": "high",
            "route_skeptic": "high",
            "route_referee": "high",
            "route_tool_audit": "high",
            "synthesis": "high",
            "final_revision": "high",
        }
    )
    exploration_output_token_tiers: list[int] = Field(
        default_factory=lambda: [64000, 96000, 128000],
        min_length=1,
        max_length=8,
    )
    provider_circuit_breaker_enabled: bool = True
    provider_circuit_failure_threshold: int = Field(default=2, ge=2, le=32)
    provider_circuit_window_seconds: float = Field(default=90.0, ge=1.0, le=3600.0)
    provider_circuit_cooldown_seconds: float = Field(default=300.0, ge=1.0, le=86400.0)
    provider_terminal_http_statuses: list[int] = Field(default_factory=lambda: [402])
    provider_shared_auth_http_statuses: list[int] = Field(
        default_factory=lambda: [401, 403]
    )
    log_level: Literal["DEBUG", "INFO", "WARNING", "ERROR"] = "INFO"
    random_seed: int = 20260719

    @model_validator(mode="before")
    @classmethod
    def discard_legacy_reasoning_time_limits(cls, value: Any) -> Any:
        if not isinstance(value, dict):
            return value
        normalized = dict(value)
        normalized.pop("reasoning_only_abort_seconds", None)
        normalized.pop("reasoning_only_min_characters", None)
        return normalized

    @field_validator("stage_output_token_limits")
    @classmethod
    def validate_stage_output_limits(cls, value: dict[str, int]) -> dict[str, int]:
        normalized: dict[str, int] = {}
        for stage, limit in value.items():
            if not stage.strip():
                raise ValueError("stage_output_token_limits keys must be non-empty")
            if limit < 256 or limit > 384000:
                raise ValueError(
                    "stage_output_token_limits values must be between 256 and 384000"
                )
            normalized[str(stage)] = int(limit)
        return normalized

    @field_validator("stage_thinking_modes")
    @classmethod
    def validate_stage_thinking_modes(
        cls, value: dict[str, StageThinkingMode]
    ) -> dict[str, StageThinkingMode]:
        if any(not str(stage).strip() for stage in value):
            raise ValueError("stage_thinking_modes keys must be non-empty")
        return {str(stage): mode for stage, mode in value.items()}

    @field_validator(
        "provider_terminal_http_statuses",
        "provider_shared_auth_http_statuses",
    )
    @classmethod
    def validate_provider_http_statuses(cls, value: list[int]) -> list[int]:
        statuses = sorted(set(int(item) for item in value))
        if any(item < 400 or item > 599 for item in statuses):
            raise ValueError("provider circuit HTTP statuses must be in 400..599")
        return statuses

    @field_validator("exploration_output_token_tiers")
    @classmethod
    def validate_exploration_tiers(cls, value: list[int]) -> list[int]:
        tiers = [int(item) for item in value]
        if any(item < 512 or item > 384000 for item in tiers):
            raise ValueError("exploration token tiers must be between 512 and 384000")
        if tiers != sorted(set(tiers)):
            raise ValueError("exploration token tiers must be strictly increasing")
        return tiers


class SystemConfig(ConfigModel):
    system_name: str = "MathProofMesh"
    agents: list[AgentConfig]
    budget: BudgetConfig = Field(default_factory=BudgetConfig)
    scheduler: SchedulerConfig = Field(default_factory=SchedulerConfig)
    topology: TopologyConfig = Field(default_factory=TopologyConfig)
    verification: VerificationConfig = Field(default_factory=VerificationConfig)
    continuation: ContinuationConfig = Field(default_factory=ContinuationConfig)
    deep_exploration_policy: DeepExplorationPolicyConfig = Field(
        default_factory=DeepExplorationPolicyConfig
    )
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

    @model_validator(mode="after")
    def validate_hierarchical_budget_reserve(self) -> "SystemConfig":
        active_hierarchical = self.topology.mode == "hierarchical_sparse" and (
            self.topology.typed_communication.enabled
            or self.topology.route_teams.enabled
            or self.topology.proof_graph.mode == "active"
            or (
                self.topology.inspiration.enabled
                and self.topology.inspiration.mode == "active"
            )
        )
        if active_hierarchical and not self.continuation.enabled:
            raise ValueError(
                "active hierarchical topology requires continuation.enabled=true "
                "so Broker, typed route prompts, and RouteTeam execute in the live pipeline"
            )
        inspiration = self.topology.inspiration
        if (
            self.topology.mode != "hierarchical_sparse"
            or not inspiration.enabled
            or inspiration.mode == "off"
            or not inspiration.protect_finalization_reserve
        ):
            return self
        verification_calls = (
            1
            + self.budget.high_risk_verifier_replicas
            + self.scheduler.verification_call_safety_margin
        )
        revision_cycles = min(
            self.scheduler.reserve_revision_cycles,
            self.budget.max_revisions,
        )
        requested_reserve = 1 + verification_calls
        requested_reserve += revision_cycles * (1 + verification_calls)
        requested_reserve += self.scheduler.finish_transition_buffer_calls
        reserve = min(self.budget.max_total_calls, requested_reserve)
        exploratory_calls = self.budget.max_total_calls - reserve
        if inspiration.surprise_budget_min_calls > exploratory_calls:
            raise ValueError(
                "surprise_budget_min_calls would consume the protected "
                "finalization reserve"
            )
        return self

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
