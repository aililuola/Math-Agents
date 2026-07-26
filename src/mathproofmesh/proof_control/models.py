from __future__ import annotations

from enum import StrEnum
from typing import Any, Literal

from pydantic import Field

from ..schemas import (
    ActionKind,
    ClaimStatus,
    ComputationPlan,
    ExperimentSpec,
    FailureLevel,
    NoveltySignature,
    QuantifierSpec,
    StrategyCard,
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


class AssumptionDomain(StrEnum):
    MATHEMATICAL = "mathematical"
    SEARCH = "search"
    PROTOCOL = "protocol"
    PROCESS = "process"
    TOOL = "tool"
    VERIFICATION = "verification"
    SAFETY = "safety"


class ObligationDomain(StrEnum):
    MATHEMATICAL = "mathematical"
    SEARCH = "search"
    PROCESS = "process"
    TOOL = "tool"
    VERIFICATION = "verification"
    PROTOCOL = "protocol"
    SAFETY = "safety"


class ControlActionType(StrEnum):
    CREATE_SUB_OBLIGATION = "create_sub_obligation"
    BIND_ROUTE_TARGET = "bind_route_target"
    REWRITE_BLUEPRINT = "rewrite_blueprint"
    WEAKEN_TARGET = "weaken_target"
    CREATE_MINIMAL_BRIDGE = "create_minimal_bridge"
    CREATE_COUNTERMODEL_TASK = "create_countermodel_task"
    ACTIVATE_INDUCTION_MEASURE = "activate_induction_measure"
    CREATE_ASSUMPTION_CHALLENGER = "create_assumption_challenger"
    MATERIALIZE_BOTTLENECK_CLUSTER = "materialize_bottleneck_cluster"
    MATERIALIZE_FALSIFICATION_TASK = "materialize_falsification_task"
    SCHEDULE_ROUTE_UPDATE = "schedule_route_update"
    DEFER_INSPIRATION_REVIEW = "defer_inspiration_review"
    REASSIGN_INSPIRATION_REVIEW = "reassign_inspiration_review"
    EXECUTE_META_PIVOT = "execute_meta_pivot"
    CLOSE_BY_DIRECT_PREMISE = "close_by_direct_premise"


class ControlActionStatus(StrEnum):
    PROPOSED = "proposed"
    ADMITTED = "admitted"
    EXECUTING = "executing"
    EXECUTED = "executed"
    DEFERRED = "deferred"
    REJECTED = "rejected"
    FAILED = "failed"


class MetaPivotStatus(StrEnum):
    NONE = "none"
    REQUESTED = "requested"
    ADMITTED = "admitted"
    EXECUTING = "executing"
    EXECUTED = "executed"
    EVALUATED = "evaluated"
    FAILED = "failed"


class TaskStatus(StrEnum):
    CREATED = "created"
    NEEDS_REWRITE = "needs_rewrite"
    ASSIGNED = "assigned"
    READY = "ready"
    RUNNING = "running"
    COMPLETED = "completed"
    INCONCLUSIVE = "inconclusive"
    DEFERRED = "deferred"
    BLOCKED = "blocked"
    FAILED = "failed"
    EXPIRED = "expired"


class WakeConditionKind(StrEnum):
    PROVIDER_AVAILABLE = "provider_available"
    BUDGET_AVAILABLE = "budget_available"
    DEPENDENCY_FACT_AVAILABLE = "dependency_fact_available"
    OBLIGATION_STATE_CHANGED = "obligation_state_changed"
    REVIEWER_AVAILABLE = "reviewer_available"
    TASK_RECOMPILED = "task_recompiled"
    USER_INTERVENTION = "user_intervention"
    CONFIG_CHANGED = "config_changed"


class WakeCondition(StrictModel):
    condition_id: str
    kind: WakeConditionKind
    target_id: str | None = None
    earliest_round: int | None = Field(default=None, ge=0)
    earliest_time: str | None = None
    satisfied: bool = False
    satisfied_at: str | None = None


class ExecutableTaskRecord(StrictModel):
    task_id: str
    task_kind: Literal[
        "countermodel",
        "falsification",
        "route_update",
        "inspiration_review",
        "meta_pivot_step",
    ]
    status: TaskStatus
    target_claim_ids: list[str] = Field(default_factory=list)
    target_obligation_ids: list[str] = Field(default_factory=list)
    route_ids: list[str] = Field(default_factory=list)
    assigned_agent_id: str | None = None
    registered_handler: str | None = None
    typed_contract_ref: str | None = None
    explicit_prompt_ref: str | None = None
    wake_conditions: list[WakeCondition] = Field(default_factory=list)
    created_round: int = Field(ge=0)
    last_transition_round: int = Field(ge=0)
    expires_round: int | None = Field(default=None, ge=0)
    result_refs: list[str] = Field(default_factory=list)
    terminal_reason: str | None = None
    verifies_target_claim: Literal[False] = False
    transition_history: list[dict[str, Any]] = Field(default_factory=list)


class BlueprintNodeKind(StrEnum):
    GIVEN = "given"
    CLAIM = "claim"
    LEMMA = "lemma"
    CONSTRUCTION = "construction"
    CASE_SPLIT = "case_split"
    COUNTERMODEL_TASK = "countermodel_task"
    COMPUTATION_TASK = "computation_task"
    TARGET = "target"


class BlueprintNode(StrictModel):
    node_id: str
    strategy_id: str
    kind: BlueprintNodeKind
    statement: str
    normalized_statement: str
    assumptions: list[str] = Field(default_factory=list)
    quantifiers: list[QuantifierSpec] = Field(default_factory=list)
    scope_signature_id: str | None = None
    source_field: Literal[
        "critical_claim",
        "expected_lemma",
        "bottleneck",
        "key_original_step",
        "generated_bridge",
        "main_goal",
        "given",
    ]
    executable_first_step: str | None = None
    semantic_quality_score: float = Field(ge=0.0, le=1.0)


class BlueprintEdge(StrictModel):
    edge_id: str
    source_node_id: str
    target_node_id: str
    relation: Literal[
        "implies",
        "depends_on",
        "equivalent_to",
        "closes",
        "requires_construction",
    ]
    implication_outline: list[str] = Field(default_factory=list)
    verified: bool = False


class StrategyBlueprint(StrictModel):
    blueprint_id: str
    strategy_id: str
    problem_hash: str
    node_ids: list[str]
    edge_ids: list[str]
    main_goal_node_id: str
    direct_target_node_ids: list[str]
    root_entry_node_ids: list[str]
    open_gap_node_ids: list[str]
    preserves_mechanism_signature: bool
    complete_path_to_main_goal: bool
    compilation_confidence: float = Field(ge=0.0, le=1.0)
    status: Literal[
        "draft",
        "compiled",
        "needs_review",
        "accepted",
        "rejected",
    ] = "draft"


class StrategyBlueprintCompilation(StrictModel):
    blueprint: StrategyBlueprint
    nodes: list[BlueprintNode]
    edges: list[BlueprintEdge]
    review_reasons: list[str] = Field(default_factory=list)


class BlueprintSemanticAssessment(StrictModel):
    blueprint_id: str
    accepted: bool
    reasons: list[str] = Field(default_factory=list)


class StrategyRevisionReason(StrEnum):
    ADMISSION_REWRITE = "admission_rewrite"
    PLAN_FAILURE = "plan_failure"
    SCOPE_REPAIR = "scope_repair"
    BRIDGE_INSERTION = "bridge_insertion"
    COUNTERMODEL_FEEDBACK = "countermodel_feedback"
    USER_INTERVENTION = "user_intervention"


class StrategyLineageRecord(StrictModel):
    strategy_id: str
    parent_strategy_id: str | None
    root_strategy_id: str
    revision_number: int = Field(ge=0)
    revision_reason: StrategyRevisionReason | None
    preserved_mechanism_tags: list[str]
    preserved_domain_objects: list[str]
    removed_claim_ids: list[str]
    added_claim_ids: list[str]
    superseded_by_strategy_id: str | None = None
    status: Literal[
        "original",
        "active",
        "needs_rewrite",
        "superseded",
        "rejected_with_evidence",
        "archived",
    ]


class RewriteSemanticVerdict(StrEnum):
    VALID = "valid"
    TAUTOLOGICAL = "tautological"
    PLACEHOLDER = "placeholder"
    NO_EXECUTABLE_STEP = "no_executable_step"
    NO_TARGET = "no_target"
    LOST_DOMAIN_MECHANISM = "lost_domain_mechanism"
    SCOPE_INVALID = "scope_invalid"
    DUPLICATE = "duplicate"


class RewriteSemanticAssessment(StrictModel):
    rewrite_request_id: str
    candidate_strategy_id: str | None
    candidate_bridge_node_ids: list[str]
    verdict: RewriteSemanticVerdict
    canonical_source_hash: str
    canonical_target_hash: str
    is_self_implication: bool
    has_nontrivial_graph_change: bool
    has_executable_first_step: bool
    domain_mechanism_preserved: bool
    reasons: list[str]


class RevisedStrategyResult(StrictModel):
    revised_strategy: StrategyCard
    revised_blueprint: StrategyBlueprint
    lineage: StrategyLineageRecord
    semantic_assessment: RewriteSemanticAssessment
    retained_claim_ids: list[str]
    removed_claim_ids: list[str]
    added_claim_ids: list[str]
    first_executable_obligation_id: str


class OriginalStrategyArchiveEntry(StrictModel):
    strategy: StrategyCard
    mechanism_signature: NoveltySignature
    domain_objects: list[str]
    critical_claims: list[str]
    expected_lemmas: list[str]
    first_seen_round: int = Field(ge=0)
    raw_artifact_ref: str


class StrategyCandidateAssessment(StrictModel):
    strategy_id: str
    selectable: bool
    semantic_rejection_reason: str | None = None


class DependencyKind(StrEnum):
    LOCAL_STEP = "local_step"
    LOCAL_CLAIM = "local_claim"
    GLOBAL_FACT = "global_fact"
    MESSAGE = "message"
    OBLIGATION = "obligation"
    TOOL_CERTIFICATE = "tool_certificate"
    FORMAL_CERTIFICATE = "formal_certificate"
    EXTERNAL_RESULT = "external_result"


class DependencyRef(StrictModel):
    kind: DependencyKind
    target_id: str
    source_attempt_id: str | None = None
    source_delta_id: str | None = None
    source_route_id: str | None = None
    content_hash: str | None = None


class DependencyNormalizationTask(StrictModel):
    task_id: str
    source_attempt_id: str | None = None
    source_delta_id: str | None = None
    ambiguous_ids: list[str]
    status: Literal["open", "resolved", "cancelled"] = "open"


class DependencyMigrationResult(StrictModel):
    dependency_refs: list[DependencyRef]
    migration_status: Literal["complete", "ambiguous"]
    normalization_task: DependencyNormalizationTask | None = None
    invalidates_claim: Literal[False] = False


class DependencyResolutionResult(StrictModel):
    resolved: bool
    resolved_refs: list[DependencyRef] = Field(default_factory=list)
    missing_refs: list[DependencyRef] = Field(default_factory=list)
    invalid_refs: list[DependencyRef] = Field(default_factory=list)
    ambiguous_refs: list[DependencyRef] = Field(default_factory=list)


class ClaimRefereeDisposition(StrEnum):
    ACCEPT = "accept"
    REJECT = "reject"
    DEFER = "defer"
    NEEDS_ADDITIONAL_REVIEW = "needs_additional_review"


class ClaimRefereeRecord(StrictModel):
    review_id: str
    referee_agent_id: str
    source_attempt_id: str
    source_delta_id: str | None
    claim_id: str
    disposition: ClaimRefereeDisposition
    dependencies_valid: bool
    scope_valid: bool
    quantifiers_valid: bool
    evidence_type_valid: bool
    reason: str


class ControlActionRecord(StrictModel):
    action_id: str = Field(default_factory=lambda: new_id("control_action"))
    action_type: ControlActionType
    source_record_ids: list[str] = Field(default_factory=list)
    route_ids: list[str] = Field(default_factory=list)
    target_obligation_ids: list[str] = Field(default_factory=list)
    payload: dict[str, Any] = Field(default_factory=dict)
    idempotency_key: str
    status: ControlActionStatus = ControlActionStatus.PROPOSED
    admission_reason: str = ""
    failure_reason: str = ""
    created_round: int = Field(default=0, ge=0)
    executed_round: int | None = Field(default=None, ge=0)
    result_refs: list[str] = Field(default_factory=list)


class ControlActionResult(StrictModel):
    result_refs: list[str] = Field(default_factory=list)
    postcondition_met: bool
    detail: str = ""


class AssumptionDomainRecord(StrictModel):
    assumption_key: str
    domain: AssumptionDomain
    inferred_from: str
    confidence: float = Field(ge=0.0, le=1.0)

    @property
    def eligible_for_mathematical_control(self) -> bool:
        return self.domain == AssumptionDomain.MATHEMATICAL


class ObligationDomainRecord(StrictModel):
    obligation_id: str
    domain: ObligationDomain
    inferred_from: str
    confidence: float = Field(ge=0.0, le=1.0)

    @property
    def eligible_for_mathematical_control(self) -> bool:
        return self.domain == ObligationDomain.MATHEMATICAL

    @property
    def eligible_for_route_target(self) -> bool:
        return self.domain == ObligationDomain.MATHEMATICAL


class ObligationSemanticQuality(StrictModel):
    obligation_id: str
    domain: ObligationDomain
    truth_apt: bool
    has_explicit_objects: bool
    has_explicit_relation: bool
    has_explicit_quantifiers_or_scope: bool
    is_placeholder: bool
    is_self_implication: bool
    duplicates_main_goal: bool
    has_executable_first_step: bool
    score: float = Field(ge=0.0, le=1.0)
    rejection_reasons: list[str] = Field(default_factory=list)
    accepted: bool = False
    semantic_quarantine: bool = False
    eligible_for_core_debt: bool = False
    eligible_for_bottleneck: bool = False


class AlignmentExceptionCode(StrEnum):
    DIRECT_PREMISE_CLOSURE = "direct_premise_closure"
    VERIFIED_GRAPH_EDGE = "verified_graph_edge"
    EXACT_EQUIVALENCE = "exact_equivalence"


class ClaimVerificationState(StrEnum):
    PROPOSED = "proposed"
    LOCALLY_VERIFIED = "locally_verified"
    INDEPENDENTLY_VERIFIED = "independently_verified"
    REFEREE_ACCEPTED = "referee_accepted"
    FACT_CANDIDATE = "fact_candidate"
    FACT = "fact"
    INVALIDATED = "invalidated"
    REJECTED = "rejected"


class ClaimVerificationLedgerEntry(StrictModel):
    claim_id: str
    source_attempt_id: str
    source_delta_id: str | None = None
    state: ClaimVerificationState = ClaimVerificationState.PROPOSED
    dependency_ids: list[str] = Field(default_factory=list)
    dependency_refs: list[DependencyRef] = Field(default_factory=list)
    local_report_ids: list[str] = Field(default_factory=list)
    independent_report_ids: list[str] = Field(default_factory=list)
    referee_review_ids: list[str] = Field(default_factory=list)
    referee_agent_ids: list[str] = Field(default_factory=list)
    source_attempt_incomplete: bool = False
    invalidation_reason: str | None = None
    invalidating_evidence_ids: list[str] = Field(default_factory=list)
    transition_history: list[dict[str, Any]] = Field(default_factory=list)


class PremiseClosureRecord(StrictModel):
    record_id: str = Field(default_factory=lambda: new_id("premise_closure"))
    target_obligation_id: str
    closure_type: Literal[
        "given_assumption",
        "direct_witness",
        "definition_unfolding",
        "verified_fact_instance",
        "none",
    ]
    supporting_ids: list[str] = Field(default_factory=list)
    verified: bool = False


class CountermodelTaskRecord(StrictModel):
    task_id: str = Field(default_factory=lambda: new_id("countermodel_task"))
    source_record_id: str
    source_goal_link_id: str | None = None
    target_obligation_id: str
    route_ids: list[str] = Field(default_factory=list)
    status: Literal[
        "pending",
        "assigned",
        "ready",
        "running",
        "deferred",
        "inapplicable",
        "completed",
        "inconclusive",
        "failed",
        "expired",
    ] = "pending"
    reason: str = ""
    result_refs: list[str] = Field(default_factory=list)
    assigned_agent_id: str | None = None
    executable_task_id: str | None = None


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
        "deferred",
    ] = "not_requested"
    minimality_score: float = Field(default=0.5, ge=0.0, le=1.0)
    alignment_confidence: float = Field(default=0.0, ge=0.0, le=1.0)
    assessment_source: Literal["deterministic", "model_review", "tool"] = (
        "deterministic"
    )
    evidence_refs: list[str] = Field(default_factory=list)


