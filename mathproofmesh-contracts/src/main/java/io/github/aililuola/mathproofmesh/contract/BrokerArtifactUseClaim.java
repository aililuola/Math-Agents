package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record BrokerArtifactUseClaim(
    @JsonProperty(value = "artifact_id", required = true) @ContractNonNull String artifactId,
    @JsonProperty(value = "use_kind", required = true) @ContractNonNull BrokerArtifactUseKind useKind,
    @JsonProperty("referenced_proof_step_ids") @ContractNonNull List<String> referencedProofStepIds,
    @JsonProperty("affected_claim_ids") @ContractNonNull List<String> affectedClaimIds,
    @JsonProperty("affected_obligation_ids") @ContractNonNull List<String> affectedObligationIds,
    @JsonProperty("target_semantic_hash") String targetSemanticHash,
    @JsonProperty(value = "exact_use_summary", required = true) @ContractNonNull String exactUseSummary) {
  public BrokerArtifactUseClaim(
      String artifactId,
      BrokerArtifactUseKind useKind,
      List<String> referencedProofStepIds,
      List<String> affectedClaimIds,
      List<String> affectedObligationIds,
      String exactUseSummary) {
    this(
        artifactId,
        useKind,
        referencedProofStepIds,
        affectedClaimIds,
        affectedObligationIds,
        null,
        exactUseSummary);
  }

  public BrokerArtifactUseClaim {
    artifactId = ContractStrings.required("artifact_id", ContractStrings.trim(artifactId));
    useKind = ContractValues.required("use_kind", useKind);
    referencedProofStepIds = ImmutableCollections.listOrEmpty(referencedProofStepIds);
    affectedClaimIds = ImmutableCollections.listOrEmpty(affectedClaimIds);
    affectedObligationIds = ImmutableCollections.listOrEmpty(affectedObligationIds);
    targetSemanticHash = ContractStrings.trim(targetSemanticHash);
    exactUseSummary = ContractStrings.required("exact_use_summary", ContractStrings.trim(exactUseSummary));
  }
  @Override public List<String> referencedProofStepIds() { return List.copyOf(referencedProofStepIds); }
  @Override public List<String> affectedClaimIds() { return List.copyOf(affectedClaimIds); }
  @Override public List<String> affectedObligationIds() { return List.copyOf(affectedObligationIds); }
}
