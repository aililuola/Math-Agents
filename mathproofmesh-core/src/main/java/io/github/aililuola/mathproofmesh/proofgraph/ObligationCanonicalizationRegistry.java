package io.github.aililuola.mathproofmesh.proofgraph;

import com.fasterxml.jackson.databind.JsonNode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.ObligationKind;
import io.github.aililuola.mathproofmesh.contract.ProofObligation;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Deterministic operational projection over the immutable raw obligation history. */
@SuppressFBWarnings(
    value = {"EI_EXPOSE_REP", "IMPROPER_UNICODE"},
    justification =
        "Public collection views are immutable; NFKC and Locale.ROOT define stable keys.")
public final class ObligationCanonicalizationRegistry {
  private final Map<String, ObligationOccurrenceRecord> occurrences = new LinkedHashMap<>();
  private final Map<String, CanonicalObligationRecord> canonicalTargets =
      new LinkedHashMap<>();
  private final Map<String, BottleneckFamilyRecord> bottleneckFamilies =
      new LinkedHashMap<>();
  private final Map<String, String> canonicalBySignature = new LinkedHashMap<>();
  private final Map<String, String> familyByKey = new LinkedHashMap<>();
  private final Map<String, String> familyByCanonicalTarget = new LinkedHashMap<>();
  private final Map<String, String> representativeExactStatements = new LinkedHashMap<>();
  private final Map<String, Double> canonicalCentrality = new LinkedHashMap<>();
  private final Map<String, Double> canonicalPriority = new LinkedHashMap<>();
  private final Map<String, String> canonicalRepresentativeStatements =
      new LinkedHashMap<>();
  private final Set<String> taskLeaseKeys = new LinkedHashSet<>();
  private final List<ObligationCanonicalizationAuditEvent> audit = new ArrayList<>();
  private long possibleEquivalentQuarantines;
  private long unsafeHardMerges;
  private long version;

  public ObligationCanonicalizationRegistry() {}

  private ObligationCanonicalizationRegistry(ObligationCanonicalizationSnapshot snapshot) {
    load(snapshot);
  }

  public static ObligationCanonicalizationRegistry restore(
      ObligationCanonicalizationSnapshot snapshot) {
    return new ObligationCanonicalizationRegistry(
        snapshot == null ? ObligationCanonicalizationSnapshot.empty() : snapshot);
  }

