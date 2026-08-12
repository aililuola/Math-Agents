package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record InvariantHypothesis(
    @JsonProperty(value = "allowed_operations", required = true) @ContractNonNull List<String> allowedOperations,
    @JsonProperty(value = "behavior", required = true) @ContractNonNull String behavior,
    @JsonProperty(value = "boundary_case", required = true) @ContractNonNull String boundaryCase,
    @JsonProperty(value = "boundary_result", required = true) @ContractNonNull String boundaryResult,
    @JsonProperty(value = "candidate_expression", required = true) @ContractNonNull String candidateExpression,
    @JsonProperty(value = "falsification_request", required = true) @ContractNonNull String falsificationRequest,
    @JsonProperty(value = "hypothesis_id") @ContractNonNull String hypothesisId,
    @JsonProperty(value = "novelty_signature", required = true) @ContractNonNull NoveltySignature noveltySignature,
    @JsonProperty(value = "state_definition", required = true) @ContractNonNull String stateDefinition,
    @JsonProperty(value = "target_obligation_ids", required = true) @ContractNonNull List<String> targetObligationIds
) implements StrictContract {

  public InvariantHypothesis {
    allowedOperations = ImmutableCollections.requiredList("allowed_operations", allowedOperations);
    behavior = ContractStrings.trim(behavior);
    behavior = ContractStrings.required("behavior", behavior);
    ContractValues.oneOf("behavior", behavior, "invariant", "nondecreasing", "nonincreasing");
    boundaryCase = ContractStrings.trim(boundaryCase);
    boundaryCase = ContractStrings.required("boundary_case", boundaryCase);
    ContractValues.minimumLength("boundary_case", boundaryCase, 1);
    boundaryResult = ContractStrings.trim(boundaryResult);
    boundaryResult = ContractStrings.required("boundary_result", boundaryResult);
    ContractValues.minimumLength("boundary_result", boundaryResult, 1);
    candidateExpression = ContractStrings.trim(candidateExpression);
    candidateExpression = ContractStrings.required("candidate_expression", candidateExpression);
    ContractValues.minimumLength("candidate_expression", candidateExpression, 1);
    falsificationRequest = ContractStrings.trim(falsificationRequest);
    falsificationRequest = ContractStrings.required("falsification_request", falsificationRequest);
    ContractValues.minimumLength("falsification_request", falsificationRequest, 1);
    if (hypothesisId == null) {
      hypothesisId = PythonCompatibleIdGenerator.newId("invariant");
    }
    hypothesisId = ContractStrings.trim(hypothesisId);
    noveltySignature = ContractValues.required("novelty_signature", noveltySignature);
    stateDefinition = ContractStrings.trim(stateDefinition);
    stateDefinition = ContractStrings.required("state_definition", stateDefinition);
    ContractValues.minimumLength("state_definition", stateDefinition, 1);
    targetObligationIds = ImmutableCollections.requiredList("target_obligation_ids", targetObligationIds);
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<String> allowedOperations() {
    return allowedOperations == null ? null : List.copyOf(allowedOperations);
  }

  public List<String> targetObligationIds() {
    return targetObligationIds == null ? null : List.copyOf(targetObligationIds);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
