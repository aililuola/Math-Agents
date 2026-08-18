package io.github.aililuola.mathproofmesh.concurrency;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record ResearchWorkItem(
    String workItemId,
    String epochId,
    String snapshotHash,
    ResearchWorkKind kind,
    String routeId,
    String claimId,
    String obligationId,
    String canonicalTargetId,
    String requiredRole,
    AgentLeaseClass leaseClass,
    Set<String> excludedAgentIds,
    ResearchWorkReadSet readSet,
    ResearchWorkConflictSet conflictSet,
    String inputArtifactRef,
    String expectedResultSchema,
    int stableOrdinal) {
  public ResearchWorkItem {
    workItemId = text(workItemId, "workItemId");
    epochId = text(epochId, "epochId");
    snapshotHash = text(snapshotHash, "snapshotHash");
    kind = Objects.requireNonNull(kind, "kind");
    routeId = optional(routeId);
    claimId = optional(claimId);
    obligationId = optional(obligationId);
    canonicalTargetId = optional(canonicalTargetId);
    requiredRole = text(requiredRole, "requiredRole");
    leaseClass = Objects.requireNonNull(leaseClass, "leaseClass");
    excludedAgentIds = excludedAgentIds == null ? Set.of() : Set.copyOf(excludedAgentIds);
    readSet = readSet == null ? ResearchWorkReadSet.empty() : readSet;
    conflictSet = conflictSet == null ? ResearchWorkConflictSet.empty() : conflictSet;
    inputArtifactRef = text(inputArtifactRef, "inputArtifactRef");
    expectedResultSchema = text(expectedResultSchema, "expectedResultSchema");
    if (stableOrdinal < 0) {
      throw new IllegalArgumentException("stableOrdinal must be nonnegative");
    }
  }

  public static String deterministicId(
      String epochId,
      ResearchWorkKind kind,
      String routeId,
      String claimId,
      String obligationId,
      int stableOrdinal) {
    String hash =
        CanonicalJson.stableHash(
            Map.of(
                "epoch", epochId,
                "kind", kind.name(),
                "route", optional(routeId),
                "claim", optional(claimId),
                "obligation", optional(obligationId),
                "ordinal", stableOrdinal));
    return "work-" + hash.substring(0, 24);
  }

  private static String text(String value, String label) {
    Objects.requireNonNull(value, label);
    String normalized = value.strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(label + " must not be blank");
    }
    return normalized;
  }

  private static String optional(String value) {
    return value == null ? "" : value.strip();
  }
}
