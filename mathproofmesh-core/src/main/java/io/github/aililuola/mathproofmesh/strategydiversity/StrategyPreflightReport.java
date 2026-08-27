package io.github.aililuola.mathproofmesh.strategydiversity;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public record StrategyPreflightReport(
    String strategyId,
    String problemHash,
    List<CriticalClaimPreflightResult> claims,
    boolean hardRejected,
    boolean requiresRegeneration,
    double requiredClaimEvidenceCoverage,
    @JsonDeserialize(as = LinkedHashSet.class) Set<String> unresolvedRequiredClaimKeys,
    String reportHash) {
  public StrategyPreflightReport {
    strategyId = StrategySemanticNormalizer.require(strategyId, "strategyId");
    problemHash = StrategySemanticNormalizer.require(problemHash, "problemHash");
    claims = claims == null ? List.of() : List.copyOf(claims);
    if (!Double.isFinite(requiredClaimEvidenceCoverage)
        || requiredClaimEvidenceCoverage < 0.0d
        || requiredClaimEvidenceCoverage > 1.0d) {
      throw new IllegalArgumentException("requiredClaimEvidenceCoverage must be in [0,1]");
    }
    unresolvedRequiredClaimKeys =
        StrategyImmutableCollections.orderedSet(unresolvedRequiredClaimKeys);
    reportHash = StrategySemanticNormalizer.require(reportHash, "reportHash");
  }

  @Override
  public List<CriticalClaimPreflightResult> claims() {
    return List.copyOf(claims);
  }

  @Override
  public Set<String> unresolvedRequiredClaimKeys() {
    return StrategyImmutableCollections.orderedSet(unresolvedRequiredClaimKeys);
  }
}
