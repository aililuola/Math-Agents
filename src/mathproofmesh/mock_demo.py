from __future__ import annotations

import re
from typing import Any

from .config import (
    AgentConfig,
    BudgetConfig,
    ContinuationConfig,
    RuntimeConfig,
    SystemConfig,
)
from .llm.base import Message


def _user_text(messages: list[Message]) -> str:
    return "\n".join(
        message["content"] for message in messages if message["role"] == "user"
    )


def demo_responder(
    schema_name: str | None,
    messages: list[Message],
    schema: dict[str, Any] | None,
) -> dict[str, Any]:
    """A deterministic, schema-valid responder for smoke tests and the CLI demo."""
    text = _user_text(messages)
    if schema_name == "TriageResult":
        return {
            "problem_kind": "proof",
            "difficulty": "hard",
            "key_risks": ["induction hypothesis misuse", "problem statement drift"],
            "likely_tools": ["sympy_simplify"],
            "suggested_paths": 3,
            "suggested_rounds": 2,
            "proof_mode": "hybrid",
            "rationale": "Use independent direct, inductive, and telescoping paths, then audit each step.",
            "confidence": 0.9,
        }
    if schema_name == "StrategySet":
        return {
            "strategies": [
                {
                    "title": "Induction",
                    "core_idea": "Prove the identity by induction on n.",
                    "independence_basis": "Recursive proof",
                    "expected_lemmas": ["base case", "inductive increment"],
                    "bottleneck": "correctly expand the next odd term",
                    "prerequisites": [],
                    "key_original_step": "(n+1)^2=n^2+2n+1",
                    "falsification_test": "check n=1,2,3",
                    "estimated_success": 0.95,
                    "estimated_cost": 0.25,
                    "tags": ["algebra", "induction"],
                    "assigned_agent_id": None,
                },
                {
                    "title": "Telescoping squares",
                    "core_idea": "Use (k+1)^2-k^2=2k+1 and telescope.",
                    "independence_basis": "Finite-difference proof",
                    "expected_lemmas": ["difference of consecutive squares"],
                    "bottleneck": "index alignment",
                    "prerequisites": [],
                    "key_original_step": "sum consecutive differences",
                    "falsification_test": "expand the first three terms",
                    "estimated_success": 0.96,
                    "estimated_cost": 0.20,
                    "tags": ["algebra", "telescoping"],
                    "assigned_agent_id": None,
                },
                {
                    "title": "Geometric counting",
                    "core_idea": "Add odd border layers to form successive squares.",
                    "independence_basis": "Combinatorial/geometric proof",
                    "expected_lemmas": ["the kth border contains 2k-1 cells"],
                    "bottleneck": "formalizing the diagram without a picture",
                    "prerequisites": [],
                    "key_original_step": "border count",
                    "falsification_test": "draw 1x1, 2x2, 3x3 cases",
                    "estimated_success": 0.85,
                    "estimated_cost": 0.35,
                    "tags": ["combinatorics", "geometry"],
                    "assigned_agent_id": None,
                },
            ],
            "coverage_notes": "The paths use recursive, algebraic telescoping, and geometric mechanisms.",
            "omitted_directions": [],
        }
    if schema_name in {"ContinuationTurn", "ProofDelta"}:
        parent_match = re.search(r'"checkpoint_id"\s*:\s*"([^"]+)"', text)
        problem_match = re.search(r'"integrity_hash"\s*:\s*"([^"]+)"', text)
        path_match = re.search(r'"path_id"\s*:\s*"([^"]+)"', text)
        strategy_match = re.search(r'"strategy_id"\s*:\s*"([^"]+)"', text)
        segment_match = re.search(r"segment_index=(\d+)", text)
        round_match = re.search(r"round_index=(\d+)", text)
        agent_match = re.search(r"agent_id=\'([^\']+)\'", text)
        delta = {
            "problem_hash": problem_match.group(1) if problem_match else "0" * 64,
            "path_id": path_match.group(1) if path_match else "path_mock",
            "strategy_id": strategy_match.group(1)
            if strategy_match
            else "strategy_mock",
            "parent_checkpoint_id": parent_match.group(1)
            if parent_match
            else "checkpoint_mock",
            "agent_id": agent_match.group(1) if agent_match else "mock-explorer",
            "round_index": int(round_match.group(1)) if round_match else 0,
            "segment_index": int(segment_match.group(1)) if segment_match else 1,
            "completed_subgoal": "Establish the consecutive-square difference and telescope it.",
            "new_steps": [
                {
                    "step_id": f"seg{segment_match.group(1) if segment_match else '1'}_s1",
                    "statement": "For every k>=1, k^2-(k-1)^2=2k-1.",
                    "justification": "Expand the square and simplify.",
                    "dependencies": [],
                    "calculations": ["k^2-(k^2-2k+1)=2k-1"],
                    "citations": [],
                    "is_key_step": True,
                    "confidence": 0.99,
                },
                {
                    "step_id": f"seg{segment_match.group(1) if segment_match else '1'}_s2",
                    "statement": "Summing from k=1 to n telescopes to n^2.",
                    "justification": "All intermediate square terms cancel.",
                    "dependencies": [
                        f"seg{segment_match.group(1) if segment_match else '1'}_s1"
                    ],
                    "calculations": ["sum_{k=1}^n(k^2-(k-1)^2)=n^2"],
                    "citations": [],
                    "is_key_step": True,
                    "confidence": 0.99,
                },
            ],
            "new_claims": [],
            "active_assumptions": ["n is a positive integer"],
            "remaining_subgoals": [],
            "current_goal": None,
            "known_risks": [],
            "detected_conflicts": [],
            "candidate_final_answer": "For every positive integer n, 1+3+...+(2n-1)=n^2.",
            "proof_complete": True,
            "ready_for_verification": True,
            "self_confidence": 0.98,
            "raw_artifact_ref": None,
            "usage": {},
        }
        if schema_name == "ContinuationTurn":
            return {"action": "complete", "delta": delta, "reason": ""}
        return delta
    if schema_name in {"InitialExplorationTurn", "ProofAttempt"}:
        attempt = {
            "problem_hash": "0" * 64,
            "strategy_id": "strategy_mock",
            "agent_id": "mock-explorer",
            "round_index": 0,
            "status": "complete",
            "final_answer": "For every positive integer n, 1+3+...+(2n-1)=n^2.",
            "proof_steps": [
                {
                    "step_id": "s1",
                    "statement": "For every k>=1, k^2-(k-1)^2=2k-1.",
                    "justification": "Expand: k^2-(k^2-2k+1)=2k-1.",
                    "dependencies": [],
                    "calculations": ["k^2-(k-1)^2=2k-1"],
                    "citations": [],
                    "is_key_step": True,
                    "confidence": 0.99,
                },
                {
                    "step_id": "s2",
                    "statement": "Summing the identity in s1 from k=1 to n gives the desired sum.",
                    "justification": "The left side telescopes to n^2-0^2, while the right side is 1+3+...+(2n-1).",
                    "dependencies": ["s1"],
                    "calculations": ["sum_{k=1}^n (k^2-(k-1)^2)=n^2"],
                    "citations": [],
                    "is_key_step": True,
                    "confidence": 0.99,
                },
            ],
            "proposed_lemmas": [],
            "dead_ends": [],
            "unresolved_gaps": [],
            "falsification_checks": ["n=1 gives 1=1", "n=3 gives 9=9"],
            "self_confidence": 0.98,
            "raw_artifact_ref": None,
            "usage": {},
        }
        if schema_name == "InitialExplorationTurn":
            return {
                "action": "submit_attempt",
                "attempt": attempt,
                "reason": "",
            }
        return attempt
    if schema_name == "ClaimBatch":
        attempt_match = re.search(r'"attempt_id"\s*:\s*"([^"]+)"', text)
        attempt_id = attempt_match.group(1) if attempt_match else "attempt_mock"
        return {
            "attempt_id": attempt_id,
            "claims": [
                {
                    "statement": "The sum of the first n odd positive integers equals n squared.",
                    "assumptions": ["n is a positive integer"],
                    "conclusion": "sum_{k=1}^n (2k-1)=n^2",
                    "proof_steps": [
                        {
                            "step_id": "c1s1",
                            "statement": "2k-1=k^2-(k-1)^2",
                            "justification": "Algebraic expansion.",
                            "dependencies": [],
                            "calculations": [],
                            "citations": [],
                            "is_key_step": True,
                            "confidence": 0.99,
                        }
                    ],
                    "dependencies": [],
                    "status": "proposed",
                    "source_attempt_id": attempt_id,
                    "source_agent_id": "mock",
                    "evidence_refs": [],
                    "scope_limitations": ["positive integers n"],
                    "counterexample_risk": "low after algebraic proof",
                    "self_confidence": 0.98,
                    "verification_confidence": None,
                    "tags": ["telescoping", "identity"],
                }
            ],
            "reusable_insights": [
                "Express odd numbers as consecutive square differences."
            ],
            "discarded_material": [],
            "summary": "One reusable telescoping identity was extracted with source provenance.",
        }
    if schema_name == "ExperimentProgram":
        experiment_match = re.search(r"Set experiment_id='([^']+)'", text)
        return {
            "experiment_id": (
                experiment_match.group(1) if experiment_match else "experiment_mock"
            ),
            "source": (
                "def run(data):\n"
                "    return {'outcome': 'not_refuted', 'cases_checked': 0, "
                "'scope': {}, 'exact_arithmetic': True}\n"
            ),
            "input_schema": {
                "type": "object",
                "properties": {"seed": {"type": "integer"}},
                "required": ["seed"],
            },
            "output_schema": {
                "type": "object",
                "properties": {
                    "outcome": {"type": "string"},
                    "cases_checked": {"type": "integer"},
                    "scope": {"type": "object"},
                    "exact_arithmetic": {"type": "boolean"},
                },
                "required": ["outcome", "cases_checked", "scope", "exact_arithmetic"],
            },
            "dependencies": [],
        }
    if schema_name == "RepresentationCandidate":
        obligation_ids = re.findall(r'"obligation_id"\s*:\s*"([^"]+)"', text)
        target = obligation_ids[0] if obligation_ids else "obl_mock"
        return {
            "source_problem_hash": "0" * 64,
            "representation_name": "finite-state recurrence",
            "rewritten_problem_view": "Encode the evolving residue data as a finite state and prove the target on transitions.",
            "object_mapping": {"original configuration": "finite state"},
            "preserved_invariants": ["legal transitions", "target truth value"],
            "lost_conditions": ["geometric intuition is not retained automatically"],
            "new_candidate_tools": ["recurrence_check"],
            "expected_advantage": "The open step becomes a finite transition lemma.",
            "failure_risks": [
                "the proposed state may omit information needed by the target"
            ],
            "fast_failure_tests": [
                "find two configurations with one state but different target behavior"
            ],
            "novelty_signature": {
                "representation_tags": ["finite_state", "recurrence"],
                "mechanism_tags": ["representation_switch"],
                "core_objects": ["states", "transitions"],
                "key_transformations": ["encode"],
                "proof_principles": ["induction_on_transitions"],
                "targeted_obligation_ids": [target],
            },
        }
    if schema_name == "AnalogyMapping":
        return {
            "source_record_id": "verified-local-telescoping",
            "source_problem_summary": "A verified local proof reduces a sum to consecutive differences.",
            "target_problem_hash": "0" * 64,
            "object_correspondence": {"source term": "target term"},
            "operation_correspondence": {"finite summation": "finite summation"},
            "transferable_lemmas": ["consecutive differences telescope"],
            "non_transferable_conditions": [
                "the target term still needs an exact difference identity"
            ],
            "transfer_risks": ["matching syntax alone does not establish the identity"],
            "required_bridge_lemmas": [
                "derive the target consecutive-difference formula"
            ],
            "novelty_signature": {
                "representation_tags": ["difference_sequence"],
                "mechanism_tags": ["structural_analogy"],
                "core_objects": ["partial_sums"],
                "key_transformations": ["telescope"],
                "proof_principles": ["finite_sum_identity"],
                "targeted_obligation_ids": ["obl_mock"],
            },
        }
    if schema_name == "ConstructionProposal":
        obligation_ids = re.findall(r'"obligation_id"\s*:\s*"([^"]+)"', text)
        target = obligation_ids[0] if obligation_ids else "obl_mock"
        return {
            "construction_type": "auxiliary_sequence",
            "constructed_objects": ["partial-sum sequence S_k"],
            "definition": "Define S_k as the sum of the first k target terms, with S_0=0.",
            "intended_obligations": [target],
            "expected_invariant_or_relation": "S_k-S_{k-1} equals the kth target term.",
            "expected_proof_debt_reduction": "Replaces a global identity by one local recurrence and a base case.",
            "falsification_tests": [
                "check the recurrence exactly for k=1 and the first nontrivial boundary"
            ],
            "failure_conditions": [
                "the recurrence does not determine the requested quantity"
            ],
            "novelty_signature": {
                "representation_tags": ["sequence"],
                "mechanism_tags": ["auxiliary_construction"],
                "core_objects": ["partial_sums"],
                "key_transformations": ["take_difference"],
                "proof_principles": ["induction"],
                "targeted_obligation_ids": [target],
            },
        }
    if schema_name == "InvariantHypothesis":
        obligation_ids = re.findall(r'"obligation_id"\s*:\s*"([^"]+)"', text)
        target = obligation_ids[0] if obligation_ids else "obl_mock"
        return {
            "target_obligation_ids": [target],
            "state_definition": "The state is the current partial sum and index k.",
            "allowed_operations": ["append the next term"],
            "candidate_expression": "S_k-k^2",
            "behavior": "invariant",
            "boundary_case": "k=1",
            "boundary_result": "S_1-1=0",
            "falsification_request": "Check the transition from k to k+1 symbolically and search for a smallest counterexample.",
            "novelty_signature": {
                "representation_tags": ["state_process"],
                "mechanism_tags": ["invariant_hypothesis"],
                "core_objects": ["partial_sum", "index"],
                "key_transformations": ["append_term"],
                "proof_principles": ["invariant"],
                "targeted_obligation_ids": [target],
            },
        }
    if schema_name == "ReverseGoalPlan":
        obligation_ids = re.findall(r'"obligation_id"\s*:\s*"([^"]+)"', text)
        target = obligation_ids[0] if obligation_ids else "obl_mock"
        return {
            "target_obligation_id": target,
            "goal": "Close the selected proof obligation.",
            "sufficient_intermediate_claims": [
                "establish the exact one-step difference identity"
            ],
            "fact_supported_claims": [],
            "minimal_gaps": [
                "prove the one-step identity under the original assumptions"
            ],
            "bridge_requests": ["prove the one-step identity independently"],
            "novelty_signature": {
                "representation_tags": ["backward_reasoning"],
                "mechanism_tags": ["reverse_goal_analysis"],
                "core_objects": ["target", "bridge_lemma"],
                "key_transformations": ["weaken_goal"],
                "proof_principles": ["sufficient_condition"],
                "targeted_obligation_ids": [target],
            },
        }
    if schema_name == "MetaStrategyDecision":
        return {
            "round_index": 0,
            "action": "switch_representation",
            "affected_route_ids": [],
            "selected_mechanism": "representation_switch",
            "observable_metrics": {"verified_fact_gain_recent": 0},
            "reason": "The observable verified-gain signal is flat, so test a distinct representation.",
            "estimated_calls": 1,
        }
    if schema_name == "InspirationReview":
        proposal_ids = re.findall(r'"proposal_id"\s*:\s*"([^"]+)"', text)
        return {
            "proposal_id": proposal_ids[0] if proposal_ids else "inspiration_mock",
            "reviewer_agent_id": "mock-inspiration-referee",
            "semantically_distinct": True,
            "relevant_to_open_obligation": True,
            "internally_coherent": True,
            "hidden_assumptions": [],
            "immediate_counterexamples": [],
            "recommendation": "store_insight",
            "confidence": 0.9,
        }
    if schema_name == "BlindVerificationReport":
        return {
            "problem_integrity_ok": True,
            "verdict": "pass",
            "first_error_step": None,
            "issues": [],
            "checked_dependencies": ["f1", "f2"],
            "tool_requests": [],
            "tool_results": [],
            "failure_level": "none",
            "confidence": 0.96,
            "concise_feedback": "The immutable statement and each displayed proof step pass the independent audit.",
        }
    if schema_name == "VerificationReport":
        if "[STAGE:structural_verification]" in text:
            stage = "structural"
        elif "[STAGE:final_verification]" in text:
            stage = "final"
        else:
            stage = "detailed"
        if "[STAGE:checkpoint_verification]" in text:
            target_type = "proof_delta"
        elif "CURRENT FINAL PROOF" in text or (
            '"problem_hash"' in text
            and "SELECTED ATTEMPTS" not in text
            and "TARGET:" in text
            and '"answer"' in text
        ):
            target_type = "final_proof"
        else:
            target_type = "attempt"
        return {
            "target_id": "target_mock",
            "target_type": target_type,
            "agent_id": "mock-verifier",
            "stage": stage,
            "problem_integrity_ok": True,
            "verdict": "pass",
            "first_error_step": None,
            "issues": [],
            "checked_dependencies": ["s1", "s2"],
            "tool_requests": [],
            "tool_results": [],
            "failure_level": "none",
            "confidence": 0.96,
            "concise_feedback": "The statement is unchanged and each displayed algebraic/telescoping step is valid.",
            "raw_artifact_ref": None,
            "usage": {},
        }
    if schema_name == "MetaReview":
        attempt_ids = re.findall(r'"attempt_id"\s*:\s*"([^"]+)"', text)
        target = attempt_ids[0] if attempt_ids else None
        return {
            "selected_target_id": target,
            "assessments": [
                {
                    "target_id": target or "attempt_mock",
                    "score": 0.96,
                    "strengths": ["complete telescoping proof", "explicit key steps"],
                    "weaknesses": [],
                    "recommended_action": "synthesize",
                }
            ],
            "shared_agreements": [
                "Consecutive-square differences establish the identity."
            ],
            "unresolved_conflicts": [],
            "required_actions": [],
            "broad_computation_approved_strategy_ids": [],
            "failure_level": "none",
            "can_synthesize": True,
            "confidence": 0.96,
            "summary": "A complete independently verified path is ready for synthesis.",
        }
    if schema_name == "FinalProof":
        return {
            "problem_hash": "0" * 64,
            "answer": "For every positive integer n, the sum of the first n odd positive integers is n^2.",
            "proof_steps": [
                {
                    "step_id": "f1",
                    "statement": "For k=1,...,n, 2k-1=k^2-(k-1)^2.",
                    "justification": "Expanding the right-hand side gives k^2-(k^2-2k+1)=2k-1.",
                    "dependencies": [],
                    "calculations": ["k^2-(k-1)^2=2k-1"],
                    "citations": [],
                    "is_key_step": True,
                    "confidence": 0.99,
                },
                {
                    "step_id": "f2",
                    "statement": "Summing f1 over k=1,...,n yields sum_{k=1}^n(2k-1)=n^2.",
                    "justification": "The consecutive square differences telescope: (1^2-0^2)+...+(n^2-(n-1)^2)=n^2.",
                    "dependencies": ["f1"],
                    "calculations": [],
                    "citations": [],
                    "is_key_step": True,
                    "confidence": 0.99,
                },
            ],
            "dependencies": [],
            "caveats": [],
            "source_attempt_ids": [],
            "confidence": 0.98,
        }
    raise ValueError(f"demo responder has no payload for schema {schema_name}")


