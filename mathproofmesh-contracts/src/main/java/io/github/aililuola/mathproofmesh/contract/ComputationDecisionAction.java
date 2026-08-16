package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

public enum ComputationDecisionAction {
  SUBMIT_EXACT_COUNTEREXAMPLE,
  SATISFY_FINITE_DOMAIN_OBLIGATION,
  ATTACH_FORMAL_CERTIFICATE,
  RECORD_BOUNDED_OBSERVATION,
  CREATE_EXACT_MICRO_OBLIGATION,
  REQUEST_CLAIM_COURT_REVIEW,
  REQUEST_SEMANTIC_PIVOT_CONSIDERATION,
  RETAIN_AUDIT_ONLY,
  NO_STATE_CHANGE;

  @JsonValue
  public String value() {
    return name().toLowerCase(Locale.ROOT);
  }

  @JsonCreator
  public static ComputationDecisionAction fromValue(String value) {
    try {
      return valueOf(value.toUpperCase(Locale.ROOT));
    } catch (RuntimeException exception) {
      throw new ContractValidationException(
          "unknown ComputationDecisionAction value: " + value, exception);
    }
  }
}
