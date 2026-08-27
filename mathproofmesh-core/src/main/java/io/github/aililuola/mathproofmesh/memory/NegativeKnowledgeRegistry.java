package io.github.aililuola.mathproofmesh.memory;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.EvidenceType;
import io.github.aililuola.mathproofmesh.contract.MemoryTier;
import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@SuppressFBWarnings(
    value = "USO_UNSAFE_METHOD_SYNCHRONIZATION",
    justification = "The registry monitor intentionally serializes its in-memory projection.")
public final class NegativeKnowledgeRegistry {
  private static final double POSSIBLE_EQUIVALENCE_THRESHOLD = 0.68d;

  private final Map<String, NegativeKnowledgeRecord> records = new LinkedHashMap<>();
  private final Map<String, String> semanticIndex = new LinkedHashMap<>();
  private final List<NegativeKnowledgeAuditEvent> audit = new ArrayList<>();

  public synchronized NegativeKnowledgeRecord registerTemporaryRejection(
      NegativeKnowledgeCandidate candidate,
      String evidenceMessageId,
      int firstSeenRound,
      int ttlRounds) {
    java.util.Objects.requireNonNull(candidate, "candidate");
    if (firstSeenRound < 0 || ttlRounds < 1) {
      throw new IllegalArgumentException("temporary negative lifetime is invalid");
    }
    return register(
        candidate,
        Set.of(NegativeKnowledgeKind.TEMPORARY_HYPOTHESIS_REJECTION),
        List.of(),
        evidenceMessageId,
        firstSeenRound,
        firstSeenRound + ttlRounds);
  }

  public synchronized NegativeKnowledgeRecord registerDeterministicGuardrail(
      String problemHash, DeterministicNegativeSeed seed, int firstSeenRound) {
    java.util.Objects.requireNonNull(seed, "seed");
    NegativeKnowledgeCandidate candidate =
        new NegativeKnowledgeCandidate(
            problemHash,
            seed.targetType(),
            seed.statement(),
            "",
            seed.assumptions(),
            seed.quantifiers(),
            seed.variableBindings(),
            seed.scopeLimitations(),
            seed.polarity(),
            NegativeKnowledgeSurface.STRATEGY_ADMISSION,
            NegativeCandidateIntent.AUDIT_ONLY);
    return register(
        candidate,
        Set.of(NegativeKnowledgeKind.DETERMINISTIC_GUARDRAIL),
        seed.trustedAliases(),
        "deterministic://greedy-gcd-guardrails/" + seed.seedId(),
        firstSeenRound,
        null);
  }

  public synchronized NegativeKnowledgeRecord registerVerifiedCounterexample(
      MessageEnvelope counterexample, VerifiedCounterexampleAuthority authority) {
    requireVerifiedCounterexample(counterexample, authority);
    NegativeKnowledgeCandidate candidate = candidateFromMessage(
        counterexample,
        NegativeKnowledgeSurface.PROOF_OBLIGATION_CREATION,
        NegativeCandidateIntent.AUDIT_ONLY,
        authority.targetStatement());
    return register(
        candidate,
        Set.of(NegativeKnowledgeKind.VERIFIED_COUNTEREXAMPLE),
        authority.trustedAliases(),
        counterexample.messageId(),
        counterexample.roundCreated(),
        null);
  }

  public synchronized NegativeKnowledgeDecision decide(
      NegativeKnowledgeCandidate candidate, int currentRound) {
    java.util.Objects.requireNonNull(candidate, "candidate");
    if (currentRound < 0) {
      throw new IllegalArgumentException("currentRound must be non-negative");
    }

    Match exact = exactMatch(candidate, currentRound);
    if (exact != null) {
      return decision(candidate, currentRound, exact.record(), exact.strength());
    }
    Match possible = possibleMatch(candidate, currentRound);
    if (possible != null) {
      NegativeKnowledgeDecision decision =
          new NegativeKnowledgeDecision(
              NegativeKnowledgeDecisionCode.QUARANTINE_POSSIBLE_EQUIVALENT,
              NegativeMatchStrength.POSSIBLE_EQUIVALENT,
              candidate.surface(),
              candidate.intent(),
              List.of(possible.record().negativeId()),
              "POSSIBLE_EQUIVALENT_REQUIRES_TRUSTED_REVIEW");
      recordDecision(candidate, currentRound, decision);
      return decision;
    }
    NegativeKnowledgeDecision decision =
        new NegativeKnowledgeDecision(
            NegativeKnowledgeDecisionCode.ALLOW,
            NegativeMatchStrength.NONE,
            candidate.surface(),
            candidate.intent(),
            List.of(),
            "NO_ACTIVE_NEGATIVE_MATCH");
    recordDecision(candidate, currentRound, decision);
    return decision;
  }

