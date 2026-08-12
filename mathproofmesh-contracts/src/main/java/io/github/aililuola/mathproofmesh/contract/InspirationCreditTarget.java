package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record InspirationCreditTarget(
    @JsonProperty(value = "materialization_action", required = true) @ContractNonNull String materializationAction,
    @JsonProperty(value = "message_ids") @ContractNonNull List<String> messageIds,
    @JsonProperty(value = "obligation_ids") @ContractNonNull List<String> obligationIds,
    @JsonProperty(value = "proposal_id", required = true) @ContractNonNull String proposalId,
    @JsonProperty(value = "route_ids") @ContractNonNull List<String> routeIds
) implements StrictContract {

  public InspirationCreditTarget {
    materializationAction = ContractStrings.trim(materializationAction);
    materializationAction = ContractStrings.required("materialization_action", materializationAction);
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
    if (routeIds == null) {
      routeIds = List.of();
    }
    routeIds = ImmutableCollections.listOrEmpty(routeIds);
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<String> messageIds() {
    return messageIds == null ? null : List.copyOf(messageIds);
  }

  public List<String> obligationIds() {
    return obligationIds == null ? null : List.copyOf(obligationIds);
  }

  public List<String> routeIds() {
    return routeIds == null ? null : List.copyOf(routeIds);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
