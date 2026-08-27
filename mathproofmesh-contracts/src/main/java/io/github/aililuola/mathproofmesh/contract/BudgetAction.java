package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record BudgetAction(
    @JsonProperty(value = "action", required = true) @ContractNonNull ActionKind action,
    @JsonProperty(value = "blocked_reason") String blockedReason,
    @JsonProperty(value = "eligible") @ContractNonNull Boolean eligible,
    @JsonProperty(value = "estimated_calls") @ContractNonNull Integer estimatedCalls,
    @JsonProperty(value = "forced") @ContractNonNull Boolean forced,
    @JsonProperty(value = "planned_paths") @ContractNonNull Integer plannedPaths,
    @JsonProperty(value = "rank") Integer rank,
    @JsonProperty(value = "reason", required = true) @ContractNonNull String reason,
    @JsonProperty(value = "score", required = true) @ContractNonNull Double score,
    @JsonProperty(value = "selected") @ContractNonNull Boolean selected,
    @JsonProperty(value = "strategy_id") String strategyId,
    @JsonProperty(value = "target_id") String targetId
) implements StrictContract {

  public BudgetAction {
    action = ContractValues.required("action", action);
    blockedReason = ContractStrings.trim(blockedReason);
    if (eligible == null) {
      eligible = true;
    }
    if (estimatedCalls == null) {
      estimatedCalls = 0;
    }
    ContractValues.minimum("estimated_calls", estimatedCalls, 0);
    if (forced == null) {
      forced = false;
    }
    if (plannedPaths == null) {
      plannedPaths = 0;
    }
    ContractValues.minimum("planned_paths", plannedPaths, 0);
    ContractValues.minimum("rank", rank, 1);
    reason = ContractStrings.trim(reason);
    reason = ContractStrings.required("reason", reason);
    score = ContractValues.required("score", score);
    if (selected == null) {
      selected = false;
    }
    strategyId = ContractStrings.trim(strategyId);
    targetId = ContractStrings.trim(targetId);
  }
}
