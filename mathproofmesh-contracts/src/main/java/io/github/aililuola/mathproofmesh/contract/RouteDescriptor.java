package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record RouteDescriptor(
    @JsonProperty(value = "cooldown_until_round") Integer cooldownUntilRound,
    @JsonProperty(value = "duplicate_attempt_count") @ContractNonNull Integer duplicateAttemptCount,
    @JsonProperty(value = "failure_count") @ContractNonNull Integer failureCount,
    @JsonProperty(value = "frozen_reason") String frozenReason,
    @JsonProperty(value = "frozen_signature") String frozenSignature,
    @JsonProperty(value = "inspiration_proposal_id") String inspirationProposalId,
    @JsonProperty(value = "last_progress_signature") String lastProgressSignature,
    @JsonProperty(value = "latest_attempt_id") String latestAttemptId,
    @JsonProperty(value = "latest_checkpoint_id") String latestCheckpointId,
    @JsonProperty(value = "mechanism_signature", required = true) @ContractNonNull List<String> mechanismSignature,
    @JsonProperty(value = "members") @ContractNonNull List<RouteMember> members,
    @JsonProperty(value = "merged_into_route_id") String mergedIntoRouteId,
    @JsonProperty(value = "neighbor_route_ids") @ContractNonNull List<String> neighborRouteIds,
    @JsonProperty(value = "no_progress_strikes") @ContractNonNull Integer noProgressStrikes,
    @JsonProperty(value = "requires_revision") @ContractNonNull Boolean requiresRevision,
    @JsonProperty(value = "revision_summary") String revisionSummary,
    @JsonProperty(value = "route_id", required = true) @ContractNonNull String routeId,
    @JsonProperty(value = "shared_assumptions") @ContractNonNull List<String> sharedAssumptions,
    @JsonProperty(value = "stagnation_rounds") @ContractNonNull Integer stagnationRounds,
    @JsonProperty(value = "status") @ContractNonNull RouteStatus status,
    @JsonProperty(value = "strategy_id", required = true) @ContractNonNull String strategyId,
    @JsonProperty(value = "strategy_signature") @ContractNonNull String strategySignature
) implements StrictContract {

  public RouteDescriptor {
    ContractValues.minimum("cooldown_until_round", cooldownUntilRound, 0);
    if (duplicateAttemptCount == null) {
      duplicateAttemptCount = 0;
    }
    ContractValues.minimum("duplicate_attempt_count", duplicateAttemptCount, 0);
    if (failureCount == null) {
      failureCount = 0;
    }
    ContractValues.minimum("failure_count", failureCount, 0);
    frozenReason = ContractStrings.trim(frozenReason);
    frozenSignature = ContractStrings.trim(frozenSignature);
    inspirationProposalId = ContractStrings.trim(inspirationProposalId);
    lastProgressSignature = ContractStrings.trim(lastProgressSignature);
    latestAttemptId = ContractStrings.trim(latestAttemptId);
    latestCheckpointId = ContractStrings.trim(latestCheckpointId);
    mechanismSignature = ImmutableCollections.requiredList("mechanism_signature", mechanismSignature);
    if (members == null) {
      members = List.of();
    }
    members = ImmutableCollections.listOrEmpty(members);
    mergedIntoRouteId = ContractStrings.trim(mergedIntoRouteId);
    if (neighborRouteIds == null) {
      neighborRouteIds = List.of();
    }
    neighborRouteIds = ImmutableCollections.listOrEmpty(neighborRouteIds);
    if (noProgressStrikes == null) {
      noProgressStrikes = 0;
    }
    ContractValues.minimum("no_progress_strikes", noProgressStrikes, 0);
    if (requiresRevision == null) {
      requiresRevision = false;
    }
    revisionSummary = ContractStrings.trim(revisionSummary);
    routeId = ContractStrings.trim(routeId);
    routeId = ContractStrings.required("route_id", routeId);
    if (sharedAssumptions == null) {
      sharedAssumptions = List.of();
    }
    sharedAssumptions = ImmutableCollections.listOrEmpty(sharedAssumptions);
    if (stagnationRounds == null) {
      stagnationRounds = 0;
    }
    ContractValues.minimum("stagnation_rounds", stagnationRounds, 0);
    if (status == null) {
      status = RouteStatus.ACTIVE;
    }
    strategyId = ContractStrings.trim(strategyId);
    strategyId = ContractStrings.required("strategy_id", strategyId);
    if (strategySignature == null) {
      strategySignature = "";
    }
    strategySignature = ContractStrings.trim(strategySignature);
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<String> mechanismSignature() {
    return mechanismSignature == null ? null : List.copyOf(mechanismSignature);
  }

  public List<RouteMember> members() {
    return members == null ? null : List.copyOf(members);
  }

  public List<String> neighborRouteIds() {
    return neighborRouteIds == null ? null : List.copyOf(neighborRouteIds);
  }

  public List<String> sharedAssumptions() {
    return sharedAssumptions == null ? null : List.copyOf(sharedAssumptions);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
