package io.github.aililuola.mathproofmesh.proofgraph;

/** Proof-graph convergence policy, deliberately separate from token and provider budgets. */
public record ProofGraphConvergenceConfig(
    int maxActiveCanonicalTargetsPerRoute,
    int maxActiveCanonicalTargetsCampaign,
    int maxNewCanonicalTargetsPerFocusedEpisode,
    int stagnationWindow,
    int divergenceWindow,
    int cooldownRounds,
    double debtEpsilon,
    double closedTargetWeight,
    double verifiedClaimWeight,
    double exactRefutationWeight,
    double debtDecreaseWeight,
    double newTargetPenalty,
    double duplicateOccurrencePenalty,
    double forbiddenProposalPenalty,
    int maxDeferredReactivationsPerRound) {

  public ProofGraphConvergenceConfig {
    if (maxActiveCanonicalTargetsPerRoute <= 0
        || maxActiveCanonicalTargetsCampaign <= 0
        || maxNewCanonicalTargetsPerFocusedEpisode < 0
        || maxDeferredReactivationsPerRound <= 0
        || stagnationWindow <= 0
        || divergenceWindow <= 0
        || cooldownRounds <= 0) {
      throw new IllegalArgumentException("proof-graph convergence limits must be positive");
    }
    if (!finiteNonnegative(debtEpsilon)
        || !finiteNonnegative(closedTargetWeight)
        || !finiteNonnegative(verifiedClaimWeight)
        || !finiteNonnegative(exactRefutationWeight)
        || !finiteNonnegative(debtDecreaseWeight)
        || !finiteNonnegative(newTargetPenalty)
        || !finiteNonnegative(duplicateOccurrencePenalty)
        || !finiteNonnegative(forbiddenProposalPenalty)) {
      throw new IllegalArgumentException("proof-graph convergence weights must be finite");
    }
  }

  public ProofGraphConvergenceConfig(
      int maxActiveCanonicalTargetsPerRoute,
      int maxActiveCanonicalTargetsCampaign,
      int maxNewCanonicalTargetsPerFocusedEpisode,
      int stagnationWindow,
      int divergenceWindow,
      int cooldownRounds,
      double debtEpsilon,
      double closedTargetWeight,
      double verifiedClaimWeight,
      double exactRefutationWeight,
      double debtDecreaseWeight,
      double newTargetPenalty,
      double duplicateOccurrencePenalty,
      double forbiddenProposalPenalty) {
    this(
        maxActiveCanonicalTargetsPerRoute,
        maxActiveCanonicalTargetsCampaign,
        maxNewCanonicalTargetsPerFocusedEpisode,
        stagnationWindow,
        divergenceWindow,
        cooldownRounds,
        debtEpsilon,
        closedTargetWeight,
        verifiedClaimWeight,
        exactRefutationWeight,
        debtDecreaseWeight,
        newTargetPenalty,
        duplicateOccurrencePenalty,
        forbiddenProposalPenalty,
        2);
  }

  public static ProofGraphConvergenceConfig defaults() {
    return new ProofGraphConvergenceConfig(
        8, 20, 2, 2, 2, 1, 1.0e-9d, 2.0d, 2.0d, 1.0d, 1.0d, 1.0d, 0.5d, 1.0d, 2);
  }

  public double score(ProofGraphRoundMetrics current, ProofGraphRoundMetrics previous) {
    java.util.Objects.requireNonNull(current, "current");
    boolean debtDecrease =
        previous != null
            && previous.globalCanonicalProofDebt() - current.globalCanonicalProofDebt()
                > debtEpsilon;
    return closedTargetWeight * current.newlyClosedCanonicalTargets()
        + verifiedClaimWeight * current.verifiedClaimGains()
        + exactRefutationWeight * current.exactRefutationGains()
        + (debtDecrease ? debtDecreaseWeight : 0.0d)
        - newTargetPenalty * current.newlyCreatedCanonicalTargets()
        - duplicateOccurrencePenalty * current.duplicateOccurrences()
        - forbiddenProposalPenalty * current.forbiddenProposals();
  }

  private static boolean finiteNonnegative(double value) {
    return Double.isFinite(value) && value >= 0.0d;
  }
}
