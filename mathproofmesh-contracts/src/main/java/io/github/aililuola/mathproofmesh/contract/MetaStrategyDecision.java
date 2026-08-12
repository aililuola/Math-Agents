package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record MetaStrategyDecision(
    @JsonProperty(value = "action", required = true) @ContractNonNull String action,
    @JsonProperty(value = "affected_route_ids") @ContractNonNull List<String> affectedRouteIds,
    @JsonProperty(value = "decision_id") @ContractNonNull String decisionId,
    @JsonProperty(value = "estimated_calls") @ContractNonNull Integer estimatedCalls,
    @JsonProperty(value = "observable_metrics") @ContractNonNull Map<String, JsonNode> observableMetrics,
    @JsonProperty(value = "reason", required = true) @ContractNonNull String reason,
    @JsonProperty(value = "round_index", required = true) @ContractNonNull Integer roundIndex,
    @JsonProperty(value = "selected_mechanism") InspirationMechanism selectedMechanism
) implements StrictContract {

  public MetaStrategyDecision {
    action = ContractStrings.trim(action);
    action = ContractStrings.required("action", action);
    ContractValues.oneOf("action", action, "continue_current_mechanism", "local_repair", "rewrite_plan", "switch_representation", "search_analogy", "invent_auxiliary_construction", "surprise_exploration", "merge_route", "split_route", "cooldown_route", "abandon_route");
    if (affectedRouteIds == null) {
      affectedRouteIds = List.of();
    }
    affectedRouteIds = ImmutableCollections.listOrEmpty(affectedRouteIds);
    if (decisionId == null) {
      decisionId = PythonCompatibleIdGenerator.newId("meta");
    }
    decisionId = ContractStrings.trim(decisionId);
    if (estimatedCalls == null) {
      estimatedCalls = 0;
    }
    ContractValues.minimum("estimated_calls", estimatedCalls, 0);
    if (observableMetrics == null) {
      observableMetrics = Map.of();
    }
    observableMetrics = ImmutableCollections.jsonMapOrEmpty(observableMetrics);
    reason = ContractStrings.trim(reason);
    reason = ContractStrings.required("reason", reason);
    roundIndex = ContractValues.required("round_index", roundIndex);
    ContractValues.minimum("round_index", roundIndex, 0);
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<String> affectedRouteIds() {
    return affectedRouteIds == null ? null : List.copyOf(affectedRouteIds);
  }

  public Map<String, JsonNode> observableMetrics() {
    return observableMetrics == null ? null : ImmutableCollections.copyJsonMap(observableMetrics);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
