package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record ClaimBlindAdjudicationDecision(
    @JsonProperty(value = "claim_id", required = true) @ContractNonNull String claimId,
    @JsonProperty(value = "verdict", required = true) @ContractNonNull
        ClaimBlindAdjudicationVerdict verdict,
    @JsonProperty(value = "counterexample_candidates") @ContractNonNull
        List<StatementCounterexampleCandidate> counterexampleCandidates,
    @JsonProperty(value = "concise_feedback", required = true) @ContractNonNull
        String conciseFeedback)
    implements StrictContract {
  public ClaimBlindAdjudicationDecision {
    claimId = ContractStrings.required("claim_id", ContractStrings.trim(claimId));
    verdict = ContractValues.required("verdict", verdict);
    counterexampleCandidates = ImmutableCollections.listOrEmpty(counterexampleCandidates);
    conciseFeedback =
        ContractStrings.required("concise_feedback", ContractStrings.trim(conciseFeedback));
    for (StatementCounterexampleCandidate candidate : counterexampleCandidates) {
      if (!candidate.claimId().equals(claimId)) {
        throw new ContractValidationException("blind counterexample targets another claim");
      }
    }
    if (verdict == ClaimBlindAdjudicationVerdict.COUNTEREXAMPLE_CANDIDATE
        && counterexampleCandidates.isEmpty()) {
      throw new ContractValidationException(
          "blind counterexample verdict requires at least one candidate");
    }
  }

  @Override
  public List<StatementCounterexampleCandidate> counterexampleCandidates() {
    return List.copyOf(counterexampleCandidates);
  }
}