  public synchronized List<NegativeKnowledgeRecord> records() {
    return List.copyOf(records.values());
  }

  public synchronized List<NegativeKnowledgeAuditEvent> audit() {
    return List.copyOf(audit);
  }

  public synchronized NegativeKnowledgeSnapshot snapshot() {
    return new NegativeKnowledgeSnapshot(
        NegativeKnowledgeSnapshot.CURRENT_SCHEMA_VERSION, records(), audit());
  }

  public synchronized String registryHash() {
    return CanonicalJson.stableHash(records());
  }

  public static NegativeKnowledgeRegistry restore(NegativeKnowledgeSnapshot snapshot) {
    NegativeKnowledgeRegistry registry = new NegativeKnowledgeRegistry();
    if (snapshot == null) {
      return registry;
    }
    synchronized (registry) {
      for (NegativeKnowledgeRecord record : snapshot.records()) {
        NegativeKnowledgeRecord restored =
            snapshot.schemaVersion() < NegativeKnowledgeSnapshot.CURRENT_SCHEMA_VERSION
                ? migrateLegacyRecord(record)
                : record;
        registry.records.put(restored.primarySemanticKey(), restored);
        registry.index(restored);
      }
      registry.audit.addAll(snapshot.audit());
    }
    return registry;
  }

  static NegativeKnowledgeCandidate candidateFromMessage(
      MessageEnvelope message,
      NegativeKnowledgeSurface surface,
      NegativeCandidateIntent intent) {
    return candidateFromMessage(message, surface, intent, message.normalizedStatement());
  }

  private static NegativeKnowledgeCandidate candidateFromMessage(
      MessageEnvelope message,
      NegativeKnowledgeSurface surface,
      NegativeCandidateIntent intent,
      String statement) {
    return new NegativeKnowledgeCandidate(
        message.problemHash(),
        NegativeKnowledgeTargetType.CLAIM,
        statement,
        message.normalizedStatement(),
        message.assumptions(),
        message.quantifiers(),
        message.variableBindings(),
        message.scopeLimitations(),
        message.polarity(),
        surface,
        intent);
  }

