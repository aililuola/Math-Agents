package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum InspirationMechanism {
  REPRESENTATION_SWITCH("representation_switch"),
  STRUCTURAL_ANALOGY("structural_analogy"),
  AUXILIARY_CONSTRUCTION("auxiliary_construction"),
  INVARIANT_HYPOTHESIS("invariant_hypothesis"),
  REVERSE_GOAL_ANALYSIS("reverse_goal_analysis"),
  BRIDGE_LEMMA("bridge_lemma"),
  SURPRISE_EXPLORATION("surprise_exploration"),
  META_REPLAN("meta_replan"),
  INSPIRATION_COMPOSITION("inspiration_composition");

  private final String value;

  InspirationMechanism(String value) {
    this.value = value;
  }

  @JsonValue
  public String value() {
    return value;
  }

  @JsonCreator
  public static InspirationMechanism fromValue(String value) {
    for (InspirationMechanism candidate : values()) {
      if (candidate.value.equals(value)) {
        return candidate;
      }
    }
    throw new ContractValidationException("unknown InspirationMechanism value: " + value);
  }
}
