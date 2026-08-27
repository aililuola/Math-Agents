package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum InitialExplorationAction {
  SUBMIT_ATTEMPT("submit_attempt"),
  REQUEST_COMPUTATION("request_computation"),
  ABANDON("abandon");

  private final String value;

  InitialExplorationAction(String value) {
    this.value = value;
  }

  @JsonValue
  public String value() {
    return value;
  }

  @JsonCreator
  public static InitialExplorationAction fromValue(String value) {
    for (InitialExplorationAction candidate : values()) {
      if (candidate.value.equals(value)) {
        return candidate;
      }
    }
    throw new ContractValidationException("unknown InitialExplorationAction value: " + value);
  }
}
