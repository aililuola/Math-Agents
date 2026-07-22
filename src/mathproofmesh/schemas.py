from __future__ import annotations

import hashlib
import json
from datetime import datetime, timezone
from enum import StrEnum
from typing import Any, Literal
from uuid import uuid4

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator


def utc_now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def stable_hash(value: Any) -> str:
    if isinstance(value, str):
        raw = value.encode("utf-8")
    else:
        raw = json.dumps(
            value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
        ).encode("utf-8")
    return hashlib.sha256(raw).hexdigest()


def new_id(prefix: str) -> str:
    return f"{prefix}_{uuid4().hex[:12]}"


class StrictModel(BaseModel):
    model_config = ConfigDict(
        extra="forbid", validate_assignment=True, str_strip_whitespace=True
    )


class ProblemKind(StrEnum):
    PROOF = "proof"
    CALCULATION = "calculation"
    LOGIC = "logic"
    OPTIMIZATION = "optimization"
    CONSTRUCTION = "construction"
    RESEARCH = "research"
    MIXED = "mixed"
    UNKNOWN = "unknown"


class Difficulty(StrEnum):
    EASY = "easy"
    MEDIUM = "medium"
    HARD = "hard"
    OLYMPIAD = "olympiad"
    RESEARCH = "research"


class AttemptStatus(StrEnum):
    COMPLETE = "complete"
    PARTIAL = "partial"
    FAILED = "failed"


class ClaimStatus(StrEnum):
    PROPOSED = "proposed"
    VERIFIED = "verified"
    REJECTED = "rejected"
    UNCERTAIN = "uncertain"


class CheckpointStatus(StrEnum):
    WORKING = "working"
    TENTATIVE = "tentative"
    VERIFIED = "verified"
    REJECTED = "rejected"
    COMMITTED = "committed"


class VerificationVerdict(StrEnum):
    PASS = "pass"
    FAIL = "fail"
    UNCERTAIN = "uncertain"
    SKIPPED = "skipped"


class VerificationStage(StrEnum):
    STRUCTURAL = "structural"
    DETAILED = "detailed"
    LEMMA = "lemma"
    FINAL = "final"


class FailureLevel(StrEnum):
    NONE = "none"
    EXECUTION = "execution"
    PLAN = "plan"
    STRATEGY = "strategy"


class ActionKind(StrEnum):
    WIDEN = "widen"
    DEEPEN = "deepen"
    VERIFY = "verify"
    SYNTHESIZE = "synthesize"
    REVISE = "revise"
    BRIDGE = "bridge"
    RESOLVE_CONFLICT = "resolve_conflict"
    SEARCH_COUNTEREXAMPLE = "search_counterexample"
    MERGE_ROUTE = "merge_route"
    COOLDOWN_ROUTE = "cooldown_route"
    SWITCH_REPRESENTATION = "switch_representation"
    TRIGGER_INSPIRATION = "trigger_inspiration"
    SEARCH_ANALOGY = "search_analogy"
    INVENT_CONSTRUCTION = "invent_construction"
    GENERATE_INVARIANT = "generate_invariant"
    REVERSE_GOAL = "reverse_goal"
    META_REPLAN = "meta_replan"
    SURPRISE_WIDEN = "surprise_widen"
    STOP = "stop"


class Severity(StrEnum):
    INFO = "info"
    WARNING = "warning"
    ERROR = "error"
    CRITICAL = "critical"


class ComputationPurpose(StrEnum):
    FALSIFY_CLAIM = "falsify_claim"
    CHECK_DERIVED_IDENTITY = "check_derived_identity"
    TEST_BOUNDARY_CASES = "test_boundary_cases"
    VERIFY_FINITE_REDUCTION = "verify_finite_reduction"
    VALIDATE_CONSTRUCTED_EXAMPLE = "validate_constructed_example"
    DISCOVER_PATTERN = "discover_pattern"


class ComputationMethod(StrEnum):
    SYMPY_SIMPLIFY = "sympy_simplify"
    SYMPY_EQUIVALENT = "sympy_equivalent"
    POLYNOMIAL_FACTOR = "polynomial_factor"
    MODULAR_EXHAUSTIVE = "modular_exhaustive"
    BOUNDED_INTEGER_SEARCH = "bounded_integer_search"
    GRAPH_CERTIFICATE = "graph_certificate"
    RECURRENCE_CHECK = "recurrence_check"
    BOUNDED_GREEDY_SEQUENCE = "bounded_greedy_sequence"
    CANDIDATE_PERIOD_CHECK = "candidate_period_check"
    EXACT_GEOMETRY = "exact_geometry"
    NUMERIC_COUNTEREXAMPLE = "numeric_counterexample"
    SANDBOXED_PYTHON = "sandboxed_python"
    LEAN_CHECK = "lean_check"


class ComputationDecisionStatus(StrEnum):
    ALLOW = "allow"
    DEFER = "defer"
    REJECT = "reject"


class EvidenceStrength(StrEnum):
    HEURISTIC = "heuristic"
    BOUNDED_EVIDENCE = "bounded_evidence"
    COUNTEREXAMPLE = "counterexample"
    EXHAUSTIVE_CERTIFICATE = "exhaustive_certificate"
    FORMAL_CERTIFICATE = "formal_certificate"


class RouteRole(StrEnum):
    PROVER = "prover"
    SKEPTIC = "skeptic"
    TOOL_SPECIALIST = "tool_specialist"
    REFEREE = "referee"
    BRIDGE_PROVER = "bridge_prover"
    CONFLICT_RESOLVER = "conflict_resolver"
    COUNTEREXAMPLE_HUNTER = "counterexample_hunter"


class RouteStatus(StrEnum):
    ACTIVE = "active"
    COOLING = "cooling"
    MERGED = "merged"
    ABANDONED = "abandoned"
    COMPLETED = "completed"


class MessageType(StrEnum):
    CLAIM_PROPOSAL = "claim_proposal"
    VERIFIED_LEMMA = "verified_lemma"
    PROOF_OBLIGATION = "proof_obligation"
    COUNTEREXAMPLE = "counterexample"
    CONTRADICTION_NOTICE = "contradiction_notice"
    COMPUTATION_PLAN = "computation_plan"
    COMPUTATION_CERTIFICATE = "computation_certificate"
    FORMAL_CERTIFICATE = "formal_certificate"
    REPAIR_REQUEST = "repair_request"
    BRIDGE_LEMMA_REQUEST = "bridge_lemma_request"
    STRATEGY_REWRITE_REQUEST = "strategy_rewrite_request"
    FAILURE_RECORD = "failure_record"
    ROUTE_CHECKPOINT = "route_checkpoint"


class EvidenceType(StrEnum):
    UNVERIFIED_IDEA = "unverified_idea"
    NUMERICAL_HEURISTIC = "numerical_heuristic"
    BOUNDED_EXPERIMENT = "bounded_experiment"
    EXACT_SYMBOLIC_IDENTITY = "exact_symbolic_identity"
    COMPLETE_FINITE_ENUMERATION = "complete_finite_enumeration"
    SAT_SMT_CERTIFICATE = "sat_smt_certificate"
    COUNTEREXAMPLE = "counterexample"
    NATURAL_PROOF_AUDITED = "natural_proof_audited"
    FORMAL_KERNEL_CERTIFICATE = "formal_kernel_certificate"


class MemoryTier(StrEnum):
    FACT = "fact"
    INSIGHT = "insight"
    NEGATIVE = "negative"


class ObligationKind(StrEnum):
    MAIN_GOAL = "main_goal"
    SUBGOAL = "subgoal"
    LEMMA = "lemma"
    CASE_BRANCH = "case_branch"
    CONSTRUCTION = "construction"
    COMPUTATION_QUESTION = "computation_question"
    FORMALIZATION_TASK = "formalization_task"
    CONTRADICTION = "contradiction"


class GraphEdgeType(StrEnum):
    DEPENDS_ON = "depends_on"
    IMPLIES = "implies"
    REFUTES = "refutes"
    EQUIVALENT_TO = "equivalent_to"
    STRENGTHENS = "strengthens"
    WEAKENS = "weakens"
    USES_CONSTRUCTION = "uses_construction"
    CLOSES = "closes"


class ReceiptStatus(StrEnum):
    ACCEPTED = "accepted"
    REJECTED = "rejected"
    DUPLICATE = "duplicate"
    EXPIRED = "expired"
    DEFERRED = "deferred"


