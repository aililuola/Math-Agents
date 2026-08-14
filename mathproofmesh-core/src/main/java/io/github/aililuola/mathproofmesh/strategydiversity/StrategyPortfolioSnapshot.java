package io.github.aililuola.mathproofmesh.strategydiversity;

import java.util.Map;

public record StrategyPortfolioSnapshot(
    int schemaVersion,
    Map<String, StrategyPortfolioDecision> decisions,
    Map<String, StrategyPortfolioApplyReceipt> receipts,
    long version) {
  public static final int CURRENT_SCHEMA_VERSION = 1;

  public StrategyPortfolioSnapshot {
    decisions = decisions == null ? Map.of() : Map.copyOf(decisions);
    receipts = receipts == null ? Map.of() : Map.copyOf(receipts);
    if (version < 0L) {
      throw new IllegalArgumentException("version must be nonnegative");
    }
  }

  public static StrategyPortfolioSnapshot empty() {
    return new StrategyPortfolioSnapshot(CURRENT_SCHEMA_VERSION, Map.of(), Map.of(), 0L);
  }

  @Override
  public Map<String, StrategyPortfolioDecision> decisions() {
    return Map.copyOf(decisions);
  }

  @Override
  public Map<String, StrategyPortfolioApplyReceipt> receipts() {
    return Map.copyOf(receipts);
  }
}
