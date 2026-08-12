package io.github.aililuola.mathproofmesh.config;

public final class ConfigValidationException extends IllegalArgumentException {
  private static final long serialVersionUID = 1L;

  public ConfigValidationException(String message) {
    super(message);
  }

  public ConfigValidationException(String message, Throwable cause) {
    super(message, cause);
  }
}
