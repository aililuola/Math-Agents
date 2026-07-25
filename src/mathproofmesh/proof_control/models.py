from __future__ import annotations

from enum import StrEnum
from typing import Literal

from pydantic import Field

from ..schemas import (
    ActionKind,
    ClaimStatus,
    FailureLevel,
    QuantifierSpec,
    StrictModel,
    VariableBinding,
    new_id,
)


class GoalRelation(StrEnum):
    EQUIVALENT = "equivalent"
    SUFFICIENT = "sufficient"
    NECESSARY_ONLY = "necessary_only"
    HEURISTIC_ONLY = "heuristic_only"
    UNRELATED = "unrelated"
    UNKNOWN = "unknown"


class ScopeRelation(StrEnum):
    SAME = "same"
    CLAIM_STRONGER = "claim_stronger"
    CLAIM_WEAKER = "claim_weaker"
    INCOMPARABLE = "incomparable"
    UNKNOWN = "unknown"


class ProofRole(StrEnum):
    CORE_BRIDGE = "core_bridge"
    AUXILIARY_BOUND = "auxiliary_bound"
    NECESSARY_CONDITION = "necessary_condition"
    SUFFICIENT_CONDITION = "sufficient_condition"
    EQUIVALENT_REDUCTION = "equivalent_reduction"
    TECHNICAL_LEMMA = "technical_lemma"
    SEARCH_HEURISTIC = "search_heuristic"
    COUNTEREXAMPLE = "counterexample"


class ClaimGoalLink(StrictModel):
    link_id: str = Field(default_factory=lambda: new_id("goal_link"))
    subject_id: str
    subject_kind: Literal["strategy", "claim", "message", "obligation", "proof_step"]
    target_obligation_id: str
    relation: GoalRelation
    scope_relation: ScopeRelation
    implication_outline: list[str] = Field(default_factory=list)
    remaining_obligation_ids_if_proved: list[str] = Field(default_factory=list)
    required_bridge_obligation_ids: list[str] = Field(default_factory=list)
    countermodel_status: Literal[
        "not_requested",
        "pending",
        "none_found_bounded",
        "found",
        "inapplicable",
    ] = "not_requested"
    minimality_score: float = Field(default=0.5, ge=0.0, le=1.0)
    alignment_confidence: float = Field(default=0.0, ge=0.0, le=1.0)
    assessment_source: Literal["deterministic", "model_review", "tool"] = (
        "deterministic"
    )
    evidence_refs: list[str] = Field(default_factory=list)


class MinimalBridgeProposal(StrictModel):
    proposal_id: str = Field(default_factory=lambda: new_id("minimal_bridge"))
    overstrong_subject_id: str
    target_obligation_id: str
    candidate_statement: str
    relation_to_original: Literal[
        "strictly_weaker", "equivalent", "incomparable", "unknown"
    ] = "unknown"
    implication_outline: list[str] = Field(default_factory=list)
    remaining_obligation_ids: list[str] = Field(default_factory=list)
    required_bridge_obligation_ids: list[str] = Field(default_factory=list)
    status: Literal["candidate", "reviewed", "accepted", "rejected"] = "candidate"


class IndexScope(StrEnum):
    ALL = "all"
    EVENTUAL = "eventual"
    FINITE_PREFIX = "finite_prefix"
    BOUNDED_RANGE = "bounded_range"
    SINGLE_INSTANCE = "single_instance"
    UNKNOWN = "unknown"


class UniformityScope(StrEnum):
    UNIFORM = "uniform"
    POINTWISE = "pointwise"
    EXISTS_PER_INSTANCE = "exists_per_instance"
    UNKNOWN = "unknown"


class ObjectScope(StrEnum):
    FULL_OBJECT = "full_object"
    PROJECTION = "projection"
    QUOTIENT = "quotient"
    RESIDUE_CLASSES = "residue_classes"
    SUBSTRUCTURE = "substructure"
    UNKNOWN = "unknown"


class ScopeSignature(StrictModel):
    subject_id: str
    index_scope: IndexScope = IndexScope.UNKNOWN
    uniformity: UniformityScope = UniformityScope.UNKNOWN
    object_scope: ObjectScope = ObjectScope.UNKNOWN
    quantifiers: list[QuantifierSpec] = Field(default_factory=list)
    variable_bindings: list[VariableBinding] = Field(default_factory=list)
    domain_constraints: list[str] = Field(default_factory=list)
    exceptional_cases: list[str] = Field(default_factory=list)
    normalization_confidence: float = Field(default=0.0, ge=0.0, le=1.0)


