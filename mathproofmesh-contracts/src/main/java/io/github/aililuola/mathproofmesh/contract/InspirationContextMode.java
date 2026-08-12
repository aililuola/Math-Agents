package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum InspirationContextMode {
  LOCAL("local"),
  WARM("warm"),
  COLD("cold");

  private final String value;

  InspirationContextMode(String value) {
    this.value = value;
  }

  @JsonValue
  public String value() {
    return value;
  }

  @JsonCreator
  public static InspirationContextMode fromValue(String value) {
    for (InspirationContextMode candidate : values()) {
      if (candidate.value.equals(value)) {
        return candidate;
      }
    }
    throw new ContractValidationException("unknown InspirationContextMode value: " + value);
  }
}
