package io.github.aililuola.mathproofmesh.desktop;

/** Test-only hard process boundary that deliberately bypasses RuntimeException compensation. */
final class SimulatedClaimCourtProcessTermination extends Error {
  private static final long serialVersionUID = 1L;

  SimulatedClaimCourtProcessTermination(ClaimCourtFailurePoint point) {
    super("simulated claim court process termination at " + point);
  }
}
