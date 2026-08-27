package io.github.aililuola.mathproofmesh.strategydiversity;

public enum CriticalClaimPreflightStatus {
  VERIFIED_SUPPORTED,
  VERIFIED_REFUTED,
  PERMANENTLY_BLOCKED,
  NOT_REFUTED_IN_BOUNDED_SCOPE,
  EXECUTION_QUARANTINED,
  UNKNOWN,
  UNTESTABLE,
  ERROR
}