  public synchronized CanonicalizedObligationWriteResult register(
      ProofObligation obligation, ObligationCreationContext context) {
    java.util.Objects.requireNonNull(obligation, "obligation");
    java.util.Objects.requireNonNull(context, "context");
    Optional<ObligationOccurrenceRecord> prior = occurrenceForObligation(obligation.obligationId());
    if (prior.isPresent()) {
      ObligationOccurrenceRecord occurrence = prior.orElseThrow();
      CanonicalObligationRecord canonical =
          requireCanonical(occurrence.canonicalTargetId());
      BottleneckFamilyRecord family =
          occurrence.bottleneckFamilyId().isBlank()
              ? null
              : bottleneckFamilies.get(occurrence.bottleneckFamilyId());
      return new CanonicalizedObligationWriteResult(
          obligation,
          occurrence,
          canonical,
          family,
          ObligationIdentityStrength.EXACT,
          true,
          false);
    }

    ObligationSemanticSignature signature = ObligationSemanticSignature.from(obligation, context);
    String canonicalTargetId = canonicalBySignature.get(signature.signatureHash());
    boolean existingCanonicalTarget = canonicalTargetId != null;
    boolean quarantined = false;
    ObligationIdentityStrength identityStrength = ObligationIdentityStrength.DISTINCT;
    if (existingCanonicalTarget) {
      String exactStatement = exactStatement(obligation);
      identityStrength =
          exactStatement.equals(representativeExactStatements.get(canonicalTargetId))
              ? ObligationIdentityStrength.EXACT
              : ObligationIdentityStrength.TRUSTED_ALPHA_EQUIVALENT;
    } else {
      Optional<CanonicalObligationRecord> possible = possibleEquivalent(signature);
      if (possible.isPresent()) {
        quarantined = true;
        possibleEquivalentQuarantines++;
        record(
            "POSSIBLE_EQUIVALENT_QUARANTINED",
            obligation.obligationId(),
            Map.of("candidate_canonical_target_id", possible.orElseThrow().canonicalTargetId()));
      }
      canonicalTargetId = "canonical_" + signature.signatureHash();
      canonicalBySignature.put(signature.signatureHash(), canonicalTargetId);
    }

    String dependencyPlanSignature = dependencyPlanSignature(obligation, context);
    String occurrenceId = occurrenceId(obligation, context, dependencyPlanSignature);
    ObligationOccurrenceRecord occurrence =
        new ObligationOccurrenceRecord(
            occurrenceId,
            obligation.obligationId(),
            obligation.problemHash(),
            context.routeId(),
            context.strategyId(),
            context.sourceType(),
            context.sourceArtifactRef(),
            canonicalTargetId,
            "",
            dependencyPlanSignature,
            context.schedulingState(),
            context.createdRound(),
            0);
    occurrences.put(occurrenceId, occurrence);

    CanonicalObligationRecord canonical = canonicalTargets.get(canonicalTargetId);
    if (canonical == null) {
      Set<String> routeIds = nonBlankSet(context.routeId(), obligation.routeIds());
      canonical =
          new CanonicalObligationRecord(
              canonicalTargetId,
              obligation.problemHash(),
              signature,
              occurrenceId,
              Set.of(occurrenceId),
              routeIds,
              Set.of(dependencyPlanSignature),
              canonicalSchedulingState(List.of(occurrence)),
              0);
      canonicalTargets.put(canonicalTargetId, canonical);
      representativeExactStatements.put(canonicalTargetId, exactStatement(obligation));
      canonicalCentrality.put(canonicalTargetId, obligation.centrality());
      canonicalPriority.put(canonicalTargetId, obligation.priority());
      canonicalRepresentativeStatements.put(canonicalTargetId, obligation.statement());
      record("CANONICAL_TARGET_CREATED", canonicalTargetId, Map.of());
    } else {
      canonical = mergeCanonical(canonical, occurrence, obligation);
      canonicalTargets.put(canonicalTargetId, canonical);
      record(
          "RAW_OCCURRENCE_ATTACHED",
          canonicalTargetId,
          Map.of("occurrence_id", occurrenceId, "identity", identityStrength.name()));
    }

    BottleneckFamilyRecord family = attachFamily(canonical, context);
    if (family != null) {
      occurrence = occurrence.withFamily(family.familyId());
      occurrences.put(occurrenceId, occurrence);
    }
    version++;
    return new CanonicalizedObligationWriteResult(
        obligation,
        occurrence,
        canonicalTargets.get(canonicalTargetId),
        family,
        identityStrength,
        existingCanonicalTarget,
        quarantined);
  }

  public synchronized Optional<ObligationOccurrenceRecord> occurrenceForObligation(
      String obligationId) {
    return occurrences.values().stream()
        .filter(item -> item.obligationId().equals(obligationId))
        .findFirst();
  }

  public synchronized Optional<CanonicalObligationRecord> canonicalForObligation(
      String obligationId) {
    return occurrenceForObligation(obligationId)
        .map(ObligationOccurrenceRecord::canonicalTargetId)
        .map(canonicalTargets::get);
  }

  public synchronized Optional<BottleneckFamilyRecord> familyForCanonicalTarget(
      String canonicalTargetId) {
    return Optional.ofNullable(familyByCanonicalTarget.get(canonicalTargetId))
        .map(bottleneckFamilies::get);
  }

  public synchronized List<ObligationOccurrenceRecord> occurrences() {
    return List.copyOf(occurrences.values());
  }

  public synchronized List<CanonicalObligationRecord> canonicalTargets() {
    return List.copyOf(canonicalTargets.values());
  }

  public synchronized List<BottleneckFamilyRecord> bottleneckFamilies() {
    return List.copyOf(bottleneckFamilies.values());
  }

  public synchronized String representativeStatement(String canonicalTargetId) {
    return canonicalRepresentativeStatements.getOrDefault(canonicalTargetId, "");
  }

  public synchronized boolean acquireTaskLease(
      ProofTaskScope scope, String scopeId, String actionKey) {
    java.util.Objects.requireNonNull(scope, "scope");
    String key = taskLeaseKey(scope, scopeId, actionKey);
    boolean acquired = taskLeaseKeys.add(key);
    if (acquired) {
      version++;
      record(
          "PROOF_TASK_LEASE_ACQUIRED",
          scopeId,
          Map.of("scope", scope.name(), "action_key", normalize(actionKey)));
    }
    return acquired;
  }

