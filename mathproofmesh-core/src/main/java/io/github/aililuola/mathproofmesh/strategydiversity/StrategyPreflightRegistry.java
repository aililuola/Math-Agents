package io.github.aililuola.mathproofmesh.strategydiversity;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class StrategyPreflightRegistry {
  private final Object lock = new Object();
  private final Map<String, StrategyPreflightReport> reports = new LinkedHashMap<>();
  private long version;

  public StrategyPreflightReport record(StrategyPreflightReport report) {
    java.util.Objects.requireNonNull(report, "report");
    synchronized (lock) {
      StrategyPreflightReport existing = reports.get(report.strategyId());
      if (existing != null
          && !StrategySemanticNormalizer.hashEquals(existing.reportHash(), report.reportHash())) {
        throw new IllegalStateException("preflight result cannot change for a captured candidate");
      }
      if (existing == null) {
        reports.put(report.strategyId(), report);
        version++;
        return report;
      }
      return existing;
    }
  }

  public Optional<StrategyPreflightReport> find(String strategyId) {
    synchronized (lock) {
      return Optional.ofNullable(reports.get(strategyId));
    }
  }

  public StrategyPreflightSnapshot snapshot() {
    synchronized (lock) {
      return snapshotUnsafe();
    }
  }

  public String registryHash() {
    synchronized (lock) {
      return CanonicalJson.stableHash(snapshotUnsafe());
    }
  }

  public static StrategyPreflightRegistry restore(StrategyPreflightSnapshot snapshot) {
    StrategyPreflightRegistry registry = new StrategyPreflightRegistry();
    StrategyPreflightSnapshot source =
        snapshot == null ? StrategyPreflightSnapshot.empty() : snapshot;
    synchronized (registry.lock) {
      registry.reports.putAll(source.reports());
      registry.version = source.version();
    }
    return registry;
  }

  private StrategyPreflightSnapshot snapshotUnsafe() {
    return new StrategyPreflightSnapshot(
        StrategyPreflightSnapshot.CURRENT_SCHEMA_VERSION, reports, version);
  }
}
