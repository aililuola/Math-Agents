package io.github.aililuola.mathproofmesh.orchestration;

/** Observable evidence used by the adaptive scheduler. */
public record AttemptEvidence(
    String routeId,
    boolean verifiedProgress,
    FailureClass failureClass,
    double proofDebt,
    double risk,
    int calls,
    boolean complete) {
  public AttemptEvidence {
    routeId = required(routeId, "routeId");
    failureClass = failureClass == null ? FailureClass.NONE : failureClass;
    if (!Double.isFinite(proofDebt)
        || proofDebt < 0
        || !Double.isFinite(risk)
        || risk < 0
        || risk > 1
        || calls < 0) {
      throw new IllegalArgumentException("invalid attempt evidence");
    }
  }

  public enum FailureClass {
    NONE,
    STRATEGY,
    STRUCTURAL,
    EXECUTION,
    PROBLEM_INTEGRITY
  }

  private static String required(String value, String field) {
    String result = value == null ? "" : value.strip();
    if (result.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return result;
  }
}
