package io.github.aililuola.mathproofmesh.orchestration;

/** Result of one deep-exploration lease. */
public record ExplorationOutcome(
    boolean verifiedProgress,
    boolean artifactProduced,
    boolean refuted,
    Failure failure,
    int chargedCalls,
    String reason) {
  public ExplorationOutcome {
    failure = failure == null ? Failure.NONE : failure;
    reason = reason == null ? "" : reason.strip();
    if (chargedCalls < 0) {
      throw new IllegalArgumentException("chargedCalls must be nonnegative");
    }
  }

  public enum Failure {
    NONE,
    LENGTH_LIMIT,
    FIRST_CHUNK_TIMEOUT,
    STREAM_STALL,
    PROVIDER_CIRCUIT,
    NO_ARTIFACT,
    SEMANTIC_REJECTION
  }
}
