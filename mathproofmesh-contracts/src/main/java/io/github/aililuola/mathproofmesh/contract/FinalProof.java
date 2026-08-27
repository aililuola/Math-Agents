package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record FinalProof(
    @JsonProperty(value = "answer", required = true) @ContractNonNull String answer,
    @JsonProperty(value = "caveats") @ContractNonNull List<String> caveats,
    @JsonProperty(value = "confidence", required = true) @ContractNonNull Double confidence,
    @JsonProperty(value = "dependencies") @ContractNonNull List<String> dependencies,
    @JsonProperty(value = "problem_hash", required = true) @ContractNonNull String problemHash,
    @JsonProperty(value = "proof_steps", required = true) @ContractNonNull List<ProofStep> proofSteps,
    @JsonProperty(value = "source_attempt_ids") @ContractNonNull List<String> sourceAttemptIds
) implements StrictContract {

  public FinalProof {
    answer = ContractStrings.trim(answer);
    answer = ContractStrings.required("answer", answer);
    if (caveats == null) {
      caveats = List.of();
    }
    caveats = ImmutableCollections.listOrEmpty(caveats);
    confidence = ContractValues.required("confidence", confidence);
    ContractValues.minimum("confidence", confidence, 0.0);
    ContractValues.maximum("confidence", confidence, 1.0);
    if (dependencies == null) {
      dependencies = List.of();
    }
    dependencies = ImmutableCollections.listOrEmpty(dependencies);
    problemHash = ContractStrings.trim(problemHash);
    problemHash = ContractStrings.required("problem_hash", problemHash);
    proofSteps = ImmutableCollections.requiredList("proof_steps", proofSteps);
    if (sourceAttemptIds == null) {
      sourceAttemptIds = List.of();
    }
    sourceAttemptIds = ImmutableCollections.listOrEmpty(sourceAttemptIds);
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<String> caveats() {
    return caveats == null ? null : List.copyOf(caveats);
  }

  public List<String> dependencies() {
    return dependencies == null ? null : List.copyOf(dependencies);
  }

  public List<ProofStep> proofSteps() {
    return proofSteps == null ? null : List.copyOf(proofSteps);
  }

  public List<String> sourceAttemptIds() {
    return sourceAttemptIds == null ? null : List.copyOf(sourceAttemptIds);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
