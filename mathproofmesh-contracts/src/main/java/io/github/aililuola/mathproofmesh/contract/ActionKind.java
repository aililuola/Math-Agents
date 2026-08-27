package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ActionKind {
  WIDEN("widen"),
  DEEPEN("deepen"),
  VERIFY("verify"),
  SYNTHESIZE("synthesize"),
  REVISE("revise"),
  BRIDGE("bridge"),
  RESOLVE_CONFLICT("resolve_conflict"),
  SEARCH_COUNTEREXAMPLE("search_counterexample"),
  MERGE_ROUTE("merge_route"),
  COOLDOWN_ROUTE("cooldown_route"),
  SWITCH_REPRESENTATION("switch_representation"),
  TRIGGER_INSPIRATION("trigger_inspiration"),
  SEARCH_ANALOGY("search_analogy"),
  INVENT_CONSTRUCTION("invent_construction"),
  GENERATE_INVARIANT("generate_invariant"),
  REVERSE_GOAL("reverse_goal"),
  META_REPLAN("meta_replan"),
  SURPRISE_WIDEN("surprise_widen"),
  STOP("stop");

  private final String value;

  ActionKind(String value) {
    this.value = value;
  }

  @JsonValue
  public String value() {
    return value;
  }

  @JsonCreator
  public static ActionKind fromValue(String value) {
    for (ActionKind candidate : values()) {
      if (candidate.value.equals(value)) {
        return candidate;
      }
    }
    throw new ContractValidationException("unknown ActionKind value: " + value);
  }
}
