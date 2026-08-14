package io.github.aililuola.mathproofmesh.proofgraph;

/** Durable scheduling lifecycle for a proposal preserved outside active proof work. */
public enum DeferredExpansionStatus {
  DEFERRED,
  REACTIVATED,
  SATISFIED_BY_ACTIVE_TARGET,
  RETIRED
}
