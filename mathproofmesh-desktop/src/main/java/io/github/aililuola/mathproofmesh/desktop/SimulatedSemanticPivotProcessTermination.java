package io.github.aililuola.mathproofmesh.desktop;

import java.io.Serial;

/** Test-only Error used to model termination that cannot enter RuntimeException compensation. */
final class SimulatedSemanticPivotProcessTermination extends Error {
  @Serial private static final long serialVersionUID = 1L;

  SimulatedSemanticPivotProcessTermination(SemanticPivotFailurePoint point) {
    super("simulated semantic pivot process termination: " + point);
  }
}
