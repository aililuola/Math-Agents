package io.github.aililuola.mathproofmesh.strategydiversity;

import java.util.Set;

public record CommonModeRiskRecord(
    String criticalClaimKey,
    Set<String> affectedStrategyIds,
    CriticalClaimPreflightStatus evidenceStatus,
    String necessity,
    String resolution) {
  public CommonModeRiskRecord {
    criticalClaimKey = StrategySemanticNormalizer.require(criticalClaimKey, "criticalClaimKey");
    affectedStrategyIds =
        affectedStrategyIds == null ? Set.of() : Set.copyOf(affectedStrategyIds);
    evidenceStatus = java.util.Objects.requireNonNull(evidenceStatus, "evidenceStatus");
    necessity = StrategySemanticNormalizer.require(necessity, "necessity");
    resolution = StrategySemanticNormalizer.require(resolution, "resolution");
  }

  @Override
  public Set<String> affectedStrategyIds() {
    return Set.copyOf(affectedStrategyIds);
  }
}
