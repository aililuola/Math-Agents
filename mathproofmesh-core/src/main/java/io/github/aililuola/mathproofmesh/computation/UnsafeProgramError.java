package io.github.aililuola.mathproofmesh.computation;

/** Raised when optional sandbox source fails the fail-closed source policy. */
public final class UnsafeProgramError extends IllegalArgumentException {
  private static final long serialVersionUID = 1L;

  public UnsafeProgramError(String message) {
    super(message);
  }
}
