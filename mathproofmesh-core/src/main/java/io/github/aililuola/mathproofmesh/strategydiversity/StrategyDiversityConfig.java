package io.github.aililuola.mathproofmesh.strategydiversity;

public record StrategyDiversityConfig(
    int maxExactPortfolioCandidates,
    int minPortfolioSize,
    double unknownRequiredClaimUpperBound,
    double rootGoalAlignmentWeight,
    double blueprintCompletenessWeight,
    double requiredClaimEvidenceWeight,
    double mechanismNoveltyWeight,
    double portfolioComplementarityWeight,
    double commonModePenaltyWeight,
    double costPenaltyWeight,
    double maximumModelPriorContribution,
    double minimumAdmissibleFeasibility,
    double minimumBlueprintCompleteness,
    double minimumRequiredClaimEvidenceForPrimaryRoute) {
  public StrategyDiversityConfig {
    if (maxExactPortfolioCandidates < 1 || minPortfolioSize < 1) {
      throw new IllegalArgumentException("portfolio sizes must be positive");
    }
    unit(unknownRequiredClaimUpperBound, "unknownRequiredClaimUpperBound");
    unit(rootGoalAlignmentWeight, "rootGoalAlignmentWeight");
    unit(blueprintCompletenessWeight, "blueprintCompletenessWeight");
    unit(requiredClaimEvidenceWeight, "requiredClaimEvidenceWeight");
    unit(mechanismNoveltyWeight, "mechanismNoveltyWeight");
    unit(portfolioComplementarityWeight, "portfolioComplementarityWeight");
    unit(commonModePenaltyWeight, "commonModePenaltyWeight");
    unit(costPenaltyWeight, "costPenaltyWeight");
    unit(maximumModelPriorContribution, "maximumModelPriorContribution");
    unit(minimumAdmissibleFeasibility, "minimumAdmissibleFeasibility");
    unit(minimumBlueprintCompleteness, "minimumBlueprintCompleteness");
    unit(
        minimumRequiredClaimEvidenceForPrimaryRoute,
        "minimumRequiredClaimEvidenceForPrimaryRoute");
    if (maximumModelPriorContribution > 0.10d) {
      throw new IllegalArgumentException("model prior contribution cannot exceed 10 percent");
    }
  }

  public static StrategyDiversityConfig defaults() {
    return new StrategyDiversityConfig(
        20,
        2,
        0.45d,
        0.15d,
        0.15d,
        0.25d,
        0.15d,
        0.15d,
        0.20d,
        0.05d,
        0.10d,
        0.30d,
        0.20d,
        0.0d);
  }

  public StrategyDiversityConfig withQualityGate(
      double feasibility, double blueprintCompleteness, double requiredClaimEvidence) {
    return new StrategyDiversityConfig(
        maxExactPortfolioCandidates,
        minPortfolioSize,
        unknownRequiredClaimUpperBound,
        rootGoalAlignmentWeight,
        blueprintCompletenessWeight,
        requiredClaimEvidenceWeight,
        mechanismNoveltyWeight,
        portfolioComplementarityWeight,
        commonModePenaltyWeight,
        costPenaltyWeight,
        maximumModelPriorContribution,
        feasibility,
        blueprintCompleteness,
        requiredClaimEvidence);
  }

  private static void unit(double value, String name) {
    if (!Double.isFinite(value) || value < 0.0d || value > 1.0d) {
      throw new IllegalArgumentException(name + " must be in [0,1]");
    }
  }
}
