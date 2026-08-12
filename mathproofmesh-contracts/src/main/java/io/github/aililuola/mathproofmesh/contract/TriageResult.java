package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record TriageResult(
    @JsonProperty(value = "confidence", required = true) @ContractNonNull Double confidence,
    @JsonProperty(value = "difficulty", required = true) @ContractNonNull Difficulty difficulty,
    @JsonProperty(value = "key_risks") @ContractNonNull List<String> keyRisks,
    @JsonProperty(value = "likely_tools") @ContractNonNull List<String> likelyTools,
    @JsonProperty(value = "problem_kind", required = true) @ContractNonNull ProblemKind problemKind,
    @JsonProperty(value = "proof_mode")
        @ContractNonNull
        @ContractAllowedValues({"direct", "decomposition", "hybrid"})
        String proofMode,
    @JsonProperty(value = "rationale", required = true) @ContractNonNull String rationale,
    @JsonProperty(value = "semantic_view_candidate") ProblemSemanticViewCandidate semanticViewCandidate,
    @JsonProperty(value = "suggested_paths") @ContractNonNull Integer suggestedPaths,
    @JsonProperty(value = "suggested_rounds") @ContractNonNull Integer suggestedRounds,
    @JsonProperty(value = "task_requirements") @ContractNonNull List<TaskRequirement> taskRequirements
) implements StrictContract {

  public TriageResult {
    confidence = ContractValues.required("confidence", confidence);
    ContractValues.minimum("confidence", confidence, 0.0);
    ContractValues.maximum("confidence", confidence, 1.0);
    difficulty = ContractValues.required("difficulty", difficulty);
    if (keyRisks == null) {
      keyRisks = List.of();
    }
    keyRisks = ImmutableCollections.listOrEmpty(keyRisks);
    if (likelyTools == null) {
      likelyTools = List.of();
    }
    likelyTools = ImmutableCollections.listOrEmpty(likelyTools);
    problemKind = ContractValues.required("problem_kind", problemKind);
    if (proofMode == null) {
      proofMode = "hybrid";
    }
    proofMode = ContractStrings.trim(proofMode);
    ContractValues.oneOf("proof_mode", proofMode, "direct", "decomposition", "hybrid");
    rationale = ContractStrings.trim(rationale);
    rationale = ContractStrings.required("rationale", rationale);
    if (suggestedPaths == null) {
      suggestedPaths = 4;
    }
    ContractValues.minimum("suggested_paths", suggestedPaths, 1);
    ContractValues.maximum("suggested_paths", suggestedPaths, 16);
    if (suggestedRounds == null) {
      suggestedRounds = 3;
    }
    ContractValues.minimum("suggested_rounds", suggestedRounds, 1);
    ContractValues.maximum("suggested_rounds", suggestedRounds, 16);
    if (taskRequirements == null) {
      taskRequirements = List.of();
    }
    taskRequirements = ImmutableCollections.listOrEmpty(taskRequirements);
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<String> keyRisks() {
    return keyRisks == null ? null : List.copyOf(keyRisks);
  }

  public List<String> likelyTools() {
    return likelyTools == null ? null : List.copyOf(likelyTools);
  }

  public List<TaskRequirement> taskRequirements() {
    return taskRequirements == null ? null : List.copyOf(taskRequirements);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
