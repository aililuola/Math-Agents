package io.github.aililuola.mathproofmesh.proofgraph;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Map;
import java.util.Set;

@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification = "The compact constructor creates immutable collection copies.")
public record BottleneckFamilyRecord(
    String familyId,
    String problemHash,
    String label,
    String representativeCanonicalTargetId,
    Set<String> canonicalTargetIds,
    Map<String, BottleneckRelationType> memberRelations,
    BottleneckFamilySchedulingState schedulingState,
    String source,
    long version) {

  public BottleneckFamilyRecord {
    familyId = require(familyId, "familyId");
    problemHash = require(problemHash, "problemHash");
    label = require(label, "label");
    representativeCanonicalTargetId =
        require(representativeCanonicalTargetId, "representativeCanonicalTargetId");
    canonicalTargetIds =
        canonicalTargetIds == null ? Set.of() : Set.copyOf(canonicalTargetIds);
    memberRelations = memberRelations == null ? Map.of() : Map.copyOf(memberRelations);
    schedulingState =
        schedulingState == null ? BottleneckFamilySchedulingState.ACTIVE : schedulingState;
    source = require(source, "source");
    if (canonicalTargetIds.isEmpty()
        || !canonicalTargetIds.contains(representativeCanonicalTargetId)
        || !memberRelations.keySet().equals(canonicalTargetIds)) {
      throw new IllegalArgumentException("family membership and relations must be complete");
    }
    if (memberRelations.values().stream().anyMatch(value -> value == BottleneckRelationType.DISTINCT)) {
      throw new IllegalArgumentException("DISTINCT targets cannot be family members");
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
