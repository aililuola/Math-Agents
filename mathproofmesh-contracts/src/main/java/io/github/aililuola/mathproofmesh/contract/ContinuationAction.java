package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ContinuationAction {
  SUBMIT_DELTA("submit_delta"),
  REQUEST_COMPUTATION("request_computation"),
  COMPLETE("complete"),
  ABANDON("abandon");

  private final String value;

  ContinuationAction(String value) {
    this.value = value;
  }

  @JsonValue
  public String value() {
    return value;
  }

  @JsonCreator
  public static ContinuationAction fromValue(String value) {
    for (ContinuationAction candidate : values()) {
      if (candidate.value.equals(value)) {
        return candidate;
      }
    }
    throw new ContractValidationException("unknown ContinuationAction value: " + value);
  }
}
