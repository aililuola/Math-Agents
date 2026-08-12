package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record ProofCheckpoint(
    @JsonProperty(value = "active_assumptions") @ContractNonNull List<String> activeAssumptions,
    @JsonProperty(value = "checkpoint_id") @ContractNonNull String checkpointId,
    @JsonProperty(value = "content_hash") @ContractNonNull String contentHash,
    @JsonProperty(value = "created_at") @ContractNonNull String createdAt,
    @JsonProperty(value = "current_goal") String currentGoal,
    @JsonProperty(value = "failover_chain") @ContractNonNull List<String> failoverChain,
    @JsonProperty(value = "final_answer") String finalAnswer,
    @JsonProperty(value = "known_risks") @ContractNonNull List<String> knownRisks,
    @JsonProperty(value = "parent_checkpoint_id") String parentCheckpointId,
    @JsonProperty(value = "path_id", required = true) @ContractNonNull String pathId,
    @JsonProperty(value = "problem_hash", required = true) @ContractNonNull String problemHash,
    @JsonProperty(value = "proof_complete") @ContractNonNull Boolean proofComplete,
    @JsonProperty(value = "proof_sketch") @ContractNonNull String proofSketch,
    @JsonProperty(value = "remaining_subgoals") @ContractNonNull List<String> remainingSubgoals,
    @JsonProperty(value = "segment_index") @ContractNonNull Integer segmentIndex,
    @JsonProperty(value = "source_agent_id") String sourceAgentId,
    @JsonProperty(value = "source_delta_id") String sourceDeltaId,
    @JsonProperty(value = "status") @ContractNonNull CheckpointStatus status,
    @JsonProperty(value = "strategy_id", required = true) @ContractNonNull String strategyId,
    @JsonProperty(value = "verification_report_ids") @ContractNonNull List<String> verificationReportIds,
    @JsonProperty(value = "verified_claim_ids") @ContractNonNull List<String> verifiedClaimIds,
    @JsonProperty(value = "verified_steps") @ContractNonNull List<ProofStep> verifiedSteps,
    @JsonProperty(value = "working_notes") @ContractNonNull String workingNotes
) implements StrictContract {

  public ProofCheckpoint {
    if (activeAssumptions == null) {
      activeAssumptions = List.of();
    }
    activeAssumptions = ImmutableCollections.listOrEmpty(activeAssumptions);
    if (checkpointId == null) {
      checkpointId = PythonCompatibleIdGenerator.newId("checkpoint");
    }
    checkpointId = ContractStrings.trim(checkpointId);
    if (contentHash == null) {
      contentHash = "";
    }
    contentHash = ContractStrings.trim(contentHash);
    if (createdAt == null) {
      createdAt = PythonIsoTimestampCodec.now();
    }
    createdAt = ContractStrings.trim(createdAt);
    currentGoal = ContractStrings.trim(currentGoal);
    if (failoverChain == null) {
      failoverChain = List.of();
    }
    failoverChain = ImmutableCollections.listOrEmpty(failoverChain);
    finalAnswer = ContractStrings.trim(finalAnswer);
    if (knownRisks == null) {
      knownRisks = List.of();
    }
    knownRisks = ImmutableCollections.listOrEmpty(knownRisks);
    parentCheckpointId = ContractStrings.trim(parentCheckpointId);
    pathId = ContractStrings.trim(pathId);
    pathId = ContractStrings.required("path_id", pathId);
    problemHash = ContractStrings.trim(problemHash);
    problemHash = ContractStrings.required("problem_hash", problemHash);
    if (proofComplete == null) {
      proofComplete = false;
    }
    if (proofSketch == null) {
      proofSketch = "";
    }
    proofSketch = ContractStrings.trim(proofSketch);
    ContractValues.maximumLength("proof_sketch", proofSketch, 4000);
    if (remainingSubgoals == null) {
      remainingSubgoals = List.of();
    }
    remainingSubgoals = ImmutableCollections.listOrEmpty(remainingSubgoals);
    if (segmentIndex == null) {
      segmentIndex = 0;
    }
    ContractValues.minimum("segment_index", segmentIndex, 0);
    sourceAgentId = ContractStrings.trim(sourceAgentId);
    sourceDeltaId = ContractStrings.trim(sourceDeltaId);
    if (status == null) {
      status = CheckpointStatus.COMMITTED;
    }
    strategyId = ContractStrings.trim(strategyId);
    strategyId = ContractStrings.required("strategy_id", strategyId);
    if (verificationReportIds == null) {
      verificationReportIds = List.of();
    }
    verificationReportIds = ImmutableCollections.listOrEmpty(verificationReportIds);
    if (verifiedClaimIds == null) {
      verifiedClaimIds = List.of();
    }
    verifiedClaimIds = ImmutableCollections.listOrEmpty(verifiedClaimIds);
    if (verifiedSteps == null) {
      verifiedSteps = List.of();
    }
    verifiedSteps = ImmutableCollections.listOrEmpty(verifiedSteps);
    if (workingNotes == null) {
      workingNotes = "";
    }
    workingNotes = ContractStrings.trim(workingNotes);
    ContractValues.maximumLength("working_notes", workingNotes, 4000);
    if (proofComplete && finalAnswer == null) {
      throw new ContractValidationException(
          "proof_complete checkpoint requires final_answer");
    }
    if (proofComplete && !remainingSubgoals.isEmpty()) {
      throw new ContractValidationException(
          "proof_complete checkpoint cannot retain remaining_subgoals");
    }
    contentHash =
        ContractHashes.checked(
            "checkpoint content_hash",
            contentHash,
            ContractHashes.checkpointHash(
                parentCheckpointId,
                problemHash,
                pathId,
                strategyId,
                segmentIndex,
                verifiedSteps,
                verifiedClaimIds,
                activeAssumptions,
                remainingSubgoals,
                currentGoal,
                knownRisks,
                finalAnswer,
                proofComplete));
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<String> activeAssumptions() {
    return activeAssumptions == null ? null : List.copyOf(activeAssumptions);
  }

  public List<String> failoverChain() {
    return failoverChain == null ? null : List.copyOf(failoverChain);
  }

  public List<String> knownRisks() {
    return knownRisks == null ? null : List.copyOf(knownRisks);
  }

  public List<String> remainingSubgoals() {
    return remainingSubgoals == null ? null : List.copyOf(remainingSubgoals);
  }

  public List<String> verificationReportIds() {
    return verificationReportIds == null ? null : List.copyOf(verificationReportIds);
  }

  public List<String> verifiedClaimIds() {
    return verifiedClaimIds == null ? null : List.copyOf(verifiedClaimIds);
  }

  public List<ProofStep> verifiedSteps() {
    return verifiedSteps == null ? null : List.copyOf(verifiedSteps);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
