package io.github.aililuola.mathproofmesh.computation;

/** Capability-owned resource ceiling, independent from model-token budgets. */
public record ComputationResourceEnvelope(
    int maxCases, double maxCpuSeconds, long maxMemoryBytes, int maxOutputChars) {
  public ComputationResourceEnvelope {
    if (maxCases < 1
        || !Double.isFinite(maxCpuSeconds)
        || maxCpuSeconds <= 0.0d
        || maxMemoryBytes < 1L
        || maxOutputChars < 256) {
      throw new IllegalArgumentException("invalid computation resource envelope");
    }
  }

  public static ComputationResourceEnvelope boundedDefault() {
    return new ComputationResourceEnvelope(1_000_000, 30.0d, 256L * 1024L * 1024L, 20_000);
  }
}
