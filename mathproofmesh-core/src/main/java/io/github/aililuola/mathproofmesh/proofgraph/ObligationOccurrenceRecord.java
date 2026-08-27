package io.github.aililuola.mathproofmesh.proofgraph;

public record ObligationOccurrenceRecord(
    String occurrenceId,
    String obligationId,
    String problemHash,
    String routeId,
    String strategyId,
    ObligationSourceType sourceType,
    String sourceArtifactRef,
    String canonicalTargetId,
    String bottleneckFamilyId,
    String dependencyPlanSignature,
    ObligationOccurrenceSchedulingState schedulingState,
    int createdRound,
    long version) {

  public ObligationOccurrenceRecord {
    occurrenceId = require(occurrenceId, "occurrenceId");
    obligationId = require(obligationId, "obligationId");
    problemHash = require(problemHash, "problemHash");
    routeId = normalize(routeId);
    strategyId = normalize(strategyId);
    sourceType = sourceType == null ? ObligationSourceType.UNKNOWN : sourceType;
    sourceArtifactRef = normalize(sourceArtifactRef);
    canonicalTargetId = require(canonicalTargetId, "canonicalTargetId");
    bottleneckFamilyId = normalize(bottleneckFamilyId);
    dependencyPlanSignature = require(dependencyPlanSignature, "dependencyPlanSignature");
    schedulingState =
        schedulingState == null
            ? ObligationOccurrenceSchedulingState.ACTIVE
            : schedulingState;
    if (createdRound < 0 || version < 0) {
      throw new IllegalArgumentException("createdRound and version must be nonnegative");
    }
  }

  public ObligationOccurrenceRecord withFamily(String familyId) {
    return new ObligationOccurrenceRecord(
        occurrenceId,
        obligationId,
        problemHash,
        routeId,
        strategyId,
        sourceType,
        sourceArtifactRef,
        canonicalTargetId,
        familyId,
        dependencyPlanSignature,
        schedulingState,
        createdRound,
        version + 1);
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
