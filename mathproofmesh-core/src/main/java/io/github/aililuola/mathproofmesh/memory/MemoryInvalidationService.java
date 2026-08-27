package io.github.aililuola.mathproofmesh.memory;

import io.github.aililuola.mathproofmesh.contract.EvidenceType;
import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class MemoryInvalidationService {

  public List<String> applyCounterexample(
      MessageEnvelope counterexample, TypedMemory memory) {
    java.util.Objects.requireNonNull(counterexample, "counterexample");
    java.util.Objects.requireNonNull(memory, "memory");
    if (counterexample.evidenceType() != EvidenceType.COUNTEREXAMPLE) {
      throw new IllegalArgumentException("memory invalidation requires a counterexample");
    }
    List<String> previous = memory.counterexampleBatch(counterexample.messageId());
    if (!previous.isEmpty()) {
      return previous;
    }

    Set<String> direct = new LinkedHashSet<>();
    Set<String> targetTexts = new LinkedHashSet<>();
    targetTexts.add(normalize(counterexample.normalizedStatement()));
    targetTexts.add(normalize(counterexample.conclusion()));
    targetTexts.remove("");
    Set<String> explicitDependencies = Set.copyOf(counterexample.dependencies());
    for (MessageEnvelope fact : memory.facts()) {
      Set<String> factTexts = new LinkedHashSet<>();
      factTexts.add(normalize(fact.normalizedStatement()));
      factTexts.add(normalize(fact.conclusion()));
      boolean dependencyMatch =
          explicitDependencies.contains(fact.messageId())
              || explicitDependencies.contains(fact.contentHash());
      boolean statementMatch =
          targetTexts.stream()
              .anyMatch(
                  target ->
                      factTexts.stream()
                          .filter(text -> !text.isEmpty())
                          .anyMatch(
                              factText ->
                                  target.equals(factText)
                                      || target.contains(factText)
                                      || factText.contains(target)));
      if (dependencyMatch || statementMatch) {
        direct.add(fact.messageId());
      }
    }

    Map<String, Set<String>> reverse = new LinkedHashMap<>();
    for (MessageEnvelope fact : memory.facts()) {
      for (String dependency : fact.dependencies()) {
        String dependencyId = memory.resolveFactReference(dependency);
        if (dependencyId != null) {
          reverse.computeIfAbsent(dependencyId, ignored -> new LinkedHashSet<>())
              .add(fact.messageId());
        }
      }
    }
    Deque<String> pending = new ArrayDeque<>(direct);
    List<String> invalidated = new ArrayList<>();
    while (!pending.isEmpty()) {
      String current = pending.removeFirst();
      if (invalidated.contains(current)) {
        continue;
      }
      if (memory.demoteForInvalidation(
          current, "counterexample:" + counterexample.messageId())) {
        invalidated.add(current);
      }
      pending.addAll(reverse.getOrDefault(current, Set.of()));
    }
    memory.recordCounterexampleBatch(counterexample.messageId(), invalidated);
    return List.copyOf(invalidated);
  }

  public List<String> invalidate(
      Collection<String> itemIds, String reason, TypedMemory memory) {
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("invalidation reason is required");
    }
    List<String> invalidated = new ArrayList<>();
    for (String itemId : itemIds) {
      if (memory.demoteForInvalidation(itemId, reason)) {
        invalidated.add(itemId);
      }
    }
    return List.copyOf(invalidated);
  }

  private static String normalize(String value) {
    if (value == null) {
      return "";
    }
    StringBuilder folded = new StringBuilder(value.length());
    value.codePoints()
        .map(codePoint -> codePoint >= 'A' && codePoint <= 'Z' ? codePoint + 32 : codePoint)
        .forEach(folded::appendCodePoint);
    return String.join(" ", folded.toString().trim().split("\\s+"));
  }
}
