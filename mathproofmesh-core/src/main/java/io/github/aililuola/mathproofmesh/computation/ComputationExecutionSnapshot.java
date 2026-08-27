package io.github.aililuola.mathproofmesh.computation;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.util.Comparator;
import java.util.List;

public record ComputationExecutionSnapshot(
    List<ComputationExecutionRecord> records, String stableHash) {
  public ComputationExecutionSnapshot {
    records =
        records == null
            ? List.of()
            : records.stream()
                .sorted(Comparator.comparing(ComputationExecutionRecord::executionId))
                .toList();
    String expected = CanonicalJson.stableHash(records);
    stableHash = stableHash == null || stableHash.isBlank() ? expected : stableHash.strip();
    if (!ComputationJson.hashesEqual(expected, stableHash)) {
      throw new IllegalArgumentException("computation execution snapshot hash mismatch");
    }
  }

  public static ComputationExecutionSnapshot empty() {
    return new ComputationExecutionSnapshot(List.of(), null);
  }

  @Override
  public List<ComputationExecutionRecord> records() {
    return List.copyOf(records);
  }
}
