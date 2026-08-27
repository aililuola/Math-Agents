package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record ResearchProgressReport(
    @JsonProperty(value = "created_at") @ContractNonNull String createdAt,
    @JsonProperty(value = "execution_notes") @ContractNonNull List<String> executionNotes,
    @JsonProperty(value = "formalization_coverage") FormalizationCoverageReport formalizationCoverage,
    @JsonProperty(value = "negative_evidence") @ContractNonNull List<String> negativeEvidence,
    @JsonProperty(value = "open_obligations") @ContractNonNull List<ObjectNode> openObligations,
    @JsonProperty(value = "problem_hash", required = true) @ContractNonNull String problemHash,
    @JsonProperty(value = "refuted_routes") @ContractNonNull List<ObjectNode> refutedRoutes,
    @JsonProperty(value = "remaining_gaps") @ContractNonNull List<String> remainingGaps,
    @JsonProperty(value = "strongest_partial_attempt_id") String strongestPartialAttemptId,
    @JsonProperty(value = "summary", required = true) @ContractNonNull String summary,
    @JsonProperty(value = "termination_reason") String terminationReason,
    @JsonProperty(value = "valid_partial_attempt_ids") @ContractNonNull List<String> validPartialAttemptIds,
    @JsonProperty(value = "verified_local_claim_ids") @ContractNonNull List<String> verifiedLocalClaimIds,
    @JsonProperty(value = "verified_step_ids") @ContractNonNull List<String> verifiedStepIds
) implements StrictContract {

  public ResearchProgressReport {
    if (createdAt == null) {
      createdAt = PythonIsoTimestampCodec.now();
    }
    createdAt = ContractStrings.trim(createdAt);
    if (executionNotes == null) {
      executionNotes = List.of();
    }
    executionNotes = ImmutableCollections.listOrEmpty(executionNotes);
    if (negativeEvidence == null) {
      negativeEvidence = List.of();
    }
    negativeEvidence = ImmutableCollections.listOrEmpty(negativeEvidence);
    if (openObligations == null) {
      openObligations = List.of();
    }
    openObligations = ImmutableCollections.jsonListOrEmpty(openObligations);
    problemHash = ContractStrings.trim(problemHash);
    problemHash = ContractStrings.required("problem_hash", problemHash);
    if (refutedRoutes == null) {
      refutedRoutes = List.of();
    }
    refutedRoutes = ImmutableCollections.jsonListOrEmpty(refutedRoutes);
    if (remainingGaps == null) {
      remainingGaps = List.of();
    }
    remainingGaps = ImmutableCollections.listOrEmpty(remainingGaps);
    strongestPartialAttemptId = ContractStrings.trim(strongestPartialAttemptId);
    summary = ContractStrings.trim(summary);
    summary = ContractStrings.required("summary", summary);
    terminationReason = ContractStrings.trim(terminationReason);
    if (validPartialAttemptIds == null) {
      validPartialAttemptIds = List.of();
    }
    validPartialAttemptIds = ImmutableCollections.listOrEmpty(validPartialAttemptIds);
    if (verifiedLocalClaimIds == null) {
      verifiedLocalClaimIds = List.of();
    }
    verifiedLocalClaimIds = ImmutableCollections.listOrEmpty(verifiedLocalClaimIds);
    if (verifiedStepIds == null) {
      verifiedStepIds = List.of();
    }
    verifiedStepIds = ImmutableCollections.listOrEmpty(verifiedStepIds);
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<String> executionNotes() {
    return executionNotes == null ? null : List.copyOf(executionNotes);
  }

  public List<String> negativeEvidence() {
    return negativeEvidence == null ? null : List.copyOf(negativeEvidence);
  }

  public List<ObjectNode> openObligations() {
    return openObligations == null ? null : ImmutableCollections.copyJsonList(openObligations);
  }

  public List<ObjectNode> refutedRoutes() {
    return refutedRoutes == null ? null : ImmutableCollections.copyJsonList(refutedRoutes);
  }

  public List<String> remainingGaps() {
    return remainingGaps == null ? null : List.copyOf(remainingGaps);
  }

  public List<String> validPartialAttemptIds() {
    return validPartialAttemptIds == null ? null : List.copyOf(validPartialAttemptIds);
  }

  public List<String> verifiedLocalClaimIds() {
    return verifiedLocalClaimIds == null ? null : List.copyOf(verifiedLocalClaimIds);
  }

  public List<String> verifiedStepIds() {
    return verifiedStepIds == null ? null : List.copyOf(verifiedStepIds);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
