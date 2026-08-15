package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ClaimCounterexampleWitnessReviewDecision(
    @JsonProperty(value = "candidate_id", required = true) @ContractNonNull String candidateId,
    @JsonProperty(value = "claim_id", required = true) @ContractNonNull String claimId,
    @JsonProperty(value = "statement_hash", required = true) @ContractNonNull String statementHash,
    @JsonProperty(value = "verdict", required = true) @ContractNonNull VerificationVerdict verdict,
    @JsonProperty(value = "witness_valid") @ContractNonNull Boolean witnessValid,
    @JsonProperty(value = "exact_target") @ContractNonNull Boolean exactTarget,
    @JsonProperty(value = "assumptions_match") @ContractNonNull Boolean assumptionsMatch,
    @JsonProperty(value = "quantifiers_match") @ContractNonNull Boolean quantifiersMatch,
    @JsonProperty(value = "scope_match") @ContractNonNull Boolean scopeMatch,
    @JsonProperty(value = "polarity_match") @ContractNonNull Boolean polarityMatch,
    @JsonProperty(value = "concise_feedback", required = true) @ContractNonNull
        String conciseFeedback)
    implements StrictContract {
  public ClaimCounterexampleWitnessReviewDecision {
    candidateId = ContractStrings.required("candidate_id", ContractStrings.trim(candidateId));
    claimId = ContractStrings.required("claim_id", ContractStrings.trim(claimId));
    statementHash =
        ContractStrings.required("statement_hash", ContractStrings.trim(statementHash));
    verdict = ContractValues.required("verdict", verdict);
    witnessValid = Boolean.TRUE.equals(witnessValid);
    exactTarget = Boolean.TRUE.equals(exactTarget);
    assumptionsMatch = Boolean.TRUE.equals(assumptionsMatch);
    quantifiersMatch = Boolean.TRUE.equals(quantifiersMatch);
    scopeMatch = Boolean.TRUE.equals(scopeMatch);
    polarityMatch = Boolean.TRUE.equals(polarityMatch);
    conciseFeedback =
        ContractStrings.required("concise_feedback", ContractStrings.trim(conciseFeedback));
  }

  public boolean exactWitnessAccepted() {
    return verdict == VerificationVerdict.PASS
        && witnessValid
        && exactTarget
        && assumptionsMatch
        && quantifiersMatch
        && scopeMatch
        && polarityMatch;
  }
}
