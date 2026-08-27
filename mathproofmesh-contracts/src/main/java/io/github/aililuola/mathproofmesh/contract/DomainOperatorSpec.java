package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record DomainOperatorSpec(
    @JsonProperty(value = "applicability_tokens") @ContractNonNull List<String> applicabilityTokens,
    @JsonProperty(value = "domain", required = true) @ContractNonNull String domain,
    @JsonProperty(value = "family", required = true) @ContractNonNull String family,
    @JsonProperty(value = "fast_failure_tests", required = true) @ContractNonNull List<String> fastFailureTests,
    @JsonProperty(value = "generated_obligations", required = true) @ContractNonNull List<String> generatedObligations,
    @JsonProperty(value = "known_failure_modes", required = true) @ContractNonNull List<String> knownFailureModes,
    @JsonProperty(value = "mechanism_tags") @ContractNonNull List<String> mechanismTags,
    @JsonProperty(value = "object_tags") @ContractNonNull List<String> objectTags,
    @JsonProperty(value = "operation_tags") @ContractNonNull List<String> operationTags,
    @JsonProperty(value = "operator_id", required = true) @ContractNonNull String operatorId,
    @JsonProperty(value = "preconditions", required = true) @ContractNonNull List<String> preconditions,
    @JsonProperty(value = "representation_tags") @ContractNonNull List<String> representationTags,
    @JsonProperty(value = "reversibility_requirements", required = true) @ContractNonNull List<String> reversibilityRequirements,
    @JsonProperty(value = "suggested_tools") @ContractNonNull List<String> suggestedTools,
    @JsonProperty(value = "title", required = true) @ContractNonNull String title,
    @JsonProperty(value = "transformation", required = true) @ContractNonNull String transformation
) implements StrictContract {

  public DomainOperatorSpec {
    if (applicabilityTokens == null) {
      applicabilityTokens = List.of();
    }
    applicabilityTokens = ImmutableCollections.listOrEmpty(applicabilityTokens);
    domain = ContractStrings.trim(domain);
    domain = ContractStrings.required("domain", domain);
    family = ContractStrings.trim(family);
    family = ContractStrings.required("family", family);
    ContractValues.oneOf("family", family, "representation", "construction", "mutation");
    fastFailureTests = ImmutableCollections.requiredList("fast_failure_tests", fastFailureTests);
    generatedObligations = ImmutableCollections.requiredList("generated_obligations", generatedObligations);
    knownFailureModes = ImmutableCollections.requiredList("known_failure_modes", knownFailureModes);
    if (mechanismTags == null) {
      mechanismTags = List.of();
    }
    mechanismTags = ImmutableCollections.listOrEmpty(mechanismTags);
    if (objectTags == null) {
      objectTags = List.of();
    }
    objectTags = ImmutableCollections.listOrEmpty(objectTags);
    if (operationTags == null) {
      operationTags = List.of();
    }
    operationTags = ImmutableCollections.listOrEmpty(operationTags);
    operatorId = ContractStrings.trim(operatorId);
    operatorId = ContractStrings.required("operator_id", operatorId);
    preconditions = ImmutableCollections.requiredList("preconditions", preconditions);
    if (representationTags == null) {
      representationTags = List.of();
    }
    representationTags = ImmutableCollections.listOrEmpty(representationTags);
    reversibilityRequirements = ImmutableCollections.requiredList("reversibility_requirements", reversibilityRequirements);
    if (suggestedTools == null) {
      suggestedTools = List.of();
    }
    suggestedTools = ImmutableCollections.listOrEmpty(suggestedTools);
    title = ContractStrings.trim(title);
    title = ContractStrings.required("title", title);
    transformation = ContractStrings.trim(transformation);
    transformation = ContractStrings.required("transformation", transformation);
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<String> applicabilityTokens() {
    return applicabilityTokens == null ? null : List.copyOf(applicabilityTokens);
  }

  public List<String> fastFailureTests() {
    return fastFailureTests == null ? null : List.copyOf(fastFailureTests);
  }

  public List<String> generatedObligations() {
    return generatedObligations == null ? null : List.copyOf(generatedObligations);
  }

  public List<String> knownFailureModes() {
    return knownFailureModes == null ? null : List.copyOf(knownFailureModes);
  }

  public List<String> mechanismTags() {
    return mechanismTags == null ? null : List.copyOf(mechanismTags);
  }

  public List<String> objectTags() {
    return objectTags == null ? null : List.copyOf(objectTags);
  }

  public List<String> operationTags() {
    return operationTags == null ? null : List.copyOf(operationTags);
  }

  public List<String> preconditions() {
    return preconditions == null ? null : List.copyOf(preconditions);
  }

  public List<String> representationTags() {
    return representationTags == null ? null : List.copyOf(representationTags);
  }

  public List<String> reversibilityRequirements() {
    return reversibilityRequirements == null ? null : List.copyOf(reversibilityRequirements);
  }

  public List<String> suggestedTools() {
    return suggestedTools == null ? null : List.copyOf(suggestedTools);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
