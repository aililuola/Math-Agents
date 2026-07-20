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


class RunStatus(StrEnum):
    VERIFIED = "verified"
    UNVERIFIED = "unverified"
    BUDGET_EXHAUSTED = "budget_exhausted"
    FAILED = "failed"


class RunResult(StrictModel):
    run_id: str
    status: RunStatus
    problem: ProblemContract
    final_proof: FinalProof | None = None
    final_verification: VerificationReport | None = None
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