class InspirationTriggerType(StrEnum):
    STAGNATION = "stagnation"
    REPEATED_FIRST_ERROR = "repeated_first_error"
    HIGH_ROUTE_REDUNDANCY = "high_route_redundancy"
    ALL_ROUTES_FAILED = "all_routes_failed"
    SHARED_BOTTLENECK = "shared_bottleneck"
    PROOF_DEBT_PLATEAU = "proof_debt_plateau"
    FINAL_REPAIR_FAILED = "final_repair_failed"
    MANUAL = "manual"


class InspirationMechanism(StrEnum):
    REPRESENTATION_SWITCH = "representation_switch"
    STRUCTURAL_ANALOGY = "structural_analogy"
    AUXILIARY_CONSTRUCTION = "auxiliary_construction"
    INVARIANT_HYPOTHESIS = "invariant_hypothesis"
    REVERSE_GOAL_ANALYSIS = "reverse_goal_analysis"
    BRIDGE_LEMMA = "bridge_lemma"
    SURPRISE_EXPLORATION = "surprise_exploration"
    META_REPLAN = "meta_replan"


class ExperimentOutcome(StrEnum):
    NOT_REFUTED = "not_refuted"
    COUNTEREXAMPLE_FOUND = "counterexample_found"
    CERTIFIED = "certified"
    INCONCLUSIVE = "inconclusive"
    ERROR = "error"


class InitialExplorationAction(StrEnum):
    SUBMIT_ATTEMPT = "submit_attempt"
    REQUEST_COMPUTATION = "request_computation"
    ABANDON = "abandon"


class ContinuationAction(StrEnum):
    SUBMIT_DELTA = "submit_delta"
    REQUEST_COMPUTATION = "request_computation"
    COMPLETE = "complete"
    ABANDON = "abandon"


class UsageRecord(StrictModel):
    input_tokens: int = Field(default=0, ge=0)
    output_tokens: int = Field(default=0, ge=0)
    total_tokens: int = Field(default=0, ge=0)
    estimated_cost_usd: float = Field(default=0.0, ge=0.0)
    latency_ms: float = Field(default=0.0, ge=0.0)

    @model_validator(mode="after")
    def infer_total(self) -> "UsageRecord":
        if self.total_tokens == 0 and (self.input_tokens or self.output_tokens):
            object.__setattr__(
                self, "total_tokens", self.input_tokens + self.output_tokens
            )
        return self


class EvidenceRef(StrictModel):
    artifact_ref: str
    section: str | None = None
    content_hash: str | None = None
    summary: str = ""


class CitationRecord(StrictModel):
    title: str
    authors: list[str] = Field(default_factory=list)
    url: str | None = None
    location: str | None = None
    exact_statement: str | None = None
    applicability_conditions: list[str] = Field(default_factory=list)
    verified: bool = False


class QuantifierSpec(StrictModel):
    order: int = Field(ge=0)
    kind: Literal["forall", "exists", "exists_unique"]
    variable_id: str = Field(min_length=1)
    display_name: str = Field(min_length=1)
    domain: str = Field(min_length=1)
    restrictions: list[str] = Field(default_factory=list)


class VariableBinding(StrictModel):
    variable_id: str = Field(min_length=1)
    display_name: str = Field(min_length=1)
    domain: str = Field(min_length=1)
    owner_scope: str = Field(min_length=1)
    aliases: list[str] = Field(default_factory=list)


class MessageEnvelope(StrictModel):
    schema_version: str = "1"
    message_id: str = Field(default_factory=lambda: new_id("msg"))
    problem_hash: str = Field(min_length=1)
    source_agent_id: str = Field(min_length=1)
    source_route_id: str = Field(min_length=1)
    source_role: RouteRole
    target_route_ids: list[str] = Field(default_factory=list)
    message_type: MessageType

    statement: str = Field(min_length=1)
    normalized_statement: str = Field(min_length=1)
    assumptions: list[str] = Field(default_factory=list)
    conclusion: str = Field(min_length=1)
    quantifiers: list[QuantifierSpec] = Field(default_factory=list)
    variable_bindings: list[VariableBinding] = Field(default_factory=list)
    dependencies: list[str] = Field(default_factory=list)
    scope_limitations: list[str] = Field(default_factory=list)

    evidence_type: EvidenceType
    memory_tier: MemoryTier
    verification_status: ClaimStatus
    verification_confidence: float = Field(default=0.0, ge=0.0, le=1.0)
    normalization_confidence: float = Field(default=0.0, ge=0.0, le=1.0)

    artifact_refs: list[str] = Field(default_factory=list)
    raw_source_ref: str | None = None
    round_created: int = Field(ge=0)
    ttl_rounds: int = Field(default=2, ge=1)
    content_hash: str = ""
    created_at: str = Field(default_factory=utc_now_iso)

    def immutable_payload(self) -> dict[str, Any]:
        return {
            "problem_hash": self.problem_hash,
            "source_route_id": self.source_route_id,
            "message_type": self.message_type.value,
            "normalized_statement": self.normalized_statement,
            "assumptions": self.assumptions,
            "conclusion": self.conclusion,
            "quantifiers": [item.model_dump(mode="json") for item in self.quantifiers],
            "dependencies": self.dependencies,
            "evidence_type": self.evidence_type.value,
            "memory_tier": self.memory_tier.value,
        }

    def expected_content_hash(self) -> str:
        return stable_hash(self.immutable_payload())

    def expected_semantic_hash(self) -> str:
        return stable_hash(
            {
                "assumptions": self.assumptions,
                "conclusion": self.conclusion,
                "quantifiers": [
                    item.model_dump(mode="json") for item in self.quantifiers
                ],
                "variable_bindings": [
                    item.model_dump(mode="json") for item in self.variable_bindings
                ],
            }
        )

    @model_validator(mode="after")
    def validate_scope_and_hash(self) -> "MessageEnvelope":
        orders = [item.order for item in self.quantifiers]
        if len(orders) != len(set(orders)):
            raise ValueError("quantifier orders must be unique")
        if orders and sorted(orders) != list(range(len(orders))):
            raise ValueError("quantifier orders must be contiguous and start at zero")
        binding_ids = [item.variable_id for item in self.variable_bindings]
        if len(binding_ids) != len(set(binding_ids)):
            raise ValueError("variable binding IDs must be unique")
        bindings = {item.variable_id: item for item in self.variable_bindings}
        for quantifier in self.quantifiers:
            binding = bindings.get(quantifier.variable_id)
            if binding is None:
                raise ValueError(
                    f"quantified variable {quantifier.variable_id!r} has no binding"
                )
            if binding.domain != quantifier.domain:
                raise ValueError("quantifier and binding domains must agree")
        expected = self.expected_content_hash()
        if self.content_hash and self.content_hash != expected:
            raise ValueError("message content_hash mismatch")
        object.__setattr__(self, "content_hash", expected)
        return self


class MessageReceipt(StrictModel):
    receipt_id: str = Field(default_factory=lambda: new_id("receipt"))
    message_id: str
    target_route_id: str
    receipt_token: str = ""
    status: ReceiptStatus
    used: bool = False
    parsed_assumptions: list[str] = Field(default_factory=list)
    parsed_conclusion: str = ""
    parsed_quantifiers: list[QuantifierSpec] = Field(default_factory=list)
    parsed_variable_bindings: list[VariableBinding] = Field(default_factory=list)
    referenced_in_step_ids: list[str] = Field(default_factory=list)
    claimed_closed_obligation_ids: list[str] = Field(default_factory=list)
    semantic_hash: str = ""
    reason: str = ""
    delivered_round: int = Field(ge=0)
    acknowledged_at: str = Field(default_factory=utc_now_iso)

    @model_validator(mode="after")
    def validate_parsed_scope(self) -> "MessageReceipt":
        orders = [item.order for item in self.parsed_quantifiers]
        if len(orders) != len(set(orders)):
            raise ValueError("parsed quantifier orders must be unique")
        if orders and sorted(orders) != list(range(len(orders))):
            raise ValueError(
                "parsed quantifier orders must be contiguous and start at zero"
            )
        binding_ids = [item.variable_id for item in self.parsed_variable_bindings]
        if len(binding_ids) != len(set(binding_ids)):
            raise ValueError("parsed variable binding IDs must be unique")
        bindings = {item.variable_id: item for item in self.parsed_variable_bindings}
        for quantifier in self.parsed_quantifiers:
            binding = bindings.get(quantifier.variable_id)
            if binding is None:
                raise ValueError(
                    f"parsed quantified variable {quantifier.variable_id!r} has no binding"
                )
            if binding.domain != quantifier.domain:
                raise ValueError("parsed quantifier and binding domains must agree")
        return self