class RouteTargetBinding(StrictModel):
    binding_id: str = Field(default_factory=lambda: new_id("route_target"))
    strategy_id: str
    route_id: str | None = None
    direct_target_obligation_id: str
    ancestor_obligation_ids: list[str] = Field(default_factory=list)
    main_goal_obligation_id: str
    direct_claim_ids: list[str] = Field(default_factory=list)
    bridge_obligation_ids: list[str] = Field(default_factory=list)
    relation_to_direct_target: GoalRelation
    relation_to_main_goal: GoalRelation
    scope_relation_to_direct_target: ScopeRelation
    blueprint_path_complete: bool
    binding_confidence: float = Field(ge=0.0, le=1.0)


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
    action_id: str | None = None
    materialized_obligation_id: str | None = None


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
    PARTIAL_PROPERTY_TO_TOTAL_PROPERTY = "partial_property_to_total_property"
    NONEMPTY_INTERSECTION_TO_SUBSET_CONTAINMENT = (
        "nonempty_intersection_to_subset_containment"
    )
    EXISTS_COMPONENT_TO_ALL_COMPONENTS = "exists_component_to_all_components"
    SOME_WITNESS_TO_ALL_WITNESSES = "some_witness_to_all_witnesses"
    COVERAGE_TO_EXHAUSTIVENESS = "coverage_to_exhaustiveness"
    AT_LEAST_ONE_TO_ONLY_FROM_SET = "at_least_one_to_only_from_set"
    WRONG_DIRECTION = "wrong_direction"
    QUANTIFIER_SWAP = "quantifier_swap"
    DEPENDENCY_MISSING = "dependency_missing"
    SCOPE_MISMATCH = "scope_mismatch"
    AMBIGUOUS_SEMANTIC_LEAP = "ambiguous_semantic_leap"