def build_demo_config(run_root: str = "runs") -> SystemConfig:
    agents = [
        AgentConfig(
            id="planner",
            provider="mock",
            model="mock",
            roles=[
                "planner",
                "meta_reviewer",
                "experimenter",
                "meta_strategist",
                "inspiration_referee",
                "conflict_resolver",
            ],
        ),
        AgentConfig(
            id="explorer-a",
            provider="mock",
            model="mock",
            roles=[
                "explorer",
                "summarizer",
                "route_prover",
                "route_skeptic",
                "analogy_agent",
                "invariant_hypothesis_agent",
            ],
        ),
        AgentConfig(
            id="explorer-b",
            provider="mock",
            model="mock",
            roles=[
                "explorer",
                "summarizer",
                "route_prover",
                "construction_inventor",
                "representation_switchboard",
                "reverse_goal_analyzer",
            ],
        ),
        AgentConfig(
            id="verifier-a",
            provider="mock",
            model="mock",
            roles=[
                "structural_verifier",
                "detailed_verifier",
                "final_verifier",
                "route_referee",
                "tool_specialist",
                "bridge_prover",
                "counterexample_hunter",
            ],
        ),
        AgentConfig(
            id="verifier-b",
            provider="mock",
            model="mock",
            roles=[
                "structural_verifier",
                "detailed_verifier",
                "final_verifier",
                "route_referee",
                "route_skeptic",
                "inspiration_referee",
            ],
        ),
        AgentConfig(
            id="synthesizer",
            provider="mock",
            model="mock",
            roles=["synthesizer", "final_verifier"],
        ),
    ]
    return SystemConfig(
        agents=agents,
        budget=BudgetConfig(
            max_total_calls=32,
            max_rounds=2,
            initial_paths=3,
            max_paths=4,
            strategies_to_generate=3,
            candidates_to_verify=2,
            max_revisions=1,
            base_verifier_replicas=1,
            high_risk_verifier_replicas=2,
        ),
        continuation=ContinuationConfig(enabled=False),
        runtime=RuntimeConfig(run_root=run_root, parse_retries=0, request_retries=0),
    )


def demo_responders(config: SystemConfig) -> dict[str, Any]:
    return {agent.id: demo_responder for agent in config.agents if agent.enabled}
