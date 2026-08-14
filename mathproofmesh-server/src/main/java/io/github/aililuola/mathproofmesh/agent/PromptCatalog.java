package io.github.aililuola.mathproofmesh.agent;

import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Map;

public final class PromptCatalog {
  public static final String COMMON_SYSTEM =
      """
      You are one component in a verification-first mathematical reasoning system.
      The original problem statement is immutable: never change a quantifier, hypothesis, domain, requested conclusion, or definition.
      Return only explicit, auditable mathematical claims and proof steps. Never output hidden scratch work or private chain of thought.
      Confidence is metadata, not evidence. Distinguish proved facts, conjectures, failed directions, and unresolved gaps.
      A finite sample or failure to find a counterexample never verifies a universal claim.
      Never invent a theorem or bibliographic citation. State every invoked theorem form and verify every hypothesis.
      Leave server-owned cryptographic hashes empty.
      Return exactly one JSON object conforming to the requested Java contract, with no markdown fence or surrounding prose.
      """.strip();

  public static final String JSON_REPAIR_SYSTEM =
      """
      Repair only the JSON representation of the supplied public answer.
      You may repair an outer wrapper, field name, omitted optional field, JSON escaping, or a schema-compatible scalar type.
      Do not add, remove, paraphrase, strengthen, weaken, or otherwise change any mathematical statement, assumption, formula, dependency, conclusion, or proof step.
      Return exactly one JSON object and no prose.
      """.strip();

  private static final Map<String, String> INSTRUCTIONS = instructions();

  private PromptCatalog() {}

  public static String instruction(String stage) {
    String instruction = INSTRUCTIONS.get(stage);
    if (instruction == null) {
      throw new IllegalArgumentException("unknown prompt stage: " + stage);
    }
    return instruction;
  }

  public static Map<String, String> stages() {
    return Collections.unmodifiableMap(new LinkedHashMap<>(INSTRUCTIONS));
  }

