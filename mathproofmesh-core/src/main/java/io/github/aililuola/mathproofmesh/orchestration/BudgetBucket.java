package io.github.aililuola.mathproofmesh.orchestration;

/** Stable accounting buckets for evidence-aware scheduling. */
public enum BudgetBucket {
  BREADTH,
  DEPTH,
  VERIFICATION,
  REVISION,
  SYNTHESIS,
  FINISH,
  LEGACY_UNCLASSIFIED
}
