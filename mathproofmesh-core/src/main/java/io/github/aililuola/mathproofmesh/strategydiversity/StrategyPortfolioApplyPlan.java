package io.github.aililuola.mathproofmesh.strategydiversity;

import java.util.List;

public record StrategyPortfolioApplyPlan(
    String planId,
    String episodeId,
    String problemHash,
    String rootGoalHash,
    List<String> selectedStrategyIds,
    String decisionHash) {
  public StrategyPortfolioApplyPlan {
    planId = StrategySemanticNormalizer.require(planId, "planId");
    episodeId = StrategySemanticNormalizer.require(episodeId, "episodeId");
    problemHash = StrategySemanticNormalizer.require(problemHash, "problemHash");
    rootGoalHash = StrategySemanticNormalizer.require(rootGoalHash, "rootGoalHash");
    selectedStrategyIds =
        selectedStrategyIds == null ? List.of() : List.copyOf(selectedStrategyIds);
    decisionHash = StrategySemanticNormalizer.require(decisionHash, "decisionHash");
  }

  @Override
  public List<String> selectedStrategyIds() {
    return List.copyOf(selectedStrategyIds);
  }
}
