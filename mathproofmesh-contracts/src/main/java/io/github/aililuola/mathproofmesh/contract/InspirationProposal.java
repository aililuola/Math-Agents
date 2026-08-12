package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record InspirationProposal(
    @JsonProperty(value = "analogy") AnalogyMapping analogy,
    @JsonProperty(value = "composition") ComposedInspiration composition,
    @JsonProperty(value = "construction") ConstructionProposal construction,
    @JsonProperty(value = "context_mode") @ContractNonNull InspirationContextMode contextMode,
    @JsonProperty(value = "estimated_cost", required = true) @ContractNonNull Integer estimatedCost,
    @JsonProperty(value = "evidence_type") @ContractNonNull EvidenceType evidenceType,
    @JsonProperty(value = "expected_information_gain", required = true) @ContractNonNull Double expectedInformationGain,
    @JsonProperty(value = "generated_obligations", required = true) @ContractNonNull List<String> generatedObligations,
    @JsonProperty(value = "invariant") InvariantHypothesis invariant,
    @JsonProperty(value = "mechanism", required = true) @ContractNonNull InspirationMechanism mechanism,
    @JsonProperty(value = "mutation") SurpriseMutationDirective mutation,
    @JsonProperty(value = "novelty_score", required = true) @ContractNonNull Double noveltyScore,
    @JsonProperty(value = "novelty_signature", required = true) @ContractNonNull NoveltySignature noveltySignature,
    @JsonProperty(value = "proposal_id") @ContractNonNull String proposalId,
    @JsonProperty(value = "proposal_slot") @ContractNonNull Integer proposalSlot,
    @JsonProperty(value = "rationale_summary", required = true) @ContractNonNull String rationaleSummary,
    @JsonProperty(value = "representation") RepresentationCandidate representation,
    @JsonProperty(value = "reverse_goal") ReverseGoalPlan reverseGoal,
    @JsonProperty(value = "source_agent_id", required = true) @ContractNonNull String sourceAgentId,
    @JsonProperty(value = "statement", required = true) @ContractNonNull String statement,
    @JsonProperty(value = "target_route_ids", required = true) @ContractNonNull List<String> targetRouteIds,
    @JsonProperty(value = "task_id") String taskId,
    @JsonProperty(value = "trigger_id", required = true) @ContractNonNull String triggerId
) implements StrictContract {

  public InspirationProposal {
    if (contextMode == null) {
      contextMode = InspirationContextMode.LOCAL;
    }
    estimatedCost = ContractValues.required("estimated_cost", estimatedCost);
    ContractValues.minimum("estimated_cost", estimatedCost, 0);
    if (evidenceType == null) {
      evidenceType = EvidenceType.UNVERIFIED_IDEA;
    }
    expectedInformationGain = ContractValues.required("expected_information_gain", expectedInformationGain);
    ContractValues.minimum("expected_information_gain", expectedInformationGain, 0.0);
    ContractValues.maximum("expected_information_gain", expectedInformationGain, 1.0);
    generatedObligations = ImmutableCollections.requiredList("generated_obligations", generatedObligations);
    mechanism = ContractValues.required("mechanism", mechanism);
    noveltyScore = ContractValues.required("novelty_score", noveltyScore);
    ContractValues.minimum("novelty_score", noveltyScore, 0.0);
    ContractValues.maximum("novelty_score", noveltyScore, 1.0);
    noveltySignature = ContractValues.required("novelty_signature", noveltySignature);
    if (proposalId == null) {
      proposalId = PythonCompatibleIdGenerator.newId("inspiration");
    }
    proposalId = ContractStrings.trim(proposalId);
    if (proposalSlot == null) {
      proposalSlot = 0;
    }
    ContractValues.minimum("proposal_slot", proposalSlot, 0);
    rationaleSummary = ContractStrings.trim(rationaleSummary);
    rationaleSummary = ContractStrings.required("rationale_summary", rationaleSummary);
    sourceAgentId = ContractStrings.trim(sourceAgentId);
    sourceAgentId = ContractStrings.required("source_agent_id", sourceAgentId);
    statement = ContractStrings.trim(statement);
    statement = ContractStrings.required("statement", statement);
    targetRouteIds = ImmutableCollections.requiredList("target_route_ids", targetRouteIds);
    taskId = ContractStrings.trim(taskId);
    triggerId = ContractStrings.trim(triggerId);
    triggerId = ContractStrings.required("trigger_id", triggerId);
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<String> generatedObligations() {
    return generatedObligations == null ? null : List.copyOf(generatedObligations);
  }

  public List<String> targetRouteIds() {
    return targetRouteIds == null ? null : List.copyOf(targetRouteIds);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
