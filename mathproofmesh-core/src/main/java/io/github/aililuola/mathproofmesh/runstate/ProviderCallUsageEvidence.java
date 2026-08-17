package io.github.aililuola.mathproofmesh.runstate;

import java.math.BigDecimal;
import java.util.Objects;

public record ProviderCallUsageEvidence(
    String providerRequestId,
    long inputTokens,
    long outputTokens,
    BigDecimal estimatedCostUsd,
    double latencyMs,
    String sourceArtifactHash) {
  public ProviderCallUsageEvidence {
    providerRequestId = RunStateHashes.required(providerRequestId, "providerRequestId");
    estimatedCostUsd = Objects.requireNonNull(estimatedCostUsd, "estimatedCostUsd");
    sourceArtifactHash = RunStateHashes.optional(sourceArtifactHash);
    if (inputTokens < 0L
        || outputTokens < 0L
        || estimatedCostUsd.signum() < 0
        || !Double.isFinite(latencyMs)
        || latencyMs < 0.0d) {
      throw new IllegalArgumentException("provider usage counters must be finite and nonnegative");
    }
  }

  public long totalTokens() {
    return Math.addExact(inputTokens, outputTokens);
  }
}
