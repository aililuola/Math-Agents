package io.github.aililuola.mathproofmesh.runstate;

public enum RunUsageEvidenceSource {
  DURABLE_PROVIDER_REQUESTS(4),
  SEMANTIC_CHECKPOINT(3),
  PREVIOUS_RUN_STATE(2),
  RESULT_PROJECTION(1);

  private final int priority;

  RunUsageEvidenceSource(int priority) {
    this.priority = priority;
  }

  int priority() {
    return priority;
  }
}
