package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record InspirationReview(
    @JsonProperty(value = "confidence", required = true) @ContractNonNull Double confidence,
    @JsonProperty(value = "deferred_reason") @ContractNonNull String deferredReason,
    @JsonProperty(value = "hidden_assumptions") @ContractNonNull List<String> hiddenAssumptions,
    @JsonProperty(value = "immediate_counterexamples") @ContractNonNull List<String> immediateCounterexamples,
    @JsonProperty(value = "internally_coherent", required = true) @ContractNonNull Boolean internallyCoherent,
    @JsonProperty(value = "proposal_id", required = true) @ContractNonNull String proposalId,
    @JsonProperty(value = "recommendation", required = true) @ContractNonNull String recommendation,
    @JsonProperty(value = "relevant_to_open_obligation", required = true) @ContractNonNull Boolean relevantToOpenObligation,
    @JsonProperty(value = "review_action_id") String reviewActionId,
    @JsonProperty(value = "review_status") @ContractNonNull String reviewStatus,
    @JsonProperty(value = "reviewer_agent_id", required = true) @ContractNonNull String reviewerAgentId,
    @JsonProperty(value = "semantically_distinct", required = true) @ContractNonNull Boolean semanticallyDistinct
) implements StrictContract {

  public InspirationReview {
    confidence = ContractValues.required("confidence", confidence);
    ContractValues.minimum("confidence", confidence, 0.0);
    ContractValues.maximum("confidence", confidence, 1.0);
    if (deferredReason == null) {
      deferredReason = "";
    }
    deferredReason = ContractStrings.trim(deferredReason);
    if (hiddenAssumptions == null) {
      hiddenAssumptions = List.of();
    }
    hiddenAssumptions = ImmutableCollections.listOrEmpty(hiddenAssumptions);
    if (immediateCounterexamples == null) {
      immediateCounterexamples = List.of();
    }
    immediateCounterexamples = ImmutableCollections.listOrEmpty(immediateCounterexamples);
    internallyCoherent = ContractValues.required("internally_coherent", internallyCoherent);
    proposalId = ContractStrings.trim(proposalId);
    proposalId = ContractStrings.required("proposal_id", proposalId);
    recommendation = ContractStrings.trim(recommendation);
    recommendation = ContractStrings.required("recommendation", recommendation);
    ContractValues.oneOf("recommendation", recommendation, "reject", "store_insight", "attach_to_existing_route", "create_new_route", "request_computation", "request_bridge_verification");
    relevantToOpenObligation = ContractValues.required("relevant_to_open_obligation", relevantToOpenObligation);
    reviewActionId = ContractStrings.trim(reviewActionId);
    if (reviewStatus == null) {
      reviewStatus = "completed";
    }
    reviewStatus = ContractStrings.trim(reviewStatus);
    ContractValues.oneOf("review_status", reviewStatus, "completed", "deferred");
    reviewerAgentId = ContractStrings.trim(reviewerAgentId);
    reviewerAgentId = ContractStrings.required("reviewer_agent_id", reviewerAgentId);
    semanticallyDistinct = ContractValues.required("semantically_distinct", semanticallyDistinct);
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<String> hiddenAssumptions() {
    return hiddenAssumptions == null ? null : List.copyOf(hiddenAssumptions);
  }

  public List<String> immediateCounterexamples() {
    return immediateCounterexamples == null ? null : List.copyOf(immediateCounterexamples);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
