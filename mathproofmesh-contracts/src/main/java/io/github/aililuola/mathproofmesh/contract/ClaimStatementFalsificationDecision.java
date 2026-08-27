package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record ClaimStatementFalsificationDecision(
    @JsonProperty(value = "claim_id", required = true) @ContractNonNull String claimId,
    @JsonProperty(value = "disposition", required = true) @ContractNonNull
        StatementFalsificationDisposition disposition,
    @JsonProperty(value = "counterexample_candidates") @ContractNonNull
        List<StatementCounterexampleCandidate> counterexampleCandidates,
    @JsonProperty(value = "concise_feedback", required = true) @ContractNonNull
        String conciseFeedback)
    implements StrictContract {
  public ClaimStatementFalsificationDecision {
    claimId = ContractStrings.required("claim_id", ContractStrings.trim(claimId));
    disposition = ContractValues.required("disposition", disposition);
    counterexampleCandidates = ImmutableCollections.listOrEmpty(counterexampleCandidates);
    conciseFeedback =
        ContractStrings.required("concise_feedback", ContractStrings.trim(conciseFeedback));
    if (disposition == StatementFalsificationDisposition.COUNTEREXAMPLE_CANDIDATE
        && counterexampleCandidates.isEmpty()) {
      throw new ContractValidationException(
          "counterexample disposition requires at least one candidate");
    }
    for (StatementCounterexampleCandidate candidate : counterexampleCandidates) {
      if (!candidate.claimId().equals(claimId)) {
        throw new ContractValidationException("counterexample candidate targets another claim");
      }
    }
  }

  @Override
  public List<StatementCounterexampleCandidate> counterexampleCandidates() {
    return List.copyOf(counterexampleCandidates);
  }
}
