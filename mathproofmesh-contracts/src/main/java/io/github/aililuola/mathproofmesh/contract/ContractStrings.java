package io.github.aililuola.mathproofmesh.contract;

public final class ContractStrings {
  private ContractStrings() {}

  public static String trim(String value) {
    return value == null ? null : value.strip();
  }

  public static String required(String name, String value) {
    if (value == null) {
      throw new ContractValidationException(name + " is required");
    }
    return value;
  }
}
