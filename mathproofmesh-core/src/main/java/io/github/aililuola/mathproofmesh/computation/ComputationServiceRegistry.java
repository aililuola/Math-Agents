package io.github.aililuola.mathproofmesh.computation;

import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import java.util.Set;

/** Public inventory for prompts, validation, and operational status endpoints. */
public final class ComputationServiceRegistry {
  private ComputationServiceRegistry() {}

  public static Set<ComputationMethod> javaNativeMethods() {
    return Set.of(
        ComputationMethod.MODULAR_EXHAUSTIVE,
        ComputationMethod.BOUNDED_INTEGER_SEARCH,
        ComputationMethod.GRAPH_CERTIFICATE,
        ComputationMethod.RECURRENCE_CHECK,
        ComputationMethod.BOUNDED_GREEDY_SEQUENCE,
        ComputationMethod.CANDIDATE_PERIOD_CHECK,
        ComputationMethod.EXACT_GEOMETRY,
        ComputationMethod.NUMBER_THEORY_CHECK);
  }

  public static Set<ComputationMethod> sidecarMethods() {
    return Set.of(
        ComputationMethod.SYMPY_SIMPLIFY,
        ComputationMethod.SYMPY_EQUIVALENT,
        ComputationMethod.POLYNOMIAL_FACTOR,
        ComputationMethod.NUMERIC_COUNTEREXAMPLE,
        ComputationMethod.REAL_INEQUALITY);
  }

  public static Set<ComputationMethod> arbitraryExecutionMethods() {
    return Set.of(
        ComputationMethod.SANDBOXED_PYTHON, ComputationMethod.LEAN_CHECK);
  }
}
