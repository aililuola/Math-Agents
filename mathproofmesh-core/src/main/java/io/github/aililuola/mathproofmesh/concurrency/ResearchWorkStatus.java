package io.github.aililuola.mathproofmesh.concurrency;

public enum ResearchWorkStatus {
  PLANNED,
  LEASED,
  RUNNING,
  RESULT_DURABLE,
  FAILED_DURABLE,
  QUARANTINED_UNCERTAIN_CALL,
  MERGED,
  SUPERSEDED,
  CANCELLED;

  public boolean settled() {
    return this == RESULT_DURABLE
        || this == FAILED_DURABLE
        || this == QUARANTINED_UNCERTAIN_CALL
        || this == CANCELLED
        || this == MERGED
        || this == SUPERSEDED;
  }
}