class InferenceRiskType(StrEnum):
    NECESSARY_TO_SUFFICIENT = "necessary_to_sufficient"
    EVENTUAL_TO_GLOBAL = "eventual_to_global"
    POINTWISE_TO_UNIFORM = "pointwise_to_uniform"
    FINITE_RANGE_TO_FINITE_STATE = "finite_range_to_finite_state"
    IMAGE_INCLUSION_TO_SURJECTIVITY = "image_inclusion_to_surjectivity"
    PROJECTION_TO_ORIGINAL = "projection_to_original"
    LOCAL_TO_GLOBAL = "local_to_global"
    EXISTENCE_TO_UNIFORM_EXISTENCE = "existence_to_uniform_existence"
    PAIRWISE_TO_COMMON_WITNESS = "pairwise_to_common_witness"
    EMPIRICAL_TO_UNIVERSAL = "empirical_to_universal"


class InferenceRiskRecord(StrictModel):
    risk_id: str = Field(default_factory=lambda: new_id("risk"))
    route_id: str | None = None
    subject_id: str
    premise_ids: list[str] = Field(default_factory=list)
    conclusion_id: str | None = None
    risk_type: InferenceRiskType
    deterministic_rule_id: str | None = None
    explanation: str
    status: Literal["open", "cleared", "refuted", "accepted_with_bridge"] = "open"
    confidence: float = Field(default=0.5, ge=0.0, le=1.0)
    countermodel_task_id: str | None = None
    required_bridge_obligation_ids: list[str] = Field(default_factory=list)


class AbstractStructureProposal(StrictModel):
    structure_id: str = Field(default_factory=lambda: new_id("structure"))
    route_id: str
    source_subject_id: str
    representation_kind: str
    components: list[str]
    proposed_reduction: str
    removable_components: list[str]
    preserved_constraints: list[str]
    target_obligation_ids: list[str]
    status: Literal[
        "candidate",
        "validated_structure",
        "refuted_structure",
        "exhausted",
    ] = "candidate"
    evidence_refs: list[str] = Field(default_factory=list)


class RealizerFailureType(StrEnum):
    ADMISSIBILITY = "admissibility"
    LOWER_BOUND = "lower_bound"
    UPPER_BOUND = "upper_bound"
    DEGENERACY = "degeneracy"
    SCOPE = "scope"
    STRICT_DESCENT = "strict_descent"
    UNKNOWN = "unknown"


class RealizerCandidate(StrictModel):
    candidate_id: str = Field(default_factory=lambda: new_id("realizer"))
    structure_id: str
    route_id: str
    construction: str
    admissibility_conditions: list[str]
    boundary_conditions: list[str]
    descent_measure: str
    expected_strict_decrease: str
    falsification_tests: list[str]
    status: Literal["candidate", "verified", "failed"] = "candidate"
    failure_type: RealizerFailureType | None = None
    failure_reason: str | None = None
    evidence_refs: list[str] = Field(default_factory=list)


class RealizerRepairTask(StrictModel):
    task_id: str = Field(default_factory=lambda: new_id("realizer_repair"))
    structure_id: str
    failed_candidate_id: str
    repair_operator: Literal[
        "replace_realizer_preserve_structure",
        "minimal_admissible_realizer",
        "alternative_representative",
        "repair_boundary_conditions",
    ]
    required_constraints: list[str]
    targeted_obligation_ids: list[str]


class InductionMeasureProposal(StrictModel):
    proposal_id: str = Field(default_factory=lambda: new_id("induction"))
    route_id: str
    target_obligation_ids: list[str]
    measure_name: str
    well_founded_domain: str
    base_cases: list[str]
    induction_step_relation: str
    strict_decrease_argument: str
    why_natural_index_is_insufficient: str
    trigger_features: list[str]
    status: Literal["candidate", "accepted", "rejected"] = "candidate"
    confidence: float = Field(default=0.5, ge=0.0, le=1.0)


class ProofFailureClass(StrEnum):
    EXECUTION = "execution"
    BRIDGE = "bridge"
    PLAN = "plan"
    FRAMING = "framing"


class FailureClassificationRecord(StrictModel):
    record_id: str = Field(default_factory=lambda: new_id("failure_class"))
    route_id: str
    target_id: str
    legacy_failure_level: FailureLevel
    control_failure_class: ProofFailureClass
    first_error_fingerprint: str | None = None
    evidence: list[str] = Field(default_factory=list)
    recommended_existing_action: ActionKind
    recommended_control_subaction: str
    confidence: float = Field(default=0.0, ge=0.0, le=1.0)


