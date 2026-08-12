package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ObligationKind {
  MAIN_GOAL("main_goal"),
  SUBGOAL("subgoal"),
  LEMMA("lemma"),
  CASE_BRANCH("case_branch"),
  CONSTRUCTION("construction"),
  COMPUTATION_QUESTION("computation_question"),
  FORMALIZATION_TASK("formalization_task"),
  CONTRADICTION("contradiction");

  private final String value;

  ObligationKind(String value) {
    this.value = value;
  }

  @JsonValue
  public String value() {
    return value;
  }

  @JsonCreator
  public static ObligationKind fromValue(String value) {
    for (ObligationKind candidate : values()) {
      if (candidate.value.equals(value)) {
        return candidate;
      }
    }
    throw new ContractValidationException("unknown ObligationKind value: " + value);
  }
}
