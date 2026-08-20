package io.github.aililuola.mathproofmesh.concurrency;

import java.util.Objects;

public record ResearchEpochCommitResult(
    ResearchAuthorityMutationReceipt authorityMutation, ResearchMergeReceipt mergeReceipt) {
  public ResearchEpochCommitResult {
    Objects.requireNonNull(authorityMutation, "authorityMutation");
    Objects.requireNonNull(mergeReceipt, "mergeReceipt");
    if (!authorityMutation.epochId().equals(mergeReceipt.epochId())
        || !authorityMutation.mergePlanHash().equals(mergeReceipt.mergePlanHash())
        || !authorityMutation.acceptedResultHashes().equals(mergeReceipt.acceptedResultHashes())
        || !authorityMutation
            .authorityHashAfter()
            .equals(mergeReceipt.authorityHashAfterCommit())) {
      throw new IllegalArgumentException("epoch commit receipts describe different mutations");
    }
  }
}
