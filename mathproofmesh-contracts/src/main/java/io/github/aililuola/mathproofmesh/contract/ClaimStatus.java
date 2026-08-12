package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ClaimStatus {
  PROPOSED("proposed"),
  VERIFIED("verified"),
  REJECTED("rejected"),
  UNCERTAIN("uncertain");

  private final String value;

  ClaimStatus(String value) {
    this.value = value;
  }

  @JsonValue
  public String value() {
    return value;
  }

  @JsonCreator
  public static ClaimStatus fromValue(String value) {
    for (ClaimStatus candidate : values()) {
      if (candidate.value.equals(value)) {
        return candidate;
      }
    }
    throw new ContractValidationException("unknown ClaimStatus value: " + value);
  }
}
