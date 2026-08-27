package io.github.aililuola.mathproofmesh.strategydiversity;

import java.util.List;

public record StrategySeed(
    String seedId,
    String mechanismHint,
    List<String> requiredClaimHints,
    String falsificationHint) {
  public StrategySeed {
    seedId = StrategySemanticNormalizer.require(seedId, "seedId");
    mechanismHint = StrategySemanticNormalizer.require(mechanismHint, "mechanismHint");
    requiredClaimHints =
        requiredClaimHints == null ? List.of() : List.copyOf(requiredClaimHints);
    falsificationHint = StrategySemanticNormalizer.require(falsificationHint, "falsificationHint");
  }

  @Override
  public List<String> requiredClaimHints() {
    return List.copyOf(requiredClaimHints);
  }
}
