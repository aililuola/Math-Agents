package io.github.aililuola.mathproofmesh.strategydiversity;

import java.util.OptionalDouble;

public record StrategyFeasibilityScore(
    double rootGoalAlignment,
    double blueprintCompleteness,
    double requiredClaimEvidenceCoverage,
    double mechanismNovelty,
    double portfolioComplementarity,
    double commonModePenalty,
    double costPenalty,
    double modelPriorContribution,
    double total,
    OptionalDouble hardUpperBound) {
  public StrategyFeasibilityScore {
    unit(rootGoalAlignment, "rootGoalAlignment");
    unit(blueprintCompleteness, "blueprintCompleteness");
    unit(requiredClaimEvidenceCoverage, "requiredClaimEvidenceCoverage");
    unit(mechanismNovelty, "mechanismNovelty");
    unit(portfolioComplementarity, "portfolioComplementarity");
    unit(commonModePenalty, "commonModePenalty");
    unit(costPenalty, "costPenalty");
    unit(modelPriorContribution, "modelPriorContribution");
    unit(total, "total");
    hardUpperBound = hardUpperBound == null ? OptionalDouble.empty() : hardUpperBound;
    if (hardUpperBound.isPresent()) {
      unit(hardUpperBound.getAsDouble(), "hardUpperBound");
      if (total > hardUpperBound.getAsDouble() + 1.0e-12d) {
        throw new IllegalArgumentException("total exceeds hardUpperBound");
      }
    }
    if (total > 0.0d && modelPriorContribution / total > 0.10d + 1.0e-12d) {
      throw new IllegalArgumentException("model prior exceeds 10 percent of total");
    }
  }

  private static void unit(double value, String name) {
    if (!Double.isFinite(value) || value < 0.0d || value > 1.0d) {
      throw new IllegalArgumentException(name + " must be in [0,1]");
    }
  }
}
