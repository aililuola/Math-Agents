package io.github.aililuola.mathproofmesh.concurrency;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/** Single-writer boundary for deterministic epoch result application. */
public final class ResearchEpochCommitter {
  public synchronized ResearchMergeReceipt commit(
      FrozenResearchSnapshot frozen,
      ResearchMergePlan plan,
      Supplier<ResearchAuthorityAnchor> currentAuthority,
      Function<List<String>, String> authorityMutation) {
    Objects.requireNonNull(frozen, "frozen");
    Objects.requireNonNull(plan, "plan");
    ResearchAuthorityAnchor current = currentAuthority.get();
    if (!current.stableHash().equals(frozen.authority().stableHash())) {
      throw new IllegalStateException("STALE_SNAPSHOT");
    }
    List<String> accepted = plan.acceptedResultHashes();
    String authorityAfter = authorityMutation.apply(accepted);
    List<String> rejected =
        plan.decisions().stream()
            .filter(decision -> !decision.accepted())
            .map(ResearchMergeDecision::resultHash)
            .toList();
    return new ResearchMergeReceipt(
        plan.epochId(), plan.mergePlanHash(), accepted, rejected, authorityAfter);
  }
}
