package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum MathStatus {
  VERIFIED("verified"),
  INCONCLUSIVE("inconclusive"),
  REFUTED("refuted");

  private final String value;

  MathStatus(String value) {
    this.value = value;
  }

  @JsonValue
  public String value() {
    return value;
  }

  @JsonCreator
  public static MathStatus fromValue(String value) {
    for (MathStatus candidate : values()) {
      if (candidate.value.equals(value)) {
        return candidate;
      }
    }
    throw new ContractValidationException("unknown MathStatus value: " + value);
  }
}
