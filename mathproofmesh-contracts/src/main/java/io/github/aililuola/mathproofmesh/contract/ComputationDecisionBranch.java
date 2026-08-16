package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ComputationDecisionBranch(
    @JsonProperty(value = "outcome", required = true) @ContractNonNull ExperimentOutcome outcome,
    @JsonProperty(value = "action", required = true)
        @ContractNonNull
        ComputationDecisionAction action,
    @JsonProperty(value = "result_scope_hash", required = true)
        @ContractNonNull
        String resultScopeHash) {
  public ComputationDecisionBranch {
    outcome = ContractValues.required("outcome", outcome);
    action = ContractValues.required("action", action);
    resultScopeHash = ContractStrings.required("result_scope_hash", ContractStrings.trim(resultScopeHash));
  }
}
