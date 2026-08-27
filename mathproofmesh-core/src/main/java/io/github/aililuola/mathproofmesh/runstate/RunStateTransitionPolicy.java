package io.github.aililuola.mathproofmesh.runstate;

import java.util.Objects;

public final class RunStateTransitionPolicy {
  public void validate(RunStateSnapshot previous, RunStateSnapshot next) {
    Objects.requireNonNull(next, "next");
    if (previous == null) {
      return;
    }
    RunAuthoritySnapshot before = previous.authority();
    RunAuthoritySnapshot after = next.authority();
    if (!before.runId().equals(after.runId()) || !before.problemHash().equals(after.problemHash())) {
      throw new IllegalArgumentException("run identity is immutable");
    }
    if (after.authoritySequence() <= before.authoritySequence()
        || after.version() <= before.version()) {
      throw new IllegalArgumentException("authority sequence and version must increase");
    }
    if (mathRank(after.mathStatus()) < mathRank(before.mathStatus())
        && after.mathStatus() != RunMathematicalStatus.AUTHORITY_CONFLICT) {
      throw new IllegalArgumentException("mathematical status must not regress");
    }
    if (after.usage().providerCalls() < before.usage().providerCalls()
        || after.usage().totalTokens() < before.usage().totalTokens()
        || after.usage().estimatedCostUsd().compareTo(before.usage().estimatedCostUsd()) < 0) {
      throw new IllegalArgumentException("usage totals must not regress");
    }
    RunMathematicalProgressSnapshot prior = before.mathematicalProgress();
    RunMathematicalProgressSnapshot current = after.mathematicalProgress();
    if (current.verifiedLocalClaims() < prior.verifiedLocalClaims()
        || current.refutedClaims() < prior.refutedClaims()
        || !current.verifiedClaimIds().containsAll(prior.verifiedClaimIds())
        || !current.refutedClaimIds().containsAll(prior.refutedClaimIds())
        || (prior.finalProofPresent() && !current.finalProofPresent())
        || (prior.finalValidationPassed() && !current.finalValidationPassed())
        || (prior.finalReviewPassed() && !current.finalReviewPassed())
        || (!before.proofGraphHash().isEmpty() && after.proofGraphHash().isEmpty())) {
      throw new IllegalArgumentException("mathematical progress must not regress");
    }
    if (before.campaignStatus() == RunCampaignStatus.TERMINAL
        && after.campaignStatus() != RunCampaignStatus.TERMINAL
        && after.campaignStatus() != RunCampaignStatus.ARCHIVED) {
      throw new IllegalArgumentException("terminal campaign cannot reopen");
    }
  }

  static int mathRank(RunMathematicalStatus status) {
    return switch (status) {
      case NOT_STARTED -> 0;
      case PARTIAL_UNVERIFIED -> 1;
      case CANDIDATE_UNVERIFIED -> 2;
      case VERIFIED -> 3;
      case AUTHORITY_CONFLICT -> 4;
    };
  }
}
