package io.github.aililuola.mathproofmesh.proofgraph;

import io.github.aililuola.mathproofmesh.contract.ProofObligation;
import java.util.List;
import java.util.Map;

public record ObligationCreationContext(
    String problemHash,
    String routeId,
    String strategyId,
    ObligationSourceType sourceType,
    String sourceArtifactRef,
    List<String> scopeMarkers,
    String polarity,
    Map<String, String> trustedSymbolRoles,
    String bottleneckKey,
    String bottleneckLabel,
    BottleneckRelationType bottleneckRelation,
    ObligationOccurrenceSchedulingState schedulingState,
    int createdRound) {

  public ObligationCreationContext {
    problemHash = require(problemHash, "problemHash");
    routeId = normalize(routeId);
    strategyId = normalize(strategyId);
    sourceType = sourceType == null ? ObligationSourceType.UNKNOWN : sourceType;
    sourceArtifactRef = normalize(sourceArtifactRef);
    scopeMarkers = scopeMarkers == null ? List.of() : List.copyOf(scopeMarkers);
    polarity = normalize(polarity);
    trustedSymbolRoles = trustedSymbolRoles == null ? Map.of() : Map.copyOf(trustedSymbolRoles);
    bottleneckKey = normalize(bottleneckKey);
    bottleneckLabel = normalize(bottleneckLabel);
    bottleneckRelation =
        bottleneckRelation == null
            ? BottleneckRelationType.SHARES_UPSTREAM_BOTTLENECK
            : bottleneckRelation;
    schedulingState =
        schedulingState == null
            ? ObligationOccurrenceSchedulingState.ACTIVE
            : schedulingState;
    if (createdRound < 0) {
      throw new IllegalArgumentException("createdRound must be nonnegative");
    }
  }

  public static ObligationCreationContext defaultFor(ProofObligation obligation) {
    java.util.Objects.requireNonNull(obligation, "obligation");
    String routeId = obligation.routeIds().isEmpty() ? "run" : obligation.routeIds().getFirst();
    ObligationSourceType sourceType =
        obligation.kind() == io.github.aililuola.mathproofmesh.contract.ObligationKind.MAIN_GOAL
            ? ObligationSourceType.MAIN_GOAL
            : ObligationSourceType.UNKNOWN;
    return new ObligationCreationContext(
        obligation.problemHash(),
        routeId,
        "",
        sourceType,
        "",
        List.of(),
        "",
        Map.of(),
        obligation.firstErrorFingerprint(),
        obligation.firstErrorFingerprint(),
        BottleneckRelationType.SHARES_UPSTREAM_BOTTLENECK,
        ObligationOccurrenceSchedulingState.ACTIVE,
        0);
  }

  public ObligationCreationContext withSchedulingState(
      ObligationOccurrenceSchedulingState state) {
    return new ObligationCreationContext(
        problemHash,
        routeId,
        strategyId,
        sourceType,
        sourceArtifactRef,
        scopeMarkers,
        polarity,
        trustedSymbolRoles,
        bottleneckKey,
        bottleneckLabel,
        bottleneckRelation,
        state,
        createdRound);
  }

  private static String normalize(String value) {
    return value == null ? "" : value.strip();
  }

  private static String require(String value, String field) {
    String normalized = normalize(value);
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return normalized;
  }
}
