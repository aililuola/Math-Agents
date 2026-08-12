package io.github.aililuola.mathproofmesh.persistence;

public final class ArtifactValidationException extends PersistenceException {
  private static final long serialVersionUID = 1L;

  public ArtifactValidationException(String message) {
    super(message);
  }

  public ArtifactValidationException(String message, Throwable cause) {
    super(message, cause);
  }
}
