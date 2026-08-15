package io.github.aililuola.mathproofmesh.strategydiversity;

import io.github.aililuola.mathproofmesh.contract.StrategyPreflightPlan;
import java.util.Map;

public record StrategyPreflightSnapshot(
    int schemaVersion,
    Map<String, StrategyPreflightReport> reports,
    Map<String, StrategyPreflightPlan> plans,
    Map<String, StrategyPreflightExecutionRecord> executions,
    long version) {
  public static final int CURRENT_SCHEMA_VERSION = 3;

  public StrategyPreflightSnapshot {
    reports = reports == null ? Map.of() : Map.copyOf(reports);
    plans = plans == null ? Map.of() : Map.copyOf(plans);
    executions = executions == null ? Map.of() : Map.copyOf(executions);
    if (version < 0L) {
      throw new IllegalArgumentException("version must be nonnegative");
    }
  }

  public static StrategyPreflightSnapshot empty() {
    return new StrategyPreflightSnapshot(
        CURRENT_SCHEMA_VERSION, Map.of(), Map.of(), Map.of(), 0L);
  }

  public StrategyPreflightSnapshot(
      int schemaVersion, Map<String, StrategyPreflightReport> reports, long version) {
    this(schemaVersion, reports, Map.of(), Map.of(), version);
  }

  @Override
  public Map<String, StrategyPreflightReport> reports() {
    return Map.copyOf(reports);
  }

  @Override
  public Map<String, StrategyPreflightPlan> plans() {
    return Map.copyOf(plans);
  }

  @Override
  public Map<String, StrategyPreflightExecutionRecord> executions() {
    return Map.copyOf(executions);
  }
}
