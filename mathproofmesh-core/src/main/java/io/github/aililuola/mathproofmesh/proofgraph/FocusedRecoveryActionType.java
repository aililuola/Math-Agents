package io.github.aililuola.mathproofmesh.proofgraph;

/** Actions understood by the deterministic focused-recovery admission gate. */
public enum FocusedRecoveryActionType {
  FOCUSED_PROVER(true),
  FOCUSED_SKEPTIC(true),
  EXACT_FALSIFICATION(true),
  FAMILY_BRIDGE_REPAIR(true),
  VERIFIED_CLAIM_REUSE(true),
  GENERIC_INSPIRATION(false),
  REPRESENTATION_SWITCH(false),
  STRUCTURAL_ANALOGY(false),
  NEW_STRATEGY(false),
  UNSCOPED_BRIDGE(false);

  private final boolean recoveryAction;

  FocusedRecoveryActionType(boolean recoveryAction) {
    this.recoveryAction = recoveryAction;
  }

  public boolean recoveryAction() {
    return recoveryAction;
  }
}
