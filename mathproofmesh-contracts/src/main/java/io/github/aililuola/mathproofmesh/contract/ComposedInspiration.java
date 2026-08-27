package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record ComposedInspiration(
    @JsonProperty(value = "combined_mechanism", required = true) @ContractNonNull List<String> combinedMechanism,
    @JsonProperty(value = "compatibility_conditions", required = true) @ContractNonNull List<String> compatibilityConditions,
    @JsonProperty(value = "composition_id", required = true) @ContractNonNull String compositionId,
    @JsonProperty(value = "estimated_cost") @ContractNonNull Integer estimatedCost,
    @JsonProperty(value = "fast_failure_tests", required = true) @ContractNonNull List<String> fastFailureTests,
    @JsonProperty(value = "first_executable_step", required = true) @ContractNonNull String firstExecutableStep,
    @JsonProperty(value = "new_obligations", required = true) @ContractNonNull List<String> newObligations,
    @JsonProperty(value = "novelty_signature", required = true) @ContractNonNull NoveltySignature noveltySignature,
    @JsonProperty(value = "source_proposal_ids", required = true) @ContractNonNull List<String> sourceProposalIds,
    @JsonProperty(value = "target_obligation_ids", required = true) @ContractNonNull List<String> targetObligationIds
) implements StrictContract {

  public ComposedInspiration {
    combinedMechanism = ImmutableCollections.requiredList("combined_mechanism", combinedMechanism);
    compatibilityConditions = ImmutableCollections.requiredList("compatibility_conditions", compatibilityConditions);
    compositionId = ContractStrings.trim(compositionId);
    compositionId = ContractStrings.required("composition_id", compositionId);
    if (estimatedCost == null) {
      estimatedCost = 1;
    }
    ContractValues.minimum("estimated_cost", estimatedCost, 0);
    fastFailureTests = ImmutableCollections.requiredList("fast_failure_tests", fastFailureTests);
    firstExecutableStep = ContractStrings.trim(firstExecutableStep);
    firstExecutableStep = ContractStrings.required("first_executable_step", firstExecutableStep);
    ContractValues.minimumLength("first_executable_step", firstExecutableStep, 1);
    newObligations = ImmutableCollections.requiredList("new_obligations", newObligations);
    noveltySignature = ContractValues.required("novelty_signature", noveltySignature);
    sourceProposalIds = ImmutableCollections.requiredList("source_proposal_ids", sourceProposalIds);
    targetObligationIds = ImmutableCollections.requiredList("target_obligation_ids", targetObligationIds);
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<String> combinedMechanism() {
    return combinedMechanism == null ? null : List.copyOf(combinedMechanism);
  }

  public List<String> compatibilityConditions() {
    return compatibilityConditions == null ? null : List.copyOf(compatibilityConditions);
  }

  public List<String> fastFailureTests() {
    return fastFailureTests == null ? null : List.copyOf(fastFailureTests);
  }

  public List<String> newObligations() {
    return newObligations == null ? null : List.copyOf(newObligations);
  }

  public List<String> sourceProposalIds() {
    return sourceProposalIds == null ? null : List.copyOf(sourceProposalIds);
  }

  public List<String> targetObligationIds() {
    return targetObligationIds == null ? null : List.copyOf(targetObligationIds);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