class ToolAuditReport(StrictModel):
    agent_id: str
    route_id: str
    experiment_ids: list[str] = Field(default_factory=list)
    replay_artifact_refs: list[str] = Field(default_factory=list)
    mathematical_mapping_checked: bool = False
    all_results_replayed_independently: bool = False
    issues: list[str] = Field(default_factory=list)
    verdict: Literal["pass", "fail", "inconclusive"] = "inconclusive"
    confidence: float = Field(default=0.0, ge=0.0, le=1.0)


class RouteMember(StrictModel):
    agent_id: str
    role: RouteRole
    assigned_round: int = Field(ge=0)


class RouteDescriptor(StrictModel):
    route_id: str
    strategy_id: str
    mechanism_signature: list[str]
    status: RouteStatus = RouteStatus.ACTIVE
    members: list[RouteMember] = Field(default_factory=list)
    neighbor_route_ids: list[str] = Field(default_factory=list)
    latest_attempt_id: str | None = None
    latest_checkpoint_id: str | None = None
    failure_count: int = Field(default=0, ge=0)
    stagnation_rounds: int = Field(default=0, ge=0)
    cooldown_until_round: int | None = Field(default=None, ge=0)
    merged_into_route_id: str | None = None
    strategy_signature: str = ""
    shared_assumptions: list[str] = Field(default_factory=list)
    inspiration_proposal_id: str | None = None
    requires_revision: bool = False
    revision_summary: str | None = None


class ProofObligation(StrictModel):
    obligation_id: str = Field(default_factory=lambda: new_id("obl"))
    problem_hash: str
    route_ids: list[str]
    kind: ObligationKind
    statement: str
    normalized_statement: str
    assumptions: list[str] = Field(default_factory=list)
    quantifiers: list[QuantifierSpec] = Field(default_factory=list)
    dependency_ids: list[str] = Field(default_factory=list)
    status: Literal["open", "tentative", "closed", "refuted", "blocked"] = "open"
    priority: float = Field(default=0.5, ge=0.0, le=1.0)
    centrality: float = Field(default=0.0, ge=0.0, le=1.0)
    evidence_message_ids: list[str] = Field(default_factory=list)
    first_error_fingerprint: str | None = None
    content_hash: str = ""

    @model_validator(mode="after")
    def validate_obligation(self) -> "ProofObligation":
        payload = {
            "problem_hash": self.problem_hash,
            "normalized_statement": self.normalized_statement,
            "assumptions": self.assumptions,
            "quantifiers": [item.model_dump(mode="json") for item in self.quantifiers],
            "kind": self.kind.value,
        }
        expected = stable_hash(payload)
        if self.content_hash and self.content_hash != expected:
            raise ValueError("obligation content_hash mismatch")
        if self.status == "closed" and not self.evidence_message_ids:
            raise ValueError("closed obligation requires reusable evidence")
        object.__setattr__(self, "content_hash", expected)
        return self


class ProofGraphEdge(StrictModel):
    edge_id: str = Field(default_factory=lambda: new_id("pge"))
    source_id: str
    target_id: str
    edge_type: GraphEdgeType
    route_id: str | None = None
    evidence_message_id: str | None = None


class BrokerDecision(StrictModel):
    message_id: str
    accepted: bool
    rejection_reason: str | None = None
    selected_targets: list[str] = Field(default_factory=list)
    rejected_targets: dict[str, str] = Field(default_factory=dict)
    duplicate_of: str | None = None
    bridge_task_id: str | None = None
    contradiction_id: str | None = None
    score_breakdown: dict[str, float] = Field(default_factory=dict)


class BridgeTask(StrictModel):
    task_id: str = Field(default_factory=lambda: new_id("bridge"))
    obligation_ids: list[str]
    route_ids: list[str]
    normalized_goal: str
    allowed_fact_ids: list[str] = Field(default_factory=list)
    forbidden_negative_ids: list[str] = Field(default_factory=list)
    priority: float = Field(default=0.5, ge=0.0, le=1.0)

    @model_validator(mode="after")
    def validate_bridge_scope(self) -> "BridgeTask":
        if len(set(self.obligation_ids)) < 2 or len(set(self.route_ids)) < 2:
            raise ValueError(
                "a bridge task must connect at least two obligations/routes"
            )
        return self


class NoveltySignature(StrictModel):
    representation_tags: list[str] = Field(default_factory=list)
    mechanism_tags: list[str] = Field(default_factory=list)
    core_objects: list[str] = Field(default_factory=list)
    key_transformations: list[str] = Field(default_factory=list)
    proof_principles: list[str] = Field(default_factory=list)
    targeted_obligation_ids: list[str] = Field(default_factory=list)
    normalized_hash: str = ""

    def normalized_payload(self) -> dict[str, list[str]]:
        return {
            "representation_tags": sorted(set(self.representation_tags)),
            "mechanism_tags": sorted(set(self.mechanism_tags)),
            "core_objects": sorted(set(self.core_objects)),
            "key_transformations": sorted(set(self.key_transformations)),
            "proof_principles": sorted(set(self.proof_principles)),
            "targeted_obligation_ids": sorted(set(self.targeted_obligation_ids)),
        }

    @model_validator(mode="after")
    def set_normalized_hash(self) -> "NoveltySignature":
        expected = stable_hash(self.normalized_payload())
        if self.normalized_hash and self.normalized_hash != expected:
            raise ValueError("novelty signature hash mismatch")
        object.__setattr__(self, "normalized_hash", expected)
        return self


class InspirationTrigger(StrictModel):
    trigger_id: str = Field(default_factory=lambda: new_id("trigger"))
    trigger_type: InspirationTriggerType
    round_index: int = Field(ge=0)
    affected_route_ids: list[str]
    evidence_refs: list[str] = Field(default_factory=list)
    proof_debt_before: float = Field(default=0.0, ge=0.0)
    verified_gain_recent: int = Field(default=0, ge=0)
    repeated_error_fingerprints: list[str] = Field(default_factory=list)
    reason: str


class RepresentationCandidate(StrictModel):
    candidate_id: str = Field(default_factory=lambda: new_id("representation"))
    source_problem_hash: str
    representation_name: str
    rewritten_problem_view: str
    object_mapping: dict[str, str]
    preserved_invariants: list[str]
    lost_conditions: list[str] = Field(default_factory=list)
    new_candidate_tools: list[str] = Field(default_factory=list)
    expected_advantage: str
    failure_risks: list[str]
    fast_failure_tests: list[str] = Field(default_factory=list)
    novelty_signature: NoveltySignature

    @model_validator(mode="after")
    def require_auditable_mapping(self) -> "RepresentationCandidate":
        if not self.object_mapping:
            raise ValueError("representation candidate requires an object mapping")
        if not self.failure_risks:
            raise ValueError("representation candidate requires failure risks")
        return self


class AnalogyMapping(StrictModel):
    analogy_id: str = Field(default_factory=lambda: new_id("analogy"))
    source_record_id: str
    source_problem_summary: str
    target_problem_hash: str
    object_correspondence: dict[str, str]
    operation_correspondence: dict[str, str]
    transferable_lemmas: list[str]
    non_transferable_conditions: list[str]
    transfer_risks: list[str]
    required_bridge_lemmas: list[str] = Field(default_factory=list)
    novelty_signature: NoveltySignature

    @model_validator(mode="after")
    def require_transfer_limits(self) -> "AnalogyMapping":
        if not self.object_correspondence or not self.operation_correspondence:
            raise ValueError("analogy requires object and operation correspondence")
        if not self.non_transferable_conditions:
            raise ValueError("analogy must state non-transferable conditions")
        if not self.transfer_risks:
            raise ValueError("analogy must state transfer risks")
        return self


class ConstructionProposal(StrictModel):
    proposal_id: str = Field(default_factory=lambda: new_id("construction"))
    construction_type: str
    constructed_objects: list[str]
    definition: str = Field(min_length=1)
    intended_obligations: list[str]
    expected_invariant_or_relation: str
    expected_proof_debt_reduction: str = ""
    falsification_tests: list[str]
    failure_conditions: list[str] = Field(default_factory=list)
    novelty_signature: NoveltySignature

    @model_validator(mode="after")
    def require_testable_construction(self) -> "ConstructionProposal":
        if not self.constructed_objects:
            raise ValueError("construction requires at least one constructed object")
        if not self.intended_obligations:
            raise ValueError("construction must target an open obligation")
        if not self.falsification_tests:
            raise ValueError("construction requires a falsification test")
        return self


