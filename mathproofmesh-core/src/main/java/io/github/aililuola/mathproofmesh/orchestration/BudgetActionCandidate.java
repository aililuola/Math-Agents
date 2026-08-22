package io.github.aililuola.mathproofmesh.orchestration;

import io.github.aililuola.mathproofmesh.contract.ActionKind;
import java.util.Objects;

/** One fully costed scheduler candidate before it enters the frozen ready queue. */
public record BudgetActionCandidate(
    ActionKind action,
    String targetId,
    String strategyId,
    String reason,
    boolean eligible,
    String blockedReason,
    double evidenceScore,
    BudgetResourceVector resourceEstimate,
    BudgetBucket bucket,
    int rank,
    boolean forced,
    boolean selected) {

  public BudgetActionCandidate {
    action = Objects.requireNonNull(action, "action");
    targetId = targetId == null ? "" : targetId.strip();
    strategyId = strategyId == null ? "" : strategyId.strip();
    reason = required(reason, "reason");
    blockedReason = blockedReason == null ? "" : blockedReason.strip();
    if (!Double.isFinite(evidenceScore) || rank < 0) {
      throw new IllegalArgumentException("candidate score must be finite and rank nonnegative");
    }
    resourceEstimate = Objects.requireNonNull(resourceEstimate, "resourceEstimate");
    bucket = Objects.requireNonNull(bucket, "bucket");
    if (selected && !eligible) {
      throw new IllegalArgumentException("an ineligible budget candidate cannot be selected");
    }
  }

  private static String required(String value, String field) {
    String normalized = value == null ? "" : value.strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return normalized;
  }
}
