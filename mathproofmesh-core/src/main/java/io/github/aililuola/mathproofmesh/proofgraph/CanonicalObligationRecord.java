package io.github.aililuola.mathproofmesh.proofgraph;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Set;

@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification = "The compact constructor creates immutable collection copies.")
public record CanonicalObligationRecord(
    String canonicalTargetId,
    String problemHash,
    ObligationSemanticSignature signature,
    String representativeOccurrenceId,
    Set<String> occurrenceIds,
    Set<String> routeIds,
    Set<String> dependencyPlanSignatures,
    CanonicalObligationSchedulingState schedulingState,
    long version) {

  public CanonicalObligationRecord {
    canonicalTargetId = require(canonicalTargetId, "canonicalTargetId");
    problemHash = require(problemHash, "problemHash");
    signature = java.util.Objects.requireNonNull(signature, "signature");
    representativeOccurrenceId = require(representativeOccurrenceId, "representativeOccurrenceId");
    occurrenceIds = occurrenceIds == null ? Set.of() : Set.copyOf(occurrenceIds);
    routeIds = routeIds == null ? Set.of() : Set.copyOf(routeIds);
    dependencyPlanSignatures =
        dependencyPlanSignatures == null ? Set.of() : Set.copyOf(dependencyPlanSignatures);
    schedulingState =
        schedulingState == null ? CanonicalObligationSchedulingState.ACTIVE : schedulingState;
    if (occurrenceIds.isEmpty() || !occurrenceIds.contains(representativeOccurrenceId)) {
      throw new IllegalArgumentException("representative occurrence must belong to the target");
    }
    if (version < 0) {
      throw new IllegalArgumentException("version must be nonnegative");
    }
  }

  private static String require(String value, String field) {
    String normalized = value == null ? "" : value.strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return normalized;
  }
}