class InvariantHypothesis(StrictModel):
    hypothesis_id: str = Field(default_factory=lambda: new_id("invariant"))
    target_obligation_ids: list[str]
    state_definition: str = Field(min_length=1)
    allowed_operations: list[str]
    candidate_expression: str = Field(min_length=1)
    behavior: Literal["invariant", "nondecreasing", "nonincreasing"]
    boundary_case: str = Field(min_length=1)
    boundary_result: str = Field(min_length=1)
    falsification_request: str = Field(min_length=1)
    novelty_signature: NoveltySignature

    @model_validator(mode="after")
    def require_invariant_scope(self) -> "InvariantHypothesis":
        if not self.target_obligation_ids or not self.allowed_operations:
            raise ValueError("invariant hypothesis requires targets and operations")
        return self


class ReverseGoalPlan(StrictModel):
    plan_id: str = Field(default_factory=lambda: new_id("reverse_goal"))
    target_obligation_id: str
    goal: str
    sufficient_intermediate_claims: list[str]
    fact_supported_claims: list[str] = Field(default_factory=list)
    minimal_gaps: list[str]
    bridge_requests: list[str]
    novelty_signature: NoveltySignature

    @model_validator(mode="after")
    def require_reverse_gap(self) -> "ReverseGoalPlan":
        if not self.sufficient_intermediate_claims or not self.minimal_gaps:
            raise ValueError(
                "reverse goal analysis must expose a sufficient claim and gap"
            )
        return self


class MetaStrategyDecision(StrictModel):
    decision_id: str = Field(default_factory=lambda: new_id("meta"))
    round_index: int = Field(ge=0)
    action: Literal[
        "continue_current_mechanism",
        "local_repair",
        "rewrite_plan",
        "switch_representation",
        "search_analogy",
        "invent_auxiliary_construction",
        "surprise_exploration",
        "merge_route",
        "split_route",
        "cooldown_route",
        "abandon_route",
    ]
    affected_route_ids: list[str] = Field(default_factory=list)
    selected_mechanism: InspirationMechanism | None = None
    observable_metrics: dict[str, float | int | str | bool] = Field(
        default_factory=dict
    )
    reason: str
    estimated_calls: int = Field(default=0, ge=0)


class SurpriseBudgetState(StrictModel):
    total_calls: int = Field(default=0, ge=0)
    used_calls: int = Field(default=0, ge=0)
    finalization_reserve_calls: int = Field(default=0, ge=0)
    rejection_streak: int = Field(default=0, ge=0)
    cooldown_until_round: int | None = Field(default=None, ge=0)

    @property
    def remaining_calls(self) -> int:
        return max(0, self.total_calls - self.used_calls)


class InspirationProposal(StrictModel):
    proposal_id: str = Field(default_factory=lambda: new_id("inspiration"))
    trigger_id: str
    mechanism: InspirationMechanism
    source_agent_id: str
    target_route_ids: list[str]
    statement: str
    rationale_summary: str
    generated_obligations: list[str]
    representation: RepresentationCandidate | None = None
    analogy: AnalogyMapping | None = None
    construction: ConstructionProposal | None = None
    invariant: InvariantHypothesis | None = None
    reverse_goal: ReverseGoalPlan | None = None
    novelty_signature: NoveltySignature
    novelty_score: float = Field(ge=0.0, le=1.0)
    expected_information_gain: float = Field(ge=0.0, le=1.0)
    estimated_cost: int = Field(ge=0)
    evidence_type: EvidenceType = EvidenceType.UNVERIFIED_IDEA

    @model_validator(mode="after")
    def enforce_insight_only_proposal(self) -> "InspirationProposal":
        if self.evidence_type != EvidenceType.UNVERIFIED_IDEA:
            raise ValueError("inspiration proposals begin as unverified ideas")
        if not self.generated_obligations:
            raise ValueError("inspiration proposal must target or create an obligation")
        return self


class InspirationReview(StrictModel):
    proposal_id: str
    reviewer_agent_id: str
    semantically_distinct: bool
    relevant_to_open_obligation: bool
    internally_coherent: bool
    hidden_assumptions: list[str] = Field(default_factory=list)
    immediate_counterexamples: list[str] = Field(default_factory=list)
    recommendation: Literal[
        "reject",
        "store_insight",
        "attach_to_existing_route",
        "create_new_route",
        "request_computation",
        "request_bridge_verification",
    ]
    confidence: float = Field(ge=0.0, le=1.0)


class InspirationTask(StrictModel):
    task_id: str = Field(default_factory=lambda: new_id("inspiration_task"))
    trigger_id: str
    mechanism: InspirationMechanism
    target_route_ids: list[str] = Field(default_factory=list)
    target_obligation_ids: list[str] = Field(default_factory=list)
    reason: str
    max_proposals: int = Field(default=1, ge=1)


class InspirationMaterialization(StrictModel):
    proposal_id: str
    action: Literal[
        "shadow_only",
        "rejected",
        "stored_insight",
        "attached",
        "route_created",
        "computation_requested",
        "bridge_requested",
    ]
    route_id: str | None = None
    obligation_ids: list[str] = Field(default_factory=list)
    reason: str = ""


class ComputationHint(StrictModel):
    """A planner-authored possibility. It is deliberately not executable."""

    purpose: ComputationPurpose
    target_claim: str
    suggested_method: ComputationMethod
    decision_use: str
    broad_search: bool = False


class ExperimentSpec(StrictModel):
    """An auditable, decision-linked request for a bounded computation."""

    experiment_id: str = Field(default_factory=lambda: new_id("experiment"))
    purpose: ComputationPurpose = ComputationPurpose.FALSIFY_CLAIM
    target_claim: str = Field(min_length=1)
    assumptions: list[str] = Field(default_factory=list)
    reasoning_basis: str = Field(min_length=1)
    why_computation_is_needed: str = Field(min_length=1)
    decision_if_confirmed: str = Field(min_length=1)
    decision_if_refuted: str = Field(min_length=1)
    noncomputational_alternative: str = Field(min_length=1)
    method: ComputationMethod
    domains: dict[str, Any] = Field(default_factory=dict)
    arguments: dict[str, Any] = Field(default_factory=dict)
    exact_arithmetic: bool = True
    broad_search: bool = False
    typed_tool_gap: str | None = None
    max_cases: int = Field(default=100_000, ge=1, le=100_000_000)
    seed: int = 20260719
    requested_by: str | None = None
    path_id: str | None = None
    parent_checkpoint_id: str | None = None
    runtime_fingerprint: dict[str, Any] = Field(default_factory=dict)
    request_hash: str = ""

    def normalized_payload(self) -> dict[str, Any]:
        return {
            "purpose": self.purpose.value,
            "target_claim": self.target_claim,
            "assumptions": self.assumptions,
            "reasoning_basis": self.reasoning_basis,
            "why_computation_is_needed": self.why_computation_is_needed,
            "decision_if_confirmed": self.decision_if_confirmed,
            "decision_if_refuted": self.decision_if_refuted,
            "noncomputational_alternative": self.noncomputational_alternative,
            "method": self.method.value,
            "domains": self.domains,
            "arguments": self.arguments,
            "exact_arithmetic": self.exact_arithmetic,
            "broad_search": self.broad_search,
            "typed_tool_gap": self.typed_tool_gap,
            "max_cases": self.max_cases,
            "seed": self.seed,
            "runtime_fingerprint": self.runtime_fingerprint,
        }

    def bind_runtime_fingerprint(self, fingerprint: dict[str, Any]) -> None:
        """Bind cache-relevant runtime identity before gate/cache lookup."""
        object.__setattr__(self, "runtime_fingerprint", fingerprint)
        object.__setattr__(self, "request_hash", stable_hash(self.normalized_payload()))

    @model_validator(mode="after")
    def validate_and_hash(self) -> "ExperimentSpec":
        if (
            self.purpose == ComputationPurpose.DISCOVER_PATTERN
            and not self.broad_search
        ):
            raise ValueError("discover_pattern requests must set broad_search=true")
        if self.broad_search and self.purpose == ComputationPurpose.FALSIFY_CLAIM:
            raise ValueError(
                "falsify_claim is a targeted request; broad searches must use discover_pattern"
            )
        expected = stable_hash(self.normalized_payload())
        if self.request_hash and self.request_hash != expected:
            raise ValueError(
                "request_hash does not match the normalized experiment request"
            )
        object.__setattr__(self, "request_hash", expected)
        return self


