package io.github.aililuola.mathproofmesh.computation;

/** Route-local state used by the reasoning-first computation gate. */
public record ComputationContext(
    String pathId,
    int stalledRounds,
    boolean metaReviewApproved,
    int remainingLlmCalls,
    int experimentsUsed,
    double cpuSecondsUsed) {

  public ComputationContext {
    if (pathId == null || pathId.isBlank()) {
      throw new IllegalArgumentException("pathId is required");
    }
    if (stalledRounds < 0
        || remainingLlmCalls < 0
        || experimentsUsed < 0
        || cpuSecondsUsed < 0) {
      throw new IllegalArgumentException("computation context counters must be nonnegative");
    }
  }

  public static ComputationContext initial(String pathId, int remainingLlmCalls) {
    return new ComputationContext(pathId, 0, false, remainingLlmCalls, 0, 0.0);
  }
}
