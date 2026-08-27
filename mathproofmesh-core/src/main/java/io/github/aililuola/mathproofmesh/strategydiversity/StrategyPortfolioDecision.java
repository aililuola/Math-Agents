package io.github.aililuola.mathproofmesh.strategydiversity;

import java.util.List;
import java.util.Map;

public record StrategyPortfolioDecision(
    String episodeId,
    List<String> selectedStrategyIds,
    Map<String, String> nonSelectionReasons,
    double objectiveValue,
    boolean requestedSizeMet,
    String decisionHash,
    List<StrategyPortfolioAuditEvent> audit) {
  public StrategyPortfolioDecision {
    episodeId = StrategySemanticNormalizer.require(episodeId, "episodeId");
    selectedStrategyIds =
        selectedStrategyIds == null ? List.of() : List.copyOf(selectedStrategyIds);
    nonSelectionReasons = nonSelectionReasons == null ? Map.of() : Map.copyOf(nonSelectionReasons);
    if (!Double.isFinite(objectiveValue) || objectiveValue < 0.0d) {
      throw new IllegalArgumentException("objectiveValue must be nonnegative");
    }
    decisionHash = StrategySemanticNormalizer.require(decisionHash, "decisionHash");
    audit = audit == null ? List.of() : List.copyOf(audit);
  }

  @Override
  public List<String> selectedStrategyIds() {
    return List.copyOf(selectedStrategyIds);
  }

  @Override
  public Map<String, String> nonSelectionReasons() {
    return Map.copyOf(nonSelectionReasons);
  }

  @Override
  public List<StrategyPortfolioAuditEvent> audit() {
    return List.copyOf(audit);
  }
}
