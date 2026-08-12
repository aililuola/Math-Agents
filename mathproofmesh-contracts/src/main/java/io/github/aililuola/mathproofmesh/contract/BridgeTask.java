package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record BridgeTask(
    @JsonProperty(value = "allowed_fact_ids") @ContractNonNull List<String> allowedFactIds,
    @JsonProperty(value = "forbidden_negative_ids") @ContractNonNull List<String> forbiddenNegativeIds,
    @JsonProperty(value = "normalized_goal", required = true) @ContractNonNull String normalizedGoal,
    @JsonProperty(value = "obligation_ids", required = true) @ContractNonNull List<String> obligationIds,
    @JsonProperty(value = "priority") @ContractNonNull Double priority,
    @JsonProperty(value = "route_ids", required = true) @ContractNonNull List<String> routeIds,
    @JsonProperty(value = "task_id") @ContractNonNull String taskId
) implements StrictContract {

  public BridgeTask {
    if (allowedFactIds == null) {
      allowedFactIds = List.of();
    }
    allowedFactIds = ImmutableCollections.listOrEmpty(allowedFactIds);
    if (forbiddenNegativeIds == null) {
      forbiddenNegativeIds = List.of();
    }
    forbiddenNegativeIds = ImmutableCollections.listOrEmpty(forbiddenNegativeIds);
    normalizedGoal = ContractStrings.trim(normalizedGoal);
    normalizedGoal = ContractStrings.required("normalized_goal", normalizedGoal);
    obligationIds = ImmutableCollections.requiredList("obligation_ids", obligationIds);
    if (priority == null) {
      priority = 0.5d;
    }
    ContractValues.minimum("priority", priority, 0.0);
    ContractValues.maximum("priority", priority, 1.0);
    routeIds = ImmutableCollections.requiredList("route_ids", routeIds);
    if (taskId == null) {
      taskId = PythonCompatibleIdGenerator.newId("bridge");
    }
    taskId = ContractStrings.trim(taskId);
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<String> allowedFactIds() {
    return allowedFactIds == null ? null : List.copyOf(allowedFactIds);
  }

  public List<String> forbiddenNegativeIds() {
    return forbiddenNegativeIds == null ? null : List.copyOf(forbiddenNegativeIds);
  }

  public List<String> obligationIds() {
    return obligationIds == null ? null : List.copyOf(obligationIds);
  }

  public List<String> routeIds() {
    return routeIds == null ? null : List.copyOf(routeIds);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
