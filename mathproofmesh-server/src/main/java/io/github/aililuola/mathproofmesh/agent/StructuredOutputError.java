package io.github.aililuola.mathproofmesh.agent;

public final class StructuredOutputError extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public StructuredOutputError(String message) {
    super(message);
  }

  public StructuredOutputError(String message, Throwable cause) {
    super(message, cause);
  }
}
