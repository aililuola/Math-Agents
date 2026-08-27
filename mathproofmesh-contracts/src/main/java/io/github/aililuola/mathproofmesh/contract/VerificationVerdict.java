package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum VerificationVerdict {
  PASS("pass"),
  FAIL("fail"),
  UNCERTAIN("uncertain"),
  SKIPPED("skipped");

  private final String value;

  VerificationVerdict(String value) {
    this.value = value;
  }

  @JsonValue
  public String value() {
    return value;
  }

  @JsonCreator
  public static VerificationVerdict fromValue(String value) {
    for (VerificationVerdict candidate : values()) {
      if (candidate.value.equals(value)) {
        return candidate;
      }
    }
    throw new ContractValidationException("unknown VerificationVerdict value: " + value);
  }
}
