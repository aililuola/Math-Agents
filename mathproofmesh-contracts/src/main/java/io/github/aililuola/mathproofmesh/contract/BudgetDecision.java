package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record BudgetDecision(
    @JsonProperty(value = "actions", required = true) @ContractNonNull List<BudgetAction> actions,
    @JsonProperty(value = "all_evaluated_paths_failed") @ContractNonNull Boolean allEvaluatedPathsFailed,
    @JsonProperty(value = "candidates") @ContractNonNull List<BudgetAction> candidates,
    @JsonProperty(value = "coverage", required = true) @ContractNonNull Double coverage,
    @JsonProperty(value = "failure_rate") @ContractNonNull Double failureRate,
    @JsonProperty(value = "finish_reserve_calls") @ContractNonNull Integer finishReserveCalls,
    @JsonProperty(value = "forced_widen") @ContractNonNull Boolean forcedWiden,
    @JsonProperty(value = "global_uncertainty", required = true) @ContractNonNull Double globalUncertainty,
    @JsonProperty(value = "rationale", required = true) @ContractNonNull String rationale
) implements StrictContract {

  public BudgetDecision {
    actions = ImmutableCollections.requiredList("actions", actions);
    if (allEvaluatedPathsFailed == null) {
      allEvaluatedPathsFailed = false;
    }
    if (candidates == null) {
      candidates = List.of();
    }
    candidates = ImmutableCollections.listOrEmpty(candidates);
    coverage = ContractValues.required("coverage", coverage);
    ContractValues.minimum("coverage", coverage, 0.0);
    ContractValues.maximum("coverage", coverage, 1.0);
    if (failureRate == null) {
      failureRate = 0.0d;
    }
    ContractValues.minimum("failure_rate", failureRate, 0.0);
    ContractValues.maximum("failure_rate", failureRate, 1.0);
    if (finishReserveCalls == null) {
      finishReserveCalls = 0;
    }
    ContractValues.minimum("finish_reserve_calls", finishReserveCalls, 0);
    if (forcedWiden == null) {
      forcedWiden = false;
    }
    globalUncertainty = ContractValues.required("global_uncertainty", globalUncertainty);
    ContractValues.minimum("global_uncertainty", globalUncertainty, 0.0);
    ContractValues.maximum("global_uncertainty", globalUncertainty, 1.0);
    rationale = ContractStrings.trim(rationale);
    rationale = ContractStrings.required("rationale", rationale);
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<BudgetAction> actions() {
    return actions == null ? null : List.copyOf(actions);
  }

  public List<BudgetAction> candidates() {
    return candidates == null ? null : List.copyOf(candidates);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
