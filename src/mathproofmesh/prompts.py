from __future__ import annotations

import json
from collections.abc import Mapping
from dataclasses import asdict, dataclass, is_dataclass
from enum import Enum
from pathlib import Path
from typing import Any, Type

from pydantic import BaseModel

from .schemas import (
    AnalogyMapping,
    BlindReviewPacket,
    BlindVerificationReport,
    BrokerDecision,
    ClaimBatch,
    ConstructionProposal,
    ContinuationTurn,
    ExperimentProgram,
    ExperimentSpec,
    FinalProof,
    InitialExplorationTurn,
    InspirationProposal,
    InspirationReview,
    InvariantHypothesis,
    MessageEnvelope,
    MessageReceipt,
    MetaReview,
    MetaStrategyDecision,
    ProblemContract,
    ProofAttempt,
    ProofCheckpoint,
    ProofDelta,
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


def _schema(model: Type[BaseModel]) -> str:
    schema = model.model_json_schema()
    example = _validated_model_example(model, schema)
    return (
        f"{json.dumps(schema, ensure_ascii=False, indent=2)}\n\n"
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
Return exactly one JSON object conforming to the supplied JSON Schema. Do not add markdown fences or prose outside the JSON object.
Inside JSON strings, escape every LaTeX backslash as `\\`; an unescaped backslash makes the whole artifact invalid.
""".strip()


class PromptFactory:
    def __init__(
        self, output_language: str = "zh-CN", *, computation_enabled: bool = False
    ) -> None:
        self.output_language = output_language
        self.computation_enabled = computation_enabled

    def triage(self, problem: ProblemContract) -> PromptBundle:
        user = f"""
[STAGE:triage]
Classify the problem and recommend a cost-aware reasoning mode. Do not attempt the full solution yet.
Assess whether the task needs direct solving, a claim-dependency DAG, or a hybrid. Identify likely failure modes and useful deterministic tools.
The final system output language is {self.output_language}.

IMMUTABLE PROBLEM CONTRACT:
{_json(problem)}

JSON SCHEMA:
{_schema(TriageResult)}
""".strip()
        return PromptBundle(
            "triage", COMMON_SYSTEM, user, TriageResult, temperature=0.0
        )

    def strategies(
        self,
        problem: ProblemContract,
        triage: TriageResult,
        count: int,
        prior_strategy_titles: list[str] | None = None,
        regulator_feedback: list[str] | None = None,
    ) -> PromptBundle:
        prior_strategy_titles = prior_strategy_titles or []
        regulator_feedback = regulator_feedback or []
        user = f"""
[STAGE:strategy_generation]
Generate up to {count} genuinely distinct and feasible solution strategies for the immutable problem.
The strategies must differ in their decisive mathematical mechanism, not merely wording or notation.
Do not pad the list with invented weak variants. If the mathematical space supports fewer sound approaches, return fewer.
For every strategy, state the bottleneck, the intended falsification test, the expected intermediate lemmas, and why it is independent of the others.
You may record narrowly described ComputationHint items for later consideration, but hints are non-executable and must not replace the strategy's abstract mathematical mechanism.
Avoid repeating previous directions unless regulator feedback explicitly asks for a repair.

IMMUTABLE PROBLEM CONTRACT:
{_json(problem)}

TRIAGE:
{_json(triage)}

PREVIOUS STRATEGY TITLES TO AVOID OR REVISE:
{_json(prior_strategy_titles)}

REGULATOR FEEDBACK:
{_json(regulator_feedback)}

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
    ) -> PromptBundle:
        targeted_feedback = targeted_feedback or []
        experiment_results = experiment_results or []
        computation_feedback = computation_feedback or []
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
Develop this direction as deeply as possible. A complete proof is preferred, but do not force a false conclusion: rigorous partial lemmas, a precise obstruction, or a proved dead end are valid progress.
Begin from the structural mathematical mechanism. Computation is not the default route: use no more than three representative hand checks, and never perform long enumeration in prose.
{computation_instruction}
The request's reasoning_basis is a short auditable mathematical rationale, not private chain of thought. Pattern discovery or broad search must set broad_search=true and may be deferred by policy. Never request computation merely to "look for a pattern" before doing abstract reasoning.
Choose a registered typed method whenever possible. sandboxed_python is permitted only as a last resort and requires typed_tool_gap to explain precisely why none of the registered tools can express the check.
For numeric_counterexample, set exact_arithmetic=false; only an independently re-substituted candidate may later become exact counterexample evidence.
Every non-routine decisive step must have is_key_step=true and be expanded into explicit substeps. Avoid words such as "obvious" or "clearly" in place of justification.
For each dependency, use a step_id or verified claim_id. Encode an external theorem as `external:<exact theorem name>` and state its hypotheses in the justification; never put a bare theorem title in dependencies. Do not use an unverified claim as a theorem.
State falsification checks and unresolved gaps explicitly.
The response must retain the problem_hash exactly as given and set agent_id={agent_id!r}, round_index={round_index}.

IMMUTABLE PROBLEM CONTRACT:
{_json(problem)}

ASSIGNED STRATEGY:
{_json(strategy)}

VERIFIED LEMMA LIBRARY (facts only; may be empty):
{_json(verified_claims)}

TARGETED REVIEW FEEDBACK FOR THIS PATH:
{_json(targeted_feedback)}

PREVIOUS ATTEMPT ON THIS SAME PATH (empty on first round):
{_json(previous_attempt or {})}

COMPUTATION DECISIONS FROM THIS SAME TURN (may include reject/defer):
{_json(computation_feedback)}

STRUCTURED EXPERIMENT RESULTS FROM THIS SAME PATH:
{_json(experiment_results)}

Interpret the mathematical consequence of any experiment result before submitting an attempt. not_refuted and bounded_evidence are not proofs. Do not place experimental output directly into proposed_lemmas or present it as a proved step.
After a confirmed counterexample, immediately correct or abandon the affected route and set experiment_impact to execution, plan, or strategy according to its scope.

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
    ) -> PromptBundle:
        targeted_feedback = targeted_feedback or []
        experiment_results = experiment_results or []
        computation_feedback = computation_feedback or []
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
Encode every external theorem dependency as `external:<exact theorem name>` and state its applicable hypotheses in the step justification. A bare theorem title is not a valid dependency ID.
Work on the checkpoint's current_goal first. Finish a coherent subgoal rather than emitting a long unfinished transcript.
Use abstract reasoning first and limit manual numerical examples to three representative checks. {computation_instruction}
Always prefer a registered typed method. A sandboxed_python request must be the isolated last resort and must fill typed_tool_gap.
For numeric_counterexample, set exact_arithmetic=false; sampled non-refutation is always heuristic.
CHECKPOINT POLICY: {checkpoint_policy}. When this is "verified_subgoal", completed_subgoal must explicitly name the coherent subgoal completed by this delta unless the full proof is complete or a contradiction with the checkpoint is being reported.
Set proof_complete=true only when the original immutable problem is fully solved, candidate_final_answer is self-contained, and remaining_subgoals is empty.
The response must retain problem_hash, path_id, strategy_id, parent_checkpoint_id, round_index, and segment_index exactly as supplied.
Reason privately, but output only the next auditable mathematical delta; never output hidden scratch work.

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

