package io.github.aililuola.mathproofmesh.proofcontrol;

/** A strategy-focus change; no action closes or refutes mathematical graph state. */
public enum PivotObligationAction {
  RETAIN_AS_ACTIVE_FOCUS,
  RETIRE_FROM_STRATEGY_FOCUS,
  ADD_NEW_OBLIGATION
}
