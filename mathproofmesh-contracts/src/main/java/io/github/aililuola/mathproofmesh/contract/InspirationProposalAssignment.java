package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record InspirationProposalAssignment(
    @JsonProperty(value = "context_mode", required = true) @ContractNonNull InspirationContextMode contextMode,
    @JsonProperty(value = "mechanism", required = true) @ContractNonNull InspirationMechanism mechanism,
    @JsonProperty(value = "proposal_slot", required = true) @ContractNonNull Integer proposalSlot,
    @JsonProperty(value = "proposer_agent_id", required = true) @ContractNonNull String proposerAgentId,
    @JsonProperty(value = "proposer_role", required = true) @ContractNonNull String proposerRole,
    @JsonProperty(value = "specialist_match", required = true) @ContractNonNull Boolean specialistMatch,
    @JsonProperty(value = "task_id", required = true) @ContractNonNull String taskId
) implements StrictContract {

  public InspirationProposalAssignment {
    contextMode = ContractValues.required("context_mode", contextMode);
    mechanism = ContractValues.required("mechanism", mechanism);
    proposalSlot = ContractValues.required("proposal_slot", proposalSlot);
    ContractValues.minimum("proposal_slot", proposalSlot, 0);
    proposerAgentId = ContractStrings.trim(proposerAgentId);
    proposerAgentId = ContractStrings.required("proposer_agent_id", proposerAgentId);
    proposerRole = ContractStrings.trim(proposerRole);
    proposerRole = ContractStrings.required("proposer_role", proposerRole);
    specialistMatch = ContractValues.required("specialist_match", specialistMatch);
    taskId = ContractStrings.trim(taskId);
    taskId = ContractStrings.required("task_id", taskId);
  }
}
