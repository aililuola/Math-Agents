package io.github.aililuola.mathproofmesh.persistence;

public class PersistenceException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public PersistenceException(String message) {
    super(message);
  }

  public PersistenceException(String message, Throwable cause) {
    super(message, cause);
  }
}
