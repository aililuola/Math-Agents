from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any, Type

from pydantic import BaseModel

from .schemas import (
    ClaimBatch,
    FinalProof,
    MetaReview,
    ProblemContract,
    ProofAttempt,
    ProofCheckpoint,
    ProofDelta,
    StrategySet,
    TriageResult,
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


def _json(value: Any) -> str:
    if isinstance(value, BaseModel):
        value = value.model_dump(mode="json")
    return json.dumps(value, ensure_ascii=False, indent=2)


def _schema(model: Type[BaseModel]) -> str:
    return json.dumps(model.model_json_schema(), ensure_ascii=False, indent=2)


COMMON_SYSTEM = """
You are one component in a verification-first mathematical reasoning system.
The original problem statement is immutable: never change a quantifier, hypothesis, domain, requested conclusion, or definition.
Reason privately, but output only explicit, auditable mathematical claims and proof steps. Do not output hidden scratch work.
A confidence value is metadata, not evidence. Never treat another agent's confidence as proof.
Never invent a theorem or bibliographic citation. A standard named theorem may be used without a bibliographic source location only when the exact invoked form is stated and every hypothesis is explicitly verified from prior steps; otherwise mark the use unverified.
Distinguish rigorously proved facts, plausible conjectures, failed directions, and unresolved gaps.
Return exactly one JSON object conforming to the supplied JSON Schema. Do not add markdown fences or prose outside the JSON object.
""".strip()


class PromptFactory:
    def __init__(self, output_language: str = "zh-CN") -> None:
        self.output_language = output_language

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
    ) -> PromptBundle:
        targeted_feedback = targeted_feedback or []
        user = f"""
[STAGE:independent_exploration]
You are explorer {agent_id}, assigned exactly one strategy. During initial exploration, ignore all other candidate solutions so that diversity is preserved.
Develop this direction as deeply as possible. A complete proof is preferred, but do not force a false conclusion: rigorous partial lemmas, a precise obstruction, or a proved dead end are valid progress.
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

REMAINING GLOBAL CALL BUDGET: {remaining_call_budget}
OUTPUT LANGUAGE: {self.output_language}

JSON SCHEMA:
{_schema(ProofAttempt)}
""".strip()
        return PromptBundle(
            "independent_exploration",
            COMMON_SYSTEM,
            user,
            ProofAttempt,
            temperature=0.45,
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
    ) -> PromptBundle:
        targeted_feedback = targeted_feedback or []
        user = f"""
[STAGE:proof_continuation]
You are explorer {agent_id}. Continue one proof path from a verified external checkpoint.
The checkpoint is authoritative mathematical state, not a suggestion. Do not re-prove committed steps unless you identify an explicit contradiction; if a contradiction exists, report it in detected_conflicts and do not silently overwrite the checkpoint.
Produce at most {max_new_steps} new logically complete proof steps and at most {max_new_claims} new reusable claims. Each new step must name all dependencies and may depend only on committed step IDs, verified claim IDs, explicit external theorems, or earlier steps in this same delta.
Encode every external theorem dependency as `external:<exact theorem name>` and state its applicable hypotheses in the step justification. A bare theorem title is not a valid dependency ID.
Work on the checkpoint's current_goal first. Finish a coherent subgoal rather than emitting a long unfinished transcript.
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

VERIFIED LEMMA LIBRARY:
{_json(verified_claims)}

TARGETED FEEDBACK:
{_json(targeted_feedback)}

AUTHORITATIVE IDS:
agent_id={agent_id!r}
round_index={round_index}
segment_index={segment_index}
parent_checkpoint_id={checkpoint.checkpoint_id!r}
REMAINING GLOBAL CALL BUDGET: {remaining_call_budget}
OUTPUT LANGUAGE: {self.output_language}

JSON SCHEMA:
{_schema(ProofDelta)}
""".strip()
        return PromptBundle(
            "proof_continuation",
            COMMON_SYSTEM,
            user,
            ProofDelta,
            temperature=0.25,
        )

    def verify_delta(
        self,
        problem: ProblemContract,
        strategy: dict[str, Any],
        checkpoint: ProofCheckpoint,
        delta: ProofDelta,
        verifier_id: str,
        verified_claims: list[dict[str, Any]],
    ) -> PromptBundle:
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
        stage: str = "detailed",
    ) -> PromptBundle:
        tool_results = tool_results or []
        user = f"""
[STAGE:{stage}_verification]
You are independent detailed verifier {verifier_id}. Check the mathematical process, not merely the final answer.
For every proof step: restate the exact assertion, identify its dependencies, test whether the justification implies it, verify algebra/inequalities/case coverage, and inspect boundary conditions.
Locate the first invalid or unjustified step. A later correct conclusion does not repair an earlier gap.
Try actively to falsify decisive claims by small cases, extremal cases, dimensional checks, substitutions, or counterexamples.
Use the verified lemma library only when every hypothesis is matched explicitly.
When a deterministic calculation would materially resolve uncertainty, emit a narrowly scoped ToolRequest. Never request arbitrary code execution.
Return PASS only when every required step is supported. Use UNCERTAIN rather than guessing when a deep theorem or computation remains unverified.

IMMUTABLE PROBLEM CONTRACT:
{_json(problem)}

STRUCTURAL REPORT:
{_json(structural_report)}

VERIFIED LEMMA LIBRARY:
{_json(verified_claims)}

AVAILABLE TOOL RESULTS:
{_json(tool_results)}

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
    ) -> PromptBundle:
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
