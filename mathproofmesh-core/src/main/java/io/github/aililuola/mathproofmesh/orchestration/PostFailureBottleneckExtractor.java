package io.github.aililuola.mathproofmesh.orchestration;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One-shot bottleneck extraction from committed public state, never hidden reasoning. */
public final class PostFailureBottleneckExtractor {
  private final Map<String, BottleneckExtractionResult> completed = new LinkedHashMap<>();

  public synchronized BottleneckExtractionResult extract(
      String failureType,
      ContinuationFunctions.Checkpoint checkpoint,
      List<String> verifiedStepIds,
      List<String> openObligationIds,
      boolean recoveryCapacityAvailable) {
    java.util.Objects.requireNonNull(checkpoint, "checkpoint");
    String failure = normalizeFailure(failureType);
    if (!checkpoint.committed()) {
      throw new IllegalArgumentException("diagnosis requires a committed checkpoint");
    }
    if (!recoveryCapacityAvailable) {
      return null;
    }
    String key =
        CanonicalJson.stableHash(
            List.of(
                checkpoint.problemHash(),
                checkpoint.pathId(),
                checkpoint.strategyId(),
                checkpoint.checkpointId()));
    BottleneckExtractionResult prior = completed.get(key);
    if (prior != null) {
      return new BottleneckExtractionResult(
          prior.diagnosticId(),
          prior.checkpointId(),
          prior.failureType(),
          prior.preservedVerifiedStepIds(),
          prior.relatedObligationIds(),
          false,
          true);
    }
    BottleneckExtractionResult result =
        new BottleneckExtractionResult(
            "bottleneck_" + key.substring(0, 12),
            checkpoint.checkpointId(),
            failure,
            verifiedStepIds,
            openObligationIds,
            false,
            false);
    completed.put(key, result);
    return result;
  }

  private static String normalizeFailure(String value) {
    String failure = value == null ? "" : value.strip();
    if (!failure.equals("reasoning_budget_exhausted")
        && !failure.equals("reasoning_only_stall")) {
      throw new IllegalArgumentException("failure did not prove that no artifact was produced");
    }
    return failure;
  }
}
