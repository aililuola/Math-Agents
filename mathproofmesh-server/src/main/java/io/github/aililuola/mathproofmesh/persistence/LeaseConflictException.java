package io.github.aililuola.mathproofmesh.persistence;

public final class LeaseConflictException extends PersistenceException {
  private static final long serialVersionUID = 1L;

  public LeaseConflictException(String runId) {
    super("run '" + runId + "' already has an active owner");
  }
}
