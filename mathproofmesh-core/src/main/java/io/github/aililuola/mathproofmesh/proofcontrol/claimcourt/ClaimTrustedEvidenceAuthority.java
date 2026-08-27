package io.github.aililuola.mathproofmesh.proofcontrol.claimcourt;

/** Server-owned authority classes that may support a repaired proof step. */
public enum ClaimTrustedEvidenceAuthority {
  VERIFIED_FACT(false),
  REPLAYED_COMPUTATION(true),
  FORMAL_CERTIFICATE(false);

  private final boolean replayRequired;

  ClaimTrustedEvidenceAuthority(boolean replayRequired) {
    this.replayRequired = replayRequired;
  }

  public boolean replayRequired() {
    return replayRequired;
  }
}
