package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum MemoryTier {
  FACT("fact"),
  INSIGHT("insight"),
  NEGATIVE("negative");

  private final String value;

  MemoryTier(String value) {
    this.value = value;
  }

  @JsonValue
  public String value() {
    return value;
  }

  @JsonCreator
  public static MemoryTier fromValue(String value) {
    for (MemoryTier candidate : values()) {
      if (candidate.value.equals(value)) {
        return candidate;
      }
    }
    throw new ContractValidationException("unknown MemoryTier value: " + value);
  }
}
