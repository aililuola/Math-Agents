package io.github.aililuola.mathproofmesh.memory;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.aililuola.mathproofmesh.contract.ClaimStatus;
import io.github.aililuola.mathproofmesh.contract.MemoryTier;
import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@SuppressFBWarnings(
    value = "USO_UNSAFE_METHOD_SYNCHRONIZATION",
    justification =
        "The public monitor is the intentional single lock for each mutable in-memory projection.")
public final class TypedMemory {
  private final MemoryPolicy policy;
  private final MemoryPromotionPolicy promotionPolicy;
  private final MemoryInvalidationService invalidationService;
  private final Map<String, MessageEnvelope> messages = new LinkedHashMap<>();
  private final Map<String, MemoryTier> tiers = new LinkedHashMap<>();
  private final Map<String, String> contentIndex = new LinkedHashMap<>();
  private final Map<String, LinkedHashSet<String>> provenance = new LinkedHashMap<>();
  private final Map<String, String> invalidations = new LinkedHashMap<>();
  private final Map<String, List<String>> counterexampleBatches = new LinkedHashMap<>();
  private final Map<String, Long> versions = new LinkedHashMap<>();
  private final List<MemoryAuditEvent> audit = new ArrayList<>();

  public TypedMemory() {
    this(MemoryPolicy.defaults());
  }

  public TypedMemory(MemoryPolicy policy) {
    this.policy = java.util.Objects.requireNonNull(policy, "policy");
    promotionPolicy = new MemoryPromotionPolicy(policy);
    invalidationService = new MemoryInvalidationService();
  }

  public synchronized MessageEnvelope addMessage(
      MessageEnvelope message, String refereeAgentId) {
    String existingId = contentIndex.get(message.contentHash());
    if (existingId != null) {
      provenance
          .computeIfAbsent(existingId, ignored -> new LinkedHashSet<>())
          .add(message.sourceAgentId());
      record(
          "memory_duplicate_merged",
          existingId,
          Map.of("duplicate_id", message.messageId()));
      return messages.get(existingId);
    }
    return switch (message.memoryTier()) {
      case FACT -> addFact(message, refereeAgentId);
      case INSIGHT -> addInsight(message);
      case NEGATIVE -> addNegative(message, "");
    };
  }

  public synchronized MessageEnvelope addMessage(MessageEnvelope message) {
    return addMessage(message, null);
  }

  public synchronized MessageEnvelope addInsight(MessageEnvelope message) {
    requireTier(message, MemoryTier.INSIGHT, "addInsight");
    return store(message, MemoryTier.INSIGHT, "insight_added");
  }

  public synchronized MessageEnvelope addNegative(
      MessageEnvelope message, String reason) {
    requireTier(message, MemoryTier.NEGATIVE, "addNegative");
    MessageEnvelope stored = store(message, MemoryTier.NEGATIVE, "negative_added");
    if (reason != null && !reason.isBlank()) {
      invalidations.put(stored.messageId(), reason);
    }
    return stored;
  }

  public synchronized MessageEnvelope addNegative(MessageEnvelope message) {
    return addNegative(message, "");
  }

  public synchronized MessageEnvelope addFact(
      MessageEnvelope message, String refereeAgentId) {
    requireTier(message, MemoryTier.FACT, "addFact");
    if (message.verificationStatus() != ClaimStatus.VERIFIED) {
      throw new IllegalArgumentException("facts must be independently verified");
    }
    promotionPolicy.validate(
        message,
        refereeAgentId,
        message.verificationConfidence(),
        facts(),
        negatives());
    return store(message, MemoryTier.FACT, "fact_promoted");
  }

  public synchronized MessageEnvelope promote(
      String messageId, String refereeAgentId, double confidence) {
    MessageEnvelope candidate = requireMessage(messageId);
    promotionPolicy.validate(
        candidate, refereeAgentId, confidence, facts(), negatives());
    MessageEnvelope promoted = MemoryEnvelopeTransitions.toFact(candidate, confidence);
    replace(candidate, promoted);
    record(
        "fact_promoted",
        messageId,
        Map.of(
            "referee_agent_id", refereeAgentId,
            "verification_confidence", Double.toString(confidence)));
    return promoted;
  }

