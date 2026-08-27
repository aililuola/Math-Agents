package io.github.aililuola.mathproofmesh.strategydiversity;

import java.util.List;
import java.util.Map;

public record PortfolioReplenishmentSnapshot(
    int schemaVersion,
    Map<String, ReplenishmentRecord> episodes,
    long version) {
  public static final int CURRENT_SCHEMA_VERSION = 1;

  public PortfolioReplenishmentSnapshot {
    episodes = episodes == null ? Map.of() : Map.copyOf(episodes);
    if (version < 0L) {
      throw new IllegalArgumentException("version must be nonnegative");
    }
  }

  public static PortfolioReplenishmentSnapshot empty() {
    return new PortfolioReplenishmentSnapshot(CURRENT_SCHEMA_VERSION, Map.of(), 0L);
  }

  @Override
  public Map<String, ReplenishmentRecord> episodes() {
    return Map.copyOf(episodes);
  }

  public record ReplenishmentRecord(
      String episodeId,
      boolean requested,
      boolean completed,
      int providerCalls,
      List<String> candidateIds,
      String requestHash) {
    public ReplenishmentRecord {
      episodeId = StrategySemanticNormalizer.require(episodeId, "episodeId");
      if (providerCalls < 0 || providerCalls > 1) {
        throw new IllegalArgumentException("a portfolio episode allows at most one provider call");
      }
      candidateIds = candidateIds == null ? List.of() : List.copyOf(candidateIds);
      requestHash = requestHash == null ? "" : requestHash.strip();
      if (completed && !requested) {
        throw new IllegalArgumentException("completed replenishment was never requested");
      }
    }

    @Override
    public List<String> candidateIds() {
      return List.copyOf(candidateIds);
    }
  }
}
