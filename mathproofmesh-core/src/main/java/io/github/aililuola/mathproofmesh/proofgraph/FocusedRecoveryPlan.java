package io.github.aililuola.mathproofmesh.proofgraph;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Set;

/** Persisted, deterministic selection of an existing family or canonical target. */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification = "The compact constructor and accessor return immutable set values.")
public record FocusedRecoveryPlan(
    String episodeId,
    String problemHash,
    String rootGoalHash,
    ProofGraphConvergenceTrigger trigger,
    int createdRound,
    String selectedFamilyId,
    Set<String> selectedCanonicalTargetIds,
    int newTargetQuota,
    int usedNewTargets) {

  public FocusedRecoveryPlan {
    episodeId = require(episodeId, "episodeId");
    problemHash = require(problemHash, "problemHash");
    rootGoalHash = require(rootGoalHash, "rootGoalHash");
    trigger = java.util.Objects.requireNonNull(trigger, "trigger");
    selectedFamilyId = selectedFamilyId == null ? "" : selectedFamilyId.strip();
    selectedCanonicalTargetIds = immutableSorted(selectedCanonicalTargetIds);
    if (createdRound < 0
        || newTargetQuota < 0
        || usedNewTargets < 0
        || usedNewTargets > newTargetQuota
        || selectedCanonicalTargetIds.isEmpty()) {
      throw new IllegalArgumentException("invalid focused-recovery plan counters or selection");
    }
  }

  @Override
  public Set<String> selectedCanonicalTargetIds() {
    return selectedCanonicalTargetIds;
  }

  public int quotaRemaining() {
    return newTargetQuota - usedNewTargets;
  }

  public boolean selects(String familyId, String canonicalTargetId) {
    return (!selectedFamilyId.isBlank() && selectedFamilyId.equals(normalize(familyId)))
        || selectedCanonicalTargetIds.contains(normalize(canonicalTargetId));
  }

  public FocusedRecoveryPlan useNewTarget() {
    if (quotaRemaining() <= 0) {
      throw new IllegalStateException("focused recovery new-target quota is exhausted");
    }
    return new FocusedRecoveryPlan(
        episodeId,
        problemHash,
        rootGoalHash,
        trigger,
        createdRound,
        selectedFamilyId,
        selectedCanonicalTargetIds,
        newTargetQuota,
        usedNewTargets + 1);
  }

  private static String normalize(String value) {
    return value == null ? "" : value.strip();
  }

  private static String require(String value, String field) {
    String normalized = normalize(value);
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return normalized;
  }

  private static Set<String> immutableSorted(Set<String> values) {
    return values == null || values.isEmpty()
        ? Set.of()
        : java.util.Collections.unmodifiableSet(new java.util.TreeSet<>(values));
  }
}
