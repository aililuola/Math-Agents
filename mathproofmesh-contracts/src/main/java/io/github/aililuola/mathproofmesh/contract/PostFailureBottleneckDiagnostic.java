package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record PostFailureBottleneckDiagnostic(
    @JsonProperty(value = "alternative_mechanism_tags", required = true) @ContractNonNull List<String> alternativeMechanismTags,
    @JsonProperty(value = "attempted_mechanism", required = true) @ContractNonNull String attemptedMechanism,
    @JsonProperty(value = "blocked_claim_source", required = true) @ContractNonNull String blockedClaimSource,
    @JsonProperty(value = "checkpoint_id") @ContractNonNull String checkpointId,
    @JsonProperty(value = "confidence") @ContractNonNull Double confidence,
    @JsonProperty(value = "diagnostic_id") @ContractNonNull String diagnosticId,
    @JsonProperty(value = "exact_failed_internal_step_known") @ContractNonNull Boolean exactFailedInternalStepKnown,
    @JsonProperty(value = "failure_fingerprint") @ContractNonNull String failureFingerprint,
    @JsonProperty(value = "failure_type") @ContractNonNull String failureType,
    @JsonProperty(value = "path_id") @ContractNonNull String pathId,
    @JsonProperty(value = "preserved_fact_message_ids") @ContractNonNull List<String> preservedFactMessageIds,
    @JsonProperty(value = "preserved_verified_step_ids") @ContractNonNull List<String> preservedVerifiedStepIds,
    @JsonProperty(value = "private_reasoning_recovered") @ContractNonNull Boolean privateReasoningRecovered,
    @JsonProperty(value = "problem_hash") @ContractNonNull String problemHash,
    @JsonProperty(value = "raw_artifact_ref") String rawArtifactRef,
    @JsonProperty(value = "related_obligation_ids") @ContractNonNull List<String> relatedObligationIds,
    @JsonProperty(value = "requires_inspiration") @ContractNonNull Boolean requiresInspiration,
    @JsonProperty(value = "route_id") String routeId,
    @JsonProperty(value = "smallest_blocked_claim", required = true) @ContractNonNull String smallestBlockedClaim,
    @JsonProperty(value = "strategy_id") @ContractNonNull String strategyId,
    @JsonProperty(value = "usage") @ContractNonNull UsageRecord usage,
    @JsonProperty(value = "why_blocked_from_public_state", required = true) @ContractNonNull String whyBlockedFromPublicState
) implements StrictContract {

  public PostFailureBottleneckDiagnostic {
    alternativeMechanismTags = ImmutableCollections.requiredList("alternative_mechanism_tags", alternativeMechanismTags);
    ContractValues.minimumSize("alternative_mechanism_tags", alternativeMechanismTags, 1);
    attemptedMechanism = ContractStrings.trim(attemptedMechanism);
    attemptedMechanism = ContractStrings.required("attempted_mechanism", attemptedMechanism);
    ContractValues.minimumLength("attempted_mechanism", attemptedMechanism, 1);
    blockedClaimSource = ContractStrings.trim(blockedClaimSource);
    blockedClaimSource = ContractStrings.required("blocked_claim_source", blockedClaimSource);
    ContractValues.oneOf("blocked_claim_source", blockedClaimSource, "checkpoint_current_goal", "checkpoint_remaining_subgoal", "working_checkpoint_gap", "typed_public_context");
    if (checkpointId == null) {
      checkpointId = "";
    }
    checkpointId = ContractStrings.trim(checkpointId);
    if (confidence == null) {
      confidence = 0.5d;
    }
    ContractValues.minimum("confidence", confidence, 0.0);
    ContractValues.maximum("confidence", confidence, 1.0);
    if (diagnosticId == null) {
      diagnosticId = PythonCompatibleIdGenerator.newId("bottleneck");
    }
    diagnosticId = ContractStrings.trim(diagnosticId);
    if (exactFailedInternalStepKnown == null) {
      exactFailedInternalStepKnown = false;
    }
    ContractValues.constant("exact_failed_internal_step_known", exactFailedInternalStepKnown, false);
    if (failureFingerprint == null) {
      failureFingerprint = "";
    }
    failureFingerprint = ContractStrings.trim(failureFingerprint);
    if (failureType == null) {
      failureType = "reasoning_budget_exhausted";
    }
    failureType = ContractStrings.trim(failureType);
    ContractValues.oneOf("failure_type", failureType, "reasoning_budget_exhausted", "reasoning_only_stall");
    if (pathId == null) {
      pathId = "";
    }
    pathId = ContractStrings.trim(pathId);
    if (preservedFactMessageIds == null) {
      preservedFactMessageIds = List.of();
    }
    preservedFactMessageIds = ImmutableCollections.listOrEmpty(preservedFactMessageIds);
    if (preservedVerifiedStepIds == null) {
      preservedVerifiedStepIds = List.of();
    }
    preservedVerifiedStepIds = ImmutableCollections.listOrEmpty(preservedVerifiedStepIds);
    if (privateReasoningRecovered == null) {
      privateReasoningRecovered = false;
    }
    ContractValues.constant("private_reasoning_recovered", privateReasoningRecovered, false);
    if (problemHash == null) {
      problemHash = "";
    }
    problemHash = ContractStrings.trim(problemHash);
    rawArtifactRef = ContractStrings.trim(rawArtifactRef);
    if (relatedObligationIds == null) {
      relatedObligationIds = List.of();
    }
    relatedObligationIds = ImmutableCollections.listOrEmpty(relatedObligationIds);
    if (requiresInspiration == null) {
      requiresInspiration = true;
    }
    routeId = ContractStrings.trim(routeId);
    smallestBlockedClaim = ContractStrings.trim(smallestBlockedClaim);
    smallestBlockedClaim = ContractStrings.required("smallest_blocked_claim", smallestBlockedClaim);
    ContractValues.minimumLength("smallest_blocked_claim", smallestBlockedClaim, 1);
    if (strategyId == null) {
      strategyId = "";
    }
    strategyId = ContractStrings.trim(strategyId);
    if (usage == null) {
      usage = new UsageRecord();
    }
    whyBlockedFromPublicState = ContractStrings.trim(whyBlockedFromPublicState);
    whyBlockedFromPublicState = ContractStrings.required("why_blocked_from_public_state", whyBlockedFromPublicState);
    ContractValues.minimumLength("why_blocked_from_public_state", whyBlockedFromPublicState, 1);
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<String> alternativeMechanismTags() {
    return alternativeMechanismTags == null ? null : List.copyOf(alternativeMechanismTags);
  }

  public List<String> preservedFactMessageIds() {
    return preservedFactMessageIds == null ? null : List.copyOf(preservedFactMessageIds);
  }

  public List<String> preservedVerifiedStepIds() {
    return preservedVerifiedStepIds == null ? null : List.copyOf(preservedVerifiedStepIds);
  }

  public List<String> relatedObligationIds() {
    return relatedObligationIds == null ? null : List.copyOf(relatedObligationIds);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
