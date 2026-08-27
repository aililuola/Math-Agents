package io.github.aililuola.mathproofmesh.runstate;

public enum RunExecutionStatus {
  QUEUED,
  RUNNING,
  SUCCEEDED,
  FAILED,
  INTERRUPTED,
  CANCELLED;

  public boolean terminal() {
    return this == SUCCEEDED || this == FAILED || this == INTERRUPTED || this == CANCELLED;
  }
}