class SetRelationKind(StrEnum):
    NONEMPTY_INTERSECTION = "nonempty_intersection"
    SUBSET = "subset"
    SUPERSET = "superset"
    EQUALITY = "equality"
    COVER = "cover"
    PARTITION = "partition"
    UNKNOWN = "unknown"


class PropertyStrength(StrEnum):
    EXISTENTIAL = "existential"
    PARTIAL = "partial"
    UNIVERSAL = "universal"
    EXHAUSTIVE = "exhaustive"


class RelationSignature(StrictModel):
    source_id: str | None = None
    set_relation: SetRelationKind = SetRelationKind.UNKNOWN
    property_strength: PropertyStrength = PropertyStrength.PARTIAL
    semantic_role: Literal[
        "property",
        "component",
        "witness",
        "coverage",
        "membership",
        "unknown",
    ] = "unknown"


class VerifierIssueCode(StrEnum):
    UNSUPPORTED_IMPLICATION = "unsupported_implication"
    WRONG_DIRECTION = "wrong_direction"
    FINITE_TO_UNIVERSAL = "finite_to_universal"
    EVENTUAL_TO_GLOBAL = "eventual_to_global"
    MISSING_UNIFORMITY = "missing_uniformity"
    QUANTIFIER_SWAP = "quantifier_swap"
    PROPERTY_STRENGTHENING = "property_strengthening"
    UNVERIFIED_CANDIDATE_BOUND = "unverified_candidate_bound"
    DEPENDENCY_MISSING = "dependency_missing"
    SCOPE_MISMATCH = "scope_mismatch"
    OTHER = "other"


