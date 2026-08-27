package io.github.aililuola.mathproofmesh.computation;

/** Capability-owned resource ceiling, independent from model-token budgets. */
public record ComputationResourceEnvelope(
    int maxCases,
    double maxCpuSeconds,
    long maxMemoryBytes,
    int maxOutputChars,
    int maxMatrixRows,
    int maxMatrixColumns,
    int maxRationalBitLength,
    int maxFiniteSetSize,
    int maxHypergraphVertices,
    int maxCertificateNodes,
    int maxResultChars) {
  public ComputationResourceEnvelope {
    maxMatrixRows = positiveOrDefault(maxMatrixRows, 64);
    maxMatrixColumns = positiveOrDefault(maxMatrixColumns, 64);
    maxRationalBitLength = positiveOrDefault(maxRationalBitLength, 4_096);
    maxFiniteSetSize = positiveOrDefault(maxFiniteSetSize, 10_000);
    maxHypergraphVertices = positiveOrDefault(maxHypergraphVertices, 24);
    maxCertificateNodes = positiveOrDefault(maxCertificateNodes, 100_000);
    maxResultChars = positiveOrDefault(maxResultChars, maxOutputChars);
    if (maxCases < 1
        || !Double.isFinite(maxCpuSeconds)
        || maxCpuSeconds <= 0.0d
        || maxMemoryBytes < 1L
        || maxOutputChars < 256
        || maxMatrixRows < 1
        || maxMatrixColumns < 1
        || maxRationalBitLength < 1
        || maxFiniteSetSize < 1
        || maxHypergraphVertices < 1
        || maxCertificateNodes < 1
        || maxResultChars < 256
        || maxResultChars > maxOutputChars) {
      throw new IllegalArgumentException("invalid computation resource envelope");
    }
  }

  public ComputationResourceEnvelope(
      int maxCases, double maxCpuSeconds, long maxMemoryBytes, int maxOutputChars) {
    this(
        maxCases,
        maxCpuSeconds,
        maxMemoryBytes,
        maxOutputChars,
        64,
        64,
        4_096,
        10_000,
        24,
        100_000,
        maxOutputChars);
  }

  public static ComputationResourceEnvelope boundedDefault() {
    return new ComputationResourceEnvelope(
        1_000_000,
        30.0d,
        256L * 1024L * 1024L,
        20_000,
        64,
        64,
        4_096,
        10_000,
        24,
        100_000,
        20_000);
  }

  private static int positiveOrDefault(int value, int fallback) {
    return value == 0 ? fallback : value;
  }
}
