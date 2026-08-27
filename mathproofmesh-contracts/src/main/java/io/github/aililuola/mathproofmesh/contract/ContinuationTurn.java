package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record ContinuationTurn(
    @JsonProperty(value = "action", required = true) @ContractNonNull ContinuationAction action,
    @JsonProperty(value = "delta") ProofDelta delta,
    @JsonProperty(value = "experiment_impact") FailureLevel experimentImpact,
    @JsonProperty(value = "experiment_spec") ExperimentSpec experimentSpec,
    @JsonProperty(value = "message_receipts") @ContractNonNull List<MessageReceipt> messageReceipts,
    @JsonProperty(value = "reason") @ContractNonNull String reason
) implements StrictContract {

  public ContinuationTurn {
    action = ContractValues.required("action", action);
    if (messageReceipts == null) {
      messageReceipts = List.of();
    }
    messageReceipts = ImmutableCollections.listOrEmpty(messageReceipts);
    if (reason == null) {
      reason = "";
    }
    reason = ContractStrings.trim(reason);
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<MessageReceipt> messageReceipts() {
    return messageReceipts == null ? null : List.copyOf(messageReceipts);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
