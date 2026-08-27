package io.github.aililuola.mathproofmesh.agent;

public final class BudgetExhaustedError extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public BudgetExhaustedError(String message) {
    super(message);
  }
}
