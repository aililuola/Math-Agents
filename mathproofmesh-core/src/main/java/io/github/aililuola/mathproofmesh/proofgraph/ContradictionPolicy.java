package io.github.aililuola.mathproofmesh.proofgraph;

public record ContradictionPolicy(boolean enabled, int maxTasksPerRound) {
  public ContradictionPolicy {
    if (maxTasksPerRound < 0) {
      throw new IllegalArgumentException("maxTasksPerRound must be non-negative");
    }
  }

  public static ContradictionPolicy defaults() {
    return new ContradictionPolicy(true, 2);
  }
}
