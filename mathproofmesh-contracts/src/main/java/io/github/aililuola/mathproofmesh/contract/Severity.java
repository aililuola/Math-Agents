package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum Severity {
  INFO("info"),
  WARNING("warning"),
  ERROR("error"),
  CRITICAL("critical");

  private final String value;

  Severity(String value) {
    this.value = value;
  }

  @JsonValue
  public String value() {
    return value;
  }

  @JsonCreator
  public static Severity fromValue(String value) {
    for (Severity candidate : values()) {
      if (candidate.value.equals(value)) {
        return candidate;
      }
    }
    throw new ContractValidationException("unknown Severity value: " + value);
  }
}
