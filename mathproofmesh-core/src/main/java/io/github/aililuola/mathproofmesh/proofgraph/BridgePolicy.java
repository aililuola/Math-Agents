package io.github.aililuola.mathproofmesh.proofgraph;

public record BridgePolicy(
    boolean enabled, int maxTasksPerRound, int minRoutes) {

  public BridgePolicy {
    if (maxTasksPerRound < 0) {
      throw new IllegalArgumentException("maxTasksPerRound must be non-negative");
    }
    if (minRoutes < 2) {
      throw new IllegalArgumentException("minRoutes must be at least 2");
    }
  }

  public static BridgePolicy defaults() {
    return new BridgePolicy(true, 2, 2);
  }
}
