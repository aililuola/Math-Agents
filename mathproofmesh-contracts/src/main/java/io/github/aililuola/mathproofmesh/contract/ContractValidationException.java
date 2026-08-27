package io.github.aililuola.mathproofmesh.contract;

public final class ContractValidationException extends IllegalArgumentException {
  private static final long serialVersionUID = 1L;

  public ContractValidationException(String message) {
    super(message);
  }

  public ContractValidationException(String message, Throwable cause) {
    super(message, cause);
  }
}