  public synchronized boolean hasTaskLease(
      ProofTaskScope scope, String scopeId, String actionKey) {
    return taskLeaseKeys.contains(taskLeaseKey(scope, scopeId, actionKey));
  }

  public synchronized long possibleEquivalentQuarantines() {
    return possibleEquivalentQuarantines;
  }

  public synchronized long unsafeHardMerges() {
    return unsafeHardMerges;
  }

  public synchronized long version() {
    return version;
  }

  public synchronized List<ObligationCanonicalizationAuditEvent> audit() {
    return List.copyOf(audit);
  }

  public synchronized String stableHash() {
    return CanonicalJson.stableHash(snapshot());
  }

  public synchronized ObligationCanonicalizationSnapshot snapshot() {
    return new ObligationCanonicalizationSnapshot(
        occurrences,
        canonicalTargets,
        bottleneckFamilies,
        canonicalBySignature,
        familyByKey,
        familyByCanonicalTarget,
        representativeExactStatements,
        canonicalCentrality,
        canonicalPriority,
        canonicalRepresentativeStatements,
        taskLeaseKeys,
        audit,
        possibleEquivalentQuarantines,
        unsafeHardMerges,
        version);
  }

  public synchronized void load(ObligationCanonicalizationSnapshot snapshot) {
    java.util.Objects.requireNonNull(snapshot, "snapshot");
    occurrences.clear();
    canonicalTargets.clear();
    bottleneckFamilies.clear();
    canonicalBySignature.clear();
    familyByKey.clear();
    familyByCanonicalTarget.clear();
    representativeExactStatements.clear();
    canonicalCentrality.clear();
    canonicalPriority.clear();
    canonicalRepresentativeStatements.clear();
    taskLeaseKeys.clear();
    audit.clear();
    occurrences.putAll(snapshot.occurrences());
    canonicalTargets.putAll(snapshot.canonicalTargets());
    bottleneckFamilies.putAll(snapshot.bottleneckFamilies());
    canonicalBySignature.putAll(snapshot.canonicalBySignature());
    familyByKey.putAll(snapshot.familyByKey());
    familyByCanonicalTarget.putAll(snapshot.familyByCanonicalTarget());
    representativeExactStatements.putAll(snapshot.representativeExactStatements());
    canonicalCentrality.putAll(snapshot.canonicalCentrality());
    canonicalPriority.putAll(snapshot.canonicalPriority());
    canonicalRepresentativeStatements.putAll(snapshot.canonicalRepresentativeStatements());
    taskLeaseKeys.addAll(snapshot.taskLeaseKeys());
    audit.addAll(snapshot.audit());
    possibleEquivalentQuarantines = snapshot.possibleEquivalentQuarantines();
    unsafeHardMerges = snapshot.unsafeHardMerges();
    version = snapshot.version();
  }

  static String dependencyPlanSignature(
      ProofObligation obligation, ObligationCreationContext context) {
    List<String> dependencyIds = obligation.dependencyIds().stream().sorted().toList();
    List<JsonNode> dependencyRefs = obligation.dependencyRefs();
    return CanonicalJson.stableHash(
        Map.of(
            "route_id", context.routeId(),
            "strategy_id", context.strategyId(),
            "source_type", context.sourceType().name(),
            "dependency_ids", dependencyIds,
            "dependency_refs", dependencyRefs));
  }

  private CanonicalObligationRecord mergeCanonical(
      CanonicalObligationRecord canonical,
      ObligationOccurrenceRecord occurrence,
      ProofObligation obligation) {
    Set<String> occurrenceIds = new LinkedHashSet<>(canonical.occurrenceIds());
    occurrenceIds.add(occurrence.occurrenceId());
    Set<String> routeIds = new LinkedHashSet<>(canonical.routeIds());
    routeIds.addAll(obligation.routeIds());
    if (!occurrence.routeId().isBlank()) {
      routeIds.add(occurrence.routeId());
    }
    Set<String> plans = new LinkedHashSet<>(canonical.dependencyPlanSignatures());
    plans.add(occurrence.dependencyPlanSignature());
    String representative = canonical.representativeOccurrenceId();
    if (isBetterRepresentative(canonical.canonicalTargetId(), obligation, occurrence)) {
      representative = occurrence.occurrenceId();
      representativeExactStatements.put(canonical.canonicalTargetId(), exactStatement(obligation));
      canonicalCentrality.put(canonical.canonicalTargetId(), obligation.centrality());
      canonicalPriority.put(canonical.canonicalTargetId(), obligation.priority());
      canonicalRepresentativeStatements.put(canonical.canonicalTargetId(), obligation.statement());
    }
    List<ObligationOccurrenceRecord> members =
        occurrenceIds.stream().map(occurrences::get).filter(java.util.Objects::nonNull).toList();
    return new CanonicalObligationRecord(
        canonical.canonicalTargetId(),
        canonical.problemHash(),
        canonical.signature(),
        representative,
        occurrenceIds,
        routeIds,
        plans,
        canonicalSchedulingState(members),
        canonical.version() + 1);
  }

