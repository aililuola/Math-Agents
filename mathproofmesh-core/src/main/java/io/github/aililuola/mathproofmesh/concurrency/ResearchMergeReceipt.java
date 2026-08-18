package io.github.aililuola.mathproofmesh.concurrency;

import java.util.List;
import java.util.Objects;

public record ResearchMergeReceipt(
    String epochId,
    String mergePlanHash,
    List<String> acceptedResultHashes,
    List<String> rejectedResultHashes,
    String authorityHashAfterCommit) {
  public ResearchMergeReceipt {
    epochId = Objects.requireNonNull(epochId, "epochId").strip();
    mergePlanHash = Objects.requireNonNull(mergePlanHash, "mergePlanHash").strip();
    acceptedResultHashes = acceptedResultHashes == null ? List.of() : List.copyOf(acceptedResultHashes);
    rejectedResultHashes = rejectedResultHashes == null ? List.of() : List.copyOf(rejectedResultHashes);
    authorityHashAfterCommit =
        Objects.requireNonNull(authorityHashAfterCommit, "authorityHashAfterCommit").strip();
  }
}
