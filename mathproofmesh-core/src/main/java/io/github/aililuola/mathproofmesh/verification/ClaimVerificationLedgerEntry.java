package io.github.aililuola.mathproofmesh.verification;

import java.util.List;

public record ClaimVerificationLedgerEntry(
    String claimId,
    String sourceAttemptId,
    List<String> dependencyIds,
    ClaimVerificationState state,
    boolean sourceAttemptIncomplete,
    String invalidationReason,
    List<String> evidenceIds) {

  public ClaimVerificationLedgerEntry {
    claimId = required(claimId, "claimId");
    sourceAttemptId = sourceAttemptId == null ? "" : sourceAttemptId.trim();
    dependencyIds = dependencyIds == null ? List.of() : List.copyOf(dependencyIds);
    state = java.util.Objects.requireNonNull(state, "state");
    invalidationReason = invalidationReason == null ? "" : invalidationReason.trim();
    evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
  }

  private static String required(String value, String field) {
    String result = value == null ? "" : value.trim();
    if (result.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return result;
  }
}
