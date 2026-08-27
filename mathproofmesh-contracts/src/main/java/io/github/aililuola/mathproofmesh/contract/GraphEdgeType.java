package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum GraphEdgeType {
  DEPENDS_ON("depends_on"),
  IMPLIES("implies"),
  REFUTES("refutes"),
  EQUIVALENT_TO("equivalent_to"),
  STRENGTHENS("strengthens"),
  WEAKENS("weakens"),
  USES_CONSTRUCTION("uses_construction"),
  CLOSES("closes");

  private final String value;

  GraphEdgeType(String value) {
    this.value = value;
  }

  @JsonValue
  public String value() {
    return value;
  }

  @JsonCreator
  public static GraphEdgeType fromValue(String value) {
    for (GraphEdgeType candidate : values()) {
      if (candidate.value.equals(value)) {
        return candidate;
      }
    }
    throw new ContractValidationException("unknown GraphEdgeType value: " + value);
  }
}
