package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record InspirationMaterialization(
    @JsonProperty(value = "action", required = true) @ContractNonNull String action,
    @JsonProperty(value = "message_ids") @ContractNonNull List<String> messageIds,
    @JsonProperty(value = "obligation_ids") @ContractNonNull List<String> obligationIds,
    @JsonProperty(value = "proposal_id", required = true) @ContractNonNull String proposalId,
    @JsonProperty(value = "reason") @ContractNonNull String reason,
    @JsonProperty(value = "route_id") String routeId
) implements StrictContract {

  public InspirationMaterialization {
    action = ContractStrings.trim(action);
    action = ContractStrings.required("action", action);
    ContractValues.oneOf("action", action, "shadow_only", "rejected", "stored_insight", "attached", "route_created", "computation_requested", "bridge_requested");
    if (messageIds == null) {
      messageIds = List.of();
    }
    messageIds = ImmutableCollections.listOrEmpty(messageIds);
    if (obligationIds == null) {
      obligationIds = List.of();
    }
    obligationIds = ImmutableCollections.listOrEmpty(obligationIds);
    proposalId = ContractStrings.trim(proposalId);
    proposalId = ContractStrings.required("proposal_id", proposalId);
    if (reason == null) {
      reason = "";
    }
    reason = ContractStrings.trim(reason);
    routeId = ContractStrings.trim(routeId);
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<String> messageIds() {
    return messageIds == null ? null : List.copyOf(messageIds);
  }

  public List<String> obligationIds() {
    return obligationIds == null ? null : List.copyOf(obligationIds);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
