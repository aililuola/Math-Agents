package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** A claim-scoped verdict; it never carries attempt- or route-level authority. */
public record ClaimReviewDecision(
    @JsonProperty(value = "claim_id", required = true) @ContractNonNull String claimId,
    @JsonProperty(value = "verdict", required = true) @ContractNonNull VerificationVerdict verdict,
    @JsonProperty(value = "confidence", required = true) @ContractNonNull Double confidence,
    @JsonProperty(value = "checked_dependencies") @ContractNonNull List<String> checkedDependencies,
    @JsonProperty(value = "problem_integrity_ok") @ContractNonNull Boolean problemIntegrityOk,
    @JsonProperty(value = "scope_valid") @ContractNonNull Boolean scopeValid,
    @JsonProperty(value = "quantifiers_valid") @ContractNonNull Boolean quantifiersValid,
    @JsonProperty(value = "evidence_type_valid") @ContractNonNull Boolean evidenceTypeValid,
    @JsonProperty(value = "witness_checked") @ContractNonNull Boolean witnessChecked,
    @JsonProperty(value = "issues") @ContractNonNull List<VerificationIssue> issues,
    @JsonProperty(value = "concise_feedback", required = true) @ContractNonNull String conciseFeedback)
    implements StrictContract {

  public ClaimReviewDecision {
    claimId = ContractStrings.required("claim_id", ContractStrings.trim(claimId));
    verdict = ContractValues.required("verdict", verdict);
    confidence = ContractValues.required("confidence", confidence);
    ContractValues.minimum("confidence", confidence, 0.0d);
    ContractValues.maximum("confidence", confidence, 1.0d);
    checkedDependencies = ImmutableCollections.listOrEmpty(checkedDependencies);
    problemIntegrityOk = problemIntegrityOk == null ? Boolean.FALSE : problemIntegrityOk;
    scopeValid = scopeValid == null ? Boolean.FALSE : scopeValid;
    quantifiersValid = quantifiersValid == null ? Boolean.FALSE : quantifiersValid;
    evidenceTypeValid = evidenceTypeValid == null ? Boolean.FALSE : evidenceTypeValid;
    witnessChecked = witnessChecked == null ? Boolean.FALSE : witnessChecked;
    issues = ImmutableCollections.listOrEmpty(issues);
    conciseFeedback =
        ContractStrings.required(
            "concise_feedback", ContractStrings.trim(conciseFeedback));
  }

  public boolean authorityDimensionsValid() {
    return problemIntegrityOk && scopeValid && quantifiersValid && evidenceTypeValid;
  }

  @Override
  public List<String> checkedDependencies() {
    return List.copyOf(checkedDependencies);
  }

  @Override
  public List<VerificationIssue> issues() {
    return List.copyOf(issues);
  }
}
