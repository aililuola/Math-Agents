package io.github.aililuola.mathproofmesh.concurrency;

import java.util.Set;

public record ResearchWorkConflictSet(
    Set<String> routeIds,
    Set<String> claimCaseIds,
    Set<String> pivotIds,
    Set<String> obligationIds,
    Set<String> strategyEpochIds,
    Set<String> resourceIds) {
  public ResearchWorkConflictSet {
    routeIds = safe(routeIds);
    claimCaseIds = safe(claimCaseIds);
    pivotIds = safe(pivotIds);
    obligationIds = safe(obligationIds);
    strategyEpochIds = safe(strategyEpochIds);
    resourceIds = safe(resourceIds);
  }

  public ResearchWorkConflictSet(
      Set<String> routeIds,
      Set<String> claimCaseIds,
      Set<String> pivotIds,
      Set<String> obligationIds,
      Set<String> strategyEpochIds) {
    this(routeIds, claimCaseIds, pivotIds, obligationIds, strategyEpochIds, Set.of());
  }

  public boolean conflictsWith(ResearchWorkConflictSet other) {
    return overlaps(routeIds, other.routeIds)
        || overlaps(claimCaseIds, other.claimCaseIds)
        || overlaps(pivotIds, other.pivotIds)
        || overlaps(obligationIds, other.obligationIds)
        || overlaps(strategyEpochIds, other.strategyEpochIds)
        || overlaps(resourceIds, other.resourceIds);
  }

  @Override
  public Set<String> routeIds() {
    return Set.copyOf(routeIds);
  }

  @Override
  public Set<String> claimCaseIds() {
    return Set.copyOf(claimCaseIds);
  }

  @Override
  public Set<String> pivotIds() {
    return Set.copyOf(pivotIds);
  }

  @Override
  public Set<String> obligationIds() {
    return Set.copyOf(obligationIds);
  }

  @Override
  public Set<String> strategyEpochIds() {
    return Set.copyOf(strategyEpochIds);
  }

  @Override
  public Set<String> resourceIds() {
    return Set.copyOf(resourceIds);
  }

  public static ResearchWorkConflictSet empty() {
    return new ResearchWorkConflictSet(
        Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
  }

  private static Set<String> safe(Set<String> values) {
    return values == null ? Set.of() : Set.copyOf(values);
  }

  private static boolean overlaps(Set<String> left, Set<String> right) {
    return left.stream().anyMatch(right::contains);
  }
}