class StructuredVerifierIssue(StrictModel):
    issue_id: str
    report_id: str
    target_id: str
    step_id: str | None = None
    code: VerifierIssueCode
    premise_summary: str
    conclusion_summary: str
    confidence: float = Field(ge=0.0, le=1.0)
    suggested_risk_type: InferenceRiskType | None = None


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
    premise_relation_signatures: list[RelationSignature] = Field(default_factory=list)
    conclusion_relation_signature: RelationSignature | None = None
    source_issue_ids: list[str] = Field(default_factory=list)
    recommended_control_action: str = "create_countermodel"

    @property
    def blocks_fact_promotion(self) -> bool:
        return self.status == "open"


class NegativePatternRecord(StrictModel):
    pattern_id: str = Field(default_factory=lambda: new_id("negative_pattern"))
    source_risk_id: str
    risk_type: InferenceRiskType
    description: str
    deterministic_signature: str
    evidence_ids: list[str] = Field(default_factory=list)


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


class AbstractRealizerExtraction(StrictModel):
    structure: AbstractStructureProposal
    realizer: RealizerCandidate


class RealizerRepairResult(StrictModel):
    task: RealizerRepairTask
    candidate: RealizerCandidate


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
    source_record_ids: list[str] = Field(default_factory=list)
    source_agent_id: str | None = None
    status: Literal["candidate", "accepted", "rejected"] = "candidate"
    reviewer_agent_id: str | None = None
    review_evidence_ids: list[str] = Field(default_factory=list)
    rejection_reason: str = ""
    activation_action_id: str | None = None
    blueprint_node_id: str | None = None
    confidence: float = Field(default=0.5, ge=0.0, le=1.0)


