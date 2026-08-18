package io.github.aililuola.mathproofmesh.concurrency;

import java.util.Set;

public record ResearchWorkConflictSet(
    Set<String> routeIds,
    Set<String> claimCaseIds,
    Set<String> pivotIds,
    Set<String> obligationIds,
    Set<String> strategyEpochIds) {
  public ResearchWorkConflictSet {
    routeIds = safe(routeIds);
    claimCaseIds = safe(claimCaseIds);
    pivotIds = safe(pivotIds);
    obligationIds = safe(obligationIds);
    strategyEpochIds = safe(strategyEpochIds);
  }

  public boolean conflictsWith(ResearchWorkConflictSet other) {
    return overlaps(routeIds, other.routeIds)
        || overlaps(claimCaseIds, other.claimCaseIds)
        || overlaps(pivotIds, other.pivotIds)
        || overlaps(obligationIds, other.obligationIds)
        || overlaps(strategyEpochIds, other.strategyEpochIds);
  }

  public static ResearchWorkConflictSet empty() {
    return new ResearchWorkConflictSet(Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
  }

  private static Set<String> safe(Set<String> values) {
    return values == null ? Set.of() : Set.copyOf(values);
  }

  private static boolean overlaps(Set<String> left, Set<String> right) {
    return left.stream().anyMatch(right::contains);
  }
}