  private static Map<String, String> instructions() {
    Map<String, String> stages = new LinkedHashMap<>();
    stages.put("goal_normalization", "Inspect ambiguity without solving or silently rewriting the goal.");
    stages.put(
        "triage",
        "Classify the immutable problem and recommend a cost-aware proof mode without solving it. "
            + "For a CJK source, return a non-authoritative English semantic_view_candidate; "
            + "its preserves_* flags are self-reports and never authorize changing the root goal.");
    stages.put("statement_normalization", "Restate each proposition explicitly without changing its mathematics.");
    stages.put("strategy_generation", "Generate genuinely distinct mechanisms and expose each load-bearing claim and falsification test. estimated_success and estimated_cost are normalized scores from 0.0 to 1.0, never percentages or token counts. calculation_checks accepts only typed ToolRequest kinds from its schema; sandboxed_python belongs in computation_hints and a later ExperimentSpec, never in calculation_checks.");
    stages.put("independent_exploration", "Develop only the assigned isolated route and report honest partial progress or a complete proof. Prefer registered typed computation methods. sandboxed_python is a last resort: arguments must contain exactly one input field whose value is the complete bounded JSON object supplied to run(data), domains must be empty, and typed_tool_gap must state why no typed method applies.");
    stages.put("proof_continuation", "Continue only from the verified checkpoint with a bounded auditable proof delta.");
    stages.put("route_prove", "Continue the assigned route from verified state; Insights are hints and never premises.");
    stages.put("checkpoint_verification", "Independently verify identity, dependencies, each inference, and checkpoint state before commit.");
    stages.put("claim_extraction", "Extract only reusable claims whose proofs are present. Dependency kinds are exactly \"local_claim\", \"local_step\", and \"external_result\". Never emit the legacy kind \"external\".");
    stages.put("structural_verification", "Check problem integrity, completeness, dependencies, theorem admission, and hidden steps first.");
    stages.put("detailed_verification", "Audit every inference and mount a concrete falsification attempt against the weakest key step.");
    stages.put(
        "claim_salvage_review",
        "Review each bounded candidate claim independently of its attempt and route verdict. "
            + "Return exactly one decision per supplied claim, verify scope and quantifiers, and "
            + "check an explicit witness before accepting a counterexample.");
    stages.put("final_verification", "Independently audit the final proof against the immutable problem.");
    stages.put("blind_structural_verification", "Use only the supplied packet and independently audit proof structure.");
    stages.put("blind_detailed_verification", "Audit every step using only the blind packet and admitted evidence.");
    stages.put("meta_review", "Act as review chair; do not convert votes or confidence into mathematical truth.");
    stages.put("synthesis", "Build one coherent self-contained solution only from supported material.");
    stages.put("final_revision", "Repair focused verified defects without weakening the original problem.");
    stages.put("route_skeptic", "Search first for the smallest counterexample, scope failure, circularity, or first invalid step.");
    stages.put("route_referee", "Judge global admissibility, memory tier, scope, and dependencies without inventing proof steps.");
    stages.put("route_tool_audit", "Audit deterministic replay and the exact mathematical mapping of each result.");
    stages.put("post_failure_bottleneck", "Diagnose the smallest public-state bottleneck without reconstructing private reasoning.");
    stages.put("bridge_lemma", "Prove only the shared obligation from verified facts and failure records.");
    stages.put(
        "obligation_family_review",
        "Classify only whether each supplied canonical target shares an upstream research "
            + "bottleneck, refines it, or offers an alternative proof plan. This scheduling "
            + "review never establishes mathematical equivalence and cannot close or refute a target.");
    stages.put("resolve_contradiction", "Return one scoped contradiction resolution and preserve both provenances.");
    stages.put("acknowledge_message", "Return the opaque receipt and report actual verified use without inventing a hash.");
    stages.put("counterexample_search", "Try to falsify the exact scoped claim; non-refutation is not verification.");
    stages.put("representation_switchboard", "Select one reversible representation and preserve obligations and failure tests.");
    stages.put("structural_analogy_search", "Map only verified local structures and expose non-transferable conditions.");
    stages.put("invent_auxiliary_construction", "Define one obligation-targeted auxiliary object and a fast falsification test.");
    stages.put("hypothesize_invariant", "Propose a candidate invariant with state, operations, boundary, and falsification request.");
    stages.put("reverse_goal_analysis", "Keep forward verified facts and backward sufficient claims separate; lexical overlap is not implication.");
    stages.put("persistent_meta_strategy", "Choose one portfolio action from observable persisted evidence without modifying Fact memory.");
    stages.put(
        "semantic_pivot_proposal",
        "Propose one bounded, non-authoritative draft of a structural strategy-state delta from "
            + "the supplied immutable root, "
            + "trusted obstruction references, and authority projections. Do not claim a pivot ID, "
            + "hash, verified claim, Fact, refutation, or permanent negative authority.");
    stages.put(
        "semantic_pivot_review",
        "Independently review exactly one compiled semantic pivot for obstruction binding, real "
            + "state change, root-goal preservation, and authority boundaries. This review does not "
            + "verify any proposed mathematical claim.");
    stages.put("surprise_exploration", "Execute the supplied seeded mutation and return a structurally different falsifiable proposal.");
    stages.put("inspiration_referee", "Assess novelty and relevance independently; novelty is never correctness.");
    stages.put("experiment_codegen", "Produce only the smallest bounded deterministic sandbox program under the admitted contract.");
    stages.put("computation_contract_repair", "Compile exactly one malformed bounded computation request; this is not a proof-writing stage. Change only method, domains, arguments, exact_arithmetic, and typed_tool_gap. Preserve experiment_id, purpose, target_claim, assumptions, reasoning_basis, why_computation_is_needed, decision_if_confirmed, decision_if_refuted, noncomputational_alternative, broad_search, max_cases, and seed exactly. Prefer a registered typed method only when it expresses the complete request. For sandboxed_python, arguments.input must be the complete bounded JSON object. Abandon the request when no semantics-preserving bounded contract exists.");
    stages.put("pattern_conjecture_completion", "Convert bounded pattern evidence into a scoped falsifiable conjecture, never a proof.");
    stages.put("proof_control_goal_alignment", "Classify exact logical alignment while preserving quantifiers, domains, and scope.");
    stages.put("proof_control_minimal_bridge", "Propose a strictly weaker target only when it still suffices for the immutable goal.");
    stages.put("proof_control_inference_risk", "Clear an inference risk only with an explicit bridge or countermodel audit.");
    stages.put("proof_control_bottleneck_cluster", "Cluster only mathematically equivalent bottlenecks while preserving every node and edge.");
    stages.put("proof_control_countermodel_search", "Try to refute the exact scoped inference with independently replayable evidence.");
    stages.put("proof_control_countermodel_review", "Independently verify every premise, substitution, quantifier, and scope of a countermodel.");
    stages.put("proof_control_assumption_challenge", "Challenge one shared unverified load-bearing assumption with one auditable action.");
    stages.put("proof_control_assumption_challenge_review", "Independently audit the exact assumption challenge and any alternative dependency closure.");
    stages.put("proof_control_abstract_realizer", "Separate reusable abstract structure from one concrete realizer and its admissibility.");
    stages.put("proof_control_realizer_repair", "Apply exactly one structure-preserving repair to the failed realizer.");
    stages.put("proof_control_induction_measure", "Propose one well-founded induction or descent measure with explicit decrease.");
    stages.put("proof_control_near_miss", "Extract salvageable non-authoritative structure from a failed or uncertain attempt.");
    stages.put("proof_control_failure_classification", "Classify the first failure as execution, bridge, plan, or framing.");
    stages.put("proof_control_blueprint_rewrite", "Rewrite only invalid targets, bridges, representation, and plan while preserving verified history.");
    return Map.copyOf(stages);
  }
}
