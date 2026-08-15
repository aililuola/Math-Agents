package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record ReviewedObstructionPayload(
    @JsonProperty(value = "exact_failed_proof_step_id", required = true) @ContractNonNull
        String exactFailedProofStepId,
    @JsonProperty(value = "failed_inference_statement", required = true) @ContractNonNull
        String failedInferenceStatement,
    @JsonProperty("retained_valid_claim_ids") @ContractNonNull List<String> retainedValidClaimIds,
    @JsonProperty(value = "issue_kind", required = true) @ContractNonNull String issueKind,
    @JsonProperty(value = "repairability", required = true) @ContractNonNull String repairability,
    @JsonProperty(value = "first_missing_justification", required = true) @ContractNonNull
        String firstMissingJustification,
    @JsonProperty(value = "next_exact_obligation_id", required = true) @ContractNonNull
        String nextExactObligationId,
    @JsonProperty("review_evidence_refs") @ContractNonNull List<String> reviewEvidenceRefs)
    implements BrokerArtifactPayload {
  public ReviewedObstructionPayload {
    exactFailedProofStepId = ContractStrings.required("exact_failed_proof_step_id", ContractStrings.trim(exactFailedProofStepId));
    failedInferenceStatement = ContractStrings.required("failed_inference_statement", ContractStrings.trim(failedInferenceStatement));
    retainedValidClaimIds = ImmutableCollections.listOrEmpty(retainedValidClaimIds);
    issueKind = ContractStrings.required("issue_kind", ContractStrings.trim(issueKind));
    repairability = ContractStrings.required("repairability", ContractStrings.trim(repairability));
    firstMissingJustification = ContractStrings.required("first_missing_justification", ContractStrings.trim(firstMissingJustification));
    nextExactObligationId = ContractStrings.required("next_exact_obligation_id", ContractStrings.trim(nextExactObligationId));
    reviewEvidenceRefs = ImmutableCollections.listOrEmpty(reviewEvidenceRefs);
  }
  @Override public List<String> retainedValidClaimIds() { return List.copyOf(retainedValidClaimIds); }
  @Override public List<String> reviewEvidenceRefs() { return List.copyOf(reviewEvidenceRefs); }
}
