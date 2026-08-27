package io.github.aililuola.mathproofmesh.compatibility;

public final class LegacyImportException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public LegacyImportException(String message) {
    super(message);
  }

  public LegacyImportException(String message, Throwable cause) {
    super(message, cause);
  }
}
