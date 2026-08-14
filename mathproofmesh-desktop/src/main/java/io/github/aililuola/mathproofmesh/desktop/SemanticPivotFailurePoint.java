package io.github.aililuola.mathproofmesh.desktop;

/** Deterministic atomicity injection points used by the production semantic-pivot transaction. */
enum SemanticPivotFailurePoint {
  NONE,
  AFTER_LEDGER_STAGED,
  AFTER_STRATEGY_EPOCH,
  AFTER_ROUTE_SWITCH,
  AFTER_OBLIGATION_CANONICALIZATION,
  AFTER_PENDING_TASK,
  AFTER_CHECKPOINT_BRANCH,
  DURING_CHECKPOINT_PERSIST,
  BEFORE_APPLIED_CHECKPOINT_PERSIST
}
