package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum Difficulty {
  EASY("easy"),
  MEDIUM("medium"),
  HARD("hard"),
  OLYMPIAD("olympiad"),
  RESEARCH("research");

  private final String value;

  Difficulty(String value) {
    this.value = value;
  }

  @JsonValue
  public String value() {
    return value;
  }

  @JsonCreator
  public static Difficulty fromValue(String value) {
    for (Difficulty candidate : values()) {
      if (candidate.value.equals(value)) {
        return candidate;
      }
    }
    throw new ContractValidationException("unknown Difficulty value: " + value);
  }
}
