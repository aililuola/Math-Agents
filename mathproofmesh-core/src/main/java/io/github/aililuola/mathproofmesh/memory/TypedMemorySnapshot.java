package io.github.aililuola.mathproofmesh.memory;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.aililuola.mathproofmesh.contract.MemoryTier;
import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification =
        "The compact constructor converts every collection to an immutable defensive copy.")
public record TypedMemorySnapshot(
    Map<String, MessageEnvelope> messages,
    Map<String, MemoryTier> tiers,
    Map<String, String> contentIndex,
    Map<String, List<String>> provenance,
    Map<String, String> invalidations,
    Map<String, List<String>> counterexampleBatches,
    Map<String, Long> versions,
    List<MemoryAuditEvent> audit,
    NegativeKnowledgeSnapshot negativeKnowledge) {

  public TypedMemorySnapshot {
    messages = copy(messages);
    tiers = copy(tiers);
    contentIndex = copy(contentIndex);
    provenance = copyLists(provenance);
    invalidations = copy(invalidations);
    counterexampleBatches = copyLists(counterexampleBatches);
    versions = copy(versions);
    audit = audit == null ? List.of() : List.copyOf(audit);
    negativeKnowledge =
        negativeKnowledge == null ? NegativeKnowledgeSnapshot.empty() : negativeKnowledge;
  }

  public TypedMemorySnapshot(
      Map<String, MessageEnvelope> messages,
      Map<String, MemoryTier> tiers,
      Map<String, String> contentIndex,
      Map<String, List<String>> provenance,
      Map<String, String> invalidations,
      Map<String, List<String>> counterexampleBatches,
      Map<String, Long> versions,
      List<MemoryAuditEvent> audit) {
    this(
        messages,
        tiers,
        contentIndex,
        provenance,
        invalidations,
        counterexampleBatches,
        versions,
        audit,
        NegativeKnowledgeSnapshot.empty());
  }

  private static <K, V> Map<K, V> copy(Map<K, V> value) {
    return value == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(value));
  }

  private static Map<String, List<String>> copyLists(
      Map<String, List<String>> value) {
    if (value == null) {
      return Map.of();
    }
    Map<String, List<String>> result = new LinkedHashMap<>();
    value.forEach((key, entries) -> result.put(key, List.copyOf(entries)));
    return Map.copyOf(result);
  }
}
