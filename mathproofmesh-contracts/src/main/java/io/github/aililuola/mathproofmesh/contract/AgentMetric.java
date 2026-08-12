package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record AgentMetric(
    @JsonProperty(value = "agent_id", required = true) @ContractNonNull String agentId,
    @JsonProperty(value = "calls") @ContractNonNull Integer calls,
    @JsonProperty(value = "failed_attempts") @ContractNonNull Integer failedAttempts,
    @JsonProperty(value = "failure_categories") @ContractNonNull Map<String, Integer> failureCategories,
    @JsonProperty(value = "failures") @ContractNonNull Integer failures,
    @JsonProperty(value = "successful_responses") @ContractNonNull Integer successfulResponses,
    @JsonProperty(value = "trust_score") @ContractNonNull Double trustScore,
    @JsonProperty(value = "usage") @ContractNonNull UsageRecord usage
) implements StrictContract {

  public AgentMetric {
    agentId = ContractStrings.trim(agentId);
    agentId = ContractStrings.required("agent_id", agentId);
    if (calls == null) {
      calls = 0;
    }
    ContractValues.minimum("calls", calls, 0);
    if (failedAttempts == null) {
      failedAttempts = 0;
    }
    ContractValues.minimum("failed_attempts", failedAttempts, 0);
    if (failureCategories == null) {
      failureCategories = Map.of();
    }
    failureCategories = ImmutableCollections.mapOrEmpty(failureCategories);
    if (failures == null) {
      failures = 0;
    }
    ContractValues.minimum("failures", failures, 0);
    if (successfulResponses == null) {
      successfulResponses = 0;
    }
    ContractValues.minimum("successful_responses", successfulResponses, 0);
    if (trustScore == null) {
      trustScore = 0.5d;
    }
    ContractValues.minimum("trust_score", trustScore, 0.0);
    ContractValues.maximum("trust_score", trustScore, 1.0);
    if (usage == null) {
      usage = new UsageRecord();
    }
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public Map<String, Integer> failureCategories() {
    return failureCategories == null ? null : Map.copyOf(failureCategories);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