  public synchronized MessageEnvelope promote(
      String messageId, String refereeAgentId) {
    return promote(
        messageId,
        refereeAgentId,
        requireMessage(messageId).verificationConfidence());
  }

  public synchronized List<String> applyCounterexample(
      MessageEnvelope counterexample) {
    if (!messages.containsKey(counterexample.messageId())) {
      addNegative(counterexample, "counterexample");
    }
    return invalidationService.applyCounterexample(counterexample, this);
  }

  public synchronized List<String> invalidate(
      Collection<String> itemIds, String reason) {
    return invalidationService.invalidate(itemIds, reason, this);
  }

  public synchronized List<MessageEnvelope> facts() {
    return byTier(MemoryTier.FACT);
  }

  public synchronized List<MessageEnvelope> insights() {
    return byTier(MemoryTier.INSIGHT);
  }

  public synchronized List<MessageEnvelope> negatives() {
    return byTier(MemoryTier.NEGATIVE);
  }

  public synchronized List<MessageEnvelope> factsForRoute(
      String routeId, int maxItems) {
    return forRoute(facts(), routeId, maxItems, false);
  }

  public synchronized List<MessageEnvelope> insightsForRoute(
      String routeId, int maxItems) {
    return forRoute(insights(), routeId, maxItems, false);
  }

  public synchronized List<MessageEnvelope> negativesForRoute(
      String routeId, int maxItems) {
    return forRoute(negatives(), routeId, maxItems, true);
  }

  public synchronized List<MessageEnvelope> factsForRoute(String routeId) {
    return factsForRoute(routeId, policy.maxFactContext());
  }

  public synchronized List<MessageEnvelope> insightsForRoute(String routeId) {
    return insightsForRoute(routeId, policy.maxInsightContext());
  }

  public synchronized List<MessageEnvelope> negativesForRoute(String routeId) {
    return negativesForRoute(routeId, policy.maxNegativeContext());
  }

  public synchronized Optional<MessageEnvelope> find(String itemId) {
    return Optional.ofNullable(messages.get(itemId));
  }

  public synchronized MemoryTier tier(String itemId) {
    MemoryTier tier = tiers.get(itemId);
    if (tier == null) {
      throw new IllegalArgumentException("unknown memory item: " + itemId);
    }
    return tier;
  }

  public synchronized List<String> provenance(String itemId) {
    return List.copyOf(provenance.getOrDefault(itemId, new LinkedHashSet<>()));
  }

  public synchronized Optional<String> invalidationReason(String itemId) {
    return Optional.ofNullable(invalidations.get(itemId));
  }

  public synchronized long version(String itemId) {
    return versions.getOrDefault(itemId, 0L);
  }

  public synchronized List<MemoryAuditEvent> audit() {
    return List.copyOf(audit);
  }

  public synchronized TypedMemorySnapshot snapshot() {
    Map<String, List<String>> provenanceSnapshot = new LinkedHashMap<>();
    provenance.forEach(
        (key, value) -> provenanceSnapshot.put(key, List.copyOf(value)));
    return new TypedMemorySnapshot(
        messages,
        tiers,
        contentIndex,
        provenanceSnapshot,
        invalidations,
        counterexampleBatches,
        versions,
        audit);
  }

  public static TypedMemory restore(
      TypedMemorySnapshot snapshot, MemoryPolicy policy) {
    TypedMemory memory = new TypedMemory(policy);
    synchronized (memory) {
      memory.messages.putAll(snapshot.messages());
      memory.tiers.putAll(snapshot.tiers());
      memory.contentIndex.putAll(snapshot.contentIndex());
      snapshot.provenance().forEach(
          (key, values) ->
              memory.provenance.put(key, new LinkedHashSet<>(values)));
      memory.invalidations.putAll(snapshot.invalidations());
      memory.counterexampleBatches.putAll(snapshot.counterexampleBatches());
      memory.versions.putAll(snapshot.versions());
      memory.audit.addAll(snapshot.audit());
    }
    return memory;
  }

