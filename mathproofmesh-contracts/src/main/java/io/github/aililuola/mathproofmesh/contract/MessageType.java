package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum MessageType {
  CLAIM_PROPOSAL("claim_proposal"),
  VERIFIED_LEMMA("verified_lemma"),
  PROOF_OBLIGATION("proof_obligation"),
  COUNTEREXAMPLE("counterexample"),
  CONTRADICTION_NOTICE("contradiction_notice"),
  COMPUTATION_PLAN("computation_plan"),
  COMPUTATION_CERTIFICATE("computation_certificate"),
  FORMAL_CERTIFICATE("formal_certificate"),
  REPAIR_REQUEST("repair_request"),
  BRIDGE_LEMMA_REQUEST("bridge_lemma_request"),
  STRATEGY_REWRITE_REQUEST("strategy_rewrite_request"),
  FAILURE_RECORD("failure_record"),
  ROUTE_CHECKPOINT("route_checkpoint");

  private final String value;

  MessageType(String value) {
    this.value = value;
  }

  @JsonValue
  public String value() {
    return value;
  }

  @JsonCreator
  public static MessageType fromValue(String value) {
    for (MessageType candidate : values()) {
      if (candidate.value.equals(value)) {
        return candidate;
      }
    }
    throw new ContractValidationException("unknown MessageType value: " + value);
  }
}
