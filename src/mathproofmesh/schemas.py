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
        raw = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(raw).hexdigest()


def new_id(prefix: str) -> str:
    return f"{prefix}_{uuid4().hex[:12]}"


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", validate_assignment=True, str_strip_whitespace=True)


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


class UsageRecord(StrictModel):
    input_tokens: int = Field(default=0, ge=0)
    output_tokens: int = Field(default=0, ge=0)
    total_tokens: int = Field(default=0, ge=0)
    estimated_cost_usd: float = Field(default=0.0, ge=0.0)
    latency_ms: float = Field(default=0.0, ge=0.0)

    @model_validator(mode="after")
    def infer_total(self) -> "UsageRecord":
        if self.total_tokens == 0 and (self.input_tokens or self.output_tokens):
            object.__setattr__(self, "total_tokens", self.input_tokens + self.output_tokens)
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


class ToolRequest(StrictModel):
    request_id: str = Field(default_factory=lambda: new_id("toolreq"))
    kind: Literal[
        "sympy_simplify",
        "sympy_equivalent",
        "numeric_counterexample",
        "polynomial_factor",
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
    raw_artifact_ref: str | None = None
    usage: UsageRecord = Field(default_factory=UsageRecord)

    @model_validator(mode="after")
    def complete_requires_answer(self) -> "ProofAttempt":
        if self.status == AttemptStatus.COMPLETE and not self.final_answer:
            raise ValueError("complete attempt requires final_answer")
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
    target_type: Literal["attempt", "claim", "final_proof"]
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
            raise ValueError("failed verification report must contain at least one issue")
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


class PathStats(StrictModel):
    strategy_id: str
    attempt_id: str | None = None
    complete: bool = False
    progress: float = Field(default=0.0, ge=0.0, le=1.0)
    novelty: float = Field(default=0.5, ge=0.0, le=1.0)
    uncertainty: float = Field(default=1.0, ge=0.0, le=1.0)
    verification_score: float = Field(default=0.0, ge=0.0, le=1.0)
    unresolved_gap_count: int = Field(default=0, ge=0)
    stagnation_rounds: int = Field(default=0, ge=0)
    tokens_spent: int = Field(default=0, ge=0)
    structurally_valid: bool | None = None


class BudgetAction(StrictModel):
    action: ActionKind
    strategy_id: str | None = None
    target_id: str | None = None
    score: float
    reason: str


class BudgetDecision(StrictModel):
    actions: list[BudgetAction]
    global_uncertainty: float = Field(ge=0.0, le=1.0)
    coverage: float = Field(ge=0.0, le=1.0)
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
    agent_metrics: list[AgentMetric] = Field(default_factory=list)
    total_calls: int = Field(default=0, ge=0)
    total_usage: UsageRecord = Field(default_factory=UsageRecord)
    run_directory: str
    summary: str
    created_at: str = Field(default_factory=utc_now_iso)
