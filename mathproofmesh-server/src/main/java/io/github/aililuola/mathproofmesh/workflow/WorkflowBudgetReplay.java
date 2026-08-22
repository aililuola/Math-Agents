package io.github.aililuola.mathproofmesh.workflow;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.orchestration.BudgetUsageTotals;
import io.github.aililuola.mathproofmesh.workflow.WorkflowContracts.ActivityResult;
import io.github.aililuola.mathproofmesh.workflow.WorkflowContracts.BudgetSummary;
import io.github.aililuola.mathproofmesh.workflow.WorkflowContracts.WorkflowBudgetCheckpoint;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Pure deterministic usage merge and budget decision projection for Temporal replay. */
final class WorkflowBudgetReplay {
  private final Map<String, BudgetUsageTotals> settled = new TreeMap<>();
  private int zeroGainRounds;

  WorkflowBudgetReplay(WorkflowBudgetCheckpoint checkpoint) {
    WorkflowBudgetCheckpoint source =
        checkpoint == null ? WorkflowBudgetCheckpoint.empty() : checkpoint;
    settled.putAll(source.settledUsage());
    zeroGainRounds = source.zeroGainRounds();
  }

  void record(ActivityResult result) {
    Objects.requireNonNull(result, "result");
    record(result.actionKey(), result.usage());
  }

  void recordAll(Map<String, BudgetUsageTotals> usageByAction) {
    if (usageByAction == null) {
      return;
    }
    usageByAction.forEach(this::record);
  }

  void record(String actionKey, BudgetUsageTotals usage) {
    String key = Objects.requireNonNull(actionKey, "actionKey").strip();
    if (key.isEmpty()) {
      throw new IllegalArgumentException("actionKey is required");
    }
    BudgetUsageTotals value = Objects.requireNonNull(usage, "usage");
    BudgetUsageTotals prior = settled.putIfAbsent(key, value);
    if (prior != null && !prior.equals(value)) {
      throw new IllegalStateException("conflicting workflow usage for action " + key);
    }
  }

  WorkflowBudgetCheckpoint checkpoint() {
    return new WorkflowBudgetCheckpoint(new LinkedHashMap<>(settled), zeroGainRounds, "");
  }

  BudgetSummary summary(int configuredCalls, int acceptedUpdates) {
    BudgetUsageTotals totals = totals();
    int available =
        (int) Math.max(0L, (long) configuredCalls - Math.min(Integer.MAX_VALUE, totals.calls()));
    String stateHash = checkpoint().stateHash();
    String selected = available == 0 ? "STOP_BUDGET_EXHAUSTED" : "CONTINUE";
    String decisionHash =
        CanonicalJson.stableHash(
            Map.of(
                "budget_state_hash", stateHash,
                "configured_calls", configuredCalls,
                "available_calls", available,
                "selected", selected));
    return new BudgetSummary(available, acceptedUpdates, totals, stateHash, decisionHash);
  }

  BudgetUsageTotals totals() {
    BudgetUsageTotals result = BudgetUsageTotals.zero();
    for (BudgetUsageTotals usage : settled.values()) {
      result = result.plus(usage);
    }
    return result;
  }
}
