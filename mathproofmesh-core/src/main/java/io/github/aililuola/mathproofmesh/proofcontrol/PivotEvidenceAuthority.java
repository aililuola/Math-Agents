package io.github.aililuola.mathproofmesh.proofcontrol;

/** Authority class of an obstruction. Only existing authority services may issue these values. */
public enum PivotEvidenceAuthority {
  VERIFIED_COUNTEREXAMPLE,
  PERMANENT_NEGATIVE,
  VERIFIED_CLAIM,
  EXACT_REFUTED_OBLIGATION,
  SHARP_OBSTRUCTION_CANDIDATE,
  FAILURE_FINGERPRINT,
  BOTTLENECK_FAMILY;

  public boolean mayChangeDirectionOnly() {
    return this == SHARP_OBSTRUCTION_CANDIDATE
        || this == FAILURE_FINGERPRINT
        || this == BOTTLENECK_FAMILY;
  }
}
