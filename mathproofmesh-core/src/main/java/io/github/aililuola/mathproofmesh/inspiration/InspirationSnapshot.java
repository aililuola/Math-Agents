package io.github.aililuola.mathproofmesh.inspiration;

import java.util.List;
import java.util.Map;

/** Observable scheduling state; proof transcripts are intentionally absent. */
public record InspirationSnapshot(
    int roundIndex,
    String problemHash,
    String domain,
    List<String> activeRouteIds,
    List<String> failedRouteIds,
    Map<String, Integer> stagnationRoundsByRoute,
    int verifiedFactGainRecent,
    Map<String, Double> proofDebtByRoute,
    double proofDebtReductionRecent,
    List<Double> proofDebtHistory,
    List<String> firstErrorFingerprints,
    double routeRedundancy,
    List<String> sharedBottleneckIds,
    int remainingCalls,
    int finalizationReserveCalls,
    int currentPathCount,
    int maxPaths,
    List<String> openObligationIds,
    Map<String, String> obligationKinds,
    boolean finalRepairFailed,
    boolean manualTrigger) {

  public InspirationSnapshot {
    if (roundIndex < 0
        || verifiedFactGainRecent < 0
        || remainingCalls < 0
        || finalizationReserveCalls < 0
        || currentPathCount < 0
        || maxPaths < 0) {
      throw new IllegalArgumentException("snapshot counts must be nonnegative");
    }
    problemHash = required(problemHash, "problemHash");
    domain = domain == null || domain.isBlank() ? "unknown" : domain.strip();
    activeRouteIds = copy(activeRouteIds);
    failedRouteIds = copy(failedRouteIds);
    stagnationRoundsByRoute =
        stagnationRoundsByRoute == null ? Map.of() : Map.copyOf(stagnationRoundsByRoute);
    proofDebtByRoute = proofDebtByRoute == null ? Map.of() : Map.copyOf(proofDebtByRoute);
    proofDebtHistory = copy(proofDebtHistory);
    firstErrorFingerprints = copy(firstErrorFingerprints);
    sharedBottleneckIds = copy(sharedBottleneckIds);
    openObligationIds = copy(openObligationIds);
    obligationKinds = obligationKinds == null ? Map.of() : Map.copyOf(obligationKinds);
    if (!Double.isFinite(routeRedundancy)
        || routeRedundancy < 0.0d
        || routeRedundancy > 1.0d
        || !Double.isFinite(proofDebtReductionRecent)) {
      throw new IllegalArgumentException("snapshot ratios must be finite");
    }
  }

  public int schedulableCalls() {
    return Math.max(0, remainingCalls - finalizationReserveCalls);
  }

  public boolean pathCapacityAvailable() {
    return maxPaths == 0 || currentPathCount < maxPaths;
  }

  public List<String> activeRouteIds() {
    return List.copyOf(activeRouteIds);
  }

  public List<String> failedRouteIds() {
    return List.copyOf(failedRouteIds);
  }

  public List<Double> proofDebtHistory() {
    return List.copyOf(proofDebtHistory);
  }

  public List<String> firstErrorFingerprints() {
    return List.copyOf(firstErrorFingerprints);
  }

  public List<String> sharedBottleneckIds() {
    return List.copyOf(sharedBottleneckIds);
  }

  public List<String> openObligationIds() {
    return List.copyOf(openObligationIds);
  }

  private static String required(String value, String field) {
    String result = value == null ? "" : value.strip();
    if (result.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return result;
  }

  private static <T> List<T> copy(List<T> values) {
    return values == null ? List.of() : List.copyOf(values);
  }
}
