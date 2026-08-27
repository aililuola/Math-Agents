package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ProblemKind {
  PROOF("proof"),
  CALCULATION("calculation"),
  LOGIC("logic"),
  OPTIMIZATION("optimization"),
  CONSTRUCTION("construction"),
  RESEARCH("research"),
  MIXED("mixed"),
  UNKNOWN("unknown");

  private final String value;

  ProblemKind(String value) {
    this.value = value;
  }

  @JsonValue
  public String value() {
    return value;
  }

  @JsonCreator
  public static ProblemKind fromValue(String value) {
    for (ProblemKind candidate : values()) {
      if (candidate.value.equals(value)) {
        return candidate;
      }
    }
    throw new ContractValidationException("unknown ProblemKind value: " + value);
  }
}
