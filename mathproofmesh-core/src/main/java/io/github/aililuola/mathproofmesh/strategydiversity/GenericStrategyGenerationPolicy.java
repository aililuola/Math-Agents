package io.github.aililuola.mathproofmesh.strategydiversity;

import java.util.List;
import java.util.Map;

public final class GenericStrategyGenerationPolicy {
  public Map<String, Object> guidance() {
    return Map.of(
        "mechanism_diversity",
        List.of(
            "Vary the mathematical object, representation, proof transformation, or dependency DAG, not merely the title or wording.",
            "Do not submit several routes that share the same unresolved load-bearing claim."),
        "critical_claim_contract",
        List.of(
            "Label each critical claim as REQUIRED or SUPPORTING.",
            "Separate already verified facts from unresolved assumptions.",
            "Declare claim-local assumptions, quantifiers, bindings, scope, and polarity in critical_claim_context_bindings."),
        "typed_mechanism_contract",
        List.of(
            "Declare mechanism_operations using only the bounded operation kind enum.",
            "Bind each operation to server-validated blueprint selectors such as @roots, @direct_targets, @all_intermediates, or @main_goal.",
            "Use unknown when no bounded operation kind is justified; prose never determines the hard mechanism identity."),
        "typed_reference_contract",
        Map.of(
            "mechanism_operation_selectors",
            List.of("@roots", "@direct_targets", "@all_intermediates", "@main_goal"),
            "critical_claim_node_selector",
            "@claim",
            "claim_local_assumption_selectors",
            List.of("@roots"),
            "rules",
            List.of(
                "Never invent blueprint node IDs; server-owned node IDs are not visible at generation time.",
                "Never use @all_intermediates in local_assumption_node_ids.",
                "For every critical claim emit exactly one binding with claim_blueprint_node_id=@claim.",
                "Do not repeat root assumptions, quantifiers, or variable bindings; emit only genuine claim-local additions.",
                "Bind each intended calculation check through the critical claim evidence_refs using the exact request_id.",
                "Unreferenced calculation checks cannot be bound later by the preflight model.")),
        "mechanism_operation_topology_contract",
        mechanismOperationTopologyContract(),
        "compact_output_contract",
        List.of(
            "Return exactly strategies_requested strategies and no extra variants.",
            "Keep statements and metadata concise; do not duplicate root context in each critical claim binding."),
        "falsification_contract",
        List.of(
            "Give a bounded falsification plan for each load-bearing claim.",
            "Include a genuinely independent challenger mechanism when applicable."));
  }

  public Map<String, Object> mechanismOperationTopologyContract() {
    return Map.of(
        "selectors_expand_to_sets",
        true,
        "server_reachability_rule",
        List.of(
            "Every selected input node must reach at least one selected output node.",
            "Every selected output node must be reachable from at least one selected input node."),
        "safe_generation_templates",
        List.of(
            operationTemplate("@roots", "@direct_targets"),
            operationTemplate("@direct_targets", "@main_goal"),
            operationTemplate("@roots", "@main_goal")),
        "forbidden_generation_edges",
        List.of(
            Map.of(
                "code",
                "DIRECT_TARGETS_TO_ALL_INTERMEDIATES_NOT_A_LAYER",
                "input_blueprint_node_ids",
                List.of("@direct_targets"),
                "output_blueprint_node_ids",
                List.of("@all_intermediates"),
                "reason",
                "@all_intermediates is the set of all lemma nodes, not a layer after @direct_targets.")));
  }

  private static Map<String, List<String>> operationTemplate(String input, String output) {
    return Map.of(
        "input_blueprint_node_ids", List.of(input),
        "output_blueprint_node_ids", List.of(output));
  }
}
