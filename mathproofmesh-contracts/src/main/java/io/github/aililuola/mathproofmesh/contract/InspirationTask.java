package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record InspirationTask(
    @JsonProperty(value = "max_proposals") @ContractNonNull Integer maxProposals,
    @JsonProperty(value = "mechanism", required = true) @ContractNonNull InspirationMechanism mechanism,
    @JsonProperty(value = "reason", required = true) @ContractNonNull String reason,
    @JsonProperty(value = "target_obligation_ids") @ContractNonNull List<String> targetObligationIds,
    @JsonProperty(value = "target_route_ids") @ContractNonNull List<String> targetRouteIds,
    @JsonProperty(value = "task_id") @ContractNonNull String taskId,
    @JsonProperty(value = "trigger_id", required = true) @ContractNonNull String triggerId
) implements StrictContract {

  public InspirationTask {
    if (maxProposals == null) {
      maxProposals = 1;
    }
    ContractValues.minimum("max_proposals", maxProposals, 1);
    mechanism = ContractValues.required("mechanism", mechanism);
    reason = ContractStrings.trim(reason);
    reason = ContractStrings.required("reason", reason);
    if (targetObligationIds == null) {
      targetObligationIds = List.of();
    }
    targetObligationIds = ImmutableCollections.listOrEmpty(targetObligationIds);
    if (targetRouteIds == null) {
      targetRouteIds = List.of();
    }
    targetRouteIds = ImmutableCollections.listOrEmpty(targetRouteIds);
    if (taskId == null) {
      taskId = PythonCompatibleIdGenerator.newId("inspiration_task");
    }
    taskId = ContractStrings.trim(taskId);
    triggerId = ContractStrings.trim(triggerId);
    triggerId = ContractStrings.required("trigger_id", triggerId);
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<String> targetObligationIds() {
    return targetObligationIds == null ? null : List.copyOf(targetObligationIds);
  }

  public List<String> targetRouteIds() {
    return targetRouteIds == null ? null : List.copyOf(targetRouteIds);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
