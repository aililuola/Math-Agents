package io.github.aililuola.mathproofmesh.sidecar;

import java.time.Duration;

/** Bounds applied independently by the Java adapter and the Python sidecar. */
public record SidecarLimits(
    int maxCases, long seed, Duration timeout, int maxOutputBytes) {

  public SidecarLimits {
    if (maxCases < 1 || maxCases > 100_000_000) {
      throw new IllegalArgumentException("maxCases is outside the protocol range");
    }
    if (timeout == null
        || timeout.isZero()
        || timeout.isNegative()
        || timeout.compareTo(Duration.ofSeconds(60)) > 0) {
      throw new IllegalArgumentException("timeout must be in (0, 60s]");
    }
    if (maxOutputBytes < 256 || maxOutputBytes > 2_000_000) {
      throw new IllegalArgumentException("maxOutputBytes is outside the protocol range");
    }
  }
}
