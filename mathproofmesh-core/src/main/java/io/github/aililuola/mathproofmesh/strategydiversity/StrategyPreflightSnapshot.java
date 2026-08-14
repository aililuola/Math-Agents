package io.github.aililuola.mathproofmesh.strategydiversity;

import java.util.Map;

public record StrategyPreflightSnapshot(
    int schemaVersion, Map<String, StrategyPreflightReport> reports, long version) {
  public static final int CURRENT_SCHEMA_VERSION = 1;

  public StrategyPreflightSnapshot {
    reports = reports == null ? Map.of() : Map.copyOf(reports);
    if (version < 0L) {
      throw new IllegalArgumentException("version must be nonnegative");
    }
  }

  public static StrategyPreflightSnapshot empty() {
    return new StrategyPreflightSnapshot(CURRENT_SCHEMA_VERSION, Map.of(), 0L);
  }

  @Override
  public Map<String, StrategyPreflightReport> reports() {
    return Map.copyOf(reports);
  }
}
