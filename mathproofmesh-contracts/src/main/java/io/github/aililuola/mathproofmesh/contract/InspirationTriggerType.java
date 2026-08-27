package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum InspirationTriggerType {
  STAGNATION("stagnation"),
  REPEATED_FIRST_ERROR("repeated_first_error"),
  HIGH_ROUTE_REDUNDANCY("high_route_redundancy"),
  ALL_ROUTES_FAILED("all_routes_failed"),
  SHARED_BOTTLENECK("shared_bottleneck"),
  PROOF_DEBT_PLATEAU("proof_debt_plateau"),
  FINAL_REPAIR_FAILED("final_repair_failed"),
  MANUAL("manual");

  private final String value;

  InspirationTriggerType(String value) {
    this.value = value;
  }

  @JsonValue
  public String value() {
    return value;
  }

  @JsonCreator
  public static InspirationTriggerType fromValue(String value) {
    for (InspirationTriggerType candidate : values()) {
      if (candidate.value.equals(value)) {
        return candidate;
      }
    }
    throw new ContractValidationException("unknown InspirationTriggerType value: " + value);
  }
}
