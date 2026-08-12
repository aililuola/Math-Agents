package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record MetaDirective(
    @JsonProperty(value = "action", required = true) @ContractNonNull MetaDirectiveAction action,
    @JsonProperty(value = "directive_id") @ContractNonNull String directiveId,
    @JsonProperty(value = "estimated_calls") @ContractNonNull Integer estimatedCalls,
    @JsonProperty(value = "expires_round", required = true) @ContractNonNull Integer expiresRound,
    @JsonProperty(value = "mandatory") @ContractNonNull Boolean mandatory,
    @JsonProperty(value = "observable_evidence") @ContractNonNull Map<String, JsonNode> observableEvidence,
    @JsonProperty(value = "reason", required = true) @ContractNonNull String reason,
    @JsonProperty(value = "round_index", required = true) @ContractNonNull Integer roundIndex,
    @JsonProperty(value = "route_ids") @ContractNonNull List<String> routeIds,
    @JsonProperty(value = "selected_mechanism") InspirationMechanism selectedMechanism,
    @JsonProperty(value = "source_decision_id", required = true) @ContractNonNull String sourceDecisionId
) implements StrictContract {

  public MetaDirective {
    action = ContractValues.required("action", action);
    if (directiveId == null) {
      directiveId = PythonCompatibleIdGenerator.newId("meta_directive");
    }
    directiveId = ContractStrings.trim(directiveId);
    if (estimatedCalls == null) {
      estimatedCalls = 0;
    }
    ContractValues.minimum("estimated_calls", estimatedCalls, 0);
    expiresRound = ContractValues.required("expires_round", expiresRound);
    ContractValues.minimum("expires_round", expiresRound, 0);
    if (mandatory == null) {
      mandatory = false;
    }
    if (observableEvidence == null) {
      observableEvidence = Map.of();
    }
    observableEvidence = ImmutableCollections.jsonMapOrEmpty(observableEvidence);
    reason = ContractStrings.trim(reason);
    reason = ContractStrings.required("reason", reason);
    roundIndex = ContractValues.required("round_index", roundIndex);
    ContractValues.minimum("round_index", roundIndex, 0);
    if (routeIds == null) {
      routeIds = List.of();
    }
    routeIds = ImmutableCollections.listOrEmpty(routeIds);
    sourceDecisionId = ContractStrings.trim(sourceDecisionId);
    sourceDecisionId = ContractStrings.required("source_decision_id", sourceDecisionId);
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public Map<String, JsonNode> observableEvidence() {
    return observableEvidence == null ? null : ImmutableCollections.copyJsonMap(observableEvidence);
  }

  public List<String> routeIds() {
    return routeIds == null ? null : List.copyOf(routeIds);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
