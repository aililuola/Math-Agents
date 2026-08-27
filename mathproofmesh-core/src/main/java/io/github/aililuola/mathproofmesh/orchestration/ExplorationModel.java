package io.github.aililuola.mathproofmesh.orchestration;

/** Fixed recovery tiers; elapsed wall time never selects a mathematical tier. */
public enum ExplorationModel {
  BOUNDED_REPAIR(64_000, 1),
  DEEP_96K(96_000, 2),
  DEEP_128K(128_000, 3);

  private final int outputTokens;
  private final int recoveryCalls;

  ExplorationModel(int outputTokens, int recoveryCalls) {
    this.outputTokens = outputTokens;
    this.recoveryCalls = recoveryCalls;
  }

  public int outputTokens() {
    return outputTokens;
  }

  public int recoveryCalls() {
    return recoveryCalls;
  }
}
