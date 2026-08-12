package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record ProofAttempt(
    @JsonProperty(value = "agent_id", required = true) @ContractNonNull String agentId,
    @JsonProperty(value = "attempt_id") @ContractNonNull String attemptId,
    @JsonProperty(value = "candidate_conjectures") @ContractNonNull List<CandidateConjecture> candidateConjectures,
    @JsonProperty(value = "checkpoint_ids") @ContractNonNull List<String> checkpointIds,
    @JsonProperty(value = "dead_ends") @ContractNonNull List<String> deadEnds,
    @JsonProperty(value = "failover_chain") @ContractNonNull List<String> failoverChain,
    @JsonProperty(value = "falsification_checks") @ContractNonNull List<String> falsificationChecks,
    @JsonProperty(value = "final_answer") String finalAnswer,
    @JsonProperty(value = "latest_checkpoint_id") String latestCheckpointId,
    @JsonProperty(value = "path_id") String pathId,
    @JsonProperty(value = "problem_hash", required = true) @ContractNonNull String problemHash,
    @JsonProperty(value = "proof_sketch") @ContractNonNull String proofSketch,
    @JsonProperty(value = "proof_steps") @ContractNonNull List<ProofStep> proofSteps,
    @JsonProperty(value = "proposed_lemmas") @ContractNonNull List<ClaimCard> proposedLemmas,
    @JsonProperty(value = "raw_artifact_ref") String rawArtifactRef,
    @JsonProperty(value = "resumed_from_checkpoint_id") String resumedFromCheckpointId,
    @JsonProperty(value = "round_index", required = true) @ContractNonNull Integer roundIndex,
    @JsonProperty(value = "segment_count") @ContractNonNull Integer segmentCount,
    @JsonProperty(value = "self_confidence") @ContractNonNull Double selfConfidence,
    @JsonProperty(value = "status", required = true) @ContractNonNull AttemptStatus status,
    @JsonProperty(value = "strategy_id", required = true) @ContractNonNull String strategyId,
    @JsonProperty(value = "unresolved_gaps") @ContractNonNull List<String> unresolvedGaps,
    @JsonProperty(value = "usage") @ContractNonNull UsageRecord usage
) implements StrictContract {

  public ProofAttempt {
    agentId = ContractStrings.trim(agentId);
    agentId = ContractStrings.required("agent_id", agentId);
    if (attemptId == null) {
      attemptId = PythonCompatibleIdGenerator.newId("attempt");
    }
    attemptId = ContractStrings.trim(attemptId);
    if (candidateConjectures == null) {
      candidateConjectures = List.of();
    }
    candidateConjectures = ImmutableCollections.listOrEmpty(candidateConjectures);
    if (checkpointIds == null) {
      checkpointIds = List.of();
    }
    checkpointIds = ImmutableCollections.listOrEmpty(checkpointIds);
    if (deadEnds == null) {
      deadEnds = List.of();
    }
    deadEnds = ImmutableCollections.listOrEmpty(deadEnds);
    if (failoverChain == null) {
      failoverChain = List.of();
    }
    failoverChain = ImmutableCollections.listOrEmpty(failoverChain);
    if (falsificationChecks == null) {
      falsificationChecks = List.of();
    }
    falsificationChecks = ImmutableCollections.listOrEmpty(falsificationChecks);
    finalAnswer = ContractStrings.trim(finalAnswer);
    latestCheckpointId = ContractStrings.trim(latestCheckpointId);
    pathId = ContractStrings.trim(pathId);
    problemHash = ContractStrings.trim(problemHash);
    problemHash = ContractStrings.required("problem_hash", problemHash);
    if (proofSketch == null) {
      proofSketch = "";
    }
    proofSketch = ContractStrings.trim(proofSketch);
    ContractValues.maximumLength("proof_sketch", proofSketch, 4000);
    if (proofSteps == null) {
      proofSteps = List.of();
    }
    proofSteps = ImmutableCollections.listOrEmpty(proofSteps);
    if (proposedLemmas == null) {
      proposedLemmas = List.of();
    }
    proposedLemmas = ImmutableCollections.listOrEmpty(proposedLemmas);
    rawArtifactRef = ContractStrings.trim(rawArtifactRef);
    resumedFromCheckpointId = ContractStrings.trim(resumedFromCheckpointId);
    roundIndex = ContractValues.required("round_index", roundIndex);
    ContractValues.minimum("round_index", roundIndex, 0);
    if (segmentCount == null) {
      segmentCount = 0;
    }
    ContractValues.minimum("segment_count", segmentCount, 0);
    if (selfConfidence == null) {
      selfConfidence = 0.5d;
    }
    ContractValues.minimum("self_confidence", selfConfidence, 0.0);
    ContractValues.maximum("self_confidence", selfConfidence, 1.0);
    status = ContractValues.required("status", status);
    strategyId = ContractStrings.trim(strategyId);
    strategyId = ContractStrings.required("strategy_id", strategyId);
    if (unresolvedGaps == null) {
      unresolvedGaps = List.of();
    }
    unresolvedGaps = ImmutableCollections.listOrEmpty(unresolvedGaps);
    if (usage == null) {
      usage = new UsageRecord();
    }
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<CandidateConjecture> candidateConjectures() {
    return candidateConjectures == null ? null : List.copyOf(candidateConjectures);
  }

  public List<String> checkpointIds() {
    return checkpointIds == null ? null : List.copyOf(checkpointIds);
  }

  public List<String> deadEnds() {
    return deadEnds == null ? null : List.copyOf(deadEnds);
  }

  public List<String> failoverChain() {
    return failoverChain == null ? null : List.copyOf(failoverChain);
  }

  public List<String> falsificationChecks() {
    return falsificationChecks == null ? null : List.copyOf(falsificationChecks);
  }

  public List<ProofStep> proofSteps() {
    return proofSteps == null ? null : List.copyOf(proofSteps);
  }

  public List<ClaimCard> proposedLemmas() {
    return proposedLemmas == null ? null : List.copyOf(proposedLemmas);
  }

  public List<String> unresolvedGaps() {
    return unresolvedGaps == null ? null : List.copyOf(unresolvedGaps);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