TARGETED FEEDBACK:
{_json(targeted_feedback)}

COMPUTATION DECISIONS FROM THIS SAME SEGMENT:
{_json(computation_feedback)}

STRUCTURED EXPERIMENT RESULTS FOR THIS SAME PARENT CHECKPOINT:
{_json(experiment_results)}

Explain the mathematical meaning of any result in the submitted delta. not_refuted and bounded_evidence cannot support a proof step by themselves, and no experiment may directly advance the checkpoint.
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
            output_tier=output_tier,
        )

    def route_prove(
        self,
        problem: ProblemContract,
        **context: Any,
    ) -> PromptBundle:
        checkpoint = context.get("checkpoint")
        if isinstance(checkpoint, ProofCheckpoint):
            output_tier = checkpoint.segment_index
        elif isinstance(checkpoint, Mapping):
            output_tier = int(checkpoint.get("segment_index", 0) or 0)
        else:
            output_tier = 0
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
                "proof. Put prior checkpoint references in "
                "delta.referenced_checkpoint_step_ids as string IDs, while "
                "delta.new_steps must contain complete ProofStep objects rather than "
                "bare IDs. For every broker_messages item, return exactly one "
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
            output_tier=max(0, output_tier),
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
            "Judge only global admissibility, memory tier, scope, dependencies, quantifiers, and need for escalation. Do not invent new proof steps.",
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
            "Select an applicable alternative representation, not every representation. Give a reversible object mapping, preserved properties, lost conditions, novelty source, targeted open obligations, and fast failure tests.",
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
            "Define a new auxiliary object bound to open obligations. State expected relations, proof-debt benefit, minimal falsification tests, and failure conditions.",
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
            "Work backward from the goal to sufficient intermediate claims, mark those supported by existing Facts, and isolate the smallest unsupported bridge gaps.",
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
            "Propose a high-novelty mechanism under the protected surprise budget. It must differ structurally, target an open obligation, and remain quickly falsifiable; changed notation or wording is invalid.",
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