class ExperimentProgram(StrictModel):
    experiment_id: str
    source: str
    input_schema: dict[str, Any] = Field(default_factory=dict)
    output_schema: dict[str, Any] = Field(default_factory=dict)
    dependencies: list[str] = Field(default_factory=list)
    code_hash: str = ""
    created_at: str = Field(default_factory=utc_now_iso)

    @model_validator(mode="after")
    def set_code_hash(self) -> "ExperimentProgram":
        expected = stable_hash(self.source)
        if self.code_hash and self.code_hash != expected:
            raise ValueError("code_hash does not match source")
        object.__setattr__(self, "code_hash", expected)
        return self


class ExperimentResult(StrictModel):
    experiment_id: str
    request_hash: str
    path_id: str | None = None
    parent_checkpoint_id: str | None = None
    target_claim: str
    method: ComputationMethod
    outcome: ExperimentOutcome
    evidence_strength: EvidenceStrength
    scope: dict[str, Any] = Field(default_factory=dict)
    counterexample: dict[str, Any] | None = None
    certificate: dict[str, Any] | None = None
    exact_arithmetic: bool = False
    cases_checked: int = Field(default=0, ge=0)
    runtime_seconds: float = Field(default=0.0, ge=0.0)
    tool_name: str
    tool_version: str
    program_hash: str | None = None
    cached: bool = False
    independently_verified: bool = False
    verification_notes: list[str] = Field(default_factory=list)
    error: str | None = None
    artifact_refs: list[EvidenceRef] = Field(default_factory=list)
    result_hash: str = ""
    created_at: str = Field(default_factory=utc_now_iso)

    @model_validator(mode="after")
    def enforce_evidence_semantics(self) -> "ExperimentResult":
        if self.outcome == ExperimentOutcome.COUNTEREXAMPLE_FOUND:
            if self.counterexample is None:
                raise ValueError(
                    "counterexample_found requires a counterexample payload"
                )
            if self.evidence_strength != EvidenceStrength.COUNTEREXAMPLE:
                raise ValueError(
                    "a counterexample must use counterexample evidence strength"
                )
            if not self.independently_verified:
                raise ValueError(
                    "counterexample_found requires independent deterministic verification"
                )
        if self.outcome == ExperimentOutcome.CERTIFIED:
            if self.certificate is None:
                raise ValueError("certified requires a certificate payload")
            if self.evidence_strength not in {
                EvidenceStrength.EXHAUSTIVE_CERTIFICATE,
                EvidenceStrength.FORMAL_CERTIFICATE,
            }:
                raise ValueError(
                    "certified requires exhaustive_certificate or formal_certificate evidence"
                )
        if (
            self.outcome == ExperimentOutcome.NOT_REFUTED
            and self.evidence_strength
            not in {
                EvidenceStrength.HEURISTIC,
                EvidenceStrength.BOUNDED_EVIDENCE,
            }
        ):
            raise ValueError("not_refuted can only be heuristic or bounded evidence")
        if self.outcome in {ExperimentOutcome.ERROR, ExperimentOutcome.INCONCLUSIVE}:
            if self.evidence_strength != EvidenceStrength.HEURISTIC:
                raise ValueError("failed or inconclusive computation is only heuristic")
        payload = {
            "request_hash": self.request_hash,
            "target_claim": self.target_claim,
            "method": self.method.value,
            "outcome": self.outcome.value,
            "evidence_strength": self.evidence_strength.value,
            "scope": self.scope,
            "counterexample": self.counterexample,
            "certificate": self.certificate,
            "exact_arithmetic": self.exact_arithmetic,
            "cases_checked": self.cases_checked,
            "tool_name": self.tool_name,
            "tool_version": self.tool_version,
            "program_hash": self.program_hash,
            "independently_verified": self.independently_verified,
            "verification_notes": self.verification_notes,
            "error": self.error,
        }
        expected = stable_hash(payload)
        if self.result_hash and self.result_hash != expected:
            raise ValueError("result_hash does not match experiment result")
        object.__setattr__(self, "result_hash", expected)
        return self


class ComputationPlan(StrictModel):
    """Cross-route description of an approved experiment, never executable code."""

    plan_id: str = Field(default_factory=lambda: new_id("compute_plan"))
    experiment_id: str
    request_hash: str
    target_claim: str
    assumptions: list[str] = Field(default_factory=list)
    method: ComputationMethod
    decision_use: str
    bounded_scope: dict[str, Any] = Field(default_factory=dict)
    exact_arithmetic: bool = True
    source_artifact_ref: str | None = None

    @classmethod
    def from_spec(cls, spec: ExperimentSpec) -> "ComputationPlan":
        return cls(
            experiment_id=spec.experiment_id,
            request_hash=spec.request_hash,
            target_claim=spec.target_claim,
            assumptions=spec.assumptions,
            method=spec.method,
            decision_use=spec.decision_if_refuted,
            bounded_scope=spec.domains,
            exact_arithmetic=spec.exact_arithmetic,
        )


class ComputationCertificate(StrictModel):
    certificate_id: str = Field(default_factory=lambda: new_id("compute_cert"))
    experiment_id: str
    request_hash: str
    result_hash: str
    target_claim: str
    outcome: ExperimentOutcome
    evidence_type: EvidenceType
    scope: dict[str, Any] = Field(default_factory=dict)
    independently_verified: bool = False
    artifact_refs: list[str] = Field(default_factory=list)

    @classmethod
    def from_result(cls, result: ExperimentResult) -> "ComputationCertificate":
        evidence_map = {
            EvidenceStrength.HEURISTIC: EvidenceType.NUMERICAL_HEURISTIC,
            EvidenceStrength.BOUNDED_EVIDENCE: EvidenceType.BOUNDED_EXPERIMENT,
            EvidenceStrength.COUNTEREXAMPLE: EvidenceType.COUNTEREXAMPLE,
            EvidenceStrength.EXHAUSTIVE_CERTIFICATE: EvidenceType.COMPLETE_FINITE_ENUMERATION,
            EvidenceStrength.FORMAL_CERTIFICATE: EvidenceType.FORMAL_KERNEL_CERTIFICATE,
        }
        return cls(
            experiment_id=result.experiment_id,
            request_hash=result.request_hash,
            result_hash=result.result_hash,
            target_claim=result.target_claim,
            outcome=result.outcome,
            evidence_type=evidence_map[result.evidence_strength],
            scope=result.scope,
            independently_verified=result.independently_verified,
            artifact_refs=[ref.artifact_ref for ref in result.artifact_refs],
        )


class FormalStatementPacket(StrictModel):
    packet_id: str = Field(default_factory=lambda: new_id("formal_statement"))
    problem_hash: str
    obligation_id: str
    statement: str
    assumptions: list[str] = Field(default_factory=list)
    quantifiers: list[QuantifierSpec] = Field(default_factory=list)
    target_language: str = "lean4"


class FormalCertificateRef(StrictModel):
    certificate_id: str = Field(default_factory=lambda: new_id("formal_cert"))
    packet_id: str
    backend: str
    status: Literal["verified", "failed", "pending", "unavailable"]
    artifact_ref: str | None = None
    statement_hash: str
    compiler_output_hash: str | None = None
    diagnostics: list[str] = Field(default_factory=list)


class ComputationDecision(StrictModel):
    experiment_id: str
    request_hash: str
    decision: ComputationDecisionStatus
    reason: str
    rule_id: str
    cache_hit: bool = False
    remaining_experiments: int = Field(default=0, ge=0)
    requires_meta_review: bool = False
    created_at: str = Field(default_factory=utc_now_iso)


class ToolRequest(StrictModel):
    request_id: str = Field(default_factory=lambda: new_id("toolreq"))
    kind: Literal[
        "sympy_simplify",
        "sympy_equivalent",
        "numeric_counterexample",
        "polynomial_factor",
        "modular_exhaustive",
        "bounded_integer_search",
        "graph_certificate",
        "recurrence_check",
        "bounded_greedy_sequence",
        "candidate_period_check",
        "exact_geometry",
        "lean_check",
    ]
    arguments: dict[str, Any] = Field(default_factory=dict)
    purpose: str


class ToolResult(StrictModel):
    request_id: str
    kind: str
    ok: bool
    result: Any = None
    error: str | None = None
    evidence_ref: EvidenceRef | None = None


class ProblemContract(StrictModel):
    problem_id: str = Field(default_factory=lambda: new_id("problem"))
    exact_statement: str
    normalized_statement: str
    problem_kind: ProblemKind = ProblemKind.UNKNOWN
    deliverables: list[str] = Field(default_factory=list)
    definitions: list[str] = Field(default_factory=list)
    hard_constraints: list[str] = Field(default_factory=list)
    allowed_tools: list[str] = Field(default_factory=list)
    output_language: str = "zh-CN"
    integrity_hash: str = ""
    created_at: str = Field(default_factory=utc_now_iso)

    @model_validator(mode="after")
    def set_hash(self) -> "ProblemContract":
        expected = stable_hash(self.exact_statement)
        if self.integrity_hash and self.integrity_hash != expected:
            raise ValueError("integrity_hash does not match exact_statement")
        object.__setattr__(self, "integrity_hash", expected)
        return self


