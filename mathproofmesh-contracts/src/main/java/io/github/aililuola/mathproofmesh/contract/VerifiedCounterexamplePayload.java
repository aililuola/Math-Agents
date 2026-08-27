package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record VerifiedCounterexamplePayload(
    @JsonProperty(value = "target_claim", required = true) @ContractNonNull
        BrokerClaimSemanticContext targetClaim,
    @JsonProperty(value = "exact_target_claim_id", required = true) @ContractNonNull
        String exactTargetClaimId,
    @JsonProperty(value = "target_semantic_hash", required = true) @ContractNonNull
        String targetSemanticHash,
    @JsonProperty(value = "witness", required = true) @ContractNonNull String witness,
    @JsonProperty("witness_evidence_refs") @ContractNonNull List<String> witnessEvidenceRefs,
    @JsonProperty("affected_exact_obligation_ids") @ContractNonNull
        List<String> affectedExactObligationIds) implements BrokerArtifactPayload {
  public VerifiedCounterexamplePayload {
    targetClaim = ContractValues.required("target_claim", targetClaim);
    exactTargetClaimId = ContractStrings.required("exact_target_claim_id", ContractStrings.trim(exactTargetClaimId));
    targetSemanticHash = ContractStrings.required("target_semantic_hash", ContractStrings.trim(targetSemanticHash));
    witness = ContractStrings.required("witness", ContractStrings.trim(witness));
    witnessEvidenceRefs = ImmutableCollections.listOrEmpty(witnessEvidenceRefs);
    affectedExactObligationIds = ImmutableCollections.listOrEmpty(affectedExactObligationIds);
  }
  @Override public List<String> witnessEvidenceRefs() { return List.copyOf(witnessEvidenceRefs); }
  @Override public List<String> affectedExactObligationIds() { return List.copyOf(affectedExactObligationIds); }
}
