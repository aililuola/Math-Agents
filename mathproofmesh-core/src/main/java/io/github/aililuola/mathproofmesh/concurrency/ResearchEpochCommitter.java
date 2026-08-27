package io.github.aililuola.mathproofmesh.concurrency;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Single-writer boundary for deterministic epoch result application. */
@SuppressFBWarnings(
    value = "THROWS_METHOD_THROWS_RUNTIMEEXCEPTION",
    justification =
        "The transaction restores its authority snapshot and deliberately preserves the original"
            + " validation or mutation exception.")
public final class ResearchEpochCommitter {
  public synchronized <S> ResearchEpochCommitResult commit(
      FrozenResearchSnapshot frozen,
      ResearchMergePlan plan,
      Supplier<ResearchAuthorityAnchor> currentAuthority,
      ResearchAuthorityMutationTransaction<S> authorityMutation) {
    Objects.requireNonNull(frozen, "frozen");
    Objects.requireNonNull(plan, "plan");
    Objects.requireNonNull(currentAuthority, "currentAuthority");
    Objects.requireNonNull(authorityMutation, "authorityMutation");
    ResearchAuthorityAnchor current =
        Objects.requireNonNull(currentAuthority.get(), "current authority");
    if (!current.stableHash().equals(frozen.authority().stableHash())) {
      throw new IllegalStateException("STALE_SNAPSHOT");
    }
    List<String> accepted = plan.acceptedResultHashes();
    S snapshot =
        Objects.requireNonNull(authorityMutation.snapshot(), "authority mutation snapshot");
    try {
      ResearchAuthorityMutationReceipt mutation =
          Objects.requireNonNull(authorityMutation.apply(accepted), "authority mutation receipt");
      validateMutationReceipt(frozen, plan, accepted, mutation);
      List<String> rejected =
          plan.decisions().stream()
              .filter(decision -> !decision.accepted())
              .map(ResearchMergeDecision::resultHash)
              .toList();
      ResearchMergeReceipt merge =
          new ResearchMergeReceipt(
              plan.epochId(),
              plan.mergePlanHash(),
              accepted,
              rejected,
              mutation.authorityHashAfter());
      return new ResearchEpochCommitResult(mutation, merge);
    } catch (RuntimeException exception) {
      try {
        authorityMutation.restore(snapshot);
      } catch (RuntimeException restoreFailure) {
        exception.addSuppressed(restoreFailure);
      }
      throw exception;
    }
  }

  private static void validateMutationReceipt(
      FrozenResearchSnapshot frozen,
      ResearchMergePlan plan,
      List<String> accepted,
      ResearchAuthorityMutationReceipt receipt) {
    if (!receipt.epochId().equals(plan.epochId())
        || !receipt.mergePlanHash().equals(plan.mergePlanHash())
        || !receipt.authorityHashBefore().equals(frozen.authority().stableHash())
        || !receipt.acceptedResultHashes().equals(accepted)) {
      throw new IllegalArgumentException("authority mutation receipt does not bind the merge plan");
    }
  }
}
