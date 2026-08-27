package io.github.aililuola.mathproofmesh.orchestration;

/** Evidence controlling tier promotion and recovery. */
public record ExplorationEvidence(
    boolean verified96kProgress,
    boolean metaApproved128k,
    boolean artifactStarted,
    boolean localPivot,
    int remainingCalls,
    int finalizationReserve,
    int priorRepairs) {
  public ExplorationEvidence {
    if (remainingCalls < 0 || finalizationReserve < 0 || priorRepairs < 0) {
      throw new IllegalArgumentException("exploration evidence counts must be nonnegative");
    }
  }

  public int schedulableCalls() {
    return Math.max(0, remainingCalls - finalizationReserve);
  }
}
