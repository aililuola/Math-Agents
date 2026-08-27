package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record SurpriseMutationDirective(
    @JsonProperty(value = "deterministic") @ContractNonNull Boolean deterministic,
    @JsonProperty(value = "directive_id", required = true) @ContractNonNull String directiveId,
    @JsonProperty(value = "fast_failure_tests", required = true) @ContractNonNull List<String> fastFailureTests,
    @JsonProperty(value = "generated_obligations", required = true) @ContractNonNull List<String> generatedObligations,
    @JsonProperty(value = "known_failure_modes", required = true) @ContractNonNull List<String> knownFailureModes,
    @JsonProperty(value = "operator_id", required = true) @ContractNonNull String operatorId,
    @JsonProperty(value = "preconditions", required = true) @ContractNonNull List<String> preconditions,
    @JsonProperty(value = "reversibility_requirements", required = true) @ContractNonNull List<String> reversibilityRequirements,
    @JsonProperty(value = "seed", required = true) @ContractNonNull Integer seed,
    @JsonProperty(value = "suggested_tools") @ContractNonNull List<String> suggestedTools,
    @JsonProperty(value = "target_obligation_ids", required = true) @ContractNonNull List<String> targetObligationIds,
    @JsonProperty(value = "transformation", required = true) @ContractNonNull String transformation
) implements StrictContract {

  public SurpriseMutationDirective {
    if (deterministic == null) {
      deterministic = true;
    }
    ContractValues.constant("deterministic", deterministic, true);
    directiveId = ContractStrings.trim(directiveId);
    directiveId = ContractStrings.required("directive_id", directiveId);
    fastFailureTests = ImmutableCollections.requiredList("fast_failure_tests", fastFailureTests);
    generatedObligations = ImmutableCollections.requiredList("generated_obligations", generatedObligations);
    knownFailureModes = ImmutableCollections.requiredList("known_failure_modes", knownFailureModes);
    operatorId = ContractStrings.trim(operatorId);
    operatorId = ContractStrings.required("operator_id", operatorId);
    preconditions = ImmutableCollections.requiredList("preconditions", preconditions);
    reversibilityRequirements = ImmutableCollections.requiredList("reversibility_requirements", reversibilityRequirements);
    seed = ContractValues.required("seed", seed);
    ContractValues.minimum("seed", seed, 0);
    if (suggestedTools == null) {
      suggestedTools = List.of();
    }
    suggestedTools = ImmutableCollections.listOrEmpty(suggestedTools);
    targetObligationIds = ImmutableCollections.requiredList("target_obligation_ids", targetObligationIds);
    transformation = ContractStrings.trim(transformation);
    transformation = ContractStrings.required("transformation", transformation);
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<String> fastFailureTests() {
    return fastFailureTests == null ? null : List.copyOf(fastFailureTests);
  }

  public List<String> generatedObligations() {
    return generatedObligations == null ? null : List.copyOf(generatedObligations);
  }

  public List<String> knownFailureModes() {
    return knownFailureModes == null ? null : List.copyOf(knownFailureModes);
  }

  public List<String> preconditions() {
    return preconditions == null ? null : List.copyOf(preconditions);
  }

  public List<String> reversibilityRequirements() {
    return reversibilityRequirements == null ? null : List.copyOf(reversibilityRequirements);
  }

  public List<String> suggestedTools() {
    return suggestedTools == null ? null : List.copyOf(suggestedTools);
  }

  public List<String> targetObligationIds() {
    return targetObligationIds == null ? null : List.copyOf(targetObligationIds);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