class InductionBlueprintNode(StrictModel):
    blueprint_node_id: str = Field(
        default_factory=lambda: new_id("induction_blueprint")
    )
    proposal_id: str
    route_id: str
    target_obligation_ids: list[str]
    measure_name: str
    well_founded_domain: str
    base_cases: list[str]
    induction_step_relation: str
    strict_decrease_argument: str
    prohibited_circularity: str
    reviewer_agent_id: str
    review_evidence_ids: list[str]
    status: Literal["active", "retired"] = "active"


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
    status: Literal[
        "pending",
        "accepted",
        "rejected",
        "executed",
        "deferred",
        "failed",
    ] = "pending"
    execution_action_id: str | None = None
    historical_target_obligation_ids: list[str] = Field(default_factory=list)
    result_obligation_ids: list[str] = Field(default_factory=list)
    failure_reason: str = ""


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
    alias_map: dict[str, str] = Field(default_factory=dict)
    member_statuses: dict[str, str] = Field(default_factory=dict)
    first_error_fingerprints: list[str] = Field(default_factory=list)
    bridge_task_id: str | None = None
    materialization_action_id: str | None = None
    status: Literal["active", "resolved", "split"] = "active"


class CriticalAssumption(StrictModel):
    assumption_id: str = Field(default_factory=lambda: new_id("assumption"))
    normalized_statement: str
    source_subject_ids: list[str]
    route_ids: list[str]
    verification_status: ClaimStatus
    necessity_by_route: dict[str, float]
    common_mode_risk: float = Field(ge=0.0, le=1.0)
    domain: AssumptionDomain = AssumptionDomain.MATHEMATICAL
    family_id: str | None = None
    semantic_tags: list[str] = Field(default_factory=list)
    challenger_task_id: str | None = None


