package io.github.aililuola.mathproofmesh.computation;

import io.github.aililuola.mathproofmesh.contract.ExperimentOutcome;
import io.github.aililuola.mathproofmesh.contract.ExperimentResult;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Thread-safe cache used by local runs and deterministic tests. */
public final class InMemoryComputationCache implements ComputationCache {
  private final ConcurrentMap<Key, ExperimentResult> values = new ConcurrentHashMap<>();
  private final ConcurrentMap<ComputationCacheKey, CanonicalComputationCacheEntry> canonical =
      new ConcurrentHashMap<>();

  @Override
  public Optional<CanonicalComputationCacheEntry> find(ComputationCacheKey key) {
    return Optional.ofNullable(canonical.get(key));
  }

  @Override
  public void put(ComputationCacheKey key, CanonicalComputationCacheEntry entry) {
    if (entry.result().outcome() != ExperimentOutcome.ERROR
        && entry.result().outcome() != ExperimentOutcome.INCONCLUSIVE) {
      canonical.putIfAbsent(key, entry);
    }
  }

  @Override
  public Optional<ExperimentResult> find(
      String runId, String executionHash, String toolIdentity) {
    return Optional.ofNullable(values.get(new Key(runId, executionHash, toolIdentity)));
  }

  @Override
  public void put(
      String runId,
      String executionHash,
      String toolIdentity,
      ExperimentResult result) {
    if (result.outcome() != ExperimentOutcome.ERROR
        && result.outcome() != ExperimentOutcome.INCONCLUSIVE) {
      values.putIfAbsent(new Key(runId, executionHash, toolIdentity), result);
    }
  }

  public int size() {
    return Math.max(values.size(), canonical.size());
  }

  private record Key(String runId, String executionHash, String toolIdentity) {
    private Key {
      if (runId == null
          || runId.isBlank()
          || executionHash == null
          || executionHash.isBlank()
          || toolIdentity == null
          || toolIdentity.isBlank()) {
        throw new IllegalArgumentException("cache key fields are required");
      }
    }
  }
}
