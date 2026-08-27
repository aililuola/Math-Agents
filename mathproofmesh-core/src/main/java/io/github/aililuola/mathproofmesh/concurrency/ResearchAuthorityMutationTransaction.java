package io.github.aililuola.mathproofmesh.concurrency;

import java.util.List;

/** Rollback-capable single-writer transaction used by an epoch authority commit. */
public interface ResearchAuthorityMutationTransaction<S> {
  S snapshot();

  ResearchAuthorityMutationReceipt apply(List<String> acceptedResultHashes);

  void restore(S snapshot);
}