class AssumptionFamily(StrictModel):
    family_id: str = Field(default_factory=lambda: new_id("assumption_family"))
    canonical_statement: str
    member_assumption_ids: list[str]
    route_ids: list[str]
    semantic_tags: list[str]
    common_mode_risk: float = Field(ge=0.0, le=1.0)
    normalization_confidence: float = Field(ge=0.0, le=1.0)
    challenger_task_id: str | None = None


class AssumptionChallengerTask(StrictModel):
    task_id: str = Field(default_factory=lambda: new_id("assumption_challenger"))
    family_id: str
    assumption_ids: list[str]
    target_statement: str
    route_ids: list[str]
    required_actions: list[str]
    premise_eligible: bool = False
    status: Literal["open", "resolved", "cancelled"] = "open"
    action_id: str | None = None


class BottleneckBridgeTask(StrictModel):
    task_id: str = Field(default_factory=lambda: new_id("bottleneck_bridge"))
    cluster_id: str
    target_obligation_id: str
    member_obligation_ids: list[str]
    route_ids: list[str]
    required_action: str
    status: Literal["open", "resolved", "cancelled"] = "open"


class FalsificationCompilationStatus(StrEnum):
    EXECUTABLE = "executable"
    NEEDS_REWRITE = "needs_rewrite"
    NON_AUTOMATABLE = "non_automatable"


