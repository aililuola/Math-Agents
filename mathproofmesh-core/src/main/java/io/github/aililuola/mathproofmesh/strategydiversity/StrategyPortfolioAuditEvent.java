package io.github.aililuola.mathproofmesh.strategydiversity;

import java.util.List;

public record StrategyPortfolioAuditEvent(
    String eventId,
    String episodeId,
    String action,
    List<String> strategyIds,
    String detail) {
  public StrategyPortfolioAuditEvent {
    eventId = StrategySemanticNormalizer.require(eventId, "eventId");
    episodeId = StrategySemanticNormalizer.require(episodeId, "episodeId");
    action = StrategySemanticNormalizer.require(action, "action");
    strategyIds = strategyIds == null ? List.of() : List.copyOf(strategyIds);
    detail = detail == null ? "" : detail.strip();
  }

  @Override
  public List<String> strategyIds() {
    return List.copyOf(strategyIds);
  }
}
