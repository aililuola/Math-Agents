package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record InspirationAssignmentPlan(
    @JsonProperty(value = "assignments") @ContractNonNull List<InspirationProposalAssignment> assignments,
    @JsonProperty(value = "deferred_reason") String deferredReason,
    @JsonProperty(value = "eligible_agent_ids") @ContractNonNull List<String> eligibleAgentIds,
    @JsonProperty(value = "mechanism", required = true) @ContractNonNull InspirationMechanism mechanism,
    @JsonProperty(value = "requested_proposals", required = true) @ContractNonNull Integer requestedProposals,
    @JsonProperty(value = "round_index", required = true) @ContractNonNull Integer roundIndex,
    @JsonProperty(value = "task_id", required = true) @ContractNonNull String taskId
) implements StrictContract {

  public InspirationAssignmentPlan {
    if (assignments == null) {
      assignments = List.of();
    }
    assignments = ImmutableCollections.listOrEmpty(assignments);
    deferredReason = ContractStrings.trim(deferredReason);
    if (eligibleAgentIds == null) {
      eligibleAgentIds = List.of();
    }
    eligibleAgentIds = ImmutableCollections.listOrEmpty(eligibleAgentIds);
    mechanism = ContractValues.required("mechanism", mechanism);
    requestedProposals = ContractValues.required("requested_proposals", requestedProposals);
    ContractValues.minimum("requested_proposals", requestedProposals, 0);
    roundIndex = ContractValues.required("round_index", roundIndex);
    ContractValues.minimum("round_index", roundIndex, 0);
    taskId = ContractStrings.trim(taskId);
    taskId = ContractStrings.required("task_id", taskId);
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<InspirationProposalAssignment> assignments() {
    return assignments == null ? null : List.copyOf(assignments);
  }

  public List<String> eligibleAgentIds() {
    return eligibleAgentIds == null ? null : List.copyOf(eligibleAgentIds);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