  private BottleneckFamilyRecord attachFamily(
      CanonicalObligationRecord canonical, ObligationCreationContext context) {
    if (canonical.signature().kind() == ObligationKind.MAIN_GOAL
        || context.bottleneckKey().isBlank()) {
      return null;
    }
    String key =
        CanonicalJson.stableHash(
            Map.of(
                "problem_hash", canonical.problemHash(),
                "bottleneck_key", normalize(context.bottleneckKey())));
    String familyId = familyByKey.get(key);
    if (familyId == null) {
      String label =
          context.bottleneckLabel().isBlank()
              ? context.bottleneckKey()
              : context.bottleneckLabel();
      familyId =
          "family_"
              + CanonicalJson.stableHash(
                  Map.of(
                      "problem_hash", canonical.problemHash(),
                      "earliest_canonical_target_id", canonical.canonicalTargetId(),
                      "label", normalize(label)));
      BottleneckFamilyRecord family =
          new BottleneckFamilyRecord(
              familyId,
              canonical.problemHash(),
              label,
              canonical.canonicalTargetId(),
              Set.of(canonical.canonicalTargetId()),
              Map.of(canonical.canonicalTargetId(), context.bottleneckRelation()),
              BottleneckFamilySchedulingState.ACTIVE,
              context.sourceType().name(),
              0);
      bottleneckFamilies.put(familyId, family);
      familyByKey.put(key, familyId);
      familyByCanonicalTarget.put(canonical.canonicalTargetId(), familyId);
      record("BOTTLENECK_FAMILY_CREATED", familyId, Map.of("key", key));
      return family;
    }
    BottleneckFamilyRecord current = bottleneckFamilies.get(familyId);
    String priorFamily = familyByCanonicalTarget.get(canonical.canonicalTargetId());
    if (priorFamily != null && !priorFamily.equals(familyId)) {
      record(
          "BOTTLENECK_FAMILY_COLLISION_QUARANTINED",
          canonical.canonicalTargetId(),
          Map.of("existing_family_id", priorFamily, "candidate_family_id", familyId));
      return bottleneckFamilies.get(priorFamily);
    }
    Set<String> members = new LinkedHashSet<>(current.canonicalTargetIds());
    members.add(canonical.canonicalTargetId());
    Map<String, BottleneckRelationType> relations =
        new LinkedHashMap<>(current.memberRelations());
    relations.putIfAbsent(canonical.canonicalTargetId(), context.bottleneckRelation());
    String representative = selectFamilyRepresentative(members);
    BottleneckFamilyRecord updated =
        new BottleneckFamilyRecord(
            current.familyId(),
            current.problemHash(),
            current.label(),
            representative,
            members,
            relations,
            current.schedulingState(),
            current.source(),
            current.version() + (members.size() == current.canonicalTargetIds().size() ? 0 : 1));
    bottleneckFamilies.put(familyId, updated);
    familyByCanonicalTarget.put(canonical.canonicalTargetId(), familyId);
    return updated;
  }

  private Optional<CanonicalObligationRecord> possibleEquivalent(
      ObligationSemanticSignature signature) {
    return canonicalTargets.values().stream()
        .filter(target -> ObligationSemanticSignature.sameContext(target.signature(), signature))
        .filter(
            target ->
                MathTextSimilarity.statementSimilarity(
                        target.signature().normalizedStatement(), signature.normalizedStatement())
                    >= 0.72d)
        .min(Comparator.comparing(CanonicalObligationRecord::canonicalTargetId));
  }

  private String selectFamilyRepresentative(Set<String> members) {
    return members.stream()
        .sorted(
            Comparator.<String>comparingInt(
                    id -> requireCanonical(id).routeIds().size())
                .reversed()
                .thenComparing(
                    id -> canonicalCentrality.getOrDefault(id, 0.0d),
                    Comparator.reverseOrder())
                .thenComparing(
                    id -> canonicalPriority.getOrDefault(id, 0.0d),
                    Comparator.reverseOrder())
                .thenComparingInt(
                    id -> canonicalRepresentativeStatements.getOrDefault(id, "").length())
                .thenComparing(Comparator.naturalOrder()))
        .findFirst()
        .orElseThrow();
  }

