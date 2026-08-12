package io.github.aililuola.mathproofmesh.computation;

/** Bounded diagnostics from the explicitly enabled container sandbox. */
public final class SandboxExecutionError extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final Integer exitCode;
  private final String stdout;
  private final String stderr;

  public SandboxExecutionError(
      String message, Integer exitCode, String stdout, String stderr) {
    super(message);
    this.exitCode = exitCode;
    this.stdout = bounded(stdout);
    this.stderr = bounded(stderr);
  }

  public Integer exitCode() {
    return exitCode;
  }

  public String stdout() {
    return stdout;
  }

  public String stderr() {
    return stderr;
  }

  private static String bounded(String value) {
    if (value == null) {
      return "";
    }
    String normalized = value.replace('\r', ' ').replace('\n', ' ');
    return normalized.length() <= 20_000
        ? normalized
        : normalized.substring(0, 20_000);
  }
}