class TypedFalsificationContract(StrictModel):
    contract_id: str
    target_subject_id: str
    parameters: list[dict[str, Any]] = Field(default_factory=list)
    finite_domains: dict[str, list[int] | dict[str, int]] = Field(default_factory=dict)
    exact_relation: dict[str, Any] = Field(default_factory=dict)
    counterexample_predicate: dict[str, Any] = Field(default_factory=dict)
    registered_handler: str | None = None
    max_cases: int = Field(default=0, ge=0)
    expected_if_found: str
    expected_if_not_found: str
    status: FalsificationCompilationStatus
    compile_reason: str


class FalsificationTaskRecord(StrictModel):
    task_id: str = Field(default_factory=lambda: new_id("falsification_task"))
    source_kind: Literal["strategy", "goal_link", "inference_risk"]
    source_record_id: str
    strategy_id: str | None = None
    route_id: str | None = None
    target_obligation_id: str | None = None
    target_claim_id: str | None = None
    request_text: str
    experiment_spec: ExperimentSpec | None = None
    computation_plan: ComputationPlan | None = None
    status: Literal[
        "proposed",
        "admitted",
        "deferred",
        "running",
        "counterexample_found",
        "not_refuted",
        "failed",
    ] = "proposed"
    deferred_reason: str = ""
    result_experiment_id: str | None = None
    action_id: str | None = None
    typed_contract_id: str | None = None
    executable_task_id: str | None = None


class MessageExpectedEffect(StrEnum):
    CLOSE = "close"
    REDUCE = "reduce"
    REFUTE = "refute"
    REWRITE = "rewrite"
    PROVIDE_CONSTRUCTION = "provide_construction"


class RouteUpdateTask(StrictModel):
    task_id: str = Field(default_factory=lambda: new_id("route_update"))
    target_route_id: str
    message_ids: list[str]
    priority: Literal["critical", "high"]
    scheduled_round: int = Field(ge=0)
    status: Literal[
        "scheduled",
        "presented",
        "acknowledged",
        "used",
        "expired_without_opportunity",
        "failed",
    ] = "scheduled"
    action_id: str
    receipt_ids: list[str] = Field(default_factory=list)
    failure_reason: str = ""


class InspirationReviewDeferral(StrictModel):
    deferral_id: str = Field(default_factory=lambda: new_id("inspiration_review"))
    proposal_id: str
    task_id: str | None = None
    reason: str
    review_status: Literal["deferred", "reassigned", "completed"] = "deferred"
    reviewed: bool = False
    assigned_reviewer_agent_id: str | None = None
    defer_action_id: str | None = None
    reassign_action_id: str | None = None


class ProcessFailureDiagnostic(StrictModel):
    diagnostic_id: str = Field(default_factory=lambda: new_id("process_failure"))
    source_report_id: str
    route_id: str
    target_obligation_id: str | None = None
    domain: Literal["process", "execution"]
    reason: str
    evidence: list[str] = Field(default_factory=list)


