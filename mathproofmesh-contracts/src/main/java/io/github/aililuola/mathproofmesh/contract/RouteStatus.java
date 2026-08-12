package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum RouteStatus {
  ACTIVE("active"),
  WAITING("waiting"),
  REPAIR_ONCE("repair_once"),
  FROZEN("frozen"),
  TERMINAL("terminal"),
  FROZEN_STALLED("frozen_stalled"),
  REFUTED("refuted"),
  COOLING("cooling"),
  MERGED("merged"),
  ABANDONED("abandoned"),
  COMPLETED("completed");

  private final String value;

  RouteStatus(String value) {
    this.value = value;
  }

  @JsonValue
  public String value() {
    return value;
  }

  @JsonCreator
  public static RouteStatus fromValue(String value) {
    for (RouteStatus candidate : values()) {
      if (candidate.value.equals(value)) {
        return candidate;
      }
    }
    throw new ContractValidationException("unknown RouteStatus value: " + value);
  }
}
