package io.github.aililuola.mathproofmesh.concurrency;

public enum ResearchEpochStatus {
  PLANNED,
  DISPATCHING,
  ALL_SETTLED,
  MERGE_PREPARED,
  COMMITTED,
  ABORTED,
  STALE_SNAPSHOT,
  QUARANTINED
}
