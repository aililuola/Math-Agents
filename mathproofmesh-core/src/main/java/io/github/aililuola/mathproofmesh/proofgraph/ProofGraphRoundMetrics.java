package io.github.aililuola.mathproofmesh.proofgraph;

/** Immutable state-difference metrics sampled from authoritative graph state. */
public record ProofGraphRoundMetrics(
    int round,
    int rawOpenObligations,
    int activeCanonicalTargets,
    int deferredCanonicalTargets,
    int closedCanonicalTargets,
    int newlyCreatedCanonicalTargets,
    int duplicateOccurrences,
    int forbiddenProposals,
    int verifiedClaimGains,
    int exactRefutationGains,
    int newlyClosedCanonicalTargets,
    double rawProofDebt,
    double activeCanonicalProofDebt,
    double deferredCanonicalProofDebt,
    double globalCanonicalProofDebt,
    double convergenceScore) {

  public ProofGraphRoundMetrics {
    if (round < 0
        || rawOpenObligations < 0
        || activeCanonicalTargets < 0
        || deferredCanonicalTargets < 0
        || closedCanonicalTargets < 0
        || newlyCreatedCanonicalTargets < 0
        || duplicateOccurrences < 0
        || forbiddenProposals < 0
        || verifiedClaimGains < 0
        || exactRefutationGains < 0
        || newlyClosedCanonicalTargets < 0) {
      throw new IllegalArgumentException("proof-graph counters must be nonnegative");
    }
    if (!Double.isFinite(rawProofDebt)
        || rawProofDebt < 0.0d
        || !Double.isFinite(activeCanonicalProofDebt)
        || activeCanonicalProofDebt < 0.0d
        || !Double.isFinite(deferredCanonicalProofDebt)
        || deferredCanonicalProofDebt < 0.0d
        || !Double.isFinite(globalCanonicalProofDebt)
        || globalCanonicalProofDebt < 0.0d
        || !Double.isFinite(convergenceScore)) {
      throw new IllegalArgumentException("proof-graph debt and score values must be finite");
    }
  }

  public boolean authoritativeProgress() {
    return verifiedClaimGains > 0
        || exactRefutationGains > 0
        || newlyClosedCanonicalTargets > 0;
  }

  public int totalCanonicalTargets() {
    return activeCanonicalTargets + deferredCanonicalTargets + closedCanonicalTargets;
  }

  public ProofGraphRoundMetrics withConvergenceScore(double score) {
    return new ProofGraphRoundMetrics(
        round,
        rawOpenObligations,
        activeCanonicalTargets,
        deferredCanonicalTargets,
        closedCanonicalTargets,
        newlyCreatedCanonicalTargets,
        duplicateOccurrences,
        forbiddenProposals,
        verifiedClaimGains,
        exactRefutationGains,
        newlyClosedCanonicalTargets,
        rawProofDebt,
        activeCanonicalProofDebt,
        deferredCanonicalProofDebt,
        globalCanonicalProofDebt,
        score);
  }
}
