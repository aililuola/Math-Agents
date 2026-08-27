package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record ConstructionProposal(
    @JsonProperty(value = "constructed_objects", required = true) @ContractNonNull List<String> constructedObjects,
    @JsonProperty(value = "construction_type", required = true) @ContractNonNull String constructionType,
    @JsonProperty(value = "definition", required = true) @ContractNonNull String definition,
    @JsonProperty(value = "expected_invariant_or_relation", required = true) @ContractNonNull String expectedInvariantOrRelation,
    @JsonProperty(value = "expected_proof_debt_reduction") @ContractNonNull String expectedProofDebtReduction,
    @JsonProperty(value = "failure_conditions") @ContractNonNull List<String> failureConditions,
    @JsonProperty(value = "falsification_tests", required = true) @ContractNonNull List<String> falsificationTests,
    @JsonProperty(value = "generated_obligations") @ContractNonNull List<String> generatedObligations,
    @JsonProperty(value = "intended_obligations", required = true) @ContractNonNull List<String> intendedObligations,
    @JsonProperty(value = "novelty_signature", required = true) @ContractNonNull NoveltySignature noveltySignature,
    @JsonProperty(value = "operator_id") String operatorId,
    @JsonProperty(value = "operator_preconditions") @ContractNonNull List<String> operatorPreconditions,
    @JsonProperty(value = "proposal_id") @ContractNonNull String proposalId,
    @JsonProperty(value = "reversibility_requirements") @ContractNonNull List<String> reversibilityRequirements,
    @JsonProperty(value = "suggested_tools") @ContractNonNull List<String> suggestedTools
) implements StrictContract {

  public ConstructionProposal {
    constructedObjects = ImmutableCollections.requiredList("constructed_objects", constructedObjects);
    constructionType = ContractStrings.trim(constructionType);
    constructionType = ContractStrings.required("construction_type", constructionType);
    definition = ContractStrings.trim(definition);
    definition = ContractStrings.required("definition", definition);
    ContractValues.minimumLength("definition", definition, 1);
    expectedInvariantOrRelation = ContractStrings.trim(expectedInvariantOrRelation);
    expectedInvariantOrRelation = ContractStrings.required("expected_invariant_or_relation", expectedInvariantOrRelation);
    if (expectedProofDebtReduction == null) {
      expectedProofDebtReduction = "";
    }
    expectedProofDebtReduction = ContractStrings.trim(expectedProofDebtReduction);
    if (failureConditions == null) {
      failureConditions = List.of();
    }
    failureConditions = ImmutableCollections.listOrEmpty(failureConditions);
    falsificationTests = ImmutableCollections.requiredList("falsification_tests", falsificationTests);
    if (generatedObligations == null) {
      generatedObligations = List.of();
    }
    generatedObligations = ImmutableCollections.listOrEmpty(generatedObligations);
    intendedObligations = ImmutableCollections.requiredList("intended_obligations", intendedObligations);
    noveltySignature = ContractValues.required("novelty_signature", noveltySignature);
    operatorId = ContractStrings.trim(operatorId);
    if (operatorPreconditions == null) {
      operatorPreconditions = List.of();
    }
    operatorPreconditions = ImmutableCollections.listOrEmpty(operatorPreconditions);
    if (proposalId == null) {
      proposalId = PythonCompatibleIdGenerator.newId("construction");
    }
    proposalId = ContractStrings.trim(proposalId);
    if (reversibilityRequirements == null) {
      reversibilityRequirements = List.of();
    }
    reversibilityRequirements = ImmutableCollections.listOrEmpty(reversibilityRequirements);
    if (suggestedTools == null) {
      suggestedTools = List.of();
    }
    suggestedTools = ImmutableCollections.listOrEmpty(suggestedTools);
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<String> constructedObjects() {
    return constructedObjects == null ? null : List.copyOf(constructedObjects);
  }

  public List<String> failureConditions() {
    return failureConditions == null ? null : List.copyOf(failureConditions);
  }

  public List<String> falsificationTests() {
    return falsificationTests == null ? null : List.copyOf(falsificationTests);
  }

  public List<String> generatedObligations() {
    return generatedObligations == null ? null : List.copyOf(generatedObligations);
  }

  public List<String> intendedObligations() {
    return intendedObligations == null ? null : List.copyOf(intendedObligations);
  }

  public List<String> operatorPreconditions() {
    return operatorPreconditions == null ? null : List.copyOf(operatorPreconditions);
  }

  public List<String> reversibilityRequirements() {
    return reversibilityRequirements == null ? null : List.copyOf(reversibilityRequirements);
  }

  public List<String> suggestedTools() {
    return suggestedTools == null ? null : List.copyOf(suggestedTools);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
