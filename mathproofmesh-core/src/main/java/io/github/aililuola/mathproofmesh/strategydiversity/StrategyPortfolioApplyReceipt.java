package io.github.aililuola.mathproofmesh.strategydiversity;

import java.util.List;

public record StrategyPortfolioApplyReceipt(
    String receiptId,
    String planId,
    List<String> admittedStrategyIds,
    List<String> createdRouteIds,
    String activeStateHash) {
  public StrategyPortfolioApplyReceipt {
    receiptId = StrategySemanticNormalizer.require(receiptId, "receiptId");
    planId = StrategySemanticNormalizer.require(planId, "planId");
    admittedStrategyIds =
        admittedStrategyIds == null ? List.of() : List.copyOf(admittedStrategyIds);
    createdRouteIds = createdRouteIds == null ? List.of() : List.copyOf(createdRouteIds);
    activeStateHash = StrategySemanticNormalizer.require(activeStateHash, "activeStateHash");
  }

  @Override
  public List<String> admittedStrategyIds() {
    return List.copyOf(admittedStrategyIds);
  }

  @Override
  public List<String> createdRouteIds() {
    return List.copyOf(createdRouteIds);
  }
}