  private boolean isBetterRepresentative(
      String canonicalTargetId,
      ProofObligation candidate,
      ObligationOccurrenceRecord occurrence) {
    double centrality = canonicalCentrality.getOrDefault(canonicalTargetId, 0.0d);
    int comparison = Double.compare(candidate.centrality(), centrality);
    if (comparison != 0) {
      return comparison > 0;
    }
    comparison =
        Double.compare(candidate.priority(), canonicalPriority.getOrDefault(canonicalTargetId, 0.0d));
    if (comparison != 0) {
      return comparison > 0;
    }
    String current = canonicalRepresentativeStatements.getOrDefault(canonicalTargetId, "");
    if (candidate.statement().length() != current.length()) {
      return candidate.statement().length() < current.length();
    }
    CanonicalObligationRecord canonical = requireCanonical(canonicalTargetId);
    return occurrence.occurrenceId().compareTo(canonical.representativeOccurrenceId()) < 0;
  }

  private static CanonicalObligationSchedulingState canonicalSchedulingState(
      List<ObligationOccurrenceRecord> members) {
    if (members.stream()
        .anyMatch(
            item -> item.schedulingState() == ObligationOccurrenceSchedulingState.ACTIVE)) {
      return CanonicalObligationSchedulingState.ACTIVE;
    }
    if (members.stream()
        .anyMatch(
            item ->
                item.schedulingState()
                    == ObligationOccurrenceSchedulingState.DEFERRED_FOCUSED_RECOVERY)) {
      return CanonicalObligationSchedulingState.DEFERRED_FOCUSED_RECOVERY;
    }
    if (members.stream()
        .anyMatch(
            item ->
                item.schedulingState()
                    == ObligationOccurrenceSchedulingState.DEFERRED_CAPACITY)) {
      return CanonicalObligationSchedulingState.DEFERRED_CAPACITY;
    }
    return CanonicalObligationSchedulingState.RETIRED;
  }

  private CanonicalObligationRecord requireCanonical(String canonicalTargetId) {
    CanonicalObligationRecord result = canonicalTargets.get(canonicalTargetId);
    if (result == null) {
      throw new IllegalArgumentException("unknown canonical obligation target: " + canonicalTargetId);
    }
    return result;
  }

  private void record(String code, String subjectId, Map<String, String> details) {
    audit.add(
        new ObligationCanonicalizationAuditEvent(
            audit.size() + 1L, code, subjectId, details));
  }

  private static String occurrenceId(
      ProofObligation obligation,
      ObligationCreationContext context,
      String dependencyPlanSignature) {
    return "occurrence_"
        + CanonicalJson.stableHash(
            Map.of(
                "problem_hash", obligation.problemHash(),
                "obligation_id", obligation.obligationId(),
                "route_id", context.routeId(),
                "source_type", context.sourceType().name(),
                "source_artifact_ref", context.sourceArtifactRef(),
                "dependency_plan_signature", dependencyPlanSignature));
  }

  private static String taskLeaseKey(
      ProofTaskScope scope, String scopeId, String actionKey) {
    String normalizedScopeId = require(scopeId, "scopeId");
    String normalizedAction = require(actionKey, "actionKey");
    return CanonicalJson.stableHash(
        Map.of(
            "scope", scope.name(),
            "scope_id", normalizedScopeId,
            "action_key", normalize(normalizedAction)));
  }

  private static String exactStatement(ProofObligation obligation) {
    return ObligationSemanticSignature.normalizeMath(obligation.normalizedStatement(), Map.of());
  }

  private static Set<String> nonBlankSet(String preferred, List<String> values) {
    Set<String> result = new LinkedHashSet<>();
    if (preferred != null && !preferred.isBlank()) {
      result.add(preferred.strip());
    }
    values.stream().filter(value -> value != null && !value.isBlank()).map(String::strip).forEach(result::add);
    return Set.copyOf(result);
  }

  private static String normalize(String value) {
    return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
        .toLowerCase(Locale.ROOT)
        .strip()
        .replaceAll("\\s+", " ");
  }

  private static String require(String value, String field) {
    String normalized = value == null ? "" : value.strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return normalized;
  }
}
