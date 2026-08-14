package io.github.aililuola.mathproofmesh.proofgraph;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Map;
import java.util.Set;

@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification = "The compact constructor creates immutable collection copies.")
public record ObligationCanonicalizationSnapshot(
    Map<String, ObligationOccurrenceRecord> occurrences,
    Map<String, CanonicalObligationRecord> canonicalTargets,
    Map<String, BottleneckFamilyRecord> bottleneckFamilies,
    Map<String, String> canonicalBySignature,
    Map<String, String> familyByKey,
    Map<String, String> familyByCanonicalTarget,
    Map<String, String> representativeExactStatements,
    Map<String, Double> canonicalCentrality,
    Map<String, Double> canonicalPriority,
    Map<String, String> canonicalRepresentativeStatements,
    Set<String> taskLeaseKeys,
    List<ObligationCanonicalizationAuditEvent> audit,
    long possibleEquivalentQuarantines,
    long unsafeHardMerges,
    long version) {

  public ObligationCanonicalizationSnapshot {
    occurrences = immutable(occurrences);
    canonicalTargets = immutable(canonicalTargets);
    bottleneckFamilies = immutable(bottleneckFamilies);
    canonicalBySignature = immutable(canonicalBySignature);
    familyByKey = immutable(familyByKey);
    familyByCanonicalTarget = immutable(familyByCanonicalTarget);
    representativeExactStatements = immutable(representativeExactStatements);
    canonicalCentrality = immutable(canonicalCentrality);
    canonicalPriority = immutable(canonicalPriority);
    canonicalRepresentativeStatements = immutable(canonicalRepresentativeStatements);
    taskLeaseKeys = taskLeaseKeys == null ? Set.of() : Set.copyOf(taskLeaseKeys);
    audit = audit == null ? List.of() : List.copyOf(audit);
    if (possibleEquivalentQuarantines < 0 || unsafeHardMerges < 0 || version < 0) {
      throw new IllegalArgumentException("snapshot counters must be nonnegative");
    }
  }

  public static ObligationCanonicalizationSnapshot empty() {
    return new ObligationCanonicalizationSnapshot(
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Set.of(),
        List.of(),
        0,
        0,
        0);
  }

  public boolean emptyState() {
    return occurrences.isEmpty() && canonicalTargets.isEmpty() && bottleneckFamilies.isEmpty();
  }

  private static <K, V> Map<K, V> immutable(Map<K, V> value) {
    return value == null ? Map.of() : Map.copyOf(value);
  }
}
