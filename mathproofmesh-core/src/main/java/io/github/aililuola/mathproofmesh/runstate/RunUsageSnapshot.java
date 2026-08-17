package io.github.aililuola.mathproofmesh.runstate;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

public record RunUsageSnapshot(
    long providerCalls,
    long inputTokens,
    long outputTokens,
    long totalTokens,
    BigDecimal estimatedCostUsd,
    double latencyMs,
    String requestIdSetHash,
    String sourceArtifactHash) {

  public RunUsageSnapshot {
    estimatedCostUsd = Objects.requireNonNull(estimatedCostUsd, "estimatedCostUsd");
    requestIdSetHash = RunStateHashes.optional(requestIdSetHash);
    sourceArtifactHash = RunStateHashes.optional(sourceArtifactHash);
    if (providerCalls < 0L
        || inputTokens < 0L
        || outputTokens < 0L
        || totalTokens < 0L
        || Math.addExact(inputTokens, outputTokens) != totalTokens
        || estimatedCostUsd.signum() < 0
        || !Double.isFinite(latencyMs)
        || latencyMs < 0.0d) {
      throw new IllegalArgumentException("usage counters must be finite, nonnegative, and consistent");
    }
  }

  public static RunUsageSnapshot empty() {
    return new RunUsageSnapshot(0L, 0L, 0L, 0L, BigDecimal.ZERO, 0.0d, "", "");
  }

  public static RunUsageSnapshot of(
      long providerCalls,
      long inputTokens,
      long outputTokens,
      BigDecimal estimatedCostUsd,
      double latencyMs,
      String requestIdSetHash,
      String sourceArtifactHash) {
    return new RunUsageSnapshot(
        providerCalls,
        inputTokens,
        outputTokens,
        Math.addExact(inputTokens, outputTokens),
        estimatedCostUsd,
        latencyMs,
        requestIdSetHash,
        sourceArtifactHash);
  }

  Map<String, Object> identityPayload() {
    return Map.of(
        "providerCalls", providerCalls,
        "inputTokens", inputTokens,
        "outputTokens", outputTokens,
        "totalTokens", totalTokens,
        "estimatedCostUsd", estimatedCostUsd,
        "latencyMs", latencyMs,
        "requestIdSetHash", requestIdSetHash,
        "sourceArtifactHash", sourceArtifactHash);
  }
}