  private NegativeKnowledgeRecord register(
      NegativeKnowledgeCandidate candidate,
      Set<NegativeKnowledgeKind> incomingKinds,
      List<String> trustedAliases,
      String evidenceMessageId,
      int firstSeenRound,
      Integer expiresAfterRound) {
    if (evidenceMessageId == null || evidenceMessageId.isBlank()) {
      throw new IllegalArgumentException("evidenceMessageId is required");
    }
    List<String> aliasStatements =
        trustedAliases == null
            ? List.of()
            : trustedAliases.stream()
                .filter(java.util.Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    List<String> aliasKeys =
        aliasStatements.stream().map(alias -> semanticKey(candidate, alias)).distinct().toList();
    String existingPrimary = findExistingPrimary(candidate.semanticKey(), aliasKeys);
    NegativeKnowledgeRecord existing =
        existingPrimary == null ? null : records.get(existingPrimary);
    if (existing == null) {
      boolean permanent = incomingKinds.stream().anyMatch(NegativeKnowledgeKind::permanent);
      NegativeKnowledgeRecord created =
          new NegativeKnowledgeRecord(
              "negative-" + candidate.semanticKey().substring(0, 20),
              candidate.problemHash(),
              candidate.targetType(),
              candidate.semanticKey(),
              aliasKeys,
              aliasStatements,
              incomingKinds,
              candidate.statement(),
              candidate.normalizedStatement(),
              candidate.assumptions(),
              candidate.quantifiers(),
              candidate.variableBindings(),
              candidate.scopeLimitations(),
              candidate.polarity(),
              List.of(evidenceMessageId),
              firstSeenRound,
              permanent ? null : expiresAfterRound,
              1L);
      records.put(created.primarySemanticKey(), created);
      index(created);
      return created;
    }

    Set<NegativeKnowledgeKind> kinds = EnumSet.copyOf(existing.kinds());
    kinds.addAll(incomingKinds);
    boolean permanent = kinds.stream().anyMatch(NegativeKnowledgeKind::permanent);
    LinkedHashSet<String> mergedAliasKeys = new LinkedHashSet<>(existing.trustedAliasKeys());
    LinkedHashSet<String> mergedAliasStatements =
        new LinkedHashSet<>(existing.trustedAliasStatements());
    if (!candidate.semanticKey().equals(existing.primarySemanticKey())) {
      mergedAliasKeys.add(candidate.semanticKey());
      mergedAliasStatements.add(candidate.statement());
    }
    mergedAliasKeys.addAll(aliasKeys);
    mergedAliasStatements.addAll(aliasStatements);
    LinkedHashSet<String> evidence = new LinkedHashSet<>(existing.evidenceMessageIds());
    evidence.add(evidenceMessageId);
    Integer mergedExpiry =
        permanent
            ? null
            : Math.max(existing.expiresAfterRound(), expiresAfterRound);
    NegativeKnowledgeRecord merged =
        new NegativeKnowledgeRecord(
            existing.negativeId(),
            existing.problemHash(),
            existing.targetType(),
            existing.primarySemanticKey(),
            List.copyOf(mergedAliasKeys),
            List.copyOf(mergedAliasStatements),
            kinds,
            existing.statement(),
            existing.normalizedStatement(),
            existing.assumptions(),
            existing.quantifiers(),
            existing.variableBindings(),
            existing.scopeLimitations(),
            existing.polarity(),
            List.copyOf(evidence),
            Math.min(existing.firstSeenRound(), firstSeenRound),
            mergedExpiry,
            existing.version() + 1L);
    records.put(existing.primarySemanticKey(), merged);
    index(merged);
    return merged;
  }

  private String findExistingPrimary(String primaryKey, Collection<String> aliasKeys) {
    String direct = semanticIndex.get(primaryKey);
    if (direct != null) {
      return direct;
    }
    for (String aliasKey : aliasKeys) {
      String matched = semanticIndex.get(aliasKey);
      if (matched != null) {
        return matched;
      }
    }
    return null;
  }

  private void index(NegativeKnowledgeRecord record) {
    semanticIndex.put(record.primarySemanticKey(), record.primarySemanticKey());
    record.trustedAliasKeys()
        .forEach(alias -> semanticIndex.put(alias, record.primarySemanticKey()));
  }

  private Match exactMatch(NegativeKnowledgeCandidate candidate, int currentRound) {
    String primary = semanticIndex.get(candidate.semanticKey());
    if (primary == null) {
      return null;
    }
    NegativeKnowledgeRecord record = records.get(primary);
    if (record == null || !record.activeAt(currentRound)) {
      return null;
    }
    NegativeMatchStrength strength =
        record.primarySemanticKey().equals(candidate.semanticKey())
            ? NegativeMatchStrength.EXACT
            : NegativeMatchStrength.TRUSTED_ALIAS;
    return new Match(record, strength);
  }

  private Match possibleMatch(NegativeKnowledgeCandidate candidate, int currentRound) {
    for (NegativeKnowledgeRecord record : records.values()) {
      if (!record.activeAt(currentRound) || !possibleContextMatch(candidate, record)) {
        continue;
      }
      if (possiblyEquivalent(candidate.normalizedStatement(), record.normalizedStatement())
          || record.trustedAliasStatements().stream()
              .map(NegativeKnowledgeSemanticKey::normalizeStatement)
              .anyMatch(alias -> possiblyEquivalent(candidate.normalizedStatement(), alias))) {
        return new Match(record, NegativeMatchStrength.POSSIBLE_EQUIVALENT);
      }
    }
    return null;
  }

  private static boolean possiblyEquivalent(String left, String right) {
    String normalizedLeft = NegativeKnowledgeSemanticKey.normalizeStatement(left);
    String normalizedRight = NegativeKnowledgeSemanticKey.normalizeStatement(right);
    String phraseLeft = normalizedLeft.replaceAll("[\\p{Punct}]+", " ").replaceAll("\\s+", " ").trim();
    String phraseRight = normalizedRight.replaceAll("[\\p{Punct}]+", " ").replaceAll("\\s+", " ").trim();
    boolean meaningfulContainment =
        Math.min(phraseLeft.length(), phraseRight.length()) >= 12
            && (phraseLeft.contains(phraseRight) || phraseRight.contains(phraseLeft));
    return meaningfulContainment
        || NegativeKnowledgeSemanticKey.similarity(normalizedLeft, normalizedRight)
            >= POSSIBLE_EQUIVALENCE_THRESHOLD;
  }

  private NegativeKnowledgeDecision decision(
      NegativeKnowledgeCandidate candidate,
      int currentRound,
      NegativeKnowledgeRecord record,
      NegativeMatchStrength strength) {
    NegativeKnowledgeDecisionCode code;
    if (candidate.intent() == NegativeCandidateIntent.FALSIFICATION_ONLY) {
      code = NegativeKnowledgeDecisionCode.ALLOW_FALSIFICATION_ONLY;
    } else if (record.permanent()) {
      code = NegativeKnowledgeDecisionCode.BLOCK_PERMANENT;
    } else {
      code = NegativeKnowledgeDecisionCode.BLOCK_TEMPORARY;
    }
    NegativeKnowledgeDecision result =
        new NegativeKnowledgeDecision(
            code,
            strength,
            candidate.surface(),
            candidate.intent(),
            List.of(record.negativeId()),
            "known counterexample or negative knowledge: "
                + code.name()
                + ": "
                + record.negativeId());
    recordDecision(candidate, currentRound, result);
    return result;
  }

  private void recordDecision(
      NegativeKnowledgeCandidate candidate,
      int currentRound,
      NegativeKnowledgeDecision decision) {
    audit.add(
        new NegativeKnowledgeAuditEvent(
            audit.size() + 1L,
            currentRound,
            candidate.surface(),
            candidate.intent(),
            decision.code(),
            decision.matchStrength(),
            candidate.semanticKey(),
            decision.matchedNegativeIds().isEmpty()
                ? ""
                : decision.matchedNegativeIds().getFirst(),
            decision.detail()));
  }

  private static String semanticKey(NegativeKnowledgeCandidate candidate, String statement) {
    return NegativeKnowledgeSemanticKey.semanticKey(
        candidate.problemHash(),
        candidate.targetType(),
        statement,
        candidate.assumptions(),
        candidate.quantifiers(),
        candidate.variableBindings(),
        candidate.scopeLimitations(),
        candidate.polarity());
  }

  private static boolean possibleContextMatch(
      NegativeKnowledgeCandidate candidate, NegativeKnowledgeRecord record) {
    if (record.contextKey().equals(candidate.contextKey())) {
      return true;
    }
    boolean legacyPolarity =
        NegativeKnowledgeSemanticKey.UNSPECIFIED_POLARITY.equals(candidate.polarity())
            || NegativeKnowledgeSemanticKey.UNSPECIFIED_POLARITY.equals(record.polarity());
    return legacyPolarity
        && record.contextKeyIgnoringPolarity().equals(candidate.contextKeyIgnoringPolarity());
  }

  private static NegativeKnowledgeRecord migrateLegacyRecord(
      NegativeKnowledgeRecord legacy) {
    NegativeKnowledgeCandidate candidate =
        new NegativeKnowledgeCandidate(
            legacy.problemHash(),
            legacy.targetType(),
            legacy.statement(),
            legacy.normalizedStatement(),
            legacy.assumptions(),
            legacy.quantifiers(),
            legacy.variableBindings(),
            legacy.scopeLimitations(),
            NegativeKnowledgeSemanticKey.UNSPECIFIED_POLARITY,
            NegativeKnowledgeSurface.RESTORE_REVALIDATION,
            NegativeCandidateIntent.AUDIT_ONLY);
    List<String> aliasStatements = legacy.trustedAliasStatements();
    List<String> aliasKeys =
        aliasStatements.stream()
            .map(alias -> semanticKey(candidate, alias))
            .distinct()
            .toList();
    return new NegativeKnowledgeRecord(
        legacy.negativeId(),
        legacy.problemHash(),
        legacy.targetType(),
        candidate.semanticKey(),
        aliasKeys,
        aliasStatements,
        legacy.kinds(),
        legacy.statement(),
        legacy.normalizedStatement(),
        legacy.assumptions(),
        legacy.quantifiers(),
        legacy.variableBindings(),
        legacy.scopeLimitations(),
        NegativeKnowledgeSemanticKey.UNSPECIFIED_POLARITY,
        legacy.evidenceMessageIds(),
        legacy.firstSeenRound(),
        legacy.expiresAfterRound(),
        legacy.version());
  }

  private static void requireVerifiedCounterexample(
      MessageEnvelope counterexample, VerifiedCounterexampleAuthority authority) {
    java.util.Objects.requireNonNull(counterexample, "counterexample");
    java.util.Objects.requireNonNull(authority, "authority");
    if (counterexample.memoryTier() != MemoryTier.NEGATIVE
        || counterexample.evidenceType() != EvidenceType.COUNTEREXAMPLE
        || !authority.trusted()
        || !authority.experimentArtifactRef().contains("://")
        || !counterexample.artifactRefs().contains(authority.experimentArtifactRef())
        || !authority.rawSourceRef().equals(counterexample.rawSourceRef())
        || !NegativeKnowledgeSemanticKey.normalizeStatement(authority.targetStatement())
            .equals(
                NegativeKnowledgeSemanticKey.normalizeStatement(
                    counterexample.normalizedStatement()))) {
      throw new IllegalArgumentException(
          "verified counterexample requires a replayed REFUTED trace, artifact, raw result, and exact target");
    }
  }

  private record Match(NegativeKnowledgeRecord record, NegativeMatchStrength strength) {}
}
