package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record ProofStep(
    @JsonProperty(value = "branch_label") String branchLabel,
    @JsonProperty(value = "calculation_checks") @ContractNonNull List<ToolRequest> calculationChecks,
    @JsonProperty(value = "calculation_evidence_refs") @ContractNonNull List<EvidenceRef> calculationEvidenceRefs,
    @JsonProperty(value = "calculations") @ContractNonNull List<String> calculations,
    @JsonProperty(value = "citations") @ContractNonNull List<CitationRecord> citations,
    @JsonProperty(value = "confidence") @ContractNonNull Double confidence,
    @JsonProperty(value = "dependencies") @ContractNonNull List<String> dependencies,
    @JsonProperty(value = "dependency_refs") @ContractNonNull List<JsonNode> dependencyRefs,
    @JsonProperty(value = "is_key_step") @ContractNonNull Boolean isKeyStep,
    @JsonProperty(value = "justification", required = true) @ContractNonNull String justification,
    @JsonProperty(value = "statement", required = true) @ContractNonNull String statement,
    @JsonProperty(value = "step_id", required = true) @ContractNonNull String stepId,
    @JsonProperty(value = "step_type")
        @ContractNonNull
        @ContractAllowedValues({
          "derivation",
          "assumption_intro",
          "assumption_discharge",
          "case_split",
          "case_close",
          "definition",
          "construction"
        })
        String stepType
) implements StrictContract {

  public ProofStep {
    branchLabel = ContractStrings.trim(branchLabel);
    if (calculationChecks == null) {
      calculationChecks = List.of();
    }
    calculationChecks = ImmutableCollections.listOrEmpty(calculationChecks);
    if (calculationEvidenceRefs == null) {
      calculationEvidenceRefs = List.of();
    }
    calculationEvidenceRefs = ImmutableCollections.listOrEmpty(calculationEvidenceRefs);
    if (calculations == null) {
      calculations = List.of();
    }
    calculations = ImmutableCollections.listOrEmpty(calculations);
    if (citations == null) {
      citations = List.of();
    }
    citations = ImmutableCollections.listOrEmpty(citations);
    if (confidence == null) {
      confidence = 0.5d;
    }
    ContractValues.minimum("confidence", confidence, 0.0);
    ContractValues.maximum("confidence", confidence, 1.0);
    if (dependencies == null) {
      dependencies = List.of();
    }
    dependencies = ImmutableCollections.listOrEmpty(dependencies);
    if (dependencyRefs == null) {
      dependencyRefs = List.of();
    }
    dependencyRefs = ImmutableCollections.jsonListOrEmpty(dependencyRefs);
    if (isKeyStep == null) {
      isKeyStep = false;
    }
    justification = ContractStrings.trim(justification);
    justification = ContractStrings.required("justification", justification);
    statement = ContractStrings.trim(statement);
    statement = ContractStrings.required("statement", statement);
    stepId = ContractStrings.trim(stepId);
    stepId = ContractStrings.required("step_id", stepId);
    if (stepType == null) {
      stepType = "derivation";
    }
    stepType = ContractStrings.trim(stepType);
    ContractValues.oneOf("step_type", stepType, "derivation", "assumption_intro", "assumption_discharge", "case_split", "case_close", "definition", "construction");
  }

  @JsonIgnore
  public ObjectNode checkpointPayload() {
    return ContractHashes.proofStepCheckpointPayload(this);
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<ToolRequest> calculationChecks() {
    return calculationChecks == null ? null : List.copyOf(calculationChecks);
  }

  public List<EvidenceRef> calculationEvidenceRefs() {
    return calculationEvidenceRefs == null ? null : List.copyOf(calculationEvidenceRefs);
  }

  public List<String> calculations() {
    return calculations == null ? null : List.copyOf(calculations);
  }

  public List<CitationRecord> citations() {
    return citations == null ? null : List.copyOf(citations);
  }

  public List<String> dependencies() {
    return dependencies == null ? null : List.copyOf(dependencies);
  }

  public List<JsonNode> dependencyRefs() {
    return dependencyRefs == null ? null : ImmutableCollections.copyJsonList(dependencyRefs);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
