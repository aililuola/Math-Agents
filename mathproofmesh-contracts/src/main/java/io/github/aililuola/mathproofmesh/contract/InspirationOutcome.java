package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record InspirationOutcome(
    @JsonProperty(value = "cited_by_final_proof") @ContractNonNull Boolean citedByFinalProof,
    @JsonProperty(value = "credit_obligation_ids") @ContractNonNull List<String> creditObligationIds,
    @JsonProperty(value = "credit_route_ids") @ContractNonNull List<String> creditRouteIds,
    @JsonProperty(value = "domain") @ContractNonNull String domain,
    @JsonProperty(value = "materialization_action") String materializationAction,
    @JsonProperty(value = "materialized") @ContractNonNull Boolean materialized,
    @JsonProperty(value = "mechanism", required = true) @ContractNonNull InspirationMechanism mechanism,
    @JsonProperty(value = "obligation_kinds") @ContractNonNull List<ObligationKind> obligationKinds,
    @JsonProperty(value = "obligations_closed") @ContractNonNull List<String> obligationsClosed,
    @JsonProperty(value = "problem_hash") @ContractNonNull String problemHash,
    @JsonProperty(value = "proof_debt_after") Double proofDebtAfter,
    @JsonProperty(value = "proof_debt_before") @ContractNonNull Double proofDebtBefore,
    @JsonProperty(value = "proof_debt_delta") @ContractNonNull Double proofDebtDelta,
    @JsonProperty(value = "proposal_id", required = true) @ContractNonNull String proposalId,
    @JsonProperty(value = "proposer_calls") @ContractNonNull Integer proposerCalls,
    @JsonProperty(value = "refuted") @ContractNonNull Boolean refuted,
    @JsonProperty(value = "review_calls") @ContractNonNull Integer reviewCalls,
    @JsonProperty(value = "reward") @ContractNonNull Double reward,
    @JsonProperty(value = "round_created", required = true) @ContractNonNull Integer roundCreated,
    @JsonProperty(value = "rounds_to_first_gain") Integer roundsToFirstGain,
    @JsonProperty(value = "route_calls") @ContractNonNull Integer routeCalls,
    @JsonProperty(value = "task_id") String taskId,
    @JsonProperty(value = "tokens") @ContractNonNull Integer tokens,
    @JsonProperty(value = "trigger_type", required = true) @ContractNonNull InspirationTriggerType triggerType,
    @JsonProperty(value = "verified_fact_gain") @ContractNonNull Integer verifiedFactGain
) implements StrictContract {

  public InspirationOutcome {
    if (citedByFinalProof == null) {
      citedByFinalProof = false;
    }
    if (creditObligationIds == null) {
      creditObligationIds = List.of();
    }
    creditObligationIds = ImmutableCollections.listOrEmpty(creditObligationIds);
    if (creditRouteIds == null) {
      creditRouteIds = List.of();
    }
    creditRouteIds = ImmutableCollections.listOrEmpty(creditRouteIds);
    if (domain == null) {
      domain = "unknown";
    }
    domain = ContractStrings.trim(domain);
    materializationAction = ContractStrings.trim(materializationAction);
    if (materialized == null) {
      materialized = false;
    }
    mechanism = ContractValues.required("mechanism", mechanism);
    if (obligationKinds == null) {
      obligationKinds = List.of();
    }
    obligationKinds = ImmutableCollections.listOrEmpty(obligationKinds);
    if (obligationsClosed == null) {
      obligationsClosed = List.of();
    }
    obligationsClosed = ImmutableCollections.listOrEmpty(obligationsClosed);
    if (problemHash == null) {
      problemHash = "";
    }
    problemHash = ContractStrings.trim(problemHash);
    ContractValues.minimum("proof_debt_after", proofDebtAfter, 0.0);
    if (proofDebtBefore == null) {
      proofDebtBefore = 0.0d;
    }
    ContractValues.minimum("proof_debt_before", proofDebtBefore, 0.0);
    if (proofDebtDelta == null) {
      proofDebtDelta = 0.0d;
    }
    proposalId = ContractStrings.trim(proposalId);
    proposalId = ContractStrings.required("proposal_id", proposalId);
    if (proposerCalls == null) {
      proposerCalls = 0;
    }
    ContractValues.minimum("proposer_calls", proposerCalls, 0);
    if (refuted == null) {
      refuted = false;
    }
    if (reviewCalls == null) {
      reviewCalls = 0;
    }
    ContractValues.minimum("review_calls", reviewCalls, 0);
    if (reward == null) {
      reward = 0.0d;
    }
    roundCreated = ContractValues.required("round_created", roundCreated);
    ContractValues.minimum("round_created", roundCreated, 0);
    ContractValues.minimum("rounds_to_first_gain", roundsToFirstGain, 0);
    if (routeCalls == null) {
      routeCalls = 0;
    }
    ContractValues.minimum("route_calls", routeCalls, 0);
    taskId = ContractStrings.trim(taskId);
    if (tokens == null) {
      tokens = 0;
    }
    ContractValues.minimum("tokens", tokens, 0);
    triggerType = ContractValues.required("trigger_type", triggerType);
    if (verifiedFactGain == null) {
      verifiedFactGain = 0;
    }
    ContractValues.minimum("verified_fact_gain", verifiedFactGain, 0);
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<String> creditObligationIds() {
    return creditObligationIds == null ? null : List.copyOf(creditObligationIds);
  }

  public List<String> creditRouteIds() {
    return creditRouteIds == null ? null : List.copyOf(creditRouteIds);
  }

  public List<ObligationKind> obligationKinds() {
    return obligationKinds == null ? null : List.copyOf(obligationKinds);
  }

  public List<String> obligationsClosed() {
    return obligationsClosed == null ? null : List.copyOf(obligationsClosed);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
