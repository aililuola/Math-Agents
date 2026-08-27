package io.github.aililuola.mathproofmesh.computation;

import io.github.aililuola.mathproofmesh.contract.ExperimentResult;
import java.util.Optional;

/** Run-scoped canonical computation-result cache. */
public interface ComputationCache {
  Optional<CanonicalComputationCacheEntry> find(ComputationCacheKey key);

  void put(ComputationCacheKey key, CanonicalComputationCacheEntry entry);

  Optional<ExperimentResult> find(
      String runId, String executionHash, String toolIdentity);

  void put(
      String runId,
      String executionHash,
      String toolIdentity,
      ExperimentResult result);
}
