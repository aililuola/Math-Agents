package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record MetaReview(
    @JsonProperty(value = "assessments") @ContractNonNull List<CandidateAssessment> assessments,
    @JsonProperty(value = "broad_computation_approved_strategy_ids") @ContractNonNull List<String> broadComputationApprovedStrategyIds,
    @JsonProperty(value = "can_synthesize") @ContractNonNull Boolean canSynthesize,
    @JsonProperty(value = "confidence", required = true) @ContractNonNull Double confidence,
    @JsonProperty(value = "failure_level") @ContractNonNull FailureLevel failureLevel,
    @JsonProperty(value = "required_actions") @ContractNonNull List<String> requiredActions,
    @JsonProperty(value = "selected_target_id") String selectedTargetId,
    @JsonProperty(value = "shared_agreements") @ContractNonNull List<String> sharedAgreements,
    @JsonProperty(value = "summary", required = true) @ContractNonNull String summary,
    @JsonProperty(value = "unresolved_conflicts") @ContractNonNull List<String> unresolvedConflicts
) implements StrictContract {

  public MetaReview {
    if (assessments == null) {
      assessments = List.of();
    }
    assessments = ImmutableCollections.listOrEmpty(assessments);
    if (broadComputationApprovedStrategyIds == null) {
      broadComputationApprovedStrategyIds = List.of();
    }
    broadComputationApprovedStrategyIds = ImmutableCollections.listOrEmpty(broadComputationApprovedStrategyIds);
    if (canSynthesize == null) {
      canSynthesize = false;
    }
    confidence = ContractValues.required("confidence", confidence);
    ContractValues.minimum("confidence", confidence, 0.0);
    ContractValues.maximum("confidence", confidence, 1.0);
    if (failureLevel == null) {
      failureLevel = FailureLevel.NONE;
    }
    if (requiredActions == null) {
      requiredActions = List.of();
    }
    requiredActions = ImmutableCollections.listOrEmpty(requiredActions);
    selectedTargetId = ContractStrings.trim(selectedTargetId);
    if (sharedAgreements == null) {
      sharedAgreements = List.of();
    }
    sharedAgreements = ImmutableCollections.listOrEmpty(sharedAgreements);
    summary = ContractStrings.trim(summary);
    summary = ContractStrings.required("summary", summary);
    if (unresolvedConflicts == null) {
      unresolvedConflicts = List.of();
    }
    unresolvedConflicts = ImmutableCollections.listOrEmpty(unresolvedConflicts);
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<CandidateAssessment> assessments() {
    return assessments == null ? null : List.copyOf(assessments);
  }

  public List<String> broadComputationApprovedStrategyIds() {
    return broadComputationApprovedStrategyIds == null ? null : List.copyOf(broadComputationApprovedStrategyIds);
  }

  public List<String> requiredActions() {
    return requiredActions == null ? null : List.copyOf(requiredActions);
  }

  public List<String> sharedAgreements() {
    return sharedAgreements == null ? null : List.copyOf(sharedAgreements);
  }

  public List<String> unresolvedConflicts() {
    return unresolvedConflicts == null ? null : List.copyOf(unresolvedConflicts);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
