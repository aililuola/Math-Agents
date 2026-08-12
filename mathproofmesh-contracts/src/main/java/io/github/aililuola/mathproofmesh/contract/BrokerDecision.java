package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record BrokerDecision(
    @JsonProperty(value = "accepted", required = true) @ContractNonNull Boolean accepted,
    @JsonProperty(value = "accepted_claim_ids") @ContractNonNull List<String> acceptedClaimIds,
    @JsonProperty(value = "bridge_task_id") String bridgeTaskId,
    @JsonProperty(value = "contradiction_id") String contradictionId,
    @JsonProperty(value = "deferred_claim_ids") @ContractNonNull List<String> deferredClaimIds,
    @JsonProperty(value = "duplicate_of") String duplicateOf,
    @JsonProperty(value = "message_id", required = true) @ContractNonNull String messageId,
    @JsonProperty(value = "rejected_claim_ids") @ContractNonNull List<String> rejectedClaimIds,
    @JsonProperty(value = "rejected_targets") @ContractNonNull Map<String, String> rejectedTargets,
    @JsonProperty(value = "rejection_reason") String rejectionReason,
    @JsonProperty(value = "score_breakdown") @ContractNonNull Map<String, Double> scoreBreakdown,
    @JsonProperty(value = "selected_targets") @ContractNonNull List<String> selectedTargets
) implements StrictContract {

  public BrokerDecision {
    accepted = ContractValues.required("accepted", accepted);
    if (acceptedClaimIds == null) {
      acceptedClaimIds = List.of();
    }
    acceptedClaimIds = ImmutableCollections.listOrEmpty(acceptedClaimIds);
    bridgeTaskId = ContractStrings.trim(bridgeTaskId);
    contradictionId = ContractStrings.trim(contradictionId);
    if (deferredClaimIds == null) {
      deferredClaimIds = List.of();
    }
    deferredClaimIds = ImmutableCollections.listOrEmpty(deferredClaimIds);
    duplicateOf = ContractStrings.trim(duplicateOf);
    messageId = ContractStrings.trim(messageId);
    messageId = ContractStrings.required("message_id", messageId);
    if (rejectedClaimIds == null) {
      rejectedClaimIds = List.of();
    }
    rejectedClaimIds = ImmutableCollections.listOrEmpty(rejectedClaimIds);
    if (rejectedTargets == null) {
      rejectedTargets = Map.of();
    }
    rejectedTargets = ImmutableCollections.mapOrEmpty(rejectedTargets);
    rejectionReason = ContractStrings.trim(rejectionReason);
    if (scoreBreakdown == null) {
      scoreBreakdown = Map.of();
    }
    scoreBreakdown = ImmutableCollections.mapOrEmpty(scoreBreakdown);
    if (selectedTargets == null) {
      selectedTargets = List.of();
    }
    selectedTargets = ImmutableCollections.listOrEmpty(selectedTargets);
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<String> acceptedClaimIds() {
    return acceptedClaimIds == null ? null : List.copyOf(acceptedClaimIds);
  }

  public List<String> deferredClaimIds() {
    return deferredClaimIds == null ? null : List.copyOf(deferredClaimIds);
  }

  public List<String> rejectedClaimIds() {
    return rejectedClaimIds == null ? null : List.copyOf(rejectedClaimIds);
  }

  public Map<String, String> rejectedTargets() {
    return rejectedTargets == null ? null : Map.copyOf(rejectedTargets);
  }

  public Map<String, Double> scoreBreakdown() {
    return scoreBreakdown == null ? null : Map.copyOf(scoreBreakdown);
  }

  public List<String> selectedTargets() {
    return selectedTargets == null ? null : List.copyOf(selectedTargets);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
