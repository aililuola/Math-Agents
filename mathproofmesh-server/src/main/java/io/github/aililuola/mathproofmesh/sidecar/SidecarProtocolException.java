package io.github.aililuola.mathproofmesh.sidecar;

/** A fail-closed process, transport, or response validation failure. */
public final class SidecarProtocolException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final String boundedStderr;

  public SidecarProtocolException(String message) {
    this(message, "", null);
  }

  public SidecarProtocolException(String message, String boundedStderr) {
    this(message, boundedStderr, null);
  }

  public SidecarProtocolException(String message, String boundedStderr, Throwable cause) {
    super(message, cause);
    this.boundedStderr = boundedStderr == null ? "" : boundedStderr;
  }

  public String boundedStderr() {
    return boundedStderr;
  }
}
