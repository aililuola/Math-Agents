package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ExperimentOutcome {
  NOT_REFUTED("not_refuted"),
  COUNTEREXAMPLE_FOUND("counterexample_found"),
  CERTIFIED("certified"),
  INCONCLUSIVE("inconclusive"),
  ERROR("error");

  private final String value;

  ExperimentOutcome(String value) {
    this.value = value;
  }

  @JsonValue
  public String value() {
    return value;
  }

  @JsonCreator
  public static ExperimentOutcome fromValue(String value) {
    for (ExperimentOutcome candidate : values()) {
      if (candidate.value.equals(value)) {
        return candidate;
      }
    }
    throw new ContractValidationException("unknown ExperimentOutcome value: " + value);
  }
}
