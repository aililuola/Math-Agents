package io.github.aililuola.mathproofmesh.computation;

/** Stable prompt fragments for declaring typed checks in the existing model call. */
public final class ComputationPromptInstructions {
  private ComputationPromptInstructions() {}

  public static String strategyInstructions() {
    return """
        REGISTERED TYPED CALCULATION CONTRACTS
        Put route-critical finite checks in strategy.calculation_checks.
        Declaring a check adds no model call; the server executes it deterministically.
        """;
  }

  public static String explorationInstructions() {
    return """
        REGISTERED TYPED COMPUTATION CONTRACTS
        Put each route-critical numerical premise in ProofStep.calculation_checks.
        Use exact registered argument names; the declaration adds no model call.
        """;
  }
}
