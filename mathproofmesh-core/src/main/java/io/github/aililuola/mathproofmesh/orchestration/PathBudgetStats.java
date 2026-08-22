package io.github.aililuola.mathproofmesh.orchestration;

import java.math.BigDecimal;
import java.util.Objects;

/** Public, committed evidence used to value one route without trusting model self-assessment. */
public record PathBudgetStats(
    String strategyId,
    String routeId,
    String latestAttemptId,
    boolean complete,
    boolean verifiedProgress,
    double marginalProgress,
    double gapReduction,
    double novelty,
    double uncertainty,
    double verificationScore,
    String latestVerdict,
    AttemptEvidence.FailureClass failureLevel,
    double failureConfidence,
    int consecutiveFailures,
    int failedRepairAttempts,
    int unresolvedGapCount,
    int stagnationRounds,
    long tokensSpent,
    BigDecimal costSpent,
    boolean structurallyValid,
    String mechanismSignature) {

  public PathBudgetStats {
    strategyId = required(strategyId, "strategyId");
    routeId = required(routeId, "routeId");
    latestAttemptId = latestAttemptId == null ? "" : latestAttemptId.strip();
    latestVerdict = latestVerdict == null ? "unknown" : latestVerdict.strip().toLowerCase(java.util.Locale.ROOT);
    failureLevel = failureLevel == null ? AttemptEvidence.FailureClass.NONE : failureLevel;
    checkUnit(marginalProgress, "marginalProgress");
    checkUnit(gapReduction, "gapReduction");
    checkUnit(novelty, "novelty");
    checkUnit(uncertainty, "uncertainty");
    checkUnit(verificationScore, "verificationScore");
    checkUnit(failureConfidence, "failureConfidence");
    if (consecutiveFailures < 0
        || failedRepairAttempts < 0
        || unresolvedGapCount < 0
        || stagnationRounds < 0
        || tokensSpent < 0) {
      throw new IllegalArgumentException("path counters must not be negative");
    }
    costSpent = Objects.requireNonNull(costSpent, "costSpent");
    if (costSpent.signum() < 0) {
      throw new IllegalArgumentException("costSpent must not be negative");
    }
    costSpent = costSpent.signum() == 0 ? BigDecimal.ZERO : costSpent.stripTrailingZeros();
    mechanismSignature =
        mechanismSignature == null || mechanismSignature.isBlank()
            ? strategyId
            : mechanismSignature.strip();
  }

  public TargetMechanismKey key(io.github.aililuola.mathproofmesh.contract.ActionKind kind) {
    return new TargetMechanismKey(routeId, strategyId, kind, mechanismSignature);
  }

  public boolean failed() {
    return failureLevel != AttemptEvidence.FailureClass.NONE;
  }

  private static void checkUnit(double value, String field) {
    if (!Double.isFinite(value) || value < 0.0d || value > 1.0d) {
      throw new IllegalArgumentException(field + " must be finite and within [0,1]");
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