class BlindReviewPacket(StrictModel):
    context_purpose: Literal["blind_review"] = "blind_review"
    problem: ProblemContract
    final_proof_text: str
    cited_fact_packets: list[dict[str, Any]] = Field(default_factory=list)
    negative_evidence_packets: list[dict[str, Any]] = Field(default_factory=list)
    forbidden_claims: list[str] = Field(default_factory=list)
    fact_context_complete: bool = True
    missing_cited_fact_refs: list[str] = Field(default_factory=list)
    fact_context_failure_reasons: list[str] = Field(default_factory=list)
    negative_context_complete: bool = True
    negative_context_truncated: bool = False
    negative_evidence_total_count: int = Field(default=0, ge=0)
    negative_evidence_omitted_count: int = Field(default=0, ge=0)
    negative_mandatory_omitted_count: int = Field(default=0, ge=0)
    negative_context_chars_used: int = Field(default=0, ge=0)
    negative_context_char_budget: int = Field(default=0, ge=0)


class TriageResult(StrictModel):
    problem_kind: ProblemKind
    difficulty: Difficulty
    key_risks: list[str] = Field(default_factory=list)
    likely_tools: list[str] = Field(default_factory=list)
    suggested_paths: int = Field(default=4, ge=1, le=16)
    suggested_rounds: int = Field(default=3, ge=1, le=16)
    proof_mode: Literal["direct", "decomposition", "hybrid"] = "hybrid"
    rationale: str
    confidence: float = Field(ge=0.0, le=1.0)


class StrategyCard(StrictModel):
    strategy_id: str = Field(default_factory=lambda: new_id("strategy"))
    title: str
    core_idea: str
    independence_basis: str
    expected_lemmas: list[str] = Field(default_factory=list)
    bottleneck: str
    prerequisites: list[str] = Field(default_factory=list)
    key_original_step: str | None = None
    falsification_test: str
    estimated_success: float = Field(ge=0.0, le=1.0)
    estimated_cost: float = Field(default=0.5, ge=0.0, le=1.0)
    tags: list[str] = Field(default_factory=list)
    computation_hints: list[ComputationHint] = Field(default_factory=list)
    assigned_agent_id: str | None = None
    inspiration_proposal_id: str | None = None
    parent_strategy_ids: list[str] = Field(default_factory=list)


class StrategySet(StrictModel):
    strategies: list[StrategyCard]
    coverage_notes: str
    omitted_directions: list[str] = Field(default_factory=list)

    @field_validator("strategies")
    @classmethod
    def require_strategy(cls, value: list[StrategyCard]) -> list[StrategyCard]:
        if not value:
            raise ValueError("at least one strategy is required")
        return value


class ProofStep(StrictModel):
    step_id: str
    statement: str
    justification: str
    dependencies: list[str] = Field(default_factory=list)
    calculations: list[str] = Field(default_factory=list)
    citations: list[CitationRecord] = Field(default_factory=list)
    is_key_step: bool = False
    confidence: float = Field(default=0.5, ge=0.0, le=1.0)


class ClaimCard(StrictModel):
    claim_id: str = Field(default_factory=lambda: new_id("claim"))
    statement: str
    assumptions: list[str] = Field(default_factory=list)
    conclusion: str
    proof_steps: list[ProofStep] = Field(default_factory=list)
    dependencies: list[str] = Field(default_factory=list)
    status: ClaimStatus = ClaimStatus.PROPOSED
    source_attempt_id: str | None = None
    source_agent_id: str | None = None
    evidence_refs: list[EvidenceRef] = Field(default_factory=list)
    scope_limitations: list[str] = Field(default_factory=list)
    counterexample_risk: str = "unknown"
    self_confidence: float = Field(default=0.5, ge=0.0, le=1.0)
    verification_confidence: float | None = Field(default=None, ge=0.0, le=1.0)
    tags: list[str] = Field(default_factory=list)
    content_hash: str = ""

    @model_validator(mode="after")
    def set_content_hash(self) -> "ClaimCard":
        payload = {
            "statement": self.statement,
            "assumptions": self.assumptions,
            "conclusion": self.conclusion,
            "dependencies": self.dependencies,
        }
        expected = stable_hash(payload)
        if self.content_hash and self.content_hash != expected:
            raise ValueError("claim content_hash mismatch")
        object.__setattr__(self, "content_hash", expected)
        return self


class ProofAttempt(StrictModel):
    attempt_id: str = Field(default_factory=lambda: new_id("attempt"))
    problem_hash: str
    strategy_id: str
    agent_id: str
    round_index: int = Field(ge=0)
    status: AttemptStatus
    final_answer: str | None = None
    proof_steps: list[ProofStep] = Field(default_factory=list)
    proposed_lemmas: list[ClaimCard] = Field(default_factory=list)
    dead_ends: list[str] = Field(default_factory=list)
    unresolved_gaps: list[str] = Field(default_factory=list)
    falsification_checks: list[str] = Field(default_factory=list)
    self_confidence: float = Field(default=0.5, ge=0.0, le=1.0)
    path_id: str | None = None
    latest_checkpoint_id: str | None = None
    checkpoint_ids: list[str] = Field(default_factory=list)
    resumed_from_checkpoint_id: str | None = None
    segment_count: int = Field(default=0, ge=0)
    failover_chain: list[str] = Field(default_factory=list)
    raw_artifact_ref: str | None = None
    usage: UsageRecord = Field(default_factory=UsageRecord)

    @model_validator(mode="after")
    def complete_requires_answer(self) -> "ProofAttempt":
        if self.status == AttemptStatus.COMPLETE and not self.final_answer:
            raise ValueError("complete attempt requires final_answer")
        return self


class ProofDelta(StrictModel):
    delta_id: str = Field(default_factory=lambda: new_id("delta"))
    problem_hash: str
    path_id: str
    strategy_id: str
    parent_checkpoint_id: str
    agent_id: str
    round_index: int = Field(ge=0)
    segment_index: int = Field(ge=1)
    # References to already committed steps are IDs. New mathematical content
    # belongs in new_steps as full ProofStep objects.
    referenced_checkpoint_step_ids: list[str] = Field(default_factory=list)
    completed_subgoal: str | None = None
    new_steps: list[ProofStep] = Field(default_factory=list)
    new_claims: list[ClaimCard] = Field(default_factory=list)
    active_assumptions: list[str] = Field(default_factory=list)
    remaining_subgoals: list[str] = Field(default_factory=list)
    current_goal: str | None = None
    known_risks: list[str] = Field(default_factory=list)
    detected_conflicts: list[str] = Field(default_factory=list)
    candidate_final_answer: str | None = None
    proof_complete: bool = False
    ready_for_verification: bool = True
    self_confidence: float = Field(default=0.5, ge=0.0, le=1.0)
    raw_artifact_ref: str | None = None
    usage: UsageRecord = Field(default_factory=UsageRecord)

    @model_validator(mode="after")
    def complete_delta_is_closed(self) -> "ProofDelta":
        if self.proof_complete and not self.candidate_final_answer:
            raise ValueError("proof_complete delta requires candidate_final_answer")
        if self.proof_complete and self.remaining_subgoals:
            raise ValueError("proof_complete delta cannot retain remaining_subgoals")
        if not self.new_steps and not self.detected_conflicts:
            raise ValueError(
                "delta must add at least one proof step or report a conflict"
            )
        return self


class InitialExplorationTurn(StrictModel):
    action: InitialExplorationAction
    attempt: ProofAttempt | None = None
    experiment_spec: ExperimentSpec | None = None
    experiment_impact: FailureLevel | None = None
    reason: str = ""

    @model_validator(mode="after")
    def require_action_payload(self) -> "InitialExplorationTurn":
        if self.experiment_impact == FailureLevel.NONE:
            raise ValueError(
                "experiment_impact must classify execution, plan, or strategy"
            )
        if self.action == InitialExplorationAction.SUBMIT_ATTEMPT:
            if self.attempt is None or self.experiment_spec is not None:
                raise ValueError("submit_attempt requires only an attempt")
        elif self.action == InitialExplorationAction.REQUEST_COMPUTATION:
            if self.experiment_spec is None or self.attempt is not None:
                raise ValueError("request_computation requires only an experiment_spec")
            if self.experiment_impact is not None:
                raise ValueError(
                    "request_computation cannot classify an experiment before it runs"
                )
        elif self.attempt is not None or self.experiment_spec is not None:
            raise ValueError("abandon cannot carry an attempt or experiment request")
        return self


