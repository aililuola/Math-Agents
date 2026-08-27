package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

public enum ComputationVerificationStatus {
  VALID,
  INVALID,
  INCONCLUSIVE,
  BACKEND_UNAVAILABLE;

  @JsonValue
  public String value() {
    return name().toLowerCase(Locale.ROOT);
  }

  @JsonCreator
  public static ComputationVerificationStatus fromValue(String value) {
    try {
      return valueOf(value.toUpperCase(Locale.ROOT));
    } catch (RuntimeException exception) {
      throw new ContractValidationException(
          "unknown ComputationVerificationStatus value: " + value, exception);
    }
  }
}
