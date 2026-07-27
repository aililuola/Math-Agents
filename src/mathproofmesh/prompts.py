from __future__ import annotations

import json
import re
from collections.abc import Mapping
from dataclasses import asdict, dataclass, is_dataclass
from enum import Enum
from pathlib import Path
from typing import Any, Type

from pydantic import BaseModel

from .computation.contracts import experiment_tool_catalog
from .proof_control.models import (
    AbstractRealizerExtraction,
    AssumptionChallengeProposal,
    AssumptionChallengeReview,
    BlueprintRewriteRequest,
    BottleneckCluster,
    ClaimGoalLink,
    FailureClassificationRecord,
    InferenceRiskRecord,
    InductionMeasureProposal,
    MinimalBridgeProposal,
    NearMissRecord,
    RealizerRepairResult,
)
from .schemas import (
    AnalogyMapping,
    BlindReviewPacket,
    BlindVerificationReport,
    BrokerDecision,
    CandidateConjectureBatch,
    ClaimBatch,
    ComputationContractRepair,
    ConstructionProposal,
    ContinuationTurn,
    ExperimentProgram,
    ExperimentSpec,
    FinalProof,
    GoalNormalizationAssessment,
    InitialExplorationTurn,
    InspirationProposal,
    InspirationReview,
    InvariantHypothesis,
    LocalGoalPrecheck,
    MessageEnvelope,
    MessageReceipt,
    MetaReview,
    MetaStrategyDecision,
    PostFailureBottleneckDiagnostic,
    ProblemContract,
    ProofAttempt,
    ProofCheckpoint,
    ProofDelta,
    PropositionNormalizationBatch,
    RepresentationCandidate,
    ReverseGoalPlan,
    StrategySet,
    TriageResult,
    ToolAuditReport,
    VerificationReport,
)


@dataclass(frozen=True, slots=True)
class PromptBundle:
    stage: str
    system: str
    user: str
    response_model: Type[BaseModel]
    temperature: float | None = None
    max_output_tokens: int | None = None
    output_tier: int | None = None


BLIND_REVIEW_FORBIDDEN_TOKENS = (
    "agent_id",
    "route_id",
    "route_score",
    "self_confidence",
    "previous review",
    "vote",
)


def assert_blind_prompt_safe(bundle: PromptBundle) -> None:
    """Fail closed if identity or social-evaluation metadata leaks into blind audit."""

    if not bundle.stage.startswith("blind_"):
        return
    payload = f"{bundle.system}\n{bundle.user}".casefold()
    leaked = [token for token in BLIND_REVIEW_FORBIDDEN_TOKENS if token in payload]
    if leaked:
        raise ValueError(f"blind review prompt contains forbidden metadata: {leaked}")


