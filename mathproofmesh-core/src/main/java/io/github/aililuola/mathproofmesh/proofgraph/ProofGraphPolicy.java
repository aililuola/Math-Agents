package io.github.aililuola.mathproofmesh.proofgraph;

public record ProofGraphPolicy(
    int maxNodes,
    int maxEdges,
    double closeObligationThreshold,
    double bridgeSimilarityThreshold,
    int sharedBottleneckMinRoutes,
    double obligationBaseWeight,
    double obligationMainGoalWeight,
    double obligationCentralityWeight,
    double obligationDependencyWeight,
    double obligationSharedRouteWeight,
    double obligationFailureWeight,
    double obligationConflictWeight) {

  public ProofGraphPolicy {
    if (maxNodes < 1 || maxEdges < 1) {
      throw new IllegalArgumentException("proof graph limits must be positive");
    }
    if (closeObligationThreshold < 0.0
        || closeObligationThreshold > 1.0
        || bridgeSimilarityThreshold < 0.0
        || bridgeSimilarityThreshold > 1.0) {
      throw new IllegalArgumentException("proof graph thresholds must be in [0, 1]");
    }
    if (sharedBottleneckMinRoutes < 2) {
      throw new IllegalArgumentException("sharedBottleneckMinRoutes must be at least 2");
    }
  }

  public static ProofGraphPolicy defaults() {
    return new ProofGraphPolicy(
        5000,
        20000,
        0.8,
        0.78,
        2,
        1.0,
        2.0,
        1.0,
        0.5,
        0.25,
        1.0,
        2.0);
  }
}
