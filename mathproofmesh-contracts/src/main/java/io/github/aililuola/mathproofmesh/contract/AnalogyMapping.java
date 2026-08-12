package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record AnalogyMapping(
    @JsonProperty(value = "analogy_id") @ContractNonNull String analogyId,
    @JsonProperty(value = "non_transferable_conditions", required = true) @ContractNonNull List<String> nonTransferableConditions,
    @JsonProperty(value = "novelty_signature", required = true) @ContractNonNull NoveltySignature noveltySignature,
    @JsonProperty(value = "object_correspondence", required = true) @ContractNonNull Map<String, String> objectCorrespondence,
    @JsonProperty(value = "operation_correspondence", required = true) @ContractNonNull Map<String, String> operationCorrespondence,
    @JsonProperty(value = "required_bridge_lemmas") @ContractNonNull List<String> requiredBridgeLemmas,
    @JsonProperty(value = "source_problem_summary", required = true) @ContractNonNull String sourceProblemSummary,
    @JsonProperty(value = "source_record_id", required = true) @ContractNonNull String sourceRecordId,
    @JsonProperty(value = "target_problem_hash", required = true) @ContractNonNull String targetProblemHash,
    @JsonProperty(value = "transfer_risks", required = true) @ContractNonNull List<String> transferRisks,
    @JsonProperty(value = "transferable_lemmas", required = true) @ContractNonNull List<String> transferableLemmas
) implements StrictContract {

  public AnalogyMapping {
    if (analogyId == null) {
      analogyId = PythonCompatibleIdGenerator.newId("analogy");
    }
    analogyId = ContractStrings.trim(analogyId);
    nonTransferableConditions = ImmutableCollections.requiredList("non_transferable_conditions", nonTransferableConditions);
    noveltySignature = ContractValues.required("novelty_signature", noveltySignature);
    objectCorrespondence = ImmutableCollections.requiredMap("object_correspondence", objectCorrespondence);
    operationCorrespondence = ImmutableCollections.requiredMap("operation_correspondence", operationCorrespondence);
    if (requiredBridgeLemmas == null) {
      requiredBridgeLemmas = List.of();
    }
    requiredBridgeLemmas = ImmutableCollections.listOrEmpty(requiredBridgeLemmas);
    sourceProblemSummary = ContractStrings.trim(sourceProblemSummary);
    sourceProblemSummary = ContractStrings.required("source_problem_summary", sourceProblemSummary);
    sourceRecordId = ContractStrings.trim(sourceRecordId);
    sourceRecordId = ContractStrings.required("source_record_id", sourceRecordId);
    targetProblemHash = ContractStrings.trim(targetProblemHash);
    targetProblemHash = ContractStrings.required("target_problem_hash", targetProblemHash);
    transferRisks = ImmutableCollections.requiredList("transfer_risks", transferRisks);
    transferableLemmas = ImmutableCollections.requiredList("transferable_lemmas", transferableLemmas);
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<String> nonTransferableConditions() {
    return nonTransferableConditions == null ? null : List.copyOf(nonTransferableConditions);
  }

  public Map<String, String> objectCorrespondence() {
    return objectCorrespondence == null ? null : Map.copyOf(objectCorrespondence);
  }

  public Map<String, String> operationCorrespondence() {
    return operationCorrespondence == null ? null : Map.copyOf(operationCorrespondence);
  }

  public List<String> requiredBridgeLemmas() {
    return requiredBridgeLemmas == null ? null : List.copyOf(requiredBridgeLemmas);
  }

  public List<String> transferRisks() {
    return transferRisks == null ? null : List.copyOf(transferRisks);
  }

  public List<String> transferableLemmas() {
    return transferableLemmas == null ? null : List.copyOf(transferableLemmas);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
