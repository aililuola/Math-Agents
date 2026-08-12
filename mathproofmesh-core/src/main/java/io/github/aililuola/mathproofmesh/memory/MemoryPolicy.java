package io.github.aililuola.mathproofmesh.memory;

public record MemoryPolicy(
    double factPassThreshold,
    int maxFactContext,
    int maxInsightContext,
    int maxNegativeContext) {

  public MemoryPolicy {
    if (factPassThreshold < 0.0 || factPassThreshold > 1.0) {
      throw new IllegalArgumentException("factPassThreshold must be in [0, 1]");
    }
    if (maxFactContext < 1 || maxInsightContext < 1 || maxNegativeContext < 1) {
      throw new IllegalArgumentException("memory context limits must be positive");
    }
  }

  public static MemoryPolicy defaults() {
    return new MemoryPolicy(0.8, 32, 16, 16);
  }
}
