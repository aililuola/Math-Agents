package io.github.aililuola.mathproofmesh.proofgraph;

/** Immutable production facts consumed by the deterministic deferred-work planner. */
public record DeferredExpansionReactivationCandidate(
    DeferredExpansionRecord record,
    int activeTargetsForRoute,
    int activeTargetsCampaign,
    boolean sameProblem,
    boolean rawOccurrenceExists,
    boolean canonicalTargetExists,
    CanonicalObligationStatus canonicalStatus,
    CanonicalObligationSchedulingState canonicalSchedulingState,
    boolean routeSchedulable,
    boolean routePermanentlyUnavailable,
    boolean negativeKnowledgeAllowed,
    boolean selectedBinding,
    double centrality,
    double priority) {

  public DeferredExpansionReactivationCandidate {
    record = java.util.Objects.requireNonNull(record, "record");
    canonicalStatus =
        canonicalStatus == null ? CanonicalObligationStatus.OPEN : canonicalStatus;
    canonicalSchedulingState =
        canonicalSchedulingState == null
            ? CanonicalObligationSchedulingState.RETIRED
            : canonicalSchedulingState;
    if (activeTargetsForRoute < 0
        || activeTargetsCampaign < 0
        || !Double.isFinite(centrality)
        || !Double.isFinite(priority)) {
      throw new IllegalArgumentException("invalid deferred reactivation candidate metrics");
    }
  }
}
