package io.github.aililuola.mathproofmesh.proofcontrol.claimcourt;

public record ClaimCourtConfig(
    int maxRepairAttempts,
    int maxChangedSteps,
    double maxChangedStepFraction,
    int maxInsertedSteps,
    boolean requireBlindAdjudication) {
  public ClaimCourtConfig {
    if (maxRepairAttempts < 0
        || maxChangedSteps < 0
        || !Double.isFinite(maxChangedStepFraction)
        || maxChangedStepFraction < 0.0d
        || maxChangedStepFraction > 1.0d
        || maxInsertedSteps < 0) {
      throw new IllegalArgumentException("invalid claim court repair limits");
    }
  }

  public static ClaimCourtConfig defaults() {
    return new ClaimCourtConfig(1, 3, 0.35d, 3, true);
  }
}
