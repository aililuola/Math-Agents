package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record InspirationCandidateDecision(
    @JsonProperty(value = "maximum_similarity") @ContractNonNull Double maximumSimilarity,
    @JsonProperty(value = "nearest_proposal_id") String nearestProposalId,
    @JsonProperty(value = "proposal_id", required = true) @ContractNonNull String proposalId,
    @JsonProperty(value = "rank", required = true) @ContractNonNull Integer rank,
    @JsonProperty(value = "reason", required = true) @ContractNonNull String reason,
    @JsonProperty(value = "selected_for_review", required = true) @ContractNonNull Boolean selectedForReview,
    @JsonProperty(value = "task_id", required = true) @ContractNonNull String taskId
) implements StrictContract {

  public InspirationCandidateDecision {
    if (maximumSimilarity == null) {
      maximumSimilarity = 0.0d;
    }
    ContractValues.minimum("maximum_similarity", maximumSimilarity, 0.0);
    ContractValues.maximum("maximum_similarity", maximumSimilarity, 1.0);
    nearestProposalId = ContractStrings.trim(nearestProposalId);
    proposalId = ContractStrings.trim(proposalId);
    proposalId = ContractStrings.required("proposal_id", proposalId);
    rank = ContractValues.required("rank", rank);
    ContractValues.minimum("rank", rank, 1);
    reason = ContractStrings.trim(reason);
    reason = ContractStrings.required("reason", reason);
    selectedForReview = ContractValues.required("selected_for_review", selectedForReview);
    taskId = ContractStrings.trim(taskId);
    taskId = ContractStrings.required("task_id", taskId);
  }
}
