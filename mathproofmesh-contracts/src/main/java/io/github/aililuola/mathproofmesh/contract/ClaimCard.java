package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record ClaimCard(
    @JsonProperty(value = "assumptions") @ContractNonNull List<String> assumptions,
    @JsonProperty(value = "claim_id") @ContractNonNull String claimId,
    @JsonProperty(value = "conclusion", required = true) @ContractNonNull String conclusion,
    @JsonProperty(value = "content_hash") @ContractNonNull String contentHash,
    @JsonProperty(value = "counterexample_risk") @ContractNonNull String counterexampleRisk,
    @JsonProperty(value = "dependencies") @ContractNonNull List<String> dependencies,
    @JsonProperty(value = "dependency_refs") @ContractNonNull List<JsonNode> dependencyRefs,
    @JsonProperty(value = "evidence_refs") @ContractNonNull List<EvidenceRef> evidenceRefs,
    @JsonProperty(value = "proof_steps") @ContractNonNull List<ProofStep> proofSteps,
    @JsonProperty(value = "scope_limitations") @ContractNonNull List<String> scopeLimitations,
    @JsonProperty(value = "self_confidence") @ContractNonNull Double selfConfidence,
    @JsonProperty(value = "source_agent_id") String sourceAgentId,
    @JsonProperty(value = "source_attempt_id") String sourceAttemptId,
    @JsonProperty(value = "source_delta_id") String sourceDeltaId,
    @JsonProperty(value = "statement", required = true) @ContractNonNull String statement,
    @JsonProperty(value = "status") @ContractNonNull ClaimStatus status,
    @JsonProperty(value = "tags") @ContractNonNull List<String> tags,
    @JsonProperty(value = "verification_confidence") Double verificationConfidence
) implements StrictContract {

  public ClaimCard {
    if (assumptions == null) {
      assumptions = List.of();
    }
    assumptions = ImmutableCollections.listOrEmpty(assumptions);
    if (claimId == null) {
      claimId = PythonCompatibleIdGenerator.newId("claim");
    }
    claimId = ContractStrings.trim(claimId);
    conclusion = ContractStrings.trim(conclusion);
    conclusion = ContractStrings.required("conclusion", conclusion);
    if (contentHash == null) {
      contentHash = "";
    }
    contentHash = ContractStrings.trim(contentHash);
    if (counterexampleRisk == null) {
      counterexampleRisk = "unknown";
    }
    counterexampleRisk = ContractStrings.trim(counterexampleRisk);
    if (dependencies == null) {
      dependencies = List.of();
    }
    dependencies = ImmutableCollections.listOrEmpty(dependencies);
    if (dependencyRefs == null) {
      dependencyRefs = List.of();
    }
    dependencyRefs = ImmutableCollections.jsonListOrEmpty(dependencyRefs);
    if (evidenceRefs == null) {
      evidenceRefs = List.of();
    }
    evidenceRefs = ImmutableCollections.listOrEmpty(evidenceRefs);
    if (proofSteps == null) {
      proofSteps = List.of();
    }
    proofSteps = ImmutableCollections.listOrEmpty(proofSteps);
    if (scopeLimitations == null) {
      scopeLimitations = List.of();
    }
    scopeLimitations = ImmutableCollections.listOrEmpty(scopeLimitations);
    if (selfConfidence == null) {
      selfConfidence = 0.5d;
    }
    ContractValues.minimum("self_confidence", selfConfidence, 0.0);
    ContractValues.maximum("self_confidence", selfConfidence, 1.0);
    sourceAgentId = ContractStrings.trim(sourceAgentId);
    sourceAttemptId = ContractStrings.trim(sourceAttemptId);
    sourceDeltaId = ContractStrings.trim(sourceDeltaId);
    statement = ContractStrings.trim(statement);
    statement = ContractStrings.required("statement", statement);
    if (status == null) {
      status = ClaimStatus.PROPOSED;
    }
    if (tags == null) {
      tags = List.of();
    }
    tags = ImmutableCollections.listOrEmpty(tags);
    ContractValues.minimum("verification_confidence", verificationConfidence, 0.0);
    ContractValues.maximum("verification_confidence", verificationConfidence, 1.0);
    contentHash =
        ContractHashes.checked(
            "claim content_hash",
            contentHash,
            ContractHashes.claimHash(statement, assumptions, conclusion, dependencies));
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<String> assumptions() {
    return assumptions == null ? null : List.copyOf(assumptions);
  }

  public List<String> dependencies() {
    return dependencies == null ? null : List.copyOf(dependencies);
  }

  public List<JsonNode> dependencyRefs() {
    return dependencyRefs == null ? null : ImmutableCollections.copyJsonList(dependencyRefs);
  }

  public List<EvidenceRef> evidenceRefs() {
    return evidenceRefs == null ? null : List.copyOf(evidenceRefs);
  }

  public List<ProofStep> proofSteps() {
    return proofSteps == null ? null : List.copyOf(proofSteps);
  }

  public List<String> scopeLimitations() {
    return scopeLimitations == null ? null : List.copyOf(scopeLimitations);
  }

  public List<String> tags() {
    return tags == null ? null : List.copyOf(tags);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
