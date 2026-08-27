package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record ClaimProofAuditDecision(
    @JsonProperty(value = "claim_id", required = true) @ContractNonNull String claimId,
    @JsonProperty(value = "verdict", required = true) @ContractNonNull
        ClaimProofAuditVerdict verdict,
    @JsonProperty(value = "issues") @ContractNonNull List<ProofAuditIssue> issues,
    @JsonProperty(value = "concise_feedback", required = true) @ContractNonNull
        String conciseFeedback)
    implements StrictContract {
  public ClaimProofAuditDecision {
    claimId = ContractStrings.required("claim_id", ContractStrings.trim(claimId));
    verdict = ContractValues.required("verdict", verdict);
    issues = ImmutableCollections.listOrEmpty(issues);
    conciseFeedback =
        ContractStrings.required("concise_feedback", ContractStrings.trim(conciseFeedback));
    for (ProofAuditIssue issue : issues) {
      if (!issue.claimId().equals(claimId)) {
        throw new ContractValidationException("proof audit issue targets another claim");
      }
    }
    if ((verdict == ClaimProofAuditVerdict.INVALID_REPAIRABLE
            || verdict == ClaimProofAuditVerdict.INVALID_UNREPAIRABLE)
        && issues.isEmpty()) {
      throw new ContractValidationException("invalid proof audit verdict requires issues");
    }
  }

  @Override
  public List<ProofAuditIssue> issues() {
    return List.copyOf(issues);
  }
}
