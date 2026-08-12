package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum VerificationStage {
  STRUCTURAL("structural"),
  DETAILED("detailed"),
  LEMMA("lemma"),
  FINAL("final");

  private final String value;

  VerificationStage(String value) {
    this.value = value;
  }

  @JsonValue
  public String value() {
    return value;
  }

  @JsonCreator
  public static VerificationStage fromValue(String value) {
    for (VerificationStage candidate : values()) {
      if (candidate.value.equals(value)) {
        return candidate;
      }
    }
    throw new ContractValidationException("unknown VerificationStage value: " + value);
  }
}
