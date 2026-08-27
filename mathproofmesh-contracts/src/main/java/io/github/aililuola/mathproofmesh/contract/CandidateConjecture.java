package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record CandidateConjecture(
    @JsonProperty(value = "confidence") @ContractNonNull Double confidence,
    @JsonProperty(value = "conjecture_id") @ContractNonNull String conjectureId,
    @JsonProperty(value = "content_hash") @ContractNonNull String contentHash,
    @JsonProperty(value = "evidence_refs") @ContractNonNull List<EvidenceRef> evidenceRefs,
    @JsonProperty(value = "proof_obligations", required = true) @ContractNonNull List<String> proofObligations,
    @JsonProperty(value = "rationale", required = true) @ContractNonNull String rationale,
    @JsonProperty(value = "scope_limitations", required = true) @ContractNonNull List<String> scopeLimitations,
    @JsonProperty(value = "statement", required = true) @ContractNonNull String statement,
    @JsonProperty(value = "status") @ContractNonNull String status,
    @JsonProperty(value = "supporting_experiment_ids", required = true) @ContractNonNull List<String> supportingExperimentIds
) implements StrictContract {

  public CandidateConjecture {
    if (confidence == null) {
      confidence = 0.5d;
    }
    ContractValues.minimum("confidence", confidence, 0.0);
    ContractValues.maximum("confidence", confidence, 1.0);
    if (conjectureId == null) {
      conjectureId = PythonCompatibleIdGenerator.newId("conjecture");
    }
    conjectureId = ContractStrings.trim(conjectureId);
    if (contentHash == null) {
      contentHash = "";
    }
    contentHash = ContractStrings.trim(contentHash);
    if (evidenceRefs == null) {
      evidenceRefs = List.of();
    }
    evidenceRefs = ImmutableCollections.listOrEmpty(evidenceRefs);
    proofObligations = ImmutableCollections.requiredList("proof_obligations", proofObligations);
    rationale = ContractStrings.trim(rationale);
    rationale = ContractStrings.required("rationale", rationale);
    scopeLimitations = ImmutableCollections.requiredList("scope_limitations", scopeLimitations);
    statement = ContractStrings.trim(statement);
    statement = ContractStrings.required("statement", statement);
    if (status == null) {
      status = "candidate";
    }
    status = ContractStrings.trim(status);
    ContractValues.constant("status", status, "candidate");
    supportingExperimentIds = ImmutableCollections.requiredList("supporting_experiment_ids", supportingExperimentIds);
    if (supportingExperimentIds.isEmpty()) {
      throw new ContractValidationException(
          "candidate conjecture requires at least one supporting experiment");
    }
    if (scopeLimitations.isEmpty()) {
      throw new ContractValidationException(
          "candidate conjecture scope_limitations must state why its evidence is not a proof");
    }
    if (proofObligations.isEmpty()) {
      throw new ContractValidationException(
          "candidate conjecture requires at least one separate proof obligation");
    }
    contentHash =
        ContractHashes.checked(
            "candidate conjecture content_hash",
            contentHash,
            ContractHashes.candidateConjectureHash(
                statement, supportingExperimentIds, scopeLimitations, proofObligations));
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<EvidenceRef> evidenceRefs() {
    return evidenceRefs == null ? null : List.copyOf(evidenceRefs);
  }

  public List<String> proofObligations() {
    return proofObligations == null ? null : List.copyOf(proofObligations);
  }

  public List<String> scopeLimitations() {
    return scopeLimitations == null ? null : List.copyOf(scopeLimitations);
  }

  public List<String> supportingExperimentIds() {
    return supportingExperimentIds == null ? null : List.copyOf(supportingExperimentIds);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
