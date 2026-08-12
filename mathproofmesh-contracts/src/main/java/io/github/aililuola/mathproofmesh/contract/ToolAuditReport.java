package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record ToolAuditReport(
    @JsonProperty(value = "agent_id", required = true) @ContractNonNull String agentId,
    @JsonProperty(value = "all_results_replayed_independently") @ContractNonNull Boolean allResultsReplayedIndependently,
    @JsonProperty(value = "confidence") @ContractNonNull Double confidence,
    @JsonProperty(value = "experiment_ids") @ContractNonNull List<String> experimentIds,
    @JsonProperty(value = "issues") @ContractNonNull List<String> issues,
    @JsonProperty(value = "mathematical_mapping_checked") @ContractNonNull Boolean mathematicalMappingChecked,
    @JsonProperty(value = "replay_artifact_refs") @ContractNonNull List<String> replayArtifactRefs,
    @JsonProperty(value = "route_id", required = true) @ContractNonNull String routeId,
    @JsonProperty(value = "verdict") @ContractNonNull String verdict
) implements StrictContract {

  public ToolAuditReport {
    agentId = ContractStrings.trim(agentId);
    agentId = ContractStrings.required("agent_id", agentId);
    if (allResultsReplayedIndependently == null) {
      allResultsReplayedIndependently = false;
    }
    if (confidence == null) {
      confidence = 0.0d;
    }
    ContractValues.minimum("confidence", confidence, 0.0);
    ContractValues.maximum("confidence", confidence, 1.0);
    if (experimentIds == null) {
      experimentIds = List.of();
    }
    experimentIds = ImmutableCollections.listOrEmpty(experimentIds);
    if (issues == null) {
      issues = List.of();
    }
    issues = ImmutableCollections.listOrEmpty(issues);
    if (mathematicalMappingChecked == null) {
      mathematicalMappingChecked = false;
    }
    if (replayArtifactRefs == null) {
      replayArtifactRefs = List.of();
    }
    replayArtifactRefs = ImmutableCollections.listOrEmpty(replayArtifactRefs);
    routeId = ContractStrings.trim(routeId);
    routeId = ContractStrings.required("route_id", routeId);
    if (verdict == null) {
      verdict = "inconclusive";
    }
    verdict = ContractStrings.trim(verdict);
    ContractValues.oneOf("verdict", verdict, "pass", "fail", "inconclusive");
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<String> experimentIds() {
    return experimentIds == null ? null : List.copyOf(experimentIds);
  }

  public List<String> issues() {
    return issues == null ? null : List.copyOf(issues);
  }

  public List<String> replayArtifactRefs() {
    return replayArtifactRefs == null ? null : List.copyOf(replayArtifactRefs);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
