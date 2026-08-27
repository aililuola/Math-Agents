package io.github.aililuola.mathproofmesh.orchestration;

import java.util.List;
import java.util.Objects;

/** Deterministic six-action decision produced from one canonical budget state. */
public record EvidenceAwareBudgetDecision(
    BudgetDecisionIdentity identity,
    List<BudgetActionCandidate> actions,
    String rationale,
    String stopReason) {

  public EvidenceAwareBudgetDecision {
    identity = Objects.requireNonNull(identity, "identity");
    actions = actions == null ? List.of() : List.copyOf(actions);
    rationale = required(rationale, "rationale");
    stopReason = stopReason == null ? "" : stopReason.strip();
    if (actions.stream().filter(BudgetActionCandidate::selected).count() > 1L) {
      throw new IllegalArgumentException("at most one action may be selected");
    }
  }

  public List<BudgetActionCandidate> selectedActions() {
    return actions.stream().filter(BudgetActionCandidate::selected).toList();
  }

  private static String required(String value, String field) {
    String normalized = value == null ? "" : value.strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return normalized;
  }
}
