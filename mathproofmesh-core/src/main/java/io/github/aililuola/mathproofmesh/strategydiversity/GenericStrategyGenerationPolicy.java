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
            "Separate already verified facts from unresolved assumptions."),
        "falsification_contract",
        List.of(
            "Give a bounded falsification plan for each load-bearing claim.",
            "Include a genuinely independent challenger mechanism when applicable."));
  }
}
