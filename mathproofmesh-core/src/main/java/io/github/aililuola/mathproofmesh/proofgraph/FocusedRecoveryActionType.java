package io.github.aililuola.mathproofmesh.proofgraph;

/** Actions understood by the deterministic focused-recovery admission gate. */
public enum FocusedRecoveryActionType {
  FOCUSED_PROVER(true, true),
  FOCUSED_SKEPTIC(true, true),
  EXACT_FALSIFICATION(true, false),
  FAMILY_BRIDGE_REPAIR(true, true),
  VERIFIED_CLAIM_REUSE(true, true),
  GENERIC_INSPIRATION(false, false),
  REPRESENTATION_SWITCH(false, false),
  STRUCTURAL_ANALOGY(false, false),
  NEW_STRATEGY(false, false),
  UNSCOPED_BRIDGE(false, false);

  private final boolean recoveryAction;
  private final boolean requiresSelectedBinding;

  FocusedRecoveryActionType(boolean recoveryAction, boolean requiresSelectedBinding) {
    this.recoveryAction = recoveryAction;
    this.requiresSelectedBinding = requiresSelectedBinding;
  }

  public boolean recoveryAction() {
    return recoveryAction;
  }

  public boolean requiresSelectedBinding() {
    return requiresSelectedBinding;
  }

  /** Stable source-token classification shared by every production task entry point. */
  public static FocusedRecoveryActionType classifyTaskSource(
      String source, boolean hasBottleneckFamily) {
    String normalized = source == null ? "" : source.toLowerCase(java.util.Locale.ROOT).strip();
    if (normalized.contains("exact-falsification")) {
      return EXACT_FALSIFICATION;
    }
    if (normalized.contains("focused-skeptic") || normalized.contains("meta-review")) {
      return FOCUSED_SKEPTIC;
    }
    if (normalized.contains("focused-recovery") || normalized.contains("proof-debt-stall")) {
      return FOCUSED_PROVER;
    }
    if (normalized.contains("family-bridge")) {
      return FAMILY_BRIDGE_REPAIR;
    }
    if (normalized.contains("unscoped-bridge")) {
      return UNSCOPED_BRIDGE;
    }
    if (normalized.contains("bridge")) {
      return hasBottleneckFamily ? FAMILY_BRIDGE_REPAIR : UNSCOPED_BRIDGE;
    }
    if (normalized.contains("generic-inspiration") || normalized.contains("inspiration")) {
      return GENERIC_INSPIRATION;
    }
    if (normalized.contains("representation-switch")) {
      return REPRESENTATION_SWITCH;
    }
    if (normalized.contains("structural-analogy")) {
      return STRUCTURAL_ANALOGY;
    }
    if (normalized.contains("verified-claim-reuse")) {
      return VERIFIED_CLAIM_REUSE;
    }
    return NEW_STRATEGY;
  }
}
