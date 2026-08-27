package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum RouteRole {
  PROVER("prover"),
  SKEPTIC("skeptic"),
  TOOL_SPECIALIST("tool_specialist"),
  REFEREE("referee"),
  BRIDGE_PROVER("bridge_prover"),
  CONFLICT_RESOLVER("conflict_resolver"),
  COUNTEREXAMPLE_HUNTER("counterexample_hunter");

  private final String value;

  RouteRole(String value) {
    this.value = value;
  }

  @JsonValue
  public String value() {
    return value;
  }

  @JsonCreator
  public static RouteRole fromValue(String value) {
    for (RouteRole candidate : values()) {
      if (candidate.value.equals(value)) {
        return candidate;
      }
    }
    throw new ContractValidationException("unknown RouteRole value: " + value);
  }
}
