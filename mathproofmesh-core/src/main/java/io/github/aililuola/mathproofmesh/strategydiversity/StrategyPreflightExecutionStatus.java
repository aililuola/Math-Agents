package io.github.aililuola.mathproofmesh.strategydiversity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Durable phases of one registered, deterministic strategy preflight action. */
public enum StrategyPreflightExecutionStatus {
  RESERVED("reserved"),
  RUNNING("running"),
  RESULT_DURABLE("result_durable"),
  COMPLETED("completed"),
  ABORTED("aborted");

  private final String value;

  StrategyPreflightExecutionStatus(String value) {
    this.value = value;
  }

  @JsonValue
  public String value() {
    return value;
  }

  @JsonCreator
  public static StrategyPreflightExecutionStatus fromValue(String value) {
    if ("started".equals(value)) {
      return RUNNING;
    }
    for (StrategyPreflightExecutionStatus candidate : values()) {
      if (candidate.value.equals(value) || candidate.name().equals(value)) {
        return candidate;
      }
    }
    throw new IllegalArgumentException("unknown strategy preflight status: " + value);
  }
}