class ContinuationTurn(StrictModel):
    action: ContinuationAction
    delta: ProofDelta | None = None
    experiment_spec: ExperimentSpec | None = None
    experiment_impact: FailureLevel | None = None
    message_receipts: list[MessageReceipt] = Field(default_factory=list)
    reason: str = ""

    @model_validator(mode="after")
    def require_action_payload(self) -> "ContinuationTurn":
        if self.experiment_impact == FailureLevel.NONE:
            raise ValueError(
                "experiment_impact must classify execution, plan, or strategy"
            )
        if self.action in {
            ContinuationAction.SUBMIT_DELTA,
            ContinuationAction.COMPLETE,
        }:
            if self.delta is None or self.experiment_spec is not None:
                raise ValueError("submit_delta/complete requires only a proof delta")
            if (
                self.action == ContinuationAction.COMPLETE
                and not self.delta.proof_complete
            ):
                raise ValueError("complete requires delta.proof_complete=true")
        elif self.action == ContinuationAction.REQUEST_COMPUTATION:
            if self.experiment_spec is None or self.delta is not None:
                raise ValueError("request_computation requires only an experiment_spec")
            if self.experiment_impact is not None:
                raise ValueError(
                    "request_computation cannot classify an experiment before it runs"
                )
        elif self.delta is not None or self.experiment_spec is not None:
            raise ValueError("abandon cannot carry a proof delta or experiment request")
        return self


class ProofCheckpoint(StrictModel):
    checkpoint_id: str = Field(default_factory=lambda: new_id("checkpoint"))
    parent_checkpoint_id: str | None = None
    problem_hash: str
    path_id: str
    strategy_id: str
    source_agent_id: str | None = None
    source_delta_id: str | None = None
    segment_index: int = Field(default=0, ge=0)
    verified_steps: list[ProofStep] = Field(default_factory=list)
    verified_claim_ids: list[str] = Field(default_factory=list)
    active_assumptions: list[str] = Field(default_factory=list)
    remaining_subgoals: list[str] = Field(default_factory=list)
    current_goal: str | None = None
    known_risks: list[str] = Field(default_factory=list)
    final_answer: str | None = None
    proof_complete: bool = False
    status: CheckpointStatus = CheckpointStatus.COMMITTED
    verification_report_ids: list[str] = Field(default_factory=list)
    failover_chain: list[str] = Field(default_factory=list)
    created_at: str = Field(default_factory=utc_now_iso)
    content_hash: str = ""

    @model_validator(mode="after")
    def validate_checkpoint(self) -> "ProofCheckpoint":
        if self.proof_complete and not self.final_answer:
            raise ValueError("proof_complete checkpoint requires final_answer")
        if self.proof_complete and self.remaining_subgoals:
            raise ValueError(
                "proof_complete checkpoint cannot retain remaining_subgoals"
            )
        payload = {
            "parent_checkpoint_id": self.parent_checkpoint_id,
            "problem_hash": self.problem_hash,
            "path_id": self.path_id,
            "strategy_id": self.strategy_id,
            "segment_index": self.segment_index,
            "verified_steps": [
                step.model_dump(mode="json") for step in self.verified_steps
            ],
            "verified_claim_ids": self.verified_claim_ids,
            "active_assumptions": self.active_assumptions,
            "remaining_subgoals": self.remaining_subgoals,
            "current_goal": self.current_goal,
            "known_risks": self.known_risks,
            "final_answer": self.final_answer,
            "proof_complete": self.proof_complete,
        }
        expected = stable_hash(payload)
        if self.content_hash and self.content_hash != expected:
            raise ValueError("checkpoint content_hash mismatch")
        object.__setattr__(self, "content_hash", expected)
        return self


class WorkingProofCheckpoint(StrictModel):
    """Route-local continuation state that is never globally admissible evidence."""

    working_checkpoint_id: str = Field(default_factory=lambda: new_id("working"))
    parent_verified_checkpoint_id: str
    problem_hash: str
    path_id: str
    strategy_id: str
    source_agent_id: str
    segment_index: int = Field(ge=1)
    delta: ProofDelta
    status: Literal["candidate", "uncertain", "rejected"] = "candidate"
    verification_report_ids: list[str] = Field(default_factory=list)
    feedback: list[str] = Field(default_factory=list)
    created_at: str = Field(default_factory=utc_now_iso)

    @model_validator(mode="after")
    def validate_working_scope(self) -> "WorkingProofCheckpoint":
        if self.delta.parent_checkpoint_id != self.parent_verified_checkpoint_id:
            raise ValueError("working checkpoint changed its verified parent")
        if (
            self.delta.problem_hash != self.problem_hash
            or self.delta.path_id != self.path_id
            or self.delta.strategy_id != self.strategy_id
            or self.delta.segment_index != self.segment_index
        ):
            raise ValueError("working checkpoint and delta identities do not match")
        return self


class PostFailureBottleneckDiagnostic(StrictModel):
    """Public-state diagnosis after a model call returns no usable artifact."""

    diagnostic_id: str = Field(default_factory=lambda: new_id("bottleneck"))
    problem_hash: str = ""
    path_id: str = ""
    route_id: str | None = None
    strategy_id: str = ""
    checkpoint_id: str = ""
    failure_type: Literal["reasoning_budget_exhausted", "reasoning_only_stall"] = (
        "reasoning_budget_exhausted"
    )
    failure_fingerprint: str = ""
    smallest_blocked_claim: str = Field(min_length=1)
    blocked_claim_source: Literal[
        "checkpoint_current_goal",
        "checkpoint_remaining_subgoal",
        "working_checkpoint_gap",
        "typed_public_context",
    ]
    attempted_mechanism: str = Field(min_length=1)
    why_blocked_from_public_state: str = Field(min_length=1)
    related_obligation_ids: list[str] = Field(default_factory=list)
    preserved_verified_step_ids: list[str] = Field(default_factory=list)
    preserved_fact_message_ids: list[str] = Field(default_factory=list)
    alternative_mechanism_tags: list[str] = Field(min_length=1)
    confidence: float = Field(default=0.5, ge=0.0, le=1.0)
    requires_inspiration: bool = True
    exact_failed_internal_step_known: Literal[False] = False
    private_reasoning_recovered: Literal[False] = False
    raw_artifact_ref: str | None = None
    usage: UsageRecord = Field(default_factory=UsageRecord)


class ClaimBatch(StrictModel):
    attempt_id: str
    claims: list[ClaimCard] = Field(default_factory=list)
    reusable_insights: list[str] = Field(default_factory=list)
    discarded_material: list[str] = Field(default_factory=list)
    summary: str


class VerificationIssue(StrictModel):
    issue_id: str = Field(default_factory=lambda: new_id("issue"))
    phase: str
    severity: Severity
    step_id: str | None = None
    claim_id: str | None = None
    description: str
    counterexample: str | None = None
    repair_hint: str | None = None


class BlindVerificationReport(StrictModel):
    """Identity-free verifier output expanded with provenance after the call."""

    problem_integrity_ok: bool = True
    verdict: VerificationVerdict
    first_error_step: str | None = None
    issues: list[VerificationIssue] = Field(default_factory=list)
    checked_dependencies: list[str] = Field(default_factory=list)
    tool_requests: list[ToolRequest] = Field(default_factory=list)
    tool_results: list[ToolResult] = Field(default_factory=list)
    failure_level: FailureLevel = FailureLevel.NONE
    confidence: float = Field(ge=0.0, le=1.0)
    concise_feedback: str

    @model_validator(mode="after")
    def failed_report_has_issue(self) -> "BlindVerificationReport":
        if self.verdict == VerificationVerdict.FAIL and not self.issues:
            raise ValueError("failed blind report must contain at least one issue")
        return self


class VerificationReport(StrictModel):
    report_id: str = Field(default_factory=lambda: new_id("verify"))
    target_id: str
    target_type: Literal["attempt", "claim", "proof_delta", "checkpoint", "final_proof"]
    agent_id: str
    stage: VerificationStage
    problem_integrity_ok: bool = True
    verdict: VerificationVerdict
    first_error_step: str | None = None
    issues: list[VerificationIssue] = Field(default_factory=list)
    checked_dependencies: list[str] = Field(default_factory=list)
    tool_requests: list[ToolRequest] = Field(default_factory=list)
    tool_results: list[ToolResult] = Field(default_factory=list)
    failure_level: FailureLevel = FailureLevel.NONE
    confidence: float = Field(ge=0.0, le=1.0)
    concise_feedback: str
    raw_artifact_ref: str | None = None
    usage: UsageRecord = Field(default_factory=UsageRecord)

    @model_validator(mode="after")
    def failed_report_has_issue(self) -> "VerificationReport":
        if self.verdict == VerificationVerdict.FAIL and not self.issues:
            raise ValueError(
                "failed verification report must contain at least one issue"
            )
        return self


