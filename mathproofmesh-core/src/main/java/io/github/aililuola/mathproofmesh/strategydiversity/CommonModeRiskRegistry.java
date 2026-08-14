package io.github.aililuola.mathproofmesh.strategydiversity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CommonModeRiskRegistry {
  private final Map<String, LinkedHashSet<String>> affected = new LinkedHashMap<>();

  public synchronized void observe(StrategyPreflightReport report) {
    java.util.Objects.requireNonNull(report, "report");
    report.unresolvedRequiredClaimKeys()
        .forEach(
            key ->
                affected.computeIfAbsent(key, ignored -> new LinkedHashSet<>())
                    .add(report.strategyId()));
  }

  public synchronized List<CommonModeRiskRecord> records() {
    List<CommonModeRiskRecord> result = new ArrayList<>();
    affected.forEach(
        (key, strategyIds) -> {
          if (strategyIds.size() > 1) {
            result.add(
                new CommonModeRiskRecord(
                    key,
                    strategyIds,
                    CriticalClaimPreflightStatus.UNKNOWN,
                    "required",
                    "unresolved"));
          }
        });
    return List.copyOf(result);
  }

  public synchronized Set<String> groupsFor(String strategyId) {
    Set<String> result = new LinkedHashSet<>();
    affected.forEach(
        (key, strategies) -> {
          if (strategies.contains(strategyId) && strategies.size() > 1) {
            result.add(key);
          }
        });
    return Set.copyOf(result);
  }
}
