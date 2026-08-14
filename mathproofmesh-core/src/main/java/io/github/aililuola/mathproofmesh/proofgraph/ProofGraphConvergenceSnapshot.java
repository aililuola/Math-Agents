package io.github.aililuola.mathproofmesh.proofgraph;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Set;

/** Durable state of the deterministic proof-graph convergence controller. */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification = "The compact constructor and accessors use immutable collection copies.")
public record ProofGraphConvergenceSnapshot(
    ProofGraphControlMode controlMode,
    List<ProofGraphRoundMetrics> roundHistory,
    List<ProofGraphRoundClassification> roundClassifications,
    int consecutiveStagnation,
    int consecutiveDivergence,
    int cooldownRemaining,
    FocusedRecoveryPlan focusedRecoveryPlan,
    Set<String> focusedTaskLeases,
    int stagnationEpisodes,
    int divergenceEpisodes,
    int focusedRecoveryEntries,
    int focusedRecoveryExits,
    int recoveryCooldownEntries,
    int genericExpansionAttempts,
    int genericExpansionBlocks,
    int genericExpansionLeaks,
    int observedOccurrenceTotal,
    int observedVerifiedClaimTotal,
    int observedExactRefutationTotal,
    int observedForbiddenProposalTotal,
    long version) {

  public ProofGraphConvergenceSnapshot {
    controlMode =
        controlMode == null ? ProofGraphControlMode.NORMAL_EXPANSION : controlMode;
    roundHistory = roundHistory == null ? List.of() : List.copyOf(roundHistory);
    roundClassifications =
        roundClassifications == null ? List.of() : List.copyOf(roundClassifications);
    focusedTaskLeases = immutableSorted(focusedTaskLeases);
    if (roundClassifications.size() != roundHistory.size()
        || consecutiveStagnation < 0
        || consecutiveDivergence < 0
        || cooldownRemaining < 0
        || stagnationEpisodes < 0
        || divergenceEpisodes < 0
        || focusedRecoveryEntries < 0
        || focusedRecoveryExits < 0
        || recoveryCooldownEntries < 0
        || genericExpansionAttempts < 0
        || genericExpansionBlocks < 0
        || genericExpansionLeaks < 0
        || observedOccurrenceTotal < 0
        || observedVerifiedClaimTotal < 0
        || observedExactRefutationTotal < 0
        || observedForbiddenProposalTotal < 0
        || version < 0) {
      throw new IllegalArgumentException("invalid proof-graph convergence snapshot counters");
    }
    if (controlMode == ProofGraphControlMode.FOCUSED_RECOVERY
        && focusedRecoveryPlan == null) {
      throw new IllegalArgumentException("focused recovery mode requires a persisted plan");
    }
  }

  public static ProofGraphConvergenceSnapshot empty() {
    return new ProofGraphConvergenceSnapshot(
        ProofGraphControlMode.NORMAL_EXPANSION,
        List.of(),
        List.of(),
        0,
        0,
        0,
        null,
        Set.of(),
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0L);
  }

  @Override
  public List<ProofGraphRoundMetrics> roundHistory() {
    return List.copyOf(roundHistory);
  }

  @Override
  public List<ProofGraphRoundClassification> roundClassifications() {
    return List.copyOf(roundClassifications);
  }

  @Override
  public Set<String> focusedTaskLeases() {
    return focusedTaskLeases;
  }

  private static Set<String> immutableSorted(Set<String> values) {
    return values == null || values.isEmpty()
        ? Set.of()
        : java.util.Collections.unmodifiableSet(new java.util.TreeSet<>(values));
  }
}
