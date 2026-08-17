package io.github.aililuola.mathproofmesh.runstate;

public record RunMathematicalProgressSnapshot(
    int verifiedLocalClaims,
    int refutedClaims,
    int openObligations,
    boolean frozenProblemPresent,
    boolean strategyPresent,
    boolean routePresent,
    boolean proofGraphPresent,
    boolean researchFindingPresent,
    boolean computationEvidencePresent,
    boolean finalProofPresent,
    boolean finalValidationPassed,
    boolean finalReviewPassed,
    boolean problemIntegrityOk) {

  public RunMathematicalProgressSnapshot {
    if (verifiedLocalClaims < 0 || refutedClaims < 0 || openObligations < 0) {
      throw new IllegalArgumentException("mathematical progress counters must not be negative");
    }
  }

  public boolean anyProgress() {
    return frozenProblemPresent
        || strategyPresent
        || routePresent
        || verifiedLocalClaims > 0
        || refutedClaims > 0
        || openObligations > 0
        || proofGraphPresent
        || researchFindingPresent
        || computationEvidencePresent
        || finalProofPresent;
  }

  public static RunMathematicalProgressSnapshot empty() {
    return new RunMathematicalProgressSnapshot(
        0, 0, 0, false, false, false, false, false, false, false, false, false, true);
  }
}
