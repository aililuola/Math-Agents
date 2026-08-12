package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record RepresentationCandidate(
    @JsonProperty(value = "candidate_id") @ContractNonNull String candidateId,
    @JsonProperty(value = "expected_advantage", required = true) @ContractNonNull String expectedAdvantage,
    @JsonProperty(value = "failure_risks", required = true) @ContractNonNull List<String> failureRisks,
    @JsonProperty(value = "fast_failure_tests") @ContractNonNull List<String> fastFailureTests,
    @JsonProperty(value = "generated_obligations") @ContractNonNull List<String> generatedObligations,
    @JsonProperty(value = "known_failure_modes") @ContractNonNull List<String> knownFailureModes,
    @JsonProperty(value = "lost_conditions") @ContractNonNull List<String> lostConditions,
    @JsonProperty(value = "new_candidate_tools") @ContractNonNull List<String> newCandidateTools,
    @JsonProperty(value = "novelty_signature", required = true) @ContractNonNull NoveltySignature noveltySignature,
    @JsonProperty(value = "object_mapping", required = true) @ContractNonNull Map<String, String> objectMapping,
    @JsonProperty(value = "operator_id") String operatorId,
    @JsonProperty(value = "operator_preconditions") @ContractNonNull List<String> operatorPreconditions,
    @JsonProperty(value = "preserved_invariants", required = true) @ContractNonNull List<String> preservedInvariants,
    @JsonProperty(value = "representation_name", required = true) @ContractNonNull String representationName,
    @JsonProperty(value = "reversibility_requirements") @ContractNonNull List<String> reversibilityRequirements,
    @JsonProperty(value = "rewritten_problem_view", required = true) @ContractNonNull String rewrittenProblemView,
    @JsonProperty(value = "source_problem_hash", required = true) @ContractNonNull String sourceProblemHash
) implements StrictContract {

  public RepresentationCandidate {
    if (candidateId == null) {
      candidateId = PythonCompatibleIdGenerator.newId("representation");
    }
    candidateId = ContractStrings.trim(candidateId);
    expectedAdvantage = ContractStrings.trim(expectedAdvantage);
    expectedAdvantage = ContractStrings.required("expected_advantage", expectedAdvantage);
    failureRisks = ImmutableCollections.requiredList("failure_risks", failureRisks);
    if (fastFailureTests == null) {
      fastFailureTests = List.of();
    }
    fastFailureTests = ImmutableCollections.listOrEmpty(fastFailureTests);
    if (generatedObligations == null) {
      generatedObligations = List.of();
    }
    generatedObligations = ImmutableCollections.listOrEmpty(generatedObligations);
    if (knownFailureModes == null) {
      knownFailureModes = List.of();
    }
    knownFailureModes = ImmutableCollections.listOrEmpty(knownFailureModes);
    if (lostConditions == null) {
      lostConditions = List.of();
    }
    lostConditions = ImmutableCollections.listOrEmpty(lostConditions);
    if (newCandidateTools == null) {
      newCandidateTools = List.of();
    }
    newCandidateTools = ImmutableCollections.listOrEmpty(newCandidateTools);
    noveltySignature = ContractValues.required("novelty_signature", noveltySignature);
    objectMapping = ImmutableCollections.requiredMap("object_mapping", objectMapping);
    operatorId = ContractStrings.trim(operatorId);
    if (operatorPreconditions == null) {
      operatorPreconditions = List.of();
    }
    operatorPreconditions = ImmutableCollections.listOrEmpty(operatorPreconditions);
    preservedInvariants = ImmutableCollections.requiredList("preserved_invariants", preservedInvariants);
    representationName = ContractStrings.trim(representationName);
    representationName = ContractStrings.required("representation_name", representationName);
    if (reversibilityRequirements == null) {
      reversibilityRequirements = List.of();
    }
    reversibilityRequirements = ImmutableCollections.listOrEmpty(reversibilityRequirements);
    rewrittenProblemView = ContractStrings.trim(rewrittenProblemView);
    rewrittenProblemView = ContractStrings.required("rewritten_problem_view", rewrittenProblemView);
    sourceProblemHash = ContractStrings.trim(sourceProblemHash);
    sourceProblemHash = ContractStrings.required("source_problem_hash", sourceProblemHash);
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<String> failureRisks() {
    return failureRisks == null ? null : List.copyOf(failureRisks);
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

  public List<String> lostConditions() {
    return lostConditions == null ? null : List.copyOf(lostConditions);
  }

  public List<String> newCandidateTools() {
    return newCandidateTools == null ? null : List.copyOf(newCandidateTools);
  }

  public Map<String, String> objectMapping() {
    return objectMapping == null ? null : Map.copyOf(objectMapping);
  }

  public List<String> operatorPreconditions() {
    return operatorPreconditions == null ? null : List.copyOf(operatorPreconditions);
  }

  public List<String> preservedInvariants() {
    return preservedInvariants == null ? null : List.copyOf(preservedInvariants);
  }

  public List<String> reversibilityRequirements() {
    return reversibilityRequirements == null ? null : List.copyOf(reversibilityRequirements);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
