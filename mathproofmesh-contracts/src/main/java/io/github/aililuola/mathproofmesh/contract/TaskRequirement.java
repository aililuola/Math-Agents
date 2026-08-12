package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum TaskRequirement {
  PROOF("proof"),
  SOLUTION("solution"),
  COMPUTATION("computation"),
  CONJECTURE("conjecture"),
  COUNTEREXAMPLE("counterexample"),
  CLASSIFICATION("classification"),
  OPTIMIZATION("optimization"),
  CONSTRUCTION("construction"),
  RESEARCH_PROGRESS("research_progress");

  private final String value;

  TaskRequirement(String value) {
    this.value = value;
  }

  @JsonValue
  public String value() {
    return value;
  }

  @JsonCreator
  public static TaskRequirement fromValue(String value) {
    for (TaskRequirement candidate : values()) {
      if (candidate.value.equals(value)) {
        return candidate;
      }
    }
    throw new ContractValidationException("unknown TaskRequirement value: " + value);
  }
}
