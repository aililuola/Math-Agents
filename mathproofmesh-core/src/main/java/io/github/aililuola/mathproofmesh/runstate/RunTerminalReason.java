package io.github.aililuola.mathproofmesh.runstate;

public enum RunTerminalReason {
  NONE,
  VERIFIED,
  UNVERIFIED_TERMINAL,
  BUDGET_EXHAUSTED,
  USER_CANCELLED,
  EXECUTION_FAILED,
  EXECUTION_INTERRUPTED,
  AUTHORITY_CONFLICT
}
