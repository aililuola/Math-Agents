package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record StrategyPreflightPlan(
    @JsonProperty(value = "problem_hash", required = true) @ContractNonNull String problemHash,
    @JsonProperty(value = "strategy_id", required = true) @ContractNonNull String strategyId,
    @JsonProperty(value = "claim_plans", required = true) @ContractNonNull
        List<CriticalClaimPreflightPlan> claimPlans)
    implements StrictContract {
  public StrategyPreflightPlan {
    problemHash = ContractStrings.required("problem_hash", ContractStrings.trim(problemHash));
    strategyId = ContractStrings.required("strategy_id", ContractStrings.trim(strategyId));
    claimPlans = ImmutableCollections.requiredList("claim_plans", claimPlans);
  }

  @Override
  public List<CriticalClaimPreflightPlan> claimPlans() {
    return List.copyOf(claimPlans);
  }
}
