package io.github.aililuola.mathproofmesh.orchestration;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.util.List;

/** Deterministic identity used across checkpoint and database recovery. */
public record BudgetEnvelopeId(String value) {
  public BudgetEnvelopeId {
    value = value == null ? "" : value.strip();
    if (value.isEmpty()) {
      throw new IllegalArgumentException("budget envelope id is required");
    }
  }

  public static BudgetEnvelopeId create(
      String runId,
      String epochId,
      String workItemId,
      String actionDecisionId,
      BudgetBucket bucket) {
    return new BudgetEnvelopeId(
        "budget-envelope-"
            + CanonicalJson.stableHash(
                    List.of(runId, epochId, workItemId, actionDecisionId, bucket.name()))
                .substring(0, 32));
  }
}
