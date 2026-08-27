package io.github.aililuola.mathproofmesh.computation;

import io.github.aililuola.mathproofmesh.contract.ComputationMethod;

/** Process-bound handler supplied by the server adapter for sidecar or sandbox methods. */
public interface ExternalComputationHandler extends ComputationHandler {
  boolean supports(ComputationMethod method);

  String toolIdentity(ComputationMethod method);
}
