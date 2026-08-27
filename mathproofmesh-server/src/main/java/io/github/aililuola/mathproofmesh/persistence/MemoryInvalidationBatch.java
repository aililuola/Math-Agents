package io.github.aililuola.mathproofmesh.persistence;

import java.util.List;

public record MemoryInvalidationBatch(
    String batchId,
    String counterexampleMessageId,
    List<String> invalidatedMemoryIds,
    List<String> reopenedObligationIds,
    boolean replay) {

  public MemoryInvalidationBatch {
    if (batchId == null || batchId.isBlank()) {
      throw new IllegalArgumentException("batchId is required");
    }
    if (counterexampleMessageId == null || counterexampleMessageId.isBlank()) {
      throw new IllegalArgumentException("counterexampleMessageId is required");
    }
    invalidatedMemoryIds = List.copyOf(invalidatedMemoryIds);
    reopenedObligationIds = List.copyOf(reopenedObligationIds);
  }
}
