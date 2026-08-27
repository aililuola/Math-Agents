package io.github.aililuola.mathproofmesh.proofgraph;

/** Stable reason codes for proof-graph control-mode transitions. */
public enum ProofGraphConvergenceTrigger {
  AUTHORITATIVE_PROGRESS,
  CANONICAL_DEBT_DECREASE,
  CONSECUTIVE_STAGNATION,
  CONSECUTIVE_DIVERGENCE,
  ACTIVE_TARGET_CAPACITY,
  RECOVERY_PROGRESS,
  COOLDOWN_COMPLETE
}
