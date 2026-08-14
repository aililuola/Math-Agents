package io.github.aililuola.mathproofmesh.proofcontrol;

/** A strategy-usage change, never a claim truth-state transition. */
public enum PivotClaimUsageAction {
  RETAIN_AS_VERIFIED_FACT,
  RETIRE_FROM_ACTIVE_DEPENDENCY,
  ADD_AS_PROPOSED_CLAIM
}
