package io.github.aililuola.mathproofmesh.proofcontrol;

public final class PivotCompilationException extends IllegalArgumentException {
  private static final long serialVersionUID = 1L;

  private final String code;

  public PivotCompilationException(String code, String message) {
    super(PivotValues.required(message, "message"));
    this.code = PivotValues.required(code, "code");
  }

  public String code() {
    return code;
  }
}