class MetaPivotState(StrictModel):
    pivot_id: str = Field(default_factory=lambda: new_id("meta_pivot"))
    status: MetaPivotStatus = MetaPivotStatus.NONE
    trigger_round: int = Field(ge=0)
    source_stagnation_signature: str
    requested_mechanisms: list[str]
    created_route_ids: list[str] = Field(default_factory=list)
    result_fact_ids: list[str] = Field(default_factory=list)
    result_obligation_ids: list[str] = Field(default_factory=list)
    revised_strategy_ids: list[str] = Field(default_factory=list)
    new_task_ids: list[str] = Field(default_factory=list)
    new_counterexample_ids: list[str] = Field(default_factory=list)
    changed_route_ids: list[str] = Field(default_factory=list)
    no_progress_after_pivot: bool | None = None
    failure_reason: str = ""
    action_id: str | None = None
    executed_round: int | None = Field(default=None, ge=0)
    evaluated_round: int | None = Field(default=None, ge=0)


class MetaPivotEffect(StrEnum):
    EFFECTIVE = "effective"
    EMPTY = "empty"
    DEFERRED = "deferred"
    FAILED = "failed"


class MetaPivotOutcome(StrictModel):
    pivot_id: str
    effect: MetaPivotEffect
    attempted_mechanisms: list[str] = Field(default_factory=list)
    completed_mechanisms: list[str] = Field(default_factory=list)
    unavailable_mechanisms: dict[str, str] = Field(default_factory=dict)
    new_route_ids: list[str] = Field(default_factory=list)
    revised_strategy_ids: list[str] = Field(default_factory=list)
    new_obligation_ids: list[str] = Field(default_factory=list)
    new_task_ids: list[str] = Field(default_factory=list)
    new_fact_ids: list[str] = Field(default_factory=list)
    new_counterexample_ids: list[str] = Field(default_factory=list)
    changed_route_ids: list[str] = Field(default_factory=list)
    wake_condition_ids: list[str] = Field(default_factory=list)
    reason: str


class BroadcastDecision(StrEnum):
    BROADCAST = "broadcast"
    KEEP_LOCAL = "keep_local"
    REJECT = "reject"


class BroadcastDecisionRecord(StrictModel):
    decision_id: str
    message_id: str
    decision: BroadcastDecision
    reason: str
    priority: str = "normal"
    expected_core_debt_reduction: float = Field(default=0.0, ge=0.0)
    target_obligation_ids: list[str] = Field(default_factory=list)
    consumes_neighbor_quota: bool = False


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
    repair_module: Literal[
        "realizer_repair",
        "induction_selector",
        "minimal_bridge",
        "scope_goal_rewrite",
        "bounded_local_repair",
    ] = "bounded_local_repair"
    authoritative: Literal[False] = False


class GateVerdict(StrEnum):
    PASS = "pass"
    BLOCK = "block"
    REWRITE = "rewrite"
    SHADOW_BLOCK = "shadow_block"


class GoalAlignmentContractResult(StrictModel):
    contract_id: str = Field(default_factory=lambda: new_id("alignment_contract"))
    subject_id: str
    passed_min_confidence: bool
    has_required_outline: bool
    unknown_relation_resolved: bool
    countermodel_action_id: str | None = None
    exception_code: AlignmentExceptionCode | None = None
    exception_evidence_ids: list[str] = Field(default_factory=list)
    final_verdict: GateVerdict
    reasons: list[str] = Field(default_factory=list)


class RouteAdmissionRecord(StrictModel):
    record_id: str = Field(default_factory=lambda: new_id("route_admission"))
    strategy_id: str
    verdict: GateVerdict
    alignment_score: float = Field(ge=0.0, le=1.0)
    target_obligation_ids: list[str]
    reasons: list[str]
    rewrite_request_id: str | None = None
    target_binding_id: str | None = None
    alignment_contract_id: str | None = None


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
