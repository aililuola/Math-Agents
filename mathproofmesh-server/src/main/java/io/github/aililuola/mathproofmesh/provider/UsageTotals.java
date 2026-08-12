package io.github.aililuola.mathproofmesh.provider;

import java.math.BigDecimal;
import java.util.Objects;

public record UsageTotals(
    long calls,
    long inputTokens,
    long outputTokens,
    BigDecimal costUsd,
    double latencyMs) {

  public UsageTotals {
    if (calls < 0 || inputTokens < 0 || outputTokens < 0) {
      throw new IllegalArgumentException("usage counters must not be negative");
    }
    costUsd = Objects.requireNonNull(costUsd, "costUsd");
    if (costUsd.signum() < 0) {
      throw new IllegalArgumentException("costUsd must not be negative");
    }
    if (!Double.isFinite(latencyMs) || latencyMs < 0.0d) {
      throw new IllegalArgumentException("latencyMs must be finite and non-negative");
    }
  }

  public static UsageTotals zero() {
    return new UsageTotals(0L, 0L, 0L, BigDecimal.ZERO, 0.0d);
  }

  public long totalTokens() {
    return Math.addExact(inputTokens, outputTokens);
  }

  public UsageTotals plus(UsageTotals other) {
    Objects.requireNonNull(other, "other");
    return new UsageTotals(
        Math.addExact(calls, other.calls),
        Math.addExact(inputTokens, other.inputTokens),
        Math.addExact(outputTokens, other.outputTokens),
        costUsd.add(other.costUsd),
        latencyMs + other.latencyMs);
  }
}