  synchronized List<String> counterexampleBatch(String counterexampleId) {
    return counterexampleBatches.getOrDefault(counterexampleId, List.of());
  }

  synchronized void recordCounterexampleBatch(
      String counterexampleId, List<String> invalidatedIds) {
    List<String> stable = List.copyOf(invalidatedIds);
    counterexampleBatches.put(counterexampleId, stable);
    record(
        "typed_memory_invalidated",
        counterexampleId,
        Map.of("item_ids", String.join(",", stable)));
  }

  synchronized boolean demoteForInvalidation(String itemId, String reason) {
    MessageEnvelope current = messages.get(itemId);
    if (current == null || tiers.get(itemId) != MemoryTier.FACT) {
      return false;
    }
    MessageEnvelope demoted = MemoryEnvelopeTransitions.toNegative(current);
    replace(current, demoted);
    invalidations.put(itemId, reason);
    record("fact_demoted", itemId, Map.of("reason", reason, "to_tier", "negative"));
    return true;
  }

  synchronized String resolveFactReference(String reference) {
    if (tiers.get(reference) == MemoryTier.FACT) {
      return reference;
    }
    String byHash = contentIndex.get(reference);
    return byHash != null && tiers.get(byHash) == MemoryTier.FACT ? byHash : null;
  }

  private MessageEnvelope store(
      MessageEnvelope message, MemoryTier tier, String eventType) {
    MessageEnvelope previous = messages.put(message.messageId(), message);
    if (previous != null) {
      contentIndex.remove(previous.contentHash(), previous.messageId());
    }
    tiers.put(message.messageId(), tier);
    contentIndex.put(message.contentHash(), message.messageId());
    provenance
        .computeIfAbsent(message.messageId(), ignored -> new LinkedHashSet<>())
        .add(message.sourceAgentId());
    invalidations.remove(message.messageId());
    versions.put(message.messageId(), versions.getOrDefault(message.messageId(), -1L) + 1L);
    record(eventType, message.messageId(), Map.of("content_hash", message.contentHash()));
    return message;
  }

  private void replace(MessageEnvelope previous, MessageEnvelope replacement) {
    messages.put(replacement.messageId(), replacement);
    contentIndex.remove(previous.contentHash(), previous.messageId());
    contentIndex.put(replacement.contentHash(), replacement.messageId());
    tiers.put(replacement.messageId(), replacement.memoryTier());
    versions.compute(replacement.messageId(), (key, value) -> value == null ? 0L : value + 1L);
  }

  private void record(String eventType, String itemId, Map<String, String> details) {
    audit.add(
        new MemoryAuditEvent(
            audit.size() + 1L,
            eventType,
            itemId,
            versions.getOrDefault(itemId, 0L),
            details));
  }

  private List<MessageEnvelope> byTier(MemoryTier tier) {
    return tiers.entrySet().stream()
        .filter(entry -> entry.getValue() == tier)
        .map(entry -> messages.get(entry.getKey()))
        .filter(java.util.Objects::nonNull)
        .toList();
  }

  private static List<MessageEnvelope> forRoute(
      List<MessageEnvelope> values,
      String routeId,
      int maxItems,
      boolean global) {
    if (maxItems < 0) {
      throw new IllegalArgumentException("maxItems must be non-negative");
    }
    return values.stream()
        .filter(
            item ->
                global
                    || item.sourceRouteId().equals(routeId)
                    || item.targetRouteIds().contains(routeId))
        .limit(maxItems)
        .toList();
  }

  private MessageEnvelope requireMessage(String messageId) {
    MessageEnvelope message = messages.get(messageId);
    if (message == null) {
      throw new IllegalArgumentException("unknown memory item: " + messageId);
    }
    return message;
  }

  private static void requireTier(
      MessageEnvelope message, MemoryTier expected, String operation) {
    if (message.memoryTier() != expected) {
      throw new IllegalArgumentException(
          operation + " requires memory_tier=" + expected.value());
    }
  }

}
