package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record ProofDelta(
    @JsonProperty(value = "active_assumptions") List<String> activeAssumptions,
    @JsonProperty(value = "agent_id", required = true) @ContractNonNull String agentId,
    @JsonProperty(value = "candidate_conjectures") @ContractNonNull List<CandidateConjecture> candidateConjectures,
    @JsonProperty(value = "candidate_final_answer") String candidateFinalAnswer,
    @JsonProperty(value = "completed_subgoal") String completedSubgoal,
    @JsonProperty(value = "current_goal") String currentGoal,
    @JsonProperty(value = "delta_id") @ContractNonNull String deltaId,
    @JsonProperty(value = "dependency_refs") @ContractNonNull List<JsonNode> dependencyRefs,
    @JsonProperty(value = "detected_conflicts") @ContractNonNull List<String> detectedConflicts,
    @JsonProperty(value = "known_risks") @ContractNonNull List<String> knownRisks,
    @JsonProperty(value = "new_claims") @ContractNonNull List<ClaimCard> newClaims,
    @JsonProperty(value = "new_steps") @ContractNonNull List<ProofStep> newSteps,
    @JsonProperty(value = "normalization_notes") @ContractNonNull List<String> normalizationNotes,
    @JsonProperty(value = "parent_checkpoint_id", required = true) @ContractNonNull String parentCheckpointId,
    @JsonProperty(value = "path_id", required = true) @ContractNonNull String pathId,
    @JsonProperty(value = "problem_hash", required = true) @ContractNonNull String problemHash,
    @JsonProperty(value = "proof_complete") @ContractNonNull Boolean proofComplete,
    @JsonProperty(value = "raw_artifact_ref") String rawArtifactRef,
    @JsonProperty(value = "ready_for_verification") @ContractNonNull Boolean readyForVerification,
    @JsonProperty(value = "referenced_checkpoint_step_ids") @ContractNonNull List<String> referencedCheckpointStepIds,
    @JsonProperty(value = "remaining_subgoals") @ContractNonNull List<String> remainingSubgoals,
    @JsonProperty(value = "round_index", required = true) @ContractNonNull Integer roundIndex,
    @JsonProperty(value = "segment_index", required = true) @ContractNonNull Integer segmentIndex,
    @JsonProperty(value = "self_confidence") @ContractNonNull Double selfConfidence,
    @JsonProperty(value = "strategy_id", required = true) @ContractNonNull String strategyId,
    @JsonProperty(value = "usage") @ContractNonNull UsageRecord usage,
    @JsonProperty(value = "working_notes") @ContractNonNull String workingNotes
) implements StrictContract {

  public ProofDelta {
    activeAssumptions = ImmutableCollections.nullableList(activeAssumptions);
    agentId = ContractStrings.trim(agentId);
    agentId = ContractStrings.required("agent_id", agentId);
    if (candidateConjectures == null) {
      candidateConjectures = List.of();
    }
    candidateConjectures = ImmutableCollections.listOrEmpty(candidateConjectures);
    candidateFinalAnswer = ContractStrings.trim(candidateFinalAnswer);
    completedSubgoal = ContractStrings.trim(completedSubgoal);
    currentGoal = ContractStrings.trim(currentGoal);
    if (deltaId == null) {
      deltaId = PythonCompatibleIdGenerator.newId("delta");
    }
    deltaId = ContractStrings.trim(deltaId);
    if (dependencyRefs == null) {
      dependencyRefs = List.of();
    }
    dependencyRefs = ImmutableCollections.jsonListOrEmpty(dependencyRefs);
    if (detectedConflicts == null) {
      detectedConflicts = List.of();
    }
    detectedConflicts = ImmutableCollections.listOrEmpty(detectedConflicts);
    if (knownRisks == null) {
      knownRisks = List.of();
    }
    knownRisks = ImmutableCollections.listOrEmpty(knownRisks);
    if (newClaims == null) {
      newClaims = List.of();
    }
    newClaims = ImmutableCollections.listOrEmpty(newClaims);
    if (newSteps == null) {
      newSteps = List.of();
    }
    newSteps = ImmutableCollections.listOrEmpty(newSteps);
    if (normalizationNotes == null) {
      normalizationNotes = List.of();
    }
    normalizationNotes = ImmutableCollections.listOrEmpty(normalizationNotes);
    parentCheckpointId = ContractStrings.trim(parentCheckpointId);
    parentCheckpointId = ContractStrings.required("parent_checkpoint_id", parentCheckpointId);
    pathId = ContractStrings.trim(pathId);
    pathId = ContractStrings.required("path_id", pathId);
    problemHash = ContractStrings.trim(problemHash);
    problemHash = ContractStrings.required("problem_hash", problemHash);
    if (proofComplete == null) {
      proofComplete = false;
    }
    rawArtifactRef = ContractStrings.trim(rawArtifactRef);
    if (readyForVerification == null) {
      readyForVerification = true;
    }
    if (referencedCheckpointStepIds == null) {
      referencedCheckpointStepIds = List.of();
    }
    referencedCheckpointStepIds = ImmutableCollections.listOrEmpty(referencedCheckpointStepIds);
    if (remainingSubgoals == null) {
      remainingSubgoals = List.of();
    }
    remainingSubgoals = ImmutableCollections.listOrEmpty(remainingSubgoals);
    roundIndex = ContractValues.required("round_index", roundIndex);
    ContractValues.minimum("round_index", roundIndex, 0);
    segmentIndex = ContractValues.required("segment_index", segmentIndex);
    ContractValues.minimum("segment_index", segmentIndex, 1);
    if (selfConfidence == null) {
      selfConfidence = 0.5d;
    }
    ContractValues.minimum("self_confidence", selfConfidence, 0.0);
    ContractValues.maximum("self_confidence", selfConfidence, 1.0);
    strategyId = ContractStrings.trim(strategyId);
    strategyId = ContractStrings.required("strategy_id", strategyId);
    if (usage == null) {
      usage = new UsageRecord();
    }
    if (workingNotes == null) {
      workingNotes = "";
    }
    workingNotes = ContractStrings.trim(workingNotes);
    ContractValues.maximumLength("working_notes", workingNotes, 4000);
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<String> activeAssumptions() {
    return activeAssumptions == null ? null : List.copyOf(activeAssumptions);
  }

  public List<CandidateConjecture> candidateConjectures() {
    return candidateConjectures == null ? null : List.copyOf(candidateConjectures);
  }

  public List<JsonNode> dependencyRefs() {
    return dependencyRefs == null ? null : ImmutableCollections.copyJsonList(dependencyRefs);
  }

  public List<String> detectedConflicts() {
    return detectedConflicts == null ? null : List.copyOf(detectedConflicts);
  }

  public List<String> knownRisks() {
    return knownRisks == null ? null : List.copyOf(knownRisks);
  }

  public List<ClaimCard> newClaims() {
    return newClaims == null ? null : List.copyOf(newClaims);
  }

  public List<ProofStep> newSteps() {
    return newSteps == null ? null : List.copyOf(newSteps);
  }

  public List<String> normalizationNotes() {
    return normalizationNotes == null ? null : List.copyOf(normalizationNotes);
  }

  public List<String> referencedCheckpointStepIds() {
    return referencedCheckpointStepIds == null ? null : List.copyOf(referencedCheckpointStepIds);
  }

  public List<String> remainingSubgoals() {
    return remainingSubgoals == null ? null : List.copyOf(remainingSubgoals);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