def _to_jsonable(value: Any) -> Any:
    """Recursively normalize typed prompt context without losing its structure."""

    if isinstance(value, BaseModel):
        return _to_jsonable(value.model_dump(mode="json"))
    if isinstance(value, Enum):
        return _to_jsonable(value.value)
    if isinstance(value, Path):
        return str(value)
    if is_dataclass(value) and not isinstance(value, type):
        return _to_jsonable(asdict(value))
    if isinstance(value, Mapping):
        return {str(key): _to_jsonable(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_to_jsonable(item) for item in value]
    if isinstance(value, (set, frozenset)):
        converted = [_to_jsonable(item) for item in value]
        return sorted(
            converted,
            key=lambda item: json.dumps(
                item, ensure_ascii=False, sort_keys=True, default=str
            ),
        )
    return value


def _json(value: Any) -> str:
    return json.dumps(_to_jsonable(value), ensure_ascii=False, indent=2)


def _feedback_directives(
    items: list[str] | None,
    *,
    source: str,
    default_kind: str,
    default_status: str,
) -> list[dict[str, Any]]:
    """Mark review prose as non-authoritative before it enters a model prompt."""

    directives: list[dict[str, Any]] = []
    tag_pattern = re.compile(
        r"^\[(?P<kind>[^\]]+)\]"
        r"\[STATUS:(?P<status>[^\]]+)\]"
        r"\[SOURCE:(?P<source>[^\]]+)\]"
        r"\[PREMISE_ELIGIBLE:false\]\s*"
        r"(?P<text>.*)$",
        re.DOTALL,
    )
    for index, raw_text in enumerate(items or [], start=1):
        text = raw_text.strip()
        if not text:
            continue
        tagged = tag_pattern.fullmatch(text)
        directives.append(
            {
                "directive_index": index,
                "source": tagged.group("source") if tagged else source,
                "kind": tagged.group("kind") if tagged else default_kind,
                "status": tagged.group("status") if tagged else default_status,
                "premise_eligible": False,
                "allowed_use": [
                    "open_proof_obligation",
                    "repair_hint",
                    "falsification_target",
                ],
                "forbidden_use": [
                    "verified_premise",
                    "established_conclusion",
                ],
                "text": tagged.group("text").strip() if tagged else text,
            }
        )
    return directives


def _minimal_schema_value(
    node: dict[str, Any], root: dict[str, Any], *, required: bool = True
) -> Any:
    ref = node.get("$ref")
    if isinstance(ref, str) and ref.startswith("#/"):
        target: Any = root
        for part in ref[2:].split("/"):
            target = target[part.replace("~1", "/").replace("~0", "~")]
        return _minimal_schema_value(target, root, required=required)
    if "const" in node:
        return node["const"]
    choices = node.get("enum")
    if isinstance(choices, list) and choices:
        return choices[0]
    for union_key in ("anyOf", "oneOf"):
        union = node.get(union_key)
        if isinstance(union, list):
            choice = next(
                (item for item in union if item.get("type") != "null"), union[0]
            )
            return _minimal_schema_value(choice, root, required=required)
    value_type = node.get("type")
    if value_type == "object" or "properties" in node:
        properties = dict(node.get("properties", {}))
        required_keys = list(node.get("required", []))
        return {
            key: _minimal_schema_value(properties[key], root, required=True)
            for key in required_keys
            if key in properties
        }
    if value_type == "array":
        count = max(0, int(node.get("minItems", 0) or 0))
        if required and count == 0:
            return []
        item = _minimal_schema_value(dict(node.get("items", {})), root)
        return [item for _ in range(count)]
    if value_type == "integer":
        return int(node.get("minimum", 0) or 0)
    if value_type == "number":
        return float(node.get("minimum", 0.0) or 0.0)
    if value_type == "boolean":
        return False
    if "default" in node:
        return node["default"]
    return "value" if int(node.get("minLength", 0) or 0) else ""


def _validated_model_example(
    model: Type[BaseModel], schema: dict[str, Any]
) -> dict[str, Any]:
    """Return a minimal example that also satisfies model-level validators."""

    step = {
        "step_id": "new-step-1",
        "statement": "State one auditable mathematical consequence.",
        "justification": "Derive it from the explicitly listed dependencies.",
        "dependencies": [],
    }
    delta = {
        "problem_hash": "problem-hash-from-context",
        "path_id": "path-from-context",
        "strategy_id": "strategy-from-context",
        "parent_checkpoint_id": "checkpoint-from-context",
        "agent_id": "agent-from-context",
        "round_index": 0,
        "segment_index": 1,
        "referenced_checkpoint_step_ids": [],
        "new_steps": [step],
        "new_claims": [
            {
                "claim_id": "new-claim-1",
                "statement": "The new step establishes one reusable scoped claim.",
                "conclusion": "State exactly the consequence established by new-step-1.",
                "proof_steps": [step],
                "dependencies": ["new-step-1"],
            }
        ],
        "remaining_subgoals": ["State the next unresolved subgoal."],
        "current_goal": "State the next unresolved subgoal.",
    }
    attempt = {
        "problem_hash": "problem-hash-from-context",
        "strategy_id": "strategy-from-context",
        "agent_id": "agent-from-context",
        "round_index": 0,
        "status": "partial",
        "proof_steps": [step],
        "unresolved_gaps": ["State the next unresolved subgoal."],
    }
    novelty = {
        "representation_tags": ["target representation"],
        "mechanism_tags": ["candidate mechanism"],
        "core_objects": ["main object"],
        "key_transformations": ["proposed transformation"],
        "proof_principles": ["candidate principle"],
        "targeted_obligation_ids": ["obligation-from-context"],
        "normalized_hash": "",
    }
    examples: dict[str, dict[str, Any]] = {
        "StrategySet": {
            "strategies": [
                {
                    "strategy_id": "strategy-candidate-1",
                    "title": "One structurally distinct route",
                    "core_idea": "Describe the mechanism rather than renamed notation.",
                    "independence_basis": "Explain why this mechanism is distinct.",
                    "expected_lemmas": ["One necessary intermediate lemma"],
                    "bottleneck": "The decisive unresolved implication",
                    "falsification_test": "Give one precise fast failure test.",
                    "critical_claims": [
                        {
                            "statement": "The smallest claim on which this mechanism depends.",
                            "necessity": "required",
                            "falsification_test": "Give one exact, low-cost attempt to refute it.",
                            "status": "needs_check",
                        }
                    ],
                    "estimated_success": 0.5,
                }
            ],
            "coverage_notes": "Explain which mathematical mechanisms are covered.",
        },
        "ProofAttempt": attempt,
        "ProofDelta": delta,
        "InitialExplorationTurn": {
            "action": "submit_attempt",
            "attempt": attempt,
            "reason": "A nonempty auditable partial route is ready for review.",
        },
        "ContinuationTurn": {
            "action": "submit_delta",
            "delta": delta,
            "message_receipts": [],
            "reason": "One bounded mathematical delta is ready for review.",
        },
        "ComputationContractRepair": {
            "action": "abandon_as_unrepresentable",
            "repaired_spec": None,
            "reason": (
                "No registered or sandboxed bounded computation can preserve "
                "the requested semantics."
            ),
            "semantic_equivalence": None,
        },
        "CandidateConjectureBatch": {
            "candidate_conjectures": [
                {
                    "statement": "State one concrete falsifiable pattern.",
                    "rationale": (
                        "Explain how the exact bounded result suggests this pattern."
                    ),
                    "supporting_experiment_ids": ["experiment-from-context"],
                    "evidence_refs": [],
                    "scope_limitations": [
                        "The bounded result is evidence only and is not a proof."
                    ],
                    "proof_obligations": [
                        "Prove the candidate pattern symbolically for the full domain."
                    ],
                    "confidence": 0.5,
                    "status": "candidate",
                }
            ]
        },
        "ExperimentProgram": {
            "experiment_id": "experiment-from-context",
            "source": (
                "def run(data):\n"
                "    return {'outcome': 'not_refuted', 'cases_checked': 0, "
                "'scope': {}, 'exact_arithmetic': True}\n"
            ),
            "input_schema": {"type": "object"},
            "output_schema": {"type": "object"},
            "dependencies": [],
            "code_hash": "",
        },
        "VerificationReport": {
            "target_id": "target-from-context",
            "target_type": "attempt",
            "agent_id": "agent-from-context",
            "stage": "structural",
            "problem_integrity_ok": True,
            "verdict": "uncertain",
            "issues": [],
            "checked_dependencies": [],
            "failure_level": "none",
            "confidence": 0.5,
            "concise_feedback": "State exactly what remains unchecked.",
        },
        "BlindVerificationReport": {
            "problem_integrity_ok": True,
            "verdict": "uncertain",
            "issues": [],
            "checked_dependencies": [],
            "failure_level": "none",
            "confidence": 0.5,
            "concise_feedback": "State exactly what remains unchecked.",
        },
        "MetaReview": {
            "selected_target_id": None,
            "assessments": [],
            "shared_agreements": [],
            "unresolved_conflicts": [],
            "required_actions": ["Obtain a reviewed nonempty proof delta."],
            "failure_level": "none",
            "can_synthesize": False,
            "confidence": 0.5,
            "summary": "No route currently meets the synthesis gate.",
        },
        "FinalProof": {
            "problem_hash": "problem-hash-from-context",
            "answer": "State the exact conclusion proved by the listed steps.",
            "proof_steps": [step],
            "dependencies": [],
            "caveats": [],
            "source_attempt_ids": ["reviewed-attempt-from-context"],
            "confidence": 0.5,
        },
        "RepresentationCandidate": {
            "candidate_id": "representation-candidate-1",
            "source_problem_hash": "problem-hash-from-context",
            "representation_name": "Equivalent structural representation",
            "rewritten_problem_view": "Rewrite the target without changing its scope.",
            "object_mapping": {"original object": "represented object"},
            "preserved_invariants": ["One condition preserved in both directions"],
            "expected_advantage": "Expose the current proof obstruction.",
            "failure_risks": ["The reverse implication may fail."],
            "fast_failure_tests": ["Check both directions on a boundary case."],
            "novelty_signature": novelty,
        },
        "AnalogyMapping": {
            "analogy_id": "analogy-candidate-1",
            "source_record_id": "verified-local-record-1",
            "source_problem_summary": "A verified local problem with the same mechanism.",
            "target_problem_hash": "problem-hash-from-context",
            "object_correspondence": {"source object": "target object"},
            "operation_correspondence": {"source operation": "target operation"},
            "transferable_lemmas": ["A structurally transferable lemma"],
            "non_transferable_conditions": ["A source-only hypothesis"],
            "transfer_risks": ["The target may lack the source closure property."],
            "required_bridge_lemmas": ["Prove the target closure property."],
            "novelty_signature": novelty,
        },
        "ConstructionProposal": {
            "proposal_id": "construction-candidate-1",
            "construction_type": "auxiliary object",
            "constructed_objects": ["auxiliary object A"],
            "definition": "Define A exactly from the original hypotheses.",
            "intended_obligations": ["obligation-from-context"],
            "expected_invariant_or_relation": "A satisfies the needed relation.",
            "falsification_tests": ["Check whether A exists in the boundary case."],
            "failure_conditions": ["A is not well-defined."],
            "novelty_signature": novelty,
        },
        "InvariantHypothesis": {
            "hypothesis_id": "invariant-candidate-1",
            "target_obligation_ids": ["obligation-from-context"],
            "state_definition": "Define the state and its domain exactly.",
            "allowed_operations": ["one allowed transition"],
            "candidate_expression": "I(state)",
            "behavior": "invariant",
            "boundary_case": "the initial state",
            "boundary_result": "I(initial state) has the required value",
            "falsification_request": "Check one transition that could change I.",
            "novelty_signature": novelty,
        },
        "ReverseGoalPlan": {
            "plan_id": "reverse-goal-candidate-1",
            "target_obligation_id": "obligation-from-context",
            "goal": "The exact current obligation",
            "sufficient_intermediate_claims": ["One sufficient intermediate claim"],
            "fact_supported_claims": [],
            "minimal_gaps": ["The smallest unsupported implication"],
            "bridge_requests": ["Prove the unsupported implication."],
            "novelty_signature": novelty,
        },
        "InspirationProposal": {
            "proposal_id": "inspiration-candidate-1",
            "trigger_id": "trigger-from-context",
            "mechanism": "surprise_exploration",
            "source_agent_id": "agent-from-context",
            "target_route_ids": [],
            "statement": "A precise, falsifiable new mechanism.",
            "rationale_summary": "Explain its structural relation to the open gap.",
            "generated_obligations": ["Verify the new mechanism's key implication."],
            "novelty_signature": novelty,
            "novelty_score": 0.8,
            "expected_information_gain": 0.7,
            "estimated_cost": 1,
        },
        "MessageReceipt": {
            "message_id": "message-from-context",
            "target_route_id": "route-from-context",
            "receipt_token": "copy-the-opaque-token-from-context",
            "status": "accepted",
            "used": False,
            "parsed_assumptions": [],
            "parsed_conclusion": "State the parsed conclusion.",
            "referenced_in_step_ids": [],
            "claimed_closed_obligation_ids": [],
            "delivered_round": 0,
            "reason": "Parsed successfully but not yet used in a verified step.",
        },
        "MessageEnvelope": {
            "problem_hash": "problem-hash-from-context",
            "source_agent_id": "agent-from-context",
            "source_route_id": "route-from-context",
            "source_role": "prover",
            "target_route_ids": [],
            "message_type": "claim_proposal",
            "statement": "A route-local claim awaiting independent review.",
            "normalized_statement": "a route-local claim awaiting independent review",
            "assumptions": [],
            "conclusion": "The precise candidate conclusion.",
            "dependencies": [],
            "scope_limitations": ["Not globally admissible until reviewed."],
            "evidence_type": "unverified_idea",
            "memory_tier": "insight",
            "verification_status": "proposed",
            "verification_confidence": 0.0,
            "normalization_confidence": 0.0,
            "round_created": 0,
            "ttl_rounds": 2,
            "content_hash": "",
        },
        "BrokerDecision": {
            "message_id": "message-from-context",
            "accepted": False,
            "rejection_reason": "State the exact failed gate.",
            "selected_targets": [],
            "rejected_targets": {},
            "score_breakdown": {},
        },
        "ToolAuditReport": {
            "agent_id": "auditor-from-context",
            "route_id": "route-from-context",
            "experiment_ids": [],
            "replay_artifact_refs": [],
            "mathematical_mapping_checked": False,
            "all_results_replayed_independently": False,
            "issues": ["No replay evidence was supplied."],
            "verdict": "inconclusive",
            "confidence": 0.0,
        },
        "GoalNormalizationAssessment": {
            "has_ambiguity": True,
            "is_well_formed": False,
            "ambiguity_reasons": ["A required mathematical parameter is missing."],
            "recommended_statement": "A complete candidate interpretation.",
            "recommendation_confidence": 0.5,
            "alternative_interpretations": [],
            "changes_mathematical_meaning": True,
            "clarification_question": "Which complete interpretation is intended?",
        },
    }
    example = examples.get(model.__name__)
    if example is None:
        candidate = _minimal_schema_value(schema, schema)
        if not isinstance(candidate, dict):
            raise TypeError(f"{model.__name__} requires a JSON object example")
        example = candidate
    # Fail immediately if a future schema change invalidates an advertised shape.
    model.model_validate(example)
    return example


def _strip_schema_noise(value: Any) -> Any:
    """Drop auto-generated `title` entries from a JSON schema.

    Pydantic stamps a title on every model and field; they carry no
    instruction the field name does not, and across the large nested
    response models they cost thousands of prompt tokens per call.
    Descriptions and defaults are kept — they are real instructions.
    """
    if isinstance(value, dict):
        return {
            key: _strip_schema_noise(item)
            for key, item in value.items()
            if key != "title"
        }
    if isinstance(value, list):
        return [_strip_schema_noise(item) for item in value]
    return value


def _schema(model: Type[BaseModel]) -> str:
    schema = model.model_json_schema()
    example = _validated_model_example(model, schema)
    return (
        f"{json.dumps(_strip_schema_noise(schema), ensure_ascii=False, indent=2)}\n\n"
        "MINIMAL JSON SHAPE EXAMPLE (replace placeholders; leave all hash fields "
        "empty because the server computes them):\n"
        f"{json.dumps(example, ensure_ascii=False, indent=2)}"
    )


COMMON_SYSTEM = """
You are one component in a verification-first mathematical reasoning system.
The original problem statement is immutable: never change a quantifier, hypothesis, domain, requested conclusion, or definition.
Reason privately, but output only explicit, auditable mathematical claims and proof steps. Do not output hidden scratch work.
A field ending in `_ids` contains string references only. A ProofStep field contains complete objects only; never put a step ID where a ProofStep object is required.
Leave cryptographic hash fields empty. The server computes hashes from canonical structured objects and ignores model-supplied hashes.
A confidence value is metadata, not evidence. Never treat another agent's confidence as proof.
Never invent a theorem or bibliographic citation. A standard named theorem may be used without a bibliographic source location only when the exact invoked form is stated and every hypothesis is explicitly verified from prior steps; otherwise mark the use unverified.
Distinguish rigorously proved facts, plausible conjectures, failed directions, and unresolved gaps.
Abstract mathematical reasoning is the default. Computation is evidence for a precisely stated decision, never a replacement for proof and never a default strategy generator.
No finite sample or failure to find a counterexample verifies a universal claim. A computation request must expose only an auditable mathematical basis, not private chain of thought.
The fields calculation_evidence_refs are server-owned; always return them empty. When a route premise or ProofStep relies on an explicit value attributed to finite computation (for example a generated sequence prefix, enumerated minimum, finite search, or period sample), declare an assertion-checking ToolRequest in calculation_checks. A minimum or classification established symbolically is not a computation trigger. The server executes declared checks locally before admitting that finite premise.
Return exactly one JSON object conforming to the supplied JSON Schema. Do not add markdown fences or prose outside the JSON object.
Inside JSON strings, escape every LaTeX backslash as `\\`; an unescaped backslash makes the whole artifact invalid.
""".strip()


GOAL_NORMALIZATION_SYSTEM = """
You are the goal-normalizer in a verification-first mathematical reasoning system.
Do not solve the problem. Inspect only whether the submitted statement determines one
well-formed mathematical task. Treat missing moduli, domains, quantifiers, definitions,
referenced figures, or parameters as material when they can change the proposition.
Never silently rewrite the user's goal. A recommended statement is only a candidate for
explicit user confirmation. If the submitted statement is already unambiguous, copy it
verbatim into recommended_statement and set changes_mathematical_meaning=false.
If it is ambiguous, provide the most likely complete interpretation and list genuinely
different plausible alternatives. Do not disguise added hypotheses or parameters as
formatting. Confidence is metadata, not authority.
Return exactly one JSON object conforming to the supplied JSON Schema. Do not add
markdown fences, a proof, private scratch work, or prose outside the JSON object.
Inside JSON strings, escape every LaTeX backslash as `\\`.
""".strip()


class PromptFactory:
    def __init__(
        self, output_language: str = "zh-CN", *, computation_enabled: bool = False
    ) -> None:
        self.output_language = output_language
        self.computation_enabled = computation_enabled

    def goal_normalization(
        self,
        original_statement: str,
        local_precheck: LocalGoalPrecheck,
    ) -> PromptBundle:
        user = f"""
[STAGE:goal_normalization]
Review the exact submitted statement for ambiguity or missing mathematical information.
The local findings are high-precision warnings, not permission to guess. Return a
complete recommended interpretation, alternatives when they are genuinely plausible,
and whether adopting the recommendation changes mathematical meaning.
The user-facing explanation should use {self.output_language}.

EXACT SUBMITTED STATEMENT:
{original_statement}

LOCAL DETERMINISTIC PRECHECK:
{_json(local_precheck)}

JSON SCHEMA:
{_schema(GoalNormalizationAssessment)}
""".strip()
        return PromptBundle(
            "goal_normalization",
            GOAL_NORMALIZATION_SYSTEM,
            user,
            GoalNormalizationAssessment,
            temperature=0.0,
            max_output_tokens=4096,
        )

    def triage(self, problem: ProblemContract) -> PromptBundle:
        user = f"""
[STAGE:triage]
Classify the problem and recommend a cost-aware reasoning mode. Do not attempt the full solution yet.
Assess whether the task needs direct solving, a claim-dependency DAG, or a hybrid. Identify likely failure modes and useful deterministic tools.
Populate task_requirements from what the user actually requested. Do not add proof merely because the input is mathematical: computation, conjecture, counterexample, classification, optimization, construction, solution, and proof are distinct deliverables. A conjecture explicitly marked for separate later proof does not request that proof in this run.
If exact_statement is not English, populate semantic_view_candidate with a faithful English rendering for downstream semantic comparison. This rendering is non-authoritative: preserve every formula, variable, number, hypothesis, quantifier, domain, requested conclusion, task intent, polarity, implication direction, and the order of directional mathematical clauses exactly. Set each preservation flag independently and leave the candidate null when the statement is already English.
The final system output language is {self.output_language}.

IMMUTABLE PROBLEM CONTRACT:
{_json(problem)}

JSON SCHEMA:
{_schema(TriageResult)}
""".strip()
        return PromptBundle(
            "triage", COMMON_SYSTEM, user, TriageResult, temperature=0.0
        )

    def normalize_statements(
        self,
        problem: ProblemContract,
        statements: list[dict[str, Any]],
    ) -> PromptBundle:
        """Batched form-repair of blueprint statements the deterministic
        semantic gate marked NEEDS_NORMALIZATION.

        The reviewer restates each item with explicit objects, quantifiers,
        relation, and scope. It must not prove, refute, strengthen, weaken,
        or otherwise change the mathematics, and must not output private
        chain of thought.
        """
        user = f"""
[STAGE:statement_normalization]
You are a structural reviewer. Each statement below is believed to be a mathematical proposition, but the deterministic parser could not extract explicit objects, a relation, or a quantifier/scope from its current wording.
For every input item, return the same mathematics restated explicitly:
1. Name the mathematical objects (sets, sequences, integers, functions).
2. Make every quantifier explicit ("对任意"/"存在"/"for all"/"there exists"), including quantifiers that were implicit in a predicate such as "递增"/"increasing".
3. State the relation with an explicit relation symbol or relation word (=, ≤, ∈, 整除, divides, ...).
4. Preserve the language of the original statement.
Never prove or refute a statement, never judge whether it is true, never add or remove mathematical content, and never merge two statements. If an item is not a mathematical proposition at all (it is a search instruction or process text), set is_mathematical_proposition=false and copy the original text unchanged.
The needs field of each item lists what the parser could not find; address exactly those gaps.

IMMUTABLE PROBLEM CONTRACT:
{_json(problem)}

STATEMENTS TO NORMALIZE:
{_json(statements)}

JSON SCHEMA:
{_schema(PropositionNormalizationBatch)}
""".strip()
        return PromptBundle(
            "statement_normalization",
            COMMON_SYSTEM,
            user,
            PropositionNormalizationBatch,
            temperature=0.0,
        )

    _TECHNIQUE_MENU = {
        "combinatorics": (
            "invariants/monovariants, double counting, extremal principle, "
            "pigeonhole, probabilistic method, generating functions, "
            "bijections, graph modelling"
        ),
        "number_theory": (
            "modular arithmetic and orders, lifting the exponent, p-adic "
            "valuation, infinite descent, CRT, bounding between consecutive "
            "powers, multiplicative structure"
        ),
        "algebra": (
            "clever substitution, symmetrization, SOS decomposition, "
            "homogenization, tangent-line trick, telescoping, roots of "
            "unity filter, polynomial identities"
        ),
        "inequality": (
            "SOS, homogenization/normalization, tangent-line trick, "
            "smoothing/mixing, Schur/SOS-Schur, convexity and Jensen, "
            "rearrangement"
        ),
        "functional_equation": (
            "substitution families, injectivity/surjectivity arguments, "
            "fixed points, Cauchy-type reductions, symmetry swaps"
        ),
        "geometry": (
            "complex numbers, inversion, projective/cross-ratio, "
            "barycentric coordinates, spiral similarity, radical axes"
        ),
        "analysis": (
            "monotone/bounded convergence, compactness, extremal choice, "
            "continuity/discreteness interplay, telescoping estimates"
        ),
    }

    def strategies(
        self,
        problem: ProblemContract,
        triage: TriageResult,
        count: int,
        prior_strategy_titles: list[str] | None = None,
        regulator_feedback: list[str] | None = None,
        forbidden_mechanisms: list[str] | None = None,
    ) -> PromptBundle:
        prior_strategy_titles = prior_strategy_titles or []
        forbidden_mechanisms = forbidden_mechanisms or []
        calculation_contracts = (
            experiment_tool_catalog(problem.allowed_tools)
            if self.computation_enabled
            else []
        )
        feedback_directives = _feedback_directives(
            regulator_feedback,
            source="meta_review_or_strategy_regulator",
            default_kind="required_action_or_unresolved_conflict",
            default_status="open",
        )
        problem_kind = getattr(triage.problem_kind, "value", str(triage.problem_kind))
        technique_menu = self._TECHNIQUE_MENU.get(
            problem_kind.casefold(),
            "; ".join(sorted(set(self._TECHNIQUE_MENU.values()))[:3]),
        )
        user = f"""
[STAGE:strategy_generation]
Generate up to {count} genuinely distinct and feasible solution strategies for the immutable problem.
Optimize each strategy for the explicit task_requirements and deliverables in the contract. Do not add a proof deliverable that the user did not request. For a computation-and-conjecture task, plan one exact bounded computation plus a scoped candidate statement and stop before attempting the separately listed proof obligation.
The strategies must differ in their decisive mathematical mechanism, not merely wording or notation.
Do not pad the list with invented weak variants. If the mathematical space supports fewer sound approaches, return fewer.
Before choosing mechanisms, write 2-4 EQUIVALENT REFORMULATIONS of the goal (contrapositive, a strengthened induction-friendly statement, a generalization with a parameter, a dual/complementary counting view) and let at least one strategy attack a reformulation rather than the literal statement — hard problems are usually solved through the right restatement.
TECHNIQUE MENU for this problem kind ({problem_kind}): {technique_menu}. Treat the menu as a checklist of candidate decisive mechanisms, not a limit; combining or transcending menu items is encouraged, but never ignore an applicable classical technique.
For every strategy, fill key_original_step with the single most non-routine transformation the route depends on — the step a competent student would NOT find automatically. A strategy whose key_original_step is routine is a weak variant.
FORBIDDEN MECHANISMS (already produced by earlier sampling; do not rename them): {_json(forbidden_mechanisms)}
For every strategy, state the bottleneck, the intended falsification test, the expected intermediate lemmas, and why it is independent of the others.
List the smallest load-bearing claims in critical_claims. Mark a claim "required" only when refuting it invalidates the route; give each one a precise low-cost falsification test and a preferred deterministic tool when applicable.
You may record narrowly described ComputationHint items for later consideration, but hints are non-executable and must not replace the strategy's abstract mathematical mechanism.
If a strategy already relies on concrete values obtained by finite computation, put one exact assertion-checking ToolRequest in strategy.calculation_checks for each load-bearing finite assertion. This is mandatory for computed sequence terms, an enumerated minimum, a finite search result, or a tested period used as a premise. Do not add a computation check for a symbolic extremum proof, a symbolic case classification, or merely because the falsification test proposes future computation. Use sympy_equivalent rather than result-only simplification/factorization when an exact symbolic equality is the asserted finite result. A bounded check validates only its finite statement and cannot establish an infinite pattern.
Avoid repeating previous directions unless regulator feedback explicitly asks for a repair.

IMMUTABLE PROBLEM CONTRACT:
{_json(problem)}

TRIAGE:
{_json(triage)}

PREVIOUS STRATEGY TITLES TO AVOID OR REVISE:
{_json(prior_strategy_titles)}

NON-AUTHORITATIVE REGULATOR DIRECTIVES:
{_json(feedback_directives)}

Every directive above is an OPEN task, conflict, or repair request, never an established mathematical fact.
Imperative wording such as "rule out X", "prove Y", or "derive Z" means that X/Y/Z still requires proof.
Do not cite a directive as theorem support, do not restate it as already proved, and do not promote it to a strategy premise.
Only items supplied in an explicitly verified/Broker-admitted fact section are premise-eligible.

REGISTERED TYPED CALCULATION CONTRACTS:
{_json(calculation_contracts)}

JSON SCHEMA:
{_schema(StrategySet)}
""".strip()
        return PromptBundle(
            "strategy_generation", COMMON_SYSTEM, user, StrategySet, temperature=0.5
        )

    def explore(
        self,
        problem: ProblemContract,
        strategy: dict[str, Any],
        agent_id: str,
        round_index: int,
        verified_claims: list[dict[str, Any]],
        targeted_feedback: list[str] | None = None,
        previous_attempt: dict[str, Any] | None = None,
        remaining_call_budget: int = 0,
        experiment_results: list[dict[str, Any]] | None = None,
        computation_feedback: list[dict[str, Any]] | None = None,
        negative_knowledge: list[dict[str, Any]] | None = None,
    ) -> PromptBundle:
        negative_knowledge = negative_knowledge or []
        feedback_directives = _feedback_directives(
            targeted_feedback,
            source="path_review_or_failure_record",
            default_kind="repair_or_falsification_directive",
            default_status="open_or_rejected",
        )
        experiment_results = experiment_results or []
        computation_feedback = computation_feedback or []
        computation_contracts = (
            experiment_tool_catalog(problem.allowed_tools)
            if self.computation_enabled
            else []
        )
        response_model = (
            InitialExplorationTurn if self.computation_enabled else ProofAttempt
        )
        computation_instruction = (
            'If a cheap exact test would promptly falsify a precise conjecture, or a necessary bounded check is no longer reasonable by hand, return action="request_computation" with one complete ExperimentSpec. Otherwise return action="submit_attempt" with a ProofAttempt. Return action="abandon" only with a precise mathematical obstruction.'
            if self.computation_enabled
            else "Computation requests are disabled in this profile; return a ProofAttempt based on mathematical reasoning."
        )
        user = f"""
[STAGE:independent_exploration]
You are explorer {agent_id}, assigned exactly one strategy. During initial exploration, ignore all other candidate solutions so that diversity is preserved.
Follow the immutable task_requirements exactly. If the user requested only a computation and a scoped conjecture, return those artifacts without trying to discharge the separately identified proof obligation.
Develop this direction only as far as the requested deliverables require. A complete proof is preferred only when proof is one of task_requirements; never force a false conclusion. Rigorous partial lemmas, a precise obstruction, or a proved dead end remain valid progress.
Begin from the structural mathematical mechanism. Computation is not the default route: use no more than three representative hand checks, and never perform long enumeration in prose.
{computation_instruction}
The request's reasoning_basis is a short auditable mathematical rationale, not private chain of thought. Pattern discovery or broad search must set broad_search=true and may be deferred by policy. Never request computation merely to "look for a pattern" before doing abstract reasoning.
Choose a registered typed method whenever possible. sandboxed_python is permitted only as a last resort and requires typed_tool_gap to explain precisely why none of the registered tools can express the check.
For numeric_counterexample, set exact_arithmetic=false; only an independently re-substituted candidate may later become exact counterexample evidence.
Use argument names and semantics exactly as declared below. Never invent aliases or send arguments that a tool contract does not list.
Every non-routine decisive step must have is_key_step=true and be expanded into explicit substeps. Avoid words such as "obvious" or "clearly" in place of justification.
Whenever a ProofStep relies on a value explicitly obtained by finite computation, include an exact assertion-checking ToolRequest in that ProofStep.calculation_checks. The check must encode the claimed answer, not merely ask the tool to generate an unrelated value. Symbolic extremum arguments, symbolic case classifications, and routine displayed algebra do not require a tool unless they depend on a separate load-bearing finite computation. The server blocks a required check if the declaration is missing, malformed, inconclusive, or refuted. This adds no model call when the request is valid.
For each dependency, use a step_id or verified claim_id. Encode an external theorem as `external:<exact theorem name>` and state its hypotheses in the justification; never put a bare theorem title in dependencies. Do not use an unverified claim as a theorem.
State falsification checks and unresolved gaps explicitly.
The response must retain the problem_hash exactly as given and set agent_id={agent_id!r}, round_index={round_index}.

IMMUTABLE PROBLEM CONTRACT:
{_json(problem)}

ASSIGNED STRATEGY:
{_json(strategy)}

VERIFIED LEMMA LIBRARY (facts only; may be empty):
{_json(verified_claims)}

NEGATIVE KNOWLEDGE FROM ALL ROUTES (refuted claims with their counterexamples and dead ends other explorers already hit; never premises, never targets — spend zero calls re-deriving or re-attempting them):
{_json(negative_knowledge)}

NON-AUTHORITATIVE TARGETED REVIEW DIRECTIVES FOR THIS PATH:
{_json(feedback_directives)}

The directives above report open obligations, rejected attempts, verification issues, or repair requests.
They are not mathematical premises. Convert OPEN items into explicit subgoals; use REJECTED items only as negative evidence.
Only the verified lemma library and newly proved steps with valid dependencies may support a conclusion.

PREVIOUS ATTEMPT ON THIS SAME PATH (empty on first round):
{_json(previous_attempt or {})}

COMPUTATION DECISIONS FROM THIS SAME TURN (may include reject/defer):
{_json(computation_feedback)}

REGISTERED TYPED COMPUTATION CONTRACTS:
{_json(computation_contracts)}

STRUCTURED EXPERIMENT RESULTS FROM THIS SAME PATH:
{_json(experiment_results)}

Interpret the mathematical consequence of any experiment result before submitting an attempt. not_refuted and bounded_evidence are not proofs. Do not place experimental output directly into proposed_lemmas or present it as a proved step.
After a successful discover_pattern result, put at least one concrete, falsifiable hypothesis in candidate_conjectures. Link it to the exact experiment_id, state why bounded evidence is not proof, and name the separate symbolic proof obligation. Leave candidate evidence_refs empty for the server.
After a confirmed counterexample, immediately correct or abandon the affected route and set experiment_impact to execution, plan, or strategy according to its scope.
Fill attempt.proof_sketch (<=4000 chars) with your global route map: the intended chain of lemmas, the technique planned for each, and where the hardest step lies. The sketch is non-authoritative and is re-shown to you on every later segment of this path, so invest in it — it is the only channel that survives between your calls.

REMAINING GLOBAL CALL BUDGET: {remaining_call_budget}
OUTPUT LANGUAGE: {self.output_language}

JSON SCHEMA:
{_schema(response_model)}
""".strip()
        return PromptBundle(
            "independent_exploration",
            COMMON_SYSTEM,
            user,
            response_model,
            temperature=0.45,
            output_tier=0,
        )

    def complete_pattern_conjectures(
        self,
        problem: ProblemContract,
        experiment_results: list[dict[str, Any]],
        existing_candidates: list[dict[str, Any]],
    ) -> PromptBundle:
        user = f"""
[STAGE:pattern_conjecture_completion]
The previous route response consumed one or more successful discover_pattern experiments but omitted the required structured candidate conjecture.
Perform only this small semantic completion. Do not redo the proof and do not request another computation.

For every returned CandidateConjecture:
- state a concrete mathematical formula, recurrence, invariant, classification, or other falsifiable pattern;
- copy at least one experiment_id exactly into supporting_experiment_ids;
- explain briefly how the finite result suggests the pattern;
- state explicitly in scope_limitations that bounded evidence is not a proof;
- give at least one separate symbolic proof obligation;
- leave evidence_refs empty because the server attaches audited artifacts;
- keep status="candidate" and never present the conjecture as a verified fact.

IMMUTABLE PROBLEM CONTRACT:
{_json(problem)}

SUCCESSFUL DISCOVER_PATTERN RESULTS REQUIRING INTERPRETATION:
{_json(experiment_results)}

ALREADY RECORDED CANDIDATES:
{_json(existing_candidates)}

OUTPUT LANGUAGE: {self.output_language}

JSON SCHEMA:
{_schema(CandidateConjectureBatch)}
""".strip()
        return PromptBundle(
            "pattern_conjecture_completion",
            COMMON_SYSTEM,
            user,
            CandidateConjectureBatch,
            temperature=0.1,
            max_output_tokens=4096,
        )

    def continue_proof(
        self,
        problem: ProblemContract,
        strategy: dict[str, Any],
        checkpoint: ProofCheckpoint,
        agent_id: str,
        round_index: int,
        segment_index: int,
        verified_claims: list[dict[str, Any]],
        targeted_feedback: list[str] | None = None,
        max_new_steps: int = 3,
        max_new_claims: int = 3,
        checkpoint_policy: str = "verified_subgoal",
        remaining_call_budget: int = 0,
        experiment_results: list[dict[str, Any]] | None = None,
        computation_feedback: list[dict[str, Any]] | None = None,
        previous_working_checkpoint: dict[str, Any] | None = None,
        negative_knowledge: list[dict[str, Any]] | None = None,
    ) -> PromptBundle:
        feedback_directives = _feedback_directives(
            targeted_feedback,
            source="path_review_or_failure_record",
            default_kind="repair_or_falsification_directive",
            default_status="open_or_rejected",
        )
        experiment_results = experiment_results or []
        computation_feedback = computation_feedback or []
        negative_knowledge = negative_knowledge or []
        computation_contracts = (
            experiment_tool_catalog(problem.allowed_tools)
            if self.computation_enabled
            else []
        )
        response_model = ContinuationTurn if self.computation_enabled else ProofDelta
        computation_instruction = (
            'If a precise bounded computation is necessary, return action="request_computation" with one ExperimentSpec instead of enumerating in prose. A cheap exact falsification request may be made before the route stalls; broad pattern search may not.'
            if self.computation_enabled
            else "Computation requests are disabled in this profile; return the next auditable ProofDelta."
        )
        user = f"""
[STAGE:proof_continuation]
You are explorer {agent_id}. Continue one proof path from a verified external checkpoint.
The checkpoint is authoritative mathematical state, not a suggestion. Do not re-prove committed steps unless you identify an explicit contradiction; if a contradiction exists, report it in detected_conflicts and do not silently overwrite the checkpoint.
Produce at most {max_new_steps} new logically complete proof steps and at most {max_new_claims} new reusable claims. Each new step must name all dependencies and may depend only on committed step IDs, verified claim IDs, explicit external theorems, or earlier steps in this same delta.
Put existing checkpoint references only in `referenced_checkpoint_step_ids` as string IDs. Put newly proved mathematics only in `new_steps` as complete ProofStep objects; never place a bare step ID in `new_steps`.
Type every step: use step_type=assumption_intro/assumption_discharge for proof-by-contradiction scaffolding, case_split/case_close with a branch_label (for example "case d=1") for case analyses, definition/construction for auxiliary objects, and derivation otherwise. When every branch of a case_split is case_closed, restate the merged conclusion in a derivation step; when the last assumption is discharged, return active_assumptions=[] explicitly.
Every entry in `new_claims[].proof_steps` must also be a complete ProofStep object, normally copied from `new_steps`; never put a string such as `"s14"` there. Bare IDs belong only in `dependencies` or `referenced_checkpoint_step_ids`.
Encode every external theorem dependency as `external:<exact theorem name>` and state its applicable hypotheses in the step justification. A bare theorem title is not a valid dependency ID.
Work on the checkpoint's current_goal first. Finish a coherent subgoal rather than emitting a long unfinished transcript.
Use abstract reasoning first and limit manual numerical examples to three representative checks. {computation_instruction}
Always prefer a registered typed method. A sandboxed_python request must be the isolated last resort and must fill typed_tool_gap.
For numeric_counterexample, set exact_arithmetic=false; sampled non-refutation is always heuristic.
Use argument names and semantics exactly as declared below. Never invent aliases or send arguments that a tool contract does not list.
For every new ProofStep that relies on an explicit value obtained by finite computation, fill ProofStep.calculation_checks with an assertion-checking typed ToolRequest in the same response. A symbolic minimum proof, symbolic finite classification, and routine displayed algebra do not require a tool by themselves. The local gate runs required checks before checkpoint review and blocks missing, malformed, inconclusive, or refuted calculations. Leave calculation_evidence_refs empty for the server.
CHECKPOINT POLICY: {checkpoint_policy}. When this is "verified_subgoal", completed_subgoal must explicitly name the coherent subgoal completed by this delta unless the full proof is complete or a contradiction with the checkpoint is being reported.
If you removed a subgoal from remaining_subgoals, it must be the one named in completed_subgoal; never silently drop an unproven subgoal.
Set proof_complete=true only when the original immutable problem is fully solved, candidate_final_answer is self-contained, and remaining_subgoals is empty.
The response must retain problem_hash, path_id, strategy_id, parent_checkpoint_id, round_index, and segment_index exactly as supplied.
Reason privately, but output only the next auditable mathematical delta; never output hidden scratch work.
CONTINUITY CHANNEL: the checkpoint's proof_sketch is your route map and working_notes are YOUR OWN notes from the previous segment (plans, failed directions, technique ideas). They are non-authoritative, never dependencies, never evidence — but read them first so you do not rebuild your plan from scratch. Update delta.working_notes (<=4000 chars) with what your NEXT segment needs to know: what you plan next, what failed and why, which technique you intend for the remaining subgoals.

IMMUTABLE PROBLEM CONTRACT:
{_json(problem)}

ASSIGNED STRATEGY:
{_json(strategy)}

LATEST VERIFIED CHECKPOINT:
{_json(checkpoint)}

PREVIOUS ROUTE-LOCAL WORKING CHECKPOINT (not verified and never a premise; use only
to repair or continue the same route without repeating useful local work):
{_json(previous_working_checkpoint or {})}

VERIFIED LEMMA LIBRARY:
{_json(verified_claims)}

NEGATIVE KNOWLEDGE FROM ALL ROUTES (refuted claims with counterexamples and dead ends; never premises, never targets — do not re-derive them):
{_json(negative_knowledge)}

NON-AUTHORITATIVE TARGETED REVIEW DIRECTIVES:
{_json(feedback_directives)}

The directives above are never extensions of the verified checkpoint.
An instruction such as "rigorously rule out d=1" means that d=1 remains OPEN until a new delta proves otherwise and passes independent checkpoint verification.
Treat failed/rejected feedback as negative evidence only; never copy any directive into a proof step as an established premise.

COMPUTATION DECISIONS FROM THIS SAME SEGMENT:
{_json(computation_feedback)}

REGISTERED TYPED COMPUTATION CONTRACTS:
{_json(computation_contracts)}

STRUCTURED EXPERIMENT RESULTS FOR THIS SAME PARENT CHECKPOINT:
{_json(experiment_results)}

Explain the mathematical meaning of any result in the submitted delta. not_refuted and bounded_evidence cannot support a proof step by themselves, and no experiment may directly advance the checkpoint.
After a successful discover_pattern result, return at least one concrete hypothesis in delta.candidate_conjectures with the exact experiment_id, explicit scope limitations, and a separate proof obligation. It remains route-local and cannot advance the verified checkpoint.
After a confirmed counterexample, immediately correct or abandon the affected route and classify experiment_impact as execution, plan, or strategy.

AUTHORITATIVE IDS:
agent_id={agent_id!r}
round_index={round_index}
segment_index={segment_index}
parent_checkpoint_id={checkpoint.checkpoint_id!r}
REMAINING GLOBAL CALL BUDGET: {remaining_call_budget}
OUTPUT LANGUAGE: {self.output_language}

JSON SCHEMA:
{_schema(response_model)}
""".strip()
        return PromptBundle(
            "proof_continuation",
            COMMON_SYSTEM,
            user,
            response_model,
            temperature=0.25,
            output_tier=max(0, segment_index - 1),
        )

    def experiment_codegen(
        self,
        problem: ProblemContract,
        spec: ExperimentSpec,
        agent_id: str,
    ) -> PromptBundle:
        user = f"""
[STAGE:experiment_codegen]
You are isolated experiment author {agent_id}. A policy gate has already determined that no registered typed tool can express this bounded request.
Write the smallest deterministic Python program that defines exactly one function run(data) and returns a JSON object. Do not call open, exec, eval, compile, subprocess, socket, network APIs, environment APIs, dynamic import, or filesystem APIs. Do not read stdin yourself and do not print. Use only Python built-ins or explicitly declared imports from math, fractions, decimal, itertools, functools, or collections.
The result object must use outcome in counterexample_found | not_refuted | inconclusive, include cases_checked, scope, exact_arithmetic, and include counterexample only when one is found. It must never label a positive finite check as verified or certified.
Return source plus explicit input_schema and output_schema. input_schema must declare the integer seed field injected by the runner, and dependencies must exactly list the imported whitelisted modules. Do not solve or alter the mathematical problem.

IMMUTABLE PROBLEM CONTRACT:
{_json(problem)}

ADMITTED EXPERIMENT SPECIFICATION:
{_json(spec)}

Set experiment_id={spec.experiment_id!r}. No third-party dependencies are allowed.

JSON SCHEMA:
{_schema(ExperimentProgram)}
""".strip()
        return PromptBundle(
            "experiment_codegen",
            COMMON_SYSTEM,
            user,
            ExperimentProgram,
            temperature=0.0,
        )

    def computation_contract_repair(
        self,
        problem: ProblemContract,
        spec: ExperimentSpec,
        issues: list[str],
        agent_id: str,
        *,
        sandbox_enabled: bool,
    ) -> PromptBundle:
        contracts = experiment_tool_catalog(problem.allowed_tools)
        user = f"""
[STAGE:computation_contract_repair]
You are computation request compiler {agent_id}. Repair exactly one malformed bounded computation request. This is not a proof-writing stage.

You may change only method, domains, arguments, exact_arithmetic, and typed_tool_gap. Preserve experiment_id, purpose, target_claim, assumptions, reasoning_basis, why_computation_is_needed, decision_if_confirmed, decision_if_refuted, noncomputational_alternative, broad_search, max_cases, and seed exactly.

Rules:
1. Retry with a registered typed tool only when its contract expresses the complete requested computation without changing its cases, predicate, or requested output.
2. Never reinterpret a batch as one sequence, never silently discard fields, and never reduce the requested scope merely to fit a contract.
3. If the request needs a batch, custom aggregation, or other bounded operation not represented by a typed contract, use sandboxed_python only when SANDBOX AVAILABLE is true. Put the complete bounded JSON input under arguments.input and state the precise capability gap in typed_tool_gap.
4. If no semantics-preserving bounded request can be formed, abandon it. Do not return a proof attempt, proof delta, mathematical conclusion, or a replacement claim.
5. A finite non-refutation remains bounded evidence and cannot prove an infinite statement.

IMMUTABLE PROBLEM CONTRACT:
{_json(problem)}

ORIGINAL EXPERIMENT SPECIFICATION:
{_json(spec)}

CONTRACT ERRORS:
{_json(issues)}

REGISTERED TOOL CONTRACTS:
{_json(contracts)}

SANDBOX AVAILABLE: {str(sandbox_enabled).lower()}
Set repaired_spec.experiment_id={spec.experiment_id!r} when retrying.

JSON SCHEMA:
{_schema(ComputationContractRepair)}
""".strip()
        return PromptBundle(
            "computation_contract_repair",
            COMMON_SYSTEM,
            user,
            ComputationContractRepair,
            temperature=0.0,
            max_output_tokens=8192,
        )

    def verify_delta(
        self,
        problem: ProblemContract,
        strategy: dict[str, Any],
        checkpoint: ProofCheckpoint,
        delta: ProofDelta,
        verifier_id: str,
        verified_claims: list[dict[str, Any]],
        experiment_results: list[dict[str, Any]] | None = None,
        tool_results: list[dict[str, Any]] | None = None,
    ) -> PromptBundle:
        experiment_results = experiment_results or []
        tool_results = tool_results or []
        rollback_instruction = (
            """
SPECIAL ROLLBACK REVIEW:
This delta adds no proof steps and asks to roll back the latest committed
checkpoint because detected_conflicts is nonempty. PASS authorizes a rollback;
it does not append the delta. Return PASS only if you independently verify a
concrete contradiction in a step introduced by the latest checkpoint segment.
Put that exact step_id in first_error_step and checked_dependencies. A vague
concern, an error in an older ancestor, or the author's unsupported assertion
must be FAIL or UNCERTAIN.
""".strip()
            if delta.detected_conflicts and not delta.new_steps
            else ""
        )
        user = f"""
[STAGE:checkpoint_verification]
You are independent checkpoint verifier {verifier_id}. The delta author is a different agent.
Decide whether the proposed proof delta may be committed as the next persistent resume point. Perform both structural and step-level checks in one cost-aware review:
1. Confirm the immutable problem, path, strategy, parent checkpoint, and segment index are unchanged.
2. Confirm every dependency is already committed, verified, external with explicit hypotheses, or an earlier step in this delta.
3. Check every new inference, calculation, inequality direction, quantifier, case split, and boundary condition.
4. Locate the first invalid or unjustified new step. A later correct statement cannot repair an earlier gap.
5. Check that claimed completed_subgoal/current_goal/remaining_subgoals accurately reflect the mathematical state.
6. Permit proof_complete only if the full original problem is solved with no hidden assumptions or remaining subgoals.
7. Return PASS only when the entire delta is safe to append to the verified checkpoint. Otherwise return FAIL or UNCERTAIN and give a focused repair instruction.
8. Inspect independent_replay_audit for every decisive counterexample/certificate represented in EXPERIMENT RESULTS, then verify its mathematical mapping yourself. A failed replay blocks PASS. A not_refuted, heuristic, or bounded_evidence result cannot justify PASS. For an exhaustive certificate, verify that its finite reduction really covers the original claim.
Set target_id to the delta_id, target_type="proof_delta", agent_id={verifier_id!r}, and stage="detailed".

{rollback_instruction}

IMMUTABLE PROBLEM CONTRACT:
{_json(problem)}

ASSIGNED STRATEGY:
{_json(strategy)}

PARENT VERIFIED CHECKPOINT:
{_json(checkpoint)}

VERIFIED LEMMA LIBRARY:
{_json(verified_claims)}

CANDIDATE PROOF DELTA:
{_json(delta)}

EXPERIMENT RESULTS ASSOCIATED WITH THIS PATH (evidence only, never proof steps):
{_json(experiment_results)}

INDEPENDENT TOOL RESULTS REQUESTED BY THIS VERIFIER:
{_json(tool_results)}

JSON SCHEMA:
{_schema(VerificationReport)}
""".strip()
        return PromptBundle(
            "checkpoint_verification",
            COMMON_SYSTEM,
            user,
            VerificationReport,
            temperature=0.0,
        )

    def summarize_claims(
        self,
        problem: ProblemContract,
        attempt: ProofAttempt,
        existing_claims: list[dict[str, Any]],
    ) -> PromptBundle:
        user = f"""
[STAGE:claim_extraction]
Convert the attempt into a compact, loss-aware lemma packet for future agents.
Extract only mathematically substantive new claims whose proof is actually present. Do not promote guesses, goals, or desired conclusions into lemmas.
Each extracted claim must include assumptions, conclusion, dependencies, proof steps, source attempt/agent IDs, scope limitations, and evidence references to the attempt.
ClaimCard.dependencies may contain only other ClaimCard IDs from the library or explicit external IDs prefixed with "external:"; dependencies among steps inside the same claim belong in ProofStep.dependencies.
Deduplicate against the existing library. Preserve uncertainty and failed conditions. The summary must not erase counterexamples or unresolved gaps.
If no reusable claim is proved, return an empty claims list and explain why in discarded_material.

IMMUTABLE PROBLEM CONTRACT:
{_json(problem)}

SOURCE ATTEMPT:
{_json(attempt)}

EXISTING CLAIM LIBRARY:
{_json(existing_claims)}

JSON SCHEMA:
{_schema(ClaimBatch)}
""".strip()
        return PromptBundle(
            "claim_extraction", COMMON_SYSTEM, user, ClaimBatch, temperature=0.0
        )

    def structural_verify(
        self,
        problem: ProblemContract,
        target: dict[str, Any],
        verifier_id: str,
        plan: dict[str, Any] | None = None,
    ) -> PromptBundle:
        user = f"""
[STAGE:structural_verification]
You are independent structural verifier {verifier_id}. You did not participate in producing the target.
Perform inexpensive gatekeeping before detailed proof checking:
1. Compare the target's claimed theorem against the immutable problem, including every quantifier, hypothesis, domain, and requested part.
2. Check completeness: all requested parts are addressed, and a complete attempt really has a final answer.
3. Validate the dependency graph: no missing dependencies, circular claims, orphan conclusions, or use of proposed/uncertain lemmas as established facts.
4. Check that the proof follows the assigned plan when a plan is supplied.
5. Inspect theorem use structurally: the exact invoked form and applicability conditions must be present. Do not demand a bibliographic source for a standard named theorem, but do flag any missing hypothesis or unverifiable theorem use.
6. Ensure nontrivial steps are explicitly marked and not hidden behind vague language.
7. MANDATORY theorem-admission check: for every `external:<theorem>` dependency, judge whether the cited theorem is equivalent to, or strictly stronger than, the target proposition — citing a theorem that directly contains the goal trivializes the problem and is a STRATEGY-level failure, not a valid proof. Also verify no hard constraint in the problem contract's hard_constraints list is violated by any cited theorem or tool; a violated hard constraint is a STRATEGY-level failure.
Do not perform an expensive line-by-line re-proof unless needed to identify a structural failure.
Set problem_integrity_ok=false for any change to the problem. Classify failure_level as execution, plan, or strategy.

IMMUTABLE PROBLEM CONTRACT:
{_json(problem)}

OPTIONAL PLAN:
{_json(plan or {})}

TARGET:
{_json(target)}

Set agent_id={verifier_id!r}, stage="structural", and target_type appropriately.

JSON SCHEMA:
{_schema(VerificationReport)}
""".strip()
        return PromptBundle(
            "structural_verification",
            COMMON_SYSTEM,
            user,
            VerificationReport,
            temperature=0.0,
        )

    def detailed_verify(
        self,
        problem: ProblemContract,
        target: dict[str, Any],
        structural_report: VerificationReport,
        verified_claims: list[dict[str, Any]],
        verifier_id: str,
        tool_results: list[dict[str, Any]] | None = None,
        experiment_results: list[dict[str, Any]] | None = None,
        stage: str = "detailed",
    ) -> PromptBundle:
        tool_results = tool_results or []
        experiment_results = experiment_results or []
        user = f"""
[STAGE:{stage}_verification]
You are independent detailed verifier {verifier_id}. Check the mathematical process, not merely the final answer.
For every proof step: restate the exact assertion, identify its dependencies, test whether the justification implies it, verify algebra/inequalities/case coverage, and inspect boundary conditions.
Locate the first invalid or unjustified step. A later correct conclusion does not repair an earlier gap.
Try actively to falsify decisive claims by small cases, extremal cases, dimensional checks, substitutions, or counterexamples.
DEVIL'S ADVOCATE OBLIGATION: identify the single weakest key step and mount your strongest concrete refutation attempt against it (a candidate counterexample, an unchecked degenerate case, a quantifier-order slip, a division-by-zero branch, a limit/summation exchange); record what you tried and why it failed to break the step. A PASS without a recorded refutation attempt on the weakest step is invalid.
Your PASS must list every step ID you actually checked in checked_dependencies; an empty checked_dependencies with a PASS verdict will be downgraded automatically.
Use the verified lemma library only when every hypothesis is matched explicitly.
When a deterministic calculation would materially resolve uncertainty, emit a narrowly scoped ToolRequest. Never request arbitrary code execution.
Return PASS only when every required step is supported. Use UNCERTAIN rather than guessing when a deep theorem or computation remains unverified.
Treat not_refuted, heuristic, and bounded_evidence experiments as non-proofs. Inspect each independent_replay_audit, independently check the mathematical mapping of decisive counterexamples, and validate the finite-reduction mapping behind any exhaustive certificate. A failed replay blocks PASS. A tool exception is inconclusive, not a mathematical failure. Lean acceptance still requires checking that the encoded statement matches the natural-language claim.

IMMUTABLE PROBLEM CONTRACT:
{_json(problem)}

STRUCTURAL REPORT:
{_json(structural_report)}

VERIFIED LEMMA LIBRARY:
{_json(verified_claims)}

AVAILABLE TOOL RESULTS:
{_json(tool_results)}

AUDITABLE EXPERIMENT RESULTS:
{_json(experiment_results)}

TARGET:
{_json(target)}

Set agent_id={verifier_id!r}, stage={stage!r}, and target_type appropriately.

JSON SCHEMA:
{_schema(VerificationReport)}
""".strip()
        return PromptBundle(
            f"{stage}_verification",
            COMMON_SYSTEM,
            user,
            VerificationReport,
            temperature=0.0,
        )

    def blind_structural_review(
        self,
        packet: BlindReviewPacket,
    ) -> PromptBundle:
        user = f"""
[STAGE:blind_structural_verification]
Conduct an independent structural examination using only the packet below.
1. Compare the claimed theorem with the immutable statement, including every quantifier, hypothesis, domain, and requested part.
2. Check completeness and dependency integrity: no missing dependencies, circular claims, orphan conclusions, or uncertain material presented as established fact.
3. Check every named theorem structurally: its invoked form and all applicability conditions must be explicit.
4. Flag vague placeholders that conceal a nontrivial step.
5. Inspect fact_context_complete and negative_context_complete. Either being false is a deterministic context-integrity defect and cannot receive PASS. Optional negative_context_truncated=true alone is not a defect because mandatory counterexamples and direct conflicts are selected first.
Do not infer authorship, provenance, ranking, or any assessment made outside this packet.
Do not perform an expensive line-by-line reconstruction unless needed to identify a structural defect.
Set problem_integrity_ok=false if the proof changes the statement. Classify a defect as execution, plan, or strategy.

BLIND REVIEW PACKET:
{_json(packet)}

JSON SCHEMA:
{_schema(BlindVerificationReport)}
""".strip()
        return PromptBundle(
            "blind_structural_verification",
            COMMON_SYSTEM,
            user,
            BlindVerificationReport,
            temperature=0.0,
        )

    def blind_detailed_review(
        self,
        packet: BlindReviewPacket,
        *,
        tool_results: list[dict[str, Any]] | None = None,
        experiment_results: list[dict[str, Any]] | None = None,
    ) -> PromptBundle:
        tool_results = tool_results or []
        experiment_results = experiment_results or []
        user = f"""
[STAGE:blind_detailed_verification]
Independently audit the mathematical process using only the packet and auditable evidence below.
For every proof step, identify the exact assertion and dependencies, test whether the stated justification implies it, verify algebra and inequalities, and check all cases and boundary conditions.
Locate the first invalid or unjustified step. A later correct conclusion does not repair an earlier gap.
Actively try to falsify decisive claims with exact small cases, extremal cases, dimensional checks, substitutions, or counterexamples.
Treat not_refuted, heuristic, and bounded_evidence experiments as non-proofs. Validate any finite-reduction mapping behind an exhaustive certificate. A tool exception is inconclusive, not a mathematical failure.
When a deterministic calculation would materially resolve uncertainty, emit a narrowly scoped ToolRequest. Never request arbitrary code execution.
Inspect fact_context_complete and negative_context_complete before judging the proof. Either being false prevents PASS; report the missing cited Fact or omitted mandatory negative evidence as the first structural defect when applicable. Optional negative_context_truncated=true alone does not prevent PASS.
Return PASS only when every requested conclusion and decisive step is supported. Use UNCERTAIN when a genuine gap remains.
Do not infer authorship, provenance, ranking, or any assessment made outside this packet.

BLIND REVIEW PACKET:
{_json(packet)}

AVAILABLE TOOL RESULTS:
{_json(tool_results)}

AUDITABLE EXPERIMENT RESULTS:
{_json(experiment_results)}

JSON SCHEMA:
{_schema(BlindVerificationReport)}
""".strip()
        return PromptBundle(
            "blind_detailed_verification",
            COMMON_SYSTEM,
            user,
            BlindVerificationReport,
            temperature=0.0,
        )

    def meta_review(
        self,
        problem: ProblemContract,
        attempts: list[dict[str, Any]],
        reports: list[dict[str, Any]],
    ) -> PromptBundle:
        user = f"""
[STAGE:meta_review]
Act as a review chair, not as another debater. Independently assess the candidates, then integrate the independent verification reports.
Deduplicate repeated comments, expose disagreements, and never convert majority opinion into mathematical truth.
Rank candidates by proof completeness, key-step validity, repairability, and verified progress. Confidence values may break ties only after evidence quality.
For the best candidate, diagnose the next action at the correct level:
- execution: keep the strategy and repair local proof steps;
- plan: revise the dependency plan;
- strategy: abandon or widen to a new mechanism.
Set can_synthesize=true only when at least one candidate is sufficiently supported to form a final proof.
Approve broad computation only by listing a strategy_id in broad_computation_approved_strategy_ids, and only when that route has demonstrably stalled after abstract reasoning and a broad search has a precise decision purpose. Leave the list empty otherwise.

IMMUTABLE PROBLEM CONTRACT:
{_json(problem)}

CANDIDATE ATTEMPTS (structured summaries, not hidden transcripts):
{_json(attempts)}

INDEPENDENT VERIFICATION REPORTS:
{_json(reports)}

JSON SCHEMA:
{_schema(MetaReview)}
""".strip()
        return PromptBundle(
            "meta_review", COMMON_SYSTEM, user, MetaReview, temperature=0.0
        )

    def synthesize(
        self,
        problem: ProblemContract,
        selected_attempts: list[dict[str, Any]],
        verified_claims: list[dict[str, Any]],
        review: MetaReview,
        synthesizer_id: str,
        *,
        open_obligations: list[dict[str, Any]] | None = None,
        forbidden_claims: list[str] | None = None,
    ) -> PromptBundle:
        open_obligations = open_obligations or []
        forbidden_claims = forbidden_claims or []
        user = f"""
[STAGE:synthesis]
You are synthesizer {synthesizer_id}. Produce one self-contained final solution to the immutable problem using only supported material below.
Do not average incompatible proofs. Choose a coherent route, and combine claims only when their assumptions and dependencies match.
Every decisive non-routine step must be explicit and marked is_key_step=true. Include all cases, boundary conditions, and substitutions.
Preserve calculation_checks from supported source steps. For any newly introduced explicit computed value, add an assertion-checking ToolRequest to that ProofStep.calculation_checks; leave calculation_evidence_refs empty for the server. A finite certificate may support only the exact finite assertion it checked.
Before using any named theorem, state the exact form being invoked and add explicit proof steps for all of its hypotheses, even when a hypothesis follows by a short contradiction from earlier congruences. Use an `external:<exact theorem name>` dependency for the theorem. This explicitness is required so a repairable omission is fixed before final acceptance.
Do not mention agents, votes, review scores, or the orchestration process in the mathematical solution.
If the evidence does not support a complete proof, produce the strongest honest partial result with caveats rather than fabricating closure.
Set problem_hash exactly to the immutable problem's integrity_hash.

IMMUTABLE PROBLEM CONTRACT:
{_json(problem)}

SELECTED ATTEMPTS:
{_json(selected_attempts)}

VERIFIED LEMMA LIBRARY:
{_json(verified_claims)}

OPEN OBLIGATIONS (must not be presented as proved):
{_json(open_obligations)}

NEGATIVE MEMORY / FORBIDDEN CLAIMS:
{_json(forbidden_claims)}

META-REVIEW:
{_json(review)}

OUTPUT LANGUAGE: {self.output_language}

JSON SCHEMA:
{_schema(FinalProof)}
""".strip()
        return PromptBundle(
            "synthesis", COMMON_SYSTEM, user, FinalProof, temperature=0.1
        )

    def revise_final(
        self,
        problem: ProblemContract,
        proof: FinalProof,
        verification: VerificationReport,
        verified_claims: list[dict[str, Any]],
        reviser_id: str,
    ) -> PromptBundle:
        user = f"""
[STAGE:final_revision]
You are reviser {reviser_id}. Repair the submitted proof using the focused independent verification report.
Do not blindly accept feedback: first check each reported issue. Fix local execution errors without changing a sound plan; restructure the plan when dependencies are missing; abandon the strategy only when the report identifies a fundamental obstruction.
Preserve all correct material, remove invalid claims, and make every repaired inference explicit.
Preserve valid calculation_checks. If the repair introduces or changes an explicit computed value, update the assertion-checking ToolRequest in that ProofStep and leave calculation_evidence_refs empty for the server.
Do not weaken the original problem or silently add assumptions. If the proof cannot be repaired from available evidence, retain caveats and lower confidence.

IMMUTABLE PROBLEM CONTRACT:
{_json(problem)}

CURRENT FINAL PROOF:
{_json(proof)}

INDEPENDENT VERIFICATION REPORT:
{_json(verification)}

VERIFIED LEMMA LIBRARY:
{_json(verified_claims)}

Set problem_hash exactly to the immutable problem's integrity_hash. OUTPUT LANGUAGE: {self.output_language}

JSON SCHEMA:
{_schema(FinalProof)}
""".strip()
        return PromptBundle(
            "final_revision", COMMON_SYSTEM, user, FinalProof, temperature=0.1
        )

    def _typed_stage(
        self,
        stage: str,
        instruction: str,
        response_model: Type[BaseModel],
        context: dict[str, Any],
        *,
        temperature: float = 0.0,
        output_tier: int | None = None,
        max_output_tokens: int | None = None,
    ) -> PromptBundle:
        user = f"""
[STAGE:{stage}]
{instruction}
Do not emit a private chain of thought or any route transcript. Return only the
auditable mathematical artifact. State assumptions, scope, dependencies,
quantifiers, target obligations, and a falsification condition explicitly.

SANITIZED CONTEXT:
{_json(context)}

OUTPUT LANGUAGE: {self.output_language}

JSON SCHEMA:
{_schema(response_model)}
""".strip()
        return PromptBundle(
            stage,
            COMMON_SYSTEM,
            user,
            response_model,
            temperature=temperature,
            max_output_tokens=max_output_tokens,
            output_tier=output_tier,
        )

    def route_prove(
        self,
        problem: ProblemContract,
        *,
        authorized_output_tier: int = 0,
        **context: Any,
    ) -> PromptBundle:
        return self._typed_stage(
            "route_prove",
            (
                "Continue only the assigned mechanism from the authoritative verified "
                "checkpoint. Use fact_inbox and accepted broker_messages as premises, "
                "label insight_hints as non-premises, honor negative_memory, and work on "
                "the listed open_obligations. Respect every continuation limit and "
                "authoritative ID in the context. Return a ContinuationTurn: submit a "
                "bounded ProofDelta, request one precisely scoped computation when the "
                "reasoning-first gate permits it, complete the proof, or abandon with a "
                "specific obstruction. Never use not_refuted or bounded evidence as "
                "proof. A successful discover_pattern result must be interpreted as at "
                "least one concrete delta.candidate_conjectures item linked to the exact "
                "experiment_id, with scope limitations and a separate proof obligation; "
                "it remains route-local and is never a proved ClaimCard. Every ProofStep "
                "that relies on an explicit value attributed to finite computation must "
                "include an assertion-checking ToolRequest in calculation_checks. A "
                "symbolic minimum proof or symbolic finite classification is not a "
                "computation trigger. Routine displayed algebra does not require a tool. "
                "Leave calculation_evidence_refs empty for the server. Put prior checkpoint references in "
                "delta.referenced_checkpoint_step_ids as string IDs, while "
                "delta.new_steps must contain complete ProofStep objects rather than "
                "bare IDs. Every delta.new_claims[].proof_steps entry must likewise "
                "be a complete ProofStep object copied from delta.new_steps, never a "
                "string step ID; bare IDs belong in dependencies. If useful steps are "
                "ready but no self-contained final answer exists, return "
                "action=submit_delta with proof_complete=false instead of claiming "
                "completion. For every broker_messages item, return exactly one "
                "message_receipt. Copy only the opaque receipt_token supplied in "
                "message_receipt_requirements; never invent a hash. Report accepted or "
                "rejected, used, referenced_in_step_ids, claimed_closed_obligation_ids, "
                "and a short reason. Set used=true and add references only when the "
                "returned delta actually cites the message. The server validates use "
                "against the verified delta; reading or accepting a message is not use. "
                "Expose every new "
                "obligation and do not repeat a consumed message as a new discovery."
            ),
            ContinuationTurn,
            {"problem": problem, **context},
            temperature=0.25,
            # This is server-owned admission state. Checkpoint depth alone may not
            # escalate a route into a larger reasoning budget.
            output_tier=max(0, authorized_output_tier),
        )

    def route_skeptic(self, **context: Any) -> PromptBundle:
        return self._typed_stage(
            "route_skeptic",
            "Search first for the smallest counterexample, boundary failure, quantifier error, circular dependency, or first invalid step. Do not reward proof length and do not supply a replacement proof unless repair_request is true.",
            VerificationReport,
            context,
        )

    def route_referee(self, **context: Any) -> PromptBundle:
        return self._typed_stage(
            "route_referee",
            (
                "Judge only global admissibility, memory tier, scope, dependencies, "
                "quantifiers, and need for escalation. For every Claim in the "
                "artifact, place its exact claim_id in exactly one of "
                "accepted_claim_ids, rejected_claim_ids, or deferred_claim_ids. "
                "Artifact-level accepted=true does not accept an unmapped Claim. "
                "Do not invent new proof steps."
            ),
            BrokerDecision,
            context,
        )

    def route_tool_audit(self, **context: Any) -> PromptBundle:
        return self._typed_stage(
            "route_tool_audit",
            "Independently audit the supplied deterministic replay records and the mathematical mapping from each experiment to the candidate proof delta. Do not rerun arbitrary generated code, infer an unbounded theorem from bounded evidence, or pass a missing/invalid replay. Return pass only when every proof-relevant result was replayed and its scope is used correctly.",
            ToolAuditReport,
            context,
        )

    def post_failure_bottleneck(
        self,
        problem: ProblemContract,
        *,
        max_output_tokens: int,
        **context: Any,
    ) -> PromptBundle:
        return self._typed_stage(
            "post_failure_bottleneck",
            (
                "The previous route call returned no usable structured artifact. "
                "Diagnose only from the verified checkpoint, admitted typed public "
                "context, and the explicitly labeled non-authoritative working "
                "checkpoint. You cannot see, recover, summarize, or continue the "
                "failed call's private reasoning. Identify the smallest explicit "
                "mathematical claim now blocking progress. Prefer current_goal, then "
                "one remaining_subgoal; use a working-checkpoint gap only when clearly "
                "marked unverified. State the attempted public mechanism, why the "
                "public state does not close the claim, preserved verified IDs, and "
                "structurally different mechanisms for Inspiration to try. If the exact "
                "hidden failure point is unknowable, lower confidence rather than "
                "inventing hidden progress. This is a route-local diagnosis, not a "
                "proof, Claim, Fact, or verification result. Set "
                "exact_failed_internal_step_known=false and "
                "private_reasoning_recovered=false."
            ),
            PostFailureBottleneckDiagnostic,
            {"problem": problem, **context},
            temperature=0.0,
            output_tier=0,
            max_output_tokens=max_output_tokens,
        )

    def bridge_lemma(self, **context: Any) -> PromptBundle:
        return self._typed_stage(
            "bridge_lemma",
            "Prove only the shared obligation from the supplied verified facts and failure records. You do not receive or reconstruct full route transcripts.",
            MessageEnvelope,
            context,
            temperature=0.15,
        )

    def resolve_contradiction(self, **context: Any) -> PromptBundle:
        return self._typed_stage(
            "resolve_contradiction",
            "Return exactly one scoped resolution: A refutes B, B refutes A, different scopes, both unsupported, compatible after normalization, or requires tool/formal check.",
            MessageEnvelope,
            context,
        )

    def acknowledge_message(self, **context: Any) -> PromptBundle:
        return self._typed_stage(
            "acknowledge_message",
            "Return the supplied opaque receipt token, accepted/rejected status, actual-use flag, cited step IDs, affected obligation IDs, and a short reason. Do not compute or invent a semantic hash.",
            MessageReceipt,
            context,
        )

    def counterexample_search(self, **context: Any) -> PromptBundle:
        return self._typed_stage(
            "counterexample_search",
            "Try to falsify the exact scoped claim. A finite search that finds nothing is not verification; a candidate counterexample must be independently replayable.",
            MessageEnvelope,
            context,
        )

    def representation_switchboard(
        self, problem: ProblemContract, **context: Any
    ) -> PromptBundle:
        return self._typed_stage(
            "representation_switchboard",
            "Select an applicable alternative representation, not every representation. Prefer a supplied domain operator whose preconditions hold; copy its operator_id and preserve its obligations, reversibility requirements, fast failure tests, and known failure modes. Give a reversible object mapping, preserved properties, lost conditions, novelty source, and targeted open obligations.",
            RepresentationCandidate,
            {"problem": problem, **context},
            temperature=0.35,
        )

    def structural_analogy_search(self, **context: Any) -> PromptBundle:
        return self._typed_stage(
            "structural_analogy_search",
            "Use only the supplied verified local records. Map objects and operations, identify transferable bridge lemmas, and explicitly list non-transferable conditions and transfer risks. Return no analogy rather than fabricate a source.",
            AnalogyMapping,
            context,
            temperature=0.2,
        )

    def invent_auxiliary_construction(self, **context: Any) -> PromptBundle:
        return self._typed_stage(
            "invent_auxiliary_construction",
            "Define a new auxiliary object bound to open obligations. Prefer an applicable supplied domain construction operator and retain its explicit preconditions, generated obligations, reversibility requirements, suggested tools, falsification tests, and known failure modes. State the expected relation and proof-debt benefit.",
            ConstructionProposal,
            context,
            temperature=0.4,
        )

    def hypothesize_invariant(self, **context: Any) -> PromptBundle:
        return self._typed_stage(
            "hypothesize_invariant",
            "Propose only a candidate invariant or monovariant. Define state and legal operations, give the expression, check a nontrivial boundary case, and request independent falsification.",
            InvariantHypothesis,
            context,
            temperature=0.35,
        )

    def reverse_goal_analysis(self, **context: Any) -> PromptBundle:
        return self._typed_stage(
            "reverse_goal_analysis",
            "Maintain two bounded frontiers. The forward frontier may contain only supplied Broker-admitted Typed Facts; never infer Fact support from route prose. Build a backward frontier of sufficient claims from the target. A shared symbol, keyword, or high lexical overlap never establishes A implies B. Treat a forward Fact only as a candidate ingredient, preserve route scope and quantified domains, and explicitly state the variable mapping, applicability conditions, and all additional premises that would be needed to reach the backward target. When that cannot be justified, request a proof of the backward target from its scoped assumptions without claiming any forward Fact implies it. Do not label an unsupported claim as fact-supported.",
            ReverseGoalPlan,
            context,
            temperature=0.2,
        )

    def persistent_meta_strategy(self, **context: Any) -> PromptBundle:
        return self._typed_stage(
            "persistent_meta_strategy",
            "Choose one portfolio action from observable route scores, proof-debt history, verified gain, repeated first errors, redundancy, message utility, conflicts, and protected budget. Never modify FactMemory.",
            MetaStrategyDecision,
            context,
            temperature=0.1,
        )

    def surprise_exploration(self, **context: Any) -> PromptBundle:
        return self._typed_stage(
            "surprise_exploration",
            "Execute the supplied seeded SurpriseMutationDirective rather than merely choosing a lower-ranked representation. Copy the directive into the mutation field without changing its seed or operator_id, then instantiate a structurally different, obligation-targeted and quickly falsifiable proposal. Changed notation, wording, or an unrelated mutation is invalid.",
            InspirationProposal,
            context,
            temperature=0.5,
        )

    def inspiration_referee(self, **context: Any) -> PromptBundle:
        return self._typed_stage(
            "inspiration_referee",
            "Independently assess mechanism novelty, relevance, coherence, hidden assumptions, and immediate counterexamples. Novelty is not correctness and the proposal cannot become a Fact.",
            InspirationReview,
            context,
        )

    def assess_claim_goal_alignment(self, **context: Any) -> PromptBundle:
        return self._typed_stage(
            "proof_control_goal_alignment",
            (
                "Assess only whether the identified strategy, claim, message, or "
                "obligation is equivalent to, sufficient for, necessary-only for, "
                "heuristic-only for, unrelated to, or still unknown relative to the "
                "identified target obligation. Compare quantifier order, domains, "
                "uniformity, index range, and object scope. Give an implication "
                "outline and IDs of every bridge still required. Lexical overlap is "
                "not implication. Do not modify a Fact or close an obligation."
            ),
            ClaimGoalLink,
            context,
        )

    def find_minimal_sufficient_bridge(self, **context: Any) -> PromptBundle:
        return self._typed_stage(
            "proof_control_minimal_bridge",
            (
                "Given one overstrong target, propose a strictly weaker candidate "
                "only when it still suffices for the immutable main goal. State the "
                "strict strength relation, implication outline, remaining open "
                "obligations, and required bridges. The proposal is control metadata, "
                "not a Fact and not a replacement goal."
            ),
            MinimalBridgeProposal,
            context,
            temperature=0.1,
        )

    def review_inference_risk(self, **context: Any) -> PromptBundle:
        return self._typed_stage(
            "proof_control_inference_risk",
            (
                "Review one ambiguous inference-risk candidate. Preserve premise "
                "and conclusion IDs, quantifier order, domains, index scope, "
                "uniformity, and object scope. Clear the risk only with an explicit "
                "bridge; otherwise leave it open or identify a countermodel task. "
                "Do not change verification status, promote a Fact, or close an "
                "obligation."
            ),
            InferenceRiskRecord,
            context,
        )

    def review_bottleneck_cluster(self, **context: Any) -> PromptBundle:
        return self._typed_stage(
            "proof_control_bottleneck_cluster",
            (
                "Decide whether the supplied open obligations are the same "
                "mathematical bottleneck after accounting for assumptions, "
                "quantifier order, scope, dependency neighborhoods, and first-error "
                "fingerprints. Preserve every original node and edge. Select a "
                "canonical obligation only as sidecar scheduling metadata."
            ),
            BottleneckCluster,
            context,
        )

    def challenge_critical_assumption(self, **context: Any) -> PromptBundle:
        return self._typed_stage(
            "proof_control_assumption_challenge",
            (
                "Challenge one shared, unverified load-bearing assumption. Seek an "
                "exact counterexample, a route that avoids it, a weaker sufficient "
                "condition, or a direct proof. Select exactly one action. Return "
                "only a concise, auditable mathematical artifact with explicit "
                "dependencies and remaining gaps; do not expose private reasoning. "
                "An avoid or weaken action must include a complete alternative "
                "StrategyCard whose dependency closure omits the challenged family. "
                "Multiple routes agreeing is not evidence and must not change "
                "verification status."
            ),
            AssumptionChallengeProposal,
            context,
            temperature=0.1,
        )

    def review_critical_assumption_challenge(
        self,
        **context: Any,
    ) -> PromptBundle:
        return self._typed_stage(
            "proof_control_assumption_challenge_review",
            (
                "Independently audit one assumption-challenge proposal. Check exact "
                "scope, every proof dependency, any claimed counterexample, and "
                "whether an alternative route truly omits the challenged dependency "
                "family rather than merely rewording it. A finite non-refutation is "
                "inconclusive. Do not promote a Fact or close an obligation. Return "
                "a pass only for the specific action actually established."
            ),
            AssumptionChallengeReview,
            context,
            temperature=0.0,
        )

    def extract_abstract_structure_and_realizer(self, **context: Any) -> PromptBundle:
        return self._typed_stage(
            "proof_control_abstract_realizer",
            (
                "Separate the reusable abstract structure from one concrete "
                "realizer. Identify preserved constraints, removable components, "
                "candidate-specific admissibility and boundary conditions, a "
                "well-founded descent measure, and a fast falsification test. A "
                "failure of this realizer must not refute the abstract structure."
            ),
            AbstractRealizerExtraction,
            context,
            temperature=0.1,
        )

    def repair_realizer(self, **context: Any) -> PromptBundle:
        return self._typed_stage(
            "proof_control_realizer_repair",
            (
                "Apply exactly one requested structure-preserving repair operator "
                "to a failed concrete realizer. Retain the abstract proposal and "
                "required constraints, change the candidate construction, state "
                "admissibility and strict descent explicitly, and include a fast "
                "falsification test. The result is not a Fact."
            ),
            RealizerRepairResult,
            context,
            temperature=0.2,
        )

    def select_induction_or_descent_measure(self, **context: Any) -> PromptBundle:
        return self._typed_stage(
            "proof_control_induction_measure",
            (
                "Select one induction or descent measure tied to the supplied "
                "target obligations. State its well-founded domain, base cases, "
                "strict decrease, step relation, trigger features, and why ordinary "
                "induction on the ambient natural index is insufficient. Return a "
                "proposal, not a proved premise."
            ),
            InductionMeasureProposal,
            context,
            temperature=0.1,
        )

    def extract_near_miss(self, **context: Any) -> PromptBundle:
        return self._typed_stage(
            "proof_control_near_miss",
            (
                "Extract only the salvageable mathematical structure from a failed "
                "or uncertain verified attempt. Record the concrete candidate, "
                "preserved properties, first failed constraint, repair operators, "
                "and possible descent measures. The record is non-authoritative "
                "control memory and cannot be used as a premise."
            ),
            NearMissRecord,
            context,
        )

    def classify_proof_failure(self, **context: Any) -> PromptBundle:
        return self._typed_stage(
            "proof_control_failure_classification",
            (
                "Classify the first mathematical failure as execution, bridge, "
                "plan, or framing. Local calculation or boundary mistakes are "
                "execution failures; one explicit missing implication is a bridge "
                "failure; a route that cannot imply the goal is a plan failure; an "
                "overstrong target, wrong representation, shared false assumption, "
                "or fundamental scope mismatch is a framing failure. Preserve the "
                "legacy failure level and map to an existing ActionKind."
            ),
            FailureClassificationRecord,
            context,
        )

    def rewrite_proof_blueprint(self, **context: Any) -> PromptBundle:
        return self._typed_stage(
            "proof_control_blueprint_rewrite",
            (
                "Rewrite only the invalid route targets, bridge obligations, "
                "representation, and plan. Preserve every supplied verified Fact, "
                "verified proof step, refuted claim, and negative constraint by ID. "
                "Do not delete route history, promote a Fact, or close an obligation."
            ),
            BlueprintRewriteRequest,
            context,
            temperature=0.1,
        )
