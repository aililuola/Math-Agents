package io.github.aililuola.mathproofmesh.proofgraph;

/** Controls which proof-graph expansion actions may be admitted in the next round. */
public enum ProofGraphControlMode {
  NORMAL_EXPANSION,
  FOCUSED_RECOVERY,
  RECOVERY_COOLDOWN
}
