package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum MetaDirectiveAction {
  CONTINUE("continue"),
  REPAIR("repair"),
  REWRITE_PLAN("rewrite_plan"),
  SWITCH_REPRESENTATION("switch_representation"),
  COOLDOWN_ROUTE("cooldown_route"),
  ABANDON_ROUTE("abandon_route"),
  MERGE_ROUTES("merge_routes"),
  ALLOCATE_SURPRISE_BUDGET("allocate_surprise_budget");

  private final String value;

  MetaDirectiveAction(String value) {
    this.value = value;
  }

  @JsonValue
  public String value() {
    return value;
  }

  @JsonCreator
  public static MetaDirectiveAction fromValue(String value) {
    for (MetaDirectiveAction candidate : values()) {
      if (candidate.value.equals(value)) {
        return candidate;
      }
    }
    throw new ContractValidationException("unknown MetaDirectiveAction value: " + value);
  }
}
