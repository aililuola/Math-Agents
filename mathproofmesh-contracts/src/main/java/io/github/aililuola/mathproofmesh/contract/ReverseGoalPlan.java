package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record ReverseGoalPlan(
    @JsonProperty(value = "backward_frontier") @ContractNonNull List<FrontierClaim> backwardFrontier,
    @JsonProperty(value = "bridge_requests", required = true) @ContractNonNull List<String> bridgeRequests,
    @JsonProperty(value = "fact_supported_claims") @ContractNonNull List<String> factSupportedClaims,
    @JsonProperty(value = "forward_frontier") @ContractNonNull List<FrontierClaim> forwardFrontier,
    @JsonProperty(value = "frontier_bridges") @ContractNonNull List<FrontierBridge> frontierBridges,
    @JsonProperty(value = "goal", required = true) @ContractNonNull String goal,
    @JsonProperty(value = "minimal_gaps", required = true) @ContractNonNull List<String> minimalGaps,
    @JsonProperty(value = "novelty_signature", required = true) @ContractNonNull NoveltySignature noveltySignature,
    @JsonProperty(value = "plan_id") @ContractNonNull String planId,
    @JsonProperty(value = "sufficient_intermediate_claims", required = true) @ContractNonNull List<String> sufficientIntermediateClaims,
    @JsonProperty(value = "target_obligation_id", required = true) @ContractNonNull String targetObligationId
) implements StrictContract {

  public ReverseGoalPlan {
    if (backwardFrontier == null) {
      backwardFrontier = List.of();
    }
    backwardFrontier = ImmutableCollections.listOrEmpty(backwardFrontier);
    bridgeRequests = ImmutableCollections.requiredList("bridge_requests", bridgeRequests);
    if (factSupportedClaims == null) {
      factSupportedClaims = List.of();
    }
    factSupportedClaims = ImmutableCollections.listOrEmpty(factSupportedClaims);
    if (forwardFrontier == null) {
      forwardFrontier = List.of();
    }
    forwardFrontier = ImmutableCollections.listOrEmpty(forwardFrontier);
    if (frontierBridges == null) {
      frontierBridges = List.of();
    }
    frontierBridges = ImmutableCollections.listOrEmpty(frontierBridges);
    goal = ContractStrings.trim(goal);
    goal = ContractStrings.required("goal", goal);
    minimalGaps = ImmutableCollections.requiredList("minimal_gaps", minimalGaps);
    noveltySignature = ContractValues.required("novelty_signature", noveltySignature);
    if (planId == null) {
      planId = PythonCompatibleIdGenerator.newId("reverse_goal");
    }
    planId = ContractStrings.trim(planId);
    sufficientIntermediateClaims = ImmutableCollections.requiredList("sufficient_intermediate_claims", sufficientIntermediateClaims);
    targetObligationId = ContractStrings.trim(targetObligationId);
    targetObligationId = ContractStrings.required("target_obligation_id", targetObligationId);
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<FrontierClaim> backwardFrontier() {
    return backwardFrontier == null ? null : List.copyOf(backwardFrontier);
  }

  public List<String> bridgeRequests() {
    return bridgeRequests == null ? null : List.copyOf(bridgeRequests);
  }

  public List<String> factSupportedClaims() {
    return factSupportedClaims == null ? null : List.copyOf(factSupportedClaims);
  }

  public List<FrontierClaim> forwardFrontier() {
    return forwardFrontier == null ? null : List.copyOf(forwardFrontier);
  }

  public List<FrontierBridge> frontierBridges() {
    return frontierBridges == null ? null : List.copyOf(frontierBridges);
  }

  public List<String> minimalGaps() {
    return minimalGaps == null ? null : List.copyOf(minimalGaps);
  }

  public List<String> sufficientIntermediateClaims() {
    return sufficientIntermediateClaims == null ? null : List.copyOf(sufficientIntermediateClaims);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