class BlueprintRewriteRequest(StrictModel):
    request_id: str = Field(default_factory=lambda: new_id("blueprint_rewrite"))
    route_id: str
    failure_record_id: str
    preserved_fact_ids: list[str]
    preserved_step_ids: list[str]
    invalidated_plan_elements: list[str]
    current_overstrong_targets: list[str]
    proposed_weaker_targets: list[str]
    proposed_bridge_obligation_ids: list[str]
    representation_change_required: bool
    status: Literal["pending", "accepted", "rejected", "executed"] = "pending"


class BottleneckCluster(StrictModel):
    cluster_id: str = Field(default_factory=lambda: new_id("bottleneck_cluster"))
    member_obligation_ids: list[str]
    canonical_obligation_id: str
    canonical_statement: str
    shared_assumptions: list[str]
    route_ids: list[str]
    centrality: float = Field(ge=0.0, le=1.0)
    proof_debt: float = Field(ge=0.0)
    minimal_bridge_candidate_ids: list[str] = Field(default_factory=list)
    status: Literal["active", "resolved", "split"] = "active"


class CriticalAssumption(StrictModel):
    assumption_id: str = Field(default_factory=lambda: new_id("assumption"))
    normalized_statement: str
    source_subject_ids: list[str]
    route_ids: list[str]
    verification_status: ClaimStatus
    necessity_by_route: dict[str, float]
    common_mode_risk: float = Field(ge=0.0, le=1.0)
    challenger_task_id: str | None = None


class MessageExpectedEffect(StrEnum):
    CLOSE = "close"
    REDUCE = "reduce"
    REFUTE = "refute"
    REWRITE = "rewrite"
    PROVIDE_CONSTRUCTION = "provide_construction"


class MessageUtilityContract(StrictModel):
    contract_id: str = Field(default_factory=lambda: new_id("utility_contract"))
    message_id: str
    source_route_id: str
    target_obligation_ids: list[str]
    expected_effect: MessageExpectedEffect
    required_assumptions: list[str]
    expected_core_debt_reduction: float = Field(default=0.0, ge=0.0)
    expires_round: int = Field(ge=0)


class MessageUsageReceipt(StrictModel):
    usage_receipt_id: str = Field(default_factory=lambda: new_id("usage_receipt"))
    message_id: str
    consumer_route_id: str
    referenced_step_ids: list[str] = Field(default_factory=list)
    closed_obligation_ids: list[str] = Field(default_factory=list)
    refuted_claim_ids: list[str] = Field(default_factory=list)
    produced_message_ids: list[str] = Field(default_factory=list)
    blueprint_rewrite_request_ids: list[str] = Field(default_factory=list)
    cited_by_final_proof: bool = False
    verified_use: bool = False
    utility_score: float = Field(default=0.0, ge=0.0)


class NearMissRecord(StrictModel):
    near_miss_id: str = Field(default_factory=lambda: new_id("near_miss"))
    route_id: str
    target_obligation_id: str | None = None
    source_target_id: str
    abstract_idea: str
    concrete_candidate: str
    preserved_properties: list[str]
    failed_constraints: list[str]
    first_failure_type: str
    salvageable_components: list[str]
    suggested_repair_operators: list[str]
    suggested_induction_measures: list[str]
    verifier_report_ids: list[str]
    verifier_confidence: float = Field(ge=0.0, le=1.0)


class GateVerdict(StrEnum):
    PASS = "pass"
    BLOCK = "block"
    REWRITE = "rewrite"
    SHADOW_BLOCK = "shadow_block"


class RouteAdmissionRecord(StrictModel):
    record_id: str = Field(default_factory=lambda: new_id("route_admission"))
    strategy_id: str
    verdict: GateVerdict
    alignment_score: float = Field(ge=0.0, le=1.0)
    target_obligation_ids: list[str]
    reasons: list[str]
    rewrite_request_id: str | None = None


class ContinueGateRecord(StrictModel):
    record_id: str = Field(default_factory=lambda: new_id("continue_gate"))
    route_id: str
    segment_index: int
    verdict: GateVerdict
    core_obligation_closed: bool
    core_debt_reduced: bool
    first_error_changed: bool
    verified_bridge_gain: bool
    consecutive_no_core_progress: int = Field(ge=0)
    reason: str


class SynthesisReadinessRecord(StrictModel):
    record_id: str = Field(default_factory=lambda: new_id("synthesis_ready"))
    verdict: GateVerdict
    open_core_obligation_ids: list[str]
    open_scope_risk_ids: list[str]
    unresolved_conflict_ids: list[str]
    invalid_goal_link_ids: list[str]
    unresolved_common_mode_assumption_ids: list[str]
    reasons: list[str]