class CandidateAssessment(StrictModel):
    target_id: str
    score: float = Field(ge=0.0, le=1.0)
    strengths: list[str] = Field(default_factory=list)
    weaknesses: list[str] = Field(default_factory=list)
    recommended_action: ActionKind


class MetaReview(StrictModel):
    selected_target_id: str | None = None
    assessments: list[CandidateAssessment] = Field(default_factory=list)
    shared_agreements: list[str] = Field(default_factory=list)
    unresolved_conflicts: list[str] = Field(default_factory=list)
    required_actions: list[str] = Field(default_factory=list)
    broad_computation_approved_strategy_ids: list[str] = Field(default_factory=list)
    failure_level: FailureLevel = FailureLevel.NONE
    can_synthesize: bool = False
    confidence: float = Field(ge=0.0, le=1.0)
    summary: str


class ContextPack(StrictModel):
    problem: ProblemContract
    strategy: StrategyCard | None = None
    verified_claims: list[ClaimCard] = Field(default_factory=list)
    uncertain_claims: list[ClaimCard] = Field(default_factory=list)
    targeted_feedback: list[str] = Field(default_factory=list)
    evidence_refs: list[EvidenceRef] = Field(default_factory=list)
    round_index: int = 0
    remaining_call_budget: int = 0
    notes: list[str] = Field(default_factory=list)
    proof_checkpoint: ProofCheckpoint | None = None


class PathStats(StrictModel):
    strategy_id: str
    attempt_id: str | None = None
    complete: bool = False
    progress: float = Field(default=0.0, ge=0.0, le=1.0)
    marginal_progress: float = Field(default=0.0, ge=-1.0, le=1.0)
    gap_reduction: float = Field(default=0.0, ge=-1.0, le=1.0)
    novelty: float = Field(default=0.5, ge=0.0, le=1.0)
    uncertainty: float = Field(default=1.0, ge=0.0, le=1.0)
    verification_score: float = Field(default=0.0, ge=0.0, le=1.0)
    latest_verdict: VerificationVerdict | None = None
    failure_level: FailureLevel = FailureLevel.NONE
    failure_confidence: float = Field(default=0.0, ge=0.0, le=1.0)
    consecutive_failures: int = Field(default=0, ge=0)
    failed_repair_attempts: int = Field(default=0, ge=0)
    unresolved_gap_count: int = Field(default=0, ge=0)
    stagnation_rounds: int = Field(default=0, ge=0)
    last_round_index: int = Field(default=0, ge=0)
    tokens_spent: int = Field(default=0, ge=0)
    structurally_valid: bool | None = None
    proof_debt: float = Field(default=0.0, ge=0.0)
    proof_debt_reduction: float = 0.0
    verified_fact_gain: int = Field(default=0, ge=0)
    shared_obligation_count: int = Field(default=0, ge=0)
    high_centrality_obligation_count: int = Field(default=0, ge=0)
    contradiction_count: int = Field(default=0, ge=0)
    counterexample_count: int = Field(default=0, ge=0)
    message_utility: float = Field(default=0.0, ge=0.0, le=1.0)
    route_redundancy: float = Field(default=0.0, ge=0.0, le=1.0)
    bridge_opportunity: float = Field(default=0.0, ge=0.0, le=1.0)
    negative_memory_hits: int = Field(default=0, ge=0)
    inspiration_trigger_count: int = Field(default=0, ge=0)
    novelty_score: float = Field(default=0.0, ge=0.0, le=1.0)
    representation_diversity: float = Field(default=0.0, ge=0.0, le=1.0)
    analogy_opportunity: float = Field(default=0.0, ge=0.0, le=1.0)
    construction_opportunity: float = Field(default=0.0, ge=0.0, le=1.0)
    surprise_budget_remaining: int = Field(default=0, ge=0)


class BudgetAction(StrictModel):
    action: ActionKind
    strategy_id: str | None = None
    target_id: str | None = None
    score: float
    reason: str
    rank: int | None = Field(default=None, ge=1)
    eligible: bool = True
    selected: bool = False
    forced: bool = False
    estimated_calls: int = Field(default=0, ge=0)
    planned_paths: int = Field(default=0, ge=0)
    blocked_reason: str | None = None


class BudgetDecision(StrictModel):
    actions: list[BudgetAction]
    candidates: list[BudgetAction] = Field(default_factory=list)
    global_uncertainty: float = Field(ge=0.0, le=1.0)
    coverage: float = Field(ge=0.0, le=1.0)
    failure_rate: float = Field(default=0.0, ge=0.0, le=1.0)
    all_evaluated_paths_failed: bool = False
    forced_widen: bool = False
    finish_reserve_calls: int = Field(default=0, ge=0)
    rationale: str


class FinalProof(StrictModel):
    problem_hash: str
    answer: str
    proof_steps: list[ProofStep]
    dependencies: list[str] = Field(default_factory=list)
    caveats: list[str] = Field(default_factory=list)
    source_attempt_ids: list[str] = Field(default_factory=list)
    confidence: float = Field(ge=0.0, le=1.0)


class AgentMetric(StrictModel):
    agent_id: str
    calls: int = Field(default=0, ge=0)
    usage: UsageRecord = Field(default_factory=UsageRecord)
    trust_score: float = Field(default=0.5, ge=0.0, le=1.0)
    failures: int = Field(default=0, ge=0)
    successful_responses: int = Field(default=0, ge=0)
    failed_attempts: int = Field(default=0, ge=0)
    failure_categories: dict[str, int] = Field(default_factory=dict)


class MathStatus(StrEnum):
    VERIFIED = "verified"
    INCONCLUSIVE = "inconclusive"
    REFUTED = "refuted"


class ExecutionStatus(StrEnum):
    COMPLETED = "completed"
    BUDGET_EXHAUSTED = "budget_exhausted"
    NETWORK_INTERRUPTED = "network_interrupted"
    FAILED = "failed"


class ResearchProgressReport(StrictModel):
    problem_hash: str
    valid_partial_attempt_ids: list[str] = Field(default_factory=list)
    strongest_partial_attempt_id: str | None = None
    verified_step_ids: list[str] = Field(default_factory=list)
    verified_local_claim_ids: list[str] = Field(default_factory=list)
    refuted_routes: list[dict[str, Any]] = Field(default_factory=list)
    negative_evidence: list[str] = Field(default_factory=list)
    open_obligations: list[dict[str, Any]] = Field(default_factory=list)
    remaining_gaps: list[str] = Field(default_factory=list)
    execution_notes: list[str] = Field(default_factory=list)
    summary: str
    created_at: str = Field(default_factory=utc_now_iso)


class RunStatus(StrEnum):
    VERIFIED = "verified"
    UNVERIFIED = "unverified"
    BUDGET_EXHAUSTED = "budget_exhausted"
    PAUSED_EXTERNAL_FAILURE = "paused_external_failure"
    FAILED = "failed"


class RunResult(StrictModel):
    run_id: str
    status: RunStatus
    math_status: MathStatus = MathStatus.INCONCLUSIVE
    execution_status: ExecutionStatus = ExecutionStatus.COMPLETED
    problem: ProblemContract
    final_proof: FinalProof | None = None
    final_verification: VerificationReport | None = None
    research_progress_report: ResearchProgressReport | None = None
    attempts: list[ProofAttempt] = Field(default_factory=list)
    claims: list[ClaimCard] = Field(default_factory=list)
    verification_reports: list[VerificationReport] = Field(default_factory=list)
    meta_reviews: list[MetaReview] = Field(default_factory=list)
    proof_checkpoints: list[ProofCheckpoint] = Field(default_factory=list)
    experiments: list[ExperimentResult] = Field(default_factory=list)
    resumed: bool = False
    resumed_from_checkpoint_id: str | None = None
    agent_metrics: list[AgentMetric] = Field(default_factory=list)
    total_calls: int = Field(default=0, ge=0)
    total_usage: UsageRecord = Field(default_factory=UsageRecord)
    run_directory: str
    summary: str
    created_at: str = Field(default_factory=utc_now_iso)
