package io.github.aililuola.mathproofmesh.concurrency;

import java.util.Objects;

public record ResearchMergeDecision(
    String workItemId,
    String resultHash,
    boolean accepted,
    String reason,
    int stableOrdinal,
    String routeId,
    String claimId,
    String obligationId) {
  public ResearchMergeDecision {
    workItemId = Objects.requireNonNull(workItemId, "workItemId").strip();
    resultHash = Objects.requireNonNull(resultHash, "resultHash").strip();
    reason = Objects.requireNonNull(reason, "reason").strip();
    routeId = optional(routeId);
    claimId = optional(claimId);
    obligationId = optional(obligationId);
    if (workItemId.isEmpty() || resultHash.isEmpty() || reason.isEmpty() || stableOrdinal < 0) {
      throw new IllegalArgumentException("merge decision fields must be valid");
    }
  }

  public ResearchMergeDecision(
      String workItemId,
      String resultHash,
      boolean accepted,
      String reason,
      int stableOrdinal) {
    this(workItemId, resultHash, accepted, reason, stableOrdinal, "", "", "");
  }

  private static String optional(String value) {
    return value == null ? "" : value.strip();
  }
}
