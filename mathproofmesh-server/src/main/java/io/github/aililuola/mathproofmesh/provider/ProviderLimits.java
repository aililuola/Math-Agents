package io.github.aililuola.mathproofmesh.provider;

import java.time.Duration;
import java.util.Objects;

public record ProviderLimits(
    long maxResponseBytes,
    Duration firstChunkTimeout,
    Duration idleTimeout) {

  public ProviderLimits {
    if (maxResponseBytes < 1) {
      throw new IllegalArgumentException("maxResponseBytes must be positive");
    }
    firstChunkTimeout = requirePositive(firstChunkTimeout, "firstChunkTimeout");
    idleTimeout = requirePositive(idleTimeout, "idleTimeout");
  }

  public static ProviderLimits defaults() {
    return new ProviderLimits(
        16L * 1024 * 1024, Duration.ofSeconds(90), Duration.ofMinutes(5));
  }

  private static Duration requirePositive(Duration value, String label) {
    Objects.requireNonNull(value, label);
    if (value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(label + " must be positive");
    }
    return value;
  }
}
