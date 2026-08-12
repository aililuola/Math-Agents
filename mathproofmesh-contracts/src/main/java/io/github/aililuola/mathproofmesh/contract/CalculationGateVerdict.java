package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum CalculationGateVerdict {
  PASSED("passed"),
  MISSING_DECLARATION("missing_declaration"),
  INVALID_CONTRACT("invalid_contract"),
  REFUTED("refuted"),
  INCONCLUSIVE("inconclusive");

  private final String value;

  CalculationGateVerdict(String value) {
    this.value = value;
  }

  @JsonValue
  public String value() {
    return value;
  }

  @JsonCreator
  public static CalculationGateVerdict fromValue(String value) {
    for (CalculationGateVerdict candidate : values()) {
      if (candidate.value.equals(value)) {
        return candidate;
      }
    }
    throw new ContractValidationException("unknown CalculationGateVerdict value: " + value);
  }
}
