package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record MetaDirectiveExecution(
    @JsonProperty(value = "affected_route_ids") @ContractNonNull List<String> affectedRouteIds,
    @JsonProperty(value = "directive_id", required = true) @ContractNonNull String directiveId,
    @JsonProperty(value = "generated_task_ids") @ContractNonNull List<String> generatedTaskIds,
    @JsonProperty(value = "reason", required = true) @ContractNonNull String reason,
    @JsonProperty(value = "status", required = true) @ContractNonNull String status
) implements StrictContract {

  public MetaDirectiveExecution {
    if (affectedRouteIds == null) {
      affectedRouteIds = List.of();
    }
    affectedRouteIds = ImmutableCollections.listOrEmpty(affectedRouteIds);
    directiveId = ContractStrings.trim(directiveId);
    directiveId = ContractStrings.required("directive_id", directiveId);
    if (generatedTaskIds == null) {
      generatedTaskIds = List.of();
    }
    generatedTaskIds = ImmutableCollections.listOrEmpty(generatedTaskIds);
    reason = ContractStrings.trim(reason);
    reason = ContractStrings.required("reason", reason);
    status = ContractStrings.trim(status);
    status = ContractStrings.required("status", status);
    ContractValues.oneOf("status", status, "executed", "rejected", "deferred", "noop");
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<String> affectedRouteIds() {
    return affectedRouteIds == null ? null : List.copyOf(affectedRouteIds);
  }

  public List<String> generatedTaskIds() {
    return generatedTaskIds == null ? null : List.copyOf(generatedTaskIds);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
