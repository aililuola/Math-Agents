package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ComputationDecisionStatus {
  ALLOW("allow"),
  DEFER("defer"),
  REJECT("reject");

  private final String value;

  ComputationDecisionStatus(String value) {
    this.value = value;
  }

  @JsonValue
  public String value() {
    return value;
  }

  @JsonCreator
  public static ComputationDecisionStatus fromValue(String value) {
    for (ComputationDecisionStatus candidate : values()) {
      if (candidate.value.equals(value)) {
        return candidate;
      }
    }
    throw new ContractValidationException("unknown ComputationDecisionStatus value: " + value);
  }
}
