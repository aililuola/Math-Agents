package io.github.aililuola.mathproofmesh.persistence;

public final class OptimisticLockException extends PersistenceException {
  private static final long serialVersionUID = 1L;

  public OptimisticLockException(String runId, long expectedVersion) {
    super(
        "run '"
            + runId
            + "' was not updated at expected version "
            + expectedVersion
            + " with an active fencing token");
  }
}
